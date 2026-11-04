// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's TagCodecTest, under their names: expected bytes follow the old and new eD2K tag
//! encodings and the Kad tag encoding.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use njmule_engine::protocol::io::{PacketReader, PacketWriter, StringEncoding};
use njmule_engine::protocol::tag::tag_codec::{read_ed2k, read_ed2k_list, read_kad, write_ed2k, write_kad};
use njmule_engine::protocol::tag::{Ed2kTagFormat, Tag, TagName};

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
