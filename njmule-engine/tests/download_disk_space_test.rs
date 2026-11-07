// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's DownloadDiskSpaceTest, under its names: Windows refuses writes into a range another handle locked,
//! as it refuses those to a full disk.

#![cfg(windows)]
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::arithmetic_side_effects,
    clippy::indexing_slicing
)]

mod queue_lab;

use std::fs::{File, OpenOptions};
use std::sync::atomic::Ordering;
use std::time::Duration;

use njmule_engine::api::download_info::DownloadPriority;
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::download::download_queue::QueueEvent;
use njmule_engine::files::hash::ed2k::EMBLOCKSIZE;
use njmule_engine::files::part_file::{Block, Status};
use queue_lab::QueueLab;
use rand::SeedableRng;
use rand::rngs::StdRng;

const WAIT: Duration = Duration::from_secs(30);

struct DiskSpace {
    lab: QueueLab,
    hash: [u8; 16],
    other: Option<File>,
}

impl DiskSpace {
    fn given_a_download() -> Self {
        let mut lab = QueueLab::load();
        let link = FileLink::of("full.bin", 400_000, [0xd1; 16]).unwrap();
        let hash = lab.queue.add(&link, 0, DownloadPriority::Auto).unwrap();
        lab.queue.get_mut(&hash).unwrap().file.next_blocks(&[true], 1, &mut StdRng::seed_from_u64(1));
        Self { lab, hash, other: None }
    }

    fn given_its_data_cannot_be_written(&mut self) {
        let data = self.lab.queue.get(&self.hash).unwrap().file.data_file();
        let other = OpenOptions::new().write(true).open(data).unwrap();
        other.lock().unwrap();
        self.other = Some(other);
    }

    fn given_data_arrived_while_the_disk_was_out_of_space(&mut self) {
        self.when_data_arrives_while_the_disk_is_out_of_space();
        assert!(self.next_told().is_some());
    }

    fn when_data_arrives_while_the_disk_is_out_of_space(&mut self) {
        self.lab.min_free.store(u64::MAX, Ordering::Relaxed);
        let hash = self.hash;
        self.lab.queue.received(&hash, 0, &vec![0; EMBLOCKSIZE as usize], None);
    }

    fn when_there_is_room_again(&mut self) {
        if let Some(other) = self.other.take() {
            other.unlock().unwrap();
        }
        self.lab.min_free.store(1, Ordering::Relaxed);
        self.lab.queue.check_space();
    }

    fn then_the_downloads_wait_for_space_instead_of_failing(&mut self) {
        assert_eq!(self.next_told().as_deref(), Some("space short"));
        let download = self.lab.queue.get(&self.hash).unwrap();
        assert_eq!(download.file.status(), Status::Downloading);
        assert!(!download.is_active());
    }

    fn then_the_download_goes_on_and_asks_for_the_data_again(&mut self) {
        assert_eq!(self.next_told().as_deref(), Some("space enough"));
        let hash = self.hash;
        assert!(self.lab.queue.get(&hash).unwrap().is_active());
        let file = &mut self.lab.queue.get_mut(&hash).unwrap().file;
        let blocks = file.next_blocks(&[true], 1, &mut StdRng::seed_from_u64(1));
        assert_eq!(blocks, [Block { start: 0, end: EMBLOCKSIZE }]);
    }

    // What the Java test's listener tells: the disk space and a failure.
    fn next_told(&mut self) -> Option<String> {
        self.lab.next(WAIT, |event| match event {
            QueueEvent::DiskSpace(true) => Some("space enough".to_owned()),
            QueueEvent::DiskSpace(false) => Some("space short".to_owned()),
            QueueEvent::Failed { reason, .. } => Some(format!("failed {reason}")),
            _ => None,
        })
    }
}

#[test]
fn should_make_the_downloads_wait_instead_of_failing_when_a_write_finds_the_disk_full() {
    let mut space = DiskSpace::given_a_download();
    space.given_its_data_cannot_be_written();

    space.when_data_arrives_while_the_disk_is_out_of_space();

    space.then_the_downloads_wait_for_space_instead_of_failing();
}

#[test]
fn should_go_on_by_itself_with_a_download_waiting_for_room_after_a_failed_write() {
    let mut space = DiskSpace::given_a_download();
    space.given_its_data_cannot_be_written();
    space.given_data_arrived_while_the_disk_was_out_of_space();

    space.when_there_is_room_again();

    space.then_the_download_goes_on_and_asks_for_the_data_again();
}
