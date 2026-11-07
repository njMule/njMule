// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadPublisherNotesTest, under its names: our comments on the files we share, published once a day,
//! at once when they change, no more once taken away, and one at a time by a reachable node.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::HashMap;
use std::time::{Duration, Instant};

use njmule_engine::api::file_comment::FileComment;
use njmule_engine::kad::kad_publisher::{self, KadPublisher, Node, Shared};
use njmule_engine::protocol::kad::kad_messages::Entry;

const MOVIE: [u8; 16] =
    [0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef];
const SONG: [u8; 16] = [0xfe, 0xdc, 0xba, 0x98, 0x76, 0x54, 0x32, 0x10, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0x54, 0x32, 0x10];
const PARTIAL: [u8; 16] =
    [0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff];

/// A note publish the node was asked for.
struct Publish {
    hash: [u8; 16],
    name: String,
    size: u64,
}

/// A node that takes every note publish and remembers it.
#[derive(Default)]
struct NotesNode {
    notes: Vec<Publish>,
    firewalled: bool,
    running: usize,
}

impl Node for NotesNode {
    fn is_ready(&self) -> bool {
        true
    }

    fn is_connected(&self) -> bool {
        true
    }

    fn is_firewalled(&self) -> bool {
        self.firewalled
    }

    fn running_keyword_publishes(&self) -> usize {
        3
    }

    fn running_source_publishes(&self) -> usize {
        4
    }

    fn running_note_publishes(&self) -> usize {
        self.running
    }

    fn publish_keyword(&mut self, _: &str, _: Vec<Entry>) -> bool {
        false
    }

    fn publish_source(&mut self, _: [u8; 16], _: u64) -> bool {
        false
    }

    fn publish_note(&mut self, hash: [u8; 16], name: &str, size: u64) -> bool {
        self.notes.push(Publish { hash, name: name.to_owned(), size });
        true
    }
}

struct Notes {
    node: NotesNode,
    now: Instant,
    comments: HashMap<[u8; 16], FileComment>,
    shared: Vec<Shared>,
    publisher: KadPublisher,
}

impl Notes {
    fn new() -> Self {
        let shared = vec![
            Shared { hash: MOVIE, name: "Great Movie.avi".to_owned(), size: 700_000_000, complete: true },
            Shared { hash: SONG, name: "great song.mp3".to_owned(), size: 5_000_000, complete: true },
            Shared { hash: PARTIAL, name: "half done.iso".to_owned(), size: 9_000_000, complete: false },
        ];
        Self {
            node: NotesNode::default(),
            now: Instant::now(),
            comments: HashMap::new(),
            shared,
            publisher: KadPublisher::new(),
        }
    }

    fn given_our_comment(&mut self, file: [u8; 16], comment: FileComment) {
        self.comments.insert(file, comment);
    }

    fn given_our_comment_is_taken_away(&mut self, file: [u8; 16]) {
        self.comments.remove(&file);
    }

    fn given_a_note_publish_runs_already(&mut self) {
        self.node.running = 1;
    }

    fn given_no_note_publish_runs(&mut self) {
        self.node.running = 0;
    }

    fn given_the_node_is_firewalled(&mut self) {
        self.node.firewalled = true;
    }

    fn given_time_passes(&mut self, time: Duration) {
        self.when_time_passes(time);
    }

    // The publisher runs every second, as the engine runs it.
    fn when_time_passes(&mut self, time: Duration) {
        let end = self.now + time;
        while self.now < end {
            let comments = &self.comments;
            self.publisher.tick(&mut self.node, &self.shared, &|hash| comments.get(hash).cloned(), self.now);
            self.now += Duration::from_secs(1).min(end - self.now);
        }
    }

    fn then_notes_were_published_for(&self, files: &[[u8; 16]]) {
        assert_eq!(self.node.notes.iter().map(|publish| publish.hash).collect::<Vec<_>>(), files);
    }

    fn then_the_first_note_is(&self, file: [u8; 16], name: &str, size: u64, comment: &FileComment) {
        let first = &self.node.notes[0];
        assert_eq!((first.hash, first.name.as_str(), first.size), (file, name, size));
        assert_eq!(self.reads_now(file), Some(comment));
    }

    fn then_the_last_note_says(&self, comment: &FileComment) {
        let last = self.node.notes.last().unwrap();
        assert_eq!(self.reads_now(last.hash), Some(comment));
    }

    fn then_the_first_publish_now_reads_no_comment(&self) {
        assert_eq!(self.reads_now(self.node.notes[0].hash), None);
    }

    fn then_no_note_was_published(&self) {
        assert!(self.node.notes.is_empty());
    }

    // What a publish under way reads of our comment, as the node reads it for each node it goes to.
    fn reads_now(&self, file: [u8; 16]) -> Option<&FileComment> {
        self.comments.get(&file).filter(|comment| !comment.is_empty())
    }
}

#[test]
fn should_publish_the_comment_of_each_commented_file_once() {
    let mut notes = Notes::new();
    notes.given_our_comment(MOVIE, FileComment::of("great", 5));
    notes.given_our_comment(PARTIAL, FileComment::of("", 1));

    notes.when_time_passes(Duration::from_secs(20));

    notes.then_notes_were_published_for(&[MOVIE, PARTIAL]);
    notes.then_the_first_note_is(MOVIE, "Great Movie.avi", 700_000_000, &FileComment::of("great", 5));
}

#[test]
fn should_publish_a_comment_again_after_a_day() {
    let mut notes = Notes::new();
    notes.given_our_comment(MOVIE, FileComment::of("great", 5));
    notes.given_time_passes(Duration::from_secs(20));
    notes.given_time_passes(kad_publisher::NOTE_EVERY - Duration::from_secs(60));

    notes.when_time_passes(Duration::from_secs(120));

    notes.then_notes_were_published_for(&[MOVIE, MOVIE]);
}

#[test]
fn should_publish_a_changed_comment_at_once() {
    let mut notes = Notes::new();
    notes.given_our_comment(MOVIE, FileComment::of("great", 5));
    notes.given_time_passes(Duration::from_secs(20));
    notes.given_our_comment(MOVIE, FileComment::of("a fake", 1));

    notes.when_time_passes(Duration::from_secs(4));

    notes.then_notes_were_published_for(&[MOVIE, MOVIE]);
    notes.then_the_last_note_says(&FileComment::of("a fake", 1));
}

#[test]
fn should_no_longer_publish_a_comment_taken_away() {
    let mut notes = Notes::new();
    notes.given_our_comment(MOVIE, FileComment::of("great", 5));
    notes.given_time_passes(Duration::from_secs(20));
    notes.given_our_comment_is_taken_away(MOVIE);

    notes.when_time_passes(kad_publisher::NOTE_EVERY + Duration::from_secs(60));

    notes.then_notes_were_published_for(&[MOVIE]);
    notes.then_the_first_publish_now_reads_no_comment();
}

#[test]
fn should_publish_one_note_at_a_time_and_only_when_reachable() {
    let mut notes = Notes::new();
    notes.given_our_comment(MOVIE, FileComment::of("great", 5));
    notes.given_a_note_publish_runs_already();
    notes.given_time_passes(Duration::from_secs(20));
    notes.given_no_note_publish_runs();
    notes.given_the_node_is_firewalled();

    notes.when_time_passes(Duration::from_secs(20));

    notes.then_no_note_was_published();
}
