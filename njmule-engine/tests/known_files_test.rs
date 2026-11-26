// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What `known.met` and `known2_64.met` keep of shared files.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::{HashMap, VecDeque};
use std::fs;
use std::ops::ControlFlow;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use njmule_engine::api::upload_priority::UploadPriority;
use njmule_engine::files::hash::file_hasher::{self, FileHashes};
use njmule_engine::files::shared_files::{self, Changes, Finished, Found, SharedFiles};
use njmule_engine::persist::atomic_files;
use njmule_engine::persist::format::known_met::{self, KnownFile};
use njmule_engine::persist::format::known2_met::{self, Entry};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};

#[test]
fn should_read_a_damaged_known2_from_its_backup() {
    let mut lab = Lab::new();
    lab.given_the_incoming_directory_holds(&["a.bin"]);
    lab.given_known_met_records(&["a.bin"]);
    lab.given_the_backup_of_known2_holds_the_tree_of("a.bin");
    lab.given_known2_is(&[9, 9, 9]);

    lab.when_the_shares_open_and_close_at_once();

    lab.then_known2_holds_only_the_trees_of(&["a.bin"]);
}

#[test]
fn should_keep_the_trees_before_the_cut_of_a_known2_cut_short() {
    let mut lab = Lab::new();
    lab.given_the_incoming_directory_holds(&["a.bin", "b.bin"]);
    lab.given_known_met_records(&["a.bin", "b.bin"]);
    lab.given_known2_holds_the_trees_of_cut_short(&["a.bin", "b.bin"]);

    lab.when_the_shares_open_and_close_at_once();

    lab.then_known2_holds_only_the_trees_of(&["a.bin"]);
}

#[test]
fn should_keep_the_records_before_the_cut_of_a_known_met_cut_short() {
    let mut lab = Lab::new();
    lab.given_the_incoming_directory_holds(&["a.bin", "b.bin"]);
    lab.given_known_met_cut_short_records(&["a.bin", "b.bin"]);

    lab.when_the_shares_open_and_close_at_once();

    lab.then_known_met_records(&["a.bin"]);
}

#[test]
fn should_keep_one_record_for_a_file_whose_date_changes() {
    let mut lab = Lab::new();
    lab.given_the_incoming_directory_holds(&["a.bin"]);
    lab.given_the_files_are_shared_and_recorded();

    lab.when_the_date_of_changes_and_it_is_hashed_again("a.bin");

    lab.then_known_met_holds_one_record_of_at_the_date_it_has_now("a.bin");
}

#[test]
fn should_drop_a_tree_no_record_needs() {
    let mut lab = Lab::new();
    lab.given_the_incoming_directory_holds(&["a.bin"]);
    lab.given_known_met_records(&["a.bin"]);
    lab.given_known2_holds_the_trees_of(&["a.bin"]);
    lab.given_known2_holds_a_tree_no_record_needs();

    lab.when_the_files_are_shared_and_saved();

    lab.then_known2_holds_only_the_trees_of(&["a.bin"]);
}

#[test]
fn should_keep_the_record_but_not_the_tree_of_a_file_unshared_for_over_a_month() {
    let mut lab = Lab::new();
    lab.given_the_incoming_directory_holds(&[]);
    lab.given_known_met_records_a_file_last_shared_days_ago("old.bin", 40);
    lab.given_known_met_records_a_file_last_shared_days_ago("recent.bin", 10);

    lab.when_the_files_are_shared_and_saved();

    lab.then_known_met_records(&["old.bin", "recent.bin"]);
    lab.then_known2_holds_only_the_trees_of(&["recent.bin"]);
}

#[test]
fn should_hash_for_its_tree_a_known_file_shared_without_it() {
    let mut lab = Lab::new();
    lab.given_the_incoming_directory_holds(&["a.bin"]);
    lab.given_known_met_records(&["a.bin"]);

    lab.when_the_files_are_shared();

    lab.then_the_aich_tree_of_is_known("a.bin");
}

#[test]
fn should_write_the_trees_only_when_they_change() {
    let mut lab = Lab::new();
    lab.given_the_incoming_directory_holds(&["a.bin"]);
    lab.given_the_files_are_shared_and_recorded();
    lab.given_known2_was_last_written_long_ago();

    lab.when_only_the_upload_priority_of_changes("a.bin");

    lab.then_known2_was_not_written_again();
}

#[test]
fn should_not_record_a_finished_download_with_the_aich_hash_of_its_link_before_its_own_tree_is_built() {
    let mut lab = Lab::new();
    lab.given_the_incoming_directory_holds(&["done.bin"]);
    lab.given_a_download_of_whose_link_gave_another_aich_hash("done.bin");

    lab.when_it_finishes_and_the_shares_close_at_once("done.bin");

    lab.then_known_met_records_without_an_aich_hash("done.bin");
}

struct Lab {
    dir: tempfile::TempDir,
    config: PathBuf,
    incoming: PathBuf,
    hashes: HashMap<String, FileHashes>,
    records: Vec<KnownFile>,
    trees: Vec<Entry>,
    shares: Option<SharedFiles>,
    waiting: VecDeque<Found>,
    trees_date: Option<SystemTime>,
    finished: Option<Finished>,
}

impl Lab {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let config = dir.path().join("config");
        let incoming = dir.path().join("incoming");
        fs::create_dir_all(&config).unwrap();
        fs::create_dir_all(&incoming).unwrap();
        Self {
            dir,
            config,
            incoming,
            hashes: HashMap::new(),
            records: Vec::new(),
            trees: Vec::new(),
            shares: None,
            waiting: VecDeque::new(),
            trees_date: None,
            finished: None,
        }
    }

    fn given_the_incoming_directory_holds(&mut self, names: &[&str]) {
        for (i, name) in names.iter().enumerate() {
            let file = self.incoming.join(name);
            fs::write(&file, bytes(400_000, i as u64 + 1)).unwrap();
            let result = file_hasher::hash_file(&file).unwrap();
            self.hashes.insert((*name).to_owned(), result);
        }
    }

    fn given_known_met_records(&mut self, names: &[&str]) {
        let now = now_secs();
        for name in names {
            let hash = self.hashes[*name].ed2k.file_hash();
            self.records.push(self.record_for_disk(name, hash, now));
        }
        fs::write(self.config.join(shared_files::KNOWN_FILE_NAME), known_met::write(&self.records).unwrap()).unwrap();
    }

    fn given_known_met_cut_short_records(&mut self, names: &[&str]) {
        let now = now_secs();
        for name in names {
            let hash = self.hashes[*name].ed2k.file_hash();
            self.records.push(self.record_for_disk(name, hash, now));
        }
        let data = known_met::write(&self.records).unwrap();
        fs::write(self.config.join(shared_files::KNOWN_FILE_NAME), &data[..data.len() - 5]).unwrap();
    }

    fn given_known_met_records_a_file_last_shared_days_ago(&mut self, name: &str, days: u64) {
        let seed: u64 = name.bytes().fold(0u64, |acc, b| acc.wrapping_mul(31).wrapping_add(u64::from(b)));
        let content = bytes(300_000, seed);
        let result =
            file_hasher::hash(&mut content.as_slice(), content.len() as u64, |_| ControlFlow::Continue(())).unwrap();
        let last_shared = now_secs() - days * 86_400;
        let record = KnownFile {
            mtime: 1_700_000_000,
            file_hash: result.ed2k.file_hash(),
            part_hashes: result.ed2k.part_hashes().to_vec(),
            tags: vec![
                Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() },
                Tag::Int { name: TagName::id(file_tag::FILESIZE), value: result.ed2k.size() },
                Tag::String {
                    name: TagName::id(file_tag::AICH_HASH),
                    value: data_encoding::BASE32_NOPAD.encode(&result.aich.as_ref().unwrap().master_hash()),
                },
                Tag::Int { name: TagName::id(file_tag::LASTSHARED), value: last_shared },
            ],
        };
        self.trees.push(entry_of(&result));
        self.records.push(record);
        self.hashes.insert(name.to_owned(), result);
        fs::write(self.config.join(shared_files::KNOWN_FILE_NAME), known_met::write(&self.records).unwrap()).unwrap();
        fs::write(self.config.join(shared_files::KNOWN2_FILE_NAME), known2_met::write(&self.trees).unwrap()).unwrap();
    }

    fn given_a_download_of_whose_link_gave_another_aich_hash(&mut self, name: &str) {
        let result = &self.hashes[name];
        self.finished = Some(Finished {
            hash: result.ed2k.file_hash(),
            size: result.ed2k.size(),
            part_hashes: result.ed2k.part_hashes().to_vec(),
            aich_hash: Some(tree('L')),
        });
    }

    fn given_the_backup_of_known2_holds_the_tree_of(&self, name: &str) {
        let known2 = self.config.join(shared_files::KNOWN2_FILE_NAME);
        let bak = atomic_files::bak_path(&known2);
        fs::write(bak, known2_met::write(&[entry_of(&self.hashes[name])]).unwrap()).unwrap();
    }

    fn given_known2_is(&self, content: &[u8]) {
        fs::write(self.config.join(shared_files::KNOWN2_FILE_NAME), content).unwrap();
    }

    fn given_known2_holds_the_trees_of(&mut self, names: &[&str]) {
        for name in names {
            self.trees.push(entry_of(&self.hashes[*name]));
        }
        fs::write(self.config.join(shared_files::KNOWN2_FILE_NAME), known2_met::write(&self.trees).unwrap()).unwrap();
    }

    fn given_known2_holds_the_trees_of_cut_short(&mut self, names: &[&str]) {
        for name in names {
            self.trees.push(entry_of(&self.hashes[*name]));
        }
        let data = known2_met::write(&self.trees).unwrap();
        fs::write(self.config.join(shared_files::KNOWN2_FILE_NAME), &data[..data.len() - 7]).unwrap();
    }

    fn given_known2_holds_a_tree_no_record_needs(&mut self) {
        self.trees.push(Entry { master_hash: tree('Q'), block_hashes: vec![tree('R')] });
        fs::write(self.config.join(shared_files::KNOWN2_FILE_NAME), known2_met::write(&self.trees).unwrap()).unwrap();
    }

    fn given_the_files_are_shared_and_recorded(&mut self) {
        self.open_and_scan();
        self.close();
        self.open_and_scan();
    }

    fn given_known2_was_last_written_long_ago(&mut self) {
        let old = UNIX_EPOCH + Duration::from_secs(1_600_000_000);
        set_modified(&self.config.join(shared_files::KNOWN2_FILE_NAME), old);
        self.trees_date =
            Some(fs::metadata(self.config.join(shared_files::KNOWN2_FILE_NAME)).unwrap().modified().unwrap());
    }

    fn when_the_shares_open_and_close_at_once(&mut self) {
        self.load();
        self.close();
    }

    fn when_it_finishes_and_the_shares_close_at_once(&mut self, name: &str) {
        self.load();
        let file = self.incoming.join(name);
        let finished = self.finished.take().unwrap();
        let _ = self.shares.as_mut().unwrap().finished(&file, finished);
        self.close();
    }

    fn when_the_files_are_shared(&mut self) {
        self.open_and_scan();
    }

    fn when_the_files_are_shared_and_saved(&mut self) {
        self.open_and_scan();
        self.close();
    }

    fn when_the_date_of_changes_and_it_is_hashed_again(&mut self, name: &str) {
        let file = self.incoming.join(name);
        let current = fs::metadata(&file).unwrap().modified().unwrap();
        let moved = current + Duration::from_secs(100);
        set_modified(&file, moved);
        self.scan();
        self.close();
    }

    fn when_only_the_upload_priority_of_changes(&mut self, name: &str) {
        let hash = self.hashes[name].ed2k.file_hash();
        assert!(self.shares.as_mut().unwrap().set_priority(&hash, UploadPriority::High));
        self.close();
    }

    fn then_the_aich_tree_of_is_known(&mut self, name: &str) {
        let hash = self.hashes[name].ed2k.file_hash();
        assert!(self.shares.as_mut().unwrap().aich_tree(&hash).is_some());
    }

    fn then_known_met_holds_one_record_of_at_the_date_it_has_now(&self, name: &str) {
        let date = mtime_secs(&self.incoming.join(name));
        let matching: Vec<_> = self.known_met().into_iter().filter(|r| r.name() == Some(name)).collect();
        assert_eq!(matching.len(), 1);
        assert_eq!(matching[0].mtime, date);
    }

    fn then_known_met_records_without_an_aich_hash(&self, name: &str) {
        let matching: Vec<_> = self.known_met().into_iter().filter(|r| r.name() == Some(name)).collect();
        assert_eq!(matching.len(), 1);
        assert!(matching[0].aich_hash().is_none());
    }

    fn then_known_met_records(&self, names: &[&str]) {
        let mut actual: Vec<String> = self.known_met().into_iter().map(|r| r.name().unwrap().to_owned()).collect();
        let mut expected: Vec<String> = names.iter().map(|s| (*s).to_owned()).collect();
        actual.sort();
        expected.sort();
        assert_eq!(actual, expected);
    }

    fn then_known2_holds_only_the_trees_of(&self, names: &[&str]) {
        let mut expected: Vec<[u8; 20]> =
            names.iter().map(|name| self.hashes[*name].aich.as_ref().unwrap().master_hash()).collect();
        let mut actual: Vec<[u8; 20]> =
            known2_met::read(&fs::read(self.config.join(shared_files::KNOWN2_FILE_NAME)).unwrap())
                .unwrap()
                .into_iter()
                .map(|e| e.master_hash)
                .collect();
        expected.sort();
        actual.sort();
        assert_eq!(actual, expected);
    }

    fn then_known2_was_not_written_again(&self) {
        let now = fs::metadata(self.config.join(shared_files::KNOWN2_FILE_NAME)).unwrap().modified().unwrap();
        assert_eq!(now, self.trees_date.unwrap());
        let records = self.known_met();
        assert_eq!(records.len(), 1);
        assert!(records[0].upload_priority().is_some());
    }

    fn load(&mut self) {
        let mut shares = SharedFiles::load(&self.config);
        shares.set_folders(vec![self.incoming.clone()], vec![self.dir.path().join("temp")]);
        self.shares = Some(shares);
    }

    fn open_and_scan(&mut self) {
        self.load();
        self.scan();
    }

    fn scan(&mut self) {
        let listing = shared_files::list(&self.shares.as_ref().unwrap().places());
        let changes = self.shares.as_mut().unwrap().scanned(listing);
        self.drain_hashes(changes);
    }

    fn drain_hashes(&mut self, changes: Changes) {
        self.waiting.extend(changes.to_hash);
        while let Some(found) = self.waiting.pop_front() {
            let hashed = shared_files::hash(&found).map_err(|e| e.to_string());
            let more = self.shares.as_mut().unwrap().hashed(found, hashed);
            self.waiting.extend(more.to_hash);
        }
    }

    fn close(&mut self) {
        if let Some(mut shares) = self.shares.take() {
            shares.close().unwrap();
        }
    }

    fn known_met(&self) -> Vec<KnownFile> {
        known_met::read(&fs::read(self.config.join(shared_files::KNOWN_FILE_NAME)).unwrap()).unwrap()
    }

    fn record_for_disk(&self, name: &str, hash: [u8; 16], last_shared: u64) -> KnownFile {
        let file = self.incoming.join(name);
        let mut tags = vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: fs::metadata(&file).unwrap().len() },
        ];
        if let Some(aich) = &self.hashes[name].aich {
            tags.push(Tag::String {
                name: TagName::id(file_tag::AICH_HASH),
                value: data_encoding::BASE32_NOPAD.encode(&aich.master_hash()),
            });
        }
        tags.push(Tag::Int { name: TagName::id(file_tag::LASTSHARED), value: last_shared });
        KnownFile { mtime: mtime_secs(&file), file_hash: hash, part_hashes: Vec::new(), tags }
    }
}

fn entry_of(result: &FileHashes) -> Entry {
    let aich = result.aich.as_ref().unwrap();
    Entry { master_hash: aich.master_hash(), block_hashes: aich.block_hashes().to_vec() }
}

fn tree(letter: char) -> [u8; 20] {
    data_encoding::BASE32_NOPAD.decode(letter.to_string().repeat(32).as_bytes()).unwrap().try_into().unwrap()
}

fn bytes(size: usize, seed: u64) -> Vec<u8> {
    let mut data = vec![0u8; size];
    StdRng::seed_from_u64(seed).fill(&mut data[..]);
    data
}

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs()
}

fn mtime_secs(file: &Path) -> u32 {
    fs::metadata(file).unwrap().modified().unwrap().duration_since(UNIX_EPOCH).unwrap().as_secs() as u32
}

fn set_modified(file: &Path, time: SystemTime) {
    fs::File::options().write(true).open(file).unwrap().set_modified(time).unwrap();
}
