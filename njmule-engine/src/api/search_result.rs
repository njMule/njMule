// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A file a search found, as merged from the answers that named it.

use std::collections::BTreeSet;

use crate::api::ed2k_link::{FileLink, InvalidLink};
use crate::files::hash::ed2k;

/// A rating from which a result counts as spam.
pub const SPAM_THRESHOLD: u32 = 60;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchResult {
    pub hash: [u8; 16],
    /// The name most sources give.
    pub name: String,
    pub size: u64,
    pub sources: u32,
    /// None when the answer did not tell it.
    pub complete_sources: Option<u32>,
    /// The eD2K type, e.g. `Video`.
    pub file_type: Option<String>,
    /// What the file's publishers told of its content.
    pub media: Media,
    /// Where it came from, as its spam rating weighs it.
    pub origin: Origin,
    pub spam_rating: u32,
    pub aich_hash: Option<[u8; 20]>,
    /// The names the file was found under, when more than one.
    pub names: Vec<Name>,
}

/// A name of a result with the sources that give it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Name {
    pub name: String,
    pub sources: u32,
    pub complete_sources: Option<u32>,
}

impl SearchResult {
    /// A result found under one name, of no known origin or media, not rated for spam.
    pub fn new(
        hash: [u8; 16],
        name: &str,
        size: u64,
        sources: u32,
        complete_sources: Option<u32>,
        file_type: Option<&str>,
    ) -> Self {
        Self {
            hash,
            name: name.to_owned(),
            size,
            sources,
            complete_sources,
            file_type: file_type.map(str::to_owned),
            media: Media::default(),
            origin: Origin::default(),
            spam_rating: 0,
            aich_hash: None,
            names: Vec::new(),
        }
    }

    pub fn is_spam(&self) -> bool {
        self.spam_rating >= SPAM_THRESHOLD
    }

    /// The ed2k link of the file; an error for a name or size that cannot be downloaded.
    pub fn link(&self) -> Result<FileLink, InvalidLink> {
        FileLink::new(self.name.clone(), self.size, self.hash, self.aich_hash, Vec::new(), Vec::new(), Vec::new())
    }

    /// The file as found under `other` alone, with the sources that gave that name.
    pub fn named(&self, other: &Name) -> Self {
        Self {
            name: other.name.clone(),
            sources: other.sources,
            complete_sources: other.complete_sources,
            names: Vec::new(),
            ..self.clone()
        }
    }
}

/// Where a result came from: the servers that gave it over UDP, whether it also came from the connected server or from
/// Kad, the addresses of the sources the answers named, and the name without the words searched for, in lower case
/// with single spaces. Addresses are numbers, as [`crate::util::ipv4`] holds them.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Origin {
    pub udp_servers: BTreeSet<u32>,
    pub direct: bool,
    pub source_ips: BTreeSet<u32>,
    pub bare_name: String,
}

impl Origin {
    /// Servers and sources kept for one result: what a spammer adds beyond them changes nothing.
    pub const MAX_ADDRESSES: usize = 64;

    pub fn new(
        udp_servers: impl IntoIterator<Item = u32>,
        direct: bool,
        source_ips: impl IntoIterator<Item = u32>,
        bare_name: &str,
    ) -> Self {
        Self {
            udp_servers: capped(udp_servers),
            direct,
            source_ips: capped(source_ips),
            bare_name: bare_name.to_owned(),
        }
    }

    /// Both origins of a result, as its answers came.
    pub fn and(&self, other: &Self) -> Self {
        Self::new(
            self.udp_servers.iter().chain(&other.udp_servers).copied(),
            self.direct || other.direct,
            self.source_ips.iter().chain(&other.source_ips).copied(),
            if self.bare_name.is_empty() { &other.bare_name } else { &self.bare_name },
        )
    }
}

// The lowest addresses as signed numbers, which the Java engine keeps of a longer list.
fn capped(addresses: impl IntoIterator<Item = u32>) -> BTreeSet<u32> {
    let all: BTreeSet<u32> = addresses.into_iter().collect();
    if all.len() <= Origin::MAX_ADDRESSES {
        return all;
    }
    let mut signed: Vec<i32> = all.into_iter().map(u32::cast_signed).collect();
    signed.sort_unstable();
    signed.into_iter().take(Origin::MAX_ADDRESSES).map(i32::cast_unsigned).collect()
}

/// Whether a file of this name and size can be downloaded: a name, and a size eD2K allows.
pub fn is_downloadable(name: &str, size: u64) -> bool {
    !name.trim().is_empty() && size > 0 && size <= ed2k::MAX_FILE_SIZE
}

/// The media details of a file, as its tags tell them: empty texts and zeros where unknown.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Media {
    pub artist: String,
    pub album: String,
    pub title: String,
    /// Seconds.
    pub length: u32,
    /// Kilobits per second.
    pub bitrate: u32,
    pub codec: String,
}

impl Media {
    /// The longest text kept, in UTF-16 code units as other clients count them.
    pub const MAX_TEXT: usize = 256;

    /// The details, their texts made fit to show: control characters made spaces, cut to [`Self::MAX_TEXT`].
    pub fn new(artist: &str, album: &str, title: &str, length: u32, bitrate: u32, codec: &str) -> Self {
        Self { artist: clean(artist), album: clean(album), title: clean(title), length, bitrate, codec: clean(codec) }
    }

    /// These details, the unknown ones taken from `other`.
    pub fn or(&self, other: &Self) -> Self {
        fn text(mine: &str, theirs: &str) -> String {
            if mine.is_empty() { theirs } else { mine }.to_owned()
        }
        Self {
            artist: text(&self.artist, &other.artist),
            album: text(&self.album, &other.album),
            title: text(&self.title, &other.title),
            length: if self.length == 0 { other.length } else { self.length },
            bitrate: if self.bitrate == 0 { other.bitrate } else { self.bitrate },
            codec: text(&self.codec, &other.codec),
        }
    }
}

fn clean(text: &str) -> String {
    let mut out = String::new();
    let mut units = 0usize;
    for c in crate::util::white_space::strip(text).chars() {
        units = units.saturating_add(c.len_utf16());
        if units > Media::MAX_TEXT {
            break;
        }
        out.push(if c.is_control() { ' ' } else { c });
    }
    crate::util::white_space::strip(&out).to_owned()
}
