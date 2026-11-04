// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! UDP framing: `<protocol u8><opcode u8><payload>`, one packet per datagram.

use crate::protocol::io::DecodeError;
use crate::protocol::packet::Packet;

pub const HEADER_SIZE: usize = 2;

pub fn encode(packet: &Packet) -> Vec<u8> {
    let mut out = Vec::with_capacity(HEADER_SIZE.saturating_add(packet.length()));
    out.push(packet.protocol());
    out.push(packet.opcode());
    out.extend_from_slice(packet.payload());
    out
}

/// Decodes a received datagram; shorter than a header is malformed.
pub fn decode(datagram: &[u8]) -> Result<Packet, DecodeError> {
    match datagram {
        [protocol, opcode, payload @ ..] => Ok(Packet::of(*protocol, *opcode, payload)),
        _ => Err(DecodeError::new(format!("datagram of {} bytes has no header", datagram.len()))),
    }
}
