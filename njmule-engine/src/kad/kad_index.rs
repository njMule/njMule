// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What other nodes publish to us: files under keywords, sources of files and notes (comments and ratings) on files,
//! with global and per-key limits and an expiry, answered to searches. Times are the nanoseconds of the node's ticker,
//! which may go back before it started, as a restored index needs; `wall` times are epoch seconds.

use std::collections::HashMap;
use std::time::Duration;

use indexmap::IndexMap;

use crate::api::file_comment::FileComment;
use crate::kad::kad_lower_case;
use crate::kad::keywords;
use crate::kad::uint128::UInt128;
use crate::persist::format::kad_index_files as files;
use crate::protocol::kad::kad_messages::{Entry, SearchAnswer};
use crate::protocol::tag::{Tag, TagName, file_tag, tags};
use crate::search::search_expr::{Comparison, SearchExpr};

pub const KEYWORD_LIFETIME: Duration = Duration::from_secs(24 * 3600);
pub const SOURCE_LIFETIME: Duration = Duration::from_secs(5 * 3600);
/// Publishers renew their notes once a day, so a note nobody renews goes after a day.
pub const NOTE_LIFETIME: Duration = Duration::from_secs(24 * 3600);
/// Notes per file at most, and per answer.
pub const MAX_NOTES_PER_FILE: usize = 150;
/// Notes of all files at most.
pub const MAX_NOTES: usize = 50_000;
/// What a note keeps of the file name the publisher gave, so that a note stays small.
pub const MAX_NOTE_NAME: usize = 255;
/// What a keyword entry keeps of a name: no file system takes a longer one.
pub const MAX_KEYWORD_NAME: usize = 255;
/// Keyword entries in total at most.
pub const MAX_KEYWORD_ENTRIES: usize = 60_000;
/// Files per keyword at most.
pub const MAX_FILES_PER_KEYWORD: usize = 50_000;
/// Sources per file at most.
pub const MAX_SOURCES_PER_FILE: usize = 1_000;
/// Files of all keywords and sources of all files at most.
pub const MAX_SOURCES: usize = 250_000;
/// Results per answer at most.
pub const MAX_RESULTS: usize = 300;
/// Bytes of a search answer at most: one datagram that no link has to fragment.
pub const ANSWER_BYTES: usize = 1_420;

const MAX_PUBLISHERS: usize = 100;
// Our keywords whose nodes said they are loaded, kept at most.
const MAX_LOADS: usize = 100_000;
// The largest information an answer carries about the publishers of a file.
const MAX_PUBLISH_INFO: u64 = 0xFFFF_FFFF;
const NANOS: i64 = 1_000_000_000;

/// A publisher of a file under a keyword: the name it gave last, and when.
#[derive(Debug, Clone)]
struct Publisher {
    name: String,
    at: i64,
}

/// A file published under a keyword.
#[derive(Debug)]
struct KeywordEntry {
    // How many publishers give each name: no more names than publishers are kept.
    names: IndexMap<String, u32>,
    size: u64,
    tags: Vec<Tag>,
    // By address, the one that published longest ago first.
    publishers: IndexMap<u32, Publisher>,
    expires: i64,
    // The most popular name and its lower case, worked out again only once the names changed.
    popular: Option<(String, String)>,
}

impl KeywordEntry {
    fn new(size: u64) -> Self {
        Self { names: IndexMap::new(), size, tags: Vec::new(), publishers: IndexMap::new(), expires: 0, popular: None }
    }

    // A publisher counts once, for the name it gave last, so repeating or switching names gains nothing.
    fn published(&mut self, ip: u32, name: &str, now: i64) {
        self.forget(now);
        let before = self.publishers.shift_remove(&ip);
        if before.is_none() && self.publishers.len() >= MAX_PUBLISHERS {
            return;
        }
        self.publishers.insert(ip, Publisher { name: name.to_owned(), at: now });
        if before.as_ref().is_none_or(|before| before.name != name) {
            if let Some(before) = before {
                self.drop_name(&before.name);
            }
            let count = self.names.entry(name.to_owned()).or_insert(0);
            *count = count.saturating_add(1);
            self.popular = None;
        }
    }

    // A publisher silent for as long as entries live counts no more and leaves its place to another.
    fn forget(&mut self, now: i64) {
        while let Some((_, oldest)) = self.publishers.first()
            && now.saturating_sub(oldest.at) >= nanos(KEYWORD_LIFETIME)
        {
            if let Some((_, gone)) = self.publishers.shift_remove_index(0) {
                self.drop_name(&gone.name);
            }
        }
    }

    fn drop_name(&mut self, name: &str) {
        match self.names.get_mut(name) {
            Some(count) if *count > 1 => *count = count.saturating_sub(1),
            Some(_) => {
                self.names.shift_remove(name);
            }
            None => {}
        }
        self.popular = None;
    }

    // The name most publishers give, the first of them on a tie, and its lower case.
    fn popular(&mut self) -> (String, String) {
        if self.popular.is_none() {
            let mut best = "";
            let mut most = 0;
            for (name, count) in &self.names {
                if best.is_empty() && most == 0 || *count > most {
                    best = name;
                    most = *count;
                }
            }
            self.popular = Some((best.to_owned(), kad_lower_case::to_lower_case(best)));
        }
        self.popular.clone().unwrap_or_default()
    }
}

/// A source of a file.
#[derive(Debug, Clone)]
struct SourceEntry {
    ip: u32,
    tcp_port: u16,
    udp_port: u16,
    tags: Vec<Tag>,
    expires: i64,
}

/// A note on a file, as an answer carries it.
#[derive(Debug, Clone)]
struct NoteEntry {
    ip: u32,
    size: u64,
    tags: Vec<Tag>,
    expires: i64,
}

#[derive(Debug, Default)]
pub struct KadIndex {
    keywords: IndexMap<UInt128, IndexMap<UInt128, KeywordEntry>>,
    sources: IndexMap<UInt128, IndexMap<UInt128, SourceEntry>>,
    // By file, then by the publisher's id, oldest first.
    notes: IndexMap<UInt128, IndexMap<UInt128, NoteEntry>>,
    // Our keywords not to publish until the time given, because their nodes are loaded.
    loaded: IndexMap<UInt128, i64>,
    keyword_entries: usize,
    source_entries: usize,
    note_entries: usize,
}

impl KadIndex {
    pub fn new() -> Self {
        Self::default()
    }

    /// Stores a file published under `keyword` by `ip`; returns the keyword's load.
    pub fn publish_keyword(&mut self, keyword: UInt128, file: &Entry, ip: u32, now: i64) -> u8 {
        let mut count = self.keywords.get(&keyword).map_or(0, IndexMap::len);
        let name = tags::string_value(&file.tags, file_tag::FILENAME).map(|name| cut(name, MAX_KEYWORD_NAME));
        let size = tags::int_value(&file.tags, file_tag::FILESIZE).unwrap_or(0);
        let Some(name) = name.filter(|name| !name.is_empty() && size > 0) else {
            return load(count, MAX_FILES_PER_KEYWORD);
        };
        let other = other_tags(&file.tags);
        // What no answer could carry is never asked for.
        let answer = answer_entry(file.id, &name, size, &other, MAX_PUBLISH_INFO);
        if !SearchAnswer::fits(&answer, ANSWER_BYTES).unwrap_or(false) {
            return load(count, MAX_FILES_PER_KEYWORD);
        }
        let known = self.keywords.get(&keyword).and_then(|files| files.get(&file.id)).map(|entry| entry.size);
        if known != Some(size) {
            if self.keyword_entries >= MAX_KEYWORD_ENTRIES || count >= MAX_FILES_PER_KEYWORD {
                return 100;
            }
            self.keywords.entry(keyword).or_default().insert(file.id, KeywordEntry::new(size));
            if known.is_none() {
                self.keyword_entries = self.keyword_entries.saturating_add(1);
                count = count.saturating_add(1);
            }
        }
        if let Some(entry) = self.keywords.get_mut(&keyword).and_then(|files| files.get_mut(&file.id)) {
            entry.published(ip, &name, now);
            entry.tags = other;
            entry.expires = now.saturating_add(nanos(KEYWORD_LIFETIME));
        }
        if count == 1 { 1 } else { load(count, MAX_FILES_PER_KEYWORD) }
    }

    /// Stores a source of `file` at `ip`; returns its load, none when refused.
    pub fn publish_source(
        &mut self,
        file: UInt128,
        source: UInt128,
        tags: &[Tag],
        ip: u32,
        udp_port: u16,
        now: i64,
    ) -> Option<u8> {
        tags::int_value(tags, file_tag::SOURCETYPE)?;
        let tcp = tags::port_value(tags, file_tag::SOURCEPORT).unwrap_or(0);
        let udp = if tags::int_value(tags, file_tag::SOURCEUPORT).is_some() {
            tags::port_value(tags, file_tag::SOURCEUPORT).unwrap_or(0)
        } else {
            udp_port
        };
        if ip == 0 || tcp == 0 || udp == 0 {
            return None;
        }
        // The address is the one the packet came from, never one the publisher names.
        let mut stored = vec![int_tag(file_tag::SOURCEIP, u64::from(ip))];
        let mut has_udp = false;
        for tag in tags {
            let id = tag.name().as_id();
            if id == Some(file_tag::SOURCEIP) {
                continue;
            }
            has_udp |= id == Some(file_tag::SOURCEUPORT);
            stored.push(tag.clone());
        }
        if !has_udp {
            stored.push(int_tag(file_tag::SOURCEUPORT, u64::from(udp)));
        }
        let entries = self.sources.entry(file).or_default();
        let before = entries.len();
        entries.retain(|_, e| !(e.ip == ip && (e.tcp_port == tcp || e.udp_port == udp)));
        // The same source from another address replaces itself.
        entries.shift_remove(&source);
        self.source_entries = self.source_entries.saturating_sub(before.saturating_sub(entries.len()));
        if entries.len() >= MAX_SOURCES_PER_FILE || self.source_entries >= MAX_SOURCES {
            entries.shift_remove_index(0)?;
            self.source_entries = self.source_entries.saturating_sub(1);
        }
        let expires = now.saturating_add(nanos(SOURCE_LIFETIME));
        entries.insert(source, SourceEntry { ip, tcp_port: tcp, udp_port: udp, tags: stored, expires });
        self.source_entries = self.source_entries.saturating_add(1);
        Some(load(entries.len(), MAX_SOURCES_PER_FILE))
    }

    /// Files under `keyword` matching `expression`, skipping the first `start`.
    pub fn search_keyword(
        &mut self,
        keyword: UInt128,
        expression: Option<&SearchExpr>,
        start: usize,
        now: i64,
    ) -> Vec<Entry> {
        let mut results = Vec::new();
        let Some(files) = self.keywords.get_mut(&keyword) else {
            return results;
        };
        let matcher = expression.map(Matcher::new);
        let mut skipped = 0usize;
        for (id, entry) in files.iter_mut() {
            if now >= entry.expires {
                continue;
            }
            // Refreshed only by publishers that found no place, an entry may have no name left.
            entry.forget(now);
            let (name, lower) = entry.popular();
            if name.is_empty() || matcher.as_ref().is_some_and(|m| !m.matches(&name, &lower, entry.size, &entry.tags)) {
                continue;
            }
            skipped = skipped.saturating_add(1);
            if skipped <= start {
                continue;
            }
            let publishers = entry.publishers.len() as u64;
            let info = (entry.names.len() as u64).min(255) << 24
                | publishers.min(255) << 16
                | publishers.saturating_mul(100).min(0xFFFF);
            results.push(answer_entry(*id, &name, entry.size, &entry.tags, info));
            if results.len() >= MAX_RESULTS {
                break;
            }
        }
        results
    }

    /// Sources of `file` for a file of `size` bytes (0: any).
    pub fn search_sources(&self, file: UInt128, size: u64, start: usize, now: i64) -> Vec<Entry> {
        let mut results = Vec::new();
        let Some(entries) = self.sources.get(&file) else {
            return results;
        };
        let mut skipped = 0usize;
        for (id, source) in entries {
            if now >= source.expires {
                continue;
            }
            let entry_size = tags::int_value(&source.tags, file_tag::FILESIZE).unwrap_or(0);
            if size != 0 && entry_size != 0 && entry_size != size {
                continue;
            }
            skipped = skipped.saturating_add(1);
            if skipped <= start {
                continue;
            }
            results.push(Entry { id: *id, tags: source.tags.clone() });
            if results.len() >= MAX_RESULTS {
                break;
            }
        }
        results
    }

    /// Stores the note of the node `publisher` on `file`, sent from `ip`; returns the file's load, none when refused.
    /// A publisher, and an address, hold one note on a file: a new one replaces it.
    pub fn publish_note(&mut self, file: UInt128, publisher: UInt128, tags: &[Tag], ip: u32, now: i64) -> Option<u8> {
        let comment = note_comment(tags);
        if ip == 0 || comment.is_empty() {
            return None;
        }
        // Only what a note says is kept, no longer than clients show it.
        let name = cut(tags::string_value(tags, file_tag::FILENAME).unwrap_or(""), MAX_NOTE_NAME);
        let size = tags::int_value(tags, file_tag::FILESIZE).unwrap_or(0);
        let mut stored = Vec::new();
        if !name.is_empty() {
            stored.push(string_tag(file_tag::FILENAME, name));
        }
        if size > 0 {
            stored.push(int_tag(file_tag::FILESIZE, size));
        }
        if comment.rating() > 0 {
            stored.push(int_tag(file_tag::FILERATING, u64::from(comment.rating())));
        }
        if !comment.text().is_empty() {
            stored.push(string_tag(file_tag::DESCRIPTION, comment.text().to_owned()));
        }
        let entries = self.notes.entry(file).or_default();
        let before = entries.len();
        entries.retain(|_, e| e.ip != ip);
        entries.shift_remove(&publisher);
        self.note_entries = self.note_entries.saturating_sub(before.saturating_sub(entries.len()));
        if entries.len() >= MAX_NOTES_PER_FILE || self.note_entries >= MAX_NOTES {
            if entries.is_empty() {
                self.notes.shift_remove(&file);
                return None;
            }
            entries.shift_remove_index(0);
            self.note_entries = self.note_entries.saturating_sub(1);
        }
        let expires = now.saturating_add(nanos(NOTE_LIFETIME));
        entries.insert(publisher, NoteEntry { ip, size, tags: stored, expires });
        self.note_entries = self.note_entries.saturating_add(1);
        Some(load(entries.len(), MAX_NOTES_PER_FILE))
    }

    /// The notes on `file` for a file of `size` bytes (0: any).
    pub fn search_notes(&self, file: UInt128, size: u64, now: i64) -> Vec<Entry> {
        self.notes.get(&file).map_or_else(Vec::new, |entries| {
            entries
                .iter()
                .filter(|(_, note)| now < note.expires && (size == 0 || note.size == 0 || note.size == size))
                .map(|(id, note)| Entry { id: *id, tags: note.tags.clone() })
                .collect()
        })
    }

    /// The nodes that store our keyword `key` are loaded: it is not published before `until`.
    pub fn loaded(&mut self, key: UInt128, until: i64) {
        if self.loaded.len() >= MAX_LOADS && !self.loaded.contains_key(&key) {
            self.loaded.shift_remove_index(0);
        }
        self.loaded.insert(key, until);
    }

    /// Whether our keyword `key` may be published now.
    pub fn may_store(&mut self, key: &UInt128, now: i64) -> bool {
        let Some(until) = self.loaded.get(key) else {
            return true;
        };
        if now < *until {
            return false;
        }
        self.loaded.shift_remove(key);
        true
    }

    /// The keyword entries alive at `now` for the node `own_id`, as a file. The publishers of each name follow one
    /// another, in the order of the names.
    pub fn keyword_file(&mut self, own_id: UInt128, wall: u64, now: i64) -> files::KeywordIndex {
        let mut keys = Vec::new();
        for (key, entries) in &mut self.keywords {
            let mut sources = Vec::new();
            for (id, entry) in entries.iter_mut() {
                entry.forget(now);
                if now >= entry.expires || entry.publishers.is_empty() {
                    continue;
                }
                let mut by_name: HashMap<&str, Vec<files::Publisher>> = HashMap::new();
                for (ip, publisher) in &entry.publishers {
                    by_name.entry(publisher.name.as_str()).or_default().push(files::Publisher {
                        ip: *ip,
                        time: seconds(wall_of(publisher.at, wall, now)),
                        aich_index: 0xFFFF,
                    });
                }
                let mut names = Vec::new();
                let mut publishers = Vec::new();
                for (name, popularity) in &entry.names {
                    names.push(files::PublishedName { name: name.clone(), popularity: *popularity });
                    publishers.extend(by_name.remove(name.as_str()).unwrap_or_default());
                }
                let (popular, _) = entry.popular();
                let mut tags = vec![string_tag(file_tag::FILENAME, popular), int_tag(file_tag::FILESIZE, entry.size)];
                tags.extend(entry.tags.iter().cloned());
                sources.push(files::KeywordSource {
                    id: *id,
                    entries: vec![files::KeywordEntry {
                        lifetime: seconds(wall_of(entry.expires, wall, now)),
                        tracking: files::Tracking { aich_hashes: Vec::new(), names, publishers },
                        tags,
                    }],
                });
            }
            if !sources.is_empty() {
                keys.push(files::Keyword { key: *key, sources });
            }
        }
        files::KeywordIndex {
            version: 4,
            expiry: seconds(wall.saturating_add(KEYWORD_LIFETIME.as_secs())),
            own_id,
            keywords: keys,
        }
    }

    /// Takes back the keyword entries of a file written for the node `own_id`, each publisher as if it published
    /// again when it last did: the same limits apply as to a live publish. Publishers `refused` are left out.
    pub fn restore_keywords(
        &mut self,
        file: &files::KeywordIndex,
        own_id: UInt128,
        refused: &dyn Fn(u32) -> bool,
        wall: u64,
        now: i64,
    ) {
        if u64::from(file.expiry) <= wall || file.own_id != own_id {
            return;
        }
        for keyword in &file.keywords {
            for source in &keyword.sources {
                for entry in source.entries.iter().filter(|entry| u64::from(entry.lifetime) > wall) {
                    self.restore_keyword(keyword.key, source.id, entry, refused, wall, now);
                }
            }
        }
    }

    fn restore_keyword(
        &mut self,
        keyword: UInt128,
        id: UInt128,
        entry: &files::KeywordEntry,
        refused: &dyn Fn(u32) -> bool,
        wall: u64,
        now: i64,
    ) {
        let (Some(popular), Some(size)) =
            (tags::string_value(&entry.tags, file_tag::FILENAME), tags::int_value(&entry.tags, file_tag::FILESIZE))
        else {
            return;
        };
        let other = other_tags(&entry.tags);
        // Each name goes to as many of the publishers that follow as it counts.
        let publishers = &entry.tracking.publishers;
        let mut names: Vec<&str> = Vec::new();
        for name in &entry.tracking.names {
            for _ in 0..name.popularity {
                if names.len() >= publishers.len() {
                    break;
                }
                names.push(&name.name);
            }
        }
        let mut order: Vec<usize> = (0..publishers.len()).collect();
        order.sort_by_key(|at| publishers.get(*at).map_or(0, |publisher| publisher.time));
        for at in order {
            let Some(publisher) = publishers.get(at) else {
                continue;
            };
            let time = u64::from(publisher.time).min(wall);
            if refused(publisher.ip) || time.saturating_add(KEYWORD_LIFETIME.as_secs()) <= wall {
                continue;
            }
            let name = names.get(at).copied().unwrap_or(popular);
            let mut tags = vec![string_tag(file_tag::FILENAME, name.to_owned()), int_tag(file_tag::FILESIZE, size)];
            tags.extend(other.iter().cloned());
            self.publish_keyword(keyword, &Entry { id, tags }, publisher.ip, ticker_of(time, wall, now));
        }
    }

    /// The sources alive at `now`, as a file.
    pub fn source_file(&self, wall: u64, now: i64) -> files::SourceIndex {
        let keys = self
            .sources
            .iter()
            .filter_map(|(key, entries)| {
                let sources: Vec<files::Source> = entries
                    .iter()
                    .filter(|(_, source)| now < source.expires)
                    .map(|(id, source)| files::Source {
                        id: *id,
                        entries: vec![files::Entry {
                            lifetime: seconds(wall_of(source.expires, wall, now)),
                            tags: source.tags.clone(),
                        }],
                    })
                    .collect();
                (!sources.is_empty()).then_some(files::IndexKey { key: *key, sources })
            })
            .collect();
        files::SourceIndex { version: 2, expiry: seconds(wall.saturating_add(SOURCE_LIFETIME.as_secs())), keys }
    }

    /// Takes back the sources of a file whose key is `near` us, as live publishes from their address.
    pub fn restore_sources(
        &mut self,
        file: &files::SourceIndex,
        near: &dyn Fn(&UInt128) -> bool,
        refused: &dyn Fn(u32) -> bool,
        wall: u64,
        now: i64,
    ) {
        if u64::from(file.expiry) <= wall {
            return;
        }
        for key in file.keys.iter().filter(|key| near(&key.key)) {
            for source in &key.sources {
                for entry in &source.entries {
                    let ip = tags::int_value(&entry.tags, file_tag::SOURCEIP).and_then(|ip| u32::try_from(ip).ok());
                    let udp = tags::port_value(&entry.tags, file_tag::SOURCEUPORT).unwrap_or(0);
                    let lifetime = u64::from(entry.lifetime).min(wall.saturating_add(SOURCE_LIFETIME.as_secs()));
                    let Some(ip) = ip.filter(|ip| *ip != 0 && !refused(*ip)) else {
                        continue;
                    };
                    if lifetime > wall {
                        let at = ticker_of(lifetime, wall, now).saturating_sub(nanos(SOURCE_LIFETIME));
                        self.publish_source(key.key, source.id, &entry.tags, ip, udp, at);
                    }
                }
            }
        }
    }

    /// The notes alive at `now`, as a file of the sources' layout: each note with its publisher's address.
    pub fn note_file(&self, wall: u64, now: i64) -> files::SourceIndex {
        let keys = self
            .notes
            .iter()
            .filter_map(|(key, entries)| {
                let sources: Vec<files::Source> = entries
                    .iter()
                    .filter(|(_, note)| now < note.expires)
                    .map(|(id, note)| {
                        let mut tags = note.tags.clone();
                        tags.push(int_tag(file_tag::SOURCEIP, u64::from(note.ip)));
                        files::Source {
                            id: *id,
                            entries: vec![files::Entry { lifetime: seconds(wall_of(note.expires, wall, now)), tags }],
                        }
                    })
                    .collect();
                (!sources.is_empty()).then_some(files::IndexKey { key: *key, sources })
            })
            .collect();
        files::SourceIndex { version: 2, expiry: seconds(wall.saturating_add(NOTE_LIFETIME.as_secs())), keys }
    }

    /// Takes back the notes of a file whose key is `near` us, as live publishes from their address.
    pub fn restore_notes(
        &mut self,
        file: &files::SourceIndex,
        near: &dyn Fn(&UInt128) -> bool,
        refused: &dyn Fn(u32) -> bool,
        wall: u64,
        now: i64,
    ) {
        if u64::from(file.expiry) <= wall {
            return;
        }
        for key in file.keys.iter().filter(|key| near(&key.key)) {
            for source in &key.sources {
                for entry in &source.entries {
                    let ip = tags::int_value(&entry.tags, file_tag::SOURCEIP).and_then(|ip| u32::try_from(ip).ok());
                    let lifetime = u64::from(entry.lifetime).min(wall.saturating_add(NOTE_LIFETIME.as_secs()));
                    let Some(ip) = ip.filter(|ip| *ip != 0 && !refused(*ip)) else {
                        continue;
                    };
                    if lifetime > wall {
                        let at = ticker_of(lifetime, wall, now).saturating_sub(nanos(NOTE_LIFETIME));
                        self.publish_note(key.key, source.id, &entry.tags, ip, at);
                    }
                }
            }
        }
    }

    /// Our keywords whose nodes are loaded, until when, as a file.
    pub fn load_file(&self, wall: u64, now: i64) -> files::LoadIndex {
        let loads = self
            .loaded
            .iter()
            .filter(|(_, until)| now < **until)
            .map(|(key, until)| files::Load { key: *key, time: seconds(wall_of(*until, wall, now)) })
            .collect();
        files::LoadIndex { save_time: seconds(wall), loads }
    }

    /// Takes back our keywords whose nodes are loaded, for no longer than the most a load spares them.
    pub fn restore_loads(&mut self, file: &files::LoadIndex, longest: Duration, wall: u64, now: i64) {
        for load in &file.loads {
            let until = u64::from(load.time).min(wall.saturating_add(longest.as_secs()));
            if until > wall {
                self.loaded(load.key, ticker_of(until, wall, now));
            }
        }
    }

    /// Drops what expired, and the publishers silent for as long.
    pub fn clean(&mut self, now: i64) {
        self.loaded.retain(|_, until| now < *until);
        let mut dropped = 0usize;
        self.keywords.retain(|_, entries| {
            let before = entries.len();
            entries.retain(|_, entry| now < entry.expires);
            entries.values_mut().for_each(|entry| entry.forget(now));
            dropped = dropped.saturating_add(before.saturating_sub(entries.len()));
            !entries.is_empty()
        });
        self.keyword_entries = self.keyword_entries.saturating_sub(dropped);
        let mut dropped = 0usize;
        self.sources.retain(|_, entries| {
            let before = entries.len();
            entries.retain(|_, source| now < source.expires);
            dropped = dropped.saturating_add(before.saturating_sub(entries.len()));
            !entries.is_empty()
        });
        self.source_entries = self.source_entries.saturating_sub(dropped);
        let mut dropped = 0usize;
        self.notes.retain(|_, entries| {
            let before = entries.len();
            entries.retain(|_, note| now < note.expires);
            dropped = dropped.saturating_add(before.saturating_sub(entries.len()));
            !entries.is_empty()
        });
        self.note_entries = self.note_entries.saturating_sub(dropped);
    }

    pub fn keyword_entries(&self) -> usize {
        self.keyword_entries
    }

    /// The keywords files are stored under.
    pub fn keyword_count(&self) -> usize {
        self.keywords.len()
    }

    pub fn source_entries(&self) -> usize {
        self.source_entries
    }

    pub fn note_entries(&self) -> usize {
        self.note_entries
    }
}

/// The comment and rating a note carries, made fit; a rating out of range counts as none.
pub fn note_comment(tags: &[Tag]) -> FileComment {
    let rating = tags::int_value(tags, file_tag::FILERATING).unwrap_or(0);
    let rating = u8::try_from(rating).ok().filter(|rating| (1..=FileComment::MAX_RATING).contains(rating));
    FileComment::of(tags::string_value(tags, file_tag::DESCRIPTION).unwrap_or(""), rating.unwrap_or(0))
}

/// `text` cut to `max` UTF-16 units, never between the two halves of a surrogate pair.
pub fn cut(text: &str, max: usize) -> String {
    let mut units = 0usize;
    for (at, c) in text.char_indices() {
        units = units.saturating_add(c.len_utf16());
        if units > max {
            return text.get(..at).unwrap_or(text).to_owned();
        }
    }
    text.to_owned()
}

fn load(count: usize, max: usize) -> u8 {
    u8::try_from(count.saturating_mul(100).checked_div(max).unwrap_or(100).min(100)).unwrap_or(100)
}

fn nanos(duration: Duration) -> i64 {
    i64::try_from(duration.as_nanos()).unwrap_or(i64::MAX)
}

// The epoch second of a ticker time, `wall` being the second of `now`.
fn wall_of(ticker: i64, wall: u64, now: i64) -> i64 {
    i64::try_from(wall).unwrap_or(i64::MAX).saturating_add(ticker.saturating_sub(now).div_euclid(NANOS))
}

// The ticker time of an epoch second, `wall` being the second of `now`.
fn ticker_of(second: u64, wall: u64, now: i64) -> i64 {
    let ago = i64::try_from(wall).unwrap_or(i64::MAX).saturating_sub(i64::try_from(second).unwrap_or(i64::MAX));
    now.saturating_sub(ago.saturating_mul(NANOS))
}

fn seconds(epoch: impl TryInto<u32>) -> u32 {
    epoch.try_into().unwrap_or(u32::MAX)
}

fn int_tag(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

fn string_tag(id: u8, value: String) -> Tag {
    Tag::String { name: TagName::id(id), value }
}

fn answer_entry(file: UInt128, name: &str, size: u64, other: &[Tag], info: u64) -> Entry {
    let mut tags = Vec::with_capacity(other.len().saturating_add(3));
    tags.push(string_tag(file_tag::FILENAME, name.to_owned()));
    tags.push(int_tag(file_tag::FILESIZE, size));
    tags.extend(other.iter().cloned());
    tags.push(int_tag(file_tag::PUBLISHINFO, info));
    Entry { id: file, tags }
}

// Name and size are kept as fields, the rest as they came, except what only answers carry.
fn other_tags(tags: &[Tag]) -> Vec<Tag> {
    tags.iter()
        .filter(|tag| {
            !matches!(tag.name().as_id(), Some(file_tag::FILENAME | file_tag::FILESIZE | file_tag::PUBLISHINFO))
        })
        .cloned()
        .collect()
}

/// An expression whose words are split and put in lower case once, for all the files it is matched against.
pub struct Matcher<'a> {
    expression: &'a SearchExpr,
    words: HashMap<&'a str, Vec<String>>,
}

impl<'a> Matcher<'a> {
    pub fn new(expression: &'a SearchExpr) -> Self {
        let mut words = HashMap::new();
        split(expression, &mut words);
        Self { expression, words }
    }

    /// Whether the file `name` (`lower` in the network's lower case) matches.
    pub fn matches(&self, name: &str, lower: &str, size: u64, tags: &[Tag]) -> bool {
        self.matches_expr(self.expression, name, lower, size, tags)
    }

    fn matches_expr(&self, expr: &SearchExpr, name: &str, lower: &str, size: u64, tags: &[Tag]) -> bool {
        match expr {
            SearchExpr::And(left, right) => {
                self.matches_expr(left, name, lower, size, tags) && self.matches_expr(right, name, lower, size, tags)
            }
            SearchExpr::Or(left, right) => {
                self.matches_expr(left, name, lower, size, tags) || self.matches_expr(right, name, lower, size, tags)
            }
            SearchExpr::Not(left, right) => {
                self.matches_expr(left, name, lower, size, tags) && !self.matches_expr(right, name, lower, size, tags)
            }
            SearchExpr::Keyword(text) => contains_all(lower, self.words.get(text.as_str())),
            SearchExpr::StringAttr { tag, value } => matches_string(*tag, value, name, tags),
            SearchExpr::NumericAttr { tag, comparison, value } => matches_number(*tag, *comparison, *value, size, tags),
        }
    }
}

fn split<'a>(expr: &'a SearchExpr, words: &mut HashMap<&'a str, Vec<String>>) {
    match expr {
        SearchExpr::And(left, right) | SearchExpr::Or(left, right) | SearchExpr::Not(left, right) => {
            split(left, words);
            split(right, words);
        }
        SearchExpr::Keyword(text) => {
            words.entry(text.as_str()).or_insert_with(|| words_of(text));
        }
        // Attributes are matched against the size and the tags as they are.
        SearchExpr::StringAttr { .. } | SearchExpr::NumericAttr { .. } => {}
    }
}

// The words of a search term, split where file names are split into keywords.
fn words_of(text: &str) -> Vec<String> {
    kad_lower_case::to_lower_case(text)
        .split(|c| keywords::DELIMITERS.contains(c))
        .filter(|word| !word.is_empty())
        .map(str::to_owned)
        .collect()
}

// A term made only of separators names no word: it matches nothing.
fn contains_all(lower: &str, words: Option<&Vec<String>>) -> bool {
    words.is_some_and(|words| !words.is_empty() && words.iter().all(|word| lower.contains(word.as_str())))
}

fn matches_string(tag: u8, value: &str, name: &str, tags: &[Tag]) -> bool {
    if tag == file_tag::FILEFORMAT {
        return name.rsplit_once('.').is_some_and(|(_, extension)| extension.to_lowercase() == value.to_lowercase());
    }
    tags::string_value(tags, tag).is_some_and(|text| text.to_lowercase() == value.to_lowercase())
}

fn matches_number(tag: u8, comparison: Comparison, wanted: u64, size: u64, tags: &[Tag]) -> bool {
    let value = if tag == file_tag::FILESIZE { Some(size) } else { tags::int_value(tags, tag) };
    let Some(value) = value else {
        return false;
    };
    match comparison {
        Comparison::Equal => value == wanted,
        Comparison::Greater => value > wanted,
        Comparison::Less => value < wanted,
        Comparison::GreaterEqual => value >= wanted,
        Comparison::LessEqual => value <= wanted,
        Comparison::NotEqual => value != wanted,
    }
}
