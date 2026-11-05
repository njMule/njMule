// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! eD2K links (`ed2k://|file|…|/`, `|server|`, `|serverlist|`, `|nodeslist|`, `|search|`) and the eD2K form of
//! magnet links. Parsing is strict about the essentials (name, size, hash) and lenient about optional parts, which are
//! dropped when invalid.

use std::fmt;

use data_encoding::{BASE32_NOPAD, HEXUPPER};

use crate::api::ed2k_link_codec;
use crate::util::white_space;

/// Largest file size a link may announce (2^38 bytes).
pub const MAX_FILE_SIZE: u64 = 1 << 38;
/// Most sources taken from one link.
pub const MAX_SOURCES: usize = 32_767;

/// Why a text is no link.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InvalidLink(String);

impl InvalidLink {
    pub fn new(reason: impl Into<String>) -> Self {
        Self(reason.into())
    }
}

impl fmt::Display for InvalidLink {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for InvalidLink {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Ed2kLink {
    File(FileLink),
    Server(ServerLink),
    ServerList(ServerListLink),
    NodesList(NodesListLink),
    Search(SearchLink),
}

impl Ed2kLink {
    /// Parses a link, or tells why it is none.
    pub fn parse(text: &str) -> Result<Self, InvalidLink> {
        ed2k_link_codec::parse(text)
    }

    pub fn try_parse(text: &str) -> Option<Self> {
        Self::parse(text).ok()
    }

    pub fn to_uri(&self) -> String {
        match self {
            Ed2kLink::File(link) => link.to_uri(),
            Ed2kLink::Server(link) => format!("ed2k://|server|{}|{}|/", link.host, link.port),
            Ed2kLink::ServerList(link) => format!("ed2k://|serverlist|{}|/", link.url),
            Ed2kLink::NodesList(link) => format!("ed2k://|nodeslist|{}|/", link.url),
            Ed2kLink::Search(link) => format!("ed2k://|search|{}|/", ed2k_link_codec::encode(&link.term)),
        }
    }
}

/// A source given in a link: an IPv4 address, an IPv6 address (without brackets) or a host name, and a TCP port.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Source {
    pub host: String,
    pub port: u16,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileLink {
    pub name: String,
    pub size: u64,
    pub hash: [u8; 16],
    pub aich_hash: Option<[u8; 20]>,
    pub part_hashes: Vec<[u8; 16]>,
    pub sources: Vec<Source>,
    pub http_sources: Vec<String>,
}

impl FileLink {
    /// The link, if its name is not blank and its size within 1 and [`MAX_FILE_SIZE`].
    pub fn new(
        name: String,
        size: u64,
        hash: [u8; 16],
        aich_hash: Option<[u8; 20]>,
        part_hashes: Vec<[u8; 16]>,
        sources: Vec<Source>,
        http_sources: Vec<String>,
    ) -> Result<Self, InvalidLink> {
        if name.chars().all(white_space::is_white_space) {
            return Err(InvalidLink::new("a file link needs a name"));
        }
        if !(1..=MAX_FILE_SIZE).contains(&size) {
            return Err(InvalidLink::new(format!("file size out of range: {size}")));
        }
        Ok(Self { name, size, hash, aich_hash, part_hashes, sources, http_sources })
    }

    pub fn of(name: &str, size: u64, hash: [u8; 16]) -> Result<Self, InvalidLink> {
        Self::new(name.to_owned(), size, hash, None, Vec::new(), Vec::new(), Vec::new())
    }

    /// The same link under another name.
    pub fn with_name(&self, other: &str) -> Result<Self, InvalidLink> {
        Self::new(
            other.to_owned(),
            self.size,
            self.hash,
            self.aich_hash,
            self.part_hashes.clone(),
            self.sources.clone(),
            self.http_sources.clone(),
        )
    }

    pub fn to_uri(&self) -> String {
        let mut out = format!(
            "ed2k://|file|{}|{}|{}|",
            ed2k_link_codec::encode(&self.name),
            self.size,
            HEXUPPER.encode(&self.hash)
        );
        if let Some(aich) = &self.aich_hash {
            out.push_str(&format!("h={}|", BASE32_NOPAD.encode(aich)));
        }
        if !self.part_hashes.is_empty() {
            let parts: Vec<String> = self.part_hashes.iter().map(|part| HEXUPPER.encode(part)).collect();
            out.push_str(&format!("p={}|", parts.join(":")));
        }
        out.push('/');
        if !self.sources.is_empty() {
            out.push_str("|sources");
            for source in &self.sources {
                // An IPv6 address goes in brackets, so that its colons are told from the port's.
                if source.host.contains(':') {
                    out.push_str(&format!(",[{}]:{}", source.host, source.port));
                } else {
                    out.push_str(&format!(",{}:{}", source.host, source.port));
                }
            }
            out.push_str("|/");
        }
        out
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServerLink {
    pub host: String,
    pub port: u16,
}

impl ServerLink {
    pub fn new(host: &str, port: u16) -> Result<Self, InvalidLink> {
        if host.is_empty() || port == 0 {
            return Err(InvalidLink::new(format!("invalid server {host}:{port}")));
        }
        Ok(Self { host: host.to_owned(), port })
    }
}

/// A link to a server.met file to download.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServerListLink {
    pub url: String,
}

impl ServerListLink {
    pub fn new(url: &str) -> Result<Self, InvalidLink> {
        ed2k_link_codec::check_url(url).map(|()| Self { url: url.to_owned() })
    }
}

/// A link to a Kad nodes.dat file to download.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NodesListLink {
    pub url: String,
}

impl NodesListLink {
    pub fn new(url: &str) -> Result<Self, InvalidLink> {
        ed2k_link_codec::check_url(url).map(|()| Self { url: url.to_owned() })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchLink {
    pub term: String,
}

impl SearchLink {
    pub fn new(term: &str) -> Result<Self, InvalidLink> {
        if term.chars().all(white_space::is_white_space) {
            return Err(InvalidLink::new("empty search term"));
        }
        Ok(Self { term: term.to_owned() })
    }
}
