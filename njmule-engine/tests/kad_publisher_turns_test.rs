// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadPublisherTurnsTest, under its names: a publish the node refuses does not hold back the next
//! ones, a busy node starts none, and a keyword with more files than its share carries the next ones each time.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::time::{Duration, Instant};

use njmule_engine::api::file_comment::FileComment;
use njmule_engine::kad::kad_publisher::{self, KadPublisher, Node, Shared};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::kad::kad_messages::Entry;
use njmule_engine::protocol::tag::{file_tag, tags};

/// A node that takes or refuses every publish, and remembers them.
#[derive(Default)]
struct FakeNode {
    refusing: bool,
    running: usize,
    asked: Vec<(String, bool)>,
    keyword_files: Vec<Vec<Entry>>,
}

impl Node for FakeNode {
    fn is_ready(&self) -> bool {
        true
    }

    fn is_connected(&self) -> bool {
        true
    }

    fn is_firewalled(&self) -> bool {
        false
    }

    fn running_keyword_publishes(&self) -> usize {
        self.running
    }

    fn running_source_publishes(&self) -> usize {
        self.running
    }

    fn running_note_publishes(&self) -> usize {
        self.running
    }

    fn publish_keyword(&mut self, word: &str, files: Vec<Entry>) -> bool {
        self.asked.push((word.to_owned(), !self.refusing));
        if !self.refusing {
            self.keyword_files.push(files);
        }
        !self.refusing
    }

    fn publish_source(&mut self, hash: [u8; 16], _: u64) -> bool {
        self.asked.push((hex(hash), !self.refusing));
        !self.refusing
    }

    fn publish_note(&mut self, hash: [u8; 16], _: &str, _: u64) -> bool {
        self.asked.push((format!("note {}", hex(hash)), !self.refusing));
        !self.refusing
    }
}

struct Turns {
    node: FakeNode,
    now: Instant,
    shared: Vec<Shared>,
    publisher: KadPublisher,
}

impl Turns {
    fn new() -> Self {
        let shared = vec![
            shared(hash(1), "Great Movie.avi", 700_000_000, true),
            shared(hash(2), "great song.mp3", 5_000_000, true),
            shared(hash(3), "half done.iso", 9_000_000, false),
        ];
        Self { node: FakeNode::default(), now: Instant::now(), shared, publisher: KadPublisher::new() }
    }

    fn given_the_node_refuses_publishes(&mut self) {
        self.node.refusing = true;
    }

    fn given_the_node_is_busy(&mut self) {
        self.node.running = 10;
    }

    fn given_files_with_one_keyword(&mut self, count: usize) {
        self.shared = (0..count).map(|at| shared(hash(100 + at), &name(at), 10, true)).collect();
    }

    fn given_the_turns_passed(&mut self, turns: u32) {
        self.when_the_turns_pass(turns);
    }

    fn given_the_node_takes_publishes_again(&mut self) {
        self.node.refusing = false;
    }

    fn given_a_day_passed(&mut self) {
        self.now += kad_publisher::KEYWORD_EVERY;
    }

    // A turn starts a publish of each kind, two seconds apart.
    fn when_the_turns_pass(&mut self, turns: u32) {
        for _ in 0..turns {
            let fine = |_: &[u8; 16]| Some(FileComment::of("fine", 4));
            self.publisher.tick(&mut self.node, &self.shared, &fine, self.now);
            self.now += Duration::from_secs(2);
        }
    }

    fn then_the_node_was_asked_for(&self, asked: &[(String, bool)]) {
        assert_eq!(self.node.asked, asked);
    }

    // The files are named after their place among the shared ones, which wraps around.
    fn then_the_keyword_carried_the_files(&self, publish: usize, first: usize, count: usize) {
        let files: Vec<&str> = self.node.keyword_files[publish]
            .iter()
            .map(|entry| tags::string_value(&entry.tags, file_tag::FILENAME).unwrap())
            .collect();
        let names: Vec<String> = (0..count).map(|at| name((first + at) % self.shared.len())).collect();
        assert_eq!(files, names);
    }
}

fn shared(hash: [u8; 16], name: &str, size: u64, complete: bool) -> Shared {
    Shared { hash, name: name.to_owned(), size, complete }
}

// Two letters are no keyword: "common" is the only one.
fn name(n: usize) -> String {
    let letter = |at: usize| char::from(b'a' + u8::try_from(at).unwrap());
    format!("common {}{}.txt", letter(n / 26), letter(n % 26))
}

fn hash(n: usize) -> [u8; 16] {
    let mut bytes = [0u8; 16];
    bytes[0] = n as u8;
    bytes[1] = (n >> 8) as u8;
    bytes[15] = 1;
    bytes
}

fn hex(hash: [u8; 16]) -> String {
    UInt128::from_big_endian(hash).to_hex()
}

fn asked(what: &str, taken: bool) -> (String, bool) {
    (what.to_owned(), taken)
}

// Each kind refused at its first turn, then taken from the next one on, round the list.
fn refused_once_then_taken_in_turn() -> Vec<(String, bool)> {
    let (movie, song, partial) = (hex(hash(1)), hex(hash(2)), hex(hash(3)));
    vec![
        asked("great", false),
        asked(&movie, false),
        asked(&format!("note {movie}"), false),
        asked("movie", true),
        asked(&song, true),
        asked(&format!("note {song}"), true),
        asked("song", true),
        asked(&partial, true),
        asked(&format!("note {partial}"), true),
        asked("great", true),
        asked(&movie, true),
        asked(&format!("note {movie}"), true),
    ]
}

#[test]
fn should_give_the_next_publishes_their_turn_when_the_node_refuses_one() {
    let mut turns = Turns::new();
    turns.given_the_node_refuses_publishes();
    turns.given_the_turns_passed(1);
    turns.given_the_node_takes_publishes_again();

    turns.when_the_turns_pass(3);

    turns.then_the_node_was_asked_for(&refused_once_then_taken_in_turn());
}

#[test]
fn should_start_no_publish_while_the_node_is_busy() {
    let mut turns = Turns::new();
    turns.given_the_node_is_busy();

    turns.when_the_turns_pass(3);

    turns.then_the_node_was_asked_for(&[]);
}

#[test]
fn should_rotate_the_files_of_a_keyword_with_more_than_its_share() {
    let mut turns = Turns::new();
    turns.given_files_with_one_keyword(kad_publisher::FILES_PER_KEYWORD + 10);
    turns.given_the_turns_passed(1);
    turns.given_a_day_passed();

    turns.when_the_turns_pass(1);

    turns.then_the_keyword_carried_the_files(0, 0, kad_publisher::FILES_PER_KEYWORD);
    turns.then_the_keyword_carried_the_files(1, kad_publisher::FILES_PER_KEYWORD, kad_publisher::FILES_PER_KEYWORD);
}
