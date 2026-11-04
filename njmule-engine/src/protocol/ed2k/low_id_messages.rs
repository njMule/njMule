// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The client messages that keep a firewalled Kad client reachable: the link to its buddy, the callbacks that ask it
//! to connect, and the UDP firewall test. Ids and file hashes travel in the Kad id layout.

use crate::kad::uint128::UInt128;
use crate::protocol::io::{DecodeError, PacketWriter};
use crate::protocol::packet::opcodes::{client_udp, extended};
use crate::protocol::packet::{Packet, protocol};

/// OP_BUDDYPING: the firewalled side keeps the link to its buddy alive.
pub fn buddy_ping() -> Packet {
    Packet::empty(protocol::EMULE, extended::BUDDYPING)
}

/// OP_BUDDYPONG: the buddy's answer.
pub fn buddy_pong() -> Packet {
    Packet::empty(protocol::EMULE, extended::BUDDYPONG)
}

/// OP_CALLBACK, from a buddy to the firewalled client it serves: the client at `requester_ip` listening on
/// `requester_port` wants `file`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Callback {
    pub buddy_id: UInt128,
    pub file: UInt128,
    pub requester_ip: u32,
    pub requester_port: u16,
}

impl Callback {
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        self.buddy_id.write(&mut out);
        self.file.write(&mut out);
        out.u32(self.requester_ip).u16(self.requester_port);
        Packet::written(protocol::EMULE, extended::CALLBACK, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self {
            buddy_id: UInt128::read(&mut input)?,
            file: UInt128::read(&mut input)?,
            requester_ip: input.u32()?,
            requester_port: input.u16()?,
        })
    }
}

/// OP_DIRECTCALLBACKREQ, over UDP to the Kad port of a firewalled client whose UDP port is open: connect to our
/// `tcp_port`; the connect options say whether to obfuscate.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DirectCallbackRequest {
    pub tcp_port: u16,
    pub user_hash: [u8; 16],
    pub connect_options: u8,
}

impl DirectCallbackRequest {
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        out.u16(self.tcp_port).hash16(&self.user_hash).u8(self.connect_options);
        Packet::written(protocol::EMULE, client_udp::DIRECTCALLBACKREQ, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self { tcp_port: input.u16()?, user_hash: input.hash16()?, connect_options: input.u8()? })
    }
}

/// OP_FWCHECKUDPREQ: a client testing its UDP port asks us to send a datagram to its internal and external port,
/// obfuscated with `sender_key`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UdpCheckRequest {
    pub internal_port: u16,
    pub external_port: u16,
    pub sender_key: u32,
}

impl UdpCheckRequest {
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        out.u16(self.internal_port).u16(self.external_port).u32(self.sender_key);
        Packet::written(protocol::EMULE, extended::FWCHECKUDPREQ, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self { internal_port: input.u16()?, external_port: input.u16()?, sender_key: input.u32()? })
    }
}
