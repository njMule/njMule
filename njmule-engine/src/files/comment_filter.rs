// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The comment filter: what others say of a file is left out, as spam, when it holds one of its words.

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CommentFilter {
    words: Vec<String>,
}

impl CommentFilter {
    /// The filter of the settings, whose words are apart by `|`.
    pub fn of(settings: &str) -> Self {
        let words =
            settings.split('|').map(|word| word.trim().to_lowercase()).filter(|word| !word.is_empty()).collect();
        Self { words }
    }

    /// Whether `text` holds a word of the filter, in any case.
    pub fn leaves_out(&self, text: &str) -> bool {
        let lower = text.to_lowercase();
        self.words.iter().any(|word| lower.contains(word.as_str()))
    }
}
