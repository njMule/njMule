// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ConfigBackupTest, under its names.

#![allow(clippy::unwrap_used)]

use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};

use njmule_engine::persist::config_backup;
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;

fn configuration_with(root: &Path, files: &[&str]) -> PathBuf {
    let config = root.join("config");
    fs::create_dir_all(&config).unwrap();
    for file in files {
        fs::write(config.join(file), "x").unwrap();
    }
    config
}

fn at(instant: &str) -> OffsetDateTime {
    OffsetDateTime::parse(instant, &Rfc3339).unwrap()
}

fn entries(copy: &Path) -> Vec<String> {
    let mut zip = zip::ZipArchive::new(fs::File::open(copy).unwrap()).unwrap();
    (0..zip.len()).map(|i| zip.by_index(i).unwrap().name().unwrap().into_owned()).collect()
}

fn name(copy: &Path) -> String {
    copy.file_name().unwrap().to_string_lossy().into_owned()
}

#[test]
fn should_copy_the_configuration_and_keep_the_latest_ones() {
    let directory = tempfile::tempdir().unwrap();
    let config = configuration_with(
        directory.path(),
        &["njmule.lock", "handoff", "known2_64.met", "server.met.bak", "known.met.tmp"],
    );
    fs::write(config.join("njmule.properties"), "net.tcpPort=4662\n").unwrap();
    fs::write(config.join("server.met"), [0xE0, 0, 0, 0, 0]).unwrap();
    let folder = directory.path().join("data").join(config_backup::FOLDER);
    let first = config_backup::write(&config, &folder, 2, at("2026-10-13T20:00:00Z")).unwrap();
    config_backup::write(&config, &folder, 2, at("2026-10-14T20:00:00Z")).unwrap();

    let copy = config_backup::write(&config, &folder, 2, at("2026-10-15T20:00:00Z")).unwrap();

    let copies = config_backup::copies(&folder).unwrap();
    assert_eq!(copies.len(), 2);
    assert_eq!(copies.last(), Some(&copy));
    assert!(!first.exists());
    assert_eq!(name(&copy), "njmule-config-20261015-200000.zip");
    assert_eq!(entries(&copy), ["njmule.properties", "server.met"]);
    let mut zip = zip::ZipArchive::new(fs::File::open(&copy).unwrap()).unwrap();
    let mut server_met = Vec::new();
    zip.by_name("server.met").unwrap().read_to_end(&mut server_met).unwrap();
    assert_eq!(server_met, [0xE0, 0, 0, 0, 0]);
}

#[test]
fn should_never_prune_the_copy_just_written() {
    let directory = tempfile::tempdir().unwrap();
    let config = configuration_with(directory.path(), &["njmule.properties"]);
    let folder = directory.path().join(config_backup::FOLDER);
    fs::create_dir_all(&folder).unwrap();
    let foreign = folder.join("njmule-config-mine.zip");
    fs::write(&foreign, "x").unwrap();
    config_backup::write(&config, &folder, 1, at("2026-10-15T20:00:00Z")).unwrap();

    // The clock went back.
    let copy = config_backup::write(&config, &folder, 1, at("2026-10-14T20:00:00Z")).unwrap();

    assert!(copy.exists());
    assert_eq!(config_backup::copies(&folder).unwrap(), [copy]);
    assert!(foreign.exists(), "not one of ours");
}

#[test]
fn should_name_the_copies_in_utc() {
    let directory = tempfile::tempdir().unwrap();
    let config = configuration_with(directory.path(), &[]);

    // 00:30 in UTC, 02:30 in Rome.
    let copy = config_backup::write(&config, &directory.path().join("b"), 3, at("2026-10-25T02:30:00+02:00")).unwrap();

    assert_eq!(name(&copy), "njmule-config-20261025-003000.zip");
}

#[cfg(unix)]
#[test]
fn should_make_the_copies_for_their_owner_only() {
    use std::os::unix::fs::PermissionsExt;
    let directory = tempfile::tempdir().unwrap();
    let config = configuration_with(directory.path(), &[]);

    let copy = config_backup::write(&config, &directory.path().join("b"), 1, at("2026-10-15T20:00:00Z")).unwrap();

    assert_eq!(fs::metadata(&copy).unwrap().permissions().mode() & 0o777, 0o600);
}

#[test]
fn should_keep_at_least_one_copy() {
    let directory = tempfile::tempdir().unwrap();
    let config = configuration_with(directory.path(), &[]);
    let folder = directory.path().join("b");

    let refused = config_backup::write(&config, &folder, 0, at("2026-10-15T20:00:00Z"));

    assert_eq!(refused.unwrap_err().kind(), std::io::ErrorKind::InvalidInput);
    assert!(config_backup::copies(&folder).unwrap().is_empty());
}

#[test]
fn should_leave_the_kad_index_out_of_the_copy() {
    let directory = tempfile::tempdir().unwrap();
    let config = configuration_with(
        directory.path(),
        &["nodes.dat", "key_index.dat", "src_index.dat", "notes_index.dat", "load_index.dat"],
    );

    let copy = config_backup::write(&config, &directory.path().join("b"), 1, at("2026-10-15T20:00:00Z")).unwrap();

    assert_eq!(entries(&copy), ["nodes.dat"]);
}

#[test]
fn should_take_only_the_names_of_its_copies() {
    let directory = tempfile::tempdir().unwrap();
    for name in [
        "njmule-config-20261015-200000.zip",
        "njmule-config-2026101-2000000.zip",
        "njmule-config-20261015-20000a.zip",
        "njmule-config-20261015200000.zip",
        "other-20261015-200000.zip",
        "njmule-config-20261014-200000.zip",
    ] {
        fs::write(directory.path().join(name), "x").unwrap();
    }

    let copies = config_backup::copies(directory.path()).unwrap();

    let names: Vec<String> = copies.iter().map(|copy| name(copy)).collect();
    assert_eq!(names, ["njmule-config-20261014-200000.zip", "njmule-config-20261015-200000.zip"]);
    assert!(config_backup::copies(&directory.path().join("none")).unwrap().is_empty());
}
