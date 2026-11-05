// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! File names from the network made safe on every system: no separators, controls, forbidden characters nor device
//! names, and a length every file system takes.

use std::fs::OpenOptions;
use std::io::{self, ErrorKind};
use std::path::{Component, Path, PathBuf};

use crate::util::white_space;

/// Longest name kept, in UTF-8 bytes (well below every file system's limit).
pub const MAX_NAME_BYTES: usize = 200;

const FORBIDDEN: &str = "\\/:*?\"<>|";
const MAX_EXTENSION: usize = 16;
const MAX_NUMBER: u32 = 10_000;
// Windows device names, with the superscript digits it also takes for digits.
const RESERVED: [&str; 32] = [
    "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
    "COM8", "COM9", "COM¹", "COM²", "COM³", "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8",
    "LPT9", "LPT¹", "LPT²", "LPT³",
];

/// A file name without separators, control or forbidden characters, nor a reserved name; never empty, at most
/// [`MAX_NAME_BYTES`] bytes, and its own sanitized form.
pub fn file_name(name: &str) -> String {
    let replaced: String =
        name.chars().map(|c| if c < ' ' || c == '\u{7F}' || FORBIDDEN.contains(c) { '_' } else { c }).collect();
    let mut cleaned = trim_end(&truncate(trim_end(white_space::strip(&replaced)))).to_owned();
    if cleaned.is_empty() {
        return "_".to_owned();
    }
    let base = cleaned.split('.').next().unwrap_or_default().to_uppercase();
    let base = white_space::strip(&base);
    if RESERVED.contains(&base) {
        cleaned = trim_end(&truncate(&format!("_{cleaned}"))).to_owned();
    }
    cleaned
}

/// `directory/name`, with the name sanitized; never outside `directory`.
pub fn resolve_inside(directory: &Path, name: &str) -> io::Result<PathBuf> {
    let safe = file_name(name);
    let mut components = Path::new(&safe).components();
    match (components.next(), components.next()) {
        (Some(Component::Normal(_)), None) => Ok(directory.join(safe)),
        _ => Err(io::Error::new(ErrorKind::InvalidInput, format!("name escapes its directory: {name}"))),
    }
}

/// As a free name in `directory` (the name, else "name (2).ext", ...), but the empty file is created at once and
/// atomically, so two callers never get the same name; the caller replaces it.
pub fn reserve(directory: &Path, name: &str) -> io::Result<PathBuf> {
    let first = resolve_inside(directory, name)?;
    if create_new(&first)? {
        return Ok(first);
    }
    let safe = file_name(name);
    for n in 2..MAX_NUMBER {
        let candidate = resolve_inside(directory, &numbered(&safe, n))?;
        if create_new(&candidate)? {
            return Ok(candidate);
        }
    }
    Err(io::Error::new(ErrorKind::AlreadyExists, format!("no free name for {name} in {}", directory.display())))
}

fn create_new(path: &Path) -> io::Result<bool> {
    match OpenOptions::new().write(true).create_new(true).open(path) {
        Ok(_) => Ok(true),
        Err(e) if e.kind() == ErrorKind::AlreadyExists => Ok(false),
        Err(e) => Err(e),
    }
}

// Windows drops trailing dots and spaces, which would make two names collide.
fn trim_end(name: &str) -> &str {
    name.trim_end_matches(|c: char| c == '.' || white_space::is_white_space(c))
}

// "stem (n).ext" from a sanitized name, with the stem cut first so that the number always fits.
fn numbered(safe: &str, n: u32) -> String {
    let dot = safe.rfind('.').filter(|&dot| dot > 0);
    let (mut stem, mut suffix) = match dot {
        Some(dot) => (safe.get(..dot).unwrap_or(safe), format!(" ({n}){}", safe.get(dot..).unwrap_or_default())),
        None => (safe, format!(" ({n})")),
    };
    if suffix.len() > MAX_NAME_BYTES / 2 {
        // An extension this long is no extension: the number goes at the end.
        stem = safe;
        suffix = format!(" ({n})");
    }
    format!("{}{suffix}", cut(stem, MAX_NAME_BYTES.saturating_sub(suffix.len())))
}

// Keeps a short extension, cutting the stem on character boundaries.
fn truncate(name: &str) -> String {
    if name.len() <= MAX_NAME_BYTES {
        return name.to_owned();
    }
    let extension = name
        .rfind('.')
        .filter(|&dot| dot > 0 && name.get(dot..).is_some_and(|ext| ext.chars().count() <= MAX_EXTENSION))
        .and_then(|dot| name.get(dot..))
        .unwrap_or_default();
    let stem = name.get(..name.len().saturating_sub(extension.len())).unwrap_or(name);
    format!("{}{extension}", cut(stem, MAX_NAME_BYTES.saturating_sub(extension.len())).trim_end())
}

// The longest start of `text` within `budget` UTF-8 bytes, never splitting a character.
fn cut(text: &str, budget: usize) -> &str {
    let mut end = 0;
    for (at, c) in text.char_indices() {
        let next = at.saturating_add(c.len_utf8());
        if next > budget {
            break;
        }
        end = next;
    }
    text.get(..end).unwrap_or_default()
}
