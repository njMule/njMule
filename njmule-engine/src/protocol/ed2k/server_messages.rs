// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Messages between a client and an eD2K server over TCP. IP addresses travel as the four bytes in network order;
//! client ids below [`LOW_ID_LIMIT`] are LowIDs (firewalled clients).

use crate::protocol::ed2k::client_version;
use crate::protocol::ed2k::file_entry::{self, FileEntry};
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter, StringEncoding};
use crate::protocol::packet::opcodes::server;
use crate::protocol::packet::{Packet, protocol};
use crate::protocol::tag::tag_codec::{read_ed2k_list, write_ed2k_list};
use crate::protocol::tag::{Ed2kTagFormat, Tag, TagName, client_tag, server_tag, tags};
use crate::search::search_expr::SearchExpr;
use crate::search::search_tree_codec;
use crate::util::{ipv4, white_space};

/// Client ids below this value are LowIDs.
pub const LOW_ID_LIMIT: u32 = 0x0100_0000;

// Capabilities announced at login.
pub const CAP_ZLIB: u32 = 0x0001;
pub const CAP_NEWTAGS: u32 = 0x0008;
pub const CAP_UNICODE: u32 = 0x0010;
pub const CAP_LARGEFILES: u32 = 0x0100;
pub const CAP_SUPPORTS_CRYPT: u32 = 0x0200;
pub const CAP_REQUESTS_CRYPT: u32 = 0x0400;
pub const CAP_REQUIRES_CRYPT: u32 = 0x0800;

// Server flags sent with the id.
pub const TCP_COMPRESSION: u32 = 0x0001;
pub const TCP_NEWTAGS: u32 = 0x0008;
pub const TCP_UNICODE: u32 = 0x0010;
pub const TCP_RELATED_SEARCH: u32 = 0x0040;
pub const TCP_TYPE_TAG_INTEGER: u32 = 0x0080;
pub const TCP_LARGEFILES: u32 = 0x0100;
pub const TCP_OBFUSCATION: u32 = 0x0400;

/// Search results accepted per packet.
pub const MAX_RESULTS: usize = 1_000;

const MAX_SERVER_LIST: usize = 255;
const MAX_SOURCES: usize = 255;
const CALLBACK_WITH_HASH: usize = 4 + 2 + 1 + 16;

/// The login request, the first message after connecting.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Login {
    pub user_hash: [u8; 16],
    pub client_id: u32,
    pub tcp_port: u16,
    pub nick: String,
    pub capabilities: u32,
}

impl Login {
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let tags = [
            Tag::String { name: TagName::id(client_tag::NAME), value: self.nick.clone() },
            Tag::Int { name: TagName::id(client_tag::VERSION), value: u64::from(client_version::EDONKEY) },
            Tag::Int { name: TagName::id(client_tag::SERVER_FLAGS), value: u64::from(self.capabilities) },
            Tag::Int { name: TagName::id(client_tag::EMULE_VERSION), value: u64::from(client_version::nj_mule()) },
        ];
        let mut out = PacketWriter::new();
        out.hash16(&self.user_hash).u32(self.client_id).u16(self.tcp_port).count32(tags.len())?;
        write_ed2k_list(&mut out, &tags, Ed2kTagFormat::Old, StringEncoding::Utf8)?;
        Ok(Packet::written(protocol::EDONKEY, server::LOGINREQUEST, out))
    }

    /// The server side of [`Login::encode`].
    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let user_hash = input.hash16()?;
        let client_id = input.u32()?;
        let tcp_port = input.u16()?;
        let count = input.u32()?;
        let tags = read_ed2k_list(&mut input, u64::from(count), file_entry::MAX_TAGS)?;
        Ok(Self {
            user_hash,
            client_id,
            tcp_port,
            nick: tags::string_value(&tags, client_tag::NAME).unwrap_or_default().to_owned(),
            // The field holds 32 bits: a wider tag keeps its low half.
            capabilities: low32(tags::int_value(&tags, client_tag::SERVER_FLAGS).unwrap_or(0)),
        })
    }
}

/// The id the server assigned, its flags and, from newer servers, our address as it sees it (0 when absent).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IdChange {
    pub client_id: u32,
    pub server_flags: u32,
    pub reported_ip: u32,
}

impl IdChange {
    pub fn is_low_id(&self) -> bool {
        self.client_id < LOW_ID_LIMIT
    }

    /// Our public address: the id itself for a HighID, else what the server reported.
    pub fn public_ip(&self) -> u32 {
        if self.is_low_id() { self.reported_ip } else { ipv4::from_ed2k(self.client_id) }
    }

    pub fn has(&self, flag: u32) -> bool {
        self.server_flags & flag != 0
    }

    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        out.u32(self.client_id).u32(self.server_flags).u32(0).u32(if self.reported_ip == 0 {
            0
        } else {
            ipv4::to_ed2k(self.reported_ip)
        });
        Packet::written(protocol::EDONKEY, server::IDCHANGE, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let client_id = input.u32()?;
        let server_flags = if input.remaining() >= 4 { input.u32()? } else { 0 };
        let mut reported_ip = 0;
        if input.remaining() >= 8 {
            input.skip(4)?;
            let reported = input.u32()?;
            reported_ip = if reported < LOW_ID_LIMIT { 0 } else { ipv4::from_ed2k(reported) };
        }
        Ok(Self { client_id, server_flags, reported_ip })
    }
}

/// Users and files on the server.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ServerStatus {
    pub users: u32,
    pub files: u32,
}

impl ServerStatus {
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        out.u32(self.users).u32(self.files);
        Packet::written(protocol::EDONKEY, server::SERVERSTATUS, out)
    }

    /// None for a packet too short to hold both counts.
    pub fn decode(packet: &Packet) -> Result<Option<Self>, DecodeError> {
        let mut input = packet.reader();
        if input.remaining() < 8 {
            return Ok(None);
        }
        Ok(Some(Self { users: input.u32()?, files: input.u32()? }))
    }
}

/// The server's own description.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServerIdent {
    pub hash: [u8; 16],
    pub ip: u32,
    pub port: u16,
    pub name: Option<String>,
    pub description: Option<String>,
}

impl ServerIdent {
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let tags: Vec<Tag> = [(server_tag::SERVERNAME, &self.name), (server_tag::DESCRIPTION, &self.description)]
            .into_iter()
            .filter_map(|(id, value)| value.as_ref().map(|v| Tag::String { name: TagName::id(id), value: v.clone() }))
            .collect();
        let mut out = PacketWriter::new();
        out.hash16(&self.hash).u32(ipv4::to_ed2k(self.ip)).u16(self.port).count32(tags.len())?;
        write_ed2k_list(&mut out, &tags, Ed2kTagFormat::New, StringEncoding::Utf8)?;
        Ok(Packet::written(protocol::EDONKEY, server::SERVERIDENT, out))
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let hash = input.hash16()?;
        let ip = ipv4::from_ed2k(input.u32()?);
        let port = input.u16()?;
        let count = input.u32()?;
        let tags = read_ed2k_list(&mut input, u64::from(count), file_entry::MAX_TAGS)?;
        Ok(Self {
            hash,
            ip,
            port,
            name: tags::string_value(&tags, server_tag::SERVERNAME).map(str::to_owned),
            description: tags::string_value(&tags, server_tag::DESCRIPTION).map(str::to_owned),
        })
    }
}

/// An address in a server list or a callback request.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Endpoint {
    pub ip: u32,
    pub port: u16,
}

/// Search results; `more` when the server can send more after QUERY_MORE_RESULT.
#[derive(Debug, Clone, PartialEq)]
pub struct SearchResults {
    pub files: Vec<FileEntry>,
    pub more: bool,
}

impl SearchResults {
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        out.count32(self.files.len())?;
        for file in &self.files {
            file.write(&mut out, Ed2kTagFormat::New)?;
        }
        out.u8(u8::from(self.more));
        Ok(Packet::written(protocol::EDONKEY, server::SEARCHRESULT, out))
    }

    /// Decodes an answer; one cut short keeps the files before the cut, provided there is one.
    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let announced = input.u32()?;
        let count = input.count(u64::from(announced), 0, MAX_RESULTS)?;
        let mut files = Vec::with_capacity(count.min(input.remaining() / file_entry::MIN_SIZE));
        for _ in 0..count {
            match FileEntry::read(&mut input) {
                Ok(file) => files.push(file),
                Err(e) if files.is_empty() => return Err(e),
                Err(_) => return Ok(Self { files, more: false }),
            }
        }
        // A single trailing byte is the "more results" flag.
        let more = input.remaining() == 1 && input.u8()? == 1;
        Ok(Self { files, more })
    }
}

/// A LowID client asked through the server that we connect to it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CallbackRequested {
    pub endpoint: Endpoint,
    pub crypt_options: u8,
    pub user_hash: Option<[u8; 16]>,
}

impl CallbackRequested {
    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let endpoint = Endpoint { ip: ipv4::from_ed2k(input.u32()?), port: input.u16()? };
        if packet.length() < CALLBACK_WITH_HASH {
            return Ok(Self { endpoint, crypt_options: 0, user_hash: None });
        }
        let crypt_options = input.u8()?;
        Ok(Self { endpoint, crypt_options, user_hash: Some(input.hash16()?) })
    }
}

/// A source for a file: a client id (its IP for a HighID), its port and, from servers that know them, its connect
/// options and user hash.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Source {
    pub client_id: u32,
    pub port: u16,
    pub crypt_options: u8,
    pub user_hash: Option<[u8; 16]>,
}

impl Source {
    /// Connect options flag: the user hash follows.
    pub const HAS_USER_HASH: u8 = 0x80;

    pub fn is_low_id(&self) -> bool {
        self.client_id < LOW_ID_LIMIT
    }
}

/// The sources a server knows for one file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FoundSources {
    pub file: [u8; 16],
    pub sources: Vec<Source>,
}

impl FoundSources {
    /// Decodes OP_FOUNDSOURCES, or OP_FOUNDSOURCES_OBFU with the connect options of its sources.
    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        Self::read(&mut packet.reader(), packet.opcode() == server::FOUNDSOURCES_OBFU)
    }

    /// Reads one file's sources; `obfuscated` sources carry crypt options and maybe a user hash.
    pub fn read(input: &mut PacketReader<'_>, obfuscated: bool) -> Result<Self, DecodeError> {
        let file = input.hash16()?;
        let announced = input.u8()?;
        let count = input.count(u64::from(announced), if obfuscated { 7 } else { 6 }, MAX_SOURCES)?;
        let mut sources = Vec::with_capacity(count);
        for _ in 0..count {
            let client_id = input.u32()?;
            let port = input.u16()?;
            let mut crypt_options = 0;
            let mut user_hash = None;
            if obfuscated {
                crypt_options = input.u8()?;
                if crypt_options & Source::HAS_USER_HASH != 0 {
                    user_hash = Some(input.hash16()?);
                }
            }
            sources.push(Source { client_id, port, crypt_options, user_hash });
        }
        Ok(Self { file, sources })
    }

    /// OP_FOUNDSOURCES_OBFU, as servers answer clients that support obfuscation.
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        self.write(&mut out, true)?;
        Ok(Packet::written(protocol::EDONKEY, server::FOUNDSOURCES_OBFU, out))
    }

    pub fn write(&self, out: &mut PacketWriter, obfuscated: bool) -> Result<(), EncodeError> {
        out.hash16(&self.file).count8(self.sources.len())?;
        for source in &self.sources {
            out.u32(source.client_id).u16(source.port);
            if obfuscated {
                out.u8(source.crypt_options);
                if let Some(hash) = &source.user_hash {
                    out.hash16(hash);
                }
            }
        }
        Ok(())
    }
}

/// Asks for the sources of a file; files above 4 GB use the 64-bit form, which only servers with large file support
/// understand.
pub fn get_sources(file: &[u8; 16], size: u64, obfuscated: bool) -> Packet {
    let mut out = PacketWriter::new();
    out.hash16(file);
    match u32::try_from(size) {
        Ok(small) => out.u32(small),
        Err(_) => out.u32(0).u64(size),
    };
    Packet::written(protocol::EDONKEY, if obfuscated { server::GETSOURCES_OBFU } else { server::GETSOURCES }, out)
}

/// Lines of a server message: the text is split on line breaks and blank lines dropped.
pub fn decode_message(packet: &Packet) -> Result<Vec<String>, DecodeError> {
    let text = packet.reader().string16()?;
    Ok(text
        .split(['\r', '\n'])
        .map(white_space::strip)
        .filter(|line| !line.is_empty())
        .map(str::to_owned)
        .collect())
}

pub fn message(text: &str) -> Result<Packet, EncodeError> {
    let mut out = PacketWriter::new();
    out.string16(text, StringEncoding::Utf8)?;
    Ok(Packet::written(protocol::EDONKEY, server::SERVERMESSAGE, out))
}

pub fn server_list(servers: &[Endpoint]) -> Result<Packet, EncodeError> {
    let mut out = PacketWriter::new();
    out.count8(servers.len())?;
    for server in servers {
        out.u32(ipv4::to_ed2k(server.ip)).u16(server.port);
    }
    Ok(Packet::written(protocol::EDONKEY, server::SERVERLIST, out))
}

pub fn decode_server_list(packet: &Packet) -> Result<Vec<Endpoint>, DecodeError> {
    let mut input = packet.reader();
    let announced = input.u8()?;
    let count = input.count(u64::from(announced), 6, MAX_SERVER_LIST)?;
    (0..count).map(|_| Ok(Endpoint { ip: ipv4::from_ed2k(input.u32()?), port: input.u16()? })).collect()
}

pub fn query_more_results() -> Packet {
    Packet::empty(protocol::EDONKEY, server::QUERY_MORE_RESULT)
}

pub fn get_server_list() -> Packet {
    Packet::empty(protocol::EDONKEY, server::GETSERVERLIST)
}

/// Asks the server to have a LowID client connect to us.
pub fn callback_request(low_id: u32) -> Packet {
    let mut out = PacketWriter::new();
    out.u32(low_id);
    Packet::written(protocol::EDONKEY, server::CALLBACKREQUEST, out)
}

/// Our shared files, with new tags for servers that announced them.
pub fn offer_files(files: &[FileEntry], new_tags: bool) -> Result<Packet, EncodeError> {
    let format = if new_tags { Ed2kTagFormat::New } else { Ed2kTagFormat::Old };
    let mut out = PacketWriter::new();
    out.count32(files.len())?;
    for file in files {
        file.write(&mut out, format)?;
    }
    Ok(Packet::written(protocol::EDONKEY, server::OFFERFILES, out))
}

/// An empty file offer: keeps an idle connection alive.
pub fn keep_alive() -> Packet {
    let mut out = PacketWriter::new();
    out.u32(0);
    Packet::written(protocol::EDONKEY, server::OFFERFILES, out)
}

fn low32(value: u64) -> u32 {
    u32::try_from(value & u64::from(u32::MAX)).unwrap_or(u32::MAX)
}

/// A search on the server we are on; `large_files` when it takes 64-bit sizes.
pub fn search_request(expression: &SearchExpr, large_files: bool) -> Result<Packet, EncodeError> {
    let mut out = PacketWriter::new();
    search_tree_codec::write(&mut out, &search_tree_codec::for_server(expression), large_files)?;
    Ok(Packet::written(protocol::EDONKEY, server::SEARCHREQUEST, out))
}
