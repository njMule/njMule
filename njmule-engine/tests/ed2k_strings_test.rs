// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's Ed2kStringsTest, under their names, and the Windows-1252 bytes the corpora may
//! not hold.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use njmule_engine::protocol::io::StringEncoding;
use njmule_engine::protocol::io::ed2k_strings::{decode, encode};

fn decoded(hex: &str) -> String {
    decode(&hex::decode(hex).unwrap())
}

#[test]
fn should_decode_valid_utf8_as_utf8() {
    assert_eq!(decode("città €".as_bytes()), "città €");
}

#[test]
fn should_take_a_bom_as_a_mark_of_utf8_and_drop_it() {
    assert_eq!(decoded("efbbbf63c3a0"), "cà");
}

#[test]
fn should_fall_back_to_windows1252_for_invalid_utf8() {
    assert_eq!(decoded("436166e92080"), "Café €");
}

#[test]
fn should_fall_back_to_windows1252_for_invalid_utf8_after_a_bom_too() {
    assert_eq!(decoded("efbbbf80e9"), "€é");
}

#[test]
fn should_decode_a_bom_alone_as_no_text() {
    assert_eq!(decoded("efbbbf"), "");
}

#[test]
fn should_decode_malformed_utf8_as_windows1252() {
    for (description, hex, expected) in [
        ("overlong /", "c0af", "À¯".to_owned()),
        ("an encoded surrogate", "eda080", format!("í{}€", '\u{A0}')),
        ("a cut BOM", "efbb", "ï»".to_owned()),
        ("a lone lead byte", "ef", "ï".to_owned()),
        ("a lead byte beyond U+10FFFF", "f5808080", "õ€€€".to_owned()),
        ("a sequence cut at the end", "41c3", "AÃ".to_owned()),
        ("no bytes", "", String::new()),
    ] {
        assert_eq!(decoded(hex), expected, "{description}");
    }
}

// As the Java engine decodes them: its windows-1252 makes U+FFFD of the five bytes the code page leaves undefined.
#[test]
fn should_decode_the_bytes_windows1252_leaves_undefined_as_the_replacement_character() {
    for byte in ["81", "8d", "8f", "90", "9d"] {
        assert_eq!(decoded(byte), "\u{FFFD}", "0x{byte}");
    }
}

#[test]
fn should_write_ascii_without_a_bom() {
    assert_eq!(encode("plain", StringEncoding::Utf8BomIfNeeded), b"plain");
}

#[test]
fn should_write_unicode_with_a_bom() {
    assert_eq!(hex::encode(encode("à", StringEncoding::Utf8BomIfNeeded)), "efbbbfc3a0");
}

#[test]
fn should_write_unicode_without_a_bom_in_plain_utf8() {
    assert_eq!(hex::encode(encode("à", StringEncoding::Utf8)), "c3a0");
}
