// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Properties files in UTF-8, read and written as the Java engine does, and their numbered entries (`prefix.N.key`).
//!
//! The `java-properties` crate reads them. They are written here: the crate escapes every space and writes a control
//! character as a short `\u` escape (`\u1`) that no reader takes back, where the Java engine writes it as it is.

use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::Path;

use encoding_rs::UTF_8;
use java_properties::PropertiesIter;

use crate::persist::atomic_files;

const BYTE_ORDER_MARK: char = '\u{FEFF}';

/// Reads `file`, or its backup.
pub fn read(file: &Path) -> io::Result<BTreeMap<String, String>> {
    atomic_files::read(file, |data| Ok(parse(&String::from_utf8_lossy(data))))
}

/// The properties of `text`, the last value of a key winning; a leading byte order mark (editors write one) is skipped,
/// and an entry with a malformed `\u` escape is left out instead of the whole text.
pub fn parse(text: &str) -> BTreeMap<String, String> {
    let text = text.strip_prefix(BYTE_ORDER_MARK).unwrap_or(text);
    match load(text) {
        Ok(properties) => properties,
        Err(e) => {
            tracing::warn!("Leaving out lines with a malformed escape: {e}");
            let mut properties = BTreeMap::new();
            for entry in entries(text) {
                if let Ok(one) = load(&entry) {
                    properties.extend(one);
                }
            }
            properties
        }
    }
}

fn load(text: &str) -> Result<BTreeMap<String, String>, java_properties::PropertiesError> {
    let mut properties = BTreeMap::new();
    PropertiesIter::new_with_encoding(text.as_bytes(), UTF_8).read_into(|key, value| {
        properties.insert(key, value);
    })?;
    Ok(properties)
}

// The text split into entries: lines ending with an odd number of backslashes go on in the next one, except comments.
fn entries(text: &str) -> Vec<String> {
    let mut entries = Vec::new();
    let mut entry = String::new();
    for line in lines(text) {
        let first = entry.is_empty();
        entry.push_str(line);
        entry.push('\n');
        let start = line.trim_start();
        let comment = first && (start.starts_with('#') || start.starts_with('!'));
        if comment || !continues(line) {
            entries.push(std::mem::take(&mut entry));
        }
    }
    if !entry.is_empty() {
        entries.push(entry);
    }
    entries
}

// The lines of `text`, each ended by `\n`, `\r` or `\r\n`, as a properties file has them.
fn lines(text: &str) -> Vec<&str> {
    let mut lines = Vec::new();
    let mut rest = text;
    while !rest.is_empty() {
        let Some(end) = rest.find(['\r', '\n']) else {
            lines.push(rest);
            break;
        };
        let (line, tail) = rest.split_at(end);
        lines.push(line);
        rest = tail.strip_prefix("\r\n").or_else(|| tail.strip_prefix(['\r', '\n'])).unwrap_or(tail);
    }
    lines
}

fn continues(line: &str) -> bool {
    line.chars().rev().take_while(|&c| c == '\\').count() % 2 != 0
}

/// The numbers N of the `prefix.N.*` keys, in order; other keys are left out.
pub fn numbers(properties: &BTreeMap<String, String>, prefix: &str) -> BTreeSet<i32> {
    properties
        .keys()
        .filter_map(|key| key.strip_prefix(prefix))
        .filter_map(|rest| rest.split_once('.').map_or(rest, |(number, _)| number).parse().ok())
        .collect()
}

/// Appends the line of `key` and `value` to `text`, escaped as the Java engine writes it.
pub fn append(text: &mut String, key: &str, value: &str) {
    escape(text, key, true);
    text.push('=');
    escape(text, value, false);
    text.push('\n');
}

fn escape(text: &mut String, part: &str, every_space: bool) {
    for (index, c) in part.chars().enumerate() {
        match c {
            '\\' => text.push_str("\\\\"),
            ' ' if index == 0 || every_space => text.push_str("\\ "),
            '\t' => text.push_str("\\t"),
            '\n' => text.push_str("\\n"),
            '\r' => text.push_str("\\r"),
            '\x0c' => text.push_str("\\f"),
            '=' | ':' | '#' | '!' => {
                text.push('\\');
                text.push(c);
            }
            _ => text.push(c),
        }
    }
}
