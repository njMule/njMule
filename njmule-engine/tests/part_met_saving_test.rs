// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's PartMetSavingTest, under its names: a part.met another program holds is saved on the download's
//! disk thread, not on the engine's.

#![cfg(windows)]
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::arithmetic_side_effects,
    clippy::indexing_slicing
)]

mod queue_lab;

use std::fs::{self, File, OpenOptions};
use std::os::windows::fs::OpenOptionsExt;
use std::time::{Duration, Instant};

use njmule_engine::api::download_info::DownloadPriority;
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::persist::format::part_met;
use queue_lab::QueueLab;

// Shared for reading and writing but not for deleting: no file can be renamed over it, as while a backup reads it.
const SHARE_READ_AND_WRITE: u32 = 0x1 | 0x2;

struct Saving {
    lab: QueueLab,
    hash: [u8; 16],
    holder: Option<File>,
    engine_busy: Duration,
}

impl Saving {
    fn given_a_download() -> Self {
        let mut lab = QueueLab::load();
        let link = FileLink::of("held.bin", 5000, [0x4d; 16]).unwrap();
        let hash = lab.queue.add(&link, 0, DownloadPriority::Auto).unwrap();
        lab.queue.save_changed();
        let met = lab.queue.get(&hash).unwrap().file.met_file();
        let deadline = Instant::now() + Duration::from_secs(30);
        while !met.exists() {
            assert!(Instant::now() < deadline, "saved");
            lab.settle();
            std::thread::sleep(Duration::from_millis(20));
        }
        Self { lab, hash, holder: None, engine_busy: Duration::ZERO }
    }

    fn given_another_program_holds_its_part_met(&mut self) {
        let met = self.lab.queue.get(&self.hash).unwrap().file.met_file();
        self.holder = Some(OpenOptions::new().read(true).share_mode(SHARE_READ_AND_WRITE).open(met).unwrap());
    }

    fn when_the_download_is_paused_and_saved(&mut self) {
        let start = Instant::now();
        let hash = self.hash;
        self.lab.queue.pause(&hash);
        self.lab.queue.save_changed();
        self.engine_busy = start.elapsed();
    }

    fn then_the_engine_was_not_held_up(&self) {
        assert!(self.engine_busy < Duration::from_millis(200), "{:?}", self.engine_busy);
    }

    fn then_the_part_met_is_saved_once_it_is_free(&mut self) {
        let met = self.lab.queue.get(&self.hash).unwrap().file.met_file();
        self.holder = None;
        self.lab.queue.close();
        assert!(part_met::read(&fs::read(met).unwrap()).unwrap().paused());
    }
}

#[test]
fn should_not_hold_up_the_engine_while_a_part_met_is_slow_to_replace() {
    let mut saving = Saving::given_a_download();
    saving.given_another_program_holds_its_part_met();

    saving.when_the_download_is_paused_and_saved();

    saving.then_the_engine_was_not_held_up();
    saving.then_the_part_met_is_saved_once_it_is_free();
}
