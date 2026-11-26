// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Cancelled downloads remembered under salted keys in `cancelled.met`.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::panic)]

use std::fs;
use std::path::PathBuf;

use njmule_engine::files::cancelled_files::{self, CancelledFiles, Snapshot};
use njmule_engine::persist::format::cancelled_met;
use rand::SeedableRng;
use rand::rngs::StdRng;
use tempfile::TempDir;

const FILM: [u8; 16] = [1; 16];
const SONG: [u8; 16] = [2; 16];

struct State {
    _dir: TempDir,
    file: PathBuf,
    rng: StdRng,
    cancelled: Option<CancelledFiles>,
    snapshot: Option<Snapshot>,
}

impl State {
    fn new() -> Self {
        let dir = TempDir::new().unwrap();
        let file = dir.path().join(cancelled_files::FILE_NAME);
        Self { _dir: dir, file, rng: StdRng::seed_from_u64(42), cancelled: None, snapshot: None }
    }

    fn cancelled(&self) -> &CancelledFiles {
        self.cancelled.as_ref().unwrap()
    }

    fn cancelled_mut(&mut self) -> &mut CancelledFiles {
        self.cancelled.as_mut().unwrap()
    }

    fn given_a_new_list_without(&mut self, hash: [u8; 16]) {
        self.given_the_list_is_loaded();
        assert!(!self.cancelled().contains(&hash));
    }

    fn given_the_list_is_loaded(&mut self) {
        self.cancelled = Some(CancelledFiles::load(&self.file, &mut self.rng).unwrap());
    }

    fn given_a_list_in_the_old_format_with(&self, hash: [u8; 16]) {
        // The old format: header 0x0E, then count (1), then plain hash + 0 tags.
        let mut old = vec![0u8; 1 + 4 + 17];
        old[0] = 0x0E;
        old[1] = 1;
        old[5..21].copy_from_slice(&hash);
        fs::write(&self.file, old).unwrap();
    }

    fn given_the_file_is_cancelled(&mut self, hash: [u8; 16]) {
        let mut rng = StdRng::seed_from_u64(99);
        self.cancelled_mut().add(&hash, &mut rng).unwrap();
    }

    fn given_the_saved_list_is_damaged(&self) {
        fs::write(&self.file, [0x0F, 1, 2]).unwrap();
    }

    fn given_a_snapshot_is_taken(&mut self) {
        self.snapshot = Some(self.cancelled().snapshot());
    }

    fn when_the_list_is_loaded(&mut self) {
        self.cancelled = Some(CancelledFiles::load(&self.file, &mut self.rng).unwrap());
    }

    fn when_the_file_is_cancelled(&mut self, hash: [u8; 16]) {
        let mut rng = StdRng::seed_from_u64(7);
        self.cancelled_mut().add(&hash, &mut rng).unwrap();
    }

    fn when_the_list_is_cleared(&mut self) {
        self.cancelled_mut().clear().unwrap();
    }

    fn then_the_list_contains(&self, hash: [u8; 16]) {
        assert!(self.cancelled().contains(&hash));
    }

    fn then_the_list_contains_as_before_the_last_change(&self, hash: [u8; 16]) {
        assert!(self.cancelled().contains(&hash), "as it was before the last change");
    }

    fn then_the_list_does_not_contain(&self, hash: [u8; 16]) {
        assert!(!self.cancelled().contains(&hash));
    }

    fn then_the_list_is_empty(&self) {
        assert_eq!(self.cancelled().size(), 0);
    }

    fn then_a_reloaded_list_contains(&mut self, hash: [u8; 16]) {
        let reloaded = CancelledFiles::load(&self.file, &mut self.rng).unwrap();
        assert!(reloaded.contains(&hash));
    }

    fn then_a_reloaded_list_does_not_contain(&mut self, hash: [u8; 16]) {
        let reloaded = CancelledFiles::load(&self.file, &mut self.rng).unwrap();
        assert!(!reloaded.contains(&hash));
    }

    fn then_the_saved_list_holds_one_key_other_than(&self, hash: [u8; 16]) {
        let contents = cancelled_met::read(&fs::read(&self.file).unwrap()).unwrap();
        assert_eq!(contents.keys.len(), 1);
        assert_ne!(contents.keys[0], hash);
    }

    fn then_the_saved_list_is_not_in_the_old_format(&self) {
        let contents = cancelled_met::read(&fs::read(&self.file).unwrap()).unwrap();
        assert!(!contents.legacy);
    }

    fn then_among_the_files_the_snapshot_cancels_only(&self, all: &[[u8; 16]], expected: [u8; 16]) {
        let snapshot = self.snapshot.as_ref().unwrap();
        let kept: Vec<[u8; 16]> = all.iter().copied().filter(|h| snapshot.contains(h)).collect();
        assert_eq!(kept, vec![expected]);
    }
}

#[test]
fn should_remember_cancelled_files_across_loads_without_listing_them() {
    let mut t = State::new();
    t.given_a_new_list_without(FILM);

    t.when_the_file_is_cancelled(FILM);

    t.then_a_reloaded_list_contains(FILM);
    t.then_a_reloaded_list_does_not_contain(SONG);
    t.then_the_saved_list_holds_one_key_other_than(FILM);
}

#[test]
fn should_read_the_old_format() {
    let mut t = State::new();
    t.given_a_list_in_the_old_format_with(FILM);

    t.when_the_list_is_loaded();

    t.then_the_list_contains(FILM);
}

#[test]
fn should_give_the_old_format_keys_of_its_own() {
    let mut t = State::new();
    t.given_a_list_in_the_old_format_with(FILM);
    t.given_the_list_is_loaded();

    t.when_the_file_is_cancelled(SONG);

    t.then_the_saved_list_is_not_in_the_old_format();
    t.then_a_reloaded_list_contains(FILM);
}

#[test]
fn should_read_a_damaged_list_from_its_backup() {
    let mut t = State::new();
    t.given_the_list_is_loaded();
    t.given_the_file_is_cancelled(FILM);
    t.given_the_file_is_cancelled(SONG);
    t.given_the_saved_list_is_damaged();

    t.when_the_list_is_loaded();

    t.then_the_list_contains_as_before_the_last_change(FILM);
}

#[test]
fn should_keep_the_list_as_it_was_in_a_snapshot() {
    let mut t = State::new();
    t.given_the_list_is_loaded();
    t.given_the_file_is_cancelled(FILM);
    t.given_a_snapshot_is_taken();
    t.given_the_file_is_cancelled(SONG);

    t.when_the_list_is_cleared();

    t.then_among_the_files_the_snapshot_cancels_only(&[FILM, SONG], FILM);
    t.then_the_list_does_not_contain(FILM);
    t.then_the_list_is_empty();
}
