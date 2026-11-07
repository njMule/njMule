// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's DownloadQueueTest, under its names: a queue over a temp directory, its disk work brought back and
//! its events told as the Java listeners tell them.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::panic
)]

use std::collections::VecDeque;
use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use njmule_engine::api::download_info::DownloadPriority;
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::download::download_queue::{DownloadQueue, QueueEvent};
use njmule_engine::files::hash::ed2k::{self, EMBLOCKSIZE, PARTSIZE};
#[cfg(windows)]
use njmule_engine::files::part_file::Block;
use njmule_engine::files::part_file::{self, KnownSource, PartFile, Status};
use njmule_engine::files::part_file_storage::{DiskClosed, DiskDone};
use njmule_engine::persist::atomic_files;
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};
use sha1::{Digest, Sha1};
use tokio::sync::mpsc;

const WAIT: Duration = Duration::from_secs(30);

// How a test hears the queue, as the Java test's listeners: the plain one tells free space but no corrupt part, the
// recorder a corrupt part but no free space; two more act on what they hear.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Listener {
    Plain,
    Recorder,
    CancelOnProgress,
    CancelAndAddWhileMoved,
}

struct Queue {
    dir: tempfile::TempDir,
    queue: Option<DownloadQueue>,
    disk: Option<mpsc::UnboundedReceiver<DiskDone>>,
    listener: Listener,
    min_free: Arc<AtomicU64>,
    grace: Duration,
    events: VecDeque<String>,
    hash: [u8; 16],
    link: Option<FileLink>,
    data: Vec<u8>,
    bad: Vec<u8>,
    parts: Vec<[u8; 16]>,
    met: PathBuf,
    file: PathBuf,
    #[cfg(windows)]
    blocks: Vec<Block>,
    accepted: bool,
    restored: bool,
    closed: Option<DiskClosed>,
    moved_while_finishing: bool,
    kept_after_cancel: Option<bool>,
    #[cfg(windows)]
    holder: Option<fs::File>,
    adopted: Option<std::io::Result<[u8; 16]>>,
}

impl Queue {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            queue: None,
            disk: None,
            listener: Listener::Plain,
            min_free: Arc::new(AtomicU64::new(0)),
            // Cancelled downloads go at once here, unless a test keeps them for an undo.
            grace: Duration::ZERO,
            events: VecDeque::new(),
            hash: [0; 16],
            link: None,
            data: Vec::new(),
            bad: Vec::new(),
            parts: Vec::new(),
            met: PathBuf::new(),
            file: PathBuf::new(),
            #[cfg(windows)]
            blocks: Vec::new(),
            accepted: false,
            restored: false,
            closed: None,
            moved_while_finishing: false,
            kept_after_cancel: None,
            #[cfg(windows)]
            holder: None,
            adopted: None,
        }
    }

    fn given_the_data(&mut self, bytes: Vec<u8>) {
        self.data = bytes;
    }

    fn given_a_queue(&mut self) {
        self.load();
    }

    fn given_a_queue_that_tells_every_event(&mut self) {
        self.listener = Listener::Recorder;
        self.load();
    }

    fn given_a_queue_whose_listener_cancels_a_download_on_its_progress(&mut self) {
        self.listener = Listener::CancelOnProgress;
        self.load();
    }

    fn given_a_queue_whose_user_cancels_and_adds_the_download_again_while_its_file_is_moved(&mut self) {
        self.listener = Listener::CancelAndAddWhileMoved;
        self.load();
    }

    fn given_a_queue_keeping_cancelled_downloads_for(&mut self, grace: Duration) {
        self.grace = grace;
        self.load();
    }

    fn given_a_file_where_the_incoming_directory_should_be(&mut self) {
        fs::write(self.incoming(), "a file where the directory should be").unwrap();
    }

    fn given_a_minimum_free_space_of(&mut self, bytes: u64) {
        self.min_free.store(bytes, Ordering::Relaxed);
    }

    fn given_leftovers_in_the_temp_directory(&mut self) {
        let temp = self.temp();
        fs::create_dir_all(&temp).unwrap();
        fs::write(temp.join("001.part.met"), [1, 2, 3]).unwrap();
        fs::write(atomic_files::bak_path(&temp.join("002.part.met")), [1, 2, 3]).unwrap();
        fs::write(atomic_files::tmp_path(&temp.join("003.part.met")), [1]).unwrap();
        fs::write(temp.join("004.part"), [1]).unwrap();
    }

    fn given_a_download_of_the_data(&mut self, name: &str) {
        let link = FileLink::of(name, self.data.len() as u64, ed2k::md4(&self.data)).unwrap();
        self.add_download(link);
    }

    fn given_a_download_of_the_data_with_its_part_hashes(&mut self, name: &str) {
        self.parts = part_hashes(&self.data);
        let hash = ed2k::file_hash(&self.parts).unwrap();
        let link = FileLink::new(
            name.to_owned(),
            self.data.len() as u64,
            hash,
            None,
            self.parts.clone(),
            Vec::new(),
            Vec::new(),
        )
        .unwrap();
        self.add_download(link);
    }

    fn given_a_download_without_hash_set_whose_second_part_is_the_data(&mut self, name: &str) {
        self.parts = vec![[0x15; 16], ed2k::md4(&self.data)];
        let hash = ed2k::file_hash(&self.parts).unwrap();
        let size = PARTSIZE + self.data.len() as u64;
        let link = FileLink::new(name.to_owned(), size, hash, None, Vec::new(), Vec::new(), Vec::new()).unwrap();
        self.add_download(link);
    }

    fn given_a_download(&mut self, name: &str, size: u64, hash: [u8; 16]) {
        self.add_download(FileLink::of(name, size, hash).unwrap());
    }

    fn given_a_link_with_a_wrong_hash_set(&mut self) {
        self.link = Some(
            FileLink::new("exact.bin".to_owned(), PARTSIZE, [0x56; 16], None, vec![[0x78; 16]], Vec::new(), Vec::new())
                .unwrap(),
        );
    }

    fn given_the_download_was_announced(&mut self) {
        assert_eq!(self.poll(WAIT).as_deref(), Some("added"));
    }

    fn given_the_download_has_its_hash_set(&mut self) {
        assert!(self.download().file.has_hash_set());
    }

    fn given_the_data_received(&mut self) {
        self.receive(0, &self.data.clone());
    }

    fn given_the_data_received_up_to(&mut self, end: usize) {
        let piece = self.data[..end].to_vec();
        self.receive(0, &piece);
        self.await_prefix("progress");
    }

    // Received a part at a time.
    fn given_the_data_received_with_a_byte_flipped_at(&mut self, at: usize) {
        self.bad = self.data.clone();
        self.bad[at] ^= 1;
        let bad = self.bad.clone();
        for (part, piece) in bad.chunks(PARTSIZE as usize).enumerate() {
            self.receive(part as u64 * PARTSIZE, piece);
        }
    }

    fn given_the_corrupt_first_part_taken_back(&mut self) {
        let deadline = Instant::now() + WAIT;
        while Instant::now() < deadline {
            self.poll(Duration::from_millis(100));
            let file = &self.download().file;
            if !file.is_part_complete(0) && file.is_part_complete(1) && file.completed_bytes() == 1000 {
                return;
            }
        }
        panic!("the corrupt part was not taken back");
    }

    #[cfg(windows)]
    fn given_the_first_blocks_asked_for(&mut self) {
        self.blocks = self.next_blocks();
    }

    // Another process holds a lock on the data: the write fails.
    #[cfg(windows)]
    fn given_the_first_block_could_not_be_written_while_another_process_locked_the_data(&mut self) {
        let other = fs::OpenOptions::new().write(true).open(self.temp().join("001.part")).unwrap();
        other.lock().unwrap();
        let piece = self.data[..EMBLOCKSIZE as usize].to_vec();
        self.receive(0, &piece);
        assert!(self.await_prefix("failed").contains("cannot write"));
        other.unlock().unwrap();
    }

    fn given_the_download_failed_with(&mut self, reason: &str) {
        assert!(self.await_prefix("failed").contains(reason));
    }

    fn given_the_download_went_into_error(&mut self) {
        assert_eq!(self.download().file.status(), Status::Error);
    }

    fn given_the_incoming_directory_freed(&mut self) {
        fs::remove_file(self.incoming()).unwrap();
    }

    fn given_the_queue_closed(&mut self) {
        self.close_the_queue();
    }

    fn given_the_part_files_copied(&mut self, from: &str, to: &str) {
        let temp = self.temp();
        fs::copy(temp.join(format!("{from}.part.met")), temp.join(format!("{to}.part.met"))).unwrap();
        fs::copy(temp.join(format!("{from}.part")), temp.join(format!("{to}.part"))).unwrap();
    }

    fn given_only_the_backup_of_the_part_file_left(&mut self, number: &str) {
        let met = self.temp().join(format!("{number}.part.met"));
        fs::rename(&met, atomic_files::bak_path(&met)).unwrap();
    }

    fn given_a_data_file_left_by_a_crash(&mut self, number: &str) {
        fs::write(self.temp().join(format!("{number}.part")), "left by a crash").unwrap();
    }

    fn given_the_queue_loaded_again(&mut self) {
        self.load();
    }

    fn given_the_queue_has_the_download(&mut self) {
        assert!(self.queue().get(&self.hash).is_some());
    }

    fn given_the_unreadable_part_files_left_alone(&mut self) {
        assert_eq!(self.queue().all().count(), 0, "unreadable part files are left alone");
    }

    fn given_the_download_paused(&mut self) {
        let hash = self.hash;
        self.queue_mut().pause(&hash);
        self.settle();
    }

    fn given_a_directory_in_the_way_of_its_data(&mut self) {
        let data = self.download().file.data_file();
        fs::remove_file(&data).unwrap();
        fs::create_dir_all(data.join("in the way")).unwrap();
    }

    fn given_the_part_found_corrupt(&mut self, part: usize) {
        assert_eq!(self.await_prefix("corrupt"), format!("corrupt {part}"));
    }

    fn given_recovery_data_received(&mut self, blocks: Vec<[u8; 20]>) {
        let hash = self.hash;
        self.queue_mut().recovered(&hash, 0, blocks);
        self.settle();
    }

    fn given_the_recovery_hashes_did_not_fit_the_part(&mut self) {
        assert_eq!(self.await_prefix("progress"), "progress", "the hashes do not fit the part");
        assert_eq!(self.download().file.completed_bytes(), 0);
    }

    fn given_the_second_part_received(&mut self) {
        self.receive(PARTSIZE, &self.data.clone());
        assert_eq!(self.await_prefix("progress"), "progress");
    }

    fn given_no_part_verified_for_want_of_its_hash(&mut self) {
        assert!(!self.download().verified_parts().contains(&true), "no hash to check it with");
    }

    fn given_the_hash_set_known(&mut self) {
        let (hash, parts) = (self.hash, self.parts.clone());
        let queue = self.queue_mut();
        assert!(queue.get_mut(&hash).unwrap().file.set_hash_set(parts));
        queue.hash_set_known(&hash);
        self.settle();
    }

    fn given_the_second_part_verified(&mut self) {
        let deadline = Instant::now() + WAIT;
        while !part_file::has(self.download().verified_parts(), 1) {
            assert!(Instant::now() < deadline, "the second part is not verified");
            self.poll(Duration::from_millis(20));
        }
    }

    fn given_the_temp_directory_deleted(&mut self) {
        fs::remove_dir(self.temp()).unwrap();
    }

    fn given_the_download_is_active(&mut self) {
        assert!(self.download().is_active());
    }

    fn given_the_free_space_checked(&mut self) {
        self.check_the_free_space();
    }

    fn given_the_download_waits_for_free_space(&mut self) {
        assert_eq!(self.await_prefix("space"), "space short");
        assert!(!self.download().is_active());
        assert!(self.queue().is_space_short());
    }

    fn given_a_directory_in_the_way_of_deleting_its_backup(&mut self) {
        self.met = self.download().file.met_file();
        fs::create_dir_all(atomic_files::bak_path(&self.met).join("x")).unwrap();
    }

    // Directories in the way of the saving and of the deleting.
    fn given_directories_in_the_way_of_saving_and_deleting_its_part_file(&mut self) {
        self.met = self.download().file.met_file();
        fs::create_dir_all(atomic_files::tmp_path(&self.met).join("x")).unwrap();
        fs::create_dir_all(atomic_files::bak_path(&self.met).join("x")).unwrap();
    }

    fn given_the_paused_state_could_not_be_saved(&mut self) {
        assert!(self.met.exists(), "the paused state could not be saved");
    }

    // Written, then closed before the check could run: as after a crash.
    fn given_the_data_marked_written_but_not_checked(&mut self) {
        let (hash, length) = (self.hash, self.data.len() as u64);
        let file = &mut self.queue_mut().get_mut(&hash).unwrap().file;
        file.written(0, length);
        file.save().unwrap();
    }

    fn given_the_data_on_the_disk_with_a_byte_flipped_at(&mut self, at: usize) {
        self.bad = self.data.clone();
        self.bad[at] ^= 1;
        fs::write(self.temp().join("001.part"), &self.bad).unwrap();
    }

    fn given_the_download_cancelled(&mut self) {
        self.when_the_download_is_cancelled();
        self.then_it_is_accepted();
    }

    fn given_the_link_added_again(&mut self) {
        let link = FileLink::of("undo.bin", 5000, self.hash).unwrap();
        self.queue_mut().add(&link, 0, DownloadPriority::Auto).unwrap();
        self.settle();
    }

    fn given_nothing_is_left_in_the_temp_directory_soon(&mut self) {
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && !self.temp_directory_is_empty() {
            self.poll(Duration::from_millis(20));
        }
        self.then_nothing_is_left_in_the_temp_directory();
    }

    fn given_the_files_a_cancel_kept_for_the_number(&mut self, number: u32) {
        let temp = self.temp();
        fs::create_dir_all(&temp).unwrap();
        let base = part_file::file_name(number);
        fs::write(temp.join(format!("{base}.part.met.cancelled")), [1]).unwrap();
        fs::write(temp.join(format!("{base}.part.met.bak.cancelled")), [1]).unwrap();
        fs::write(temp.join(format!("{base}.part")), [1]).unwrap();
    }

    fn given_only_the_set_aside_met_left_for_the_number(&mut self, number: u32) {
        let temp = self.temp();
        fs::create_dir_all(&temp).unwrap();
        fs::write(temp.join(format!("{}.part.met.cancelled", part_file::file_name(number))), [1]).unwrap();
    }

    // Open, as by a virus scanner, with no share of the right to move it.
    #[cfg(windows)]
    fn given_another_program_holds_the_data(&mut self) {
        use std::os::windows::fs::OpenOptionsExt;
        const SHARE_READ_AND_WRITE: u32 = 0x1 | 0x2;
        let data = self.download().file.data_file();
        self.holder = Some(fs::OpenOptions::new().read(true).share_mode(SHARE_READ_AND_WRITE).open(data).unwrap());
    }

    #[cfg(windows)]
    fn given_the_finished_file_not_moved_for(&mut self, wait: Duration) {
        let deadline = Instant::now() + wait;
        while let Some(event) = self.poll(deadline.saturating_duration_since(Instant::now())) {
            assert!(!event.starts_with("completed") && !event.starts_with("failed"), "{event}");
        }
    }

    // Another client's download of the data, half of it there, copied beside ours.
    fn given_a_download_another_client_left(&mut self, name: &str) {
        let other = self.dir.path().join("other");
        fs::create_dir_all(&other).unwrap();
        let size = self.data.len() as u64;
        let mut file = PartFile::create(9, &other, name, size, ed2k::md4(&self.data), None, Vec::new()).unwrap();
        file.set_category(5);
        fs::write(file.data_file(), &self.data[..self.data.len() / 2]).unwrap();
        file.written(0, size / 2);
        file.save().unwrap();
        self.hash = file.hash();
        self.met = file.met_file();
        self.file = file.data_file();
    }

    fn given_the_download_set_as(&mut self, name: &str, category: u32, sources: &[KnownSource]) {
        let hash = self.hash;
        let queue = self.queue_mut();
        queue.set_priority(&hash, DownloadPriority::High);
        queue.set_category(&hash, category);
        queue.rename(&hash, name.to_owned());
        queue.set_auto_swap(&hash, true);
        queue.set_known_sources(&hash, sources);
        queue.set_idle_seconds(&hash, 4000);
        queue.set_idle_seconds(&hash, 4100);
        self.settle();
    }

    fn when_it_is_taken_over(&mut self, category: u32) {
        let (met, data) = (self.met.clone(), self.file.clone());
        self.adopted = Some(self.queue_mut().adopt(&met, &data, category));
        self.settle();
    }

    fn then_it_is_downloaded_as(&mut self, number: u32, name: &str, category: u32) {
        assert_eq!(self.adopted.as_ref().unwrap().as_ref().unwrap(), &self.hash);
        assert_eq!(self.poll(WAIT).as_deref(), Some("added"));
        let file = &self.download().file;
        assert_eq!((file.number(), file.name(), file.category()), (number, name, category));
        assert_eq!(file.completed_bytes(), self.data.len() as u64 / 2);
        assert!(!self.met.exists() && !self.file.exists(), "moved into the temp directory");
    }

    fn then_it_is_not_taken_over(&mut self) {
        let refused = self.adopted.as_ref().unwrap().as_ref().unwrap_err();
        assert_eq!(refused.kind(), std::io::ErrorKind::AlreadyExists);
        assert!(self.met.exists());
    }

    fn then_the_reloaded_download_is_set_as(&mut self, name: &str, category: u32, sources: &[KnownSource]) {
        let file = &self.download().file;
        assert_eq!((file.name(), file.category(), file.priority()), (name, category, DownloadPriority::High));
        assert!(file.auto_swap());
        assert_eq!(file.known_sources(), sources);
        assert_eq!(file.idle_seconds(), 4100);
    }

    #[cfg(windows)]
    fn when_the_other_program_lets_it_go(&mut self) {
        self.holder = None;
    }

    fn when_the_data_is_received_in_two_pieces_last_first(&mut self, middle: usize) {
        let data = self.data.clone();
        self.receive(middle as u64, &data[middle..]);
        self.receive(0, &data[..middle]);
    }

    fn when_the_first_part_is_received_again(&mut self) {
        let piece = self.data[..PARTSIZE as usize].to_vec();
        self.receive(0, &piece);
    }

    fn when_the_link_is_added(&mut self) {
        let link = self.link.clone().unwrap();
        self.add_download(link);
    }

    #[cfg(windows)]
    fn when_the_blocks_are_asked_for_after_a_pause_and_resume(&mut self) {
        let hash = self.hash;
        self.queue_mut().pause(&hash);
        self.queue_mut().resume(&hash);
        self.blocks = self.next_blocks();
    }

    fn when_the_data_is_received(&mut self) {
        self.receive(0, &self.data.clone());
    }

    fn when_the_download_is_paused_and_resumed(&mut self) {
        let hash = self.hash;
        self.queue_mut().pause(&hash);
        self.queue_mut().resume(&hash);
        self.settle();
    }

    // An empty piece first, then the pieces from the end to the start.
    fn when_the_data_is_received_backwards_in_pieces_of(&mut self, piece: usize) {
        let data = self.data.clone();
        self.receive(0, &[]);
        for end in (1..=data.len()).rev().step_by(piece) {
            let start = end.saturating_sub(piece);
            self.receive(start as u64, &data[start..end]);
        }
    }

    fn when_the_queue_is_loaded_again(&mut self) {
        self.load();
    }

    fn when_a_download_is_added(&mut self, name: &str, size: u64, hash: [u8; 16]) {
        self.given_a_download(name, size, hash);
    }

    fn when_the_data_is_received_and_the_download_cancelled_at_once(&mut self) {
        let (hash, data) = (self.hash, self.data.clone());
        let queue = self.queue_mut();
        queue.received(&hash, 0, &data, None);
        queue.cancel(&hash);
        self.settle();
    }

    fn when_data_is_received_at(&mut self, start: u64, length: usize) {
        self.receive(start, &vec![0; length]);
    }

    fn when_the_download_is_set_to_preview_first(&mut self) {
        let hash = self.hash;
        self.queue_mut().set_preview_first(&hash, true);
        self.settle();
    }

    fn when_the_download_is_resumed(&mut self) {
        let hash = self.hash;
        self.accepted = self.queue_mut().resume(&hash);
        self.settle();
    }

    fn when_recovery_data_arrives_before_and_after_the_cancel(&mut self) {
        let hash = self.hash;
        let queue = self.queue_mut();
        queue.recovered(&hash, 0, Vec::new());
        queue.cancel(&hash);
        queue.recovered(&hash, 0, Vec::new());
        self.settle();
    }

    // Hashes that prove the blocks on the disk, wrong as they are: the part is whole again, and checked again.
    fn when_recovery_data_proving_the_blocks_on_the_disk_is_received(&mut self) {
        let (hash, blocks) = (self.hash, block_hashes(&self.bad));
        self.queue_mut().recovered(&hash, 0, blocks);
        self.settle();
    }

    fn when_the_free_space_is_checked(&mut self) {
        self.check_the_free_space();
    }

    fn when_the_download_is_cancelled(&mut self) {
        let hash = self.hash;
        self.accepted = self.queue_mut().cancel(&hash);
        self.settle();
    }

    fn when_the_download_is_taken_back(&mut self) {
        let hash = self.hash;
        self.restored = self.queue_mut().restore(&hash);
        self.settle();
    }

    fn then_the_download_completes_with_the_data(&mut self) {
        let completed = self.await_prefix("completed ");
        self.file = PathBuf::from(completed.strip_prefix("completed ").unwrap());
        assert_eq!(fs::read(&self.file).unwrap(), self.data);
    }

    fn then_the_completed_file_is_in_the_incoming_directory(&mut self) {
        assert_eq!(self.file.parent().unwrap(), self.incoming());
    }

    fn then_the_completed_file_is_named(&mut self, name: &str) {
        assert_eq!(self.file.file_name().unwrap(), name);
    }

    fn then_the_completed_file_has_the_size(&mut self, size: u64) {
        assert_eq!(fs::metadata(&self.file).unwrap().len(), size);
    }

    fn then_the_temp_directory_has_no(&mut self, names: &[&str]) {
        for name in names {
            assert!(!self.temp().join(name).exists(), "{name}");
        }
    }

    fn then_the_download_is_announced(&mut self) {
        assert_eq!(self.poll(WAIT).as_deref(), Some("added"));
    }

    fn then_the_download_has_no_hash_set(&mut self) {
        assert!(!self.download().file.has_hash_set(), "asked from the sources");
    }

    #[cfg(windows)]
    fn then_the_blocks_asked_for_are(&mut self, expected: &[Block]) {
        assert_eq!(self.blocks, expected);
    }

    fn then_the_download_was_kept_after_the_cancel(&mut self) {
        assert_eq!(self.kept_after_cancel, Some(true), "the cancel waits for nothing it could undo");
    }

    fn then_the_queue_is_empty(&mut self) {
        assert_eq!(self.queue().all().count(), 0);
    }

    fn then_nothing_is_left_in_the_temp_directory(&mut self) {
        let left: Vec<_> = fs::read_dir(self.temp()).unwrap().map(|entry| entry.unwrap().file_name()).collect();
        assert!(left.is_empty(), "nothing left behind: {left:?}");
    }

    fn then_the_disk_thread_of_the_download_ends(&mut self) {
        assert!(self.closed.as_ref().unwrap().wait(WAIT), "its disk thread ends");
    }

    fn then_the_queue_no_longer_has_the_download(&mut self) {
        assert!(self.queue().get(&self.hash).is_none());
    }

    fn then_only_the_first_of_them_is_loaded(&mut self) {
        let numbers: Vec<u32> = self.queue().all().map(|d| d.file.number()).collect();
        assert_eq!(numbers, [1], "the first is kept");
    }

    fn then_the_queue_closes(&mut self) {
        self.close_the_queue();
    }

    fn then_the_reloaded_download_is_named(&mut self, name: &str) {
        assert_eq!(self.download().file.name(), name);
    }

    fn then_the_reloaded_download_has_completed(&mut self, bytes: u64) {
        assert_eq!(self.download().file.completed_bytes(), bytes);
    }

    fn then_the_corrupt_data_of_the_reloaded_download_is_taken_back(&mut self) {
        let deadline = Instant::now() + WAIT;
        while self.download().file.completed_bytes() != 0 {
            assert!(Instant::now() < deadline, "the corrupt data is not taken back");
            self.poll(Duration::from_millis(100));
        }
    }

    fn then_no_part_of_the_reloaded_download_is_verified(&mut self) {
        assert!(!self.download().verified_parts().contains(&true));
    }

    fn then_the_verified_parts_of_the_reloaded_download_are(&mut self, expected: &[bool]) {
        assert_eq!(
            self.download().verified_parts(),
            expected,
            "checked before the restart, on data that did not change since"
        );
    }

    fn then_its_number_is(&mut self, number: u32) {
        assert_eq!(self.download().file.number(), number);
    }

    fn then_no_event_comes_within(&mut self, wait: Duration) {
        assert_eq!(self.poll(wait), None);
    }

    fn then_nothing_is_completed(&mut self) {
        match self.queue().get(&self.hash) {
            Some(download) => assert_eq!(download.file.completed_bytes(), 0),
            None => assert!(!self.incoming().exists(), "nothing reached the incoming directory"),
        }
    }

    // What a cancel leaves to other threads (the queued writes and checks, the save, the set-aside, the deletes) may
    // outlast the test; closing waits for it.
    fn then_its_files_are_gone_once_the_queue_closes(&mut self) {
        self.close_the_queue();
        self.then_nothing_is_left_in_the_temp_directory();
    }

    fn then_the_leftover_part_file_is_kept(&mut self) {
        assert_eq!(fs::read(self.temp().join("001.part.met")).unwrap(), [1, 2, 3]);
    }

    fn then_the_download_has_no_sources(&mut self) {
        assert!(self.download().link_sources().is_empty());
    }

    fn then_the_download_is_previewed_first(&mut self) {
        assert!(self.download().file.preview_first());
    }

    fn then_it_is_accepted(&mut self) {
        assert!(self.accepted);
    }

    fn then_the_download_fails_with(&mut self, reason: &str) {
        assert!(self.await_prefix("failed").contains(reason));
    }

    fn then_the_download_is_in_error(&mut self) {
        assert_eq!(self.download().file.status(), Status::Error);
    }

    fn then_the_download_progresses(&mut self) {
        assert_eq!(self.await_prefix("progress"), "progress");
    }

    fn then_the_download_does_not_complete(&mut self) {
        assert_eq!(self.poll(Duration::from_millis(500)), None, "no completion");
    }

    fn then_the_next_event_is(&mut self, event: &str) {
        assert_eq!(self.poll(Duration::from_millis(500)).as_deref(), Some(event));
    }

    fn then_the_part_is_found_corrupt(&mut self, part: usize) {
        assert_eq!(self.await_prefix("corrupt"), format!("corrupt {part}"));
    }

    fn then_the_space_is_not_short(&mut self) {
        assert!(!self.queue().is_space_short());
    }

    fn then_the_next_space_event_is(&mut self, event: &str) {
        assert_eq!(self.await_prefix("space"), event);
    }

    fn then_the_download_is_active(&mut self) {
        assert!(self.download().is_active());
    }

    fn then_its_part_file_is_gone(&mut self) {
        assert!(!self.met.exists());
    }

    fn then_its_backup_is_left_behind(&mut self) {
        assert!(atomic_files::bak_path(&self.met).is_dir());
    }

    // Set aside for an undo, then left where nothing loads it; the next start tries again to delete it.
    fn then_its_backup_is_left_behind_set_aside(&mut self) {
        let bak = atomic_files::bak_path(&self.met);
        let aside = bak.with_file_name(format!("{}.cancelled", bak.file_name().unwrap().to_string_lossy()));
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && !aside.is_dir() {
            self.poll(Duration::from_millis(20));
        }
        assert!(aside.is_dir());
    }

    fn then_its_data_is_kept_but_no_start_loads_it(&mut self) {
        assert!(self.closed.as_ref().unwrap().wait(WAIT));
        let base = part_file::file_name(1);
        let temp = self.temp();
        assert!(temp.join(format!("{base}.part")).exists());
        assert!(!temp.join(format!("{base}.part.met")).exists());
        assert!(temp.join(format!("{base}.part.met.cancelled")).exists());
    }

    fn then_it_is_back_paused(&mut self) {
        assert!(self.restored);
        assert_eq!(self.download().file.status(), Status::Paused);
        assert!(self.temp().join("001.part.met").exists());
    }

    fn then_nothing_is_taken_back(&mut self) {
        assert!(!self.restored);
    }

    fn temp(&self) -> PathBuf {
        self.dir.path().join("temp")
    }

    fn incoming(&self) -> PathBuf {
        self.dir.path().join("incoming")
    }

    fn queue(&self) -> &DownloadQueue {
        self.queue.as_ref().unwrap()
    }

    fn queue_mut(&mut self) -> &mut DownloadQueue {
        self.queue.as_mut().unwrap()
    }

    fn download(&self) -> &njmule_engine::download::download_queue::Download {
        self.queue().get(&self.hash).expect("the download")
    }

    fn temp_directory_is_empty(&self) -> bool {
        fs::read_dir(self.temp()).unwrap().next().is_none()
    }

    // A queue as the engine has one; the one before, closed or not, goes with its disk threads.
    fn load(&mut self) {
        self.queue = None;
        let (done, disk) = mpsc::unbounded_channel();
        let incoming = self.incoming();
        let min_free = Arc::clone(&self.min_free);
        let mut queue = DownloadQueue::load(
            &self.temp(),
            Box::new(move |_: &PartFile| incoming.clone()),
            Box::new(move || min_free.load(Ordering::Relaxed)),
            done,
        )
        .unwrap();
        queue.keep_cancelled_for(self.grace);
        self.queue = Some(queue);
        self.disk = Some(disk);
        self.settle();
    }

    fn add_download(&mut self, link: FileLink) {
        self.hash = self.queue_mut().add(&link, 0, DownloadPriority::Auto).unwrap();
        self.closed = Some(self.download().disk_closed());
        self.link = Some(link);
        self.settle();
    }

    fn receive(&mut self, start: u64, data: &[u8]) {
        let hash = self.hash;
        self.queue_mut().received(&hash, start, data, None);
        self.settle();
    }

    // The sources have the first part.
    #[cfg(windows)]
    fn next_blocks(&mut self) -> Vec<Block> {
        let hash = self.hash;
        let file = &mut self.queue_mut().get_mut(&hash).unwrap().file;
        file.next_blocks(&[true], 3, &mut StdRng::seed_from_u64(1))
    }

    fn check_the_free_space(&mut self) {
        self.queue_mut().check_space();
        self.settle();
    }

    fn close_the_queue(&mut self) {
        self.queue_mut().close();
        self.settle();
    }

    // What the queue told, as the test's listener hears it.
    fn settle(&mut self) {
        loop {
            let events = self.queue_mut().take_events();
            if events.is_empty() {
                break;
            }
            for event in events {
                self.hear(event);
            }
        }
        // The user cancels and adds the link again while the finished file is being moved.
        if self.listener == Listener::CancelAndAddWhileMoved
            && !self.moved_while_finishing
            && self.queue.as_ref().and_then(|q| q.get(&self.hash)).is_some_and(|d| d.is_finishing())
        {
            self.moved_while_finishing = true;
            let (hash, link) = (self.hash, self.link.clone().unwrap());
            let queue = self.queue_mut();
            queue.cancel(&hash);
            let kept = queue.get(&hash).is_some();
            queue.add(&link, 0, DownloadPriority::Auto).unwrap();
            self.kept_after_cancel = Some(kept);
        }
    }

    fn hear(&mut self, event: QueueEvent) {
        let told = match event {
            QueueEvent::Added(_) => Some("added".to_owned()),
            QueueEvent::Progress(hash) => {
                if self.listener == Listener::CancelOnProgress {
                    // The check of the part just written is on its way.
                    self.queue_mut().cancel(&hash);
                }
                Some("progress".to_owned())
            }
            QueueEvent::Completed { path, .. } => Some(format!("completed {}", path.display())),
            QueueEvent::Failed { reason, .. } => Some(format!("failed {reason}")),
            QueueEvent::Corrupt { part, .. } => (self.listener != Listener::Plain).then(|| format!("corrupt {part}")),
            QueueEvent::DiskSpace(enough) => (self.listener == Listener::Plain)
                .then(|| if enough { "space enough" } else { "space short" }.to_owned()),
            QueueEvent::CorruptSenders { .. } => None,
        };
        self.events.extend(told);
    }

    // The next event within `wait`, while the disk work comes back and the queue's clock goes on.
    fn poll(&mut self, wait: Duration) -> Option<String> {
        let deadline = Instant::now() + wait;
        loop {
            if let Some(event) = self.events.pop_front() {
                return Some(event);
            }
            if Instant::now() >= deadline {
                return None;
            }
            self.queue_mut().tick(Instant::now());
            let done = self.disk.as_mut().unwrap().try_recv();
            match done {
                Ok(done) => self.queue_mut().disk_done(done),
                Err(_) => std::thread::sleep(Duration::from_millis(5)),
            }
            self.settle();
        }
    }

    fn await_prefix(&mut self, prefix: &str) -> String {
        let deadline = Instant::now() + WAIT;
        while Instant::now() < deadline {
            let Some(event) = self.poll(Duration::from_millis(100)) else {
                continue;
            };
            if event.starts_with(prefix) {
                return event;
            }
            if !prefix.starts_with("failed") {
                assert!(!event.starts_with("failed"), "unexpected failure: {event}");
            }
        }
        panic!("no event {prefix}");
    }
}

fn random(size: usize, seed: u64) -> Vec<u8> {
    let mut data = vec![0u8; size];
    StdRng::seed_from_u64(seed).fill(&mut data[..]);
    data
}

// One per eD2K part, with the empty hash of the extra part of a size that is a multiple of the part size.
fn part_hashes(data: &[u8]) -> Vec<[u8; 16]> {
    (0..ed2k::ed2k_part_count(data.len() as u64))
        .map(|part| {
            let start = ed2k::part_start(part) as usize;
            ed2k::md4(&data[start..data.len().min(start + PARTSIZE as usize)])
        })
        .collect()
}

fn block_hashes(data: &[u8]) -> Vec<[u8; 20]> {
    data.chunks(EMBLOCKSIZE as usize).map(|block| Sha1::digest(block).into()).collect()
}

fn hex(byte: u8) -> [u8; 16] {
    [byte; 16]
}

#[test]
fn should_write_verify_and_move_a_finished_file() {
    let mut queue = Queue::new();
    queue.given_the_data(random(400_000, 1));
    queue.given_a_queue();
    queue.given_a_download_of_the_data("../evil:name.bin");
    queue.given_the_download_was_announced();

    queue.when_the_data_is_received_in_two_pieces_last_first(200_000);

    queue.then_the_download_completes_with_the_data();
    queue.then_the_completed_file_is_in_the_incoming_directory();
    queue.then_the_completed_file_is_named(".._evil_name.bin");
    queue.then_the_temp_directory_has_no(&["001.part", "001.part.met"]);
}

#[test]
fn should_download_a_corrupt_part_again() {
    let mut queue = Queue::new();
    queue.given_the_data(random(PARTSIZE as usize + 1000, 2));
    queue.given_a_queue();
    queue.given_a_download_of_the_data_with_its_part_hashes("two.bin");
    queue.given_the_data_received_with_a_byte_flipped_at(123);
    queue.given_the_corrupt_first_part_taken_back();

    queue.when_the_first_part_is_received_again();

    queue.then_the_download_completes_with_the_data();
}

#[test]
fn should_still_download_a_link_with_a_wrong_hash_set() {
    let mut queue = Queue::new();
    queue.given_a_queue();
    queue.given_a_link_with_a_wrong_hash_set();

    queue.when_the_link_is_added();

    queue.then_the_download_is_announced();
    queue.then_the_download_has_no_hash_set();
}

#[cfg(windows)]
#[test]
fn should_ask_again_for_a_range_that_could_not_be_written() {
    let mut queue = Queue::new();
    queue.given_the_data(random(400_000, 5));
    queue.given_a_queue();
    queue.given_a_download_of_the_data("locked.bin");
    queue.given_the_download_was_announced();
    queue.given_the_first_blocks_asked_for();
    queue.given_the_first_block_could_not_be_written_while_another_process_locked_the_data();

    queue.when_the_blocks_are_asked_for_after_a_pause_and_resume();

    queue.then_the_blocks_asked_for_are(&[Block { start: 0, end: EMBLOCKSIZE }]);
}

#[test]
fn should_not_cancel_a_download_while_its_file_is_moved() {
    let mut queue = Queue::new();
    queue.given_the_data(random(1000, 6));
    queue.given_a_queue_whose_user_cancels_and_adds_the_download_again_while_its_file_is_moved();
    queue.given_a_download_of_the_data("moved.bin");

    queue.when_the_data_is_received();

    queue.then_the_download_completes_with_the_data();
    queue.then_the_download_was_kept_after_the_cancel();
    queue.then_the_queue_is_empty();
    queue.then_nothing_is_left_in_the_temp_directory();
    queue.then_the_disk_thread_of_the_download_ends();
}

#[test]
fn should_move_on_resume_a_file_that_could_not_be_moved() {
    let mut queue = Queue::new();
    queue.given_the_data(random(2000, 7));
    queue.given_a_file_where_the_incoming_directory_should_be();
    queue.given_a_queue();
    queue.given_a_download_of_the_data("stuck.bin");
    queue.given_the_data_received();
    queue.given_the_download_failed_with("cannot move");
    queue.given_the_download_went_into_error();
    queue.given_the_incoming_directory_freed();

    queue.when_the_download_is_paused_and_resumed();

    queue.then_the_download_completes_with_the_data();
    queue.then_the_queue_no_longer_has_the_download();
}

#[test]
fn should_load_two_part_files_of_one_file_as_one_download() {
    let mut queue = Queue::new();
    queue.given_a_queue();
    queue.given_a_download("twice.bin", 1000, hex(0x9c));
    queue.given_the_queue_closed();
    queue.given_the_part_files_copied("001", "002");

    queue.when_the_queue_is_loaded_again();

    queue.then_only_the_first_of_them_is_loaded();
    queue.then_the_queue_closes();
}

#[test]
fn should_complete_files_of_exactly_one_part_and_one_byte_more() {
    for size in [PARTSIZE, PARTSIZE + 1] {
        let mut queue = Queue::new();
        queue.given_the_data(random(size as usize, size));
        queue.given_a_queue();
        queue.given_a_download_of_the_data_with_its_part_hashes(&format!("edge{size}.bin"));
        queue.given_the_download_has_its_hash_set();

        queue.when_the_data_is_received_backwards_in_pieces_of(1_000_000);

        queue.then_the_download_completes_with_the_data();
        queue.then_the_completed_file_has_the_size(size);
    }
}

#[test]
fn should_reload_downloads_from_their_part_files() {
    let mut queue = Queue::new();
    queue.given_the_data(random(300_000, 3));
    queue.given_a_queue();
    queue.given_a_download_of_the_data("kept.bin");
    queue.given_the_data_received_up_to(100_000);
    queue.given_the_queue_closed();

    queue.when_the_queue_is_loaded_again();

    queue.then_the_reloaded_download_is_named("kept.bin");
    queue.then_the_reloaded_download_has_completed(100_000);
}

#[test]
fn should_check_the_complete_parts_of_a_resumed_download() {
    let mut queue = Queue::new();
    queue.given_the_data(random(200_000, 4));
    queue.given_a_queue();
    queue.given_a_download_of_the_data("resumed.bin");
    queue.given_the_data_marked_written_but_not_checked();
    queue.given_the_data_on_the_disk_with_a_byte_flipped_at(7);
    queue.given_the_queue_closed();

    queue.when_the_queue_is_loaded_again();

    queue.then_the_corrupt_data_of_the_reloaded_download_is_taken_back();
    queue.then_no_part_of_the_reloaded_download_is_verified();
}

#[test]
fn should_load_a_download_of_which_only_the_backup_is_left_and_never_reuse_its_number() {
    let mut queue = Queue::new();
    queue.given_a_queue();
    queue.given_a_download("kept.bin", 1000, hex(0x12));
    queue.given_the_queue_closed();
    queue.given_only_the_backup_of_the_part_file_left("001");
    queue.given_a_data_file_left_by_a_crash("002");
    queue.given_the_queue_loaded_again();
    queue.given_the_queue_has_the_download();

    queue.when_a_download_is_added("new.bin", 1000, hex(0x34));

    queue.then_its_number_is(3);
    queue.then_the_queue_closes();
}

#[test]
fn should_ignore_a_write_that_ends_after_the_cancel() {
    let mut queue = Queue::new();
    queue.given_the_data(random(1000, 3));
    queue.given_a_queue();
    queue.given_a_download_of_the_data("gone.bin");
    queue.given_the_download_was_announced();

    queue.when_the_data_is_received_and_the_download_cancelled_at_once();

    queue.then_no_event_comes_within(Duration::from_secs(1));
    queue.then_nothing_is_completed();
    queue.then_its_files_are_gone_once_the_queue_closes();
}

#[test]
fn should_keep_the_numbers_of_leftovers_in_the_temp_directory() {
    let mut queue = Queue::new();
    queue.given_leftovers_in_the_temp_directory();
    queue.given_a_queue();
    queue.given_the_unreadable_part_files_left_alone();

    queue.when_a_download_is_added("fresh.bin", 5000, hex(0x13));

    queue.then_its_number_is(5);
    queue.then_the_leftover_part_file_is_kept();
}

#[test]
fn should_take_no_data_beyond_the_end_of_a_download() {
    let mut queue = Queue::new();
    queue.given_a_queue();
    queue.given_a_download("small.bin", 5000, hex(0x14));
    queue.given_the_download_was_announced();

    queue.when_data_is_received_at(4990, 20);

    queue.then_no_event_comes_within(Duration::from_millis(300));
    queue.then_nothing_is_completed();
}

#[test]
fn should_take_no_data_for_a_paused_download() {
    let mut queue = Queue::new();
    queue.given_a_queue();
    queue.given_a_download("small.bin", 5000, hex(0x14));
    queue.given_the_download_was_announced();
    queue.given_the_download_paused();

    queue.when_data_is_received_at(0, 100);

    queue.then_no_event_comes_within(Duration::from_millis(300));
    queue.then_nothing_is_completed();
    queue.then_the_download_has_no_sources();
}

#[test]
fn should_set_a_paused_download_to_preview_first() {
    let mut queue = Queue::new();
    queue.given_a_queue();
    queue.given_a_download("small.bin", 5000, hex(0x14));
    queue.given_the_download_was_announced();
    queue.given_the_download_paused();

    queue.when_the_download_is_set_to_preview_first();

    queue.then_no_event_comes_within(Duration::from_millis(300));
    queue.then_the_download_is_previewed_first();
}

#[test]
fn should_check_a_part_once_its_hash_is_known_and_trust_it_after_a_restart() {
    let mut queue = Queue::new();
    queue.given_the_data(random(1000, 8));
    queue.given_a_queue();
    queue.given_a_download_without_hash_set_whose_second_part_is_the_data("late.bin");
    queue.given_the_second_part_received();
    queue.given_no_part_verified_for_want_of_its_hash();
    queue.given_the_hash_set_known();
    queue.given_the_second_part_verified();
    queue.given_the_queue_closed();

    queue.when_the_queue_is_loaded_again();

    queue.then_the_verified_parts_of_the_reloaded_download_are(&[false, true]);
    queue.then_the_queue_closes();
}

#[test]
fn should_move_a_finished_download_that_could_not_be_moved_when_loaded_again() {
    let mut queue = Queue::new();
    queue.given_the_data(random(5000, 9));
    queue.given_a_file_where_the_incoming_directory_should_be();
    queue.given_a_queue();
    queue.given_a_download_of_the_data("again.bin");
    queue.given_the_data_received();
    queue.given_the_download_failed_with("cannot move");
    queue.given_the_queue_closed();
    queue.given_the_incoming_directory_freed();

    queue.when_the_queue_is_loaded_again();

    queue.then_the_download_completes_with_the_data();
}

#[test]
fn should_fail_a_download_whose_data_cannot_be_opened_again() {
    let mut queue = Queue::new();
    queue.given_the_data(random(5000, 10));
    queue.given_a_file_where_the_incoming_directory_should_be();
    queue.given_a_queue();
    queue.given_a_download_of_the_data("lost.bin");
    queue.given_the_data_received();
    queue.given_the_download_failed_with("cannot move");
    queue.given_a_directory_in_the_way_of_its_data();

    queue.when_the_download_is_resumed();

    queue.then_it_is_accepted();
    queue.then_the_download_fails_with("cannot open the data");
    queue.then_the_download_is_in_error();
}

#[test]
fn should_ignore_a_check_that_ends_after_the_cancel() {
    let mut queue = Queue::new();
    queue.given_the_data(random(400_000, 11));
    queue.given_a_queue_whose_listener_cancels_a_download_on_its_progress();
    queue.given_a_download_of_the_data("checked.bin");

    queue.when_the_data_is_received();

    queue.then_the_download_progresses();
    queue.then_the_download_does_not_complete();
    queue.then_its_files_are_gone_once_the_queue_closes();
}

#[test]
fn should_ignore_recoveries_that_end_after_the_cancel() {
    let mut queue = Queue::new();
    queue.given_a_queue_whose_listener_cancels_a_download_on_its_progress();
    queue.given_a_download("recovered.bin", 400_000, hex(0x16));

    queue.when_recovery_data_arrives_before_and_after_the_cancel();

    queue.then_the_next_event_is("added");
    queue.then_no_event_comes_within(Duration::from_millis(500));
    queue.then_its_files_are_gone_once_the_queue_closes();
}

#[test]
fn should_take_back_only_the_blocks_whose_recovery_hashes_match() {
    let mut queue = Queue::new();
    queue.given_the_data(random(400_000, 12));
    queue.given_a_queue_that_tells_every_event();
    queue.given_a_download_of_the_data("aich.bin");
    queue.given_the_data_received_with_a_byte_flipped_at(399_999);
    queue.given_the_part_found_corrupt(0);
    queue.given_recovery_data_received(vec![[0; 20]]);
    queue.given_the_recovery_hashes_did_not_fit_the_part();

    queue.when_recovery_data_proving_the_blocks_on_the_disk_is_received();

    queue.then_the_part_is_found_corrupt(0);
}

#[test]
fn should_change_nothing_for_the_free_space_of_a_vanished_temp_directory() {
    let mut queue = Queue::new();
    queue.given_a_minimum_free_space_of(1);
    queue.given_a_queue();
    queue.given_the_temp_directory_deleted();

    queue.when_the_free_space_is_checked();

    queue.then_the_space_is_not_short();
    queue.then_no_event_comes_within(Duration::from_millis(200));
}

#[test]
fn should_leave_behind_the_backup_of_a_finished_download_that_cannot_be_deleted() {
    let mut queue = Queue::new();
    queue.given_the_data(random(5000, 13));
    queue.given_a_queue();
    queue.given_a_download_of_the_data("done.bin");
    queue.given_a_directory_in_the_way_of_deleting_its_backup();

    queue.when_the_data_is_received();

    queue.then_the_download_completes_with_the_data();
    queue.then_its_part_file_is_gone();
    queue.then_its_backup_is_left_behind();
}

#[test]
fn should_leave_behind_the_part_files_of_a_cancelled_download_that_cannot_be_saved() {
    let mut queue = Queue::new();
    queue.given_a_queue();
    queue.given_a_download("kept.bin", 5000, hex(0x17));
    queue.given_directories_in_the_way_of_saving_and_deleting_its_part_file();
    queue.given_the_download_paused();
    queue.given_the_queue_closed();
    queue.given_the_paused_state_could_not_be_saved();

    queue.when_the_download_is_cancelled();

    queue.then_it_is_accepted();
    queue.then_the_queue_is_empty();
    queue.then_its_backup_is_left_behind_set_aside();
}

#[test]
fn should_let_downloads_wait_for_free_space_and_go_on_by_themselves() {
    let mut queue = Queue::new();
    queue.given_a_queue();
    queue.given_a_download("wait.bin", 5000, hex(0xab));
    queue.given_the_download_is_active();
    queue.given_a_minimum_free_space_of(u64::MAX);
    queue.given_the_free_space_checked();
    queue.given_the_download_waits_for_free_space();
    queue.given_a_minimum_free_space_of(1);

    queue.when_the_free_space_is_checked();

    queue.then_the_next_space_event_is("space enough");
    queue.then_the_download_is_active();
}

#[test]
fn should_keep_the_files_of_a_cancelled_download_while_it_can_be_taken_back() {
    let mut queue = Queue::new();
    queue.given_a_queue_keeping_cancelled_downloads_for(Duration::from_secs(60));
    queue.given_a_download("undo.bin", 5000, hex(0x21));

    queue.when_the_download_is_cancelled();

    queue.then_its_data_is_kept_but_no_start_loads_it();
}

#[test]
fn should_take_back_a_cancelled_download_as_it_was() {
    let mut queue = Queue::new();
    queue.given_a_queue_keeping_cancelled_downloads_for(Duration::from_secs(60));
    queue.given_a_download("undo.bin", 5000, hex(0x22));
    queue.given_the_download_paused();
    queue.given_the_download_cancelled();

    queue.when_the_download_is_taken_back();

    queue.then_it_is_back_paused();
}

#[test]
fn should_delete_the_files_of_a_cancelled_download_once_it_can_no_longer_be_taken_back() {
    let mut queue = Queue::new();
    queue.given_a_queue_keeping_cancelled_downloads_for(Duration::from_millis(100));
    queue.given_a_download("undo.bin", 5000, hex(0x23));
    queue.given_the_download_cancelled();
    queue.given_nothing_is_left_in_the_temp_directory_soon();

    queue.when_the_download_is_taken_back();

    queue.then_nothing_is_taken_back();
}

#[test]
fn should_not_take_back_a_cancelled_download_whose_link_was_added_again() {
    let mut queue = Queue::new();
    queue.given_a_queue_keeping_cancelled_downloads_for(Duration::from_secs(60));
    queue.given_a_download("undo.bin", 5000, hex(0x24));
    queue.given_the_download_cancelled();
    queue.given_the_link_added_again();

    queue.when_the_download_is_taken_back();

    queue.then_nothing_is_taken_back();
}

// What a cancel set aside goes last: until then the next start deletes it, and no new download may take its number.
#[test]
fn should_not_give_a_new_download_the_number_of_a_met_set_aside_by_a_cancel() {
    let mut queue = Queue::new();
    queue.given_a_queue();
    queue.given_only_the_set_aside_met_left_for_the_number(1);

    queue.when_a_download_is_added("new.bin", 1000, hex(0x35));

    queue.then_its_number_is(2);
    queue.then_the_queue_closes();
}

#[test]
fn should_delete_what_a_cancel_kept_when_nj_mule_stopped() {
    let mut queue = Queue::new();
    queue.given_the_files_a_cancel_kept_for_the_number(7);

    queue.when_the_queue_is_loaded_again();

    queue.then_the_queue_is_empty();
    queue.then_nothing_is_left_in_the_temp_directory();
}

// Rust only: a finished file another program holds is moved once it is let go.
#[cfg(windows)]
#[test]
fn should_move_a_finished_file_again_once_another_program_lets_it_go() {
    let mut queue = Queue::new();
    queue.given_the_data(random(5000, 14));
    queue.given_a_queue();
    queue.given_a_download_of_the_data("held.bin");
    queue.given_another_program_holds_the_data();
    queue.given_the_data_received();
    queue.given_the_finished_file_not_moved_for(Duration::from_millis(1500));

    queue.when_the_other_program_lets_it_go();

    queue.then_the_download_completes_with_the_data();
}

// Rust only: another client's download goes on here under our next number, with a safe name.
#[test]
fn should_take_over_a_download_another_client_left() {
    let mut queue = Queue::new();
    queue.given_the_data(random(3000, 15));
    queue.given_a_queue();
    queue.given_a_download_another_client_left("a:b.bin");

    queue.when_it_is_taken_over(2);

    queue.then_it_is_downloaded_as(1, "a_b.bin", 2);
}

// Rust only: a file downloaded already is not taken over twice.
#[test]
fn should_not_take_over_a_download_of_a_file_downloaded_already() {
    let mut queue = Queue::new();
    queue.given_the_data(random(3000, 16));
    queue.given_a_queue();
    queue.given_a_download_of_the_data("mine.bin");
    queue.given_a_download_another_client_left("theirs.bin");

    queue.when_it_is_taken_over(0);

    queue.then_it_is_not_taken_over();
}

// Rust only: what the user and the sources set on a download is there after a restart.
#[test]
fn should_keep_what_is_set_on_a_download_across_a_restart() {
    let sources = [KnownSource { ip: 0x0A00_0002, port: 4662, seen: 1_760_000_000, user_hash: None, crypt_options: 1 }];
    let mut queue = Queue::new();
    queue.given_a_queue();
    queue.given_a_download("set.bin", 5000, hex(0x41));
    queue.given_the_download_set_as("renamed.bin", 3, &sources);
    queue.given_the_queue_closed();

    queue.when_the_queue_is_loaded_again();

    queue.then_the_reloaded_download_is_set_as("renamed.bin", 3, &sources);
}
