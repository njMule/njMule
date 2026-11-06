// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Rates search results for spam, from what the files marked as spam had (hash, name, a similar name, a similar size,
//! the servers and sources that gave them) and how many spam results each server gives over UDP; kept in
//! `SearchSpam.met`. The ratings add up: [`SPAM_THRESHOLD`](crate::api::search_result::SPAM_THRESHOLD) and more is
//! spam.

use std::io::{self, ErrorKind};
use std::path::Path;

use indexmap::{IndexMap, IndexSet};
use time::OffsetDateTime;

use crate::api::search_result::{Origin, SPAM_THRESHOLD, SearchResult};
use crate::api::spam_marks::{self, SpamMarks};
use crate::files::file_types;
use crate::persist::atomic_files;
use crate::persist::format::search_files;
use crate::protocol::io::{PacketWriter, StringEncoding};
use crate::protocol::tag::{Ed2kTagFormat, Tag, TagName, tag_codec};
use crate::search::levenshtein;
use crate::util::{ipv4, white_space};

pub const FILE_NAME: &str = "SearchSpam.met";

// The ratings each hit adds.
pub const HASH_HIT: u32 = 100;
pub const FULL_NAME_HIT: u32 = 80;
pub const SHORT_FULL_NAME_HIT: u32 = 50;
pub const BARE_NAME_HIT: u32 = 60;
pub const SHORT_BARE_NAME_HIT: u32 = 40;
pub const NEAR_NAME_HIT: u32 = 50;
pub const FAR_NAME_HIT: u32 = 40;
pub const SIZE_HIT: u32 = 10;
pub const ONLY_SERVER_HIT: u32 = 21;
pub const ONLY_SERVER_OF_MANY_HIT: u32 = 15;
pub const ONE_SERVER_OF_SEVERAL_HIT: u32 = 10;
pub const ONLY_SPAM_SERVERS_HIT: u32 = 30;
pub const SOURCE_HIT: u32 = 39;
pub const POPULAR_HIT: u32 = 39;
pub const POPULAR_SMALL_PROGRAM_HIT: u32 = 60;

/// Names this long or shorter are common: a match counts for less.
pub const SHORT_NAME: usize = spam_marks::MAX_COMMON_NAME;

// A server whose UDP answers are this share of spam or more is a spam server.
const SPAM_SERVER_PERCENT: u64 = 50;
// Up to this many spam servers known, a file only one of them gives rates higher.
const FEW_SPAM_SERVERS: usize = 10;
// Similar sizes: within 5 % and 5 MiB.
const SIZE_SPREAD: u64 = 5 << 20;
const SIZE_PERCENT: u64 = 5;
// A popular result: more sources than this from spam servers only.
const POPULAR: u32 = 100;
// Programs and archives of this size, small and popular, are the usual fakes.
const SMALL_PROGRAM_MIN: u64 = 100 * 1024;
const SMALL_PROGRAM_MAX: u64 = 10 << 20;
// Markers kept of each kind: the oldest go first.
const MAX_MARKERS: usize = 4096;
const UDP_RECORD_BYTES: usize = 12;

/// How many results a server gave over UDP, and how many of them were spam.
#[derive(Debug, Clone, Copy)]
struct ServerRecord {
    results: u64,
    spam: u64,
}

impl ServerRecord {
    fn is_spam_server(self) -> bool {
        self.results > 0 && self.spam.saturating_mul(100) >= self.results.saturating_mul(SPAM_SERVER_PERCENT)
    }
}

/// The markers learned, oldest first.
#[derive(Debug, Default)]
pub struct SpamFilter {
    spam: IndexSet<[u8; 16]>,
    not_spam: IndexSet<[u8; 16]>,
    // By lower case name, the name as written.
    names: IndexMap<String, String>,
    bare_names: IndexSet<String>,
    sizes: IndexSet<u64>,
    source_ips: IndexSet<u32>,
    server_ips: IndexSet<u32>,
    servers: IndexMap<u32, ServerRecord>,
    others: Vec<Tag>,
}

impl SpamFilter {
    /// Reads `file`, or its backup; a missing file gives no markers. When neither can be decoded, they are set aside as
    /// `<name>.corrupt-<time>`, so that saving does not overwrite them, and there are no markers.
    pub fn load(file: &Path) -> io::Result<Self> {
        let mut filter = Self::default();
        let file = std::path::absolute(file)?;
        let bak = atomic_files::bak_path(&file);
        if !file.exists() && !bak.exists() {
            return Ok(filter);
        }
        let read = atomic_files::read(&file, |data| {
            search_files::read_spam(data).map_err(|e| io::Error::new(ErrorKind::InvalidData, e))
        });
        let tags = match read {
            Ok(tags) => tags,
            Err(e) if e.kind() == ErrorKind::InvalidData => {
                tracing::warn!("{} is damaged: set aside, no spam markers: {e}", file.display());
                let suffix = corrupt_suffix(OffsetDateTime::now_utc());
                set_aside(&file, &suffix);
                set_aside(&bak, &suffix);
                return Ok(filter);
            }
            Err(e) => return Err(e),
        };
        for tag in tags {
            filter.take(tag);
        }
        Ok(filter)
    }

    // IPs travel as the eD2K form of the address.
    fn take(&mut self, tag: Tag) {
        if !writable(&tag) {
            tracing::debug!("Dropping the spam marker {}, which the file cannot hold", tag.name());
            return;
        }
        match (tag.name().as_id(), &tag) {
            (Some(search_files::SPAM_HASH), Tag::Hash { value, .. }) => {
                self.spam.insert(*value);
            }
            (Some(search_files::NOT_SPAM_HASH), Tag::Hash { value, .. }) => {
                self.not_spam.insert(*value);
            }
            (Some(search_files::SPAM_FULL_NAME), Tag::String { value, .. }) => {
                self.names.insert(value.to_lowercase(), value.clone());
            }
            (Some(search_files::SPAM_SIMILAR_NAME), Tag::String { value, .. })
                if !white_space::strip(value).is_empty() =>
            {
                self.bare_names.insert(value.clone());
            }
            (Some(search_files::SPAM_SIZE), Tag::Int { value, .. }) if *value > 0 => {
                self.sizes.insert(*value);
            }
            (Some(search_files::SPAM_SOURCE_IP), Tag::Int { value, .. }) => {
                self.source_ips.insert(ipv4::from_ed2k(low_bits(*value)));
            }
            (Some(search_files::SPAM_SERVER_IP), Tag::Int { value, .. }) => {
                self.server_ips.insert(ipv4::from_ed2k(low_bits(*value)));
            }
            (Some(search_files::SPAM_UDP_SERVER_RATIO), Tag::Blob { value, .. }) if value.len() == UDP_RECORD_BYTES => {
                let field = |at: usize| {
                    value
                        .get(at..at.saturating_add(4))
                        .and_then(|bytes| <[u8; 4]>::try_from(bytes).ok())
                        .map_or(0, u32::from_le_bytes)
                };
                let results = u64::from(field(4));
                let spam = u64::from(field(8)).min(results);
                self.servers.insert(ipv4::from_ed2k(field(0)), ServerRecord { results, spam });
            }
            _ => self.others.push(tag),
        }
    }

    pub fn save(&self, file: &Path) -> io::Result<()> {
        let mut markers = Vec::new();
        markers.extend(self.names.values().map(|name| string(search_files::SPAM_FULL_NAME, name)));
        markers.extend(self.bare_names.iter().map(|name| string(search_files::SPAM_SIMILAR_NAME, name)));
        markers.extend(self.sizes.iter().map(|size| int(search_files::SPAM_SIZE, *size)));
        markers.extend(self.spam.iter().map(|hash| hash_tag(search_files::SPAM_HASH, *hash)));
        markers.extend(self.not_spam.iter().map(|hash| hash_tag(search_files::NOT_SPAM_HASH, *hash)));
        markers.extend(self.server_ips.iter().map(|ip| ip_tag(search_files::SPAM_SERVER_IP, *ip)));
        markers.extend(self.source_ips.iter().map(|ip| ip_tag(search_files::SPAM_SOURCE_IP, *ip)));
        for (ip, record) in &self.servers {
            let mut value = Vec::with_capacity(UDP_RECORD_BYTES);
            value.extend(ipv4::to_ed2k(*ip).to_le_bytes());
            value.extend(clamped(record.results).to_le_bytes());
            value.extend(clamped(record.spam).to_le_bytes());
            markers.push(Tag::Blob { name: TagName::id(search_files::SPAM_UDP_SERVER_RATIO), value });
        }
        markers.extend(self.others.iter().cloned());
        markers.retain(writable);
        let data = search_files::write_spam(&markers).map_err(|e| io::Error::new(ErrorKind::InvalidInput, e))?;
        atomic_files::write(file, &data)
    }

    /// Learns what `result` has, to rate other results like it as spam; or, not spam, forgets what would rate it so.
    /// The UDP servers that gave it count one spam result more, or less.
    pub fn mark(&mut self, result: &SearchResult, is_spam: bool) {
        let was_spam = self.rate(result) >= SPAM_THRESHOLD;
        let origin = &result.origin;
        let lower = result.name.to_lowercase();
        if is_spam {
            self.not_spam.shift_remove(&result.hash);
            add(&mut self.spam, result.hash);
            self.names.insert(lower, result.name.clone());
            trim(&mut self.names);
            if !origin.bare_name.is_empty() {
                add(&mut self.bare_names, origin.bare_name.clone());
            }
            add(&mut self.sizes, result.size);
            for ip in &origin.source_ips {
                add(&mut self.source_ips, *ip);
            }
            for ip in &origin.udp_servers {
                add(&mut self.server_ips, *ip);
            }
        } else {
            self.spam.shift_remove(&result.hash);
            add(&mut self.not_spam, result.hash);
            self.names.shift_remove(&lower);
            self.bare_names.retain(|bare| bare_name_rating(&origin.bare_name, bare) == 0);
            self.sizes.retain(|size| !similar_size(result.size, *size));
            self.source_ips.retain(|ip| !origin.source_ips.contains(ip));
            self.server_ips.retain(|ip| !origin.udp_servers.contains(ip));
        }
        if was_spam != is_spam {
            for ip in &origin.udp_servers {
                if let Some(record) = self.servers.get_mut(ip) {
                    let count = if is_spam { record.spam.saturating_add(1) } else { record.spam.saturating_sub(1) };
                    record.spam = count.min(record.results);
                }
            }
        }
    }

    /// The server at `ip` gave `results` over UDP, `spam` of them rated as spam.
    pub fn answered(&mut self, ip: u32, results: u32, spam: u32) {
        let record = self.servers.shift_remove(&ip).unwrap_or(ServerRecord { results: 0, spam: 0 });
        let all = record.results.saturating_add(u64::from(results));
        let spam_ones = record.spam.saturating_add(u64::from(spam));
        self.servers.insert(ip, ServerRecord { results: all, spam: spam_ones.min(all) });
        trim(&mut self.servers);
    }

    /// How much `result` looks like the files marked as spam; 0 for a file cleared again.
    pub fn rate(&self, result: &SearchResult) -> u32 {
        if self.not_spam.contains(&result.hash) {
            return 0;
        }
        let origin = &result.origin;
        let mut rating = if self.spam.contains(&result.hash) { HASH_HIT } else { 0 };
        rating = rating.saturating_add(self.name_rating(result));
        if self.sizes.iter().any(|size| similar_size(result.size, *size)) {
            rating = rating.saturating_add(SIZE_HIT);
        }
        rating = rating.saturating_add(self.server_rating(origin));
        let only_spam_servers = !origin.direct
            && !origin.udp_servers.is_empty()
            && origin.udp_servers.iter().all(|ip| self.is_spam_server(*ip));
        if only_spam_servers {
            rating = rating.saturating_add(ONLY_SPAM_SERVERS_HIT);
            if result.sources > POPULAR {
                let hit = if small_program(result) || source_near_server(origin) {
                    POPULAR_SMALL_PROGRAM_HIT
                } else {
                    POPULAR_HIT
                };
                rating = rating.saturating_add(hit);
            }
        }
        if origin.source_ips.iter().any(|ip| self.source_ips.contains(ip)) {
            rating = rating.saturating_add(SOURCE_HIT);
        }
        rating
    }

    pub fn marks(&self) -> SpamMarks {
        SpamMarks {
            spam: self.spam.iter().copied().collect(),
            not_spam: self.not_spam.iter().copied().collect(),
            names: self.names.keys().cloned().collect(),
        }
    }

    // The same name as a spam file, or one like it once the words searched for are left out.
    fn name_rating(&self, result: &SearchResult) -> u32 {
        let name = result.name.to_lowercase();
        if self.names.contains_key(&name) {
            return if length(&name) <= SHORT_NAME { SHORT_FULL_NAME_HIT } else { FULL_NAME_HIT };
        }
        let bare = &result.origin.bare_name;
        let mut best = 0;
        for marked in &self.bare_names {
            let rating = bare_name_rating(bare, marked);
            if rating >= BARE_NAME_HIT || (rating == SHORT_BARE_NAME_HIT && bare == marked) {
                return rating;
            }
            best = best.max(rating);
        }
        best
    }

    // A server that gave spam before: more when it is the result's only server and few such servers are known.
    fn server_rating(&self, origin: &Origin) -> u32 {
        if !origin.udp_servers.iter().any(|ip| self.server_ips.contains(ip)) {
            return 0;
        }
        let servers = origin.udp_servers.len().saturating_add(usize::from(origin.direct));
        if servers > 1 {
            return ONE_SERVER_OF_SEVERAL_HIT;
        }
        if self.server_ips.len() <= FEW_SPAM_SERVERS { ONLY_SERVER_HIT } else { ONLY_SERVER_OF_MANY_HIT }
    }

    // A server never heard over UDP is no spam server: no record is no proof.
    fn is_spam_server(&self, ip: u32) -> bool {
        self.servers.get(&ip).is_some_and(|record| record.is_spam_server())
    }
}

// Equal, or close for names long enough: the fewer edits for the length, the closer.
fn bare_name_rating(bare: &str, marked: &str) -> u32 {
    if bare.is_empty() {
        return 0;
    }
    let size = length(bare);
    if bare == marked {
        return if size <= SHORT_NAME { SHORT_BARE_NAME_HIT } else { BARE_NAME_HIT };
    }
    let apart = size.abs_diff(length(marked));
    if size <= SHORT_NAME || size.checked_div(apart).is_some_and(|ratio| ratio < 3) {
        return 0;
    }
    let Some(closeness) = size.checked_div(levenshtein::distance(bare, marked)) else {
        return 0;
    };
    if closeness >= 6 {
        NEAR_NAME_HIT
    } else if closeness >= 3 {
        FAR_NAME_HIT
    } else {
        0
    }
}

// Lengths as the Java engine counts them, in UTF-16 units.
fn length(text: &str) -> usize {
    text.encode_utf16().count()
}

fn similar_size(size: u64, marked: u64) -> bool {
    let apart = size.abs_diff(marked);
    size > 0 && apart < SIZE_SPREAD && apart.saturating_mul(100).checked_div(size).is_some_and(|p| p < SIZE_PERCENT)
}

fn small_program(result: &SearchResult) -> bool {
    matches!(file_types::of(&result.name), Some(file_types::PROGRAM | file_types::ARCHIVE))
        && result.size > SMALL_PROGRAM_MIN
        && result.size < SMALL_PROGRAM_MAX
}

// A server that offers its own files: a source in its /24.
fn source_near_server(origin: &Origin) -> bool {
    origin.udp_servers.iter().any(|server| origin.source_ips.iter().any(|source| server >> 8 == source >> 8))
}

fn add<T: std::hash::Hash + Eq>(markers: &mut IndexSet<T>, marker: T) {
    markers.shift_remove(&marker);
    markers.insert(marker);
    while markers.len() > MAX_MARKERS {
        markers.shift_remove_index(0);
    }
}

fn trim<K: std::hash::Hash + Eq, V>(markers: &mut IndexMap<K, V>) {
    while markers.len() > MAX_MARKERS {
        markers.shift_remove_index(0);
    }
}

// Short blobs and strings too long for their length field cannot be written as eD2K tags.
fn writable(tag: &Tag) -> bool {
    tag_codec::write_ed2k(&mut PacketWriter::new(), tag, Ed2kTagFormat::New, StringEncoding::Utf8BomIfNeeded).is_ok()
}

fn set_aside(file: &Path, suffix: &str) {
    if !file.exists() {
        return;
    }
    let mut name = file.file_name().unwrap_or_default().to_os_string();
    name.push(suffix);
    if let Err(e) = std::fs::rename(file, file.with_file_name(name)) {
        tracing::warn!("Cannot set {} aside: {e}", file.display());
    }
}

fn corrupt_suffix(at: OffsetDateTime) -> String {
    format!(
        ".corrupt-{:04}{:02}{:02}T{:02}{:02}{:02}Z",
        at.year(),
        u8::from(at.month()),
        at.day(),
        at.hour(),
        at.minute(),
        at.second()
    )
}

// An address field holds 32 bits; a wider number keeps its low ones, as the Java engine reads it.
fn low_bits(value: u64) -> u32 {
    u32::try_from(value & u64::from(u32::MAX)).unwrap_or_default()
}

fn clamped(count: u64) -> u32 {
    u32::try_from(count).unwrap_or(u32::MAX)
}

fn string(id: u8, value: &str) -> Tag {
    Tag::String { name: TagName::id(id), value: value.to_owned() }
}

fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

fn hash_tag(id: u8, value: [u8; 16]) -> Tag {
    Tag::Hash { name: TagName::id(id), value }
}

fn ip_tag(id: u8, ip: u32) -> Tag {
    int(id, u64::from(ipv4::to_ed2k(ip)))
}
