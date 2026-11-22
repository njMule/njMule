// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's UpdateStateTest, under its names.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fs;
use std::path::PathBuf;

use jiff::Timestamp;
use njmule_engine::config::update_channel::UpdateChannel;
use njmule_engine::update::update_state::{FILE_NAME, UpdateState};
use semver::Version;

#[test]
fn should_read_back_what_it_kept() {
    let mut test = State::new();
    test.given_checked(UpdateChannel::Stable, "0.2.0");
    test.given_checked(UpdateChannel::Prerelease, "0.3.0-beta.1");
    test.given_skipped("0.2.0");

    test.when_kept_and_read_back();

    test.then_it_is_the_same();
}

#[test]
fn should_keep_the_highest_version_a_channel_offered() {
    let mut test = State::new();
    test.given_checked(UpdateChannel::Stable, "0.3.0");

    test.when_checked(UpdateChannel::Stable, "0.2.0");

    test.then_the_highest_offered_is(UpdateChannel::Stable, "0.3.0");
}

#[test]
fn should_start_from_nothing_without_its_file() {
    let mut test = State::new();

    test.when_read();

    test.then_nothing_is_known();
}

#[test]
fn should_take_what_it_can_of_a_damaged_file() {
    let mut test = State::new();
    test.given_the_file("lastCheck=yesterday\nhighest.stable=0.2.0\nhighest.prerelease=soon\nskipped=../x\n");

    test.when_read();

    test.then_the_last_check_is_unknown();
    test.then_the_highest_offered_is(UpdateChannel::Stable, "0.2.0");
    test.then_no_version_is_skipped();
}

struct State {
    dir: tempfile::TempDir,
    state: UpdateState,
    loaded: UpdateState,
}

impl State {
    fn new() -> Self {
        Self { dir: tempfile::tempdir().unwrap(), state: UpdateState::default(), loaded: UpdateState::default() }
    }

    fn file(&self) -> PathBuf {
        self.dir.path().join(FILE_NAME)
    }

    fn given_checked(&mut self, channel: UpdateChannel, version: &str) {
        self.state = self.state.checked(now(), channel, &Version::parse(version).unwrap());
    }

    fn given_skipped(&mut self, version: &str) {
        self.state = self.state.skipping(version);
    }

    fn given_the_file(&self, text: &str) {
        fs::write(self.file(), text).unwrap();
    }

    fn when_kept_and_read_back(&mut self) {
        self.state.save(&self.file()).unwrap();
        self.loaded = UpdateState::load(&self.file());
    }

    fn when_checked(&mut self, channel: UpdateChannel, version: &str) {
        self.loaded = self.state.checked(now(), channel, &Version::parse(version).unwrap());
    }

    fn when_read(&mut self) {
        self.loaded = UpdateState::load(&self.file());
    }

    fn then_it_is_the_same(&self) {
        assert_eq!(self.loaded, self.state);
    }

    fn then_the_highest_offered_is(&self, channel: UpdateChannel, version: &str) {
        assert_eq!(self.loaded.highest.get(&channel).map(Version::to_string).as_deref(), Some(version));
    }

    fn then_nothing_is_known(&self) {
        assert_eq!(self.loaded, UpdateState::default());
    }

    fn then_the_last_check_is_unknown(&self) {
        assert!(self.loaded.last_check.is_none());
    }

    fn then_no_version_is_skipped(&self) {
        assert!(self.loaded.skipped.is_none());
    }
}

fn now() -> Timestamp {
    "2026-11-03T08:00:00Z".parse().unwrap()
}
