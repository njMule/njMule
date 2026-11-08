// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ConversationsTest, under its names: the chats the front ends show.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::time::{Duration, SystemTime};

use njmule_engine::remote::conversations::{self, Author, Chat, Conversations, Notice};

const ALICE: &str = "203.0.113.5:4662";
const ALICE_HASH: [u8; 16] = [0xA1; 16];

#[test]
fn should_keep_a_message_with_the_nick_and_user_hash_it_came_with() {
    let mut test = Chats::new();
    test.given_a_message_from(ALICE, "hello");

    test.when_the_chats_are_read();

    test.then_the_chat_with_is(ALICE, "alice", Author::Them, "hello");
}

#[test]
fn should_keep_our_answer_and_forget_the_captcha_it_solves() {
    let mut test = Chats::new();
    test.given_a_captcha_asked_by(ALICE);

    test.when_we_write(ALICE, "XK4P");

    test.then_the_chat_with_has_no_captcha(ALICE);
    test.then_the_last_line_is(Author::Us, "XK4P");
}

#[test]
fn should_tell_that_a_message_was_not_delivered() {
    let mut test = Chats::new();
    test.given_a_message_from(ALICE, "hello");
    test.given_a_message_not_delivered_to(ALICE, "offline");

    test.when_the_chats_are_read();

    test.then_the_last_line_is_the_notice(Notice::NotDelivered, "offline");
}

#[test]
fn should_forget_the_oldest_strangers_beyond_their_limit() {
    let mut test = Chats::new();
    test.given_strangers_wrote(conversations::MAX_STRANGERS + 1);

    test.when_the_chats_are_read();

    test.then_there_are_chats(conversations::MAX_STRANGERS);
    test.then_there_is_no_chat_with(&stranger(0));
}

#[test]
fn should_keep_the_chats_we_wrote_whatever_strangers_write() {
    let mut test = Chats::new();
    test.given_we_wrote(ALICE);
    test.given_strangers_wrote(conversations::MAX_STRANGERS + 1);

    test.when_the_chats_are_read();

    test.then_there_are_chats(conversations::MAX_STRANGERS + 1);
    test.then_the_oldest_chat_is_with(ALICE);
}

struct Chats {
    conversations: Conversations,
    now: SystemTime,
    chats: Vec<Chat>,
}

impl Chats {
    fn new() -> Self {
        // 2026-10-24T12:00:00Z
        let now = SystemTime::UNIX_EPOCH + Duration::from_secs(1_792_843_200);
        Self { conversations: Conversations::default(), now, chats: Vec::new() }
    }

    fn given_a_message_from(&mut self, address: &str, text: &str) {
        self.conversations.received(address, ALICE_HASH, Some("alice"), text, self.now);
    }

    fn given_a_captcha_asked_by(&mut self, address: &str) {
        self.conversations.captcha(address, ALICE_HASH, b"BM");
    }

    fn given_a_message_not_delivered_to(&mut self, address: &str, reason: &str) {
        self.conversations.failed(address, reason, self.now);
    }

    fn given_we_wrote(&mut self, address: &str) {
        self.conversations.sent(address, "hi", self.now);
    }

    fn given_strangers_wrote(&mut self, count: usize) {
        for i in 0..count {
            self.conversations.received(&stranger(i), ALICE_HASH, None, "buy now", self.now);
        }
    }

    fn when_the_chats_are_read(&mut self) {
        self.chats = self.conversations.all();
    }

    fn when_we_write(&mut self, address: &str, text: &str) {
        self.conversations.sent(address, text, self.now);
        self.chats = self.conversations.all();
    }

    fn then_the_chat_with_is(&self, address: &str, nick: &str, author: Author, text: &str) {
        let chat = self.chat_with(address);
        assert_eq!(chat.nick.as_deref(), Some(nick));
        assert_eq!(chat.user_hash, Some(ALICE_HASH));
        let lines: Vec<(Author, &str)> = chat.lines.iter().map(|line| (line.author, line.text.as_str())).collect();
        assert_eq!(lines, [(author, text)]);
    }

    fn then_the_chat_with_has_no_captcha(&self, address: &str) {
        assert_eq!(self.chat_with(address).captcha, None);
    }

    fn then_the_last_line_is(&self, author: Author, text: &str) {
        let last = self.chats[0].lines.last().unwrap();
        assert_eq!(last.author, author);
        assert_eq!(last.text, text);
    }

    fn then_the_last_line_is_the_notice(&self, notice: Notice, text: &str) {
        let last = self.chats[0].lines.last().unwrap();
        assert_eq!(last.author, Author::Notice);
        assert_eq!(last.notice, Some(notice));
        assert_eq!(last.text, text);
    }

    fn then_there_are_chats(&self, count: usize) {
        assert_eq!(self.chats.len(), count);
    }

    fn then_there_is_no_chat_with(&self, address: &str) {
        assert!(!self.chats.iter().any(|chat| chat.address == address));
    }

    fn then_the_oldest_chat_is_with(&self, address: &str) {
        assert_eq!(self.chats.last().unwrap().address, address);
    }

    fn chat_with(&self, address: &str) -> &Chat {
        self.chats.iter().find(|chat| chat.address == address).expect("the chat")
    }
}

fn stranger(i: usize) -> String {
    format!("198.51.100.{}:4662", i + 1)
}
