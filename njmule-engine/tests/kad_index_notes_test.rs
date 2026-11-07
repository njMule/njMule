// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadIndexNotesTest, under its names: the notes on files other nodes publish to us, their limits
//! and their lifetime.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::api::file_comment::FileComment;
use njmule_engine::kad::kad_index::{self, KadIndex};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::kad::kad_messages::Entry;
use njmule_engine::protocol::tag::{Tag, TagName, file_tag, tags};

const NOW: i64 = 1_000_000_000_000;
const HOUR: i64 = 3_600_000_000_000;
const SIZE: u64 = 700_000_000;
const IP: u32 = u32::from_be_bytes([81, 2, 3, 4]);

/// A note as a publisher sends it.
struct Published {
    publisher: UInt128,
    ip: u32,
    tags: Vec<Tag>,
}

struct Notes {
    index: KadIndex,
    file: UInt128,
    now: i64,
    load: Option<u8>,
    loads: Vec<Option<u8>>,
    found: Vec<Entry>,
}

impl Notes {
    fn new() -> Self {
        Self { index: KadIndex::new(), file: id(1), now: NOW, load: None, loads: Vec::new(), found: Vec::new() }
    }

    fn given_a_note(&mut self, publisher: UInt128, ip: u32, tags: &[Tag]) {
        self.load = self.index.publish_note(self.file, publisher, tags, ip, self.now);
    }

    fn given_notes_from_different_publishers(&mut self, count: u32) {
        for at in 0..count {
            self.given_a_note(
                id(1_000 + u64::from(at)),
                IP + at,
                &notes("a.avi", SIZE, 3, &format!("note {at}"), None),
            );
        }
    }

    fn given_the_index_is_full(&mut self) {
        let files = kad_index::MAX_NOTES / kad_index::MAX_NOTES_PER_FILE + 1;
        let mut stored = 0;
        for file in 0..files as u64 {
            for note in 0..kad_index::MAX_NOTES_PER_FILE as u32 {
                if stored >= kad_index::MAX_NOTES {
                    return;
                }
                let tags = notes("a", SIZE, 1, "x", None);
                self.index.publish_note(id(10_000 + file), id(u64::from(note)), &tags, IP + note, self.now);
                stored += 1;
            }
        }
    }

    fn given_time_passes(&mut self, nanos: i64) {
        self.now += nanos;
    }

    fn when_a_note_on_a_new_file_comes(&mut self) {
        self.load = self.index.publish_note(id(2), id(3), &notes("a", SIZE, 1, "x", None), IP, self.now);
    }

    fn when_these_notes_are_published(&mut self, notes: &[Published]) {
        for note in notes {
            self.loads.push(self.index.publish_note(self.file, note.publisher, &note.tags, note.ip, self.now));
        }
    }

    fn when_the_index_is_cleaned(&mut self) {
        self.index.clean(self.now);
        self.found = self.index.search_notes(self.file, 0, self.now);
    }

    fn when_the_notes_are_searched_for(&mut self, size: u64) {
        self.found = self.index.search_notes(self.file, size, self.now);
    }

    fn then_the_load_is(&self, expected: u8) {
        assert_eq!(self.load, Some(expected));
    }

    fn then_the_note_is_refused(&self) {
        assert_eq!(self.load, None);
    }

    fn then_every_note_is_refused(&self) {
        assert!(!self.loads.is_empty());
        assert!(self.loads.iter().all(Option::is_none), "{:?}", self.loads);
    }

    fn then_the_notes_found_come_from(&self, publishers: &[UInt128]) {
        let mut found: Vec<UInt128> = self.found.iter().map(|entry| entry.id).collect();
        let mut expected = publishers.to_vec();
        found.sort();
        expected.sort();
        assert_eq!(found, expected);
    }

    fn then_the_note_found_is(&self, name: &str, size: u64, rating: u64, comment: &str) {
        assert_eq!(self.found.len(), 1);
        let tags = &self.found[0].tags;
        assert_eq!(tags::string_value(tags, file_tag::FILENAME), Some(name));
        assert_eq!(tags::int_value(tags, file_tag::FILESIZE), Some(size));
        assert_eq!(tags::int_value(tags, file_tag::FILERATING), Some(rating));
        assert_eq!(tags::string_value(tags, file_tag::DESCRIPTION), Some(comment));
    }

    fn then_the_note_found_has_only_its_own_tags(&self) {
        assert_eq!(self.found[0].tags.len(), 4, "an unknown tag of the publisher is not passed on");
    }

    fn then_no_note_is_found(&self) {
        assert!(self.found.is_empty());
    }

    fn then_so_many_notes_are_found(&self, count: usize) {
        assert_eq!(self.found.len(), count);
    }

    fn then_the_oldest_notes_are_gone(&self, count: u64) {
        let gone: Vec<UInt128> = (0..count).map(|at| id(1_000 + at)).collect();
        assert!(!self.found.iter().any(|entry| gone.contains(&entry.id)));
    }

    fn then_the_index_holds_notes(&self, count: usize) {
        assert_eq!(self.index.note_entries(), count);
    }
}

fn id(low: u64) -> UInt128 {
    UInt128(0x1234u128 << 64 | u128::from(low))
}

fn notes(name: &str, size: u64, rating: u64, comment: &str, other: Option<Tag>) -> Vec<Tag> {
    let mut tags = vec![Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() }];
    if rating != 0 {
        tags.push(Tag::Int { name: TagName::id(file_tag::FILERATING), value: rating });
    }
    if !comment.is_empty() {
        tags.push(Tag::String { name: TagName::id(file_tag::DESCRIPTION), value: comment.to_owned() });
    }
    if size != 0 {
        tags.push(Tag::Int { name: TagName::id(file_tag::FILESIZE), value: size });
    }
    tags.extend(other);
    tags
}

#[test]
fn should_answer_a_search_with_what_the_note_says() {
    let mut notes_of = Notes::new();
    let other = Tag::Int { name: TagName::id(0x99), value: 7 };
    notes_of.given_a_note(id(100), IP, &notes("Film.avi", SIZE, 4, "good copy", Some(other)));

    notes_of.when_the_notes_are_searched_for(SIZE);

    notes_of.then_the_load_is(0);
    notes_of.then_the_notes_found_come_from(&[id(100)]);
    notes_of.then_the_note_found_is("Film.avi", SIZE, 4, "good copy");
    notes_of.then_the_note_found_has_only_its_own_tags();
}

#[test]
fn should_keep_one_note_per_publisher_and_per_address() {
    let mut notes_of = Notes::new();
    notes_of.given_a_note(id(100), IP, &notes("a.avi", SIZE, 1, "first", None));
    notes_of.given_a_note(id(100), IP + 1, &notes("a.avi", SIZE, 2, "same publisher, new address", None));
    notes_of.given_a_note(id(101), IP + 1, &notes("a.avi", SIZE, 3, "same address, new publisher", None));
    notes_of.given_a_note(id(102), IP + 2, &notes("a.avi", SIZE, 5, "another", None));

    notes_of.when_the_notes_are_searched_for(0);

    notes_of.then_the_notes_found_come_from(&[id(101), id(102)]);
    notes_of.then_the_index_holds_notes(2);
}

#[test]
fn should_refuse_a_note_that_says_nothing_or_has_no_address() {
    let mut notes_of = Notes::new();

    notes_of.when_these_notes_are_published(&[
        Published { publisher: id(100), ip: IP, tags: notes("a.avi", SIZE, 0, "   ", None) },
        Published { publisher: id(101), ip: IP, tags: notes("a.avi", SIZE, 9, "", None) },
        Published { publisher: id(102), ip: 0, tags: notes("a.avi", SIZE, 3, "fine", None) },
    ]);

    notes_of.then_every_note_is_refused();
    notes_of.then_the_index_holds_notes(0);
}

#[test]
fn should_keep_the_newest_notes_of_a_file_when_it_has_too_many() {
    let mut notes_of = Notes::new();
    notes_of.given_notes_from_different_publishers(kad_index::MAX_NOTES_PER_FILE as u32 + 5);

    notes_of.when_the_notes_are_searched_for(SIZE);

    notes_of.then_the_load_is(100);
    notes_of.then_so_many_notes_are_found(kad_index::MAX_NOTES_PER_FILE);
    notes_of.then_the_oldest_notes_are_gone(5);
}

#[test]
fn should_stop_at_the_cap_of_all_notes_taking_no_new_file() {
    let mut notes_of = Notes::new();
    notes_of.given_the_index_is_full();

    notes_of.when_a_note_on_a_new_file_comes();

    notes_of.then_the_note_is_refused();
    notes_of.then_the_index_holds_notes(kad_index::MAX_NOTES);
}

#[test]
fn should_forget_a_note_nobody_renews_within_a_day() {
    let mut notes_of = Notes::new();
    notes_of.given_a_note(id(100), IP, &notes("a.avi", SIZE, 3, "fine", None));
    notes_of.given_time_passes(25 * HOUR);

    notes_of.when_the_index_is_cleaned();

    notes_of.then_no_note_is_found();
    notes_of.then_the_index_holds_notes(0);
}

#[test]
fn should_leave_out_notes_on_a_file_of_another_size() {
    let mut notes_of = Notes::new();
    notes_of.given_a_note(id(100), IP, &notes("a.avi", SIZE, 3, "this size", None));
    notes_of.given_a_note(id(101), IP + 1, &notes("a.avi", SIZE + 1, 3, "another size", None));
    notes_of.given_a_note(id(102), IP + 2, &notes("a.avi", 0, 3, "no size", None));

    notes_of.when_the_notes_are_searched_for(SIZE);

    notes_of.then_the_notes_found_come_from(&[id(100), id(102)]);
}

#[test]
fn should_cut_long_names_and_comments() {
    let mut notes_of = Notes::new();
    notes_of.given_a_note(id(100), IP, &notes(&"n".repeat(1_000), SIZE, 3, &"c".repeat(1_000), None));

    notes_of.when_the_notes_are_searched_for(SIZE);

    notes_of.then_the_note_found_is(
        &"n".repeat(kad_index::MAX_NOTE_NAME),
        SIZE,
        3,
        &"c".repeat(FileComment::MAX_LENGTH),
    );
}
