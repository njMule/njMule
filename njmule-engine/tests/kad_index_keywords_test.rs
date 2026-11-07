// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadIndexKeywordsTest, under its names: the publishers of a keyword entry, the name it answers
//! with, and what no answer could carry.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::kad::kad_index::{self, KadIndex};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::kad::kad_messages::Entry;
use njmule_engine::protocol::tag::{Tag, TagName, file_tag, tags};
use njmule_engine::search::search_expr::SearchExpr;

const NOW: i64 = 1_000_000_000_000;
const HOUR: i64 = 3_600_000_000_000;
const SIZE: u64 = 700_000_000;
const IP: u32 = u32::from_be_bytes([81, 2, 3, 4]);

struct Keywords {
    index: KadIndex,
    keyword: UInt128,
    file: UInt128,
    found: Vec<Entry>,
    keywords: usize,
}

impl Keywords {
    fn new() -> Self {
        Self { index: KadIndex::new(), keyword: id(1), file: id(2), found: Vec::new(), keywords: 0 }
    }

    fn given_the_file_is_published_under(&mut self, keyword: UInt128, file: UInt128) {
        self.index.publish_keyword(keyword, &entry(file, "file.avi", Vec::new()), IP, NOW);
    }

    fn given_the_keyword_was_searched_for(&mut self, expression: &SearchExpr) {
        self.when_the_keyword_is_searched_for(expression, NOW);
        assert_eq!(self.found.len(), 1);
    }

    fn given_a_search_for_finds_the_name(&mut self, expression: &SearchExpr, at: i64, name: &str) {
        self.when_the_keyword_is_searched_for(expression, at);
        self.then_the_name_found_is(name);
    }

    fn given_the_file_is_published_by_addresses(&mut self, first_ip: u32, count: u32, name: &str, at: i64) {
        for offset in 0..count {
            self.index.publish_keyword(self.keyword, &entry(self.file, name, Vec::new()), first_ip + offset, at);
        }
    }

    fn given_the_file_is_published_with_tags(&mut self, tags: Vec<Tag>) {
        self.index.publish_keyword(self.keyword, &entry(self.file, "big.avi", tags), IP, NOW);
    }

    fn given_the_index_is_cleaned_at(&mut self, at: i64) {
        self.index.clean(at);
    }

    fn when_the_keywords_are_counted(&mut self) {
        self.keywords = self.index.keyword_count();
    }

    fn when_the_keyword_is_searched_for(&mut self, expression: &SearchExpr, at: i64) {
        self.found = self.index.search_keyword(self.keyword, Some(expression), 0, at);
    }

    fn when_the_keyword_is_searched_at(&mut self, at: i64) {
        self.found = self.index.search_keyword(self.keyword, None, 0, at);
    }

    fn then_the_keywords_counted_are(&self, expected: usize) {
        assert_eq!(self.keywords, expected);
    }

    fn then_the_name_found_is(&self, name: &str) {
        assert_eq!(self.found.len(), 1);
        assert_eq!(tags::string_value(&self.found[0].tags, file_tag::FILENAME), Some(name));
    }

    fn then_the_publishers_counted_are(&self, publishers: u64) {
        let info = tags::int_value(&self.found[0].tags, file_tag::PUBLISHINFO).unwrap();
        assert_eq!(info >> 16 & 0xFF, publishers);
    }

    fn then_nothing_is_found(&self) {
        assert!(self.found.is_empty());
    }

    fn then_the_index_holds_keyword_entries(&self, entries: usize) {
        assert_eq!(self.index.keyword_entries(), entries);
    }
}

fn entry(id: UInt128, name: &str, more: Vec<Tag>) -> Entry {
    let mut tags = vec![
        Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() },
        Tag::Int { name: TagName::id(file_tag::FILESIZE), value: SIZE },
    ];
    tags.extend(more);
    Entry { id, tags }
}

fn id(n: u64) -> UInt128 {
    UInt128(u128::from(n) << 64 | u128::from(n))
}

#[test]
fn should_forget_publishers_silent_for_a_day_so_that_new_ones_count() {
    let mut keywords = Keywords::new();
    keywords.given_the_file_is_published_by_addresses(IP, 99, "Stale.avi", NOW);
    keywords.given_the_file_is_published_by_addresses(IP + 1_000, 1, "Kept.avi", NOW);
    keywords.given_the_file_is_published_by_addresses(IP + 1_000, 1, "Kept.avi", NOW + 12 * HOUR);
    keywords.given_the_file_is_published_by_addresses(IP + 2_000, 1, "Kept.avi", NOW + 24 * HOUR + 1);

    keywords.when_the_keyword_is_searched_at(NOW + 24 * HOUR + 2);

    keywords.then_the_name_found_is("Kept.avi");
    keywords.then_the_publishers_counted_are(2);
}

#[test]
fn should_forget_silent_publishers_when_the_index_is_cleaned() {
    let mut keywords = Keywords::new();
    keywords.given_the_file_is_published_by_addresses(IP, 50, "Stale.avi", NOW);
    keywords.given_the_file_is_published_by_addresses(IP + 1_000, 1, "Kept.avi", NOW + 12 * HOUR);
    keywords.given_the_index_is_cleaned_at(NOW + 24 * HOUR);

    keywords.when_the_keyword_is_searched_at(NOW + 24 * HOUR);

    keywords.then_the_name_found_is("Kept.avi");
    keywords.then_the_publishers_counted_are(1);
}

#[test]
fn should_keep_of_a_name_no_more_than_a_file_system_takes() {
    let mut keywords = Keywords::new();
    keywords.given_the_file_is_published_by_addresses(IP, 1, &("x".repeat(10_000) + ".avi"), NOW);

    keywords.when_the_keyword_is_searched_at(NOW);

    keywords.then_the_name_found_is(&"x".repeat(kad_index::MAX_KEYWORD_NAME));
}

#[test]
fn should_not_keep_a_file_that_no_answer_could_carry() {
    let mut keywords = Keywords::new();
    let artist = Tag::String { name: TagName::id(file_tag::MEDIA_ARTIST), value: "a".repeat(2_000) };
    keywords.given_the_file_is_published_with_tags(vec![artist]);

    keywords.when_the_keyword_is_searched_at(NOW);

    keywords.then_nothing_is_found();
    keywords.then_the_index_holds_keyword_entries(0);
}

#[test]
fn should_answer_with_the_name_most_publishers_give_while_they_change() {
    let mut keywords = Keywords::new();
    keywords.given_the_file_is_published_by_addresses(IP, 1, "First Name.avi", NOW);
    keywords.given_the_keyword_was_searched_for(&SearchExpr::keyword("first"));
    keywords.given_the_file_is_published_by_addresses(IP + 10, 2, "Second Name.avi", NOW + HOUR);
    keywords.given_a_search_for_finds_the_name(&SearchExpr::keyword("second"), NOW + HOUR, "Second Name.avi");
    keywords.given_the_file_is_published_by_addresses(IP, 1, "First Name.avi", NOW + 23 * HOUR);

    keywords.when_the_keyword_is_searched_for(&SearchExpr::keyword("FIRST name"), NOW + 25 * HOUR);

    keywords.then_the_name_found_is("First Name.avi");
}

#[test]
fn should_count_the_keywords_files_are_stored_under() {
    let mut keywords = Keywords::new();
    keywords.given_the_file_is_published_under(id(1), id(2));
    keywords.given_the_file_is_published_under(id(1), id(3));
    keywords.given_the_file_is_published_under(id(4), id(2));

    keywords.when_the_keywords_are_counted();

    keywords.then_the_keywords_counted_are(2);
}
