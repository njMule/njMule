// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's Ed2kLinkCodecTest, under their names: what a link cannot carry is
//! percent-encoded, and decoding gives back what was encoded.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use njmule_engine::api::ed2k_link_codec::{decode, encode};

const PUNCTUATION: &str = "!\"#$&'()*+,-./:;<=>?@[\\]^_`{}~";
const TEXTS: &[&str] = &["", "a b|c%d", "Café.avi", "\u{7F}\u{1}", PUNCTUATION, "日本語 😀", "tab\there\n", "%41"];

// The encoding by hand, as the reference.
fn encoded_by_hand(original: &str) -> String {
    original
        .bytes()
        .map(|b| match b {
            b'%' | b' ' | b'|' => format!("%{b:02X}"),
            0x20..0x7F => char::from(b).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

#[test]
fn should_percent_encode_what_a_link_cannot_carry() {
    for (original, expected) in [
        ("a b|c%d", "a%20b%7Cc%25d"),
        ("Café.avi", "Caf%C3%A9.avi"),
        ("\u{7F}\u{1}", "%7F%01"),
        (PUNCTUATION, PUNCTUATION),
    ] {
        assert_eq!(encode(original), expected, "{original:?}");
    }
}

#[test]
fn should_encode_as_before() {
    for original in TEXTS {
        assert_eq!(encode(original), encoded_by_hand(original), "{original:?}");
    }
}

#[test]
fn should_decode_what_it_encodes() {
    // Printable text: decoding drops control characters, and C1 controls (U+0080 to U+009F) with them.
    for original in ["", "a b|c%d", "Café.avi", PUNCTUATION, "日本語 😀", "%41", "ÿ and Ā"] {
        assert_eq!(decode(&encode(original)), original, "{original:?}");
    }
}
