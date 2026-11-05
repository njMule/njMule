// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Kad index a node keeps for others: `load_index.dat` (publish load per key), `src_index.dat` (sources per file)
//! and `key_index.dat` (files per keyword, with publish tracking). Each file starts with a version and a time; entries
//! carry their own lifetime.

use crate::kad::uint128::UInt128;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter, StringEncoding};
use crate::protocol::tag::{Tag, tag_codec};

const MAX_ITEMS: usize = 10_000_000;
/// The AICH index of a publisher that gave no AICH hash.
pub const NO_AICH: u16 = 0xFFFF;

/// `<version 1><save time><count>{<key><time>}`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LoadIndex {
    pub save_time: u32,
    pub loads: Vec<Load>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Load {
    pub key: UInt128,
    pub time: u32,
}

/// An indexed entry: when it expires and its Kad tags (name, size, IPs, ports, …).
#[derive(Debug, Clone, PartialEq)]
pub struct Entry {
    pub lifetime: u32,
    pub tags: Vec<Tag>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Source {
    pub id: UInt128,
    pub entries: Vec<Entry>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct IndexKey {
    pub key: UInt128,
    pub sources: Vec<Source>,
}

/// `src_index.dat`: version 2, valid until `expiry` (epoch seconds).
#[derive(Debug, Clone, PartialEq)]
pub struct SourceIndex {
    pub version: u32,
    pub expiry: u32,
    pub keys: Vec<IndexKey>,
}

/// A file name published for a keyword with its popularity.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PublishedName {
    pub name: String,
    pub popularity: u32,
}

/// A publisher; `aich_index` points into [`Tracking::aich_hashes`] or is [`NO_AICH`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Publisher {
    pub ip: u32,
    pub time: u32,
    pub aich_index: u16,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Tracking {
    pub aich_hashes: Vec<[u8; 20]>,
    pub names: Vec<PublishedName>,
    pub publishers: Vec<Publisher>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct KeywordEntry {
    pub lifetime: u32,
    pub tracking: Tracking,
    pub tags: Vec<Tag>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct KeywordSource {
    pub id: UInt128,
    pub entries: Vec<KeywordEntry>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Keyword {
    pub key: UInt128,
    pub sources: Vec<KeywordSource>,
}

/// `key_index.dat`: version 4, valid until `expiry`, for the node `own_id`.
#[derive(Debug, Clone, PartialEq)]
pub struct KeywordIndex {
    pub version: u32,
    pub expiry: u32,
    pub own_id: UInt128,
    pub keywords: Vec<Keyword>,
}

pub fn read_load(data: &[u8]) -> Result<LoadIndex, DecodeError> {
    let mut input = PacketReader::of(data);
    let version = input.u32()?;
    if version >= 2 {
        return Err(DecodeError::new(format!("unsupported load_index.dat version {version}")));
    }
    let save_time = input.u32()?;
    let loads = list(&mut input, 20, |input| Ok(Load { key: UInt128::read(input)?, time: input.u32()? }))?;
    Ok(LoadIndex { save_time, loads })
}

pub fn write_load(index: &LoadIndex) -> Result<Vec<u8>, EncodeError> {
    let mut out = PacketWriter::new();
    out.u32(1).u32(index.save_time).count32(index.loads.len())?;
    for load in &index.loads {
        load.key.write(&mut out);
        out.u32(load.time);
    }
    Ok(out.into_byte_array())
}

pub fn read_sources(data: &[u8]) -> Result<SourceIndex, DecodeError> {
    let mut input = PacketReader::of(data);
    let version = input.u32()?;
    if version >= 3 {
        return Err(DecodeError::new(format!("unsupported src_index.dat version {version}")));
    }
    let expiry = input.u32()?;
    let keys = list(&mut input, 20, |input| {
        let key = UInt128::read(input)?;
        let sources = list(input, 20, |input| {
            let id = UInt128::read(input)?;
            let entries =
                list(input, 5, |input| Ok(Entry { lifetime: input.u32()?, tags: tag_codec::read_kad_list(input)? }))?;
            Ok(Source { id, entries })
        })?;
        Ok(IndexKey { key, sources })
    })?;
    Ok(SourceIndex { version, expiry, keys })
}

pub fn write_sources(index: &SourceIndex) -> Result<Vec<u8>, EncodeError> {
    let mut out = PacketWriter::new();
    out.u32(2).u32(index.expiry).count32(index.keys.len())?;
    for key in &index.keys {
        key.key.write(&mut out);
        out.count32(key.sources.len())?;
        for source in &key.sources {
            source.id.write(&mut out);
            out.count32(source.entries.len())?;
            for entry in &source.entries {
                out.u32(entry.lifetime);
                tag_codec::write_kad_list(&mut out, &entry.tags)?;
            }
        }
    }
    Ok(out.into_byte_array())
}

pub fn read_keywords(data: &[u8]) -> Result<KeywordIndex, DecodeError> {
    let mut input = PacketReader::of(data);
    let version = input.u32()?;
    if version >= 5 {
        return Err(DecodeError::new(format!("unsupported key_index.dat version {version}")));
    }
    let expiry = input.u32()?;
    let own_id = UInt128::read(&mut input)?;
    let keywords = list(&mut input, 20, |input| {
        let key = UInt128::read(input)?;
        let sources = list(input, 20, |input| {
            let id = UInt128::read(input)?;
            let entries = list(input, 5, |input| {
                let lifetime = input.u32()?;
                let tracking = if version >= 3 { tracking(input, version >= 4)? } else { Tracking::default() };
                Ok(KeywordEntry { lifetime, tracking, tags: tag_codec::read_kad_list(input)? })
            })?;
            Ok(KeywordSource { id, entries })
        })?;
        Ok(Keyword { key, sources })
    })?;
    Ok(KeywordIndex { version, expiry, own_id, keywords })
}

pub fn write_keywords(index: &KeywordIndex) -> Result<Vec<u8>, EncodeError> {
    let mut out = PacketWriter::new();
    out.u32(4).u32(index.expiry);
    index.own_id.write(&mut out);
    out.count32(index.keywords.len())?;
    for keyword in &index.keywords {
        keyword.key.write(&mut out);
        out.count32(keyword.sources.len())?;
        for source in &keyword.sources {
            source.id.write(&mut out);
            out.count32(source.entries.len())?;
            for entry in &source.entries {
                out.u32(entry.lifetime);
                let tracking = &entry.tracking;
                out.count16(tracking.aich_hashes.len())?;
                for hash in &tracking.aich_hashes {
                    out.hash20(hash);
                }
                out.count32(tracking.names.len())?;
                for name in &tracking.names {
                    out.string16(&name.name, StringEncoding::Utf8)?.u32(name.popularity);
                }
                out.count32(tracking.publishers.len())?;
                for publisher in &tracking.publishers {
                    out.u32(publisher.ip).u32(publisher.time).u16(publisher.aich_index);
                }
                tag_codec::write_kad_list(&mut out, &entry.tags)?;
            }
        }
    }
    Ok(out.into_byte_array())
}

// `<u32 count>` items of at least `min_item_size` bytes each.
fn list<'a, T>(
    input: &mut PacketReader<'a>,
    min_item_size: usize,
    mut item: impl FnMut(&mut PacketReader<'a>) -> Result<T, DecodeError>,
) -> Result<Vec<T>, DecodeError> {
    let declared = input.u32()?;
    let count = input.count(u64::from(declared), min_item_size, MAX_ITEMS)?;
    (0..count).map(|_| item(input)).collect()
}

fn tracking(input: &mut PacketReader<'_>, with_aich: bool) -> Result<Tracking, DecodeError> {
    let mut aich_hashes = Vec::new();
    if with_aich {
        let declared = input.u16()?;
        let count = input.count(u64::from(declared), 20, 0xFFFF)?;
        aich_hashes = (0..count).map(|_| input.hash20()).collect::<Result<_, _>>()?;
    }
    let names = list(input, 6, |input| Ok(PublishedName { name: input.string16()?, popularity: input.u32()? }))?;
    let publishers = list(input, if with_aich { 10 } else { 8 }, |input| {
        let ip = input.u32()?;
        let time = input.u32()?;
        let aich = if with_aich { input.u16()? } else { NO_AICH };
        let known = usize::from(aich) < aich_hashes.len();
        Ok(Publisher { ip, time, aich_index: if known { aich } else { NO_AICH } })
    })?;
    Ok(Tracking { aich_hashes, names, publishers })
}
