// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A file a search found, as merged from the answers that named it.

use crate::files::hash::ed2k;

/// A rating from which a result counts as spam.
pub const SPAM_THRESHOLD: u32 = 60;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchResult {
    pub hash: [u8; 16],
    /// The name most sources give.
    pub name: String,
    pub size: u64,
    pub sources: u32,
    /// None when the answer did not tell it.
    pub complete_sources: Option<u32>,
    /// The eD2K type, e.g. `Video`.
    pub file_type: Option<String>,
    pub spam_rating: u32,
    pub aich_hash: Option<[u8; 20]>,
    /// The names the file was found under, when more than one.
    pub names: Vec<Name>,
}

/// A name of a result with the sources that give it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Name {
    pub name: String,
    pub sources: u32,
    pub complete_sources: Option<u32>,
}

impl SearchResult {
    pub fn is_spam(&self) -> bool {
        self.spam_rating >= SPAM_THRESHOLD
    }
}

/// Whether a file of this name and size can be downloaded: a name, and a size eD2K allows.
pub fn is_downloadable(name: &str, size: u64) -> bool {
    !name.trim().is_empty() && size > 0 && size <= ed2k::MAX_FILE_SIZE
}
