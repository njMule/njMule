// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A download's metadata, `NNN.part.met`: `<0xE0|0xE2><mtime u32><hash set><u32 tag count><tags>`; 0xE2 marks files
//! larger than 4 GB. Missing ranges are stored as tag pairs named `"\x09<n>"` (start) and `"\x0A<n>"` (end, exclusive).

use std::collections::{BTreeMap, HashMap};

use crate::files::hash::ed2k::{MAX_FILE_SIZE, OLD_MAX_FILE_SIZE};
use crate::persist::format::met_codecs;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter};
use crate::protocol::tag::{Tag, TagName, file_tag, tags};

pub const VERSION: u8 = 0xE0;
pub const VERSION_LARGE: u8 = 0xE2;
/// FT_STATUS of a paused file; other clients take any value but 0 as paused.
pub const PAUSED: u64 = 1;
/// FT_STATUS of a stopped file.
pub const STOPPED: u64 = 2;

/// A missing range `[start, end)`, never empty.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Gap {
    start: u64,
    end: u64,
}

impl Gap {
    /// The range `[start, end)`; none when it is empty.
    pub fn new(start: u64, end: u64) -> Option<Self> {
        (start < end).then_some(Self { start, end })
    }

    pub fn start(&self) -> u64 {
        self.start
    }

    pub fn end(&self) -> u64 {
        self.end
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Contents {
    pub version: u8,
    pub mtime: u32,
    pub file_hash: [u8; 16],
    pub part_hashes: Vec<[u8; 16]>,
    pub tags: Vec<Tag>,
}

impl Contents {
    pub fn name(&self) -> Option<&str> {
        tags::string_value(&self.tags, file_tag::FILENAME)
    }

    pub fn size(&self) -> Option<u64> {
        tags::int_value(&self.tags, file_tag::FILESIZE)
    }

    pub fn aich_hash(&self) -> Option<[u8; 20]> {
        tags::string_value(&self.tags, file_tag::AICH_HASH).and_then(met_codecs::aich_hash)
    }

    /// Paused or stopped (FT_STATUS ≠ 0).
    pub fn paused(&self) -> bool {
        tags::int_value(&self.tags, file_tag::STATUS).unwrap_or(0) != 0
    }

    /// Stopped rather than only paused (FT_STATUS = 2).
    pub fn stopped(&self) -> bool {
        tags::int_value(&self.tags, file_tag::STATUS).unwrap_or(0) == STOPPED
    }

    /// FT_DLPRIORITY: 0 low, 1 normal, 2 high, 5 auto.
    pub fn download_priority(&self) -> Option<u64> {
        tags::int_value(&self.tags, file_tag::DLPRIORITY)
    }

    pub fn category(&self) -> Option<u64> {
        tags::int_value(&self.tags, file_tag::CATEGORY)
    }

    /// The missing ranges, clamped to the file size and sorted by start. Incomplete pairs and ranges starting beyond
    /// the end are dropped; overlaps are left for the gap list to merge.
    pub fn gaps(&self) -> Vec<Gap> {
        // Values above 2^63 count as absent, as other readers take them for negative numbers.
        let signed = |value: u64| i64::try_from(value).ok();
        let size = self.size().map_or(Some(0), signed);
        let mut pairs: HashMap<&[u8], (Option<u64>, Option<u64>)> = HashMap::new();
        for tag in &self.tags {
            if let Tag::Int { name, value } = tag
                && let Some((&kind, number)) = gap_name(name)
            {
                // The names are numbers: "01" and "1" name one pair.
                let pair = pairs.entry(without_leading_zeros(number)).or_default();
                let value = signed(*value).map(|_| *value);
                if kind == file_tag::GAPSTART {
                    pair.0 = value;
                } else {
                    pair.1 = value;
                }
            }
        }
        let mut sorted: BTreeMap<u64, u64> = BTreeMap::new();
        if let Some(size) = size.and_then(|size| u64::try_from(size).ok()) {
            for (start, end) in pairs.into_values() {
                if let (Some(start), Some(end)) = (start, end)
                    && start < size
                    && start < end.min(size)
                {
                    let end = end.min(size);
                    sorted.entry(start).and_modify(|kept| *kept = (*kept).max(end)).or_insert(end);
                }
            }
        }
        sorted.into_iter().filter_map(|(start, end)| Gap::new(start, end)).collect()
    }

    /// A copy whose gap tags describe `gaps`.
    #[must_use]
    pub fn with_gaps(&self, gaps: &[Gap]) -> Self {
        let mut tags: Vec<Tag> = self.tags.iter().filter(|tag| gap_name(tag.name()).is_none()).cloned().collect();
        for (i, gap) in gaps.iter().enumerate() {
            tags.push(Tag::Int { name: gap_tag(file_tag::GAPSTART, i), value: gap.start });
            tags.push(Tag::Int { name: gap_tag(file_tag::GAPEND, i), value: gap.end });
        }
        Self {
            version: self.version,
            mtime: self.mtime,
            file_hash: self.file_hash,
            part_hashes: self.part_hashes.clone(),
            tags,
        }
    }
}

pub fn read(data: &[u8]) -> Result<Contents, DecodeError> {
    let mut input = PacketReader::of(data);
    let version = input.u8()?;
    if version != VERSION && version != VERSION_LARGE {
        return Err(DecodeError::new(format!("unsupported part.met version 0x{version:02X}")));
    }
    let mtime = input.u32()?;
    let set = met_codecs::read_hash_set(&mut input)?;
    let tags = met_codecs::read_tags(&mut input)?;
    let contents = Contents { version, mtime, file_hash: set.file_hash, part_hashes: set.part_hashes, tags };
    let size = contents.size().ok_or_else(|| DecodeError::new("part.met without file size"))?;
    if size == 0 || size > MAX_FILE_SIZE {
        return Err(DecodeError::new(format!("part.met file size out of range: {size}")));
    }
    Ok(contents)
}

/// Writes the file; the version follows the size, as 64-bit values need 0xE2.
pub fn write(contents: &Contents) -> Result<Vec<u8>, EncodeError> {
    let size = contents.size().ok_or_else(|| EncodeError::new("no file size"))?;
    let mut out = PacketWriter::new();
    out.u8(if size > OLD_MAX_FILE_SIZE { VERSION_LARGE } else { VERSION }).u32(contents.mtime);
    met_codecs::write_hash_set(&mut out, &contents.file_hash, &contents.part_hashes)?;
    met_codecs::write_tags(&mut out, &contents.tags)?;
    Ok(out.into_byte_array())
}

// The kind byte and the digits of a gap tag's name.
fn gap_name(name: &TagName) -> Option<(&u8, &[u8])> {
    name.to_bytes().split_first().filter(|(kind, number)| {
        (**kind == file_tag::GAPSTART || **kind == file_tag::GAPEND)
            && !number.is_empty()
            && number.iter().all(u8::is_ascii_digit)
    })
}

fn without_leading_zeros(number: &[u8]) -> &[u8] {
    let zeros = number.iter().take_while(|digit| **digit == b'0').count().min(number.len().saturating_sub(1));
    number.get(zeros..).unwrap_or(number)
}

fn gap_tag(kind: u8, index: usize) -> TagName {
    let mut name = vec![kind];
    name.extend_from_slice(index.to_string().as_bytes());
    TagName::of_bytes(&name)
}
