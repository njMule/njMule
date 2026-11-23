// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's SharedFilesScanTest, under its names: how the scan of shared directories picks files.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::VecDeque;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use njmule_engine::files::hash::ed2k;
use njmule_engine::files::shared_files::{self, Changes, Found, SharedFiles, Sharing};
use njmule_engine::persist::format::known_met::{self, KnownFile};
use njmule_engine::persist::format::known2_met::{self, Entry};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};

const REMEMBERED: [u8; 16] = [0x5e; 16];

#[test]
fn should_never_share_files_other_programs_leave_behind_or_are_still_writing() {
    let mut lab = Lab::new();
    lab.given_the_incoming_directory_holds(&[
        "real.bin",
        "~$report.docx",
        "._photo.jpg",
        ".~lock.notes.odt#",
        "ehthumbs.db",
        ".nfs0000000001",
        ".syncthing.film.avi",
        ".sb-1234-abcd",
        "video.mp4.crdownload",
        "page.html.partial",
        "song.mp3.download",
        "movie.mkv.!qB",
        "album.zip.!ut",
        "draft.txt~",
    ]);

    lab.when_the_directories_are_scanned();

    lab.then_only_these_are_shared(&["real.bin"]);
}

#[test]
fn should_share_a_file_another_program_locks_for_a_moment_once_it_is_free() {
    let mut lab = Lab::new();
    lab.given_the_incoming_directory_holds(&["locked.bin"]);
    let releaser = lab.given_another_program_locks_it_for("locked.bin", Duration::from_millis(1200));

    lab.when_the_directories_are_scanned();

    if let Some(handle) = releaser {
        handle.join().unwrap();
    }
    lab.then_only_these_are_shared(&["locked.bin"]);
}

#[test]
fn should_share_with_its_new_content_a_file_that_changes_while_it_is_hashed() {
    let mut lab = Lab::new();
    lab.given_the_incoming_directory_holds(&[]);
    lab.given_another_directory_holds("changing.bin", &bytes(3000, 7));

    lab.when_that_directory_is_shared_and_the_file_changes_before_hashing(&bytes(5000, 8));

    lab.then_the_file_is_shared_with_its_content_and_date_now("changing.bin");
}

#[test]
fn a_known_file_whose_date_moved_as_on_fat_or_across_daylight_saving_is_not_hashed_again() {
    for seconds in [2i64, -2, 3600, -3600, 3602, -3598] {
        let mut lab = Lab::new();
        lab.given_the_incoming_directory_holds(&["dated.bin"]);
        lab.given_known_met_remembers_it_under_a_hash_of_its_own_at_a_date_off_by("dated.bin", seconds);

        lab.when_the_directories_are_scanned();

        lab.then_it_is_shared_with_the_remembered_hash("dated.bin");
        lab.then_known_met_holds_one_record_of_it_at_the_date_it_has_now("dated.bin");
    }
}

#[test]
fn a_known_file_whose_date_moved_otherwise_is_hashed_again() {
    for seconds in [3i64, -3, 60, 3597, 3603, 7200] {
        let mut lab = Lab::new();
        lab.given_the_incoming_directory_holds(&["dated.bin"]);
        lab.given_known_met_remembers_it_under_a_hash_of_its_own_at_a_date_off_by("dated.bin", seconds);

        lab.when_the_directories_are_scanned();

        lab.then_it_is_shared_with_the_hash_of_its_content("dated.bin");
    }
}

struct Lab {
    dir: tempfile::TempDir,
    config: PathBuf,
    incoming: PathBuf,
    other: PathBuf,
    shares: Option<SharedFiles>,
    shared_names: Vec<String>,
    waiting: VecDeque<Found>,
}

impl Lab {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let config = dir.path().join("config");
        let incoming = dir.path().join("incoming");
        let other = dir.path().join("other");
        fs::create_dir_all(&config).unwrap();
        fs::create_dir_all(&incoming).unwrap();
        Self { dir, config, incoming, other, shares: None, shared_names: Vec::new(), waiting: VecDeque::new() }
    }

    fn given_the_incoming_directory_holds(&self, names: &[&str]) {
        for (i, name) in names.iter().enumerate() {
            fs::write(self.incoming.join(name), bytes(3000, i as u64 + 1)).unwrap();
        }
    }

    fn given_another_directory_holds(&self, name: &str, content: &[u8]) {
        fs::create_dir_all(&self.other).unwrap();
        fs::write(self.other.join(name), content).unwrap();
    }

    #[cfg(windows)]
    fn given_another_program_locks_it_for(&self, name: &str, time: Duration) -> Option<std::thread::JoinHandle<()>> {
        let file = fs::File::options().write(true).open(self.incoming.join(name)).unwrap();
        file.lock().unwrap();
        Some(std::thread::spawn(move || {
            std::thread::sleep(time);
            let _ = file.unlock();
            drop(file);
        }))
    }

    #[cfg(not(windows))]
    fn given_another_program_locks_it_for(&self, _name: &str, _time: Duration) -> Option<std::thread::JoinHandle<()>> {
        None
    }

    fn given_known_met_remembers_it_under_a_hash_of_its_own_at_a_date_off_by(&self, name: &str, seconds: i64) {
        let file = self.incoming.join(name);
        let remembered: i64 = 1_700_000_000;
        let on_disk = (remembered + seconds) as u64;
        set_modified(&file, UNIX_EPOCH + Duration::from_secs(on_disk));
        let tree_hash = tree('T');
        let record = KnownFile {
            mtime: remembered as u32,
            file_hash: REMEMBERED,
            part_hashes: Vec::new(),
            tags: vec![
                Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() },
                Tag::Int { name: TagName::id(file_tag::FILESIZE), value: fs::metadata(&file).unwrap().len() },
                Tag::String {
                    name: TagName::id(file_tag::AICH_HASH),
                    value: data_encoding::BASE32_NOPAD.encode(&tree_hash),
                },
            ],
        };
        fs::write(self.config.join(shared_files::KNOWN_FILE_NAME), known_met::write(&[record]).unwrap()).unwrap();
        let entry = Entry { master_hash: tree_hash, block_hashes: vec![tree_hash] };
        fs::write(self.config.join(shared_files::KNOWN2_FILE_NAME), known2_met::write(&[entry]).unwrap()).unwrap();
    }

    fn when_the_directories_are_scanned(&mut self) {
        let mut shares = SharedFiles::load(&self.config);
        shares.set_folders(vec![self.incoming.clone()], vec![self.dir.path().join("temp")]);
        self.shares = Some(shares);
        let listing = shared_files::list(&self.shares.as_ref().unwrap().places());
        let changes = self.shares.as_mut().unwrap().scanned(listing);
        self.note_and_hash(changes);
    }

    fn when_that_directory_is_shared_and_the_file_changes_before_hashing(&mut self, content: &[u8]) {
        let mut shares = SharedFiles::load(&self.config);
        shares.set_folders(vec![self.incoming.clone()], vec![self.dir.path().join("temp")]);
        shares.add_directory(&self.other, false).unwrap();
        let listing = shared_files::list(&shares.places());
        let changes = shares.scanned(listing);
        self.shares = Some(shares);
        // File changes on disk while queued for hashing.
        let file = self.other.join("changing.bin");
        fs::write(&file, content).unwrap();
        let moved = fs::metadata(&file).unwrap().modified().unwrap() + Duration::from_secs(60);
        set_modified(&file, moved);
        self.note_and_hash(changes);
        // Rescan picks up the new content and date.
        let listing = shared_files::list(&self.shares.as_ref().unwrap().places());
        let changes = self.shares.as_mut().unwrap().scanned(listing);
        self.note_and_hash(changes);
    }

    fn note_and_hash(&mut self, changes: Changes) {
        self.record(changes);
        while let Some(found) = self.waiting.pop_front() {
            let hashed = shared_files::hash(&found).map_err(|e| e.to_string());
            let more = self.shares.as_mut().unwrap().hashed(found, hashed);
            self.record(more);
        }
    }

    fn record(&mut self, changes: Changes) {
        for sharing in changes.sharing {
            if let Sharing::Shared(file) = sharing {
                self.shared_names.push(file.name);
            }
        }
        self.waiting.extend(changes.to_hash);
    }

    fn then_only_these_are_shared(&self, names: &[&str]) {
        let mut actual = self.shared_names.clone();
        let mut expected: Vec<String> = names.iter().map(|s| (*s).to_owned()).collect();
        actual.sort();
        expected.sort();
        assert_eq!(actual, expected);
        let mut listed: Vec<String> = self.shares.as_ref().unwrap().files().map(|f| f.name.clone()).collect();
        listed.sort();
        assert_eq!(listed, expected);
    }

    fn then_it_is_shared_with_the_remembered_hash(&self, name: &str) {
        let shared = self.shares.as_ref().unwrap().files().find(|f| f.name == name).unwrap();
        assert_eq!(shared.hash, REMEMBERED);
    }

    fn then_it_is_shared_with_the_hash_of_its_content(&self, name: &str) {
        let content = fs::read(self.incoming.join(name)).unwrap();
        let shared = self.shares.as_ref().unwrap().files().find(|f| f.name == name).unwrap();
        assert_eq!(shared.hash, ed2k::md4(&content));
    }

    fn then_known_met_holds_one_record_of_it_at_the_date_it_has_now(&mut self, name: &str) {
        if let Some(mut shares) = self.shares.take() {
            shares.close().unwrap();
        }
        let date = mtime_secs(&self.incoming.join(name));
        let records = known_met::read(&fs::read(self.config.join(shared_files::KNOWN_FILE_NAME)).unwrap()).unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].mtime, date);
    }

    fn then_the_file_is_shared_with_its_content_and_date_now(&self, name: &str) {
        let file = self.other.join(name);
        let content = fs::read(&file).unwrap();
        let shared = self.shares.as_ref().unwrap().files().find(|f| f.path == file).unwrap();
        assert_eq!(shared.hash, ed2k::md4(&content));
        assert_eq!(shared.size, content.len() as u64);
        assert_eq!(shared.mtime, mtime_secs(&file));
    }
}

fn tree(letter: char) -> [u8; 20] {
    data_encoding::BASE32_NOPAD.decode(letter.to_string().repeat(32).as_bytes()).unwrap().try_into().unwrap()
}

fn bytes(size: usize, seed: u64) -> Vec<u8> {
    let mut data = vec![0u8; size];
    StdRng::seed_from_u64(seed).fill(&mut data[..]);
    data
}

fn mtime_secs(file: &Path) -> u32 {
    fs::metadata(file).unwrap().modified().unwrap().duration_since(UNIX_EPOCH).unwrap().as_secs() as u32
}

fn set_modified(file: &Path, time: SystemTime) {
    fs::File::options().write(true).open(file).unwrap().set_modified(time).unwrap();
}
