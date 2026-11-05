// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Shared and once-shared files, `known.met`: `<0x0E|0x0F><u32 count>` then `<mtime u32><hash set><u32 tag
//! count><tags>` per file; 0x0F allows 64-bit sizes.

use crate::files::hash::ed2k::{self, MAX_FILE_SIZE, OLD_MAX_FILE_SIZE};
use crate::persist::format::met_codecs;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter};
use crate::protocol::tag::{Tag, file_tag, tags};

pub const HEADER: u8 = 0x0E;
pub const HEADER_64BIT: u8 = 0x0F;
const MAX_FILES: usize = 1_000_000;

#[derive(Debug, Clone, PartialEq)]
pub struct KnownFile {
    pub mtime: u32,
    pub file_hash: [u8; 16],
    pub part_hashes: Vec<[u8; 16]>,
    pub tags: Vec<Tag>,
}

impl KnownFile {
    pub fn name(&self) -> Option<&str> {
        tags::string_value(&self.tags, file_tag::FILENAME)
    }

    /// FT_FILESIZE, with FT_FILESIZE_HI of old 32-bit entries.
    pub fn size(&self) -> Option<u64> {
        let low = tags::int_value(&self.tags, file_tag::FILESIZE)?;
        let high = tags::int_value(&self.tags, file_tag::FILESIZE_HI).unwrap_or(0);
        Some(high.wrapping_shl(32) | low)
    }

    /// FT_ULPRIORITY: 0 low, 1 normal, 2 high, 3 release, 4 very low, 5 auto.
    pub fn upload_priority(&self) -> Option<u64> {
        tags::int_value(&self.tags, file_tag::ULPRIORITY)
    }

    pub fn aich_hash(&self) -> Option<[u8; 20]> {
        tags::string_value(&self.tags, file_tag::AICH_HASH).and_then(met_codecs::aich_hash)
    }

    /// All-time requests of the file (FT_ATREQUESTED).
    pub fn requests(&self) -> u64 {
        tags::int_value(&self.tags, file_tag::ATREQUESTED).unwrap_or(0)
    }

    /// All-time requests that got an upload slot (FT_ATACCEPTED).
    pub fn accepted(&self) -> u64 {
        tags::int_value(&self.tags, file_tag::ATACCEPTED).unwrap_or(0)
    }

    /// All-time uploaded bytes (FT_ATTRANSFERRED with FT_ATTRANSFERREDHI).
    pub fn transferred(&self) -> u64 {
        tags::int_value(&self.tags, file_tag::ATTRANSFERREDHI).unwrap_or(0).wrapping_shl(32)
            | tags::int_value(&self.tags, file_tag::ATTRANSFERRED).unwrap_or(0)
    }

    /// Whether the part hashes match the size and produce the file hash.
    pub fn is_consistent(&self) -> bool {
        let Some(size) = self.size().filter(|size| *size <= MAX_FILE_SIZE) else {
            return false;
        };
        self.part_hashes.len() == ed2k::hash_set_size(size)
            && (self.part_hashes.is_empty() || ed2k::file_hash(&self.part_hashes) == Ok(self.file_hash))
    }
}

pub fn read(data: &[u8]) -> Result<Vec<KnownFile>, DecodeError> {
    let mut input = PacketReader::of(data);
    met_codecs::header(&mut input, "known.met", &[HEADER, HEADER_64BIT])?;
    let declared = input.u32()?;
    let count = input.count(u64::from(declared), 4 + 16 + 2 + 4, MAX_FILES)?;
    let mut files = Vec::with_capacity(count);
    records(&mut input, count, &mut files)?;
    Ok(files)
}

/// The records of a damaged file that come before the damage; none when its header cannot be read.
pub fn salvage(data: &[u8]) -> Vec<KnownFile> {
    let mut files = Vec::new();
    let mut input = PacketReader::of(data);
    // What follows the damage is lost.
    let _ = met_codecs::header(&mut input, "known.met", &[HEADER, HEADER_64BIT]).and_then(|_| input.u32()).and_then(
        |declared| {
            let count = usize::try_from(declared).map_or(MAX_FILES, |count| count.min(MAX_FILES));
            records(&mut input, count, &mut files)
        },
    );
    files
}

fn records(input: &mut PacketReader<'_>, count: usize, files: &mut Vec<KnownFile>) -> Result<(), DecodeError> {
    for _ in 0..count {
        let mtime = input.u32()?;
        let set = met_codecs::read_hash_set(input)?;
        let tags = met_codecs::read_tags(input)?;
        files.push(KnownFile { mtime, file_hash: set.file_hash, part_hashes: set.part_hashes, tags });
    }
    Ok(())
}

pub fn write(files: &[KnownFile]) -> Result<Vec<u8>, EncodeError> {
    let large = files.iter().any(|file| file.size().unwrap_or(0) > OLD_MAX_FILE_SIZE);
    let mut out = PacketWriter::new();
    out.u8(if large { HEADER_64BIT } else { HEADER }).count32(files.len())?;
    for file in files {
        out.u32(file.mtime);
        met_codecs::write_hash_set(&mut out, &file.file_hash, &file.part_hashes)?;
        met_codecs::write_tags(&mut out, &file.tags)?;
    }
    Ok(out.into_byte_array())
}
