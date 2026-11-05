// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Search files: `StoredSearches.met` (open search tabs with their results, restored at start) and `SearchSpam.met`
//! (learned spam markers). Both start with `0x0F`; results use the same record as server search results, `<hash
//! 16><client id u32><port u16><u32 tag count><new-encoding tags>`.

use crate::persist::format::met_codecs;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter, StringEncoding};
use crate::protocol::tag::{Ed2kTagFormat, Tag, tag_codec};

const HEADER: u8 = 0x0F;
const STORED_VERSION: u8 = 1;
const MAX_RESULTS: usize = 1_000_000;
const MAX_RESULT_TAGS: usize = 1024;

/// Spam marker tag ids.
pub const SPAM_FULL_NAME: u8 = 0x01;
pub const SPAM_HASH: u8 = 0x02;
pub const NOT_SPAM_HASH: u8 = 0x03;
pub const SPAM_SOURCE_IP: u8 = 0x04;
pub const SPAM_SERVER_IP: u8 = 0x05;
pub const SPAM_SIMILAR_NAME: u8 = 0x06;
pub const SPAM_SIZE: u8 = 0x07;
pub const SPAM_UDP_SERVER_RATIO: u8 = 0x08;

/// A search result: the file and the source that offered it (a server or a client).
#[derive(Debug, Clone, PartialEq)]
pub struct SearchResult {
    pub file_hash: [u8; 16],
    pub client_id: u32,
    pub port: u16,
    pub tags: Vec<Tag>,
}

impl SearchResult {
    pub fn read(input: &mut PacketReader<'_>) -> Result<Self, DecodeError> {
        let file_hash = input.hash16()?;
        let client_id = input.u32()?;
        let port = input.u16()?;
        let declared = input.u32()?;
        let tags = tag_codec::read_ed2k_list(input, u64::from(declared), MAX_RESULT_TAGS)?;
        Ok(Self { file_hash, client_id, port, tags })
    }

    pub fn write(&self, out: &mut PacketWriter) -> Result<(), EncodeError> {
        out.hash16(&self.file_hash).u32(self.client_id).u16(self.port).count32(self.tags.len())?;
        tag_codec::write_ed2k_list(out, &self.tags, Ed2kTagFormat::New, StringEncoding::Utf8)
    }
}

/// One stored search tab. `search_type` is the search method (automatic, server, global, Kad).
#[derive(Debug, Clone, PartialEq)]
pub struct StoredSearch {
    pub id: u32,
    pub search_type: u8,
    pub client_shared_files: bool,
    pub title: String,
    pub expression: String,
    pub file_type: String,
    pub results: Vec<SearchResult>,
}

pub fn read_stored(data: &[u8]) -> Result<Vec<StoredSearch>, DecodeError> {
    let mut input = PacketReader::of(data);
    met_codecs::header(&mut input, "StoredSearches.met", &[HEADER])?;
    let version = input.u8()?;
    if version != STORED_VERSION {
        return Err(DecodeError::new(format!("unsupported StoredSearches.met version {version}")));
    }
    let declared = input.u16()?;
    let count = input.count(u64::from(declared), 12, 0xFFFF)?;
    (0..count)
        .map(|_| {
            let id = input.u32()?;
            let search_type = input.u8()?;
            let client_shared_files = input.u8()? != 0;
            let title = input.string16()?;
            let expression = input.string16()?;
            let file_type = input.string16()?;
            let declared = input.u32()?;
            let n = input.count(u64::from(declared), 26, MAX_RESULTS)?;
            let results = (0..n).map(|_| SearchResult::read(&mut input)).collect::<Result<_, _>>()?;
            Ok(StoredSearch { id, search_type, client_shared_files, title, expression, file_type, results })
        })
        .collect()
}

pub fn write_stored(searches: &[StoredSearch]) -> Result<Vec<u8>, EncodeError> {
    let mut out = PacketWriter::new();
    out.u8(HEADER).u8(STORED_VERSION).count16(searches.len())?;
    for search in searches {
        out.u32(search.id).u8(search.search_type).u8(u8::from(search.client_shared_files));
        out.string16(&search.title, StringEncoding::Utf8)?
            .string16(&search.expression, StringEncoding::Utf8)?
            .string16(&search.file_type, StringEncoding::Utf8)?
            .count32(search.results.len())?;
        for result in &search.results {
            result.write(&mut out)?;
        }
    }
    Ok(out.into_byte_array())
}

/// The spam markers, one tag each.
pub fn read_spam(data: &[u8]) -> Result<Vec<Tag>, DecodeError> {
    let mut input = PacketReader::of(data);
    met_codecs::header(&mut input, "SearchSpam.met", &[HEADER])?;
    let declared = input.u32()?;
    tag_codec::read_ed2k_list(&mut input, u64::from(declared), MAX_RESULTS)
}

pub fn write_spam(markers: &[Tag]) -> Result<Vec<u8>, EncodeError> {
    let mut out = PacketWriter::new();
    out.u8(HEADER).count32(markers.len())?;
    tag_codec::write_ed2k_list(&mut out, markers, Ed2kTagFormat::New, StringEncoding::Utf8BomIfNeeded)?;
    Ok(out.into_byte_array())
}
