// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's UInt128Test, under its names: a Kad id on the wire, big-endian, in hex, and as a distance.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::kad::uint128::UInt128;
use proptest::prelude::*;

const SAMPLE: UInt128 = UInt128(0x0011_2233_4455_6677_8899_AABB_CCDD_EEFF);

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn id(high: u64, low: u64) -> UInt128 {
    UInt128(u128::from(high) << 64 | u128::from(low))
}

#[test]
fn should_write_each_word_little_endian_most_significant_first_on_the_wire() {
    let bytes = SAMPLE.to_wire_bytes();

    assert_eq!(hex(&bytes), "33221100".to_owned() + "77665544" + "bbaa9988" + "ffeeddcc");
}

#[test]
fn should_read_an_id_back_from_its_wire_bytes() {
    let bytes = SAMPLE.to_wire_bytes();

    let read = UInt128::from_wire_bytes(&bytes).unwrap();

    assert_eq!(read, SAMPLE);
}

#[test]
fn should_write_the_id_big_endian() {
    let bytes = SAMPLE.to_big_endian();

    assert_eq!(hex(&bytes), "00112233445566778899aabbccddeeff");
}

#[test]
fn should_refuse_wire_bytes_of_the_wrong_length() {
    let read = UInt128::from_wire_bytes(&[0; 15]);

    assert!(read.is_err());
}

#[test]
fn should_write_the_hex_in_upper_case_most_significant_first() {
    let hex = id(0x0011_2233_4455_6677, 0x8899_AABB_CCDD_EEFF).to_hex();

    assert_eq!(hex, "00112233445566778899AABBCCDDEEFF");
}

#[test]
fn should_refuse_hex_with_a_sign() {
    let parsed = UInt128::parse_hex("+0112233445566778899AABBCCDDEEFF");

    assert_eq!(parsed, None);
}

#[test]
fn should_refuse_hex_of_the_wrong_length() {
    let parsed = UInt128::parse_hex("00");

    assert_eq!(parsed, None);
}

#[test]
fn should_order_ids_as_unsigned_numbers() {
    let top = id(1 << 63, 0);
    let orders =
        [(top, id(u64::MAX >> 1, u64::MAX)), (id(0, 1 << 63), id(0, u64::MAX >> 1)), (id(u64::MAX, u64::MAX), top)];
    for (greater, smaller) in orders {
        assert!(greater > smaller, "{greater} > {smaller}");
    }
}

#[test]
fn should_number_the_bits_from_the_most_significant() {
    let low_sign = id(0, 1 << 63);
    let cases = [(id(1 << 63, 0), 0, 1), (low_sign, 64, 1), (low_sign, 63, 0), (id(u64::MAX, u64::MAX), 127, 1)];
    for (value, index, expected) in cases {
        assert_eq!(value.bit(index), expected, "bit {index} of {value}");
    }
}

#[test]
fn should_take_the_top32_bits_as_an_unsigned_number() {
    let top = id(u64::MAX, u64::MAX).top32();

    assert_eq!(top, 0xFFFF_FFFF);
}

proptest! {
    #[test]
    fn should_measure_no_distance_from_an_id_to_itself(a1: u64, a2: u64) {
        let value = id(a1, a2);

        prop_assert!((value ^ value).is_zero());
    }

    #[test]
    fn should_measure_the_same_distance_both_ways(a1: u64, a2: u64, b1: u64, b2: u64) {
        let (a, b) = (id(a1, a2), id(b1, b2));

        prop_assert_eq!(a ^ b, b ^ a);
    }

    #[test]
    fn should_combine_the_distances_through_a_middle_id_into_the_direct_one(
        a1: u64,
        a2: u64,
        b1: u64,
        b2: u64,
        c1: u64,
    ) {
        let (a, b, c) = (id(a1, a2), id(b1, b2), id(c1, a2 ^ b2));

        prop_assert_eq!((a ^ b) ^ (b ^ c), a ^ c);
    }

    #[test]
    fn should_order_ids_as_big_integer_does(a1: u64, a2: u64, b1: u64, b2: u64) {
        let (a, b) = (id(a1, a2), id(b1, b2));

        // Big-endian bytes compare as the unsigned numbers they spell.
        prop_assert_eq!(a.cmp(&b), a.to_big_endian().cmp(&b.to_big_endian()));
    }

    #[test]
    fn should_number_the_bits_as_big_integer_does_from_the_top(a1: u64, a2: u64) {
        let value = id(a1, a2);

        for index in 0..UInt128::BITS {
            let byte = value.to_big_endian()[(index / 8) as usize];
            prop_assert_eq!(value.bit(index), byte >> (7 - index % 8) & 1);
        }
    }

    #[test]
    fn should_round_trip_through_hex(high: u64, low: u64) {
        let value = id(high, low);

        prop_assert_eq!(UInt128::parse_hex(&value.to_hex()), Some(value));
    }

    #[test]
    fn should_round_trip_through_the_wire(high: u64, low: u64) {
        let value = id(high, low);

        prop_assert_eq!(UInt128::from_wire_bytes(&value.to_wire_bytes()).unwrap(), value);
    }

    #[test]
    fn should_round_trip_through_big_endian_bytes(high: u64, low: u64) {
        let value = id(high, low);

        prop_assert_eq!(UInt128::from_big_endian(value.to_big_endian()), value);
    }

    // A hash is its 16 bytes, which the id takes big-endian.
    #[test]
    fn should_round_trip_through_a_hash(high: u64, low: u64) {
        let value = id(high, low);
        let hash: [u8; 16] = value.to_big_endian();

        prop_assert_eq!(UInt128::from_big_endian(hash), value);
    }

    #[test]
    fn should_take_the_first32_bits_as_the_top_bits(high: u64, index in 0u32..32) {
        let value = id(high, 0);

        prop_assert_eq!(u8::try_from(value.top32() >> (31 - index) & 1).unwrap(), value.bit(index));
    }
}
