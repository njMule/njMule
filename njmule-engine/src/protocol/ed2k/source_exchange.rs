// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Source exchange, second form: clients ask each other for more sources of a file. Answers of version 1 and 2 carry
//! eD2K ids, versions 3 and 4 the address in host order; version 2 adds the user hash and version 4 the connect
//! options. Between clients that both announce it, the extended form of version 1 gives each source tags instead, its
//! IPv6 address among them, but no user hash.

use std::net::Ipv6Addr;

use crate::protocol::ed2k::hello::NO_IPV4;
use crate::protocol::ed2k::server_messages::LOW_ID_LIMIT;
use crate::protocol::io::{DecodeError, EncodeError, PacketWriter, StringEncoding};
use crate::protocol::packet::opcodes::extended;
use crate::protocol::packet::{Packet, protocol};
use crate::protocol::tag::tag_codec::{read_ed2k_list, write_ed2k_list};
use crate::protocol::tag::{Ed2kTagFormat, Tag, TagName, client_tag, tags};
use crate::util::{ipv4, ipv6};

/// The highest version we speak.
pub const VERSION: u8 = 4;
/// The version of the extended form.
pub const EXTENDED_VERSION: u8 = 1;
/// Sources per answer at most.
pub const MAX_SOURCES: usize = 501;

// Tags of one source of an extended answer at most: a handful are defined.
const MAX_SOURCE_TAGS: usize = 16;

/// A source; `ip` is in host order, zero for a LowID with `low_id` set or, with [`NO_IPV4`], for a source at its IPv6
/// address alone.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Source {
    pub ip: u32,
    pub low_id: u32,
    pub port: u16,
    pub server_ip: u32,
    pub server_port: u16,
    pub user_hash: Option<[u8; 16]>,
    pub crypt_options: u8,
    pub ipv6: Option<Ipv6Addr>,
}

impl Source {
    /// A source reached at its IPv6 address alone.
    pub fn ipv6_only(address: Ipv6Addr, port: u16, user_hash: Option<[u8; 16]>) -> Self {
        Self {
            ip: 0,
            low_id: NO_IPV4,
            port,
            server_ip: 0,
            server_port: 0,
            user_hash,
            crypt_options: 0,
            ipv6: Some(address),
        }
    }

    pub fn is_low_id(&self) -> bool {
        self.ip == 0 && self.low_id != NO_IPV4
    }

    pub fn is_ipv6_only(&self) -> bool {
        self.ip == 0 && self.low_id == NO_IPV4
    }
}

/// An answer with the sources of `file`, in a version from 1 to [`VERSION`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Answer {
    pub version: u8,
    pub file: [u8; 16],
    pub sources: Vec<Source>,
}

impl Answer {
    /// The answer of its version; sources at an IPv6 address alone are left out.
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        if !(1..=VERSION).contains(&self.version) {
            return Err(EncodeError::new(format!("source exchange version {}", self.version)));
        }
        let sent: Vec<&Source> = self.sources.iter().filter(|s| !s.is_ipv6_only()).collect();
        let mut out = PacketWriter::new();
        out.u8(self.version).hash16(&self.file).count16(at_most(sent.len())?)?;
        for s in sent {
            let id = if s.is_low_id() {
                s.low_id
            } else if self.version >= 3 {
                s.ip
            } else {
                ipv4::to_ed2k(s.ip)
            };
            out.u32(id).u16(s.port).u32(ipv4::to_ed2k(s.server_ip)).u16(s.server_port);
            if self.version >= 2 {
                out.hash16(&s.user_hash.unwrap_or_default());
            }
            if self.version >= 4 {
                out.u8(s.crypt_options);
            }
        }
        Ok(Packet::written(protocol::EMULE, extended::ANSWERSOURCES2, out))
    }

    /// The extended answer: per source its eD2K id, port and tags, for its server and its IPv6 address.
    pub fn encode_extended(&self) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        out.u8(EXTENDED_VERSION).hash16(&self.file).count16(at_most(self.sources.len())?)?;
        for s in &self.sources {
            let id = if s.is_ipv6_only() || s.is_low_id() { s.low_id } else { ipv4::to_ed2k(s.ip) };
            out.u32(id).u16(s.port);
            let mut tags = Vec::with_capacity(3);
            if s.server_ip != 0 {
                tags.push(int(client_tag::EMULE_SERVERIP, u64::from(ipv4::to_ed2k(s.server_ip))));
                tags.push(int(client_tag::EMULE_SERVERTCP, u64::from(s.server_port)));
            }
            if let Some(address) = s.ipv6 {
                tags.push(Tag::Hash { name: TagName::id(client_tag::MOD_IP_V6), value: address.octets() });
            }
            out.count8(tags.len())?;
            write_ed2k_list(&mut out, &tags, Ed2kTagFormat::New, StringEncoding::Utf8)?;
        }
        Ok(Packet::written(protocol::EMULE, extended::ANSWERSOURCES2, out))
    }

    /// Decodes an extended answer; tags it does not know are skipped.
    pub fn decode_extended(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let version = input.u8()?;
        if version != EXTENDED_VERSION {
            return Err(DecodeError::new(format!("extended source exchange version {version}")));
        }
        let file = input.hash16()?;
        let count = usize::from(input.u16()?);
        if count > MAX_SOURCES {
            return Err(DecodeError::new(format!("{count} sources in one answer")));
        }
        let mut sources = Vec::with_capacity(count);
        for _ in 0..count {
            let id = input.u32()?;
            let port = input.u16()?;
            let tag_count = input.u8()?;
            let tags = read_ed2k_list(&mut input, u64::from(tag_count), MAX_SOURCE_TAGS)?;
            let server_ip = ipv4::from_ed2k(low32(tags::int_value(&tags, client_tag::EMULE_SERVERIP).unwrap_or(0)));
            let server_port = low16(tags::int_value(&tags, client_tag::EMULE_SERVERTCP).unwrap_or(0));
            let ipv6 = tags::hash_value(&tags, client_tag::MOD_IP_V6).and_then(|hash| ipv6::public_of(&hash));
            let no_ipv4 = id == NO_IPV4 || id < LOW_ID_LIMIT;
            sources.push(Source {
                ip: if no_ipv4 { 0 } else { ipv4::from_ed2k(id) },
                low_id: if no_ipv4 { id } else { 0 },
                port,
                server_ip,
                server_port,
                user_hash: None,
                crypt_options: 0,
                ipv6,
            });
        }
        Ok(Self { version: EXTENDED_VERSION, file, sources })
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let version = input.u8()?;
        if !(1..=VERSION).contains(&version) {
            return Err(DecodeError::new(format!("source exchange version {version}")));
        }
        let file = input.hash16()?;
        let count = usize::from(input.u16()?);
        if count > MAX_SOURCES || count.checked_mul(entry_size(version)) != Some(input.remaining()) {
            return Err(DecodeError::new(format!("{count} sources do not fill the answer")));
        }
        let mut sources = Vec::with_capacity(count);
        for _ in 0..count {
            let mut id = input.u32()?;
            let port = input.u16()?;
            let server_ip = ipv4::from_ed2k(input.u32()?);
            let server_port = input.u16()?;
            let user_hash = if version >= 2 { Some(input.hash16()?) } else { None };
            let crypt_options = if version >= 4 { input.u8()? } else { 0 };
            let mut low = id < LOW_ID_LIMIT;
            // Some clients send a LowID in versions 3 and 4 with its bytes swapped, which reads as an address ending
            // in .0, where no client is reachable.
            if !low && version >= 3 && id & 0xFF == 0 {
                low = true;
                id = id.swap_bytes();
            }
            let ip = match (low, version >= 3) {
                (true, _) => 0,
                (false, true) => id,
                (false, false) => ipv4::from_ed2k(id),
            };
            sources.push(Source {
                ip,
                low_id: if low { id } else { 0 },
                port,
                server_ip,
                server_port,
                user_hash,
                crypt_options,
                ipv6: None,
            });
        }
        Ok(Self { version, file, sources })
    }
}

/// OP_REQUESTSOURCES2: the version we take, no options, then the file.
pub fn request(file: &[u8; 16]) -> Packet {
    request_in_version(file, VERSION)
}

/// As [`request`], for answers of `version`: [`EXTENDED_VERSION`] asks for the extended form.
pub fn request_in_version(file: &[u8; 16], version: u8) -> Packet {
    let mut out = PacketWriter::new();
    out.u8(version).u16(0).hash16(file);
    Packet::written(protocol::EMULE, extended::REQUESTSOURCES2, out)
}

/// A request: the version the asker takes, and the file.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Request {
    pub version: u8,
    pub file: [u8; 16],
}

impl Request {
    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let version = input.u8()?;
        input.u16()?;
        Ok(Self { version, file: input.hash16()? })
    }
}

fn at_most(count: usize) -> Result<usize, EncodeError> {
    if count > MAX_SOURCES {
        return Err(EncodeError::new(format!("{count} sources exceed the {MAX_SOURCES} of an answer")));
    }
    Ok(count)
}

fn entry_size(version: u8) -> usize {
    match version {
        1 => 12,
        2 | 3 => 28,
        _ => 29,
    }
}

fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

// A field of 32 or 16 bits keeps the low half of a wider tag.
fn low32(value: u64) -> u32 {
    u32::try_from(value & u64::from(u32::MAX)).unwrap_or(u32::MAX)
}

fn low16(value: u64) -> u16 {
    u16::try_from(value & u64::from(u16::MAX)).unwrap_or(u16::MAX)
}
