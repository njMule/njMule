// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! TCP framing: `<protocol u8><length u32><opcode u8><payload>`, where the length counts the opcode byte.

use std::io::{self, ErrorKind, Read};

use crate::protocol::io::{DecodeError, EncodeError};
use crate::protocol::packet::frame_limit::FrameLimit;
use crate::protocol::packet::{Packet, protocol};

pub const HEADER_SIZE: usize = 6;

pub fn encode(packet: &Packet) -> Result<Vec<u8>, EncodeError> {
    let counted = packet
        .length()
        .checked_add(1)
        .and_then(|n| u32::try_from(n).ok())
        .ok_or_else(|| EncodeError::new(format!("payload of {} bytes too long for a frame", packet.length())))?;
    let mut out = Vec::with_capacity(HEADER_SIZE.saturating_add(packet.length()));
    out.push(packet.protocol());
    out.extend_from_slice(&counted.to_le_bytes());
    out.push(packet.opcode());
    out.extend_from_slice(packet.payload());
    Ok(out)
}

/// Reads one frame: `None` at a clean end of stream before its first byte. A truncated frame, an unknown protocol
/// byte or a payload beyond `limit` is an [`ErrorKind::InvalidData`] error carrying a [`DecodeError`].
pub fn read(input: &mut impl Read, limit: &impl FrameLimit) -> io::Result<Option<Packet>> {
    let mut header = [0u8; HEADER_SIZE];
    let [first, rest @ ..] = &mut header;
    if input.read(std::slice::from_mut(first))? == 0 {
        return Ok(None);
    }
    read_fully(input, rest)?;
    let [protocol, l0, l1, l2, l3, opcode] = header;
    let length = check_header(protocol, u32::from_le_bytes([l0, l1, l2, l3]), opcode, limit).map_err(invalid)?;
    let mut payload = vec![0u8; length];
    read_fully(input, &mut payload)?;
    Ok(Some(Packet::of(protocol, opcode, payload)))
}

/// The payload length of a frame with this header, decided before anything is allocated.
pub fn check_header(protocol: u8, counted: u32, opcode: u8, limit: &impl FrameLimit) -> Result<usize, DecodeError> {
    if !protocol::is_tcp(protocol) {
        return Err(DecodeError::new(format!("unknown protocol byte 0x{protocol:02X}")));
    }
    let length = counted.checked_sub(1).ok_or_else(|| DecodeError::new("frame length 0 cannot hold the opcode"))?;
    let max = limit
        .max_payload(protocol, opcode)
        .ok_or_else(|| DecodeError::new(format!("opcode 0x{protocol:02X}/0x{opcode:02X} not allowed here")))?;
    usize::try_from(length).ok().filter(|length| *length <= max).ok_or_else(|| {
        DecodeError::new(format!("frame 0x{protocol:02X}/0x{opcode:02X} of {length} bytes exceeds {max}"))
    })
}

fn read_fully(input: &mut impl Read, buffer: &mut [u8]) -> io::Result<()> {
    input.read_exact(buffer).map_err(|e| {
        if e.kind() == ErrorKind::UnexpectedEof {
            invalid(DecodeError::new("connection closed inside a frame"))
        } else {
            e
        }
    })
}

fn invalid(error: DecodeError) -> io::Error {
    io::Error::new(ErrorKind::InvalidData, error)
}
