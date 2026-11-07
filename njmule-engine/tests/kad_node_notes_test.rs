// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodeNotesTest, under its names: notes over Kad as one node sees them, stored and answered
//! for others, published and searched for us.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod kad_lab;

use std::sync::{Arc, Mutex};
use std::time::Duration;

use kad_lab::{KadLab, PORT, ip};
use njmule_engine::api::file_comment::FileComment;
use njmule_engine::api::kad_search_info::Kind;
use njmule_engine::kad::kad_node::{KadEvent, KadNode};
use njmule_engine::kad::kad_notes::Note;
use njmule_engine::kad::kad_publisher::Node;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::{self, UdpKey};
use njmule_engine::protocol::kad::kad_messages::{
    Answer, Entry, NotesPublish, NotesSearch, PublishAnswer, SearchAnswer,
};
use njmule_engine::protocol::packet::Packet;
use njmule_engine::protocol::packet::opcodes::kad;
use njmule_engine::protocol::tag::{Tag, TagName, file_tag, tags};
use rand::RngExt;

const PEER: u32 = ip(20, 1, 1, 1);
const OTHER: u32 = ip(21, 1, 1, 1);
const SEARCHER: u32 = ip(23, 1, 1, 1);
const HOUR: Duration = Duration::from_secs(3_600);
const SIZE: u64 = 700_000_000;

struct Notes {
    lab: KadLab,
    node: KadNode,
    our_comment: Arc<Mutex<Option<FileComment>>>,
    file: [u8; 16],
    target: UInt128,
    found: Vec<Note>,
    started: bool,
    ended: bool,
}

impl Notes {
    fn new() -> Self {
        let mut lab = KadLab::new();
        let our_comment: Arc<Mutex<Option<FileComment>>> = Arc::default();
        let mut settings = kad_lab::settings();
        let comment = Arc::clone(&our_comment);
        settings.comments = Arc::new(move |_| comment.lock().unwrap().clone());
        let node = lab.node(settings);
        Self {
            lab,
            node,
            our_comment,
            file: [0; 16],
            target: UInt128(0),
            found: Vec::new(),
            started: false,
            ended: false,
        }
    }

    fn given_a_contact_at_the_file(&mut self) {
        self.file = self.lab.random.random();
        self.target = UInt128::from_big_endian(self.file);
        self.given_a_contact_at(PEER, self.target);
    }

    fn given_a_contact_at(&mut self, ip: u32, id: UInt128) {
        let contact = nodes_dat::Contact {
            id,
            ip,
            udp_port: PORT,
            tcp_port: 4662,
            version: 8,
            contact_type: 0,
            udp_key: UdpKey::default(),
            verified: true,
        };
        self.node.add_contacts(&nodes_dat::Contents { file_version: 2, bootstrap: false, contacts: vec![contact] });
    }

    fn given_a_source_search_reached_the_node_at(&mut self, ip: u32) {
        let far = far_from(self.target);
        assert!(self.node.find_sources(far.to_big_endian(), SIZE));
        self.receive(ip, &Answer { target: far, contacts: Vec::new() }.encode().unwrap());
    }

    fn given_a_source_search_of_the_file(&mut self) {
        assert!(self.node.find_sources(self.file, SIZE));
    }

    fn given_the_notes_are_searched_for(&mut self) {
        self.when_the_notes_are_searched_for();
    }

    fn given_the_lookup_reached_the_node_at(&mut self, ip: u32) {
        self.when_the_lookup_reaches_the_node_at(ip);
    }

    fn given_our_comment(&self, comment: FileComment) {
        *self.our_comment.lock().unwrap() = Some(comment);
    }

    fn given_our_note_is_published(&mut self, name: &str) {
        self.started = self.node.publish_note(self.file, name, SIZE);
    }

    fn given_a_note_published_by(&mut self, ip: u32, file: UInt128, tags: Vec<Tag>) {
        self.when_a_note_published_by(ip, file, tags);
    }

    fn given_time_passes(&self, by: Duration) {
        self.lab.advance(by);
    }

    fn when_a_note_published_by(&mut self, ip: u32, file: UInt128, tags: Vec<Tag>) {
        self.receive(ip, &NotesPublish { file, publisher: id(77), tags }.encode().unwrap());
    }

    fn when_the_notes_are_searched_for_by(&mut self, ip: u32, file: UInt128, size: u64) {
        self.receive(ip, &NotesSearch { target: file, size }.encode());
    }

    fn when_the_notes_are_searched_for(&mut self) {
        self.started = self.node.find_notes(self.file, SIZE);
    }

    // The node answers the lookup, which then sends it the actual request.
    fn when_the_lookup_reaches_the_node_at(&mut self, ip: u32) {
        self.receive(ip, &Answer { target: self.target, contacts: Vec::new() }.encode().unwrap());
        self.lab.advance(Duration::from_secs(4));
        self.node.tick();
    }

    fn when_the_node_at_answers(&mut self, ip: u32, notes: Vec<Entry>) {
        let answer = SearchAnswer { sender: id(9), target: self.target, results: notes };
        self.receive(ip, &answer.encode().unwrap());
    }

    fn when_time_passes_for_the_node(&mut self, by: Duration) {
        for _ in 0..by.as_secs() {
            self.lab.advance(Duration::from_secs(1));
            self.node.tick();
        }
    }

    fn then_the_publisher_heard_it_was_stored(&self, ip: u32, file: UInt128) {
        assert_eq!(PublishAnswer::decode(&self.last(ip, kad::PUBLISH_RES).unwrap()).unwrap().target, file);
    }

    fn then_the_publisher_heard_stored_so_many_times(&self, ip: u32, times: usize) {
        assert_eq!(self.lab.sent().iter().filter(|sent| sent.ip == ip).count(), times);
    }

    fn then_the_node_indexes_notes(&self, count: usize) {
        assert_eq!(self.node.indexed_notes(), count);
    }

    fn then_the_searcher_gets_the_note(&self, ip: u32, name: &str, rating: u64, comment: &str) {
        let answer = SearchAnswer::decode(&self.last(ip, kad::SEARCH_RES).unwrap()).unwrap();
        assert_eq!(answer.sender, self.node.id());
        assert_eq!(answer.results.len(), 1);
        let entry = &answer.results[0];
        assert_eq!(entry.id, id(77), "the publisher's id");
        assert_eq!(tags::string_value(&entry.tags, file_tag::FILENAME), Some(name));
        assert_eq!(tags::int_value(&entry.tags, file_tag::FILERATING), Some(rating));
        assert_eq!(tags::string_value(&entry.tags, file_tag::DESCRIPTION), Some(comment));
    }

    fn then_nothing_was_sent_to(&self, ip: u32) {
        assert!(!self.lab.sent().iter().any(|sent| sent.ip == ip));
    }

    fn then_the_publish_was_started(&self) {
        assert!(self.started);
    }

    fn then_the_search_was_started(&self) {
        assert!(self.started);
    }

    fn then_the_search_was_not_started(&self) {
        assert!(!self.started);
    }

    fn then_the_lookup_shows_as(&self, kind: Kind) {
        let kinds: Vec<Kind> = self.node.lookup_infos().into_iter().map(|info| info.kind).collect();
        assert_eq!(kinds, [kind]);
    }

    fn then_the_node_at_was_sent_our_note(&self, ip: u32, name: &str, rating: u64, comment: &str) {
        let publish = NotesPublish::decode(&self.last(ip, kad::PUBLISH_NOTES_REQ).unwrap()).unwrap();
        assert_eq!(publish.file, self.target);
        assert_eq!(publish.publisher, self.node.id(), "our Kad id");
        let names: Vec<Option<u8>> = publish.tags.iter().map(|tag| tag.name().as_id()).collect();
        assert_eq!(
            names,
            [
                Some(file_tag::FILENAME),
                Some(file_tag::FILERATING),
                Some(file_tag::DESCRIPTION),
                Some(file_tag::FILESIZE)
            ]
        );
        assert_eq!(tags::string_value(&publish.tags, file_tag::FILENAME), Some(name));
        assert_eq!(tags::int_value(&publish.tags, file_tag::FILERATING), Some(rating));
        assert_eq!(tags::string_value(&publish.tags, file_tag::DESCRIPTION), Some(comment));
        assert_eq!(tags::int_value(&publish.tags, file_tag::FILESIZE), Some(SIZE));
    }

    fn then_no_note_was_sent_to(&self, ip: u32) {
        assert!(self.last(ip, kad::PUBLISH_NOTES_REQ).is_none());
    }

    fn then_no_note_publish_runs(&mut self) {
        self.node.tick();
        assert_eq!(self.node.running_note_publishes(), 0);
    }

    fn then_the_node_at_was_asked_for_notes_of_size(&self, ip: u32, size: u64) {
        let search = NotesSearch::decode(&self.last(ip, kad::SEARCH_NOTES_REQ).unwrap()).unwrap();
        assert_eq!(search.target, self.target);
        assert_eq!(search.size, size);
    }

    fn then_the_notes_found_come_from(&mut self, publishers: &[UInt128]) {
        self.take_events();
        assert_eq!(self.found.iter().map(|note| note.publisher).collect::<Vec<_>>(), publishers);
    }

    fn then_the_note_from_is(&mut self, publisher: UInt128, name: &str, comment: FileComment) {
        self.take_events();
        let notes: Vec<&Note> = self.found.iter().filter(|note| note.publisher == publisher).collect();
        assert_eq!(notes.len(), 1);
        assert_eq!(notes[0].file_name, name);
        assert_eq!(notes[0].size, SIZE);
        assert_eq!(notes[0].comment, comment);
    }

    fn then_no_note_is_found(&mut self) {
        self.take_events();
        assert!(self.found.is_empty());
    }

    fn then_the_search_ended(&mut self) {
        self.take_events();
        assert!(self.ended);
    }

    fn then_no_notes_search_runs(&self) {
        assert!(!self.node.lookup_infos().iter().any(|info| info.kind == Kind::Notes));
    }

    fn take_events(&mut self) {
        for event in self.node.take_events() {
            match event {
                KadEvent::Notes { notes, .. } => self.found.extend(notes),
                KadEvent::NotesEnded { .. } => self.ended = true,
                _ => {}
            }
        }
    }

    fn receive(&mut self, ip: u32, packet: &Packet) {
        self.node.received(ip, PORT, packet, 0, 0);
    }

    fn last(&self, ip: u32, opcode: u8) -> Option<Packet> {
        self.lab
            .sent()
            .into_iter()
            .rev()
            .find(|sent| sent.ip == ip && sent.packet.opcode() == opcode)
            .map(|sent| sent.packet)
    }
}

fn id(low: u128) -> UInt128 {
    UInt128(0x5678 << 64 | low)
}

// Within the tolerance of a publish to us.
fn near(id: UInt128, low: u128) -> UInt128 {
    id ^ UInt128(low)
}

// An id whose top bits differ from ours: far beyond the tolerance of a publish.
fn far_from(id: UInt128) -> UInt128 {
    id ^ UInt128(u128::from(u64::MAX) << 64)
}

fn note(name: &str, size: u64, rating: u64, comment: &str) -> Vec<Tag> {
    let mut tags = Vec::new();
    if !name.is_empty() {
        tags.push(Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() });
    }
    if rating != 0 {
        tags.push(Tag::Int { name: TagName::id(file_tag::FILERATING), value: rating });
    }
    if !comment.is_empty() {
        tags.push(Tag::String { name: TagName::id(file_tag::DESCRIPTION), value: comment.to_owned() });
    }
    if size != 0 {
        tags.push(Tag::Int { name: TagName::id(file_tag::FILESIZE), value: size });
    }
    tags
}

#[test]
fn should_store_a_note_published_near_us_and_answer_its_search() {
    let mut notes = Notes::new();
    let own = notes.node.id();
    notes.given_a_note_published_by(PEER, own, note("Film.avi", SIZE, 4, "a good copy"));

    notes.when_the_notes_are_searched_for_by(SEARCHER, own, SIZE);

    notes.then_the_publisher_heard_it_was_stored(PEER, own);
    notes.then_the_node_indexes_notes(1);
    notes.then_the_searcher_gets_the_note(SEARCHER, "Film.avi", 4, "a good copy");
}

#[test]
fn should_not_store_a_note_published_far_from_us() {
    let mut notes = Notes::new();
    let far = far_from(notes.node.id());
    notes.given_a_note_published_by(PEER, far, note("Film.avi", SIZE, 4, "a good copy"));

    notes.when_the_notes_are_searched_for_by(SEARCHER, far, SIZE);

    notes.then_nothing_was_sent_to(PEER);
    notes.then_nothing_was_sent_to(SEARCHER);
    notes.then_the_node_indexes_notes(0);
}

#[test]
fn should_serve_only_two_note_publishes_a_minute_from_one_address() {
    let mut notes = Notes::new();
    let own = notes.node.id();
    notes.given_a_note_published_by(PEER, near(own, 1), note("a.avi", SIZE, 1, "one"));
    notes.given_a_note_published_by(PEER, near(own, 2), note("b.avi", SIZE, 2, "two"));

    notes.when_a_note_published_by(PEER, near(own, 3), note("c.avi", SIZE, 3, "three"));

    notes.then_the_publisher_heard_stored_so_many_times(PEER, 2);
    notes.then_the_node_indexes_notes(2);
}

#[test]
fn should_forget_a_note_nobody_renews_within_a_day() {
    let mut notes = Notes::new();
    let own = notes.node.id();
    notes.given_a_note_published_by(PEER, own, note("Film.avi", SIZE, 4, "a good copy"));
    notes.given_time_passes(HOUR * 25);

    notes.when_the_notes_are_searched_for_by(SEARCHER, own, SIZE);

    notes.then_nothing_was_sent_to(SEARCHER);
}

#[test]
fn should_publish_our_note_with_its_name_rating_comment_and_size() {
    let mut notes = Notes::new();
    notes.given_a_contact_at_the_file();
    notes.given_our_comment(FileComment::of("great film", 5));
    notes.given_our_note_is_published("Film.avi");

    notes.when_the_lookup_reaches_the_node_at(PEER);

    notes.then_the_publish_was_started();
    notes.then_the_lookup_shows_as(Kind::StoreNotes);
    notes.then_the_node_at_was_sent_our_note(PEER, "Film.avi", 5, "great film");
}

#[test]
fn should_stop_publishing_a_comment_taken_away_meanwhile() {
    let mut notes = Notes::new();
    notes.given_a_contact_at_the_file();
    notes.given_our_comment(FileComment::of("great film", 5));
    notes.given_our_note_is_published("Film.avi");
    notes.given_our_comment(FileComment::NONE);

    notes.when_the_lookup_reaches_the_node_at(PEER);

    notes.then_the_publish_was_started();
    notes.then_no_note_was_sent_to(PEER);
    notes.then_no_note_publish_runs();
}

#[test]
fn should_ask_the_nodes_near_the_file_for_its_notes_of_that_size() {
    let mut notes = Notes::new();
    notes.given_a_contact_at_the_file();
    notes.given_the_notes_are_searched_for();

    notes.when_the_lookup_reaches_the_node_at(PEER);

    notes.then_the_search_was_started();
    notes.then_the_lookup_shows_as(Kind::Notes);
    notes.then_the_node_at_was_asked_for_notes_of_size(PEER, SIZE);
}

#[test]
fn should_take_each_publishers_note_once_and_only_notes_on_this_file() {
    let mut notes = Notes::new();
    notes.given_a_contact_at_the_file();
    notes.given_the_notes_are_searched_for();
    notes.given_the_lookup_reached_the_node_at(PEER);

    notes.when_the_node_at_answers(
        PEER,
        vec![
            Entry { id: id(1), tags: note("Film.avi", SIZE, 4, "good") },
            Entry { id: id(1), tags: note("Film.avi", SIZE, 1, "the same publisher again") },
            Entry { id: id(2), tags: note("Film.avi", SIZE, 0, "") },
            Entry { id: id(3), tags: note("Other.avi", SIZE + 1, 2, "another size") },
            Entry { id: id(4), tags: note("", 0, 3, "no name nor size") },
        ],
    );

    notes.then_the_notes_found_come_from(&[id(1), id(4)]);
    notes.then_the_note_from_is(id(1), "Film.avi", FileComment::of("good", 4));
}

#[test]
fn should_ignore_notes_from_a_node_this_search_did_not_ask() {
    let mut notes = Notes::new();
    notes.given_a_contact_at_the_file();
    notes.given_a_contact_at(OTHER, far_from(notes.target));
    notes.given_a_source_search_reached_the_node_at(OTHER);
    notes.given_the_notes_are_searched_for();
    notes.given_the_lookup_reached_the_node_at(PEER);

    notes.when_the_node_at_answers(OTHER, vec![Entry { id: id(1), tags: note("Film.avi", SIZE, 4, "planted") }]);

    notes.then_no_note_is_found();
}

#[test]
fn should_tell_when_the_search_ends() {
    let mut notes = Notes::new();
    notes.given_a_contact_at_the_file();
    notes.given_the_notes_are_searched_for();
    notes.given_the_lookup_reached_the_node_at(PEER);

    notes.when_time_passes_for_the_node(Duration::from_secs(60));

    notes.then_the_search_ended();
    notes.then_no_notes_search_runs();
}

#[test]
fn should_not_search_a_file_that_is_looked_up_already() {
    let mut notes = Notes::new();
    notes.given_a_contact_at_the_file();
    notes.given_a_source_search_of_the_file();

    notes.when_the_notes_are_searched_for();

    notes.then_the_search_was_not_started();
}
