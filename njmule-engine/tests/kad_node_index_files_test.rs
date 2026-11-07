// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodeIndexFilesTest, under its names: what other nodes published to us outlives a restart, as
//! long as it would have lived anyway and within the limits a live publish meets.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod kad_lab;

use std::time::Duration;

use kad_lab::{KadLab, PORT, ip};
use njmule_engine::kad::kad_index;
use njmule_engine::kad::kad_node::{self, KadNode, Settings};
use njmule_engine::kad::kad_publisher::Node;
use njmule_engine::kad::keywords;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::kad_index_files::{
    self, IndexKey, Keyword, KeywordEntry, KeywordIndex, KeywordSource, PublishedName, Publisher, Source, SourceIndex,
    Tracking,
};
use njmule_engine::protocol::kad::kad_messages::{
    Answer, Entry, KeywordPublish, KeywordSearch, NotesPublish, NotesSearch, PublishAnswer, SearchAnswer,
    SourcePublish, SourceSearch,
};
use njmule_engine::protocol::packet::Packet;
use njmule_engine::protocol::packet::opcodes::kad;
use njmule_engine::protocol::tag::{Tag, TagName, file_tag, tags};

const PUBLISHER: u32 = ip(20, 1, 1, 1);
const SEARCHER: u32 = ip(21, 1, 1, 1);
const WALL: u32 = 1_800_000_000;
const SIZE: u64 = 1_000;
const WORD: &str = "ubuntu";
const HOUR: Duration = Duration::from_secs(3_600);

struct IndexFiles {
    lab: KadLab,
    node: KadNode,
    wall: u32,
}

impl IndexFiles {
    fn new() -> Self {
        let mut lab = KadLab::new();
        let node = lab.node(node_settings());
        Self { lab, node, wall: WALL }
    }

    fn given_a_keyword_a_source_and_a_note_published_to_the_node(&mut self) {
        let keyword = KeywordPublish { keyword: self.near(1), files: vec![file("ubuntu desktop.iso")] };
        self.publisher_sends(&keyword.encode().unwrap());
        let source = SourcePublish {
            file: self.near(2),
            source: high_low(7, 7),
            tags: vec![int(file_tag::SOURCETYPE, 1), int(file_tag::SOURCEPORT, 4662), int(file_tag::FILESIZE, SIZE)],
        };
        self.publisher_sends(&source.encode().unwrap());
        let note = NotesPublish {
            file: self.near(3),
            publisher: high_low(8, 8),
            tags: vec![
                string(file_tag::FILENAME, "ubuntu.iso"),
                int(file_tag::FILERATING, 4),
                string(file_tag::DESCRIPTION, "good"),
                int(file_tag::FILESIZE, SIZE),
            ],
        };
        self.publisher_sends(&note.encode().unwrap());
    }

    fn given_the_node_saves_its_index(&mut self) {
        self.node.save_index_at(u64::from(self.wall));
    }

    fn given_an_index_file_of_sources_for_one_file(&self, sources: usize) {
        let list = (0..sources)
            .map(|at| Source {
                id: high_low(100, u64::try_from(at).unwrap()),
                entries: vec![kad_index_files::Entry { lifetime: self.wall + 3_600, tags: source_tags(at) }],
            })
            .collect();
        self.write_sources(vec![IndexKey { key: self.near(2), sources: list }]);
    }

    fn given_an_index_file_of_sources_far_from_us(&self) {
        let far = self.node.id() ^ UInt128(u128::from(u64::MAX) << 64);
        let source = Source {
            id: high_low(100, 1),
            entries: vec![kad_index_files::Entry { lifetime: self.wall + 3_600, tags: source_tags(1) }],
        };
        self.write_sources(vec![IndexKey { key: far, sources: vec![source] }]);
    }

    fn given_a_keyword_index_file_with_a_name(&self, name: &str, own_id: UInt128) {
        let tracking = Tracking {
            aich_hashes: Vec::new(),
            names: vec![PublishedName { name: name.to_owned(), popularity: 1 }],
            publishers: vec![Publisher { ip: PUBLISHER, time: self.wall - 60, aich_index: 0xFFFF }],
        };
        let entry = KeywordEntry {
            lifetime: self.wall + 3_600,
            tracking,
            tags: vec![string(file_tag::FILENAME, name), int(file_tag::FILESIZE, SIZE)],
        };
        let keyword =
            Keyword { key: self.near(1), sources: vec![KeywordSource { id: high_low(9, 9), entries: vec![entry] }] };
        let index = KeywordIndex { version: 4, expiry: self.wall + 3_600, own_id, keywords: vec![keyword] };
        let path = self.lab.dir.path().join(kad_node::KEYWORD_INDEX_FILE);
        std::fs::write(path, kad_index_files::write_keywords(&index).unwrap()).unwrap();
    }

    fn given_damaged_index_files(&self) {
        for name in [
            kad_node::KEYWORD_INDEX_FILE,
            kad_node::SOURCE_INDEX_FILE,
            kad_node::NOTE_INDEX_FILE,
            kad_node::LOAD_INDEX_FILE,
        ] {
            std::fs::write(self.lab.dir.path().join(name), [0, 0, 0, 0, 1, 2, 3]).unwrap();
        }
    }

    fn given_a_keyword_publish_of_ours_whose_node_reports_the_load(&mut self, load: u8) {
        let target = keywords::id(WORD);
        self.node.add_contacts(&kad_lab::one_contact(target, PUBLISHER));
        assert!(self.node.publish_keyword(WORD, vec![file("ubuntu.iso")]));
        self.node.received(PUBLISHER, PORT, &Answer { target, contacts: Vec::new() }.encode().unwrap(), 0, 0);
        self.seconds(4);
        self.node.received(PUBLISHER, PORT, &PublishAnswer { target, load }.encode(), 0, 0);
        self.seconds(30);
        assert_eq!(self.node.running_keyword_publishes(), 0);
    }

    fn given_the_node_started_again_after(&mut self, pause: Duration) {
        self.when_the_node_starts_again_after(pause);
    }

    // The index is saved by the steps before, at the time the test sets, and is not saved again here.
    fn when_half_an_hour_passes(&mut self) {
        self.lab.advance(kad_node::SAVE_EVERY);
        self.node.tick();
    }

    fn when_the_node_starts_again_after(&mut self, pause: Duration) {
        self.node.save_nodes();
        self.lab.advance(pause);
        self.wall += u32::try_from(pause.as_secs()).unwrap();
        self.node = self.lab.node(node_settings());
        self.node.load_nodes();
        self.node.load_index_at(u64::from(self.wall));
    }

    fn then_the_index_is_on_disk(&self) {
        for name in [kad_node::KEYWORD_INDEX_FILE, kad_node::SOURCE_INDEX_FILE, kad_node::NOTE_INDEX_FILE] {
            assert!(self.lab.dir.path().join(name).exists(), "{name}");
        }
    }

    fn then_a_keyword_search_finds(&mut self, names: &[&str]) {
        let request = KeywordSearch { target: self.near(1), start: 0, expression: Vec::new() }.encode();
        let found: Vec<String> = self
            .search(&request)
            .iter()
            .map(|entry| tags::string_value(&entry.tags, file_tag::FILENAME).unwrap().to_owned())
            .collect();
        assert_eq!(found, names);
    }

    fn then_a_source_search_finds(&mut self, count: usize) {
        let request = SourceSearch { target: self.near(2), start: 0, size: SIZE }.encode();
        assert_eq!(self.search(&request).len(), count);
    }

    fn then_a_notes_search_finds(&mut self, count: usize) {
        let request = NotesSearch { target: self.near(3), size: SIZE }.encode();
        assert_eq!(self.search(&request).len(), count);
    }

    fn then_the_node_holds_sources(&self, count: usize) {
        assert_eq!(self.node.indexed().1, count);
    }

    fn then_our_keyword_cannot_be_published(&mut self) {
        assert_eq!(self.node.contacts(), 1, "the contact came back from nodes.dat");
        assert!(!self.node.publish_keyword(WORD, vec![file("ubuntu.iso")]));
    }

    fn publisher_sends(&mut self, packet: &Packet) {
        self.node.received(PUBLISHER, PORT, packet, 0, 0);
    }

    fn search(&mut self, request: &Packet) -> Vec<Entry> {
        self.lab.take_sent();
        self.node.received(SEARCHER, PORT, request, 0, 0);
        self.lab
            .take_sent()
            .into_iter()
            .filter(|sent| sent.ip == SEARCHER && sent.packet.opcode() == kad::SEARCH_RES)
            .flat_map(|sent| SearchAnswer::decode(&sent.packet).unwrap().results)
            .collect()
    }

    fn write_sources(&self, keys: Vec<IndexKey>) {
        let index = SourceIndex { version: 2, expiry: self.wall + 3_600, keys };
        let path = self.lab.dir.path().join(kad_node::SOURCE_INDEX_FILE);
        std::fs::write(path, kad_index_files::write_sources(&index).unwrap()).unwrap();
    }

    fn seconds(&mut self, seconds: u64) {
        for _ in 0..seconds {
            self.lab.advance(Duration::from_secs(1));
            self.node.tick();
        }
    }

    // Keys within the tolerance of our id, where publishes are taken.
    fn near(&self, n: u128) -> UInt128 {
        self.node.id() ^ UInt128(n)
    }
}

fn node_settings() -> Settings {
    let mut settings = kad_lab::settings();
    settings.udp_port = PORT;
    settings
}

fn high_low(high: u64, low: u64) -> UInt128 {
    UInt128(u128::from(high) << 64 | u128::from(low))
}

fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

fn string(id: u8, value: &str) -> Tag {
    Tag::String { name: TagName::id(id), value: value.to_owned() }
}

fn source_tags(at: usize) -> Vec<Tag> {
    let address = ip(30, u8::try_from(at / 250).unwrap(), u8::try_from(at % 250).unwrap(), 1);
    vec![
        int(file_tag::SOURCETYPE, 1),
        int(file_tag::SOURCEIP, u64::from(address)),
        int(file_tag::SOURCEPORT, 4662),
        int(file_tag::SOURCEUPORT, 4672),
        int(file_tag::FILESIZE, SIZE),
    ]
}

fn file(name: &str) -> Entry {
    Entry { id: high_low(5, 5), tags: vec![string(file_tag::FILENAME, name), int(file_tag::FILESIZE, SIZE)] }
}

#[test]
fn should_answer_after_a_restart_what_was_published_before() {
    let mut index = IndexFiles::new();
    index.given_a_keyword_a_source_and_a_note_published_to_the_node();
    index.given_the_node_saves_its_index();

    index.when_the_node_starts_again_after(HOUR);

    index.then_a_keyword_search_finds(&["ubuntu desktop.iso"]);
    index.then_a_source_search_finds(1);
    index.then_a_notes_search_finds(1);
}

#[test]
fn should_save_its_index_every_half_hour_without_stopping() {
    let mut index = IndexFiles::new();
    index.given_a_keyword_a_source_and_a_note_published_to_the_node();

    index.when_half_an_hour_passes();

    index.then_the_index_is_on_disk();
}

#[test]
fn should_not_load_sources_expired_meanwhile() {
    let mut index = IndexFiles::new();
    index.given_a_keyword_a_source_and_a_note_published_to_the_node();
    index.given_the_node_saves_its_index();

    index.when_the_node_starts_again_after(HOUR * 6);

    index.then_a_keyword_search_finds(&["ubuntu desktop.iso"]);
    index.then_a_source_search_finds(0);
}

#[test]
fn should_not_load_keywords_and_notes_expired_meanwhile() {
    let mut index = IndexFiles::new();
    index.given_a_keyword_a_source_and_a_note_published_to_the_node();
    index.given_the_node_saves_its_index();
    index.given_the_node_started_again_after(HOUR * 6);

    index.when_the_node_starts_again_after(HOUR * 19);

    index.then_a_keyword_search_finds(&[]);
    index.then_a_notes_search_finds(0);
}

#[test]
fn should_load_no_more_than_a_live_publish_would_store() {
    let mut index = IndexFiles::new();
    index.given_an_index_file_of_sources_for_one_file(kad_index::MAX_SOURCES_PER_FILE + 100);
    let own = index.node.id();
    index.given_a_keyword_index_file_with_a_name(&"x".repeat(1_000), own);

    index.when_the_node_starts_again_after(Duration::ZERO);

    index.then_the_node_holds_sources(kad_index::MAX_SOURCES_PER_FILE);
    index.then_a_keyword_search_finds(&[&"x".repeat(kad_index::MAX_KEYWORD_NAME)]);
}

#[test]
fn should_ignore_the_keywords_of_another_node_and_the_keys_far_from_us() {
    let mut index = IndexFiles::new();
    index.given_a_keyword_index_file_with_a_name("ubuntu other.iso", high_low(1, 2));
    index.given_an_index_file_of_sources_far_from_us();

    index.when_the_node_starts_again_after(Duration::ZERO);

    index.then_a_keyword_search_finds(&[]);
    index.then_the_node_holds_sources(0);
}

#[test]
fn should_start_empty_from_damaged_files() {
    let mut index = IndexFiles::new();
    index.given_damaged_index_files();

    index.when_the_node_starts_again_after(Duration::ZERO);

    index.then_the_node_holds_sources(0);
    index.then_a_keyword_search_finds(&[]);
}

#[test]
fn should_still_spare_a_loaded_keyword_after_a_restart() {
    let mut index = IndexFiles::new();
    index.given_a_keyword_publish_of_ours_whose_node_reports_the_load(80);
    index.given_the_node_saves_its_index();

    index.when_the_node_starts_again_after(HOUR * 24);

    index.then_our_keyword_cannot_be_published();
}
