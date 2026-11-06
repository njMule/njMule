// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The search results marked as spam: by file hash and by full name in lower case, less the hashes cleared again.

use std::collections::HashSet;

use crate::api::search_result::SearchResult;

/// Names this long or shorter (such as `setup.exe`) are too common to mark other files as spam alone.
pub const MAX_COMMON_NAME: usize = 10;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SpamMarks {
    pub spam: HashSet<[u8; 16]>,
    pub not_spam: HashSet<[u8; 16]>,
    pub names: HashSet<String>,
}

impl SpamMarks {
    /// Whether `result` counts as spam.
    pub fn is_spam(&self, result: &SearchResult) -> bool {
        if self.not_spam.contains(&result.hash) {
            return false;
        }
        self.spam.contains(&result.hash)
            || (result.name.encode_utf16().count() > MAX_COMMON_NAME
                && self.names.contains(&result.name.to_lowercase()))
    }
}
