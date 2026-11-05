// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! AICH recovery data of one part: the hashes that tie the part's 180 KiB blocks to the master hash, as clients
//! exchange them. Each hash has an identifier, the path to its node: a leading 1 for the root, then one bit per level,
//! 1 for a left branch. The data holds the sibling of each node on the way down to the part, then the part's block
//! hashes from left to right; files above [`LARGE_FILE`] bytes use 32-bit identifiers, smaller ones 16-bit.

use std::collections::HashMap;

use crate::files::hash::HashError;
use crate::files::hash::aich_tree::{AichTree, base_size, combine, left_size};
use crate::files::hash::ed2k::{EMBLOCKSIZE, PARTSIZE};
use crate::protocol::io::{DecodeError, PacketReader, PacketWriter};

/// Above this size identifiers take 32 bits.
pub const LARGE_FILE: u64 = 4_290_048_000;

// A path longer than this does not exist in a file of at most 2^38 bytes.
const MAX_LEVELS: u32 = 22;
const MAX_IDENT: u64 = 0x40_0000;

/// Whether part `part` of a file of `size` bytes has recovery data: a part of more than one block, also the only part
/// of a small file.
pub fn has_recovery(size: u64, part: u32) -> bool {
    u64::from(part).saturating_mul(PARTSIZE).saturating_add(EMBLOCKSIZE) < size
}

/// The recovery data of part `part` of `tree`.
pub fn create(tree: &AichTree, part: u32) -> Result<Vec<u8>, HashError> {
    let size = tree.size();
    if !has_recovery(size, part) {
        return Err(HashError::new(format!("part {part} of {size} bytes has no recovery data")));
    }
    let span = Span::of(size, part);
    let mut nodes = Vec::new();
    Walker { leaves: tree.block_hashes() }
        .down(size, true, base_size(size), 0, 0, span, &mut nodes)
        .ok_or_else(|| HashError::new("the tree lacks a block of the part"))?;
    let large = size > LARGE_FILE;
    let too_many = |_| HashError::new("too many recovery hashes");
    let mut out = PacketWriter::new();
    if large {
        out.u16(0);
    }
    out.count16(nodes.len()).map_err(too_many)?;
    for (ident, hash) in &nodes {
        if large {
            out.u32(u32::try_from(*ident).map_err(|_| HashError::new("identifier beyond 32 bits"))?);
        } else {
            out.u16(u16::try_from(*ident).map_err(|_| HashError::new("identifier beyond 16 bits"))?);
        }
        out.hash20(hash);
    }
    if !large {
        out.u16(0);
    }
    Ok(out.into_byte_array())
}

/// The block hashes of part `part` of a file of `size` bytes, from left to right, when `data` proves them against
/// `master`; none when it does not.
pub fn verify(size: u64, part: u32, master: &[u8; 20], data: &[u8]) -> Option<Vec<[u8; 20]>> {
    if !has_recovery(size, part) {
        return None;
    }
    let by_ident = read(data).ok()?;
    let mut checker = Checker { by_ident, blocks: Vec::new() };
    let root = checker.up(size, true, base_size(size), 0, 0, Span::of(size, part), 0)?;
    (root == *master).then_some(checker.blocks)
}

// Both identifier widths; the master hash is never taken from a peer.
fn read(data: &[u8]) -> Result<HashMap<u64, [u8; 20]>, DecodeError> {
    let mut input = PacketReader::of(data);
    let mut hashes = HashMap::new();
    let short = input.u16()?;
    for _ in 0..short {
        let ident = u64::from(input.u16()?);
        put(&mut hashes, ident, input.hash20()?)?;
    }
    if input.remaining() >= 2 {
        let long = input.u16()?;
        for _ in 0..long {
            let ident = u64::from(input.u32()?);
            put(&mut hashes, ident, input.hash20()?)?;
        }
    }
    Ok(hashes)
}

fn put(hashes: &mut HashMap<u64, [u8; 20]>, ident: u64, hash: [u8; 20]) -> Result<(), DecodeError> {
    if ident <= 1 || ident > MAX_IDENT {
        return Err(DecodeError::new(format!("bad AICH identifier {ident}")));
    }
    hashes.entry(ident).or_insert(hash);
    Ok(())
}

// The leaf of the block starting at byte offset `start`: blocks restart at each part.
fn leaf(start: u64) -> Option<usize> {
    let per_part = PARTSIZE.div_ceil(EMBLOCKSIZE);
    usize::try_from((start / PARTSIZE).saturating_mul(per_part).saturating_add(start % PARTSIZE / EMBLOCKSIZE)).ok()
}

// A node's identifier below its parent's.
fn child_ident(ident: u64, left: bool) -> u64 {
    ident.wrapping_shl(1) | u64::from(left)
}

#[derive(Debug, Clone, Copy)]
struct Span {
    start: u64,
    size: u64,
}

impl Span {
    fn of(size: u64, part: u32) -> Self {
        let start = u64::from(part).saturating_mul(PARTSIZE);
        Self { start, size: PARTSIZE.min(size.saturating_sub(start)) }
    }
}

/// Writes the recovery data from the leaves of a complete tree.
struct Walker<'a> {
    leaves: &'a [[u8; 20]],
}

impl Walker<'_> {
    #[allow(clippy::too_many_arguments)]
    fn down(
        &self,
        data_size: u64,
        left: bool,
        base: u64,
        start: u64,
        ident: u64,
        part: Span,
        out: &mut Vec<(u64, [u8; 20])>,
    ) -> Option<()> {
        if start == part.start && data_size == part.size {
            return self.lowest(data_size, left, base, start, ident, out);
        }
        let id = child_ident(ident, left);
        let left_bytes = left_size(data_size, left, base);
        let right_bytes = data_size.saturating_sub(left_bytes);
        let middle = start.saturating_add(left_bytes);
        if part.start < middle {
            out.push((id.wrapping_shl(1), self.hash(right_bytes, false, middle)?));
            self.down(left_bytes, true, base_size(left_bytes), start, id, part, out)
        } else {
            out.push((id.wrapping_shl(1) | 1, self.hash(left_bytes, true, start)?));
            self.down(right_bytes, false, base_size(right_bytes), middle, id, part, out)
        }
    }

    fn lowest(
        &self,
        data_size: u64,
        left: bool,
        base: u64,
        start: u64,
        ident: u64,
        out: &mut Vec<(u64, [u8; 20])>,
    ) -> Option<()> {
        let id = child_ident(ident, left);
        if data_size <= base {
            out.push((id, *self.leaves.get(leaf(start)?)?));
            return Some(());
        }
        let left_bytes = left_size(data_size, left, base);
        let right_bytes = data_size.saturating_sub(left_bytes);
        self.lowest(left_bytes, true, base_size(left_bytes), start, id, out)?;
        self.lowest(right_bytes, false, base_size(right_bytes), start.saturating_add(left_bytes), id, out)
    }

    fn hash(&self, data_size: u64, left: bool, start: u64) -> Option<[u8; 20]> {
        let base = base_size(data_size);
        if data_size <= base {
            return self.leaves.get(leaf(start)?).copied();
        }
        let left_bytes = left_size(data_size, left, base);
        let l = self.hash(left_bytes, true, start)?;
        let r = self.hash(data_size.saturating_sub(left_bytes), false, start.saturating_add(left_bytes))?;
        Some(combine(&l, &r))
    }
}

/// Rebuilds the way up from the part to the root with the hashes received.
struct Checker {
    by_ident: HashMap<u64, [u8; 20]>,
    blocks: Vec<[u8; 20]>,
}

impl Checker {
    #[allow(clippy::too_many_arguments)]
    fn up(
        &mut self,
        data_size: u64,
        left: bool,
        base: u64,
        start: u64,
        ident: u64,
        part: Span,
        level: u32,
    ) -> Option<[u8; 20]> {
        if level > MAX_LEVELS {
            return None;
        }
        if start == part.start && data_size == part.size {
            return self.part(data_size, left, base, ident);
        }
        let id = child_ident(ident, left);
        let left_bytes = left_size(data_size, left, base);
        let right_bytes = data_size.saturating_sub(left_bytes);
        let middle = start.saturating_add(left_bytes);
        let deeper = level.saturating_add(1);
        if part.start < middle {
            let right = self.by_ident.get(&id.wrapping_shl(1)).copied();
            let below = self.up(left_bytes, true, base_size(left_bytes), start, id, part, deeper);
            return Some(combine(&below?, &right?));
        }
        let left_hash = self.by_ident.get(&(id.wrapping_shl(1) | 1)).copied();
        let below = self.up(right_bytes, false, base_size(right_bytes), middle, id, part, deeper);
        Some(combine(&left_hash?, &below?))
    }

    // The part's subtree from its blocks, which are kept in order for the caller.
    fn part(&mut self, data_size: u64, left: bool, base: u64, ident: u64) -> Option<[u8; 20]> {
        let id = child_ident(ident, left);
        if data_size <= base {
            let block = self.by_ident.get(&id).copied();
            if let Some(hash) = block {
                self.blocks.push(hash);
            }
            return block;
        }
        let left_bytes = left_size(data_size, left, base);
        let right_bytes = data_size.saturating_sub(left_bytes);
        let l = self.part(left_bytes, true, base_size(left_bytes), id);
        let r = self.part(right_bytes, false, base_size(right_bytes), id);
        Some(combine(&l?, &r?))
    }
}
