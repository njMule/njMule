// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's SourceExchangeTest, under their names: answers of every version and the
//! extended form, and requests.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::net::{Ipv4Addr, Ipv6Addr};

use njmule_engine::protocol::ed2k::server_messages::LOW_ID_LIMIT;
use njmule_engine::protocol::ed2k::source_exchange::{
    self, Answer, EXTENDED_VERSION, MAX_SOURCES, Request, Source, VERSION,
};
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::{Packet, protocol};

const FILE: [u8; 16] = [0xAB; 16];
const USER: [u8; 16] = [0xCD; 16];

fn ip(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from(Ipv4Addr::new(a, b, c, d))
}

fn ipv6() -> Ipv6Addr {
    "2001:db8::1".parse().unwrap()
}

fn high_id_source(crypt_options: u8) -> Source {
    Source {
        ip: ip(1, 2, 3, 4),
        low_id: 0,
        port: 4662,
        server_ip: ip(9, 9, 9, 9),
        server_port: 4661,
        user_hash: Some(USER),
        crypt_options,
        ipv6: None,
    }
}

fn low_id_source() -> Source {
    Source {
        ip: 0,
        low_id: 1234,
        port: 4663,
        server_ip: 0,
        server_port: 0,
        user_hash: Some(USER),
        crypt_options: 0,
        ipv6: None,
    }
}

fn answer(version: u8, sources: Vec<Source>) -> Answer {
    Answer { version, file: FILE, sources }
}

fn payload(version: u8, sources: Vec<Source>) -> String {
    hex::encode(answer(version, sources).encode().unwrap().payload())
}

fn received(hex: &str) -> Packet {
    Packet::of(protocol::EMULE, 0x84, hex::decode(hex).unwrap())
}

#[test]
fn should_write_ids_in_ed2k_order_up_to_version_two() {
    let written = payload(2, vec![high_id_source(3)]);

    assert!(written.starts_with(&format!("02{}0100010203043612", "ab".repeat(16))), "{written}");
}

#[test]
fn should_write_ids_in_host_order_from_version_three() {
    let written = payload(4, vec![high_id_source(3)]);

    assert!(written.starts_with(&format!("04{}0100040302013612", "ab".repeat(16))), "{written}");
    assert!(written.ends_with(&format!("{}03", "cd".repeat(16))), "{written}");
}

#[test]
fn should_round_trip_an_answer_in_every_version() {
    for version in 1..=VERSION {
        let sent = answer(version, vec![high_id_source(1), low_id_source()]);

        let decoded = Answer::decode(&sent.encode().unwrap()).unwrap();

        assert_eq!(decoded.version, version);
        assert_eq!((decoded.sources[0].ip, decoded.sources[0].port), (ip(1, 2, 3, 4), 4662), "version {version}");
        assert_eq!(decoded.sources[0].server_ip, ip(9, 9, 9, 9), "version {version}");
        assert!(decoded.sources[1].is_low_id(), "version {version}");
        assert_eq!(decoded.sources[1].low_id, 1234, "version {version}");
        assert_eq!(decoded.sources[0].user_hash, (version >= 2).then_some(USER), "version {version}");
        assert_eq!(decoded.sources[0].crypt_options, u8::from(version >= 4), "version {version}");
    }
}

#[test]
fn should_read_a_low_id_with_its_bytes_swapped_as_a_low_id_not_an_address_ending_in_zero() {
    let ab = "ab".repeat(16);
    let cd = "cd".repeat(16);

    let decoded =
        Answer::decode(&received(&["03", &ab, "0100", "000004d2", "3612", "00000000", "0000", &cd].concat())).unwrap();

    assert!(decoded.sources[0].is_low_id());
    assert_eq!(decoded.sources[0].low_id, 1234);
}

#[test]
fn should_put_the_version_and_options_before_the_file_in_a_request() {
    assert_eq!(hex::encode(source_exchange::request(&FILE).payload()), format!("040000{}", "ab".repeat(16)));
}

#[test]
fn should_decode_a_source_request() {
    assert_eq!(Request::decode(&source_exchange::request(&FILE)), Ok(Request { version: 4, file: FILE }));
}

#[test]
fn should_carry_the_most_sources_its_reader_takes() {
    let sent = answer(4, vec![low_id_source(); MAX_SOURCES]);

    assert_eq!(Answer::decode(&sent.encode().unwrap()).map(|a| a.sources.len()), Ok(MAX_SOURCES));
}

#[test]
fn should_refuse_to_carry_more_sources_than_its_reader_takes() {
    assert!(answer(4, vec![low_id_source(); MAX_SOURCES + 1]).encode().is_err());
}

#[test]
fn should_refuse_an_answer_whose_size_does_not_match() {
    assert!(Answer::decode(&received(&format!("04{}0200{}", "ab".repeat(16), "00".repeat(29)))).is_err());
}

#[test]
fn should_refuse_an_answer_of_an_unknown_version() {
    assert!(Answer::decode(&received(&format!("07{}0000", "ab".repeat(16)))).is_err());
}

#[test]
fn should_read_every_source_as_an_address_or_a_low_id() {
    for version in 1..=VERSION {
        for id in [0u32, 1, 0x00FF_FFFF, 0x0100_0000, 0x0100_0001, 0x1234_5600, 0xFFFF_FFFF, 0x8000_0000, 0xAB00_0000] {
            let mut out = PacketWriter::new();
            out.u8(version).hash16(&FILE).u16(1).bytes(&id.to_le_bytes()).u16(4662).u32(0).u16(0);
            if version >= 2 {
                out.hash16(&USER);
            }
            if version >= 4 {
                out.u8(0);
            }

            let source = Answer::decode(&Packet::written(protocol::EMULE, 0x84, out)).unwrap().sources[0];

            if source.is_low_id() {
                assert!(source.low_id < LOW_ID_LIMIT, "id {id:#x} version {version}");
            } else {
                assert_ne!(source.ip, 0, "id {id:#x} version {version}");
                if version >= 3 {
                    assert_ne!(source.ip & 0xFF, 0, "no address ends in .0: id {id:#x} version {version}");
                }
            }
        }
    }
}

#[test]
fn should_read_an_extended_answer_and_skip_the_tags_it_does_not_know() {
    let decoded = Answer::decode_extended(&received(
        &[
            &format!("01{}0100010203043612", "ab".repeat(16)),
            "04",
            // An address tag of a LowID, which it does not use, then the server and the IPv6 address.
            "83b005060708",
            "83ba09090909",
            "88bb3512",
            "81ae20010db8000000000000000000000001",
        ]
        .concat(),
    ))
    .unwrap();

    let source = decoded.sources[0];
    assert_eq!((source.ip, source.port), (ip(1, 2, 3, 4), 4662));
    assert_eq!(source.server_ip, ip(9, 9, 9, 9));
    assert_eq!(source.ipv6, Some(ipv6()));
    assert_eq!(source.user_hash, None);
}

#[test]
fn should_round_trip_an_extended_answer() {
    let with_ipv6 = Source { user_hash: None, crypt_options: 0, ipv6: Some(ipv6()), ..high_id_source(0) };
    let sent = answer(EXTENDED_VERSION, vec![with_ipv6, Source::ipv6_only(ipv6(), 4664, None), low_id_source()]);

    let decoded = Answer::decode_extended(&sent.encode_extended().unwrap()).unwrap();

    assert_eq!(decoded.version, EXTENDED_VERSION);
    assert_eq!((decoded.sources[0].ip, decoded.sources[0].port), (ip(1, 2, 3, 4), 4662));
    assert_eq!(decoded.sources[0].server_ip, ip(9, 9, 9, 9));
    assert_eq!(decoded.sources[0].ipv6, Some(ipv6()));
    let alone = decoded.sources[1];
    assert!(alone.is_ipv6_only() && !alone.is_low_id());
    assert_eq!((alone.ipv6, alone.port), (Some(ipv6()), 4664));
    assert!(decoded.sources[2].is_low_id());
    assert_eq!(decoded.sources[2].low_id, 1234);
}

#[test]
fn should_leave_sources_at_an_ipv6_address_alone_out_of_an_answer_of_the_other_form() {
    let sent = answer(4, vec![high_id_source(1), Source::ipv6_only(ipv6(), 4664, None)]);

    assert_eq!(Answer::decode(&sent.encode().unwrap()).map(|a| a.sources.len()), Ok(1));
}

#[test]
fn should_ask_for_the_extended_form_with_version_one() {
    let packet = source_exchange::request_in_version(&FILE, EXTENDED_VERSION);

    assert_eq!(hex::encode(packet.payload()), format!("010000{}", "ab".repeat(16)));
}

// Garbage is read or refused as malformed: never a panic.
proptest::proptest! {
    #[test]
    fn should_only_fail_with_decode_exception_on_garbage_read_as_an_extended_answer(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=200)) {
        let _ = njmule_engine::protocol::ed2k::source_exchange::Answer::decode_extended(&Packet::of(protocol::EMULE, 0x84, data));
    }

    #[test]
    fn should_only_fail_with_decode_exception_on_garbage_read_as_an_answer(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=200)) {
        let _ = njmule_engine::protocol::ed2k::source_exchange::Answer::decode(&Packet::of(protocol::EMULE, 0x84, data));
    }

    #[test]
    fn should_only_fail_with_decode_exception_on_garbage_read_as_a_request(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=200)) {
        let _ = njmule_engine::protocol::ed2k::source_exchange::Request::decode(&Packet::of(protocol::EMULE, 0x83, data));
    }
}
