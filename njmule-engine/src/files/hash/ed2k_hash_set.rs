// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A file hash with its part hashes.

use crate::files::hash::HashError;
use crate::files::hash::ed2k;

/// A file hash with its part hashes; `part_hashes` is empty for files smaller than one part, whose file hash is the
/// MD4 of the data itself.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ed2kHashSet {
    size: u64,
    file_hash: [u8; 16],
    part_hashes: Vec<[u8; 16]>,
}

impl Ed2kHashSet {
    pub fn new(size: u64, file_hash: [u8; 16], part_hashes: Vec<[u8; 16]>) -> Result<Self, HashError> {
        ed2k::check_size(size)?;
        let expected = ed2k::hash_set_size(size);
        if part_hashes.len() != expected {
            return Err(HashError::new(format!(
                "a file of {size} bytes has {expected} part hashes, got {}",
                part_hashes.len()
            )));
        }
        Ok(Self { size, file_hash, part_hashes })
    }

    pub fn size(&self) -> u64 {
        self.size
    }

    pub fn file_hash(&self) -> [u8; 16] {
        self.file_hash
    }

    pub fn part_hashes(&self) -> &[[u8; 16]] {
        &self.part_hashes
    }

    /// True when the part hashes produce the file hash.
    pub fn is_consistent(&self) -> bool {
        self.part_hashes.is_empty() || ed2k::file_hash(&self.part_hashes) == Ok(self.file_hash)
    }

    /// The MD4 a downloaded data part must match, for a part of the file.
    pub fn part_hash(&self, part: usize) -> Option<[u8; 16]> {
        if part >= ed2k::part_count(self.size) {
            return None;
        }
        if self.part_hashes.is_empty() { Some(self.file_hash) } else { self.part_hashes.get(part).copied() }
    }
}
