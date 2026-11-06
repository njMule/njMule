// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The keywords Kad indexes a file name under, and their ids: words of at least three bytes, in the network's fixed
//! lower case, without the extension; the id is the MD4 hash of the word in UTF-8.

use crate::files::hash::ed2k;
use crate::kad::kad_lower_case;
use crate::kad::uint128::UInt128;

/// Characters that separate words.
pub const DELIMITERS: &str = " ()[]{}<>,._-!?:;\\/\"";

const MIN_BYTES: usize = 3;

/// The keywords of `text`, in order and without duplicates.
pub fn of(text: &str) -> Vec<String> {
    let mut words: Vec<String> = Vec::new();
    let mut last = "";
    for part in text.split(|c| DELIMITERS.contains(c)) {
        last = part;
        // Counted before lowering, which leaves a few words of one unit at two bytes.
        if part.len() >= MIN_BYTES {
            let word = kad_lower_case::to_lower_case(part);
            words.retain(|known| *known != word);
            words.push(word);
        }
    }
    // A last part of three single-byte characters is taken for the extension.
    if words.len() > 1 && last.len() == 3 && last.is_ascii() {
        words.pop();
    }
    words
}

/// The id a keyword is published under.
pub fn id(word: &str) -> UInt128 {
    UInt128::from_big_endian(ed2k::md4(word.as_bytes()))
}
