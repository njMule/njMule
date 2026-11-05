// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A read-only INI file as the reference client writes it: sections and keys without case, the first of a key winning,
//! spaces around `=` dropped, the text decoded as the other text files of a profile. Lines before the first `[section]`
//! belong to [`GLOBAL`]; booleans are `0` and `1`. Our own: no INI library keeps these rules (the first key winning, a
//! section line ending in a comment, a key starting with a letter or digit).

use std::collections::HashMap;

use crate::persist::format::text_files;

/// The section of the lines before the first `[section]`.
pub const GLOBAL: &str = "";

#[derive(Debug, Clone, Default)]
pub struct IniFile {
    sections: Vec<Section>,
}

#[derive(Debug, Clone)]
struct Section {
    key: String,
    name: String,
    values: HashMap<String, String>,
}

impl Section {
    fn named(name: &str) -> Self {
        Self { key: name.to_lowercase(), name: name.to_owned(), values: HashMap::new() }
    }
}

impl IniFile {
    pub fn parse(data: &[u8]) -> Self {
        let mut sections = vec![Section::named(GLOBAL)];
        let mut current = 0;
        for raw in text_files::lines(data) {
            let line = raw.trim();
            if let Some(name) = section_name(line) {
                let key = name.to_lowercase();
                current = sections.iter().position(|section| section.key == key).unwrap_or_else(|| {
                    sections.push(Section::named(name));
                    sections.len().saturating_sub(1)
                });
                continue;
            }
            if !line.chars().next().is_some_and(char::is_alphanumeric) {
                continue;
            }
            if let Some((key, value)) = line.split_once('=')
                && let Some(section) = sections.get_mut(current)
            {
                section.values.entry(key.trim().to_lowercase()).or_insert_with(|| value.trim().to_owned());
            }
        }
        Self { sections }
    }

    /// The names of the sections as first written, in file order, without [`GLOBAL`].
    pub fn sections(&self) -> Vec<&str> {
        self.sections.iter().map(|section| section.name.as_str()).filter(|name| !name.is_empty()).collect()
    }

    pub fn has_section(&self, section: &str) -> bool {
        self.section(section).is_some()
    }

    pub fn get(&self, section: &str, key: &str) -> Option<&str> {
        self.section(section)?.values.get(&key.to_lowercase()).map(String::as_str)
    }

    /// A signed decimal integer; a value that does not parse is none.
    pub fn get_long(&self, section: &str, key: &str) -> Option<i64> {
        self.get(section, key)?.parse().ok()
    }

    pub fn get_boolean(&self, section: &str, key: &str) -> Option<bool> {
        self.get_long(section, key).map(|value| value != 0)
    }

    fn section(&self, name: &str) -> Option<&Section> {
        let key = name.to_lowercase();
        self.sections.iter().find(|section| section.key == key)
    }
}

// The name of a `[name]` line, which may end with a `;` comment; none for any other line.
fn section_name(line: &str) -> Option<&str> {
    let inner = line.strip_prefix('[')?;
    let (close, _) = inner.match_indices(']').find(|(close, _)| {
        inner.get(close.saturating_add(1)..).is_some_and(|rest| {
            let rest = rest.trim_start();
            rest.is_empty() || rest.starts_with(';')
        })
    })?;
    inner.get(..close).filter(|name| !name.is_empty()).map(str::trim)
}
