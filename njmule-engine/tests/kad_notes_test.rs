// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNotesTest, under its names: the notes Kad finds on our files, on request and once a run for each
//! download, as the comment filter lets them be shown.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::time::{Duration, Instant};

use indexmap::IndexMap;
use njmule_engine::api::file_comment::FileComment;
use njmule_engine::api::kad_note::KadNote;
use njmule_engine::files::comment_filter::CommentFilter;
use njmule_engine::kad::kad_notes::{self, Download, Finder, KadNotes, Note};
use njmule_engine::kad::uint128::UInt128;
use tokio::sync::oneshot;

const FILM: [u8; 16] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef];
const SONG: [u8; 16] = [0xfe, 0xdc, 0xba, 0x98, 0x76, 0x54, 0x32, 0x10, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0x54, 0x32, 0x10];
const SIZE: u64 = 700_000_000;
const SECOND: Duration = Duration::from_secs(1);

type Answer = oneshot::Receiver<Result<Vec<KadNote>, String>>;

/// Kad as the notes see it: every search starts unless told otherwise.
#[derive(Default)]
struct FakeKad {
    searches: IndexMap<[u8; 16], u64>,
    not_ready: bool,
    busy: bool,
}

impl Finder for FakeKad {
    fn is_ready(&self) -> bool {
        !self.not_ready
    }

    fn find_notes(&mut self, file: [u8; 16], size: u64) -> bool {
        if self.busy {
            return false;
        }
        self.searches.insert(file, size);
        true
    }
}

struct Notes {
    finder: FakeKad,
    downloads: Vec<Download>,
    now: Instant,
    connected: bool,
    filter: String,
    notes: KadNotes,
    search: Option<Answer>,
    joined: Option<Answer>,
}

impl Notes {
    fn new() -> Self {
        let now = Instant::now();
        Self {
            finder: FakeKad::default(),
            downloads: Vec::new(),
            now,
            connected: true,
            filter: "http://".to_owned(),
            notes: KadNotes::new(now),
            search: None,
            joined: None,
        }
    }

    fn given_a_search_for(&mut self, file: [u8; 16]) {
        self.when_the_file_is_searched(file);
    }

    fn given_kad_finds(&mut self, file: [u8; 16], found: Vec<Note>) {
        assert!(self.finder.searches.contains_key(&file));
        self.notes.found(&file, found);
    }

    fn given_the_search_ended(&mut self, file: [u8; 16]) {
        self.when_the_search_ends(file);
    }

    fn given_kad_is_not_connected(&mut self) {
        self.connected = false;
    }

    fn given_kad_is_busy(&mut self) {
        self.finder.busy = true;
    }

    fn given_kad_is_not_ready(&mut self) {
        self.finder.not_ready = true;
    }

    fn given_a_download(&mut self, file: [u8; 16]) {
        self.downloads.push(Download { hash: file, size: SIZE });
    }

    fn given_time_passes(&mut self, time: Duration) {
        self.when_time_passes(time);
    }

    fn given_kad_finds_notes_with_spam(&mut self) {
        self.given_kad_finds(
            FILM,
            vec![
                note(1, "Film.avi", "see http://spam.example", 1),
                note(2, "HTTP://spam.example Film.avi", "fine", 1),
                note(3, "Film.avi", "fine", 3),
            ],
        );
    }

    fn given_the_file_is_searched_again(&mut self, file: [u8; 16]) {
        let (done, answer) = oneshot::channel();
        let kad: Option<&mut dyn Finder> = if self.connected { Some(&mut self.finder) } else { None };
        self.notes.search(file, SIZE, kad, done);
        self.joined = Some(answer);
    }

    fn when_the_file_is_searched(&mut self, file: [u8; 16]) {
        let (done, answer) = oneshot::channel();
        let kad: Option<&mut dyn Finder> = if self.connected { Some(&mut self.finder) } else { None };
        self.notes.search(file, SIZE, kad, done);
        self.search = Some(answer);
    }

    fn when_the_search_ends(&mut self, file: [u8; 16]) {
        assert!(self.finder.searches.contains_key(&file));
        let filter = CommentFilter::of(&self.filter);
        self.notes.ended(&file, &filter);
    }

    fn when_so_many_other_files_are_searched(&mut self, count: usize) {
        for at in 0..count {
            let mut other = [0u8; 16];
            other[0] = (at >> 8) as u8;
            other[1] = at as u8;
            other[2] = 1;
            self.when_the_file_is_searched(other);
            self.when_the_search_ends(other);
        }
    }

    fn when_the_filter_becomes(&mut self, words: &str) {
        self.filter = words.to_owned();
    }

    fn when_kad_stops(&mut self) {
        let filter = CommentFilter::of(&self.filter);
        self.notes.kad_stopped(&filter);
    }

    fn when_kad_finds_so_many_notes(&mut self, file: [u8; 16], count: u64) {
        let found = (0..count).map(|at| note(100 + at, "Film.avi", &format!("note {at}"), 3)).collect();
        self.notes.found(&file, found);
    }

    // The notes run every second, as the engine runs them.
    fn when_time_passes(&mut self, time: Duration) {
        let mut passed = Duration::ZERO;
        while passed < time {
            passed += SECOND;
            self.now += SECOND;
            let (now, downloads) = (self.now, self.downloads.clone());
            let kad: Option<&mut dyn Finder> = if self.connected { Some(&mut self.finder) } else { None };
            self.notes.tick(now, kad, &downloads);
        }
    }

    fn then_the_search_gives(&mut self, expected: &[KadNote]) {
        let given = self.search.as_mut().unwrap().try_recv().expect("the search ended");
        assert_eq!(given.unwrap(), expected);
    }

    fn then_the_file_has_notes(&self, file: [u8; 16], count: usize) {
        assert_eq!(self.notes.notes(&file, &CommentFilter::of(&self.filter)).len(), count);
    }

    fn then_kad_was_asked_only_once(&self) {
        assert_eq!(self.finder.searches.len(), 1);
    }

    fn then_both_searches_ended(&mut self) {
        assert!(self.search.as_mut().unwrap().try_recv().is_ok());
        assert!(self.joined.as_mut().unwrap().try_recv().is_ok());
    }

    fn then_the_search_fails(&mut self, why: &str) {
        let given = self.search.as_mut().unwrap().try_recv().expect("the search ended");
        assert!(given.unwrap_err().contains(why));
    }

    fn then_kad_was_asked_for(&self, files: &[[u8; 16]]) {
        assert_eq!(self.finder.searches.keys().copied().collect::<Vec<_>>(), files);
    }
}

fn note(publisher: u64, name: &str, comment: &str, rating: u8) -> Note {
    Note { publisher: id(publisher), file_name: name.to_owned(), size: SIZE, comment: FileComment::of(comment, rating) }
}

fn id(publisher: u64) -> UInt128 {
    UInt128(7u128 << 64 | u128::from(publisher))
}

fn kad_note(publisher: u64, name: &str, comment: &str, rating: u8) -> KadNote {
    KadNote { publisher: id(publisher).to_hex(), file_name: name.to_owned(), comment: FileComment::of(comment, rating) }
}

#[test]
fn should_keep_each_publishers_latest_note() {
    let mut notes = Notes::new();
    notes.given_a_search_for(FILM);
    notes.given_kad_finds(FILM, vec![note(1, "Film.avi", "first words", 2), note(2, "film.avi", "", 4)]);
    notes.given_kad_finds(FILM, vec![note(1, "Film.avi", "second thoughts", 5)]);

    notes.when_the_search_ends(FILM);

    notes.then_the_search_gives(&[kad_note(2, "film.avi", "", 4), kad_note(1, "Film.avi", "second thoughts", 5)]);
}

#[test]
fn should_leave_out_what_the_comment_filter_holds_in_the_comment_or_the_name() {
    let mut notes = Notes::new();
    notes.given_a_search_for(FILM);
    notes.given_kad_finds_notes_with_spam();

    notes.when_the_search_ends(FILM);

    notes.then_the_search_gives(&[kad_note(3, "Film.avi", "fine", 3)]);
}

#[test]
fn should_show_what_a_changed_filter_no_longer_holds() {
    let mut notes = Notes::new();
    notes.given_a_search_for(FILM);
    notes.given_kad_finds_notes_with_spam();
    notes.given_the_search_ended(FILM);

    notes.when_the_filter_becomes("");

    notes.then_the_file_has_notes(FILM, 3);
}

#[test]
fn should_join_the_search_running_for_the_file() {
    let mut notes = Notes::new();
    notes.given_a_search_for(FILM);
    notes.given_the_file_is_searched_again(FILM);

    notes.when_the_search_ends(FILM);

    notes.then_kad_was_asked_only_once();
    notes.then_both_searches_ended();
}

#[test]
fn should_fail_when_kad_is_not_connected() {
    let mut notes = Notes::new();
    notes.given_kad_is_not_connected();

    notes.when_the_file_is_searched(FILM);

    notes.then_the_search_fails("Kad is not connected");
}

#[test]
fn should_fail_when_kad_is_busy_with_the_file() {
    let mut notes = Notes::new();
    notes.given_kad_is_busy();

    notes.when_the_file_is_searched(FILM);

    notes.then_the_search_fails("busy");
}

#[test]
fn should_search_each_running_download_once_a_run_one_at_a_time() {
    let mut notes = Notes::new();
    notes.given_a_download(FILM);
    notes.given_a_download(SONG);

    notes.when_time_passes(Duration::from_secs(1));

    notes.then_kad_was_asked_for(&[FILM]);
}

#[test]
fn should_search_the_next_download_only_after_a_pause() {
    let mut notes = Notes::new();
    notes.given_a_download(FILM);
    notes.given_a_download(SONG);
    notes.given_time_passes(Duration::from_secs(1));
    notes.given_the_search_ended(FILM);
    notes.given_time_passes(Duration::from_secs(3));

    notes.when_time_passes(kad_notes::AUTOMATIC_EVERY);

    notes.then_kad_was_asked_for(&[FILM, SONG]);
}

#[test]
fn should_not_search_for_downloads_before_kad_knows_the_network() {
    let mut notes = Notes::new();
    notes.given_a_download(FILM);
    notes.given_kad_is_not_ready();

    notes.when_time_passes(Duration::from_secs(30));

    notes.then_kad_was_asked_for(&[]);
}

#[test]
fn should_end_the_searches_when_kad_stops() {
    let mut notes = Notes::new();
    notes.given_a_search_for(FILM);
    notes.given_kad_finds(FILM, vec![note(1, "Film.avi", "good", 4)]);

    notes.when_kad_stops();

    notes.then_the_search_gives(&[kad_note(1, "Film.avi", "good", 4)]);
}

#[test]
fn should_keep_at_most_its_share_of_notes_for_a_file() {
    let mut notes = Notes::new();
    notes.given_a_search_for(FILM);

    notes.when_kad_finds_so_many_notes(FILM, kad_notes::MAX_NOTES_PER_FILE as u64 + 10);

    notes.then_the_file_has_notes(FILM, kad_notes::MAX_NOTES_PER_FILE);
}

#[test]
fn should_forget_the_notes_of_the_file_searched_longest_ago() {
    let mut notes = Notes::new();
    notes.given_a_search_for(FILM);
    notes.given_kad_finds(FILM, vec![note(1, "Film.avi", "good", 4)]);
    notes.given_the_search_ended(FILM);

    notes.when_so_many_other_files_are_searched(kad_notes::MAX_FILES);

    notes.then_the_file_has_notes(FILM, 0);
}
