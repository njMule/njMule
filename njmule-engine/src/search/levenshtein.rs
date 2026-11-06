// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The edit distance of two names: insertions, deletions and substitutions of characters.

/// The fewest single-character edits that turn `a` into `b`, counted on UTF-16 units as the Java engine counts them.
pub fn distance(a: &str, b: &str) -> usize {
    let a: Vec<u16> = a.encode_utf16().collect();
    let b: Vec<u16> = b.encode_utf16().collect();
    strsim::generic_levenshtein(&a, &b)
}
