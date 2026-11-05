// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! AICH recovery hash sets, `known2_64.met`: `<0x02>` then, appended per file, `<master hash 20><u32 count><leaf hash
//! 20 × count>`. The older `known2.met` has no header and u16 counts.

use crate::persist::format::met_codecs;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter};

pub const VERSION: u8 = 0x02;
/// The leaves of 2 GiB of hashes.
const MAX_LEAVES: usize = 107_374_182;

/// The leaves of one file's AICH tree, as `AichTree::from_block_hashes` rebuilds it; 20 bytes each, packed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub master_hash: [u8; 20],
    pub block_hashes: Vec<[u8; 20]>,
}

pub fn read(data: &[u8]) -> Result<Vec<Entry>, DecodeError> {
    let mut input = PacketReader::of(data);
    met_codecs::header(&mut input, "known2_64.met", &[VERSION])?;
    let mut entries = Vec::new();
    read_entries(&mut input, true, &mut entries)?;
    Ok(entries)
}

/// The entries of a damaged `known2_64.met` that come before the damage; none without its header.
pub fn salvage(data: &[u8]) -> Vec<Entry> {
    let mut entries = Vec::new();
    let mut input = PacketReader::of(data);
    // What follows the damage is lost.
    let _ = met_codecs::header(&mut input, "known2_64.met", &[VERSION])
        .and_then(|_| read_entries(&mut input, true, &mut entries));
    entries
}

/// Reads the pre-64-bit `known2.met`.
pub fn read_old(data: &[u8]) -> Result<Vec<Entry>, DecodeError> {
    let mut entries = Vec::new();
    read_entries(&mut PacketReader::of(data), false, &mut entries)?;
    Ok(entries)
}

pub fn write(entries: &[Entry]) -> Result<Vec<u8>, EncodeError> {
    let mut out = PacketWriter::new();
    out.u8(VERSION);
    for entry in entries {
        out.hash20(&entry.master_hash).count32(entry.block_hashes.len())?;
        for hash in &entry.block_hashes {
            out.hash20(hash);
        }
    }
    Ok(out.into_byte_array())
}

fn read_entries(input: &mut PacketReader<'_>, wide_counts: bool, entries: &mut Vec<Entry>) -> Result<(), DecodeError> {
    while input.has_remaining() {
        let master_hash = input.hash20()?;
        let declared = if wide_counts { u64::from(input.u32()?) } else { u64::from(input.u16()?) };
        let count = input.count(declared, 20, MAX_LEAVES)?;
        let block_hashes = (0..count).map(|_| input.hash20()).collect::<Result<_, _>>()?;
        entries.push(Entry { master_hash, block_hashes });
    }
    Ok(())
}
