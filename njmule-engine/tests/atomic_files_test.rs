// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's AtomicFilesTest, under their names: files replaced whole with a backup, read back
//! from it, and held for a moment by another program. A reader rejecting a file returns an error: Rust has no runtime
//! exceptions to turn into I/O ones.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing)]

use crate::held;

use std::fs;
use std::io::{self, ErrorKind};
use std::path::{Path, PathBuf};

use held::{hold, hold_from_deletion};
use njmule_engine::persist::atomic_files::{self, BackupFailed};
use tempfile::TempDir;

fn file_in(dir: &TempDir, name: &str) -> PathBuf {
    dir.path().join(name)
}

fn written(file: &Path, versions: &[&str]) {
    for version in versions {
        atomic_files::write(file, version.as_bytes()).unwrap();
    }
}

fn content(file: &Path) -> String {
    fs::read_to_string(file).unwrap()
}

fn read_utf8(file: &Path) -> io::Result<String> {
    atomic_files::read(file, |data| Ok(String::from_utf8_lossy(data).into_owned()))
}

// Rejects any content that is not "valid:...", as a parser rejects a corrupt .met file.
fn read_valid(file: &Path) -> io::Result<String> {
    atomic_files::read(file, valid)
}

fn valid(data: &[u8]) -> io::Result<String> {
    let text = String::from_utf8_lossy(data).into_owned();
    if text.starts_with("valid:") { Ok(text) } else { Err(io::Error::new(ErrorKind::InvalidData, "corrupt")) }
}

fn rejecting(message: &'static str) -> impl Fn(&[u8]) -> io::Result<String> {
    move |_| Err(io::Error::new(ErrorKind::InvalidData, message))
}

#[cfg(unix)]
fn mode(file: &Path) -> u32 {
    use std::os::unix::fs::PermissionsExt;
    fs::metadata(file).unwrap().permissions().mode() & 0o777
}

#[test]
fn should_write_and_keep_the_previous_version_as_backup() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "sub/known.met");
    written(&file, &["v1"]);

    atomic_files::write(&file, b"v2").unwrap();

    assert_eq!(read_utf8(&file).unwrap(), "v2");
    assert_eq!(content(&atomic_files::bak_path(&file)), "v1");
    assert!(!atomic_files::tmp_path(&file).exists());
}

#[test]
fn should_leave_the_target_untouched_when_the_writer_fails() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "clients.met");
    written(&file, &["original"]);

    let error = atomic_files::write_with(&file, true, false, |out| {
        out.write_all(b"partial")?;
        Err(io::Error::other("disk full"))
    })
    .unwrap_err();

    assert_eq!(error.to_string(), "disk full");
    assert_eq!(content(&file), "original");
    assert!(!atomic_files::tmp_path(&file).exists());
}

#[test]
fn should_read_the_backup_when_the_primary_is_corrupt() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "emfriends.met");
    written(&file, &["valid:1", "valid:2"]);
    fs::write(&file, "garbage").unwrap();

    let read = read_valid(&file);

    assert_eq!(read.unwrap(), "valid:1");
}

#[test]
fn should_read_the_backup_when_the_primary_is_missing() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "server.met");
    written(&file, &["valid:a", "valid:b"]);
    // A crash between the two renames.
    fs::remove_file(&file).unwrap();

    let read = read_valid(&file);

    assert_eq!(read.unwrap(), "valid:a");
}

#[test]
fn should_fail_with_both_failures_when_primary_and_backup_are_corrupt() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "x.met");
    fs::write(&file, "bad").unwrap();
    fs::write(atomic_files::bak_path(&file), "bad too").unwrap();

    let error = read_valid(&file).unwrap_err();

    assert_eq!(error.kind(), ErrorKind::InvalidData);
    let both = error.get_ref().and_then(|inner| inner.downcast_ref::<BackupFailed>()).expect("both failures");
    assert_eq!((both.file.to_string(), both.backup.to_string()), ("corrupt".to_owned(), "corrupt".to_owned()));
}

#[test]
fn should_fail_as_io_when_the_backup_is_rejected_with_a_runtime_exception() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "njmule.properties");
    fs::write(atomic_files::bak_path(&file), "\\u12").unwrap();

    let error = atomic_files::read(&file, rejecting("malformed")).unwrap_err();

    assert_eq!((error.kind(), error.to_string()), (ErrorKind::InvalidData, "malformed".to_owned()));
}

#[test]
fn should_read_a_primary_held_for_a_moment_once_it_is_free() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "clients.met");
    written(&file, &["valid:1", "valid:2"]);
    let Some(held) = hold(&file) else { return };
    let release = held.released_after(60);

    let read = read_valid(&file);

    release.join().unwrap();
    assert_eq!(read.unwrap(), "valid:2", "not the older backup");
}

#[test]
fn should_yield_the_backup_of_a_primary_held_longer() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "njmule.properties");
    written(&file, &["valid:1", "valid:2"]);
    let Some(_held) = hold(&file) else { return };

    let read = read_valid(&file);

    assert_eq!(read.unwrap(), "valid:1");
}

#[test]
fn should_fail_on_a_primary_held_longer_where_the_backup_is_not_allowed() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "njmule.properties");
    written(&file, &["valid:1", "valid:2"]);
    let Some(_held) = hold(&file) else { return };

    let read = atomic_files::read_unless_held(&file, valid);

    assert!(read.is_err_and(|e| e.kind() != ErrorKind::NotFound && e.kind() != ErrorKind::InvalidData));
}

#[cfg(unix)]
#[test]
fn should_keep_secrets_for_their_owner_only() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "cryptkey.dat");
    fs::write(atomic_files::tmp_path(&file), "left by a crash").unwrap();
    atomic_files::write_secret(&file, b"key 1").unwrap();

    atomic_files::write_secret(&file, b"key 2").unwrap();

    assert_eq!(content(&file), "key 2");
    assert_eq!(mode(&file), 0o600);
    assert_eq!(mode(&atomic_files::bak_path(&file)), 0o600);
}

#[cfg(unix)]
#[test]
fn should_close_the_backup_of_a_secret_file_left_readable_by_all() {
    use std::os::unix::fs::PermissionsExt;
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "njmule.properties");
    fs::write(&file, "proxy.password=1").unwrap();
    fs::set_permissions(&file, fs::Permissions::from_mode(0o644)).unwrap();

    atomic_files::write_secret(&file, b"proxy.password=2").unwrap();

    assert_eq!(mode(&file), 0o600);
    assert_eq!(mode(&atomic_files::bak_path(&file)), 0o600);
}

#[test]
fn should_leave_a_directory_in_the_place_of_the_file_alone() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "server.met");
    fs::create_dir_all(&file).unwrap();
    fs::write(file.join("inside"), "x").unwrap();

    let written = atomic_files::write(&file, b"v");

    assert!(written.is_err());
    assert_eq!(content(&file.join("inside")), "x");
    assert!(!atomic_files::tmp_path(&file).exists());
}

#[test]
fn should_fail_when_a_file_is_in_the_place_of_the_folder() {
    let dir = TempDir::new().unwrap();
    let obstacle = file_in(&dir, "config");
    fs::write(&obstacle, "not a folder").unwrap();

    let written = atomic_files::write(&obstacle.join("x.met"), &[0]);

    assert!(written.is_err());
    assert_eq!(content(&obstacle), "not a folder");
}

#[test]
fn should_not_keep_a_longer_leftover_of_an_interrupted_write() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "known.met");
    fs::write(atomic_files::tmp_path(&file), "a much longer leftover of a crash").unwrap();

    atomic_files::write(&file, b"v1").unwrap();

    assert_eq!(content(&file), "v1");
}

#[test]
fn should_keep_the_version_before_the_last_as_backup() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "clients.met");
    written(&file, &["valid:1", "valid:2"]);

    atomic_files::write(&file, b"valid:3").unwrap();

    assert_eq!(content(&atomic_files::bak_path(&file)), "valid:2");
}

#[test]
fn should_reject_an_empty_primary_and_read_the_backup() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "clients.met");
    written(&file, &["valid:1", "valid:2", "valid:3"]);
    fs::write(&file, "").unwrap();

    let read = read_valid(&file);

    assert_eq!(read.unwrap(), "valid:2", "an empty file is rejected too");
}

#[test]
fn should_fail_as_io_when_the_primary_is_rejected_with_a_runtime_exception_and_there_is_no_backup() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "scheduler.properties");
    fs::write(&file, "x").unwrap();

    let error = atomic_files::read(&file, rejecting("Malformed \\uxxxx encoding.")).unwrap_err();

    assert_eq!((error.kind(), error.to_string()), (ErrorKind::InvalidData, "Malformed \\uxxxx encoding.".to_owned()));
}

#[test]
fn should_fail_to_read_a_missing_file() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "none");

    let read = read_utf8(&file);

    assert_eq!(read.unwrap_err().kind(), ErrorKind::NotFound);
}

#[test]
fn should_write_without_backup_when_told_to() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "prefs");
    atomic_files::write_with(&file, false, false, |out| out.write_all(&[1])).unwrap();

    atomic_files::write_with(&file, false, false, |out| out.write_all(&[2])).unwrap();

    assert!(!atomic_files::bak_path(&file).exists());
}

#[test]
fn should_clean_up_the_leftover_of_an_interrupted_write() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "prefs");
    atomic_files::write_with(&file, false, false, |out| out.write_all(&[1])).unwrap();
    fs::write(atomic_files::tmp_path(&file), "leftover").unwrap();

    let cleaned_up = atomic_files::cleanup_interrupted_write(&file);

    assert!(cleaned_up);
}

#[test]
fn should_find_nothing_to_clean_up_once_the_leftover_is_gone() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "prefs");
    atomic_files::write_with(&file, false, false, |out| out.write_all(&[1])).unwrap();
    fs::write(atomic_files::tmp_path(&file), "leftover").unwrap();
    assert!(atomic_files::cleanup_interrupted_write(&file));

    let cleaned_up = atomic_files::cleanup_interrupted_write(&file);

    assert!(!cleaned_up);
}

#[test]
fn should_delete_a_file_held_for_a_moment_once_it_is_free() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "held/001.part");
    written(&file, &["data"]);
    let Some(held) = hold_from_deletion(&file) else { return };
    let release = held.released_after(60);

    let deleted = atomic_files::delete(&file);

    release.join().unwrap();
    assert!(deleted.is_ok(), "{deleted:?}");
    assert!(!file.exists());
}

#[test]
fn should_fail_to_delete_a_file_held_longer() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "held/001.part");
    written(&file, &["data"]);
    let Some(_held) = hold_from_deletion(&file) else { return };

    let deleted = atomic_files::delete(&file);

    assert!(deleted.is_err());
}

#[test]
fn should_delete_nothing_when_the_file_is_missing() {
    let dir = TempDir::new().unwrap();
    let file = file_in(&dir, "001.part");

    let deleted = atomic_files::delete(&file);

    assert!(deleted.is_ok());
    assert!(!file.exists());
}
