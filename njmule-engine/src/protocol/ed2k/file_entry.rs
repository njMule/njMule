// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A file as servers describe it in search results: hash, the client offering it and its tags. Layout: `<hash
//! 16><client id u32><port u16><tag count u32><tags>`.

use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter, StringEncoding};
use crate::protocol::tag::tag_codec::{read_ed2k_list, write_ed2k_list};
use crate::protocol::tag::{Ed2kTagFormat, Tag, file_tag, tags};

/// Smallest encoded entry: no tags.
pub const MIN_SIZE: usize = 16 + 4 + 2 + 4;
/// Tags accepted per entry.
pub const MAX_TAGS: usize = 512;

#[derive(Debug, Clone, PartialEq)]
pub struct FileEntry {
    pub hash: [u8; 16],
    pub client_id: u32,
    pub port: u16,
    pub tags: Vec<Tag>,
}

impl FileEntry {
    /// The media details its tags carry.
    pub fn media(&self) -> crate::api::search_result::Media {
        crate::protocol::tag::media_tags::read(&self.tags)
    }

    pub fn read(input: &mut PacketReader<'_>) -> Result<Self, DecodeError> {
        let hash = input.hash16()?;
        let client_id = input.u32()?;
        let port = input.u16()?;
        let count = input.u32()?;
        let tags = read_ed2k_list(input, u64::from(count), MAX_TAGS)?;
        Ok(Self { hash, client_id, port, tags })
    }

    /// Writes the entry with `format` tags (servers use the new one); strings are UTF-8.
    pub fn write(&self, out: &mut PacketWriter, format: Ed2kTagFormat) -> Result<(), EncodeError> {
        out.hash16(&self.hash).u32(self.client_id).u16(self.port).count32(self.tags.len())?;
        write_ed2k_list(out, &self.tags, format, StringEncoding::Utf8)
    }

    pub fn name(&self) -> Option<&str> {
        tags::string_value(&self.tags, file_tag::FILENAME)
    }

    /// The size: a 64-bit size tag, or the 32-bit one combined with its high half.
    pub fn size(&self) -> Option<u64> {
        let size = tags::int_value(&self.tags, file_tag::FILESIZE)?;
        if size > u64::from(u32::MAX) {
            return Some(size);
        }
        let high = tags::int_value(&self.tags, file_tag::FILESIZE_HI).unwrap_or(0);
        Some(high.wrapping_shl(32) | size)
    }

    pub fn sources(&self) -> u32 {
        self.count(file_tag::SOURCES)
    }

    pub fn complete_sources(&self) -> u32 {
        self.count(file_tag::COMPLETE_SOURCES)
    }

    /// The root of the file's AICH tree, when the entry names a well-formed one.
    pub fn aich_hash(&self) -> Option<[u8; 20]> {
        let text = tags::string_value(&self.tags, file_tag::AICH_HASH)?;
        if text.len() != 32 {
            return None;
        }
        data_encoding::BASE32_NOPAD.decode(text.as_bytes()).ok().and_then(|bytes| bytes.try_into().ok())
    }

    /// The type servers report, e.g. `Video` or `Pro`.
    pub fn file_type(&self) -> Option<&str> {
        tags::string_value(&self.tags, file_tag::FILETYPE)
    }

    // Counts are unsigned on the wire, and capped where the interface's 32-bit signed counts end.
    fn count(&self, id: u8) -> u32 {
        const MAX_COUNT: u32 = i32::MAX.unsigned_abs();
        let value = tags::int_value(&self.tags, id).unwrap_or(0);
        u32::try_from(value).map_or(MAX_COUNT, |n| n.min(MAX_COUNT))
    }
}
