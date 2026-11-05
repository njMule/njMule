// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The AICH SHA-1 hash tree. Leaves are the 180 KiB blocks of each part; the tree is binary but splits at whole
//! PARTSIZE boundaries above the part level and gives the odd element to the left subtree of a left branch and to the
//! right subtree of a right branch.

use sha1::{Digest, Sha1};

use crate::files::hash::HashError;
use crate::files::hash::ed2k::{self, EMBLOCKSIZE, PARTSIZE};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AichTree {
    size: u64,
    block_hashes: Vec<[u8; 20]>,
    master_hash: [u8; 20],
    part_hashes: Vec<[u8; 20]>,
}

impl AichTree {
    /// Rebuilds the tree from its leaves in file order, as stored in known2_64.met.
    pub fn from_block_hashes(size: u64, block_hashes: Vec<[u8; 20]>) -> Result<Self, HashError> {
        if size == 0 {
            return Err(HashError::new("AICH needs a non-empty file"));
        }
        ed2k::check_size(size)?;
        let expected = block_count(size);
        if u64::try_from(block_hashes.len()).ok() != Some(expected) {
            return Err(HashError::new(format!(
                "a file of {size} bytes has {expected} AICH blocks, got {}",
                block_hashes.len()
            )));
        }
        let mut parts = Vec::new();
        let mut builder = Builder { leaves: &block_hashes, parts: (size > PARTSIZE).then_some(&mut parts), next: 0 };
        let master_hash =
            builder.node(size, true, base_size(size)).ok_or_else(|| HashError::new("too few AICH blocks"))?;
        Ok(Self { size, block_hashes, master_hash, part_hashes: parts })
    }

    pub fn size(&self) -> u64 {
        self.size
    }

    pub fn master_hash(&self) -> [u8; 20] {
        self.master_hash
    }

    /// One hash per data part for files larger than one part, otherwise empty.
    pub fn part_hashes(&self) -> &[[u8; 20]] {
        &self.part_hashes
    }

    pub fn block_hashes(&self) -> &[[u8; 20]] {
        &self.block_hashes
    }
}

/// Leaves: 180 KiB blocks restarting at every part boundary.
pub fn block_count(size: u64) -> u64 {
    let per_part = PARTSIZE.div_ceil(EMBLOCKSIZE);
    (size / PARTSIZE).saturating_mul(per_part).saturating_add((size % PARTSIZE).div_ceil(EMBLOCKSIZE))
}

/// The unit a subtree of `data_size` bytes splits by: blocks within a part, parts above.
pub fn base_size(data_size: u64) -> u64 {
    if data_size <= PARTSIZE { EMBLOCKSIZE } else { PARTSIZE }
}

/// The bytes of the left subtree of a node of `data_size` bytes.
pub fn left_size(data_size: u64, left: bool, base: u64) -> u64 {
    let blocks = data_size.div_ceil(base);
    (blocks.saturating_add(u64::from(left)) / 2).saturating_mul(base)
}

/// The hash of a node: SHA-1 of its children's hashes.
pub fn combine(left: &[u8; 20], right: &[u8; 20]) -> [u8; 20] {
    let mut sha1 = Sha1::new();
    sha1.update(left);
    sha1.update(right);
    sha1.finalize().into()
}

struct Builder<'a> {
    leaves: &'a [[u8; 20]],
    parts: Option<&'a mut Vec<[u8; 20]>>,
    next: usize,
}

impl Builder<'_> {
    // Depth ≤ 22 for the largest files. A part node is the first node below a PARTSIZE-based one.
    fn node(&mut self, data_size: u64, left: bool, base: u64) -> Option<[u8; 20]> {
        if data_size <= base {
            let leaf = self.leaves.get(self.next).copied()?;
            self.next = self.next.saturating_add(1);
            return Some(leaf);
        }
        let left_bytes = left_size(data_size, left, base);
        let l = self.child(left_bytes, true, base)?;
        let r = self.child(data_size.saturating_sub(left_bytes), false, base)?;
        Some(combine(&l, &r))
    }

    fn child(&mut self, data_size: u64, left: bool, parent_base: u64) -> Option<[u8; 20]> {
        let base = base_size(data_size);
        let hash = self.node(data_size, left, base)?;
        if parent_base == PARTSIZE
            && base == EMBLOCKSIZE
            && let Some(parts) = self.parts.as_deref_mut()
        {
            parts.push(hash);
        }
        Some(hash)
    }
}
