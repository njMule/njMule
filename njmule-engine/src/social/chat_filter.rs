// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Which incoming chat messages show: only friends' when the preferences say so, none with a filtered word, and
//! against spam none from a stranger who sends a link or keeps writing before we answered.

/// What the preferences ask of incoming messages.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChatFilter {
    pub friends_only: bool,
    words: Vec<String>,
    pub against_spam: bool,
}

/// What a client's conversation tells the filter: what it sent, whether we wrote, whether it spammed.
#[derive(Debug, Clone, Default)]
pub struct State {
    received: usize,
    wrote: bool,
    spammer: bool,
}

impl State {
    /// We wrote to the client.
    pub fn wrote(&mut self) {
        self.wrote = true;
        self.received = 0;
    }
}

/// Unanswered messages shown at most.
pub const MAX_UNANSWERED: usize = 3;
// What tells a link: a scheme or the start of a host name.
const LINKS: [&str; 6] = ["http:", "https:", "ftp:", "ed2k:", "www.", "ftp."];

impl ChatFilter {
    /// Shows every message.
    pub const NONE: Self = Self { friends_only: false, words: Vec::new(), against_spam: false };

    /// The filter, its words trimmed and in lower case, the empty ones left out.
    pub fn new(friends_only: bool, words: &[String], against_spam: bool) -> Self {
        let words =
            words.iter().map(|word| word.trim().to_lowercase()).filter(|word| !word.is_empty()).collect::<Vec<_>>();
        Self { friends_only, words, against_spam }
    }

    /// The words of `text`, apart by `|`, as the preferences write them.
    pub fn words_of(text: &str) -> Vec<String> {
        text.split('|').map(str::to_owned).collect()
    }

    pub fn words(&self) -> &[String] {
        &self.words
    }

    /// Whether a message of `text` from a client, a friend or not, shows; `state` keeps count.
    pub fn shows(&self, state: &mut State, friend: bool, text: &str) -> bool {
        if self.friends_only && !friend {
            return false;
        }
        let lower = text.to_lowercase();
        let spamming = !friend && self.against_spam;
        if self.words.iter().any(|word| lower.contains(word.as_str())) {
            state.spammer |= spamming && !state.wrote;
            return false;
        }
        if spamming {
            if !state.wrote && (state.received >= MAX_UNANSWERED || LINKS.iter().any(|link| lower.contains(link))) {
                state.spammer = true;
            }
            if state.spammer {
                return false;
            }
        }
        state.received = state.received.saturating_add(1);
        true
    }
}
