// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Pieces shared by the .met codecs.

use data_encoding::BASE32_NOPAD;

use crate::files::hash::ed2k::{MAX_FILE_SIZE, PARTSIZE};
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter, StringEncoding};
use crate::protocol::tag::{Ed2kTagFormat, Tag, tag_codec};

/// Part hashes of the largest supported file, plus the empty part.
pub const MAX_PART_HASHES: usize = (MAX_FILE_SIZE / PARTSIZE + 1) as usize;
/// Tags per record; part files carry two per gap, so this is generous.
pub const MAX_TAGS: usize = 1_000_000;

/// A file hash with its part hashes, `<hash 16><u16 count><hash 16 × count>`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HashSet {
    pub file_hash: [u8; 16],
    pub part_hashes: Vec<[u8; 16]>,
}

pub fn read_hash_set(input: &mut PacketReader<'_>) -> Result<HashSet, DecodeError> {
    let file_hash = input.hash16()?;
    let declared = input.u16()?;
    let count = input.count(u64::from(declared), 16, MAX_PART_HASHES)?;
    let part_hashes = (0..count).map(|_| input.hash16()).collect::<Result<_, _>>()?;
    Ok(HashSet { file_hash, part_hashes })
}

pub fn write_hash_set(
    out: &mut PacketWriter,
    file_hash: &[u8; 16],
    part_hashes: &[[u8; 16]],
) -> Result<(), EncodeError> {
    if part_hashes.len() > MAX_PART_HASHES {
        return Err(EncodeError::new(format!("too many part hashes: {}", part_hashes.len())));
    }
    out.hash16(file_hash).count16(part_hashes.len())?;
    for hash in part_hashes {
        out.hash16(hash);
    }
    Ok(())
}

/// `<u32 count><old-encoding tags>`.
pub fn read_tags(input: &mut PacketReader<'_>) -> Result<Vec<Tag>, DecodeError> {
    let count = input.u32()?;
    tag_codec::read_ed2k_list(input, u64::from(count), MAX_TAGS)
}

pub fn write_tags(out: &mut PacketWriter, tags: &[Tag]) -> Result<(), EncodeError> {
    out.count32(tags.len())?;
    tag_codec::write_ed2k_list(out, tags, Ed2kTagFormat::Old, StringEncoding::Utf8BomIfNeeded)
}

/// The first byte of `file`, which must be one of `accepted`.
pub fn header(input: &mut PacketReader<'_>, file: &str, accepted: &[u8]) -> Result<u8, DecodeError> {
    let header = input.u8()?;
    if accepted.contains(&header) {
        Ok(header)
    } else {
        Err(DecodeError::new(format!("{file}: unsupported header 0x{header:02X}")))
    }
}

/// An AICH hash kept as 32 Base32 characters; anything else is none, as other clients' files may hold anything.
pub fn aich_hash(text: &str) -> Option<[u8; 20]> {
    if text.len() != 32 {
        return None;
    }
    BASE32_NOPAD.decode(text.as_bytes()).ok().and_then(|bytes| bytes.try_into().ok())
}
