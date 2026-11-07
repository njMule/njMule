// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's DownloadCompletionTest, under its names: a finished file another program holds is moved once it
//! is free.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::arithmetic_side_effects,
    clippy::indexing_slicing
)]

mod queue_lab;

use std::fs;
#[cfg(windows)]
use std::fs::OpenOptions;
#[cfg(windows)]
use std::os::windows::fs::OpenOptionsExt;
use std::path::PathBuf;
use std::time::Duration;

use njmule_engine::api::download_info::DownloadPriority;
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::download::download_queue::QueueEvent;
use njmule_engine::files::hash::ed2k;
use queue_lab::QueueLab;
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};

// Shared for reading and writing but not for deleting, as a virus scanner opens it: the file cannot be moved.
#[cfg(windows)]
const SHARE_READ_AND_WRITE: u32 = 0x1 | 0x2;

struct Completion {
    lab: QueueLab,
    data: Vec<u8>,
    hash: [u8; 16],
    seen: Vec<Result<PathBuf, String>>,
}

impl Completion {
    fn given_a_download() -> Self {
        let mut data = vec![0u8; 10_000];
        StdRng::seed_from_u64(7).fill(&mut data[..]);
        let mut lab = QueueLab::load();
        let link = FileLink::of("held.bin", data.len() as u64, ed2k::md4(&data)).unwrap();
        let hash = lab.queue.add(&link, 0, DownloadPriority::Auto).unwrap();
        Self { lab, data, hash, seen: Vec::new() }
    }

    fn given_another_program_holds_its_data_for(&mut self, time: Duration) {
        let data = self.lab.queue.get(&self.hash).unwrap().file.data_file();
        #[cfg(windows)]
        let holder = OpenOptions::new().read(true).share_mode(SHARE_READ_AND_WRITE).open(data).unwrap();
        // Elsewhere an open file moves: its directory refuses it instead.
        #[cfg(unix)]
        let holder = queue_lab::ReadOnly::of(data.parent().unwrap());
        std::thread::spawn(move || {
            std::thread::sleep(time);
            drop(holder);
        });
    }

    fn when_its_last_data_arrives(&mut self) {
        let (hash, data) = (self.hash, self.data.clone());
        self.lab.queue.received(&hash, 0, &data, None);
        while !self.seen.iter().any(Result::is_ok) {
            let told = self.lab.next(Duration::from_secs(40), |event| match event {
                QueueEvent::Completed { path, .. } => Some(Ok(path.clone())),
                QueueEvent::Failed { reason, .. } => Some(Err(reason.clone())),
                _ => None,
            });
            match told {
                Some(told) => self.seen.push(told),
                None => break,
            }
        }
    }

    fn then_it_is_moved_to_the_incoming_directory_without_failing(&self) {
        assert!(self.seen.iter().all(Result::is_ok), "{:?}", self.seen);
        let moved = self.seen.last().unwrap().as_ref().unwrap();
        assert_eq!(fs::read(moved).unwrap(), self.data);
    }
}

#[test]
fn should_move_a_finished_file_another_program_holds_once_it_is_free() {
    let mut completion = Completion::given_a_download();
    completion.given_another_program_holds_its_data_for(Duration::from_millis(2500));

    completion.when_its_last_data_arrives();

    completion.then_it_is_moved_to_the_incoming_directory_without_failing();
}
