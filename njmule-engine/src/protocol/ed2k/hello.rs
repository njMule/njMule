// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The greeting two clients exchange first (OP_HELLO, then OP_HELLOANSWER): `[<16>]<user hash 16><client id
//! u32><tcp port u16><tag count u32><tags><server ip u32><server port u16>`, where only OP_HELLO starts with the hash
//! size. The tags announce name, versions, ports and features.

use std::net::{IpAddr, Ipv6Addr};

use crate::api::nj_mule_info;
use crate::protocol::ed2k::client_version;
use crate::protocol::io::{DecodeError, EncodeError, PacketWriter, StringEncoding};
use crate::protocol::packet::opcodes::client;
use crate::protocol::packet::{Packet, protocol};
use crate::protocol::tag::tag_codec::{read_ed2k_list, write_ed2k_list};
use crate::protocol::tag::{Ed2kTagFormat, Tag, TagName, client_tag, tags};
use crate::util::{ipv4, ipv6};

// Feature bits of the first options tag.
/// The AICH version, in bits 29-31: 1 for recovery data of parts.
pub const MISC1_AICH_SHIFT: u32 = 29;
pub const MISC1_UNICODE: u32 = 1 << 28;
pub const MISC1_UDP_VERSION_SHIFT: u32 = 24;
pub const MISC1_DATA_COMPRESSION: u32 = 1 << 20;
pub const MISC1_NO_VIEW_SHARED_FILES: u32 = 1 << 2;

// Feature bits of the second options tag.
pub const MISC2_LARGE_FILES: u32 = 1 << 4;
pub const MISC2_SOURCE_EXCHANGE2: u32 = 1 << 10;
pub const MISC2_SUPPORTS_CAPTCHA: u32 = 1 << 11;
pub const MISC2_SUPPORTS_CRYPT: u32 = 1 << 7;
pub const MISC2_REQUESTS_CRYPT: u32 = 1 << 8;
pub const MISC2_REQUIRES_CRYPT: u32 = 1 << 9;
/// TCP-firewalled on Kad with an open UDP port: reached by a direct UDP callback.
pub const MISC2_DIRECT_UDP_CALLBACK: u32 = 1 << 12;

// Feature bits of the newer clients' options tag.
pub const MOD_MISC_EXTENDED_SX: u32 = 1;
pub const MOD_MISC_UTP_TRAVERSAL: u32 = 1 << 1;
pub const MOD_MISC_IPV6: u32 = 1 << 2;
pub const MOD_MISC_QUIC_TRAVERSAL: u32 = 1 << 4;

/// The client id of a client with no IPv4 address, reached over IPv6 only.
pub const NO_IPV4: u32 = u32::MAX;

const HASH_SIZE: u8 = 16;
const MAX_TAGS: usize = 64;

#[derive(Debug, Clone, PartialEq)]
pub struct Hello {
    pub user_hash: [u8; 16],
    pub client_id: u32,
    pub tcp_port: u16,
    pub tags: Vec<Tag>,
    pub server_ip: u32,
    pub server_port: u16,
}

/// What [`Hello::of`] announces besides the header.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ours<'a> {
    pub nick: &'a str,
    pub udp_port: u16,
    pub kad_port: u16,
    pub misc_options1: u32,
    pub misc_options2: u32,
}

impl Hello {
    /// Our greeting; integer tags stay within 32 bits, which every client reads.
    pub fn of(
        user_hash: [u8; 16],
        client_id: u32,
        tcp_port: u16,
        ours: &Ours<'_>,
        server_ip: u32,
        server_port: u16,
    ) -> Self {
        let tags = vec![
            string(client_tag::NAME, ours.nick),
            int(client_tag::VERSION, client_version::EDONKEY),
            Tag::Int {
                name: TagName::id(client_tag::EMULE_UDPPORTS),
                value: u64::from(ours.kad_port) << 16 | u64::from(ours.udp_port),
            },
            int(client_tag::EMULE_MISCOPTIONS1, ours.misc_options1),
            int(client_tag::EMULE_MISCOPTIONS2, ours.misc_options2),
            int(client_tag::EMULE_VERSION, client_version::nj_mule()),
            // Clients show it after the software their version tag names: njMule and its full version.
            string(client_tag::MOD_VERSION, &format!("{} {}", nj_mule_info::NAME, nj_mule_info::version())),
            int(client_tag::MOD_MISCOPTIONS, MOD_MISC_IPV6),
        ];
        Self { user_hash, client_id, tcp_port, tags, server_ip, server_port }
    }

    /// This greeting with our public IPv6 address, at which clients may reach us, and the extended source exchange,
    /// which carries the IPv6 addresses of sources.
    pub fn with_ipv6(&self, ours: &Ipv6Addr) -> Self {
        let mut more = self.with_mod_options(MOD_MISC_IPV6 | MOD_MISC_EXTENDED_SX);
        more.tags.push(Tag::Hash { name: TagName::id(client_tag::MOD_IP_V6), value: ours.octets() });
        more
    }

    /// This greeting saying that we reach firewalled clients, and are reached, through routers over uTP and QUIC.
    pub fn with_router_traversal(&self) -> Self {
        self.with_mod_options(MOD_MISC_UTP_TRAVERSAL | MOD_MISC_QUIC_TRAVERSAL)
    }

    /// This greeting telling the client the address we see it at, so that it learns its own.
    pub fn to_peer_at(&self, seen: &IpAddr) -> Self {
        let name = TagName::id(client_tag::MOD_YOUR_IP);
        let tag = match seen {
            IpAddr::V6(address) => Tag::Hash { name, value: address.octets() },
            IpAddr::V4(address) => Tag::Int { name, value: u64::from(ipv4::to_ed2k(u32::from(*address))) },
        };
        let mut more = self.clone();
        more.tags.push(tag);
        more
    }

    /// This greeting with the address of our Kad buddy and its UDP port, after the UDP ports as clients expect them.
    pub fn with_buddy(&self, buddy_ip: u32, buddy_udp_port: u16) -> Self {
        let ports = TagName::id(client_tag::EMULE_UDPPORTS);
        let mut tags = Vec::with_capacity(self.tags.len().saturating_add(2));
        for tag in &self.tags {
            tags.push(tag.clone());
            if *tag.name() == ports {
                tags.push(int(client_tag::EMULE_BUDDYIP, ipv4::to_ed2k(buddy_ip)));
                tags.push(int(client_tag::EMULE_BUDDYUDP, u32::from(buddy_udp_port)));
            }
        }
        self.with_tags(tags)
    }

    /// OP_HELLO, or OP_HELLOANSWER when `answer`.
    pub fn encode(&self, answer: bool) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        if !answer {
            out.u8(HASH_SIZE);
        }
        out.hash16(&self.user_hash).u32(self.client_id).u16(self.tcp_port).count32(self.tags.len())?;
        write_ed2k_list(&mut out, &self.tags, Ed2kTagFormat::Old, StringEncoding::Utf8)?;
        out.u32(ipv4::to_ed2k(self.server_ip)).u16(self.server_port);
        Ok(Packet::written(protocol::EDONKEY, if answer { client::HELLOANSWER } else { client::HELLO }, out))
    }

    /// Decodes OP_HELLO or OP_HELLOANSWER; bytes after the server address are ignored.
    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        if packet.opcode() == client::HELLO {
            input.u8()?;
        }
        let user_hash = input.hash16()?;
        let client_id = input.u32()?;
        let tcp_port = input.u16()?;
        let count = input.u32()?;
        let tags = read_ed2k_list(&mut input, u64::from(count), MAX_TAGS)?;
        let server_ip = ipv4::from_ed2k(input.u32()?);
        let server_port = input.u16()?;
        Ok(Self { user_hash, client_id, tcp_port, tags, server_ip, server_port })
    }

    pub fn nick(&self) -> Option<&str> {
        tags::string_value(&self.tags, client_tag::NAME)
    }

    /// The listening port: a port tag, when present, overrides the header.
    pub fn listen_port(&self) -> u16 {
        low16(tags::int_value(&self.tags, client_tag::PORT).unwrap_or(u64::from(self.tcp_port)))
    }

    pub fn udp_port(&self) -> u16 {
        low16(self.udp_ports())
    }

    /// The version of the UDP messages between clients it takes, 0 for none; from 3 up it takes re-asks.
    pub fn udp_version(&self) -> u32 {
        self.misc_options1() >> MISC1_UDP_VERSION_SHIFT & 0xF
    }

    pub fn kad_port(&self) -> u16 {
        low16(self.udp_ports() >> 16)
    }

    /// The Kad version of the client's node, 0 when it runs none.
    pub fn kad_version(&self) -> u32 {
        self.misc_options2() & 0x0F
    }

    /// The address of the Kad buddy of a firewalled client, 0 when it names none.
    pub fn buddy_ip(&self) -> u32 {
        ipv4::from_ed2k(low32(tags::int_value(&self.tags, client_tag::EMULE_BUDDYIP).unwrap_or(0)))
    }

    /// The UDP port of the client's Kad buddy, 0 when it names none.
    pub fn buddy_udp_port(&self) -> u16 {
        low16(tags::int_value(&self.tags, client_tag::EMULE_BUDDYUDP).unwrap_or(0))
    }

    /// Whether the client, firewalled, takes a direct UDP callback on its Kad port.
    pub fn supports_direct_callback(&self) -> bool {
        self.misc_options2() & MISC2_DIRECT_UDP_CALLBACK != 0
    }

    /// Whether the client exchanges AICH hashes and recovery data.
    pub fn supports_aich(&self) -> bool {
        self.misc_options1() >> MISC1_AICH_SHIFT & 7 > 0
    }

    pub fn misc_options1(&self) -> u32 {
        low32(tags::int_value(&self.tags, client_tag::EMULE_MISCOPTIONS1).unwrap_or(0))
    }

    pub fn misc_options2(&self) -> u32 {
        low32(tags::int_value(&self.tags, client_tag::EMULE_MISCOPTIONS2).unwrap_or(0))
    }

    /// The extended version tag; only clients speaking the extended protocol send it.
    pub fn emule_version(&self) -> Option<u64> {
        tags::int_value(&self.tags, client_tag::EMULE_VERSION)
    }

    /// What the client tells of its software besides its version, such as the name of a modified client.
    pub fn mod_version(&self) -> Option<&str> {
        tags::string_value(&self.tags, client_tag::MOD_VERSION)
    }

    /// The feature bits of newer clients, 0 when it sends none.
    pub fn mod_misc_options(&self) -> u32 {
        low32(tags::int_value(&self.tags, client_tag::MOD_MISCOPTIONS).unwrap_or(0))
    }

    /// Whether the client exchanges sources the extended way, IPv6 addresses included.
    pub fn supports_extended_source_exchange(&self) -> bool {
        self.mod_misc_options() & MOD_MISC_EXTENDED_SX != 0
    }

    /// The public IPv6 address the client gives, if it gives one peers on the Internet can reach.
    pub fn ipv6(&self) -> Option<Ipv6Addr> {
        tags::hash_value(&self.tags, client_tag::MOD_IP_V6).and_then(|hash| ipv6::public_of(&hash))
    }

    /// Whether the client has an IPv4 address: one reached over IPv6 only says it has none.
    pub fn has_ipv4(&self) -> bool {
        self.client_id != NO_IPV4
    }

    fn with_tags(&self, tags: Vec<Tag>) -> Self {
        Self {
            user_hash: self.user_hash,
            client_id: self.client_id,
            tcp_port: self.tcp_port,
            tags,
            server_ip: self.server_ip,
            server_port: self.server_port,
        }
    }

    fn udp_ports(&self) -> u64 {
        tags::int_value(&self.tags, client_tag::EMULE_UDPPORTS).unwrap_or(0)
    }

    fn with_mod_options(&self, bits: u32) -> Self {
        let options = TagName::id(client_tag::MOD_MISCOPTIONS);
        let tags = self
            .tags
            .iter()
            .map(|tag| match tag {
                Tag::Int { name, value } if *name == options => {
                    Tag::Int { name: name.clone(), value: value | u64::from(bits) }
                }
                other => other.clone(),
            })
            .collect();
        self.with_tags(tags)
    }
}

fn string(id: u8, value: &str) -> Tag {
    Tag::String { name: TagName::id(id), value: value.to_owned() }
}

fn int(id: u8, value: u32) -> Tag {
    Tag::Int { name: TagName::id(id), value: u64::from(value) }
}

// A field of 32 or 16 bits keeps the low half of a wider tag.
fn low32(value: u64) -> u32 {
    u32::try_from(value & u64::from(u32::MAX)).unwrap_or(u32::MAX)
}

fn low16(value: u64) -> u16 {
    u16::try_from(value & u64::from(u16::MAX)).unwrap_or(u16::MAX)
}
