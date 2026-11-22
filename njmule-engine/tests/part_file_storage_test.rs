// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's PartFileStorageTest, under its names: part/block hashing, finish move and close lifecycle.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use njmule_engine::files::hash::ed2k;
use njmule_engine::files::part_file_storage::{DiskDone, Done, FinishFailure, PartFileStorage};
use sha1::{Digest, Sha1};
use tokio::sync::mpsc;

const SIZE: u64 = 2 * ed2k::EMBLOCKSIZE + 1000;
const WAIT: Duration = Duration::from_secs(10);

#[tokio::test]
async fn should_hash_a_part_never_written_as_nothing() {
    let mut lab = Lab::new();
    lab.given_a_storage_at("001.part");

    lab.when_a_part_is_hashed(SIZE, 0).await;

    lab.then_the_part_hash_is(ed2k::EMPTY_PART_HASH);
}

#[tokio::test]
async fn should_hash_the_blocks_never_written_as_nothing() {
    let mut lab = Lab::new();
    lab.given_a_storage_at("001.part");

    lab.when_the_blocks_of_a_part_are_hashed(SIZE, 0).await;

    lab.then_there_is_a_hash_of_no_bytes_per_block(3);
}

#[tokio::test]
async fn should_move_the_data_away_at_the_finish() {
    let mut lab = Lab::new();
    lab.given_a_storage_at("002.part");
    lab.given_the_storage_holds(&[1, 2, 3]).await;

    lab.when_the_storage_finishes_as("done.bin").await;

    lab.then_the_finished_file_holds(&[1, 2, 3]);
    lab.then_the_storage_is_not_open();
}

#[tokio::test]
async fn should_hash_no_part_after_the_finish() {
    let mut lab = Lab::new();
    lab.given_a_storage_at("002.part");
    lab.given_the_storage_holds(&[1, 2, 3]).await;
    lab.given_the_storage_finished_as("done.bin").await;

    lab.when_a_part_is_hashed_after_close(3, 0).await;

    lab.then_no_work_was_done();
    lab.then_the_storage_is_not_open();
}

#[tokio::test]
async fn should_hash_no_blocks_after_the_finish() {
    let mut lab = Lab::new();
    lab.given_a_storage_at("002.part");
    lab.given_the_storage_holds(&[1, 2, 3]).await;
    lab.given_the_storage_finished_as("done.bin").await;

    lab.when_the_blocks_are_hashed_after_close(3, 0).await;

    lab.then_no_work_was_done();
    lab.then_the_storage_is_not_open();
}

#[tokio::test]
async fn should_be_closed_again_without_harm_after_the_finish() {
    let mut lab = Lab::new();
    lab.given_a_storage_at("002.part");
    lab.given_the_storage_holds(&[1, 2, 3]).await;
    lab.given_the_storage_finished_as("done.bin").await;
    lab.given_the_storage_is_closed();

    lab.when_the_storage_is_closed();

    lab.then_the_storage_is_not_open();
}

#[tokio::test]
async fn should_give_the_reserved_name_back_when_a_finish_cannot_move_the_data() {
    let mut lab = Lab::new();
    lab.given_a_storage_at("003.part");
    lab.given_the_storage_holds(&[4, 5, 6]).await;
    lab.given_its_data_file_is_deleted();

    lab.when_the_storage_finishes_as("lost.bin").await;

    lab.then_the_finish_failed_as_missing();
    lab.then_nothing_is_left_in_the_incoming_directory();
}

struct Lab {
    dir: tempfile::TempDir,
    data: PathBuf,
    storage: Option<PartFileStorage>,
    done: mpsc::UnboundedReceiver<DiskDone>,
    tx: mpsc::UnboundedSender<DiskDone>,
    part_hash: Option<[u8; 16]>,
    block_hashes: Option<Vec<[u8; 20]>>,
    moved: Option<PathBuf>,
    finish_error: Option<FinishFailure>,
    extra_event: bool,
}

impl Lab {
    fn new() -> Self {
        let (tx, done) = mpsc::unbounded_channel();
        Self {
            dir: tempfile::tempdir().unwrap(),
            data: PathBuf::new(),
            storage: None,
            done,
            tx,
            part_hash: None,
            block_hashes: None,
            moved: None,
            finish_error: None,
            extra_event: false,
        }
    }

    fn incoming(&self) -> PathBuf {
        self.dir.path().join("incoming")
    }

    fn given_a_storage_at(&mut self, name: &str) {
        self.data = self.dir.path().join(name);
        self.storage = Some(PartFileStorage::open(&self.data, 1, self.tx.clone()).unwrap());
    }

    async fn given_the_storage_holds(&mut self, bytes: &[u8]) {
        self.storage.as_ref().unwrap().write(0, bytes.to_vec(), None);
        let event = tokio::time::timeout(WAIT, self.done.recv()).await.unwrap().unwrap();
        match event.work {
            Done::Written { result, .. } => result.unwrap(),
            other => panic!("unexpected event: {other:?}"),
        }
    }

    fn given_its_data_file_is_deleted(&self) {
        // On Windows an open file cannot be unlinked directly while the disk thread still holds it; removing after
        // renaming or deleting directly works on Unix, while on Windows we remove the parent? No: on Windows the disk
        // thread opens with default share flags, so let's verify: std::fs::remove_file succeeds when FILE_SHARE_DELETE
        // is set (Rust's OpenOptions sets FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE on Windows!).
        fs::remove_file(&self.data).unwrap();
    }

    async fn given_the_storage_finished_as(&mut self, name: &str) {
        self.when_the_storage_finishes_as(name).await;
        assert!(self.moved.is_some());
    }

    fn given_the_storage_is_closed(&mut self) {
        self.storage.as_mut().unwrap().close();
    }

    async fn when_a_part_is_hashed(&mut self, size: u64, part: usize) {
        self.storage.as_ref().unwrap().hash_part(size, part, false);
        let event = tokio::time::timeout(WAIT, self.done.recv()).await.unwrap().unwrap();
        match event.work {
            Done::PartHashed { result, .. } => self.part_hash = Some(result.unwrap()),
            other => panic!("unexpected event: {other:?}"),
        }
    }

    async fn when_the_blocks_of_a_part_are_hashed(&mut self, size: u64, part: usize) {
        self.storage.as_ref().unwrap().hash_blocks(size, part);
        let event = tokio::time::timeout(WAIT, self.done.recv()).await.unwrap().unwrap();
        match event.work {
            Done::BlocksHashed { result, .. } => self.block_hashes = Some(result.unwrap()),
            other => panic!("unexpected event: {other:?}"),
        }
    }

    async fn when_the_storage_finishes_as(&mut self, name: &str) {
        let incoming = self.incoming();
        let storage = self.storage.as_mut().unwrap();
        let closed = storage.closed();
        storage.finish(incoming, name.to_owned());
        let event = tokio::time::timeout(WAIT, self.done.recv()).await.unwrap().unwrap();
        match event.work {
            Done::Finished(Ok(path)) => self.moved = Some(path),
            Done::Finished(Err(err)) => self.finish_error = Some(err),
            other => panic!("unexpected event: {other:?}"),
        }
        assert!(closed.wait(WAIT));
    }

    async fn when_a_part_is_hashed_after_close(&mut self, size: u64, part: usize) {
        self.storage.as_ref().unwrap().hash_part(size, part, false);
        self.extra_event = tokio::time::timeout(Duration::from_millis(150), self.done.recv()).await.is_ok();
    }

    async fn when_the_blocks_are_hashed_after_close(&mut self, size: u64, part: usize) {
        self.storage.as_ref().unwrap().hash_blocks(size, part);
        self.extra_event = tokio::time::timeout(Duration::from_millis(150), self.done.recv()).await.is_ok();
    }

    fn when_the_storage_is_closed(&mut self) {
        self.storage.as_mut().unwrap().close();
    }

    fn then_the_part_hash_is(&self, expected: [u8; 16]) {
        assert_eq!(self.part_hash, Some(expected));
    }

    fn then_there_is_a_hash_of_no_bytes_per_block(&self, blocks: usize) {
        let empty: [u8; 20] = Sha1::new().finalize().into();
        assert_eq!(self.block_hashes.as_deref(), Some(vec![empty; blocks].as_slice()));
    }

    fn then_the_finished_file_holds(&self, expected: &[u8]) {
        let path: &Path = self.moved.as_deref().unwrap();
        assert_eq!(fs::read(path).unwrap(), expected);
    }

    fn then_the_storage_is_not_open(&self) {
        assert!(!self.storage.as_ref().unwrap().is_open());
    }

    fn then_no_work_was_done(&self) {
        assert!(!self.extra_event);
    }

    fn then_the_finish_failed_as_missing(&self) {
        let err = self.finish_error.as_ref().expect("finish should fail");
        assert!(!err.held, "expected missing file, got held: {err:?}");
    }

    fn then_nothing_is_left_in_the_incoming_directory(&self) {
        let left: Vec<_> = fs::read_dir(self.incoming()).unwrap().collect();
        assert!(left.is_empty(), "the name reserved for it is free again: {left:?}");
    }
}
