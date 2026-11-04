// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! String encoding on the eD2K network and in its data files: a UTF-8 BOM marks UTF-8, valid UTF-8 is taken as such,
//! anything else is the Windows ANSI code page (Windows-1252).

use super::StringEncoding;

const BOM: [u8; 3] = [0xEF, 0xBB, 0xBF];

// Windows-1252's 0x80..=0x9F as the Java engine decodes them: its five undefined bytes become U+FFFD. WHATWG's table,
// which encoding_rs follows, keeps them as C1 controls instead, so it would not answer the corpora alike.
const WINDOWS_1252_80_9F: [char; 32] = [
    '\u{20AC}', '\u{FFFD}', '\u{201A}', '\u{0192}', '\u{201E}', '\u{2026}', '\u{2020}', '\u{2021}', '\u{02C6}',
    '\u{2030}', '\u{0160}', '\u{2039}', '\u{0152}', '\u{FFFD}', '\u{017D}', '\u{FFFD}', '\u{FFFD}', '\u{2018}',
    '\u{2019}', '\u{201C}', '\u{201D}', '\u{2022}', '\u{2013}', '\u{2014}', '\u{02DC}', '\u{2122}', '\u{0161}',
    '\u{203A}', '\u{0153}', '\u{FFFD}', '\u{017E}', '\u{0178}',
];

/// The string `raw` holds. UTF-8 is checked as strictly as a decoder does (no overlong forms, surrogates, or code
/// points beyond U+10FFFF), which `str::from_utf8` does too.
pub fn decode(raw: &[u8]) -> String {
    let text = raw.strip_prefix(&BOM).unwrap_or(raw);
    match std::str::from_utf8(text) {
        Ok(utf8) => utf8.to_owned(),
        Err(_) => text.iter().map(|&byte| windows_1252(byte)).collect(),
    }
}

pub fn encode(value: &str, encoding: StringEncoding) -> Vec<u8> {
    match encoding {
        StringEncoding::Utf8 => value.as_bytes().to_vec(),
        StringEncoding::Utf8BomIfNeeded if value.is_ascii() => value.as_bytes().to_vec(),
        StringEncoding::Utf8BomIfNeeded => [&BOM[..], value.as_bytes()].concat(),
    }
}

// Below 0x80 and from 0xA0 on, Windows-1252 is Latin-1, whose bytes are their own code points.
fn windows_1252(byte: u8) -> char {
    match byte {
        0x80..=0x9F => WINDOWS_1252_80_9F.get(usize::from(byte ^ 0x80)).copied().unwrap_or('\u{FFFD}'),
        _ => char::from(byte),
    }
}
