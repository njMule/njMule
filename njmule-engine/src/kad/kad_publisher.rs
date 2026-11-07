// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Publishes what we share on Kad: each keyword of the complete files once a day, with all its files, ourselves as a
//! source of every shared file every five hours, and our comment on a shared file once a day and whenever it changes.
//! Only a node that is ready and reachable publishes.

use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

use indexmap::IndexMap;

use crate::api::file_comment::FileComment;
use crate::files::file_types;
use crate::kad::keywords;
use crate::kad::uint128::UInt128;
use crate::protocol::kad::kad_messages::Entry;
use crate::protocol::tag::{Tag, TagName, file_tag};

pub const KEYWORD_EVERY: Duration = Duration::from_secs(24 * 3600);
pub const SOURCE_EVERY: Duration = Duration::from_secs(5 * 3600);
pub const NOTE_EVERY: Duration = Duration::from_secs(24 * 3600);
/// Files published under one keyword at most.
pub const FILES_PER_KEYWORD: usize = 150;

const START_EVERY: Duration = Duration::from_secs(2);
const MAX_KEYWORD_PUBLISHES: usize = 3;
const MAX_SOURCE_PUBLISHES: usize = 4;
const MAX_NOTE_PUBLISHES: usize = 1;

/// A file we share; `complete` is false for the checked parts of a download.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Shared {
    pub hash: [u8; 16],
    pub name: String,
    pub size: u64,
    pub complete: bool,
}

/// What publishing needs of the node.
pub trait Node {
    fn is_ready(&self) -> bool;

    fn is_connected(&self) -> bool;

    fn is_firewalled(&self) -> bool;

    /// Whether other clients can reach us: directly, through a buddy or by a direct callback.
    fn is_reachable(&self) -> bool {
        !self.is_firewalled()
    }

    /// The address of the buddy that relays callbacks to us, 0 without one.
    fn buddy_ip(&self) -> u32 {
        0
    }

    fn running_keyword_publishes(&self) -> usize;

    fn running_source_publishes(&self) -> usize;

    fn running_note_publishes(&self) -> usize;

    /// Starts publishing `files` under `word`; false when it cannot now.
    fn publish_keyword(&mut self, word: &str, files: Vec<Entry>) -> bool;

    /// Starts publishing us as a source of a file; false when it cannot now.
    fn publish_source(&mut self, hash: [u8; 16], size: u64) -> bool;

    /// Starts publishing our comment on a file, which the node reads again for each node it goes to: taken away, the
    /// publish ends. False when it cannot start now.
    fn publish_note(&mut self, hash: [u8; 16], name: &str, size: u64) -> bool;
}

#[derive(Debug, Default)]
pub struct KadPublisher {
    keyword_done: HashMap<String, Instant>,
    // Where the files of a keyword with more than its share start at its next publish.
    keyword_start: HashMap<String, usize>,
    source_done: HashMap<[u8; 16], Instant>,
    // Our comment on a file as last published, and when.
    note_done: HashMap<[u8; 16], (FileComment, Instant)>,
    next_keyword: Option<Instant>,
    next_source: Option<Instant>,
    next_note: Option<Instant>,
    last_buddy: u32,
    // What each kind tried last: the next try starts after it.
    last_keyword: Option<String>,
    last_source: Option<[u8; 16]>,
    last_note: Option<[u8; 16]>,
}

impl KadPublisher {
    pub fn new() -> Self {
        Self::default()
    }

    /// Runs every second: starts at most one keyword, one source and one note publish every two seconds, of the
    /// `shared` files with our `comments` on them. A new buddy has every source published again at once: the old ones
    /// point to the buddy we left.
    pub fn tick(
        &mut self,
        node: &mut dyn Node,
        shared: &[Shared],
        comments: &dyn Fn(&[u8; 16]) -> Option<FileComment>,
        now: Instant,
    ) {
        if !node.is_ready() || !node.is_connected() || !node.is_reachable() {
            return;
        }
        let buddy = node.buddy_ip();
        if buddy != self.last_buddy {
            self.last_buddy = buddy;
            if buddy != 0 {
                self.source_done.clear();
            }
        }
        let next = now.checked_add(START_EVERY);
        if is_due(self.next_keyword, now) && node.running_keyword_publishes() < MAX_KEYWORD_PUBLISHES {
            self.next_keyword = next;
            self.publish_one_keyword(node, shared, now);
        }
        if is_due(self.next_source, now) && node.running_source_publishes() < MAX_SOURCE_PUBLISHES {
            self.next_source = next;
            self.publish_one_source(node, shared, now);
        }
        if is_due(self.next_note, now) && node.running_note_publishes() < MAX_NOTE_PUBLISHES {
            self.next_note = next;
            self.publish_one_note(node, shared, comments, now);
        }
    }

    // A keyword with more files than its share publishes the next ones each time, round the list.
    fn publish_one_keyword(&mut self, node: &mut dyn Node, shared: &[Shared], now: Instant) {
        let mut by_keyword: IndexMap<String, Vec<&Shared>> = IndexMap::new();
        for file in shared.iter().filter(|file| file.complete) {
            for word in keywords::of(&file.name) {
                by_keyword.entry(word).or_default().push(file);
            }
        }
        let words: Vec<&String> = by_keyword.keys().collect();
        let last = self.last_keyword.as_ref().and_then(|last| words.iter().position(|word| *word == last));
        for word in turned(&words, last) {
            if self.keyword_done.get(*word).is_some_and(|done| now.saturating_duration_since(*done) < KEYWORD_EVERY) {
                continue;
            }
            self.last_keyword = Some((*word).clone());
            let Some(all) = by_keyword.get(*word).filter(|all| !all.is_empty()) else {
                return;
            };
            let start = self.keyword_start.get(*word).copied().unwrap_or(0).checked_rem(all.len()).unwrap_or(0);
            let entries: Vec<Entry> = all
                .iter()
                .cycle()
                .skip(start)
                .take(all.len().min(FILES_PER_KEYWORD))
                .map(|file| Entry { id: UInt128::from_big_endian(file.hash), tags: tags(file) })
                .collect();
            let published = entries.len();
            if node.publish_keyword(word, entries) {
                self.keyword_done.insert((*word).clone(), now);
                self.keyword_start
                    .insert((*word).clone(), start.saturating_add(published).checked_rem(all.len()).unwrap_or(0));
            }
            return;
        }
    }

    fn publish_one_source(&mut self, node: &mut dyn Node, shared: &[Shared], now: Instant) {
        let last = self.last_source.and_then(|last| shared.iter().position(|file| file.hash == last));
        let files: Vec<&Shared> = shared.iter().collect();
        for file in turned(&files, last) {
            if self.source_done.get(&file.hash).is_some_and(|done| now.saturating_duration_since(*done) < SOURCE_EVERY)
            {
                continue;
            }
            self.last_source = Some(file.hash);
            if node.publish_source(file.hash, file.size) {
                self.source_done.insert(file.hash, now);
            }
            return;
        }
    }

    // A changed comment is due at once; one taken away is published no more, and at once if it comes back.
    fn publish_one_note(
        &mut self,
        node: &mut dyn Node,
        shared: &[Shared],
        comments: &dyn Fn(&[u8; 16]) -> Option<FileComment>,
        now: Instant,
    ) {
        let last = self.last_note.and_then(|last| shared.iter().position(|file| file.hash == last));
        let files: Vec<&Shared> = shared.iter().collect();
        let mut commented = HashSet::new();
        let mut due: Option<(&Shared, FileComment)> = None;
        for file in turned(&files, last) {
            let Some(comment) = comments(&file.hash).filter(|comment| !comment.is_empty()) else {
                continue;
            };
            commented.insert(file.hash);
            let done = self.note_done.get(&file.hash);
            let stale = done.is_none_or(|(published, at)| {
                *published != comment || now.saturating_duration_since(*at) >= NOTE_EVERY
            });
            if due.is_none() && stale {
                due = Some((file, comment));
            }
        }
        self.note_done.retain(|hash, _| commented.contains(hash));
        let Some((file, comment)) = due else {
            return;
        };
        self.last_note = Some(file.hash);
        if node.publish_note(file.hash, &file.name, file.size) {
            self.note_done.insert(file.hash, (comment, now));
        }
    }
}

fn is_due(next: Option<Instant>, now: Instant) -> bool {
    next.is_none_or(|next| now >= next)
}

// The words of keywords, or the shared files, starting after the one tried last: one that cannot be published now does
// not hold back the others, and is tried again at its next turn.
fn turned<T>(items: &[T], last: Option<usize>) -> impl Iterator<Item = &T> {
    let after = last.map_or(0, |last| last.saturating_add(1)).min(items.len());
    let (head, tail) = items.split_at(after);
    tail.iter().chain(head.iter())
}

fn tags(file: &Shared) -> Vec<Tag> {
    let mut tags = vec![
        Tag::String { name: TagName::id(file_tag::FILENAME), value: file.name.clone() },
        Tag::Int { name: TagName::id(file_tag::FILESIZE), value: file.size },
        Tag::Int { name: TagName::id(file_tag::SOURCES), value: 1 },
    ];
    if let Some(file_type) = file_types::of(&file.name) {
        tags.push(Tag::String { name: TagName::id(file_tag::FILETYPE), value: file_type.to_owned() });
    }
    tags
}
