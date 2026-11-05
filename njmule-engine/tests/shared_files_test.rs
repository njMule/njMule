// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's SharedFilesTest for the incoming folder; those of shared trees, private folders,
//! renames, counts and other clients' records wait for their ports.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use std::fs;

use njmule_engine::files::shared_files::{self, Found, SharedFiles};

#[test]
fn should_share_the_incoming_directory_and_remember_the_hashes() {
    let incoming = tempfile::tempdir().unwrap();
    let config = tempfile::tempdir().unwrap();
    fs::write(incoming.path().join("song.mp3"), vec![7u8; 10_000]).unwrap();
    let mut shared = SharedFiles::load(config.path());
    let found = shared_files::scan(incoming.path());
    assert!(!shared.take_known(&found[0]));
    let hashed = shared.hashed(found[0].clone(), &shared_files::hash(&found[0]).unwrap());
    shared.save(config.path()).unwrap();

    let mut again = SharedFiles::load(config.path());
    let known = again.take_known(&found[0]);

    assert!(known);
    assert_eq!(again.get(&hashed.hash), Some(&hashed));
    assert!(hashed.aich_hash.is_some());
}

#[test]
fn should_hash_again_a_file_that_changed_since_it_was_hashed() {
    let incoming = tempfile::tempdir().unwrap();
    let config = tempfile::tempdir().unwrap();
    fs::write(incoming.path().join("song.mp3"), vec![7u8; 10_000]).unwrap();
    let found = shared_files::scan(incoming.path()).remove(0);
    let mut shared = SharedFiles::load(config.path());
    shared.hashed(found.clone(), &shared_files::hash(&found).unwrap());
    shared.save(config.path()).unwrap();

    let changed = Found { mtime: found.mtime + 1, ..found };
    let known = SharedFiles::load(config.path()).take_known(&changed);

    assert!(!known);
}

#[test]
fn should_find_the_files_of_a_folder_but_not_its_folders_or_empty_files() {
    let folder = tempfile::tempdir().unwrap();
    fs::write(folder.path().join("b.avi"), b"video").unwrap();
    fs::write(folder.path().join("a.iso"), b"image").unwrap();
    fs::write(folder.path().join("empty.txt"), b"").unwrap();
    fs::create_dir(folder.path().join("inner")).unwrap();
    fs::write(folder.path().join("inner").join("c.mp3"), b"song").unwrap();

    let found = shared_files::scan(folder.path());

    assert_eq!(found.iter().map(|file| file.name.as_str()).collect::<Vec<_>>(), ["a.iso", "b.avi"]);
    assert_eq!(found[0].size, 5);
}
