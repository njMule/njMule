// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's CorruptPartTest, under its names: what becomes of a part that fails its check.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::panic
)]

use std::collections::VecDeque;
use std::fs;
use std::net::{IpAddr, Ipv4Addr};
use std::path::PathBuf;
use std::time::{Duration, Instant};

use njmule_engine::api::download_info::DownloadPriority;
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::download::download_queue::{DownloadQueue, QueueEvent};
use njmule_engine::files::hash::ed2k::{self, EMBLOCKSIZE, PARTSIZE};
use njmule_engine::files::part_file::{self, PartFile};
use njmule_engine::files::part_file_storage::DiskDone;
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};
use tokio::sync::mpsc;

const WAIT: Duration = Duration::from_secs(30);
const SIZE: usize = PARTSIZE as usize + 1000;
// The fourth block of the first part.
const BAD_BLOCK: usize = 3 * EMBLOCKSIZE as usize;
const SENDER: IpAddr = IpAddr::V4(Ipv4Addr::new(192, 0, 2, 33));

struct CorruptPart {
    _dir: tempfile::TempDir,
    queue: DownloadQueue,
    disk: mpsc::UnboundedReceiver<DiskDone>,
    events: VecDeque<QueueEvent>,
    data: Vec<u8>,
    hash: [u8; 16],
    finished: PathBuf,
}

impl CorruptPart {
    fn given_a_download_of_two_parts() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let mut data = vec![0u8; SIZE];
        StdRng::seed_from_u64(11).fill(&mut data[..]);
        let parts = vec![ed2k::md4(&data[..PARTSIZE as usize]), ed2k::md4(&data[PARTSIZE as usize..])];
        let (done, disk) = mpsc::unbounded_channel();
        let incoming = dir.path().join("incoming");
        let mut queue = DownloadQueue::load(
            &dir.path().join("temp"),
            Box::new(move |_: &PartFile| incoming.clone()),
            Box::new(|| 0),
            done,
        )
        .unwrap();
        let hash = ed2k::file_hash(&parts).unwrap();
        let link = FileLink::new("two.bin".to_owned(), SIZE as u64, hash, None, parts, Vec::new(), Vec::new()).unwrap();
        queue.add(&link, 0, DownloadPriority::Auto).unwrap();
        let mut corrupt =
            Self { _dir: dir, queue, disk, events: VecDeque::new(), data, hash, finished: PathBuf::new() };
        corrupt.settle();
        corrupt
    }

    fn given_its_first_part_arrived_with_a_bad_byte_in(&mut self, offset: usize) {
        self.when_its_first_part_arrives_with_a_bad_byte_in(None, offset);
        assert_eq!(self.next_told(), "corrupt 0");
    }

    fn given_its_first_part_arrived_from_with_a_bad_byte_in(&mut self, ip: IpAddr, offset: usize) {
        self.when_its_first_part_arrives_from_with_a_bad_byte_in(ip, offset);
        assert_eq!(self.next_told(), "corrupt 0");
    }

    fn given_the_block_arrived_again_at(&mut self, start: usize) {
        self.when_the_block_arrives_again_at(start);
        self.then_the_first_part_is_whole_and_checked();
    }

    fn when_its_first_part_arrives_from_with_a_bad_byte_in(&mut self, ip: IpAddr, offset: usize) {
        self.when_its_first_part_arrives_with_a_bad_byte_in(Some(ip), offset);
    }

    fn when_its_first_part_arrives_with_a_bad_byte_in(&mut self, source: Option<IpAddr>, offset: usize) {
        let mut bad = self.data[..PARTSIZE as usize].to_vec();
        bad[offset] ^= 1;
        self.queue.received(&self.hash, 0, &bad, source);
        self.settle();
    }

    fn when_the_block_arrives_again_at(&mut self, start: usize) {
        let block = self.data[start..start + EMBLOCKSIZE as usize].to_vec();
        self.queue.received(&self.hash, start as u64, &block, None);
        self.settle();
    }

    fn when_bad_data_for_the_first_part_arrives_and_then_the_second_part(&mut self) {
        let mut bad = self.data[..EMBLOCKSIZE as usize].to_vec();
        bad[7] ^= 1;
        let second = self.data[PARTSIZE as usize..].to_vec();
        self.queue.received(&self.hash, 0, &bad, None);
        self.queue.received(&self.hash, PARTSIZE, &second, None);
        self.settle();
        let told = self.next_told();
        self.finished = PathBuf::from(told.strip_prefix("completed ").expect("completed"));
    }

    fn then_the_first_part_is_whole_and_checked(&mut self) {
        let deadline = Instant::now() + WAIT;
        while !part_file::has(self.queue.get(&self.hash).unwrap().verified_parts(), 0) {
            assert!(Instant::now() < deadline, "the part checked");
            self.pump(Duration::from_millis(20));
        }
        assert!(self.queue.get(&self.hash).unwrap().file.is_part_complete(0));
    }

    fn then_the_finished_file_holds_the_good_data(&mut self) {
        assert_eq!(fs::read(&self.finished).unwrap(), self.data);
    }

    fn then_the_source_is_reported_for_corrupt_data(&mut self, ip: IpAddr) {
        let told = loop {
            let told = self.next_told();
            if told.starts_with("guilty") {
                break told;
            }
        };
        assert_eq!(told, format!("guilty [{ip}]"));
    }

    // The next event the Java test's listener tells: added and progress it does not.
    fn next_told(&mut self) -> String {
        let deadline = Instant::now() + WAIT;
        loop {
            while let Some(event) = self.events.pop_front() {
                match event {
                    QueueEvent::Completed { path, .. } => return format!("completed {}", path.display()),
                    QueueEvent::Failed { reason, .. } => return format!("failed {reason}"),
                    QueueEvent::Corrupt { part, .. } => return format!("corrupt {part}"),
                    QueueEvent::CorruptSenders { ips, .. } => return format!("guilty {ips:?}"),
                    _ => {}
                }
            }
            assert!(Instant::now() < deadline, "no event");
            self.pump(Duration::from_millis(20));
        }
    }

    fn pump(&mut self, wait: Duration) {
        let deadline = Instant::now() + wait;
        while Instant::now() < deadline {
            match self.disk.try_recv() {
                Ok(done) => {
                    self.queue.disk_done(done);
                    self.settle();
                }
                Err(_) => std::thread::sleep(Duration::from_millis(2)),
            }
        }
    }

    fn settle(&mut self) {
        self.events.extend(self.queue.take_events());
    }
}

impl Drop for CorruptPart {
    fn drop(&mut self) {
        self.queue.close();
    }
}

#[test]
fn should_make_a_corrupt_part_whole_once_its_bad_block_is_downloaded_again() {
    let mut corrupt = CorruptPart::given_a_download_of_two_parts();
    corrupt.given_its_first_part_arrived_with_a_bad_byte_in(BAD_BLOCK + 5);

    corrupt.when_the_block_arrives_again_at(BAD_BLOCK);

    corrupt.then_the_first_part_is_whole_and_checked();
}

#[test]
fn should_not_write_data_over_a_part_that_is_whole_again() {
    let mut corrupt = CorruptPart::given_a_download_of_two_parts();
    corrupt.given_its_first_part_arrived_with_a_bad_byte_in(BAD_BLOCK + 5);
    corrupt.given_the_block_arrived_again_at(BAD_BLOCK);

    corrupt.when_bad_data_for_the_first_part_arrives_and_then_the_second_part();

    corrupt.then_the_finished_file_holds_the_good_data();
}

#[test]
fn should_report_the_source_that_alone_sent_a_corrupt_part_three_times() {
    let mut corrupt = CorruptPart::given_a_download_of_two_parts();
    corrupt.given_its_first_part_arrived_from_with_a_bad_byte_in(SENDER, BAD_BLOCK + 5);
    corrupt.given_its_first_part_arrived_from_with_a_bad_byte_in(SENDER, BAD_BLOCK + 5);

    corrupt.when_its_first_part_arrives_from_with_a_bad_byte_in(SENDER, BAD_BLOCK + 5);

    corrupt.then_the_source_is_reported_for_corrupt_data(SENDER);
}
