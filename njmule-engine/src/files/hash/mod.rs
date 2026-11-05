// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The hashes of files: eD2K's MD4 part hashes and the AICH SHA-1 tree, its recovery data and the votes on its master
//! hash.

pub mod aich_recovery;
pub mod aich_tree;
pub mod aich_votes;
pub mod ed2k;
pub mod ed2k_hash_set;
pub mod file_hasher;

use std::fmt;

/// Sizes, counts or hashes that do not fit together.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HashError(String);

impl HashError {
    pub fn new(reason: impl Into<String>) -> Self {
        Self(reason.into())
    }
}

impl fmt::Display for HashError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for HashError {}
