// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Packets, their protocol bytes and opcodes, their TCP and UDP framing and their zlib packing.

pub mod frame_limit;
pub mod frame_limits;
pub mod opcodes;
pub mod packing;
pub mod protocol;
pub mod tcp_framing;
pub mod udp_framing;

use std::fmt;

use crate::protocol::io::{PacketReader, PacketWriter};

/// A packet: protocol byte, opcode and payload.
#[derive(Clone, PartialEq, Eq, Hash)]
pub struct Packet {
    protocol: u8,
    opcode: u8,
    payload: Vec<u8>,
}

impl Packet {
    pub fn of(protocol: u8, opcode: u8, payload: impl Into<Vec<u8>>) -> Self {
        Self { protocol, opcode, payload: payload.into() }
    }

    pub fn written(protocol: u8, opcode: u8, payload: PacketWriter) -> Self {
        Self::of(protocol, opcode, payload.into_byte_array())
    }

    pub fn empty(protocol: u8, opcode: u8) -> Self {
        Self::of(protocol, opcode, Vec::new())
    }

    pub fn protocol(&self) -> u8 {
        self.protocol
    }

    pub fn opcode(&self) -> u8 {
        self.opcode
    }

    pub fn length(&self) -> usize {
        self.payload.len()
    }

    /// A reader over the payload (no copy).
    pub fn reader(&self) -> PacketReader<'_> {
        PacketReader::of(&self.payload)
    }

    pub fn payload(&self) -> &[u8] {
        &self.payload
    }

    pub fn into_payload(self) -> Vec<u8> {
        self.payload
    }
}

impl fmt::Debug for Packet {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        const SHOWN: usize = 32;
        write!(f, "Packet[0x{:02X}/0x{:02X}, {} bytes: ", self.protocol, self.opcode, self.payload.len())?;
        for byte in self.payload.iter().take(SHOWN) {
            write!(f, "{byte:02x}")?;
        }
        f.write_str(if self.payload.len() > SHOWN { "…]" } else { "]" })
    }
}
