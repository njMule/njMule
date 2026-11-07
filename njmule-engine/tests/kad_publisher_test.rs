// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadPublisherTest, under its names: the keywords of our complete files and ourselves as a source
//! of every shared file, published in turn and again after their intervals, by a node that may.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::time::{Duration, Instant};

use indexmap::IndexMap;
use njmule_engine::kad::kad_publisher::{self, KadPublisher, Node, Shared};
use njmule_engine::protocol::kad::kad_messages::Entry;
use njmule_engine::protocol::tag::{file_tag, tags};

const MOVIE: [u8; 16] =
    [0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef];
const SONG: [u8; 16] = [0xfe, 0xdc, 0xba, 0x98, 0x76, 0x54, 0x32, 0x10, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0x54, 0x32, 0x10];
const PARTIAL: [u8; 16] =
    [0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff];

/// A node that takes every publish and remembers it.
struct FakeNode {
    ready: bool,
    connected: bool,
    firewalled: bool,
    keywords: IndexMap<String, Vec<Entry>>,
    sources: Vec<[u8; 16]>,
}

impl Node for FakeNode {
    fn is_ready(&self) -> bool {
        self.ready
    }

    fn is_connected(&self) -> bool {
        self.connected
    }

    fn is_firewalled(&self) -> bool {
        self.firewalled
    }

    fn running_keyword_publishes(&self) -> usize {
        0
    }

    fn running_source_publishes(&self) -> usize {
        0
    }

    fn running_note_publishes(&self) -> usize {
        0
    }

    fn publish_keyword(&mut self, word: &str, files: Vec<Entry>) -> bool {
        self.keywords.insert(word.to_owned(), files);
        true
    }

    fn publish_source(&mut self, hash: [u8; 16], _: u64) -> bool {
        self.sources.push(hash);
        true
    }

    fn publish_note(&mut self, _: [u8; 16], _: &str, _: u64) -> bool {
        false
    }
}

struct Publisher {
    node: FakeNode,
    now: Instant,
    shared: Vec<Shared>,
    publisher: KadPublisher,
    many: Vec<Shared>,
}

impl Publisher {
    fn new() -> Self {
        let node = FakeNode {
            ready: true,
            connected: true,
            firewalled: false,
            keywords: IndexMap::new(),
            sources: Vec::new(),
        };
        let shared = vec![
            shared(MOVIE, "Great Movie.avi", 700_000_000, true),
            shared(SONG, "great song.mp3", 5_000_000, true),
            shared(PARTIAL, "half done.iso", 9_000_000, false),
        ];
        Self { node, now: Instant::now(), shared, publisher: KadPublisher::new(), many: Vec::new() }
    }

    fn given_the_first_publishes_were_made_and_forgotten(&mut self) {
        self.when_seconds_pass_with_a_tick_each(20);
        self.node.keywords.clear();
        self.node.sources.clear();
    }

    fn given_time_passed(&mut self, time: Duration) {
        self.now += time;
    }

    fn given_only_the_sources_were_published_again(&mut self) {
        self.when_seconds_pass_with_a_tick_each(20);
        self.then_no_keyword_is_published();
        self.then_the_sources_published_are(&[MOVIE, SONG, PARTIAL]);
    }

    fn given_the_node_is_firewalled(&mut self) {
        self.node.firewalled = true;
    }

    fn given_the_node_is_not_connected(&mut self) {
        self.node.connected = false;
    }

    fn given_the_node_is_not_ready(&mut self) {
        self.node.ready = false;
    }

    fn given_shared_files_with_one_keyword(&mut self, count: usize) {
        self.many = (0..count)
            .map(|at| {
                let mut hash = [0u8; 16];
                hash[0] = at as u8;
                hash[1] = (at >> 8) as u8;
                shared(hash, &format!("common {at}.txt"), 10, true)
            })
            .collect();
    }

    fn when_seconds_pass_with_a_tick_each(&mut self, seconds: u32) {
        for _ in 0..seconds {
            self.publisher.tick(&mut self.node, &self.shared, &|_| None, self.now);
            self.now += Duration::from_secs(1);
        }
    }

    fn when_the_publisher_ticks(&mut self) {
        self.publisher.tick(&mut self.node, &self.shared, &|_| None, self.now);
    }

    fn when_two_turns_pass(&mut self) {
        for _ in 0..2 {
            self.publisher.tick(&mut self.node, &self.shared, &|_| None, self.now);
            self.now += Duration::from_secs(2);
        }
    }

    fn when_a_publisher_of_those_files_ticks(&mut self) {
        KadPublisher::new().tick(&mut self.node, &self.many, &|_| None, self.now);
    }

    fn then_the_keywords_published_are(&self, words: &[&str]) {
        let mut published: Vec<&str> = self.node.keywords.keys().map(String::as_str).collect();
        published.sort_unstable();
        assert_eq!(published, words);
    }

    fn then_the_keyword_carries_the_first_file_first(&self, word: &str, files: usize, name: &str, size: u64) {
        let entries = &self.node.keywords[word];
        assert_eq!(entries.len(), files);
        assert_eq!(tags::string_value(&entries[0].tags, file_tag::FILENAME), Some(name));
        assert_eq!(tags::int_value(&entries[0].tags, file_tag::FILESIZE), Some(size));
        assert!(tags::string_value(&entries[0].tags, file_tag::FILETYPE).is_some());
    }

    fn then_the_sources_published_are(&self, hashes: &[[u8; 16]]) {
        assert_eq!(self.node.sources, hashes);
    }

    fn then_no_keyword_is_published(&self) {
        assert!(self.node.keywords.is_empty());
    }

    fn then_nothing_is_published(&self) {
        assert!(self.node.keywords.is_empty());
        assert!(self.node.sources.is_empty());
    }

    fn then_the_keyword_carries_files(&self, word: &str, count: usize) {
        assert_eq!(self.node.keywords[word].len(), count);
    }
}

fn shared(hash: [u8; 16], name: &str, size: u64, complete: bool) -> Shared {
    Shared { hash, name: name.to_owned(), size, complete }
}

#[test]
fn should_publish_the_keywords_of_complete_files_and_all_sources_in_turn() {
    let mut publisher = Publisher::new();

    publisher.when_seconds_pass_with_a_tick_each(20);

    publisher.then_the_keywords_published_are(&["great", "movie", "song"]);
    publisher.then_the_keyword_carries_the_first_file_first("great", 2, "Great Movie.avi", 700_000_000);
    publisher.then_the_sources_published_are(&[MOVIE, SONG, PARTIAL]);
}

#[test]
fn should_publish_the_sources_again_only_after_their_interval() {
    let mut publisher = Publisher::new();
    publisher.given_the_first_publishes_were_made_and_forgotten();
    publisher.given_time_passed(kad_publisher::SOURCE_EVERY);

    publisher.when_seconds_pass_with_a_tick_each(20);

    publisher.then_no_keyword_is_published();
    publisher.then_the_sources_published_are(&[MOVIE, SONG, PARTIAL]);
}

#[test]
fn should_publish_the_keywords_again_only_after_their_interval() {
    let mut publisher = Publisher::new();
    publisher.given_the_first_publishes_were_made_and_forgotten();
    publisher.given_time_passed(kad_publisher::SOURCE_EVERY);
    publisher.given_only_the_sources_were_published_again();
    publisher.given_time_passed(kad_publisher::KEYWORD_EVERY);

    publisher.when_the_publisher_ticks();

    publisher.then_the_keywords_published_are(&["great"]);
}

#[test]
fn should_publish_nothing_while_the_node_is_firewalled() {
    let mut publisher = Publisher::new();
    publisher.given_the_node_is_firewalled();

    publisher.when_two_turns_pass();

    publisher.then_nothing_is_published();
}

#[test]
fn should_publish_nothing_while_the_node_is_not_connected() {
    let mut publisher = Publisher::new();
    publisher.given_the_node_is_not_connected();

    publisher.when_two_turns_pass();

    publisher.then_nothing_is_published();
}

#[test]
fn should_publish_nothing_while_the_node_is_not_ready() {
    let mut publisher = Publisher::new();
    publisher.given_the_node_is_not_ready();

    publisher.when_two_turns_pass();

    publisher.then_nothing_is_published();
}

#[test]
fn should_publish_a_keyword_with_at_most_its_share_of_files() {
    let mut publisher = Publisher::new();
    publisher.given_shared_files_with_one_keyword(kad_publisher::FILES_PER_KEYWORD + 10);

    publisher.when_a_publisher_of_those_files_ticks();

    publisher.then_the_keyword_carries_files("common", kad_publisher::FILES_PER_KEYWORD);
}
