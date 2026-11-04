// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Re-asks over UDP between clients: a client waiting in an uploader's queue asks again for its file without
//! connecting, and learns its place. What a message carries depends on the UDP version the other client announced in
//! its hello.

use crate::protocol::ed2k::client_messages::PartStatus;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter};
use crate::protocol::packet::opcodes::{client_udp, extended};
use crate::protocol::packet::{Packet, protocol};
use crate::util::ipv4;

/// The UDP version we announce: pings and answers carry the part statuses and the complete source count.
pub const UDP_VERSION: u32 = 4;

const HASH_LENGTH: usize = 16;

/// OP_REASKFILEPING: `file` asked for again; from UDP version 4 with the asker's parts of it, from version 3 with the
/// number of complete sources it knows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ping {
    pub file: [u8; 16],
    pub parts: PartStatus,
    pub complete_sources: u32,
}

impl Ping {
    /// The ping for a client of `udp_version`.
    pub fn encode(&self, udp_version: u32) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        out.hash16(&self.file);
        if udp_version > 3 {
            self.parts.write(&mut out)?;
        }
        if udp_version > 2 {
            out.u16(capped16(self.complete_sources));
        }
        Ok(Packet::written(protocol::EMULE, client_udp::REASKFILEPING, out))
    }

    /// The ping for a firewalled client of `udp_version`, sent to its buddy, which knows it by `buddy_id`.
    pub fn encode_through_buddy(&self, buddy_id: &[u8; 16], udp_version: u32) -> Result<Packet, EncodeError> {
        Ok(ThroughBuddy { buddy_id: *buddy_id, ping: self.encode(udp_version)? }.encode())
    }

    /// Reads a ping from a client of `udp_version`; what it does not send is a complete file and no count.
    pub fn decode(packet: &Packet, udp_version: u32) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let file = input.hash16()?;
        let parts = if udp_version > 3 { PartStatus::read(&mut input)? } else { PartStatus::COMPLETE };
        let complete_sources = if udp_version > 2 && input.remaining() >= 2 { u32::from(input.u16()?) } else { 0 };
        Ok(Self { file, parts, complete_sources })
    }

    /// The file a ping asks for, whoever sent it.
    pub fn file(packet: &Packet) -> Result<[u8; 16], DecodeError> {
        packet.reader().hash16()
    }
}

/// OP_REASKCALLBACKUDP: a ping for a firewalled client, sent to the buddy that knows it by `buddy_id` (big-endian
/// bytes of the Kad id).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ThroughBuddy {
    pub buddy_id: [u8; 16],
    pub ping: Packet,
}

impl ThroughBuddy {
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        out.hash16(&self.buddy_id).bytes(self.ping.payload());
        Packet::written(protocol::EMULE, client_udp::REASKCALLBACKUDP, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self { buddy_id: input.hash16()?, ping: rest_as_ping(&mut input)? })
    }
}

/// OP_REASKCALLBACKTCP: a buddy passes on to the firewalled client it serves the ping of the client at
/// `requester_ip` and UDP `requester_port`, where the answer goes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Relayed {
    pub requester_ip: u32,
    pub requester_port: u16,
    pub ping: Packet,
}

impl Relayed {
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        out.u32(ipv4::to_ed2k(self.requester_ip)).u16(self.requester_port).bytes(self.ping.payload());
        Packet::written(protocol::EMULE, extended::REASKCALLBACKTCP, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let requester_ip = ipv4::from_ed2k(input.u32()?);
        let requester_port = input.u16()?;
        Ok(Self { requester_ip, requester_port, ping: rest_as_ping(&mut input)? })
    }
}

// The rest of a message is a ping, which names its file at least.
fn rest_as_ping(input: &mut PacketReader<'_>) -> Result<Packet, DecodeError> {
    if input.remaining() < HASH_LENGTH {
        return Err(DecodeError::new("a re-ask without its file"));
    }
    Ok(Packet::of(protocol::EMULE, client_udp::REASKFILEPING, input.rest()))
}

/// OP_REASKACK: the asker's place in the queue; from UDP version 4 with the uploader's parts of the file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ack {
    pub parts: Option<PartStatus>,
    pub rank: u32,
}

impl Ack {
    /// The answer for a client of `udp_version`.
    pub fn encode(&self, udp_version: u32) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        if udp_version > 3 {
            self.parts.as_ref().unwrap_or(&PartStatus::COMPLETE).write(&mut out)?;
        }
        out.u16(capped16(self.rank));
        Ok(Packet::written(protocol::EMULE, client_udp::REASKACK, out))
    }

    /// Reads the answer of a client of `udp_version`.
    pub fn decode(packet: &Packet, udp_version: u32) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let parts = if udp_version > 3 { Some(PartStatus::read(&mut input)?) } else { None };
        Ok(Self { parts, rank: u32::from(input.u16()?) })
    }
}

/// OP_QUEUEFULL: the uploader's queue takes no one more.
pub fn queue_full() -> Packet {
    Packet::empty(protocol::EMULE, client_udp::QUEUEFULL)
}

/// OP_FILENOTFOUND: the uploader does not share the file.
pub fn file_not_found() -> Packet {
    Packet::empty(protocol::EMULE, client_udp::FILENOTFOUND)
}

fn capped16(value: u32) -> u16 {
    u16::try_from(value).unwrap_or(u16::MAX)
}
