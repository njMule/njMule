// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadIndexTest, under its names: files published under keywords and sources of files, with their
//! limits and lifetimes, and the searches that find them.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::kad::kad_index::{self, KadIndex};
use njmule_engine::kad::keywords;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::kad::kad_messages::Entry;
use njmule_engine::protocol::tag::{Tag, TagName, file_tag, tags};
use njmule_engine::search::search_expr::{Comparison, SearchExpr};
use rand::SeedableRng;
use rand::rngs::StdRng;

const NOW: i64 = 1_000_000_000_000;
const KEYWORD_END: i64 = NOW + 24 * 3600 * 1_000_000_000;
const SOURCE_END: i64 = NOW + 5 * 3600 * 1_000_000_000;
const IP: u32 = u32::from_be_bytes([81, 2, 3, 4]);

struct Index {
    random: StdRng,
    index: KadIndex,
    keyword: UInt128,
    other_keyword: UInt128,
    file: UInt128,
    source: UInt128,
    first: UInt128,
    found: Vec<Entry>,
    load: u8,
    source_load: Option<u8>,
}

impl Index {
    fn new() -> Self {
        let mut random = StdRng::from_rng(&mut rand::rng());
        Self {
            keyword: UInt128::random(&mut random),
            other_keyword: UInt128::random(&mut random),
            file: UInt128::random(&mut random),
            source: UInt128::random(&mut random),
            random,
            index: KadIndex::new(),
            first: UInt128::ZERO,
            found: Vec::new(),
            load: 0,
            source_load: None,
        }
    }

    fn given_the_file_is_published(&mut self, name: &str, size: u64, ip: u32) {
        self.index.publish_keyword(self.keyword, &entry(self.file, name, size), ip, NOW);
    }

    fn given_the_file_is_published_with_the_load(&mut self, name: &str, size: u64, ip: u32, load: u8) {
        assert_eq!(self.index.publish_keyword(self.keyword, &entry(self.file, name, size), ip, NOW), load);
    }

    fn given_the_file_is_published_with_its_size_only(&mut self, size: u64) {
        let only_size = Entry { id: self.file, tags: vec![int(file_tag::FILESIZE, size)] };
        self.index.publish_keyword(self.keyword, &only_size, IP, NOW);
    }

    fn given_another_file_is_published(&mut self, name: &str, size: u64) {
        let other = UInt128::random(&mut self.random);
        self.index.publish_keyword(self.keyword, &entry(other, name, size), IP, NOW);
    }

    fn given_five_linux_distros_are_published(&mut self) {
        for at in 0..5u64 {
            let tags = vec![
                string(file_tag::FILENAME, &format!("linux distro {at}.iso")),
                int(file_tag::FILESIZE, 1_000 * (at + 1)),
                string(file_tag::MEDIA_ARTIST, if at % 2 == 0 { "Even" } else { "Odd" }),
                int(file_tag::MEDIA_BITRATE, 128 * (at + 1)),
                int(file_tag::PUBLISHINFO, 7),
            ];
            let id = UInt128::random(&mut self.random);
            self.index.publish_keyword(self.keyword, &Entry { id, tags }, IP, NOW);
        }
    }

    fn given_the_file_is_published_again_and_again(&mut self, name: &str, ip: u32, times: u32) {
        self.publish_the_file_again_and_again(name, ip, times);
    }

    fn given_the_name_of_the_file_is_switched_back_and_forth(&mut self, name: &str, other: &str, ip: u32, times: u32) {
        self.switch_the_name_of_the_file_back_and_forth(name, other, ip, times);
    }

    fn given_files_fill_the_keyword(&mut self, count: usize) {
        self.first = UInt128::random(&mut self.random);
        for at in 0..count {
            let id = if at == 0 { self.first } else { UInt128::random(&mut self.random) };
            self.index.publish_keyword(self.keyword, &entry(id, &format!("file {at}"), 10), IP, NOW);
        }
    }

    fn given_a_new_file_is_refused(&mut self, name: &str) {
        let id = UInt128::random(&mut self.random);
        assert_eq!(self.index.publish_keyword(self.keyword, &entry(id, name, 10), IP, NOW), 100);
        assert_eq!(self.index.keyword_entries(), kad_index::MAX_FILES_PER_KEYWORD);
    }

    fn given_files_fill_the_other_keyword(&mut self, count: usize) {
        for at in 0..count {
            let id = UInt128::random(&mut self.random);
            self.index.publish_keyword(self.other_keyword, &entry(id, &format!("other {at}"), 10), IP, NOW);
        }
    }

    fn given_the_index_is_cleaned_at(&mut self, at: i64) {
        self.index.clean(at);
    }

    fn given_a_source_claiming_another_address_is_published_from(&mut self, ip: u32, udp_port: u16) {
        self.publish_a_source_claiming_another_address(ip, udp_port);
    }

    fn given_the_source_is_published_from(&mut self, ip: u32) {
        self.index.publish_source(self.file, self.source, &source_tags(4662), ip, 4672, NOW);
    }

    fn given_a_new_source_is_published_from(&mut self, ip: u32) {
        let source = UInt128::random(&mut self.random);
        self.index.publish_source(self.file, source, &source_tags(4662), ip, 4672, NOW);
    }

    fn given_new_sources_are_published_from_the_addresses_after(&mut self, ip: u32, count: u32) {
        self.publish_new_sources_from_the_addresses_after(ip, count);
    }

    fn given_no_source_is_found_at(&self, at: i64) {
        assert!(self.index.search_sources(self.file, 0, 0, at).is_empty());
    }

    fn when_the_file_is_published(&mut self, name: &str, size: u64, ip: u32) {
        self.index.publish_keyword(self.keyword, &entry(self.file, name, size), ip, NOW);
    }

    fn when_the_file_is_published_again_and_again(&mut self, name: &str, ip: u32, times: u32) {
        self.publish_the_file_again_and_again(name, ip, times);
    }

    fn when_the_name_of_the_file_is_switched_back_and_forth(&mut self, name: &str, other: &str, ip: u32, times: u32) {
        self.switch_the_name_of_the_file_back_and_forth(name, other, ip, times);
    }

    fn when_the_file_is_published_under_distinct_names_from_the_addresses_from(&mut self, ip: u32, count: u32) {
        for at in 0..count {
            let named = entry(self.file, &format!("name {at}.avi"), 10);
            self.index.publish_keyword(self.keyword, &named, ip + at, NOW);
        }
    }

    fn when_files_are_published_under_the_keyword(&mut self, count: u64) {
        for at in 0..count {
            let id = UInt128::random(&mut self.random);
            self.load = self.index.publish_keyword(self.keyword, &entry(id, &format!("file {at}"), at + 1), IP, NOW);
        }
    }

    fn when_a_new_file_is_published(&mut self, name: &str) {
        let id = UInt128::random(&mut self.random);
        self.load = self.index.publish_keyword(self.keyword, &entry(id, name, 10), IP, NOW);
    }

    fn when_a_new_file_is_published_under_the_other_keyword(&mut self, name: &str) {
        let id = UInt128::random(&mut self.random);
        self.load = self.index.publish_keyword(self.other_keyword, &entry(id, name, 10), IP, NOW);
    }

    fn when_the_first_file_is_published_again(&mut self) {
        self.index.publish_keyword(self.keyword, &entry(self.first, "file 0", 10), IP, NOW + 1);
    }

    fn when_the_keyword_is_searched(&mut self) {
        self.found = self.index.search_keyword(self.keyword, None, 0, NOW);
    }

    fn when_the_keyword_is_searched_at(&mut self, at: i64) {
        self.found = self.index.search_keyword(self.keyword, None, 0, at);
    }

    fn when_the_keyword_is_searched_skipping(&mut self, start: usize) {
        self.found = self.index.search_keyword(self.keyword, None, start, NOW);
    }

    fn when_the_keyword_is_searched_for(&mut self, expression: &SearchExpr) {
        self.found = self.index.search_keyword(self.keyword, Some(expression), 0, NOW);
    }

    fn when_the_keyword_is_searched_for_the_term(&mut self, term: &str) {
        self.when_the_keyword_is_searched_for(&SearchExpr::keyword(term));
    }

    fn when_another_keyword_is_searched(&mut self) {
        let other = UInt128::random(&mut self.random);
        self.found = self.index.search_keyword(other, None, 0, NOW);
    }

    fn when_the_index_is_cleaned_at(&mut self, at: i64) {
        self.index.clean(at);
    }

    fn when_a_source_claiming_another_address_is_published_from(&mut self, ip: u32, udp_port: u16) {
        self.publish_a_source_claiming_another_address(ip, udp_port);
    }

    fn when_a_source_is_published_with_the_tags_from(&mut self, tags: &[Tag], ip: u32) {
        self.source_load = self.index.publish_source(self.file, self.source, tags, ip, 4672, NOW);
    }

    fn when_the_source_is_published_from(&mut self, ip: u32) {
        self.index.publish_source(self.file, self.source, &source_tags(4662), ip, 4672, NOW);
    }

    fn when_a_new_source_is_published_from(&mut self, ip: u32) {
        self.given_a_new_source_is_published_from(ip);
    }

    fn when_new_sources_are_published_from_the_addresses_after(&mut self, ip: u32, count: u32) {
        self.publish_new_sources_from_the_addresses_after(ip, count);
    }

    fn when_the_sources_are_searched(&mut self, size: u64, start: usize) {
        self.found = self.index.search_sources(self.file, size, start, NOW);
    }

    fn when_the_sources_are_searched_at(&mut self, at: i64) {
        self.found = self.index.search_sources(self.file, 0, 0, at);
    }

    fn when_the_sources_of_another_file_are_searched(&mut self) {
        let other = UInt128::random(&mut self.random);
        self.found = self.index.search_sources(other, 0, 0, NOW);
    }

    fn then_the_entries_found_are(&self, count: usize, why: &str) {
        assert_eq!(self.found.len(), count, "{why}");
    }

    fn then_nothing_is_found(&self, why: &str) {
        assert!(self.found.is_empty(), "{why}");
    }

    fn then_the_name_found_is(&self, name: &str) {
        assert_eq!(tags::string_value(&self.found[0].tags, file_tag::FILENAME), Some(name));
    }

    fn then_the_size_found_is(&self, size: u64) {
        assert_eq!(tags::int_value(&self.found[0].tags, file_tag::FILESIZE), Some(size));
    }

    fn then_the_distinct_names_are(&self, names: u64) {
        assert_eq!(publish_info(&self.found) >> 24, names);
    }

    fn then_the_publishers_are(&self, publishers: u64) {
        assert_eq!(publish_info(&self.found) >> 16 & 0xFF, publishers);
    }

    // What only answers carry is not stored.
    fn then_the_first_entry_found_carries_one_publish_info(&self) {
        let infos = self.found[0].tags.iter().filter(|tag| *tag.name() == TagName::id(file_tag::PUBLISHINFO)).count();
        assert_eq!(infos, 1);
    }

    fn then_the_index_holds_keyword_entries(&self, entries: usize, why: &str) {
        assert_eq!(self.index.keyword_entries(), entries, "{why}");
    }

    fn then_the_index_holds_source_entries(&self, entries: usize, why: &str) {
        assert_eq!(self.index.source_entries(), entries, "{why}");
    }

    fn then_the_load_is(&self, expected: u8) {
        assert_eq!(self.load, expected);
    }

    fn then_a_search_finds(&mut self, count: usize) {
        assert_eq!(self.index.search_keyword(self.keyword, None, 0, NOW).len(), count);
    }

    fn then_a_search_finds_sources(&self, count: usize) {
        assert_eq!(self.index.search_sources(self.file, 0, 0, NOW).len(), count);
    }

    fn then_a_search_finds_the_only_source_from(&self, ip: u32) {
        let found = self.index.search_sources(self.file, 0, 0, NOW);
        assert_eq!(found.len(), 1);
        assert_eq!(tags::int_value(&found[0].tags, file_tag::SOURCEIP), Some(u64::from(ip)));
    }

    fn then_the_source_load_is_reported(&self) {
        assert!(self.source_load.is_some());
    }

    fn then_the_source_is_refused(&self, why: &str) {
        assert_eq!(self.source_load, None, "{why}");
    }

    fn then_the_source_found_comes_from(&self, ip: u32) {
        assert_eq!(tags::int_value(&self.found[0].tags, file_tag::SOURCEIP), Some(u64::from(ip)));
    }

    fn then_the_source_found_has_the_udp_port(&self, port: u64) {
        assert_eq!(tags::int_value(&self.found[0].tags, file_tag::SOURCEUPORT), Some(port));
    }

    fn then_the_name_of_the_file_is(&mut self, name: &str, why: &str) {
        assert_eq!(self.name_of_the_file(), name, "{why}");
    }

    fn then_at_most_distinct_names_are_kept(&mut self, names: u64) {
        let info = publish_info(&self.index.search_keyword(self.keyword, None, 0, NOW));
        assert!(info >> 24 <= names, "distinct names kept");
    }

    fn publish_the_file_again_and_again(&mut self, name: &str, ip: u32, times: u32) {
        for _ in 0..times {
            self.index.publish_keyword(self.keyword, &entry(self.file, name, 10), ip, NOW);
        }
    }

    // Switching names back and forth does not add up either.
    fn switch_the_name_of_the_file_back_and_forth(&mut self, name: &str, other: &str, ip: u32, times: u32) {
        for _ in 0..times {
            self.index.publish_keyword(self.keyword, &entry(self.file, name, 10), ip, NOW);
            self.index.publish_keyword(self.keyword, &entry(self.file, other, 10), ip, NOW);
        }
    }

    fn publish_a_source_claiming_another_address(&mut self, ip: u32, udp_port: u16) {
        let tags = [
            int(file_tag::SOURCETYPE, 1),
            int(file_tag::SOURCEIP, u64::from(u32::from_be_bytes([1, 1, 1, 1]))),
            int(file_tag::SOURCEPORT, 4662),
            int(file_tag::FILESIZE, 500),
        ];
        self.source_load = self.index.publish_source(self.file, self.source, &tags, ip, udp_port, NOW);
    }

    fn publish_new_sources_from_the_addresses_after(&mut self, ip: u32, count: u32) {
        for at in 1..=count {
            let source = UInt128::random(&mut self.random);
            self.index.publish_source(self.file, source, &source_tags(4662), ip + at, 4672, NOW);
        }
    }

    fn name_of_the_file(&mut self) -> String {
        let found = self.index.search_keyword(self.keyword, None, 0, NOW);
        let entry = found.iter().find(|entry| entry.id == self.file).unwrap();
        tags::string_value(&entry.tags, file_tag::FILENAME).unwrap().to_owned()
    }
}

fn publish_info(entries: &[Entry]) -> u64 {
    tags::int_value(&entries[0].tags, file_tag::PUBLISHINFO).unwrap()
}

fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

fn string(id: u8, value: &str) -> Tag {
    Tag::String { name: TagName::id(id), value: value.to_owned() }
}

fn entry(id: UInt128, name: &str, size: u64) -> Entry {
    Entry { id, tags: vec![string(file_tag::FILENAME, name), int(file_tag::FILESIZE, size)] }
}

fn source_tags(tcp_port: u64) -> Vec<Tag> {
    vec![int(file_tag::SOURCETYPE, 1), int(file_tag::SOURCEPORT, tcp_port)]
}

fn size(comparison: Comparison, value: u64) -> SearchExpr {
    SearchExpr::NumericAttr { tag: file_tag::FILESIZE, comparison, value }
}

#[test]
fn should_find_a_file_under_a_keyword_with_its_most_popular_name() {
    let mut index = Index::new();
    index.given_the_file_is_published_with_the_load("Some Movie.avi", 700, IP, 1);
    index.given_the_file_is_published("Other name.avi", 700, IP + 1);
    index.given_the_file_is_published("Other name.avi", 700, IP + 2);

    index.when_the_keyword_is_searched();

    index.then_the_entries_found_are(1, "one file");
    index.then_the_name_found_is("Other name.avi");
    index.then_the_size_found_is(700);
    index.then_the_distinct_names_are(2);
    index.then_the_publishers_are(3);
    index.then_the_index_holds_keyword_entries(1, "one entry");
}

#[test]
fn should_not_keep_entries_without_name_or_size() {
    let mut index = Index::new();
    index.given_the_file_is_published_with_its_size_only(5);
    index.given_the_file_is_published("", 5, IP);
    index.given_the_file_is_published("name", 0, IP);

    index.when_the_keyword_is_searched();

    index.then_nothing_is_found("no name or no size");
    index.then_the_index_holds_keyword_entries(0, "none kept");
}

#[test]
fn should_find_nothing_under_another_keyword() {
    let mut index = Index::new();
    index.given_the_file_is_published_with_its_size_only(5);
    index.given_the_file_is_published("", 5, IP);
    index.given_the_file_is_published("name", 0, IP);

    index.when_another_keyword_is_searched();

    index.then_nothing_is_found("another keyword");
}

#[test]
fn should_treat_a_file_of_another_size_as_another_entry() {
    let mut index = Index::new();
    index.given_the_file_is_published("a.avi", 10, IP);
    index.given_the_file_is_published("a.avi", 20, IP);

    index.when_the_keyword_is_searched();

    index.then_the_entries_found_are(1, "the entry replaced");
    index.then_the_size_found_is(20);
}

#[test]
fn should_filter_searches_by_expression() {
    let bitrate = SearchExpr::NumericAttr { tag: file_tag::MEDIA_BITRATE, comparison: Comparison::Greater, value: 300 };
    let even = SearchExpr::StringAttr { tag: file_tag::MEDIA_ARTIST, value: "Even".to_owned() };
    let string_attr = |tag: u8, value: &str| SearchExpr::StringAttr { tag, value: value.to_owned() };
    let cases = [
        (SearchExpr::keyword("Linux ISO"), 5),
        (SearchExpr::keyword("windows"), 0),
        (string_attr(file_tag::FILEFORMAT, "ISO"), 5),
        (string_attr(file_tag::FILEFORMAT, "avi"), 0),
        (string_attr(file_tag::MEDIA_ARTIST, "even"), 3),
        (string_attr(file_tag::DESCRIPTION, "x"), 0),
        (size(Comparison::Equal, 2_000), 1),
        (size(Comparison::Greater, 2_000), 3),
        (size(Comparison::Less, 2_000), 1),
        (size(Comparison::GreaterEqual, 2_000), 4),
        (size(Comparison::LessEqual, 2_000), 2),
        (size(Comparison::NotEqual, 2_000), 4),
        (bitrate.clone(), 3),
        (SearchExpr::NumericAttr { tag: file_tag::MEDIA_LENGTH, comparison: Comparison::Greater, value: 0 }, 0),
        (SearchExpr::and(even.clone(), bitrate.clone()), 2),
        (SearchExpr::or(even.clone(), bitrate), 4),
        (SearchExpr::not(SearchExpr::keyword("linux"), even), 2),
    ];
    for (expression, count) in cases {
        let mut index = Index::new();
        index.given_five_linux_distros_are_published();

        index.when_the_keyword_is_searched_for(&expression);

        index.then_the_entries_found_are(count, &format!("{expression:?}"));
    }
}

#[test]
fn should_skip_the_first_results() {
    let mut index = Index::new();
    index.given_five_linux_distros_are_published();

    index.when_the_keyword_is_searched_skipping(3);

    index.then_the_entries_found_are(2, "the first three skipped");
}

#[test]
fn should_not_store_what_only_answers_carry() {
    let mut index = Index::new();
    index.given_five_linux_distros_are_published();

    index.when_the_keyword_is_searched();

    index.then_the_first_entry_found_carries_one_publish_info();
}

#[test]
fn should_not_find_a_keyword_entry_past_its_lifetime() {
    let mut index = Index::new();
    index.given_another_file_is_published("a.avi", 10);

    index.when_the_keyword_is_searched_at(KEYWORD_END);

    index.then_nothing_is_found("past its lifetime");
}

#[test]
fn should_keep_a_keyword_entry_until_its_lifetime_ends() {
    let mut index = Index::new();
    index.given_another_file_is_published("a.avi", 10);

    index.when_the_index_is_cleaned_at(KEYWORD_END - 1);

    index.then_the_index_holds_keyword_entries(1, "still alive");
}

#[test]
fn should_drop_a_keyword_entry_when_its_lifetime_ends() {
    let mut index = Index::new();
    index.given_another_file_is_published("a.avi", 10);
    index.given_the_index_is_cleaned_at(KEYWORD_END - 1);

    index.when_the_index_is_cleaned_at(KEYWORD_END);

    index.then_the_index_holds_keyword_entries(0, "expired");
}

#[test]
fn should_take_a_source_publication() {
    let mut index = Index::new();

    index.when_a_source_claiming_another_address_is_published_from(IP, 4672);

    index.then_the_source_load_is_reported();
}

#[test]
fn should_store_a_source_with_the_address_it_came_from() {
    let mut index = Index::new();
    index.given_a_source_claiming_another_address_is_published_from(IP, 4672);

    index.when_the_sources_are_searched(500, 0);

    index.then_the_entries_found_are(1, "the source");
    index.then_the_source_found_comes_from(IP);
    index.then_the_source_found_has_the_udp_port(4672);
}

#[test]
fn should_find_the_sources_of_the_size_asked_for() {
    for (size, start, count) in [(0, 0, 1), (501, 0, 0), (500, 1, 0)] {
        let mut index = Index::new();
        index.given_a_source_claiming_another_address_is_published_from(IP, 4672);

        index.when_the_sources_are_searched(size, start);

        index.then_the_entries_found_are(count, &format!("size {size}, from {start}"));
    }
}

#[test]
fn should_find_no_source_of_another_file() {
    let mut index = Index::new();
    index.given_a_source_claiming_another_address_is_published_from(IP, 4672);

    index.when_the_sources_of_another_file_are_searched();

    index.then_nothing_is_found("another file");
}

// A port of -1 is u64::MAX here: out of range all the same.
#[test]
fn should_refuse_unusable_sources() {
    let mut bad_udp = source_tags(4662);
    bad_udp.push(int(file_tag::SOURCEUPORT, u64::MAX));
    let cases = [
        (vec![int(file_tag::SOURCEPORT, 4662)], IP, "no source type"),
        (vec![int(file_tag::SOURCETYPE, 1)], IP, "no TCP port"),
        (source_tags(4662), 0, "no address"),
        (source_tags(0x1_0000), IP, "TCP port out of range"),
        (bad_udp, IP, "UDP port out of range"),
    ];
    for (tags, ip, why) in cases {
        let mut index = Index::new();

        index.when_a_source_is_published_with_the_tags_from(&tags, ip);

        index.then_the_source_is_refused(why);
        index.then_the_index_holds_source_entries(0, why);
    }
}

#[test]
fn should_replace_a_source_published_again_from_its_address() {
    let mut index = Index::new();
    index.given_a_new_source_is_published_from(IP);

    index.when_a_new_source_is_published_from(IP);

    index.then_the_index_holds_source_entries(1, "replaced");
}

#[test]
fn should_make_room_for_new_sources_with_the_oldest() {
    let mut index = Index::new();
    index.given_a_new_source_is_published_from(IP);
    index.given_a_new_source_is_published_from(IP);

    index.when_new_sources_are_published_from_the_addresses_after(IP, kad_index::MAX_SOURCES_PER_FILE as u32 + 4);

    index.then_the_index_holds_source_entries(kad_index::MAX_SOURCES_PER_FILE, "the most per file");
    index.then_a_search_finds_sources(kad_index::MAX_RESULTS);
}

#[test]
fn should_drop_sources_when_their_lifetime_ends() {
    let mut index = Index::new();
    index.given_a_new_source_is_published_from(IP);
    index.given_a_new_source_is_published_from(IP);
    index.given_new_sources_are_published_from_the_addresses_after(IP, kad_index::MAX_SOURCES_PER_FILE as u32 + 4);
    index.given_no_source_is_found_at(SOURCE_END);

    index.when_the_index_is_cleaned_at(SOURCE_END);

    index.then_the_index_holds_source_entries(0, "expired");
}

#[test]
fn should_report_the_load_of_a_full_keyword() {
    let mut index = Index::new();

    index.when_files_are_published_under_the_keyword(600);

    index.then_the_load_is((600 * 100 / kad_index::MAX_FILES_PER_KEYWORD) as u8);
    index.then_a_search_finds(kad_index::MAX_RESULTS);
}

#[test]
fn should_count_a_file_republished_with_another_size_once() {
    let mut index = Index::new();
    index.given_the_file_is_published("a.avi", 10, IP);

    index.when_the_file_is_published("a.avi", 20, IP);

    index.then_the_index_holds_keyword_entries(1, "counted once");
}

#[test]
fn should_drop_a_file_republished_with_another_size_only_once() {
    let mut index = Index::new();
    index.given_the_file_is_published("a.avi", 10, IP);
    index.given_the_file_is_published("a.avi", 20, IP);

    index.when_the_index_is_cleaned_at(KEYWORD_END);

    index.then_the_index_holds_keyword_entries(0, "dropped once");
}

#[test]
fn should_count_a_source_republished_from_another_address_once() {
    let mut index = Index::new();
    index.given_the_source_is_published_from(IP);

    index.when_the_source_is_published_from(IP + 1);

    index.then_the_index_holds_source_entries(1, "counted once");
    index.then_a_search_finds_the_only_source_from(IP + 1);
}

#[test]
fn should_drop_a_source_republished_from_another_address_only_once() {
    let mut index = Index::new();
    index.given_the_source_is_published_from(IP);
    index.given_the_source_is_published_from(IP + 1);

    index.when_the_index_is_cleaned_at(SOURCE_END);

    index.then_the_index_holds_source_entries(0, "dropped once");
}

#[test]
fn should_match_nothing_with_a_term_without_any_word() {
    for term in ["-._", "\"\""] {
        let mut index = Index::new();
        index.given_another_file_is_published("linux.iso", 10);

        index.when_the_keyword_is_searched_for_the_term(term);

        index.then_nothing_is_found(term);
    }
}

#[test]
fn should_split_a_term_at_each_character_that_splits_file_names() {
    for delimiter in keywords::DELIMITERS.chars() {
        let mut index = Index::new();
        index.given_another_file_is_published("linux.iso", 10);

        index.when_the_keyword_is_searched_for_the_term(&format!("lin{delimiter}iso"));

        index.then_the_entries_found_are(1, &delimiter.to_string());
    }
}

#[test]
fn should_keep_a_term_whole_at_any_other_character() {
    let mut index = Index::new();
    index.given_another_file_is_published("linux.iso", 10);

    index.when_the_keyword_is_searched_for_the_term("lin+iso");

    index.then_nothing_is_found("a + does not split");
}

#[test]
fn should_match_the_word_of_a_term_within_punctuation() {
    let mut index = Index::new();
    index.given_another_file_is_published("linux.iso", 10);

    index.when_the_keyword_is_searched_for_the_term("(linux)");

    index.then_the_entries_found_are(1, "the word within brackets");
}

#[test]
fn should_count_a_publisher_once_for_its_name() {
    let mut index = Index::new();
    index.given_the_file_is_published("Popular.avi", 10, IP);
    index.given_the_file_is_published("Popular.avi", 10, IP + 1);

    index.when_the_file_is_published_again_and_again("Pushed.avi", IP + 2, 5);

    index.then_the_name_of_the_file_is("Popular.avi", "one address publishing again");
}

#[test]
fn should_not_add_up_names_switched_back_and_forth() {
    let mut index = Index::new();
    index.given_the_file_is_published("Popular.avi", 10, IP);
    index.given_the_file_is_published("Popular.avi", 10, IP + 1);
    index.given_the_file_is_published_again_and_again("Pushed.avi", IP + 2, 5);

    index.when_the_name_of_the_file_is_switched_back_and_forth("Pushed.avi", "Other.avi", IP + 3, 5);

    index.then_the_name_of_the_file_is("Popular.avi", "switching names gains nothing");
}

#[test]
fn should_keep_few_names_of_a_file() {
    let mut index = Index::new();
    index.given_the_file_is_published("Popular.avi", 10, IP);
    index.given_the_file_is_published("Popular.avi", 10, IP + 1);
    index.given_the_file_is_published_again_and_again("Pushed.avi", IP + 2, 5);
    index.given_the_name_of_the_file_is_switched_back_and_forth("Pushed.avi", "Other.avi", IP + 3, 5);

    index.when_the_file_is_published_under_distinct_names_from_the_addresses_from(IP + 10, 1_000);

    index.then_at_most_distinct_names_are_kept(255);
    index.then_the_name_of_the_file_is("Popular.avi", "the most popular name stays");
}

#[test]
fn should_find_a_source_until_the_last_instant_of_its_lifetime() {
    let mut index = Index::new();
    index.given_a_new_source_is_published_from(IP);

    index.when_the_sources_are_searched_at(SOURCE_END - 1);

    index.then_the_entries_found_are(1, "the last instant");
}

#[test]
fn should_not_find_a_source_at_the_exact_end_of_its_lifetime() {
    let mut index = Index::new();
    index.given_a_new_source_is_published_from(IP);

    index.when_the_sources_are_searched_at(SOURCE_END);

    index.then_nothing_is_found("the exact end");
}

#[test]
fn should_refuse_a_new_file_under_a_full_keyword() {
    let mut index = Index::new();
    index.given_files_fill_the_keyword(kad_index::MAX_FILES_PER_KEYWORD);

    index.when_a_new_file_is_published("more");

    index.then_the_load_is(100);
    index.then_the_index_holds_keyword_entries(kad_index::MAX_FILES_PER_KEYWORD, "full");
}

#[test]
fn should_take_a_known_file_again_under_a_full_keyword() {
    let mut index = Index::new();
    index.given_files_fill_the_keyword(kad_index::MAX_FILES_PER_KEYWORD);
    index.given_a_new_file_is_refused("more");

    index.when_the_first_file_is_published_again();

    index.then_the_index_holds_keyword_entries(kad_index::MAX_FILES_PER_KEYWORD, "published again");
}

#[test]
fn should_refuse_a_new_file_when_the_index_is_full() {
    let mut index = Index::new();
    index.given_files_fill_the_keyword(kad_index::MAX_FILES_PER_KEYWORD);
    index.given_files_fill_the_other_keyword(kad_index::MAX_KEYWORD_ENTRIES - kad_index::MAX_FILES_PER_KEYWORD);

    index.when_a_new_file_is_published_under_the_other_keyword("x");

    index.then_the_load_is(100);
    index.then_the_index_holds_keyword_entries(kad_index::MAX_KEYWORD_ENTRIES, "the index is full");
}

#[test]
fn should_find_nothing_when_skipping_beyond_the_results() {
    let mut index = Index::new();
    index.given_another_file_is_published("über alles.avi", 10);

    index.when_the_keyword_is_searched_skipping(5);

    index.then_nothing_is_found("beyond the results");
}

#[test]
fn should_match_in_the_network_lower_case() {
    for term in ["ÜBER", "\"über alles\""] {
        let mut index = Index::new();
        index.given_another_file_is_published("über alles.avi", 10);

        index.when_the_keyword_is_searched_for_the_term(term);

        index.then_the_entries_found_are(1, term);
    }
}
