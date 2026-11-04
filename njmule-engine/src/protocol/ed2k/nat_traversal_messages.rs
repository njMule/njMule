// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Traversal of the routers of two firewalled clients, as newer clients do it. The requester asks the target's Kad
//! buddy for a rendezvous (a re-ask through the buddy whose file hash is zero, carrying OP_RENDEZVOUS); the buddy
//! passes it on over TCP with the requester's address as it sees it; both sides send hole punches to each other's
//! address, and a uTP or QUIC connection opens through the holes, inside frames of the reserved UDP protocol 0xB2 on
//! the client UDP port.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use crate::protocol::io::{DecodeError, PacketReader, PacketWriter};
use crate::protocol::packet::opcodes::client_udp;
use crate::protocol::packet::{Packet, protocol};
use crate::util::ipv4;

// The frame types of protocol 0xB2.
pub const FRAME_UTP: u8 = 0x00;
pub const FRAME_QUIC: u8 = 0x01;
pub const FRAME_CAPS: u8 = 0x02;
pub const FRAME_CAPS_ACK: u8 = 0x03;
pub const FRAME_KEY: u8 = 0xFF;

/// Connect options: the client reaches others through their routers over uTP.
pub const OPTION_UTP: u8 = 0x80;
/// Connect options: the client reaches others through their routers over QUIC.
pub const OPTION_QUIC: u8 = 0x40;
/// Connect options of a rendezvous request: the buddy may tell the requester the target's address.
pub const OPTION_HINT_WANTED: u8 = 0x20;

// The transport a rendezvous asks for.
pub const TRANSPORT_UTP: u8 = 1;
pub const TRANSPORT_QUIC: u8 = 2;

const CAPS_MAGIC: u32 = 0x4351_4145;
const CAPS_VERSION: u8 = 1;
const CAPS_LENGTH: usize = 54;
// An IPv6 address follows in a buddy's forward.
const IPV6_MARK: u32 = u32::MAX;
const HASH_LENGTH: usize = 16;
const ZERO_HASH: [u8; 16] = [0; 16];

/// The empty datagram that opens a hole in the sender's router towards the receiver.
pub fn hole_punch() -> Packet {
    Packet::empty(protocol::EMULE, client_udp::HOLEPUNCH)
}

/// A uTP packet in its frame.
pub fn utp_frame(utp: &[u8]) -> Packet {
    Packet::of(protocol::UDP_RESERVED_2, FRAME_UTP, utp)
}

/// A QUIC datagram in its frame.
pub fn quic_frame(quic: &[u8]) -> Packet {
    Packet::of(protocol::UDP_RESERVED_2, FRAME_QUIC, quic)
}

/// What each side of a QUIC connection writes first on its stream: "EAQN1", its user hash and the hash it expects the
/// other side to have (zero when unknown).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct QuicProof {
    pub sender: [u8; 16],
    pub receiver: [u8; 16],
}

impl QuicProof {
    pub const LENGTH: usize = 37;
    const MAGIC: &[u8] = b"EAQN1";

    pub fn encode(&self) -> Vec<u8> {
        let mut out = PacketWriter::new();
        out.bytes(Self::MAGIC).hash16(&self.sender).hash16(&self.receiver);
        out.into_byte_array()
    }

    pub fn decode(proof: &[u8]) -> Result<Self, DecodeError> {
        let mut input = PacketReader::of(proof);
        if proof.len() != Self::LENGTH || input.bytes(Self::MAGIC.len())? != Self::MAGIC {
            return Err(DecodeError::new("not a QUIC proof"));
        }
        Ok(Self { sender: input.hash16()?, receiver: input.hash16()? })
    }
}

/// OP_RENDEZVOUS: who asks, its connect options, the file it wants (zero for none), and its public IPv4 address and
/// UDP port when it knows them (0 otherwise).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rendezvous {
    pub requester: [u8; 16],
    pub options: u8,
    pub file: [u8; 16],
    pub requester_ip: u32,
    pub requester_port: u16,
    pub transport: u8,
}

impl Rendezvous {
    /// The request to the target's buddy `buddy_id`, as a re-ask through it with a zero file hash.
    pub fn encode_through_buddy(&self, buddy_id: &[u8; 16]) -> Packet {
        let mut out = PacketWriter::new();
        out.hash16(buddy_id)
            .hash16(&ZERO_HASH)
            .u8(client_udp::RENDEZVOUS)
            .hash16(&self.requester)
            .u8(self.options)
            .hash16(&self.file);
        if self.requester_ip != 0 && self.requester_port != 0 {
            out.u32(ipv4::to_ed2k(self.requester_ip)).u16(self.requester_port).u8(self.transport);
        }
        Packet::written(protocol::EMULE, client_udp::REASKCALLBACKUDP, out)
    }

    /// Reads the payload after OP_RENDEZVOUS; the fields after the options may be missing.
    pub fn decode(payload: &[u8]) -> Result<Self, DecodeError> {
        let mut input = PacketReader::of(payload);
        let requester = input.hash16()?;
        let options = input.u8()?;
        let file = if input.remaining() >= HASH_LENGTH { input.hash16()? } else { ZERO_HASH };
        let (requester_ip, requester_port) =
            if input.remaining() >= 6 { (ipv4::from_ed2k(input.u32()?), input.u16()?) } else { (0, 0) };
        let transport = if input.has_remaining() { input.u8()? } else { 0 };
        Ok(Self { requester, options, file, requester_ip, requester_port, transport })
    }
}

/// What a buddy passes on over TCP that is not a re-ask: the requester's address as the buddy saw it, and the packet
/// it carries, by its opcode.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Forward {
    pub requester: SocketAddr,
    pub opcode: u8,
    pub payload: Vec<u8>,
}

impl Forward {
    /// Reads an OP_REASKCALLBACKTCP, `<IPv4 | 0xFFFFFFFF IPv6><port u16><zero hash><opcode><payload>`; none for a
    /// re-ask, whose file hash is not zero.
    pub fn decode(packet: &Packet) -> Result<Option<Self>, DecodeError> {
        let mut input = packet.reader();
        let first = input.u32()?;
        // An IPv4-mapped IPv6 address is the IPv4 address it maps.
        let address = if first == IPV6_MARK {
            IpAddr::V6(Ipv6Addr::from(input.hash16()?)).to_canonical()
        } else {
            IpAddr::V4(Ipv4Addr::from(ipv4::from_ed2k(first)))
        };
        let port = input.u16()?;
        if input.remaining() <= HASH_LENGTH || input.hash16()? != ZERO_HASH {
            return Ok(None);
        }
        let opcode = input.u8()?;
        Ok(Some(Self { requester: SocketAddr::new(address, port), opcode, payload: input.rest().to_vec() }))
    }
}

/// The capabilities a client tells another before choosing a transport, or its answer: its connect options, its user
/// hash, the hash it expects the receiver to have (zero when unknown) and the file between them (zero for none).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Caps {
    pub answer: bool,
    pub options: u8,
    pub sender: [u8; 16],
    pub expected: [u8; 16],
    pub file: [u8; 16],
}

impl Caps {
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        out.u32(CAPS_MAGIC)
            .u8(CAPS_VERSION)
            .u8(self.options)
            .hash16(&self.sender)
            .hash16(&self.expected)
            .hash16(&self.file);
        Packet::written(protocol::UDP_RESERVED_2, if self.answer { FRAME_CAPS_ACK } else { FRAME_CAPS }, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        if input.remaining() < CAPS_LENGTH || input.u32()? != CAPS_MAGIC || input.u8()? != CAPS_VERSION {
            return Err(DecodeError::new("not a capability frame of version 1"));
        }
        let options = input.u8()?;
        Ok(Self {
            answer: packet.opcode() == FRAME_CAPS_ACK,
            options,
            sender: input.hash16()?,
            expected: input.hash16()?,
            file: input.hash16()?,
        })
    }
}
