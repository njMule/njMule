// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's TagCodecTest, under their names: expected bytes follow the old and new eD2K tag
//! encodings and the Kad tag encoding.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use njmule_engine::protocol::io::{PacketReader, PacketWriter, StringEncoding};
use njmule_engine::protocol::tag::tag_codec::{
    read_ed2k, read_ed2k_list, read_kad, read_kad_list, write_ed2k, write_kad, write_kad_list,
};
use njmule_engine::protocol::tag::tags;
use njmule_engine::protocol::tag::{Ed2kTagFormat, Tag, TagName};
use proptest::prelude::*;

const FILENAME: u8 = 0x01;
const FILESIZE: u8 = 0x02;
const SOURCEPORT: u8 = 0xFD;
const USERS: &[u8] = b"users";

fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

fn string(id: u8, value: &str) -> Tag {
    Tag::String { name: TagName::id(id), value: value.to_owned() }
}

fn new_ed2k(tag: &Tag) -> String {
    let mut out = PacketWriter::new();
    write_ed2k(&mut out, tag, Ed2kTagFormat::New, StringEncoding::Utf8).unwrap();
    hex::encode(out.to_byte_array())
}

fn old_ed2k(tag: &Tag) -> String {
    let mut out = PacketWriter::new();
    write_ed2k(&mut out, tag, Ed2kTagFormat::Old, StringEncoding::Utf8BomIfNeeded).unwrap();
    hex::encode(out.to_byte_array())
}

fn read(hex: &str) -> Result<Tag, String> {
    let data = hex::decode(hex).unwrap();
    let mut input = PacketReader::of(&data);
    read_ed2k(&mut input).map_err(|e| e.to_string())
}

#[test]
fn should_use_the_smallest_integer_type_in_the_new_encoding() {
    for (size, expected) in
        [(5, "890205"), (0x1234, "88023412"), (0x1234_5678, "830278563412"), (0x1_0000_0000, "8b020000000001000000")]
    {
        assert_eq!(new_ed2k(&int(FILESIZE, size)), expected, "size {size}");
    }
}

#[test]
fn should_use_short_strings_in_the_new_encoding() {
    let seventeen = "a".repeat(17);
    for (name, expected) in [
        ("abc", "9301616263".to_owned()),
        (seventeen.as_str(), format!("82011100{}", "61".repeat(17))),
        ("", "82010000".to_owned()),
    ] {
        assert_eq!(new_ed2k(&string(FILENAME, name)), expected, "name {name:?}");
    }
}

#[test]
fn should_write_names_with_their_length_and_wide_integers_in_the_old_encoding() {
    for (tag, expected) in [
        (int(FILESIZE, 5), "0301000205000000"),
        (Tag::Int { name: TagName::of_bytes(USERS), value: 10 }, "03050075736572730a000000"),
        (Tag::Int { name: TagName::of_bytes(b"\x090"), value: 0x1_0000_0000 }, "0b020009300000000001000000"),
        (string(FILENAME, "abc"), "020100010300616263"),
    ] {
        assert_eq!(old_ed2k(&tag), expected, "{tag:?}");
    }
}

#[test]
fn should_keep_the_long_form_for_string_names_in_the_new_encoding() {
    let tag = Tag::Int { name: TagName::of_bytes(USERS), value: 7 };

    assert_eq!(new_ed2k(&tag), "090500757365727307");
}

#[test]
fn should_always_prefix_kad_names_with_their_length() {
    let mut out = PacketWriter::new();
    write_kad(&mut out, &int(SOURCEPORT, 4662)).unwrap();

    assert_eq!(hex::encode(out.to_byte_array()), "080100fd3612");
}

#[test]
fn should_read_both_ed2k_encodings() {
    for (hex, expected) in [
        ("0301000205000000", int(FILESIZE, 5)),
        ("88023412", int(FILESIZE, 0x1234)),
        ("9301616263", string(FILENAME, "abc")),
    ] {
        assert_eq!(read(hex), Ok(expected), "{hex}");
    }
}

#[test]
fn should_read_the_odd_byte_count_of_a_bool_array() {
    // 8 bits occupy 8/8+1 = 2 bytes on the wire.
    let tag = read("86010800ff00");

    assert_eq!(tag, Ok(Tag::BoolArray { name: TagName::id(1), bit_count: 8, value: vec![0xFF, 0x00] }));
}

#[test]
fn should_read_a_bsob_in_ed2k_tags() {
    let tag = read("8a0102abcd");

    assert_eq!(tag, Ok(Tag::Bsob { name: TagName::id(1), value: vec![0xAB, 0xCD] }));
}

#[test]
fn should_never_write_a_bsob_in_ed2k_tags() {
    let mut out = PacketWriter::new();

    let written = write_ed2k(
        &mut out,
        &Tag::Bsob { name: TagName::id(1), value: vec![] },
        Ed2kTagFormat::New,
        StringEncoding::Utf8,
    );

    assert!(written.is_err());
}

#[test]
fn should_reject_an_unknown_type() {
    assert!(read("8c0100").is_err());
}

#[test]
fn should_reject_an_oversized_blob() {
    assert!(read("8701ffffff7f00").is_err());
}

#[test]
fn should_reject_a_type_kad_does_not_know() {
    let data = hex::decode("0701000100").unwrap();

    assert!(read_kad(&mut PacketReader::of(&data)).is_err());
}

#[test]
fn should_check_the_count_of_a_list_against_the_data() {
    let data = hex::decode("890205").unwrap();

    assert!(read_ed2k_list(&mut PacketReader::of(&data), 2, 100).is_err());
}

// Integer tags 1 to 4 holding a port, nothing, more than sixteen bits and the highest port.
fn port_tags() -> Vec<Tag> {
    vec![int(1, 4662), int(2, 0), int(3, 0x1_0000), int(4, 0xFFFF)]
}

#[test]
fn should_take_a_port_within_its_range() {
    let port = tags::port_value(&port_tags(), 1);

    assert_eq!(port, Some(4662));
}

#[test]
fn should_take_the_highest_port() {
    let port = tags::port_value(&port_tags(), 4);

    assert_eq!(port, Some(0xFFFF));
}

#[test]
fn should_not_take_a_port_of_zero() {
    let port = tags::port_value(&port_tags(), 2);

    assert_eq!(port, None);
}

#[test]
fn should_not_take_a_port_beyond_sixteen_bits() {
    let port = tags::port_value(&port_tags(), 3);

    assert_eq!(port, None);
}

#[test]
fn should_take_no_port_without_the_tag() {
    let port = tags::port_value(&port_tags(), 5);

    assert_eq!(port, None);
}

fn names() -> impl Strategy<Value = TagName> {
    prop_oneof![
        any::<u8>().prop_map(TagName::id),
        prop::collection::vec(any::<u8>(), 0..=12).prop_map(|raw| TagName::of_bytes(&raw))
    ]
}

// Unicode without surrogates or a leading BOM: every value has one UTF-8 form.
fn strings() -> impl Strategy<Value = String> {
    prop::collection::vec(prop::char::range('\u{1}', '\u{D7FF}'), 0..=40).prop_map(String::from_iter)
}

fn ed2k_tags() -> impl Strategy<Value = Tag> {
    prop_oneof![
        (names(), any::<u64>()).prop_map(|(name, value)| Tag::Int { name, value }),
        (names(), strings()).prop_map(|(name, value)| Tag::String { name, value }),
        (names(), any::<[u8; 16]>()).prop_map(|(name, value)| Tag::Hash { name, value }),
        (names(), any::<f32>()).prop_map(|(name, value)| Tag::Float { name, value }),
        (names(), any::<bool>()).prop_map(|(name, value)| Tag::Bool { name, value }),
        (names(), 0u16..=100).prop_map(|(name, bit_count)| Tag::BoolArray {
            name,
            bit_count,
            value: vec![0; usize::from(bit_count / 8) + 1],
        }),
        (names(), prop::collection::vec(any::<u8>(), 0..=300)).prop_map(|(name, value)| Tag::Blob { name, value }),
    ]
}

fn kad_tags() -> impl Strategy<Value = Vec<Tag>> {
    let tag = prop_oneof![
        (names(), any::<u64>()).prop_map(|(name, value)| Tag::Int { name, value }),
        (names(), strings()).prop_map(|(name, value)| Tag::String { name, value }),
        (names(), any::<[u8; 16]>()).prop_map(|(name, value)| Tag::Hash { name, value }),
        (names(), any::<f32>()).prop_map(|(name, value)| Tag::Float { name, value }),
        (names(), prop::collection::vec(any::<u8>(), 0..=255)).prop_map(|(name, value)| Tag::Bsob { name, value }),
    ];
    prop::collection::vec(tag, 0..=8)
}

// Compared as written out, so that a NaN float equals itself.
fn shown(tags: &[Tag]) -> String {
    format!("{tags:?}")
}

fn round_trip_ed2k(tag: &Tag, format: Ed2kTagFormat, encoding: StringEncoding) -> Tag {
    let mut out = PacketWriter::new();
    write_ed2k(&mut out, tag, format, encoding).unwrap();
    let data = out.to_byte_array();
    read_ed2k(&mut PacketReader::of(&data)).unwrap()
}

proptest! {
    #[test]
    fn should_round_trip_tags_in_the_new_ed2k_encoding(tag in ed2k_tags()) {
        let read = round_trip_ed2k(&tag, Ed2kTagFormat::New, StringEncoding::Utf8);

        prop_assert_eq!(shown(&[read]), shown(&[tag]));
    }

    #[test]
    fn should_round_trip_tags_in_the_old_ed2k_encoding(tag in ed2k_tags()) {
        let read = round_trip_ed2k(&tag, Ed2kTagFormat::Old, StringEncoding::Utf8BomIfNeeded);

        prop_assert_eq!(shown(&[read]), shown(&[tag]));
    }

    #[test]
    fn should_round_trip_tag_lists_in_the_kad_encoding(tags in kad_tags()) {
        let mut out = PacketWriter::new();
        write_kad_list(&mut out, &tags).unwrap();
        let data = out.to_byte_array();
        let mut input = PacketReader::of(&data);

        let read = read_kad_list(&mut input).unwrap();

        prop_assert_eq!(shown(&read), shown(&tags));
        prop_assert!(!input.has_remaining());
    }

    #[test]
    fn should_never_let_garbage_escape_an_ed2k_list_as_an_unchecked_exception(
        data in prop::collection::vec(any::<u8>(), 0..=64),
    ) {
        let _ = read_ed2k_list(&mut PacketReader::of(&data), data.len() as u64, 64);
    }

    #[test]
    fn should_never_let_garbage_escape_a_kad_list_as_an_unchecked_exception(
        data in prop::collection::vec(any::<u8>(), 0..=64),
    ) {
        let _ = read_kad_list(&mut PacketReader::of(&data));
    }
}
