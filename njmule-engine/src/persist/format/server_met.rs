// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The server list, `server.met`: `<0xE0><u32 count>` then `<IP 4><port 2><u32 tag count><tags>` per server. The IP is
//! in network order; 0 with an ST_DYNIP tag means a host name. Unknown tags are kept, so that rewriting a file loses
//! nothing.

use crate::persist::format::met_codecs;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter};
use crate::protocol::tag::{Tag, TagName, server_tag, tags};
use crate::util::ipv4;

pub const HEADER: u8 = 0xE0;
const OLD_HEADER: u8 = 0x0E;
const MAX_SERVERS: usize = 100_000;

/// One server; `ip` is in [`ipv4`] form.
#[derive(Debug, Clone, PartialEq)]
pub struct Server {
    pub ip: u32,
    pub port: u16,
    pub tags: Vec<Tag>,
}

impl Server {
    /// This server with `tag` replacing any tag of the same name.
    #[must_use]
    pub fn with(&self, tag: Tag) -> Self {
        let mut tags: Vec<Tag> = self.tags.iter().filter(|t| t.name() != tag.name()).cloned().collect();
        tags.push(tag);
        Self { ip: self.ip, port: self.port, tags }
    }

    pub fn name(&self) -> Option<&str> {
        tags::string_value(&self.tags, server_tag::SERVERNAME)
    }

    pub fn description(&self) -> Option<&str> {
        tags::string_value(&self.tags, server_tag::DESCRIPTION)
    }

    pub fn dynamic_ip(&self) -> Option<&str> {
        tags::string_value(&self.tags, server_tag::DYNIP).filter(|host| !host.is_empty())
    }

    pub fn preference(&self) -> Option<u64> {
        tags::int_value(&self.tags, server_tag::PREFERENCE)
    }

    pub fn failed_count(&self) -> Option<u64> {
        tags::int_value(&self.tags, server_tag::FAIL)
    }

    pub fn users(&self) -> Option<u64> {
        tags::int_value_named(&self.tags, &TagName::of_bytes(server_tag::USERS))
    }

    pub fn files(&self) -> Option<u64> {
        tags::int_value_named(&self.tags, &TagName::of_bytes(server_tag::FILES))
    }

    pub fn max_users(&self) -> Option<u64> {
        tags::int_value(&self.tags, server_tag::MAXUSERS)
    }

    pub fn udp_flags(&self) -> Option<u64> {
        tags::int_value(&self.tags, server_tag::UDPFLAGS)
    }

    pub fn udp_key(&self) -> Option<u64> {
        tags::int_value(&self.tags, server_tag::UDPKEY)
    }

    /// Our address when the server gave its UDP key, in eD2K byte order: the key holds for it only.
    pub fn udp_key_ip(&self) -> Option<u64> {
        tags::int_value(&self.tags, server_tag::UDPKEYIP)
    }

    pub fn obfuscated_tcp_port(&self) -> Option<u64> {
        tags::int_value(&self.tags, server_tag::TCPPORTOBFUSCATION)
    }

    pub fn obfuscated_udp_port(&self) -> Option<u64> {
        tags::int_value(&self.tags, server_tag::UDPPORTOBFUSCATION)
    }

    /// How long the server took to answer a status request, in milliseconds.
    pub fn ping(&self) -> Option<u64> {
        tags::int_value(&self.tags, server_tag::PING)
    }

    /// The number of files a client should offer at most.
    pub fn soft_files(&self) -> Option<u64> {
        tags::int_value(&self.tags, server_tag::SOFTFILES)
    }

    /// The number of files beyond which the server drops a client.
    pub fn hard_files(&self) -> Option<u64> {
        tags::int_value(&self.tags, server_tag::HARDFILES)
    }

    pub fn low_id_users(&self) -> Option<u64> {
        tags::int_value(&self.tags, server_tag::LOWIDUSERS)
    }

    /// The server software's version, kept as text or as `major << 16 | minor`.
    pub fn version(&self) -> Option<String> {
        if let Some(text) = tags::string_value(&self.tags, server_tag::VERSION) {
            return Some(text.to_owned());
        }
        tags::int_value(&self.tags, server_tag::VERSION).map(|number| version_of(number >> 16, number & 0xFFFF))
    }

    /// When the server was last asked for its status, in epoch seconds.
    pub fn last_ping(&self) -> Option<u64> {
        tags::int_value(&self.tags, server_tag::LASTPING)
    }
}

/// `major.minor` with two minor digits, as servers number their releases ("17.05").
pub fn version_of(major: u64, minor: u64) -> String {
    format!("{major}.{minor:02}")
}

pub fn read(data: &[u8]) -> Result<Vec<Server>, DecodeError> {
    let mut input = PacketReader::of(data);
    met_codecs::header(&mut input, "server.met", &[HEADER, OLD_HEADER])?;
    let declared = input.u32()?;
    let count = input.count(u64::from(declared), 10, MAX_SERVERS)?;
    (0..count)
        .map(|_| {
            let ip = ipv4::from_ed2k(input.u32()?);
            let port = input.u16()?;
            Ok(Server { ip, port, tags: met_codecs::read_tags(&mut input)? })
        })
        .collect()
}

pub fn write(servers: &[Server]) -> Result<Vec<u8>, EncodeError> {
    let mut out = PacketWriter::new();
    out.u8(HEADER).count32(servers.len())?;
    for server in servers {
        out.u32(ipv4::to_ed2k(server.ip)).u16(server.port);
        met_codecs::write_tags(&mut out, &server.tags)?;
    }
    Ok(out.into_byte_array())
}
