// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! UDP messages between a client and eD2K servers (server TCP port + 4, or the server's obfuscated UDP port). Every
//! decoder checks the length before reading, including datagrams too short to hold a header.

use crate::protocol::ed2k::file_entry::{self, FileEntry};
use crate::protocol::ed2k::server_messages::FoundSources;
use crate::protocol::io::{DecodeError, EncodeError, PacketWriter, StringEncoding};
use crate::protocol::packet::opcodes::server_udp;
use crate::protocol::packet::{Packet, protocol};
use crate::protocol::tag::tag_codec::{read_ed2k_list, write_ed2k_list};
use crate::protocol::tag::tag_type::{NEW_ED2K_FLAG, UINT8};
use crate::protocol::tag::{Ed2kTagFormat, Tag, client_tag};
use crate::search::search_expr::SearchExpr;
use crate::search::search_tree_codec;

// Server UDP flags, from the status answer.
pub const UDP_EXT_GETSOURCES: u32 = 0x001;
pub const UDP_EXT_GETFILES: u32 = 0x002;
pub const UDP_NEWTAGS: u32 = 0x008;
pub const UDP_UNICODE: u32 = 0x010;
pub const UDP_EXT_GETSOURCES2: u32 = 0x020;
pub const UDP_LARGEFILES: u32 = 0x100;
pub const UDP_OBFUSCATION: u32 = 0x200;
pub const UDP_TCP_OBFUSCATION: u32 = 0x400;

/// Bytes of a status answer up to the file counts, the shortest one accepted.
pub const MIN_STATUS: usize = 12;
/// Bytes of file ids at most in one source request.
pub const MAX_SOURCE_REQUEST: usize = 510;

// The low half of a description challenge, read first as the length of the old answer's name: no valid length, so
// the answer with the challenge is told from the old one.
const DESCRIPTION_MARK: u32 = 0xF0FF;
const MAX_DESCRIPTION_TAGS: usize = 32;
const HASH_LENGTH: usize = 16;

/// Server load and capabilities; fields a server does not send are 0.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Status {
    pub challenge: u32,
    pub users: u32,
    pub files: u32,
    pub max_users: u32,
    pub soft_files: u32,
    pub hard_files: u32,
    pub udp_flags: u32,
    pub low_id_users: u32,
    pub obfuscated_udp_port: u16,
    pub obfuscated_tcp_port: u16,
    pub udp_key: u32,
}

impl Status {
    pub fn has(&self, flag: u32) -> bool {
        self.udp_flags & flag != 0
    }

    /// The full answer, with every optional field.
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        out.u32(self.challenge)
            .u32(self.users)
            .u32(self.files)
            .u32(self.max_users)
            .u32(self.soft_files)
            .u32(self.hard_files)
            .u32(self.udp_flags)
            .u32(self.low_id_users)
            .u16(self.obfuscated_udp_port)
            .u16(self.obfuscated_tcp_port)
            .u32(self.udp_key);
        Packet::written(protocol::EDONKEY, server_udp::GLOBSERVSTATRES, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        if input.remaining() < MIN_STATUS {
            return Err(DecodeError::new(format!("server status of {} bytes", input.remaining())));
        }
        let challenge = input.u32()?;
        let users = input.u32()?;
        let files = input.u32()?;
        let max_users = if input.remaining() >= 4 { input.u32()? } else { 0 };
        let (soft_files, hard_files) = if input.remaining() >= 8 { (input.u32()?, input.u32()?) } else { (0, 0) };
        let udp_flags = if input.remaining() >= 4 { input.u32()? } else { 0 };
        let low_id_users = if input.remaining() >= 4 { input.u32()? } else { 0 };
        let (obfuscated_udp_port, obfuscated_tcp_port, udp_key) =
            if input.remaining() >= 8 { (input.u16()?, input.u16()?, input.u32()?) } else { (0, 0, 0) };
        Ok(Self {
            challenge,
            users,
            files,
            max_users,
            soft_files,
            hard_files,
            udp_flags,
            low_id_users,
            obfuscated_udp_port,
            obfuscated_tcp_port,
            udp_key,
        })
    }
}

/// A file whose sources a server is asked for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SourceQuery {
    pub file: [u8; 16],
    pub size: u64,
}

/// The source requests for `files` to a server with `udp_flags`, one datagram each: several files per request and
/// their sizes only where the server takes them, files above 4 GB only for a server that takes those. Each request
/// holds at most [`MAX_SOURCE_REQUEST`] bytes of file ids, so that it fits a 576-byte datagram.
pub fn source_requests(files: &[SourceQuery], udp_flags: u32) -> Vec<Packet> {
    let several = udp_flags & UDP_EXT_GETSOURCES != 0;
    let sized = udp_flags & UDP_EXT_GETSOURCES2 != 0;
    let takes_large = sized && udp_flags & UDP_LARGEFILES != 0;
    let opcode = if sized { server_udp::GLOBGETSOURCES2 } else { server_udp::GLOBGETSOURCES };
    let mut requests = Vec::new();
    let mut out = PacketWriter::new();
    for query in files {
        let small = u32::try_from(query.size).ok();
        if small.is_none() && !takes_large {
            continue;
        }
        let bytes = match (small, sized) {
            (None, _) => HASH_LENGTH + 4 + 8,
            (Some(_), true) => HASH_LENGTH + 4,
            (Some(_), false) => HASH_LENGTH,
        };
        if out.size() > 0 && (!several || out.size().saturating_add(bytes) > MAX_SOURCE_REQUEST) {
            requests.push(Packet::written(protocol::EDONKEY, opcode, std::mem::take(&mut out)));
        }
        out.hash16(&query.file);
        match small {
            None => {
                out.u32(0).u64(query.size);
            }
            Some(size) if sized => {
                out.u32(size);
            }
            Some(_) => {}
        }
    }
    if out.size() > 0 {
        requests.push(Packet::written(protocol::EDONKEY, opcode, out));
    }
    requests
}

/// The sources of a source answer. One datagram may carry the sources of several files, each after its own `E3 9B`
/// header; parsing stops at anything else, and a file cut short is dropped with what follows it. Only a first file
/// that cannot be read fails the whole answer.
pub fn decode_found_sources(packet: &Packet) -> Result<Vec<FoundSources>, DecodeError> {
    let mut input = packet.reader();
    let mut found = vec![FoundSources::read(&mut input, false)?];
    // A header and a file id with its source count at least.
    while input.remaining() > 2 + HASH_LENGTH {
        if input.u8()? != protocol::EDONKEY || input.u8()? != server_udp::GLOBFOUNDSOURCES {
            break;
        }
        match FoundSources::read(&mut input, false) {
            Ok(file) => found.push(file),
            Err(_) => break,
        }
    }
    Ok(found)
}

/// A source answer carrying the sources of `found` (at least one file) in one datagram.
pub fn found_sources(found: &[FoundSources]) -> Result<Packet, EncodeError> {
    let (first, rest) =
        found.split_first().ok_or_else(|| EncodeError::new("a source answer carries one file at least"))?;
    let mut out = PacketWriter::new();
    first.write(&mut out, false)?;
    for file in rest {
        out.u8(protocol::EDONKEY).u8(server_udp::GLOBFOUNDSOURCES);
        file.write(&mut out, false)?;
    }
    Ok(Packet::written(protocol::EDONKEY, server_udp::GLOBFOUNDSOURCES, out))
}

/// A server's description: the challenge of the request it answers, and its name, description and version tags.
#[derive(Debug, Clone, PartialEq)]
pub struct Description {
    pub challenge: u32,
    pub tags: Vec<Tag>,
}

impl Description {
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        out.u32(self.challenge).count32(self.tags.len())?;
        write_ed2k_list(&mut out, &self.tags, Ed2kTagFormat::New, StringEncoding::Utf8)?;
        Ok(Packet::written(protocol::EDONKEY, server_udp::SERVER_DESC_RES, out))
    }

    /// Decodes the answer to a request with a challenge; the old answer, which cannot be tied to one, is refused.
    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        if input.remaining() < 8 {
            return Err(DecodeError::new(format!("server description of {} bytes", input.remaining())));
        }
        let challenge = input.u32()?;
        if challenge & 0xFFFF != DESCRIPTION_MARK {
            return Err(DecodeError::new("server description without a challenge"));
        }
        let count = input.u32()?;
        Ok(Self { challenge, tags: read_ed2k_list(&mut input, u64::from(count), MAX_DESCRIPTION_TAGS)? })
    }
}

/// The challenge of a description request, from 16 random bits.
pub fn description_challenge(random: u32) -> u32 {
    (random & 0xFFFF) << 16 | DESCRIPTION_MARK
}

pub fn description_request(challenge: u32) -> Packet {
    let mut out = PacketWriter::new();
    out.u32(challenge);
    Packet::written(protocol::EDONKEY, server_udp::SERVER_DESC_REQ, out)
}

pub fn status_request(challenge: u32) -> Packet {
    let mut out = PacketWriter::new();
    out.u32(challenge);
    Packet::written(protocol::EDONKEY, server_udp::GLOBSERVSTATREQ, out)
}

/// The results of a global search answer. One datagram may carry several results, each after its own `E3 99`
/// header; parsing stops at anything else, and a result cut short is dropped with what follows it. Only a first result
/// that cannot be read fails the whole answer.
pub fn decode_search_results(packet: &Packet) -> Result<Vec<FileEntry>, DecodeError> {
    let mut input = packet.reader();
    let mut files = vec![FileEntry::read(&mut input)?];
    while input.remaining() >= 2 + file_entry::MIN_SIZE {
        if input.u8()? != protocol::EDONKEY || input.u8()? != server_udp::GLOBSEARCHRES {
            break;
        }
        match FileEntry::read(&mut input) {
            Ok(file) => files.push(file),
            Err(_) => break,
        }
    }
    Ok(files)
}

/// A global search answer carrying `files` (at least one) in one datagram.
pub fn search_results(files: &[FileEntry]) -> Result<Packet, EncodeError> {
    let (first, rest) =
        files.split_first().ok_or_else(|| EncodeError::new("a search answer carries one file at least"))?;
    let mut out = PacketWriter::new();
    first.write(&mut out, Ed2kTagFormat::New)?;
    for file in rest {
        out.u8(protocol::EDONKEY).u8(server_udp::GLOBSEARCHRES);
        file.write(&mut out, Ed2kTagFormat::New)?;
    }
    Ok(Packet::written(protocol::EDONKEY, server_udp::GLOBSEARCHRES, out))
}

/// A global search on a server over UDP, in the form its UDP flags take; none when the expression needs 64-bit sizes
/// the server cannot take.
pub fn search_request(expression: &SearchExpr, udp_flags: u32) -> Result<Option<Packet>, EncodeError> {
    let large_files = udp_flags & UDP_LARGEFILES != 0;
    let extended = udp_flags & UDP_EXT_GETFILES != 0;
    let tree = search_tree_codec::for_server(expression);
    let mut out = PacketWriter::new();
    if large_files && extended {
        out.u32(1)
            .u8(UINT8 | NEW_ED2K_FLAG)
            .u8(client_tag::SERVER_UDPSEARCH_FLAGS)
            .u8(SEARCH_FLAG_NEWTAGS_LARGEFILES);
        search_tree_codec::write(&mut out, &tree, true)?;
        return Ok(Some(Packet::written(protocol::EDONKEY, server_udp::GLOBSEARCHREQ3, out)));
    }
    if needs_64_bit(&tree)? {
        return Ok(None);
    }
    search_tree_codec::write(&mut out, &tree, false)?;
    let opcode = if extended { server_udp::GLOBSEARCHREQ2 } else { server_udp::GLOBSEARCHREQ };
    Ok(Some(Packet::written(protocol::EDONKEY, opcode, out)))
}

const SEARCH_FLAG_NEWTAGS_LARGEFILES: u8 = 0x01;

fn needs_64_bit(tree: &SearchExpr) -> Result<bool, EncodeError> {
    let (mut narrow, mut wide) = (PacketWriter::new(), PacketWriter::new());
    search_tree_codec::write(&mut narrow, tree, false)?;
    search_tree_codec::write(&mut wide, tree, true)?;
    Ok(narrow.size() != wide.size())
}
