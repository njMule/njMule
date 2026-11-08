// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's UploadSessionTest, under its names: the list of our files a browsing client gets; and a client
//! browsing a running engine.

#![allow(clippy::unwrap_used)]

use crate::free_port;

use std::fs;
use std::net::{Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::time::Duration;

use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::files::hash::ed2k;
use njmule_engine::files::shared_files::SharedFile;
use njmule_engine::net::routes::Routes;
use njmule_engine::peer::shared_files_browser;
use njmule_engine::protocol::ed2k::file_entry::FileEntry;
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::io::PacketReader;
use njmule_engine::upload::upload_session::{self, MAX_SHARED_LIST};
use rand::SeedableRng;
use rand::rngs::StdRng;

fn file(name: &str, size: u64, seed: u32) -> SharedFile {
    let mut hash = [0u8; 16];
    hash[..4].copy_from_slice(&seed.to_le_bytes());
    SharedFile {
        hash,
        name: name.to_owned(),
        size,
        path: PathBuf::from(name),
        mtime: 0,
        part_hashes: Vec::new(),
        aich_hash: None,
    }
}

fn sizes_around_the_old_limit() -> Vec<SharedFile> {
    vec![
        file("small.bin", ed2k::OLD_MAX_FILE_SIZE, 1),
        file("just-above.bin", ed2k::OLD_MAX_FILE_SIZE + 1, 2),
        file("below-4-gib.bin", 0xFFFF_FFFF, 3),
        file("large.bin", 0x1_0000_0000, 4),
    ]
}

fn names(list: &[u8]) -> Vec<String> {
    let mut input = PacketReader::of(list);
    let count = input.u32().unwrap();
    let names = (0..count).map(|_| FileEntry::read(&mut input).unwrap().name().unwrap().to_owned()).collect();
    input.expect_end().unwrap();
    names
}

#[test]
fn should_list_only_files_within_the_old_size_limit_to_a_client_without_large_files() {
    let files = sizes_around_the_old_limit();

    let list = upload_session::listing(&files, false).into_byte_array();

    assert_eq!(names(&list), ["small.bin"]);
}

#[test]
fn should_list_files_above_the_old_size_limit_to_a_client_that_takes_large_files() {
    let files = sizes_around_the_old_limit();

    let list = upload_session::listing(&files, true).into_byte_array();

    assert_eq!(names(&list), ["small.bin", "just-above.bin", "below-4-gib.bin", "large.bin"]);
}

#[test]
fn should_stop_growing_a_list_past_its_limit() {
    let files: Vec<SharedFile> =
        (0..20_000).map(|i| file(&format!("file number {i} with a rather long name.bin"), 1000, i)).collect();

    let list = upload_session::listing(&files, false).into_byte_array();

    assert!((MAX_SHARED_LIST..=MAX_SHARED_LIST + 200).contains(&list.len()), "{}", list.len());
    assert!(names(&list).len() < files.len());
}

// Beyond the Java engine's examples: a client browsing a running engine, as the preferences let it.

async fn engine_sharing(preferences: &str) -> (tempfile::TempDir, Engine) {
    let profile = tempfile::tempdir().unwrap();
    let dirs = AppDirectories::under_root(profile.path()).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    fs::create_dir_all(&dirs.downloads).unwrap();
    fs::write(dirs.downloads.join("shared.txt"), b"a file to share").unwrap();
    // The preferences take no port of 0.
    let port = free_port::free_port();
    fs::write(dirs.config.join("njmule.properties"), format!("net.tcpPort={port}\n{preferences}")).unwrap();
    let engine = Engine::start(dirs).await.unwrap();
    // The incoming folder is hashed after the start.
    for _ in 0..100 {
        if !engine.shared_files().await.is_empty() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    (profile, engine)
}

async fn browse(engine: &Engine) -> std::io::Result<Vec<String>> {
    let ours = Ours { nick: "browser", udp_port: 0, kad_port: 0, misc_options1: 0, misc_options2: 0 };
    let address = SocketAddr::from((Ipv4Addr::LOCALHOST, engine.listen_port()));
    let mut random = StdRng::from_rng(&mut rand::rng());
    let files = shared_files_browser::browse(
        address,
        &Hello::of([7; 16], 0, 4662, &ours, 0, 0),
        &Routes::direct(),
        &mut random,
    )
    .await?;
    Ok(files.into_iter().map(|file| file.name).collect())
}

#[tokio::test]
async fn should_list_our_files_to_any_client_when_everybody_may_see_them() {
    let (_profile, engine) = engine_sharing("share.visibleTo=EVERYBODY\n").await;

    let names = browse(&engine).await.unwrap();

    assert_eq!(names, ["shared.txt"]);
    engine.stop().await;
}

#[tokio::test]
async fn should_list_no_file_when_nobody_may_see_them() {
    let (_profile, engine) = engine_sharing("share.visibleTo=NOBODY\n").await;

    let names = browse(&engine).await.unwrap();

    assert!(names.is_empty());
    engine.stop().await;
}
