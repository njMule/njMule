// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Reading requested blocks from disk and cutting them into data packets.

use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};
use std::path::Path;

use crate::protocol::ed2k::client_messages::{CompressedPart, SendingPart};
use crate::protocol::io::EncodeError;
use crate::protocol::packet::{Packet, packing};

/// Bytes of file data per packet.
pub const CHUNK: usize = 10_240;

// A shorter rest is sent in one packet rather than as a small last one.
const LAST_CHUNK: usize = 13_000;
const ZLIB_LEVEL: u8 = 1;

/// Reads `[start, start + length)` of `file`; blocking, for the callers' blocking tasks.
pub fn read(file: &Path, start: u64, length: usize) -> io::Result<Vec<u8>> {
    let mut opened = File::open(file)?;
    opened.seek(SeekFrom::Start(start))?;
    let mut data = vec![0; length];
    opened.read_exact(&mut data).map_err(|e| {
        if e.kind() == io::ErrorKind::UnexpectedEof {
            io::Error::new(io::ErrorKind::UnexpectedEof, format!("file shorter than the block {start}"))
        } else {
            e
        }
    })?;
    Ok(data)
}

/// Reads `[start, start + length)` of `data` and cuts it into the packets of `file`; blocking.
pub fn read_packets(data: &Path, file: [u8; 16], start: u64, length: usize, compress: bool) -> io::Result<Vec<Packet>> {
    let bytes = read(data, start, length)?;
    packets(file, start, &bytes, compress).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))
}

/// The packets carrying `data` of `file` from `start`: zlib-compressed when asked and smaller, 64-bit offsets when the
/// block ends above 4 GB.
pub fn packets(file: [u8; 16], start: u64, data: &[u8], compress: bool) -> Result<Vec<Packet>, EncodeError> {
    let length = u64::try_from(data.len()).unwrap_or(u64::MAX);
    let wide = start.saturating_add(length) > 0xFFFF_FFFF;
    if compress && packing::shrinks(data, ZLIB_LEVEL) {
        let stream = packing::deflate(data, ZLIB_LEVEL);
        if stream.len() < data.len() {
            let compressed_size = u32::try_from(stream.len())
                .map_err(|_| EncodeError::new(format!("compressed block too long: {}", stream.len())))?;
            return pieces(stream.len())
                .map(|(at, end)| {
                    let chunk = stream.get(at..end).unwrap_or_default().to_vec();
                    CompressedPart { file, block_start: start, compressed_size, chunk }.encode(wide)
                })
                .collect();
        }
    }
    pieces(data.len())
        .map(|(at, end)| {
            let from = start.saturating_add(u64::try_from(at).unwrap_or(u64::MAX));
            let to = start.saturating_add(u64::try_from(end).unwrap_or(u64::MAX));
            SendingPart { file, start: from, end: to, data: data.get(at..end).unwrap_or_default().to_vec() }
                .encode(wide)
        })
        .collect()
}

// The pieces of `length` bytes, each of a chunk but the last, which takes a short rest along.
fn pieces(length: usize) -> impl Iterator<Item = (usize, usize)> {
    let mut at = 0;
    std::iter::from_fn(move || {
        if at >= length {
            return None;
        }
        let end = if length.saturating_sub(at) < LAST_CHUNK { length } else { at.saturating_add(CHUNK) };
        let piece = (at, end);
        at = end;
        Some(piece)
    })
}
