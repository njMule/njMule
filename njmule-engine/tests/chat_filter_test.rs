// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ChatFilterTest, under its names: which incoming chat messages show.

use njmule_engine::social::chat_filter::{self, ChatFilter, State};

#[test]
fn should_split_the_filtered_words_at_the_bars() {
    let mut test = Filtering::new();

    test.when_the_words_are_read("a|b c|");

    test.then_the_words_are(&["a", "b c", ""]);
}

#[test]
fn should_keep_the_filtered_words_trimmed_in_lower_case_and_none_empty() {
    let mut test = Filtering::new();

    test.when_a_filter_is_made_against_the_words("Fastest Download Speed| |viagra");

    test.then_the_filter_leaves_out(&["fastest download speed", "viagra"]);
}

#[test]
fn should_leave_out_a_message_with_a_filtered_word() {
    let mut test = Filtering::new();
    test.given_the_usual_filter();

    test.when_a_friend_writes("get the FASTEST download speed now");

    test.then_the_message_is_hidden("a filtered word");
}

#[test]
fn should_show_a_message_without_filtered_words() {
    let mut test = Filtering::new();
    test.given_the_usual_filter();

    test.when_a_friend_writes("hello");

    test.then_the_message_is_shown("no filtered word");
}

#[test]
fn should_hide_strangers_when_only_friends_are_shown() {
    let mut test = Filtering::new();
    test.given_a_filter_showing_only_friends();

    test.when_a_stranger_writes("hello");

    test.then_the_message_is_hidden("a stranger");
}

#[test]
fn should_show_friends_when_only_friends_are_shown() {
    let mut test = Filtering::new();
    test.given_a_filter_showing_only_friends();

    test.when_a_friend_writes("hello");

    test.then_the_message_is_shown("a friend");
}

#[test]
fn should_take_a_link_before_we_wrote_for_spam() {
    let mut test = Filtering::new();
    test.given_the_usual_filter();

    test.when_a_stranger_writes("visit www.example.org");

    test.then_the_message_is_hidden("a link before we wrote");
}

#[test]
fn should_keep_taking_a_spammer_for_one() {
    let mut test = Filtering::new();
    test.given_the_usual_filter();
    test.given_a_stranger_was_hidden_for_writing("visit www.example.org");

    test.when_a_stranger_writes("hello");

    test.then_the_message_is_hidden("a spammer stays one");
}

#[test]
fn should_not_take_a_link_after_we_wrote_for_spam() {
    let mut test = Filtering::new();
    test.given_the_usual_filter();
    test.given_we_wrote();

    test.when_a_stranger_writes("see https://example.org");

    test.then_the_message_is_shown("we wrote");
}

#[test]
fn should_not_take_a_link_from_a_friend_for_spam() {
    let mut test = Filtering::new();
    test.given_the_usual_filter();

    test.when_a_friend_writes("www.example.org");

    test.then_the_message_is_shown("friends");
}

#[test]
fn should_show_three_unanswered_messages_at_most() {
    let mut test = Filtering::new();
    test.given_the_usual_filter();
    test.given_the_unanswered_messages_shown(chat_filter::MAX_UNANSWERED);

    test.when_a_stranger_writes("hi again");

    test.then_the_message_is_hidden("one too many");
}

#[test]
fn should_show_everything_without_a_filter() {
    let mut test = Filtering::new();
    test.given_no_filter();

    test.when_a_stranger_writes("www.example.org");

    test.then_the_message_is_shown("no filter");
}

struct Filtering {
    filter: ChatFilter,
    state: State,
    words: Vec<String>,
    shown: bool,
}

impl Filtering {
    fn new() -> Self {
        Self { filter: ChatFilter::NONE, state: State::default(), words: Vec::new(), shown: false }
    }

    fn given_the_usual_filter(&mut self) {
        self.filter = ChatFilter::new(false, &ChatFilter::words_of("Fastest Download Speed| |viagra"), true);
    }

    fn given_a_filter_showing_only_friends(&mut self) {
        self.filter = ChatFilter::new(true, &[], false);
    }

    fn given_no_filter(&mut self) {
        self.filter = ChatFilter::NONE;
    }

    fn given_we_wrote(&mut self) {
        self.state.wrote();
    }

    fn given_a_stranger_was_hidden_for_writing(&mut self, message: &str) {
        assert!(!self.filter.shows(&mut self.state, false, message));
    }

    fn given_the_unanswered_messages_shown(&mut self, count: usize) {
        for i in 0..count {
            assert!(self.filter.shows(&mut self.state, false, &format!("hi {i}")));
        }
    }

    fn when_the_words_are_read(&mut self, text: &str) {
        self.words = ChatFilter::words_of(text);
    }

    fn when_a_filter_is_made_against_the_words(&mut self, text: &str) {
        self.filter = ChatFilter::new(false, &ChatFilter::words_of(text), true);
    }

    fn when_a_friend_writes(&mut self, message: &str) {
        self.shown = self.filter.shows(&mut self.state, true, message);
    }

    fn when_a_stranger_writes(&mut self, message: &str) {
        self.shown = self.filter.shows(&mut self.state, false, message);
    }

    fn then_the_words_are(&self, expected: &[&str]) {
        assert_eq!(self.words, expected);
    }

    fn then_the_filter_leaves_out(&self, expected: &[&str]) {
        assert_eq!(self.filter.words(), expected, "trimmed, in lower case, none empty");
    }

    fn then_the_message_is_shown(&self, why: &str) {
        assert!(self.shown, "{why}");
    }

    fn then_the_message_is_hidden(&self, why: &str) {
        assert!(!self.shown, "{why}");
    }
}
