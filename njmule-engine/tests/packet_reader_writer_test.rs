// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's PacketReaderWriterTest, under their names; those of patching what was written
//! and of a size cap, which the engine never uses, and of negative lengths, which Rust cannot have, are Java only.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use njmule_engine::protocol::io::{PacketReader, PacketWriter, StringEncoding};
use proptest::prelude::*;

const HASH: [u8; 16] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF];

fn zeros(length: usize) -> Vec<u8> {
    vec![0; length]
}

#[test]
fn should_write_little_endian() {
    let mut out = PacketWriter::new();

    out.u8(0xE3).u16(0x1234).u32(0x89AB_CDEF).u64(0x0102_0304_0506_0708);

    assert_eq!(hex::encode(out.to_byte_array()), "e33412efcdab890807060504030201");
}

#[test]
fn should_read_little_endian_and_unsigned() {
    let data = hex::decode("ff3412efcdab89ffffffffffffffff").unwrap();
    let mut input = PacketReader::of(&data);

    let read = (input.u8().unwrap(), input.u16().unwrap(), input.u32().unwrap(), input.u64().unwrap());

    assert_eq!(read, (0xFF, 0x1234, 0x89AB_CDEF, u64::MAX));
    assert!(!input.has_remaining());
}

#[test]
fn should_refuse_truncated_bytes_without_moving_on() {
    let data = hex::decode("ffff0102").unwrap();
    let mut input = PacketReader::of(&data);
    let length = input.u16().unwrap();

    let read = input.bytes(usize::from(length));

    assert!(read.unwrap_err().to_string().contains("need 65535 bytes"));
    assert_eq!(input.u16().unwrap(), 0x0201);
}

#[test]
fn should_refuse_a_truncated_u32_without_moving_on() {
    let data = hex::decode("ffff0102").unwrap();
    let mut input = PacketReader::of(&data);
    input.u16().unwrap();

    let read = input.u32();

    assert!(read.is_err());
    assert_eq!(input.u16().unwrap(), 0x0201);
}

#[test]
fn should_accept_a_count_that_fits_the_remaining_data() {
    let data = zeros(10);

    let count = PacketReader::of(&data).count(5, 2, 100);

    assert_eq!(count.unwrap(), 5);
}

#[test]
fn should_refuse_a_count_that_does_not_fit_the_remaining_data() {
    let data = zeros(10);

    let count = PacketReader::of(&data).count(6, 2, 100);

    assert!(count.is_err());
}

#[test]
fn should_refuse_a_count_above_the_maximum() {
    let data = zeros(10);

    let count = PacketReader::of(&data).count(101, 0, 100);

    assert!(count.is_err());
}

#[test]
fn should_advance_the_parent_past_a_slice() {
    let data = hex::decode("0102030405").unwrap();
    let mut input = PacketReader::of(&data);

    let mut slice = input.slice(3).unwrap();

    assert_eq!(slice.rest(), [1, 2, 3]);
    assert_eq!(input.u16().unwrap(), 0x0504);
}

#[test]
fn should_refuse_a_slice_beyond_the_end() {
    let data = hex::decode("0102030405").unwrap();
    let mut input = PacketReader::of(&data);
    input.slice(3).unwrap();
    input.u16().unwrap();

    let slice = input.slice(1);

    assert!(slice.is_err());
}

#[test]
fn should_check_the_length_of_bytes_before_any_allocation() {
    let data = zeros(8);
    let mut input = PacketReader::of(&data);

    let read = input.bytes(i32::MAX as usize);

    assert!(read.is_err());
    assert_eq!(input.position(), 0);
}

#[test]
fn should_check_the_length_of_a_skip_before_moving_on() {
    let data = zeros(8);
    let mut input = PacketReader::of(&data);

    let skipped = input.skip(i32::MAX as usize);

    assert!(skipped.is_err());
    assert_eq!(input.position(), 0);
}

#[test]
fn should_check_the_length_of_a_slice_before_moving_on() {
    let data = zeros(8);
    let mut input = PacketReader::of(&data);

    let slice = input.slice(i32::MAX as usize);

    assert!(slice.is_err());
    assert_eq!(input.position(), 0);
}

#[test]
fn should_refuse_a_count_beyond_the_int_range() {
    let data = zeros(8);
    let input = PacketReader::of(&data);

    let count = input.count(0xFFFF_FFFF, 0, i32::MAX as usize);

    assert!(count.is_err());
    assert_eq!(input.position(), 0);
}

#[test]
fn should_accept_a_count_of_items_filling_the_remaining_data() {
    let data = zeros(8);
    let input = PacketReader::of(&data);

    let count = input.count(8, 1, 10);

    assert_eq!(count.unwrap(), 8);
    assert_eq!(input.position(), 0);
}

#[test]
fn should_not_check_the_fit_of_a_count_without_item_size() {
    let data = zeros(8);
    let input = PacketReader::of(&data);

    let count = input.count(1000, 0, 1000);

    assert_eq!(count.unwrap(), 1000, "no item size, no fit check");
    assert_eq!(input.position(), 0);
}

#[test]
fn should_slice_the_whole_remaining_data() {
    let data = zeros(8);
    let mut input = PacketReader::of(&data);

    let slice = input.slice(8).unwrap();

    assert_eq!(slice.remaining(), 8);
}

#[test]
fn should_slice_nothing_at_the_end() {
    let data = zeros(8);
    let mut input = PacketReader::of(&data);
    input.slice(8).unwrap();

    let slice = input.slice(0).unwrap();

    assert!(!slice.has_remaining());
    assert!(!input.has_remaining());
}

#[test]
fn should_round_trip_the_longest_string() {
    let longest = "a".repeat(0xFFFF);
    let mut out = PacketWriter::new();
    out.string16(&longest, StringEncoding::Utf8).unwrap();
    let data = out.to_byte_array();

    let read = PacketReader::of(&data).string16();

    assert_eq!(read.unwrap(), longest);
}

#[test]
fn should_refuse_to_write_a_string_longer_than_sixteen_bits() {
    let mut out = PacketWriter::new();

    let written = out.string16(&"a".repeat(0x10000), StringEncoding::Utf8).map(|_| ());

    assert!(written.is_err());
}

#[test]
fn should_count_the_length_of_a_string_in_bytes_not_characters() {
    let mut out = PacketWriter::new();

    let written = out.string16(&"é".repeat(0x8000), StringEncoding::Utf8).map(|_| ());

    assert!(written.is_err(), "counted in bytes, not characters");
}

#[test]
fn should_refuse_a_string_longer_than_the_data() {
    let data = hex::decode("ffff61").unwrap();

    let read = PacketReader::of(&data).string16();

    assert!(read.is_err());
}

#[test]
fn should_write_the_largest_counts_and_offset() {
    let mut out = PacketWriter::new();

    out.count8(255).unwrap().count16(0xFFFF).unwrap().offset32(0xFFFF_FFFF).unwrap();

    assert_eq!(out.to_byte_array().len(), 7);
}

#[test]
fn should_refuse_a_count8_that_does_not_fit_its_field() {
    let mut out = PacketWriter::new();

    let written = out.count8(256).map(|_| ());

    assert!(written.is_err());
}

#[test]
fn should_refuse_a_count16_that_does_not_fit_its_field() {
    let mut out = PacketWriter::new();

    let written = out.count16(0x10000).map(|_| ());

    assert!(written.is_err());
}

#[test]
fn should_refuse_an_offset32_that_does_not_fit_its_field() {
    let mut out = PacketWriter::new();

    let written = out.offset32(0x1_0000_0000).map(|_| ());

    assert!(written.is_err());
}

#[test]
fn should_round_trip_strings_and_hashes() {
    let mut out = PacketWriter::new();
    out.string16("Ünïcødé", StringEncoding::Utf8).unwrap();
    out.hash16(&HASH);
    let data = out.to_byte_array();
    let mut input = PacketReader::of(&data);

    let read = (input.string16().unwrap(), input.hash16().unwrap());

    assert_eq!(read, ("Ünïcødé".to_owned(), HASH));
    assert!(!input.has_remaining());
}

proptest! {
    #[test]
    fn should_round_trip_integers(a: u8, b: u16, c: u32, d: u64, e: f32) {
        let mut out = PacketWriter::new();
        out.u8(a).u16(b).u32(c).u64(d).f32(e);
        let data = out.to_byte_array();
        let mut input = PacketReader::of(&data);

        let read = (input.u8().unwrap(), input.u16().unwrap(), input.u32().unwrap(), input.u64().unwrap());

        prop_assert_eq!(read, (a, b, c, d));
        prop_assert_eq!(input.f32().unwrap().to_bits(), e.to_bits());
    }
}
