// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Messages between two clients for a download, after the hello: asking for a file, its parts and hash set, a place in
//! the upload queue, then blocks of data.

use std::net::Ipv6Addr;

use crate::files::hash::ed2k;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter, StringEncoding};
use crate::protocol::packet::opcodes::{client, extended};
use crate::protocol::packet::{Packet, protocol};
use crate::util::ipv6;

/// Blocks asked per request.
pub const BLOCKS_PER_REQUEST: usize = 3;

const MAX_PARTS: usize = (ed2k::MAX_FILE_SIZE / ed2k::PARTSIZE) as usize + 2;
const HASH_LENGTH: usize = 16;

/// Tells a client our new IPv6 address.
pub fn ipv6_changed(ours: &Ipv6Addr) -> Packet {
    Packet::of(protocol::EDONKEY, client::CHANGE_CLIENT_IP, ours.octets())
}

/// The new IPv6 address a client tells, if peers on the Internet may reach it.
pub fn changed_ipv6(packet: &Packet) -> Result<Option<Ipv6Addr>, DecodeError> {
    Ok(ipv6::public_of(packet.reader().bytes(16)?))
}

/// A message that names its file first.
pub fn for_file(opcode: u8, file: &[u8; 16]) -> Packet {
    Packet::of(protocol::EDONKEY, opcode, *file)
}

/// The file hash at the start of a message.
pub fn file_of(packet: &Packet) -> Result<[u8; 16], DecodeError> {
    packet.reader().hash16()
}

/// OP_REQFILENAMEANSWER: the name the uploader gives the file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileName {
    pub file: [u8; 16],
    pub name: String,
}

impl FileName {
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        out.hash16(&self.file).string16(&self.name, StringEncoding::Utf8)?;
        Ok(Packet::written(protocol::EDONKEY, client::REQFILENAMEANSWER, out))
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self { file: input.hash16()?, name: input.string16()? })
    }
}

/// The parts a client has of a file, one flag per eD2K part; a count of zero means the whole file.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct PartStatus {
    pub part_count: usize,
    pub parts: Vec<bool>,
}

impl PartStatus {
    pub const COMPLETE: Self = Self { part_count: 0, parts: Vec::new() };

    /// The status of a file of `size` being downloaded, with `complete` data parts; a size that is an exact multiple
    /// of the part size counts one more part, empty and so complete.
    pub fn of(size: u64, complete: &[bool]) -> Self {
        let part_count = usize::try_from(size / ed2k::PARTSIZE).map_or(usize::MAX, |n| n.saturating_add(1));
        let mut parts: Vec<bool> = (0..part_count).map(|i| complete.get(i).copied().unwrap_or(false)).collect();
        if size.is_multiple_of(ed2k::PARTSIZE)
            && let Some(last) = parts.last_mut()
        {
            *last = true;
        }
        Self { part_count, parts }
    }

    pub fn has(&self, part: usize) -> bool {
        self.parts.get(part).copied().unwrap_or(false)
    }

    /// The protocol's layout: a u16 count, then the flags eight parts a byte, lowest bit first.
    pub fn write(&self, out: &mut PacketWriter) -> Result<(), EncodeError> {
        out.count16(self.part_count)?;
        let mut bytes = vec![0u8; self.part_count.div_ceil(8)];
        for (byte, flags) in bytes.iter_mut().zip(self.parts.chunks(8)) {
            *byte = flags.iter().rev().fold(0, |bits, has| bits << 1 | u8::from(*has));
        }
        out.bytes(&bytes);
        Ok(())
    }

    pub fn read(input: &mut PacketReader<'_>) -> Result<Self, DecodeError> {
        let part_count = usize::from(input.u16()?);
        if part_count > MAX_PARTS {
            return Err(DecodeError::new(format!("part count {part_count} exceeds any file size")));
        }
        let bytes = input.bytes(part_count.div_ceil(8))?;
        // The padding bits of the last byte name no part.
        let parts =
            bytes.iter().flat_map(|byte| (0..8).map(move |bit| byte >> bit & 1 == 1)).take(part_count).collect();
        Ok(Self { part_count, parts })
    }
}

/// OP_FILESTATUS: the parts the uploader has, one flag per eD2K part; a count of zero means the whole file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileStatus {
    pub file: [u8; 16],
    pub part_count: usize,
    pub parts: Vec<bool>,
}

impl FileStatus {
    /// Whether the uploader has `part`.
    pub fn has(&self, part: usize) -> bool {
        self.part_count == 0 || self.parts.get(part).copied().unwrap_or(false)
    }

    /// Whether the status can be of a file of `size`: none for a complete file, else one flag per part, counting the
    /// empty last part of a size that is an exact multiple of the part size.
    pub fn fits(&self, size: u64) -> bool {
        self.part_count == 0
            || u64::try_from(self.part_count).is_ok_and(|n| Some(n) == (size / ed2k::PARTSIZE).checked_add(1))
    }

    /// The data parts it has, for a file of `size`.
    pub fn data_parts(&self, size: u64) -> Vec<bool> {
        (0..ed2k::part_count(size)).map(|part| self.has(part)).collect()
    }

    pub fn status(&self) -> PartStatus {
        PartStatus { part_count: self.part_count, parts: self.parts.clone() }
    }

    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        out.hash16(&self.file);
        self.status().write(&mut out)?;
        Ok(Packet::written(protocol::EDONKEY, client::FILESTATUS, out))
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let file = input.hash16()?;
        let PartStatus { part_count, parts } = PartStatus::read(&mut input)?;
        Ok(Self { file, part_count, parts })
    }
}

/// OP_HASHSETANSWER: the part hashes of a file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HashSet {
    pub file: [u8; 16],
    pub parts: Vec<[u8; 16]>,
}

impl HashSet {
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        out.hash16(&self.file).count16(self.parts.len())?;
        for part in &self.parts {
            out.hash16(part);
        }
        Ok(Packet::written(protocol::EDONKEY, client::HASHSETANSWER, out))
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let file = input.hash16()?;
        let announced = input.u16()?;
        let count = input.count(u64::from(announced), HASH_LENGTH, MAX_PARTS)?;
        let parts = (0..count).map(|_| input.hash16()).collect::<Result<_, _>>()?;
        Ok(Self { file, parts })
    }
}

/// A block range `[start, end)` of a request.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Range {
    pub start: u64,
    pub end: u64,
}

impl Range {
    pub fn is_empty(&self) -> bool {
        self.start >= self.end
    }
}

/// OP_REQUESTPARTS: three ranges, end exclusive; unused slots are empty ranges. Files above 4 GB need the 64-bit
/// OP_REQUESTPARTS_I64.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RequestParts {
    pub file: [u8; 16],
    pub ranges: [Range; BLOCKS_PER_REQUEST],
}

impl RequestParts {
    pub fn encode(&self, wide: bool) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        out.hash16(&self.file);
        for offset in self.ranges.iter().map(|r| r.start).chain(self.ranges.iter().map(|r| r.end)) {
            offset_of(&mut out, offset, wide)?;
        }
        Ok(if wide {
            Packet::written(protocol::EMULE, extended::REQUESTPARTS_I64, out)
        } else {
            Packet::written(protocol::EDONKEY, client::REQUESTPARTS, out)
        })
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let wide = packet.protocol() == protocol::EMULE;
        let mut input = packet.reader();
        let file = input.hash16()?;
        let mut ranges = [Range::default(); BLOCKS_PER_REQUEST];
        for range in &mut ranges {
            range.start = read_offset(&mut input, wide)?;
        }
        for range in &mut ranges {
            let mut end = read_offset(&mut input, wide)?;
            // Some clients ask for the block ending at 4 GiB in 32 bits, its end wrapped to 0.
            if !wide && end == 0 && range.start > 0 {
                end = 1 << 32;
            }
            if !is_offset(range.start) || !is_offset(end) {
                return Err(DecodeError::new(format!("invalid range {}-{end}", range.start)));
            }
            // A backwards range asks for nothing: skipped like an unused slot, not a reason to hang up.
            range.end = end.max(range.start);
        }
        Ok(Self { file, ranges })
    }
}

/// OP_SENDINGPART(_I64): data of `[start, end)`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SendingPart {
    pub file: [u8; 16],
    pub start: u64,
    pub end: u64,
    pub data: Vec<u8>,
}

impl SendingPart {
    pub fn encode(&self, wide: bool) -> Result<Packet, EncodeError> {
        let length = self.end.checked_sub(self.start).and_then(|n| usize::try_from(n).ok());
        if length != Some(self.data.len()) {
            return Err(EncodeError::new("data does not fill its range"));
        }
        let mut out = PacketWriter::new();
        out.hash16(&self.file);
        offset_of(&mut out, self.start, wide)?;
        offset_of(&mut out, self.end, wide)?;
        out.bytes(&self.data);
        Ok(if wide {
            Packet::written(protocol::EMULE, extended::SENDINGPART_I64, out)
        } else {
            Packet::written(protocol::EDONKEY, client::SENDINGPART, out)
        })
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let wide = packet.protocol() == protocol::EMULE;
        let mut input = packet.reader();
        let file = input.hash16()?;
        let start = read_offset(&mut input, wide)?;
        let end = read_offset(&mut input, wide)?;
        let fills = end.checked_sub(start).and_then(|n| usize::try_from(n).ok()) == Some(input.remaining());
        if !is_offset(start) || !is_offset(end) || !fills {
            return Err(DecodeError::new(format!("data of {} bytes for {start}-{end}", input.remaining())));
        }
        Ok(Self { file, start, end, data: input.rest().to_vec() })
    }
}

/// OP_COMPRESSEDPART(_I64): one chunk of the zlib stream of the block that starts at `block_start`; every chunk
/// repeats the stream's total size.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompressedPart {
    pub file: [u8; 16],
    pub block_start: u64,
    pub compressed_size: u32,
    pub chunk: Vec<u8>,
}

impl CompressedPart {
    pub fn encode(&self, wide: bool) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        out.hash16(&self.file);
        offset_of(&mut out, self.block_start, wide)?;
        out.u32(self.compressed_size).bytes(&self.chunk);
        Ok(Packet::written(
            protocol::EMULE,
            if wide { extended::COMPRESSEDPART_I64 } else { extended::COMPRESSEDPART },
            out,
        ))
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let wide = packet.opcode() == extended::COMPRESSEDPART_I64;
        let mut input = packet.reader();
        let file = input.hash16()?;
        let block_start = read_offset(&mut input, wide)?;
        let compressed_size = input.u32()?;
        let chunk = input.rest();
        if !is_offset(block_start)
            || chunk.is_empty()
            || u64::try_from(chunk.len()).map_or(true, |n| n > u64::from(compressed_size))
        {
            return Err(DecodeError::new(format!("invalid compressed chunk at {block_start}")));
        }
        Ok(Self { file, block_start, compressed_size, chunk: chunk.to_vec() })
    }
}

/// OP_QUEUERANK: our place in the uploader's queue.
pub fn decode_queue_rank(packet: &Packet) -> Result<u32, DecodeError> {
    let mut input = packet.reader();
    if packet.protocol() == protocol::EMULE { input.u16().map(u32::from) } else { input.u32() }
}

// No file is larger than the network maximum.
fn is_offset(value: u64) -> bool {
    value <= ed2k::MAX_FILE_SIZE
}

fn read_offset(input: &mut PacketReader<'_>, wide: bool) -> Result<u64, DecodeError> {
    if wide { input.u64() } else { input.u32().map(u64::from) }
}

fn offset_of(out: &mut PacketWriter, offset: u64, wide: bool) -> Result<(), EncodeError> {
    if wide {
        out.u64(offset);
    } else {
        out.offset32(offset)?;
    }
    Ok(())
}
