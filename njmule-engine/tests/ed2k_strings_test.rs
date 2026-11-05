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

// Valid UTF-8, the same with one byte changed, or any bytes.
fn nearly_utf8() -> impl proptest::strategy::Strategy<Value = Vec<u8>> {
    use proptest::prelude::*;
    let valid = any::<String>().prop_filter("not empty", |text| !text.is_empty()).prop_map(String::into_bytes);
    prop_oneof![
        valid.clone(),
        valid.prop_flat_map(|bytes| {
            let length = bytes.len();
            (Just(bytes), 0..length).prop_map(|(mut changed, at)| {
                changed[at] ^= 0x40;
                changed
            })
        }),
        proptest::collection::vec(any::<u8>(), 0..=40),
    ]
}

proptest::proptest! {
    #[test]
    fn should_round_trip_text_without_lone_surrogates_in_both_encodings(text in proptest::prelude::any::<String>()) {
        proptest::prop_assume!(!text.starts_with('\u{FEFF}'));

        let read: Vec<String> = [StringEncoding::Utf8, StringEncoding::Utf8BomIfNeeded]
            .into_iter()
            .map(|encoding| decode(&encode(&text, encoding)))
            .collect();

        proptest::prop_assert_eq!(read, vec![text.clone(), text]);
    }

    #[test]
    fn should_take_as_utf8_exactly_what_a_strict_decoder_accepts(raw in nearly_utf8()) {
        let text = decode(&raw);

        let body = raw.strip_prefix(&[0xEF, 0xBB, 0xBF]).unwrap_or(&raw);
        // The bytes windows-1252 leaves undefined are the replacement character, as a Java decoder gives them.
        let expected = std::str::from_utf8(body).map(str::to_owned).unwrap_or_else(|_| {
            encoding_rs::WINDOWS_1252
                .decode_without_bom_handling(body)
                .0
                .chars()
                .map(|c| if matches!(c, '\u{81}' | '\u{8D}' | '\u{8F}' | '\u{90}' | '\u{9D}') { '\u{FFFD}' } else { c })
                .collect()
        });
        proptest::prop_assert_eq!(text, expected);
    }

    #[test]
    fn should_decode_any_bytes_to_some_text(raw in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=256)) {
        let text = decode(&raw);

        proptest::prop_assert!(raw.is_empty() || !text.is_empty());
    }
}
