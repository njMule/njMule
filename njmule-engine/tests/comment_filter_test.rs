// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's CommentFilterTest, under its names.

use njmule_engine::config::preferences::DEFAULT_COMMENT_FILTER;
use njmule_engine::files::comment_filter::CommentFilter;

struct Filter {
    filter: CommentFilter,
    left_out: bool,
}

impl Filter {
    fn given_the_filter_of_the_settings(settings: &str) -> Self {
        Self { filter: CommentFilter::of(settings), left_out: false }
    }

    fn when_the_filter_reads(&mut self, text: &str) {
        self.left_out = self.filter.leaves_out(text);
    }

    fn then_the_text_is_left_out(&self, expected: bool) {
        assert_eq!(self.left_out, expected);
    }
}

#[test]
fn should_leave_out_a_text_holding_a_word_in_any_case() {
    let mut filter = Filter::given_the_filter_of_the_settings(DEFAULT_COMMENT_FILTER);

    filter.when_the_filter_reads("Visit WWW.example.org for more");

    filter.then_the_text_is_left_out(true);
}

#[test]
fn should_keep_a_text_holding_no_word() {
    let mut filter = Filter::given_the_filter_of_the_settings(" spam | | fake ");

    filter.when_the_filter_reads("a fine copy, no problem");

    filter.then_the_text_is_left_out(false);
}

#[test]
fn should_keep_everything_with_an_empty_filter() {
    let mut filter = Filter::given_the_filter_of_the_settings("");

    filter.when_the_filter_reads("http://anything");

    filter.then_the_text_is_left_out(false);
}
