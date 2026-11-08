// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The chats the front ends show, kept from the engine's events and our own messages, by the address of each client.

use std::collections::VecDeque;
use std::time::SystemTime;

use indexmap::IndexMap;

/// Lines a chat keeps at most, the oldest going first.
pub const MAX_LINES: usize = 1000;
/// Chats with clients we never wrote to kept at most: those that write and are never answered are forgotten first, so
/// that spam cannot fill the memory.
pub const MAX_STRANGERS: usize = 50;

/// Who a line is from; a notice tells what happened to our messages.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Author {
    Us,
    Them,
    Notice,
}

/// What a notice tells.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Notice {
    CaptchaPassed,
    CaptchaWrong,
    NotDelivered,
}

/// A line of a chat; a notice's text is the reason a message was not delivered, or empty.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Line {
    pub sequence: u64,
    pub time: SystemTime,
    pub author: Author,
    pub text: String,
    pub notice: Option<Notice>,
}

/// A chat as it stands; `captcha` a BMP picture to solve.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Chat {
    pub address: String,
    pub user_hash: Option<[u8; 16]>,
    pub nick: Option<String>,
    pub lines: Vec<Line>,
    pub captcha: Option<Vec<u8>>,
}

#[derive(Debug, Default)]
struct Open {
    lines: VecDeque<Line>,
    user_hash: Option<[u8; 16]>,
    nick: Option<String>,
    captcha: Option<Vec<u8>>,
    // We wrote or answered: kept however many strangers write.
    ours: bool,
}

#[derive(Debug, Default)]
pub struct Conversations {
    // By address, the chat with the newest line last.
    chats: IndexMap<String, Open>,
    sequence: u64,
}

impl Conversations {
    pub fn received(&mut self, address: &str, user_hash: [u8; 16], nick: Option<&str>, text: &str, now: SystemTime) {
        let chat = self.touch(address);
        chat.user_hash = Some(user_hash);
        if let Some(nick) = nick {
            chat.nick = Some(nick.to_owned());
        }
        self.add(address, Author::Them, text, None, now);
        self.forget_strangers();
    }

    pub fn captcha(&mut self, address: &str, user_hash: [u8; 16], image: &[u8]) {
        let chat = self.touch(address);
        chat.user_hash = Some(user_hash);
        chat.captcha = Some(image.to_vec());
    }

    pub fn answered(&mut self, address: &str, solved: bool, now: SystemTime) {
        self.touch(address);
        let notice = if solved { Notice::CaptchaPassed } else { Notice::CaptchaWrong };
        self.add(address, Author::Notice, "", Some(notice), now);
    }

    pub fn failed(&mut self, address: &str, reason: &str, now: SystemTime) {
        self.touch(address);
        self.add(address, Author::Notice, reason, Some(Notice::NotDelivered), now);
    }

    /// Our message to `address`, which answers a CAPTCHA it asked for.
    pub fn sent(&mut self, address: &str, text: &str, now: SystemTime) {
        let chat = self.touch(address);
        chat.ours = true;
        chat.captcha = None;
        self.add(address, Author::Us, text, None, now);
    }

    /// Forgets the chat with `address`; false when there is none.
    pub fn close(&mut self, address: &str) -> bool {
        self.chats.shift_remove(address).is_some()
    }

    /// The chats, the one with the newest line first.
    pub fn all(&self) -> Vec<Chat> {
        self.chats
            .iter()
            .rev()
            .map(|(address, chat)| Chat {
                address: address.clone(),
                user_hash: chat.user_hash,
                nick: chat.nick.clone(),
                lines: chat.lines.iter().cloned().collect(),
                captcha: chat.captcha.clone(),
            })
            .collect()
    }

    // The chat with `address`, moved last as the newest.
    fn touch(&mut self, address: &str) -> &mut Open {
        let chat = self.chats.shift_remove(address).unwrap_or_default();
        self.chats.entry(address.to_owned()).or_insert(chat)
    }

    fn add(&mut self, address: &str, author: Author, text: &str, notice: Option<Notice>, now: SystemTime) {
        self.sequence = self.sequence.saturating_add(1);
        let line = Line { sequence: self.sequence, time: now, author, text: text.to_owned(), notice };
        if let Some(chat) = self.chats.get_mut(address) {
            chat.lines.push_back(line);
            if chat.lines.len() > MAX_LINES {
                chat.lines.pop_front();
            }
        }
    }

    fn forget_strangers(&mut self) {
        let mut strangers = self.chats.values().filter(|chat| !chat.ours).count();
        // The oldest first.
        self.chats.retain(|_, chat| {
            if strangers > MAX_STRANGERS && !chat.ours {
                strangers = strangers.saturating_sub(1);
                return false;
            }
            true
        });
    }
}
