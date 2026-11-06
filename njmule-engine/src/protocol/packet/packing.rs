// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! zlib packing of whole packets (protocols `0xD4` and `0xE5`). Inflating always has an output cap, so a small
//! "zip bomb" cannot exhaust memory.

use miniz_oxide::inflate::{self, TINFLStatus};

use crate::protocol::io::{DecodeError, EncodeError};
use crate::protocol::packet::{Packet, protocol};

/// Inflated payload cap for packets from clients.
pub const MAX_CLIENT_UNPACKED: usize = 50_000;
/// Inflated payload cap for packets from servers (search results, source lists).
pub const MAX_SERVER_UNPACKED: usize = 250_000;
/// zlib's best compression, which packets are packed with.
pub const BEST_COMPRESSION: u8 = 9;

/// Packs with the best compression when that makes the packet smaller, else returns it.
pub fn pack(packet: Packet) -> Result<Packet, EncodeError> {
    let packed_protocol = match packet.protocol() {
        protocol::EMULE | protocol::EDONKEY => protocol::PACKED,
        protocol::KAD => protocol::KAD_PACKED,
        other => return Err(EncodeError::new(format!("cannot pack protocol 0x{other:02X}"))),
    };
    let packed = deflate(packet.payload(), BEST_COMPRESSION);
    Ok(if packed.len() < packet.length() { Packet::of(packed_protocol, packet.opcode(), packed) } else { packet })
}

/// Unpacks to `0xC5` (eD2K) or `0xE4` (Kad); other packets pass unchanged.
pub fn unpack(packet: Packet, max_unpacked: usize) -> Result<Packet, DecodeError> {
    let unpacked_protocol = match packet.protocol() {
        protocol::PACKED => protocol::EMULE,
        protocol::KAD_PACKED => protocol::KAD,
        _ => return Ok(packet),
    };
    Ok(Packet::of(unpacked_protocol, packet.opcode(), inflate(packet.payload(), max_unpacked)?))
}

/// Unpacks a packet from a server: inflated `0xD4` frames carry eDonkey messages.
pub fn unpack_from_server(packet: Packet) -> Result<Packet, DecodeError> {
    if packet.protocol() != protocol::PACKED {
        return Ok(packet);
    }
    Ok(Packet::of(protocol::EDONKEY, packet.opcode(), inflate(packet.payload(), MAX_SERVER_UNPACKED)?))
}

// The start of the data compressing tells about the whole.
const SAMPLE: usize = 16 * 1024;

/// Whether compressing `data` at `level` is worth trying: its first 16 KiB shrink by a thirty-second at least. Data
/// compressed already, as media and archives are whatever their names, does not, and is spared its whole compression.
pub fn shrinks(data: &[u8], level: u8) -> bool {
    let sample = data.get(..data.len().min(SAMPLE)).unwrap_or(data);
    let length = sample.len();
    !sample.is_empty() && deflate(sample, level).len() <= length.saturating_sub(length / 32)
}

pub fn deflate(data: &[u8], level: u8) -> Vec<u8> {
    miniz_oxide::deflate::compress_to_vec_zlib(data, level)
}

/// Inflates a zlib stream that must end within `max_size` bytes of output.
pub fn inflate(data: &[u8], max_size: usize) -> Result<Vec<u8>, DecodeError> {
    // One byte more than the cap tells a stream ending at the cap from one going past it.
    let inflated = inflate::decompress_to_vec_zlib_with_limit(data, max_size.saturating_add(1)).map_err(|e| {
        DecodeError::new(match e.status {
            TINFLStatus::HasMoreOutput => format!("inflated data exceeds {max_size} bytes"),
            TINFLStatus::FailedCannotMakeProgress | TINFLStatus::NeedsMoreInput => {
                "truncated or unsupported zlib data".to_owned()
            }
            _ => "corrupt zlib data".to_owned(),
        })
    })?;
    if inflated.len() > max_size {
        return Err(DecodeError::new(format!("inflated data exceeds {max_size} bytes")));
    }
    Ok(inflated)
}
