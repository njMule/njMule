// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A comment on a file and its rating.

use crate::util::white_space;

/// A comment on a file and its rating, from 0 (none) to [`FileComment::MAX_RATING`] (excellent); the text is one line
/// of at most [`FileComment::MAX_LENGTH`] UTF-16 units, as the Java engine counts them.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct FileComment {
    text: String,
    rating: u8,
}

impl FileComment {
    pub const MAX_RATING: u8 = 5;
    pub const MAX_LENGTH: usize = 128;
    pub const NONE: Self = Self { text: String::new(), rating: 0 };

    /// The comment, if it is one line of the allowed length and its rating is in range.
    pub fn new(text: &str, rating: u8) -> Option<Self> {
        let fits = rating <= Self::MAX_RATING
            && text.encode_utf16().count() <= Self::MAX_LENGTH
            && !text.chars().any(char::is_control);
        fits.then(|| Self { text: text.to_owned(), rating })
    }

    /// A comment made fit: control characters become spaces, the text is cut to its longest length.
    pub fn of(text: &str, rating: u8) -> Self {
        let line: String = white_space::strip(text).chars().map(|c| if c.is_control() { ' ' } else { c }).collect();
        let mut units = 0usize;
        let fit: String = line
            .chars()
            .take_while(|c| {
                units = units.saturating_add(c.len_utf16());
                units <= Self::MAX_LENGTH
            })
            .collect();
        Self { text: white_space::strip(&fit).to_owned(), rating: rating.min(Self::MAX_RATING) }
    }

    pub fn text(&self) -> &str {
        &self.text
    }

    pub fn rating(&self) -> u8 {
        self.rating
    }

    pub fn is_empty(&self) -> bool {
        self.text.is_empty() && self.rating == 0
    }
}
