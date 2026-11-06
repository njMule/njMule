// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What of a file can be sent, and whether its blocks are worth compressing. Beyond the Java engine's examples, which
//! reach Uploadable through the upload manager.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]

use std::path::PathBuf;

use njmule_engine::files::hash::ed2k::PARTSIZE;
use njmule_engine::files::shared_files::SharedFile;
use njmule_engine::upload::uploadable::Uploadable;

fn shared(name: &str) -> Uploadable {
    Uploadable::of(&SharedFile {
        hash: [1; 16],
        name: name.to_owned(),
        size: 3 * PARTSIZE,
        path: PathBuf::from("shared.bin"),
        mtime: 0,
        part_hashes: vec![[2; 16]; 3],
        aich_hash: None,
    })
}

#[test]
fn should_send_any_range_of_a_complete_file_within_its_size() {
    let file = shared("film.mkv");

    assert!(file.is_complete());
    assert!(file.has(0, 3 * PARTSIZE));
    assert!(!file.has(0, 3 * PARTSIZE + 1), "beyond the end");
    assert!(!file.has(10, 10), "an empty range");
}

#[test]
fn should_send_of_a_download_only_the_ranges_of_its_checked_parts() {
    let file = Uploadable { parts: Some(vec![true, false, true]), ..shared("film.mkv") };

    assert!(file.has(0, PARTSIZE));
    assert!(!file.has(PARTSIZE - 10, PARTSIZE + 10), "into the part not checked");
    assert!(file.has(2 * PARTSIZE, 3 * PARTSIZE));
}

#[test]
fn should_compress_no_block_of_a_file_compressed_already() {
    let kinds = ["film.MKV", "notes.txt", "README"].map(|name| shared(name).compressible());

    assert_eq!(kinds, [false, true, true]);
}
