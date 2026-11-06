// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's SpamFilterTest, under its names: the markers a search result marked as spam leaves, how they rate
//! other results, and `SearchSpam.met`, which keeps them.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fs;
use std::path::{Path, PathBuf};

use njmule_engine::api::search_result::{Origin, SearchResult};
use njmule_engine::api::spam_marks::SpamMarks;
use njmule_engine::persist::format::search_files;
use njmule_engine::protocol::io::{PacketWriter, StringEncoding};
use njmule_engine::protocol::tag::tag_type::{BSOB, NEW_ED2K_FLAG};
use njmule_engine::protocol::tag::{Ed2kTagFormat, Tag, TagName, tag_codec};
use njmule_engine::search::spam_filter::{self, SpamFilter};
use njmule_engine::util::ipv4;

const DAMAGED: [u8; 6] = [0x0F, 5, 0, 0, 0, 0x99];
const SPAM_SERVER: u32 = ipv4::of(198, 51, 100, 9);
const OTHER_SERVER: u32 = ipv4::of(198, 51, 100, 10);
const SPAM_SOURCE: u32 = ipv4::of(203, 0, 113, 77);
const MIB: u64 = 1 << 20;

struct Scenario {
    dir: tempfile::TempDir,
    filter: SpamFilter,
    marks: SpamMarks,
}

impl Scenario {
    fn new() -> Self {
        Self { dir: tempfile::tempdir().unwrap(), filter: SpamFilter::default(), marks: SpamMarks::default() }
    }

    fn of_the_file() -> Self {
        let mut scenario = Self::new();
        scenario.filter = SpamFilter::load(&scenario.spam_file()).unwrap();
        scenario
    }

    fn spam_file(&self) -> PathBuf {
        self.dir.path().join(spam_filter::FILE_NAME)
    }

    fn load(&self) -> SpamFilter {
        SpamFilter::load(&self.spam_file()).unwrap()
    }

    fn saved_and_loaded_again(&mut self) {
        self.filter.save(&self.spam_file()).unwrap();
        self.filter = self.load();
    }

    fn saved_spam_hashes(&self) -> Vec<[u8; 16]> {
        let mut hashes: Vec<_> = self.load().marks().spam.into_iter().collect();
        hashes.sort_unstable();
        hashes
    }
}

fn hash(hex: &str) -> [u8; 16] {
    hex::decode(hex.repeat(16)).unwrap().try_into().unwrap()
}

fn hashes(hexes: &[&str]) -> Vec<[u8; 16]> {
    let mut hashes: Vec<_> = hexes.iter().map(|hex| hash(hex)).collect();
    hashes.sort_unstable();
    hashes
}

fn result(hex: &str, name: &str) -> SearchResult {
    SearchResult::new(hash(hex), name, 1000, 1, Some(0), None)
}

fn file(hex: &str, name: &str, size: u64, origin: Origin) -> SearchResult {
    SearchResult { origin, ..SearchResult::new(hash(hex), name, size, 3, Some(0), None) }
}

fn popular(result: SearchResult) -> SearchResult {
    SearchResult { sources: 250, complete_sources: Some(250), ..result }
}

fn bare(name: &str) -> Origin {
    Origin::new([], true, [], name)
}

fn udp(servers: &[u32]) -> Origin {
    Origin::new(servers.iter().copied(), false, [], "")
}

fn source(ip: u32) -> Origin {
    Origin::new([], true, [ip], "")
}

fn origin(server: u32, source: u32, bare_name: &str) -> Origin {
    Origin::new([server], false, [source], bare_name)
}

fn set_aside(dir: &Path) -> Vec<PathBuf> {
    fs::read_dir(dir)
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .filter(|path| {
            path.file_name().unwrap().to_string_lossy().starts_with(&format!("{}.corrupt-", spam_filter::FILE_NAME))
        })
        .collect()
}

fn rating_after(marked: SearchResult, rated: SearchResult) -> u32 {
    let mut filter = SpamFilter::default();
    filter.mark(&marked, true);
    filter.rate(&rated)
}

#[test]
fn should_keep_the_marks_by_hash_and_name_in_the_file() {
    let mut scenario = Scenario::of_the_file();
    assert!(!scenario.filter.marks().is_spam(&result("ab", "Movie 2026 FULL.avi")));
    scenario.filter.mark(&result("ab", "Movie 2026 FULL.avi"), true);

    scenario.filter.save(&scenario.spam_file()).unwrap();
    let marks = scenario.load().marks();

    assert!(marks.is_spam(&result("ab", "Movie 2026 FULL.avi")));
    assert!(marks.is_spam(&result("cd", "movie 2026 full.AVI")), "the same name, in any case");
    assert!(!marks.is_spam(&result("ef", "Holiday.jpg")));
}

#[test]
fn should_clear_the_name_but_keep_the_hash_when_a_copy_with_the_same_name_is_unmarked() {
    let mut scenario = Scenario::of_the_file();
    scenario.filter.mark(&result("ab", "Movie 2026 FULL.avi"), true);
    scenario.saved_and_loaded_again();

    scenario.filter.mark(&result("cd", "movie 2026 full.AVI"), false);
    scenario.marks = scenario.filter.marks();

    assert!(!scenario.marks.is_spam(&result("cd", "movie 2026 full.AVI")));
    assert!(scenario.marks.is_spam(&result("ab", "Movie 2026 FULL.avi")), "still by its own hash");
    assert!(scenario.marks.names.is_empty(), "the name is cleared");
}

#[test]
fn should_not_mark_other_files_by_a_short_name_alone() {
    let mut filter = SpamFilter::default();
    filter.mark(&result("ab", "setup.exe"), true);
    filter.mark(&result("ac", "abcdef.zip"), true);
    filter.mark(&result("ad", "abcdefg.zip"), true);

    let marks = filter.marks();

    assert!(marks.is_spam(&result("ab", "setup.exe")), "by its hash");
    assert!(!marks.is_spam(&result("cd", "Setup.exe")));
    assert!(!marks.is_spam(&result("ce", "abcdef.zip")), "10 characters");
    assert!(marks.is_spam(&result("cf", "ABCDEFG.zip")), "11 characters");
}

#[test]
fn should_drop_the_markers_the_file_cannot_hold_so_saving_works() {
    let mut scenario = Scenario::new();
    let mut out = PacketWriter::new();
    out.u8(0x0F).u32(2);
    out.u8(BSOB | NEW_ED2K_FLAG).u8(0x09).u8(2).u8(1).u8(2);
    let marker = Tag::Hash { name: TagName::id(search_files::SPAM_HASH), value: hash("ab") };
    tag_codec::write_ed2k(&mut out, &marker, Ed2kTagFormat::New, StringEncoding::Utf8).unwrap();
    fs::write(scenario.spam_file(), out.into_byte_array()).unwrap();
    scenario.filter = scenario.load();
    scenario.filter.mark(&result("cd", "another fake file.avi"), true);

    scenario.filter.save(&scenario.spam_file()).unwrap();

    assert_eq!(scenario.saved_spam_hashes(), hashes(&["ab", "cd"]));
}

#[test]
fn should_set_a_damaged_file_aside_instead_of_overwriting_it() {
    let mut scenario = Scenario::new();
    fs::write(scenario.spam_file(), DAMAGED).unwrap();
    scenario.filter = scenario.load();
    assert!(scenario.filter.marks().spam.is_empty());
    scenario.filter.mark(&result("ab", "a fake movie.avi"), true);

    scenario.filter.save(&scenario.spam_file()).unwrap();
    scenario.filter.save(&scenario.spam_file()).unwrap();

    let aside = set_aside(scenario.dir.path());
    assert_eq!(aside.len(), 1);
    assert_eq!(fs::read(&aside[0]).unwrap(), DAMAGED);
    assert_eq!(scenario.saved_spam_hashes(), hashes(&["ab"]));
}

#[test]
fn should_rate_a_file_marked_as_spam_by_its_hash_whatever_its_name() {
    let rating = rating_after(
        file("ab", "Fake 2026.avi", 700 * MIB, Origin::default()),
        file("ab", "Another name.mkv", 300 * MIB, Origin::default()),
    );

    assert_eq!(rating, spam_filter::HASH_HIT);
}

#[test]
fn should_rate_another_file_of_the_same_long_name_as_spam() {
    let rating = rating_after(
        file("ab", "Fake 2026 FULL.avi", 700 * MIB, Origin::default()),
        file("cd", "fake 2026 full.AVI", 300 * MIB, Origin::default()),
    );

    assert_eq!(rating, spam_filter::FULL_NAME_HIT);
}

#[test]
fn should_rate_another_file_of_the_same_short_name_below_the_threshold() {
    let rating = rating_after(
        file("ab", "setup.exe", 700 * MIB, Origin::default()),
        file("cd", "Setup.exe", 300 * MIB, Origin::default()),
    );

    assert_eq!(rating, spam_filter::SHORT_FULL_NAME_HIT);
}

#[test]
fn should_rate_the_same_bare_name_as_spam() {
    let rating = rating_after(
        file("ab", "x.avi", 700 * MIB, bare("great movie hd rip 2026 avi")),
        file("cd", "y.avi", 300 * MIB, bare("great movie hd rip 2026 avi")),
    );

    assert_eq!(rating, spam_filter::BARE_NAME_HIT);
}

#[test]
fn should_rate_a_bare_name_a_few_edits_away_below_the_threshold() {
    let rating = rating_after(
        file("ab", "x.avi", 700 * MIB, bare("great movie hd rip 2026 avi")),
        file("cd", "y.avi", 300 * MIB, bare("great movie hd rip 2025 avi")),
    );

    assert_eq!(rating, spam_filter::NEAR_NAME_HIT);
}

#[test]
fn should_rate_a_size_within_five_percent_and_five_mib() {
    let rating = rating_after(
        file("ab", "Fake one.avi", 100 * MIB, Origin::default()),
        file("cd", "Other name.avi", 102 * MIB, Origin::default()),
    );

    assert_eq!(rating, spam_filter::SIZE_HIT);
}

#[test]
fn should_rate_a_result_only_a_server_that_gave_spam_gave() {
    let rating = rating_after(
        file("ab", "Fake one.avi", 700 * MIB, udp(&[SPAM_SERVER])),
        file("cd", "Other name.mkv", 300 * MIB, udp(&[SPAM_SERVER])),
    );

    assert_eq!(rating, spam_filter::ONLY_SERVER_HIT);
}

#[test]
fn should_rate_a_result_less_when_other_servers_gave_it_too() {
    let rating = rating_after(
        file("ab", "Fake one.avi", 700 * MIB, udp(&[SPAM_SERVER])),
        file("cd", "Other name.mkv", 300 * MIB, udp(&[SPAM_SERVER, OTHER_SERVER])),
    );

    assert_eq!(rating, spam_filter::ONE_SERVER_OF_SEVERAL_HIT);
}

#[test]
fn should_rate_a_result_a_source_of_spam_offers() {
    let rating = rating_after(
        file("ab", "Fake one.avi", 700 * MIB, source(SPAM_SOURCE)),
        file("cd", "Other name.mkv", 300 * MIB, source(SPAM_SOURCE)),
    );

    assert_eq!(rating, spam_filter::SOURCE_HIT);
}

#[test]
fn should_rate_a_result_only_servers_of_mostly_spam_gave() {
    let mut filter = SpamFilter::default();
    filter.answered(SPAM_SERVER, 10, 6);

    let rating = filter.rate(&file("cd", "Other name.mkv", 300 * MIB, udp(&[SPAM_SERVER])));

    assert_eq!(rating, spam_filter::ONLY_SPAM_SERVERS_HIT);
}

#[test]
fn should_rate_a_small_popular_program_only_spam_servers_gave_as_spam() {
    let mut filter = SpamFilter::default();
    filter.answered(SPAM_SERVER, 10, 6);

    let rating = filter.rate(&popular(file("cd", "Free player.exe", 2 * MIB, udp(&[SPAM_SERVER]))));

    assert_eq!(rating, spam_filter::ONLY_SPAM_SERVERS_HIT + spam_filter::POPULAR_SMALL_PROGRAM_HIT);
}

#[test]
fn should_not_rate_a_result_by_servers_never_heard_over_udp() {
    let filter = SpamFilter::default();

    let rating = filter.rate(&popular(file("cd", "Free player.exe", 2 * MIB, udp(&[SPAM_SERVER]))));

    assert_eq!(rating, 0);
}

#[test]
fn should_forget_what_would_rate_a_lookalike_once_a_file_is_cleared_again() {
    let mut filter = SpamFilter::default();
    filter.mark(
        &file("ab", "Great Movie.avi", 700 * MIB, origin(SPAM_SERVER, SPAM_SOURCE, "great movie hd rip 2026")),
        true,
    );
    filter.mark(
        &file("cd", "Great Movie.avi", 700 * MIB, origin(SPAM_SERVER, SPAM_SOURCE, "great movie hd rip 2026")),
        false,
    );

    let rating = filter.rate(&file(
        "ef",
        "Great Movie.avi",
        701 * MIB,
        origin(SPAM_SERVER, SPAM_SOURCE, "great movie hd rip 2025"),
    ));

    assert_eq!(rating, 0);
}

#[test]
fn should_keep_every_marker_and_the_servers_records_in_the_file() {
    let mut scenario = Scenario::of_the_file();
    scenario.filter.mark(
        &file("ab", "Great Movie.avi", 700 * MIB, origin(SPAM_SERVER, SPAM_SOURCE, "great movie hd rip 2026")),
        true,
    );
    scenario.filter.answered(OTHER_SERVER, 10, 9);
    scenario.saved_and_loaded_again();

    let rating = scenario.filter.rate(&popular(file("cd", "Other.exe", 2 * MIB, udp(&[OTHER_SERVER]))));

    assert_eq!(rating, spam_filter::ONLY_SPAM_SERVERS_HIT + spam_filter::POPULAR_SMALL_PROGRAM_HIT);
}

#[test]
fn should_read_back_the_learned_markers_of_a_file_saved() {
    let mut scenario = Scenario::of_the_file();
    scenario.filter.mark(
        &file("ab", "Great Movie.avi", 700 * MIB, origin(SPAM_SERVER, SPAM_SOURCE, "great movie hd rip 2026")),
        true,
    );
    scenario.saved_and_loaded_again();

    let rating = scenario.filter.rate(&file(
        "cd",
        "Other.avi",
        701 * MIB,
        origin(SPAM_SERVER, SPAM_SOURCE, "great movie hd rip 2026"),
    ));

    assert_eq!(
        rating,
        spam_filter::BARE_NAME_HIT + spam_filter::SIZE_HIT + spam_filter::ONLY_SERVER_HIT + spam_filter::SOURCE_HIT
    );
}
