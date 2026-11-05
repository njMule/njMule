// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's KadFilesTest, under their names: Kad ids on the wire, `nodes.dat` and
//! `preferencesKad.dat`, and the bit arithmetic of ids.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::{self, Contact, UdpKey};
use njmule_engine::persist::format::preferences_kad_dat::{self, Contents};
use njmule_engine::protocol::io::{PacketReader, PacketWriter};
use rand::{Rng, RngExt};

const ID: UInt128 = UInt128(0x0011_2233_4455_6677_8899_AABB_CCDD_EEFF);
// The id on the wire: four little-endian words, most significant first.
const ID_WIRE: &str = concat!("33221100", "77665544", "bbaa9988", "ffeeddcc");
const BOOTSTRAP_RECORD: &str =
    concat!("33221100", "77665544", "bbaa9988", "ffeeddcc", "04030201", "3612", "3712", "08");

fn file(parts: &[&str]) -> Vec<u8> {
    hex::decode(parts.concat()).unwrap()
}

fn verified_contact_with_a_udp_key() -> Contact {
    Contact {
        id: ID,
        ip: u32::from_be_bytes([5, 6, 7, 8]),
        udp_port: 4672,
        tcp_port: 4662,
        version: 10,
        contact_type: 0,
        udp_key: UdpKey { key: 0x1234_5678, ip: u32::from_be_bytes([9, 9, 9, 9]) },
        verified: true,
    }
}

#[test]
fn should_write_an_id_as_little_endian_words_on_the_wire() {
    let mut out = PacketWriter::new();

    ID.write(&mut out);

    assert_eq!(hex::encode(out.into_byte_array()), ID_WIRE);
}

#[test]
fn should_read_an_id_from_little_endian_words_on_the_wire() {
    let wire = hex::decode(ID_WIRE).unwrap();

    assert_eq!(UInt128::read(&mut PacketReader::of(&wire)).unwrap(), ID);
}

#[test]
fn should_take_an_id_from_a_big_endian_hash() {
    let hash: [u8; 16] = hex::decode("00112233445566778899aabbccddeeff").unwrap().try_into().unwrap();

    assert_eq!(UInt128::from_big_endian(hash), ID);
}

#[test]
fn should_give_an_id_as_a_big_endian_hash() {
    assert_eq!(hex::encode(ID.to_big_endian()), "00112233445566778899aabbccddeeff");
}

#[test]
fn should_read_version_zero_with_contact_types() {
    let contents = nodes_dat::read(&file(&["01000000", ID_WIRE, "04030201", "3612", "3712", "02"])).unwrap();

    assert_eq!(contents.file_version, 0);
    let first = &contents.contacts[0];
    assert_eq!(first.id, ID);
    assert_eq!(std::net::Ipv4Addr::from(first.ip).to_string(), "1.2.3.4");
    assert_eq!((first.udp_port, first.tcp_port), (4662, 4663));
    assert_eq!(first.contact_type, 2);
}

#[test]
fn should_read_version_one_with_kad_versions() {
    let contents =
        nodes_dat::read(&file(&["00000000", "01000000", "01000000", ID_WIRE, "04030201", "3612", "3712", "08"]))
            .unwrap();

    assert_eq!(contents.contacts[0].version, 8);
    assert!(!contents.contacts[0].verified);
}

#[test]
fn should_write_version_two() {
    let written = nodes_dat::write(&[verified_contact_with_a_udp_key()]).unwrap();

    assert_eq!(written.len(), 12 + 34);
    assert!(hex::encode(&written).starts_with(concat!("00000000", "02000000", "01000000")));
}

#[test]
fn should_read_back_the_version_two_it_writes() {
    let written = nodes_dat::write(&[verified_contact_with_a_udp_key()]).unwrap();

    let contents = nodes_dat::read(&written).unwrap();

    assert_eq!(contents.file_version, 2);
    assert_eq!(contents.contacts, [verified_contact_with_a_udp_key()]);
}

#[test]
fn should_recognise_a_bootstrap_list() {
    let contents =
        nodes_dat::read(&file(&["00000000", "03000000", "01000000", "02000000", BOOTSTRAP_RECORD, BOOTSTRAP_RECORD]))
            .unwrap();

    assert!(contents.bootstrap);
    assert_eq!(contents.contacts.len(), 2);
}

#[test]
fn should_refuse_a_bootstrap_list_shorter_than_its_count() {
    assert!(nodes_dat::read(&file(&["00000000", "03000000", "01000000", "02000000", BOOTSTRAP_RECORD])).is_err());
}

#[test]
fn should_ignore_unknown_versions() {
    for content in [concat!("00000000", "09000000"), "00000000"] {
        assert!(nodes_dat::read(&file(&[content])).unwrap().contacts.is_empty(), "{content}");
    }
}

#[test]
fn should_reject_an_impossible_count() {
    assert!(nodes_dat::read(&file(&["ffffff7f", ID_WIRE])).is_err());
}

#[test]
fn should_write_the_kad_preferences() {
    let written = preferences_kad_dat::write(&Contents { last_ip: u32::from_be_bytes([10, 0, 0, 1]), kad_id: ID });

    assert_eq!(written.len(), 23);
    assert_eq!(hex::encode(written), ["0100000a", "0000", ID_WIRE, "00"].concat());
}

#[test]
fn should_read_back_the_kad_preferences() {
    let preferences = Contents { last_ip: u32::from_be_bytes([10, 0, 0, 1]), kad_id: ID };

    assert_eq!(preferences_kad_dat::read(&preferences_kad_dat::write(&preferences)).unwrap(), preferences);
}

#[test]
fn should_fail_on_garbage_only_with_a_decode_exception() {
    let mut random = rand::rng();
    for _ in 0..2_000 {
        let mut data = vec![0; random.random_range(0..=120)];
        random.fill_bytes(&mut data);

        let _ = nodes_dat::read(&data);
        let _ = preferences_kad_dat::read(&data);
    }
}

const HIGH_AND_LOW_BITS: UInt128 = UInt128(0x8000_0000_0000_0000_0000_0000_0000_0001);

#[test]
fn should_count_the_bits_from_the_most_significant() {
    for (index, expected) in [(0, 1), (1, 0), (127, 1)] {
        assert_eq!(HIGH_AND_LOW_BITS.bit(index), expected, "bit {index}");
    }
}

#[test]
fn should_take_the_top32_bits_from_the_most_significant() {
    assert_eq!(HIGH_AND_LOW_BITS.top32(), 0x8000_0000);
}

#[test]
fn should_compare_ids_from_the_most_significant_bit() {
    assert!(HIGH_AND_LOW_BITS > UInt128(0x7FFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF));
}

#[test]
fn should_give_zero_for_an_id_xored_with_itself() {
    assert_eq!(HIGH_AND_LOW_BITS ^ HIGH_AND_LOW_BITS, UInt128::ZERO);
}
