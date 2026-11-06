// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A group of downloads: listed apart, finished into its own incoming folder when it has one, with the priority given
//! to the downloads put in it and the file names that put new downloads in it.

use std::path::PathBuf;

use fancy_regex::{Regex, RegexBuilder};

use crate::api::download_info::DownloadPriority;
use crate::util::white_space::strip;

/// No category.
pub const NONE: u32 = 0;
pub const COLORS: u32 = 8;
// Lengths are in UTF-16 units, as the Java engine counts them.
pub const MAX_NAME: usize = 64;
pub const MAX_COMMENT: usize = 256;
pub const MAX_PATTERNS: usize = 1024;

/// `id` is 1 and up (downloads with no category have [`NONE`]); `incoming` none for the incoming folder of the
/// preferences; `color` one of the [`COLORS`] category colours of the interface, 0 for none; `priority` is given to the
/// downloads put in it, [`DownloadPriority::Auto`] leaving theirs. `patterns` are the names that send new downloads
/// here, apart by `|`: with `*` and `?` a pattern matches the whole name, without them a part of it; when `regex`, one
/// regular expression (where `|` is alternation) that must match the whole name, ignoring case.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Category {
    pub id: u32,
    pub name: String,
    pub incoming: Option<PathBuf>,
    pub comment: String,
    pub color: u32,
    pub priority: DownloadPriority,
    pub patterns: String,
    pub regex: bool,
}

impl Category {
    /// This category with its texts stripped, or why it cannot be.
    pub fn validated(mut self) -> Result<Self, String> {
        if self.id < 1 {
            return Err(format!("a category id is 1 or more: {}", self.id));
        }
        self.name = strip(&self.name).to_owned();
        if self.name.is_empty() || too_long(&self.name, MAX_NAME) || self.name.chars().any(char::is_control) {
            return Err(format!("a category name has 1-{MAX_NAME} printable characters"));
        }
        if let Some(incoming) = self.incoming.as_ref().filter(|incoming| !incoming.is_absolute()) {
            return Err(format!("the incoming folder of a category must be absolute: {}", incoming.display()));
        }
        self.comment = strip(&self.comment).to_owned();
        if too_long(&self.comment, MAX_COMMENT) {
            return Err(format!("a category comment has at most {MAX_COMMENT} characters"));
        }
        if self.color > COLORS {
            return Err(format!("a category colour is 0-{COLORS}: {}", self.color));
        }
        self.patterns = strip(&self.patterns).to_owned();
        if too_long(&self.patterns, MAX_PATTERNS) {
            return Err(format!("category patterns have at most {MAX_PATTERNS} characters"));
        }
        if self.regex {
            compiled(&self.patterns)?;
        }
        Ok(self)
    }

    /// Whether a new download named `file_name` goes in this category.
    pub fn matches(&self, file_name: &str) -> bool {
        if self.regex {
            return !self.patterns.is_empty() && whole(&self.patterns, file_name);
        }
        let name = file_name.to_lowercase();
        self.patterns.split('|').map(strip).filter(|pattern| !pattern.is_empty()).any(|pattern| {
            if pattern.contains(['*', '?']) {
                whole(&glob(pattern), file_name)
            } else {
                name.contains(&pattern.to_lowercase())
            }
        })
    }
}

// The regular expressions are read as the Java engine reads them, look-arounds and back references included, which
// the regex crate has not.
fn compiled(patterns: &str) -> Result<Regex, String> {
    RegexBuilder::new(patterns)
        .case_insensitive(true)
        .build()
        .map_err(|e| format!("not a regular expression: {e}"))
}

fn whole(patterns: &str, file_name: &str) -> bool {
    compiled(&format!("^(?:{patterns})$")).is_ok_and(|regex| regex.is_match(file_name).unwrap_or(false))
}

// `*` any characters, `?` one, the rest as it is.
fn glob(pattern: &str) -> String {
    let mut regex = "(?s)".to_owned();
    for c in pattern.chars() {
        match c {
            '*' => regex.push_str(".*"),
            '?' => regex.push('.'),
            _ => regex.push_str(&fancy_regex::escape(&c.to_string())),
        }
    }
    regex
}

fn too_long(text: &str, max: usize) -> bool {
    text.encode_utf16().count() > max
}
