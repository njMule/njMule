// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's UserHashStoreTest, under their names: the hash peers know kept through backups and
//! files another program holds.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use crate::held;

use std::fs;
use std::path::Path;

use njmule_engine::persist::atomic_files;
use njmule_engine::persist::format::preferences_dat;
use njmule_engine::persist::user_hash_store::{self, FILE_NAME};

#[test]
fn should_create_a_marked_hash_once_and_keep_it() {
    let config = tempfile::tempdir().unwrap();

    let created = user_hash_store::load_or_create(config.path(), &mut rand::rng()).unwrap();
    let loaded = user_hash_store::load_or_create(config.path(), &mut rand::rng()).unwrap();

    assert_eq!(loaded, created);
    assert_eq!((created[5], created[14]), (14, 111));
    assert!(user_hash_store::is_usable(&created));
    let file = preferences_dat::read(&fs::read(config.path().join(FILE_NAME)).unwrap()).unwrap();
    assert_eq!(file.user_hash, created);
}

#[test]
fn should_replace_an_unusable_hash() {
    let config = tempfile::tempdir().unwrap();
    let mut zero = vec![0u8; 61];
    zero[0] = preferences_dat::VERSION;
    zero[6] = 14;
    zero[15] = 111;
    fs::write(config.path().join(FILE_NAME), zero).unwrap();

    let hash = user_hash_store::load_or_create(config.path(), &mut rand::rng()).unwrap();

    assert!(user_hash_store::is_usable(&hash));
}

#[test]
fn should_replace_a_corrupt_hash_without_backup() {
    let config = tempfile::tempdir().unwrap();
    fs::write(config.path().join(FILE_NAME), [0u8; 3]).unwrap();

    let hash = user_hash_store::load_or_create(config.path(), &mut rand::rng()).unwrap();

    assert!(user_hash_store::is_usable(&hash));
}

#[test]
fn should_keep_the_hash_of_the_emule_file_with_its_window_placement() {
    let config = tempfile::tempdir().unwrap();
    let mut file = vec![preferences_dat::VERSION];
    file.extend(1..=16u8);
    file.extend([7u8; 44]);
    fs::write(config.path().join(FILE_NAME), &file).unwrap();

    let hash = user_hash_store::load_or_create(config.path(), &mut rand::rng()).unwrap();

    assert_eq!(hash.as_slice(), &file[1..17]);
    assert_eq!(fs::read(config.path().join(FILE_NAME)).unwrap(), file);
}

#[cfg(unix)]
#[test]
fn should_make_the_user_hash_for_its_owner_only() {
    use std::os::unix::fs::PermissionsExt;
    let config = tempfile::tempdir().unwrap();

    user_hash_store::load_or_create(config.path(), &mut rand::rng()).unwrap();

    let mode = fs::metadata(config.path().join(FILE_NAME)).unwrap().permissions().mode();
    assert_eq!(mode & 0o777, 0o600);
}

fn loaded(config: &Path) -> std::io::Result<[u8; 16]> {
    user_hash_store::load_or_create(config, &mut rand::rng())
}

#[test]
fn should_give_way_to_the_backup_of_a_corrupt_file() {
    let config = tempfile::tempdir().unwrap();
    let expected = loaded(config.path()).unwrap();
    let file = config.path().join(FILE_NAME);
    fs::copy(&file, atomic_files::bak_path(&file)).unwrap();
    fs::write(&file, [0u8; 3]).unwrap();

    let hash = loaded(config.path());

    assert_eq!(hash.unwrap(), expected);
}

#[test]
fn should_not_replace_a_user_hash_held_by_another_program() {
    let config = tempfile::tempdir().unwrap();
    loaded(config.path()).unwrap();
    let Some(_held) = held::hold(&config.path().join(FILE_NAME)) else { return };

    let hash = loaded(config.path());

    assert!(hash.is_err());
}

#[test]
fn should_keep_the_user_hash_once_the_other_program_lets_it_go() {
    let config = tempfile::tempdir().unwrap();
    let expected = loaded(config.path()).unwrap();
    {
        let Some(_held) = held::hold(&config.path().join(FILE_NAME)) else { return };
        assert!(loaded(config.path()).is_err());
    }

    let hash = loaded(config.path());

    assert_eq!(hash.unwrap(), expected);
}
