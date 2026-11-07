// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's TotalsTest, under its names: the all-time statistics stop at the largest number.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]

mod free_port;

use std::fs;
use std::path::PathBuf;

use njmule_engine::api::engine_status::EngineStatus;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::engine::totals::{self, Totals};

const LARGEST: u64 = i64::MAX.unsigned_abs();

#[test]
fn should_stop_saved_totals_at_the_largest_number_instead_of_starting_again() {
    let mut totals = TotalsCase::new();
    totals.given_totals_saved_at_the_largest_numbers();
    totals.given_the_totals_are_loaded();

    totals.when_a_session_is_saved(10, 10, 10);

    totals.then_the_saved_totals_are(LARGEST, LARGEST, LARGEST, LARGEST);
}

#[tokio::test]
async fn should_add_the_session_to_the_status_without_overflowing() {
    let mut totals = TotalsCase::new();
    totals.given_a_profile_with_totals_at_the_largest_numbers();

    totals.when_the_engine_starts_and_tells_its_status().await;

    totals.then_the_status_counts_sessions(LARGEST);
    totals.then_the_status_counts_downloaded_bytes(LARGEST);
    totals.then_the_status_counts_seconds(LARGEST);
}

struct TotalsCase {
    dir: tempfile::TempDir,
    file: PathBuf,
    totals: Option<Totals>,
    dirs: Option<AppDirectories>,
    status: Option<EngineStatus>,
}

impl TotalsCase {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join(totals::FILE_NAME);
        Self { dir, file, totals: None, dirs: None, status: None }
    }

    fn given_totals_saved_at_the_largest_numbers(&self) {
        fs::write(&self.file, huge()).unwrap();
    }

    fn given_the_totals_are_loaded(&mut self) {
        self.totals = Some(Totals::load(&self.file));
    }

    fn given_a_profile_with_totals_at_the_largest_numbers(&mut self) {
        let dirs = AppDirectories::under_root(self.dir.path()).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        fs::write(dirs.config.join(totals::FILE_NAME), huge()).unwrap();
        let preferences = format!("net.tcpPort={}\nnet.kad.enabled=false\n", free_port::free_port());
        fs::write(dirs.config.join("njmule.properties"), preferences).unwrap();
        self.dirs = Some(dirs);
    }

    fn when_a_session_is_saved(&self, downloaded: u64, uploaded: u64, seconds: u64) {
        self.totals.as_ref().unwrap().save(downloaded, uploaded, seconds);
    }

    async fn when_the_engine_starts_and_tells_its_status(&mut self) {
        let engine = Engine::start(self.dirs.clone().unwrap()).await.unwrap();
        self.status = engine.status().await;
        engine.stop().await;
    }

    fn then_the_saved_totals_are(&self, downloaded: u64, uploaded: u64, sessions: u64, seconds: u64) {
        let saved = Totals::load(&self.file);
        assert_eq!(
            (saved.downloaded(), saved.uploaded(), saved.sessions(), saved.seconds()),
            (downloaded, uploaded, sessions, seconds)
        );
    }

    fn then_the_status_counts_sessions(&self, expected: u64) {
        assert_eq!(self.status.as_ref().unwrap().sessions, expected);
    }

    fn then_the_status_counts_downloaded_bytes(&self, expected: u64) {
        assert_eq!(self.status.as_ref().unwrap().total_downloaded, expected);
    }

    fn then_the_status_counts_seconds(&self, expected: u64) {
        assert_eq!(self.status.as_ref().unwrap().total_seconds, expected);
    }
}

// Totals at the largest numbers the file holds.
fn huge() -> String {
    let largest = i64::MAX;
    format!("downloaded={largest}\nuploaded={}\nsessions={largest}\nseconds={largest}\n", largest - 1)
}
