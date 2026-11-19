// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's UtpPacketTest, under its names.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::protocol::io::DecodeError;
use njmule_engine::utp::utp_packet::{Type, UtpPacket};

#[test]
fn should_write_the_header_in_network_order() {
    let mut test = Test::default();
    test.given_the_packet(packet(Type::Syn, 0x1234, 0x0102_0304, 0x0506_0708, 0x0010_0000, 1, 0, None, &[]));

    test.when_it_is_encoded();

    test.then_the_bytes_are(&format!("4100{}{}{}{}{}{}", "1234", "01020304", "05060708", "00100000", "0001", "0000"));
}

#[test]
fn should_round_trip_a_data_packet_with_a_selective_ack() {
    let mut test = Test::default();
    test.given_the_packet(packet(
        Type::Data,
        7,
        u32::MAX,
        42,
        65_536,
        0xFFFF,
        0x8000,
        Some(&[5, 0, 0, 0x80]),
        &[9, 8, 7],
    ));
    test.given_it_is_encoded();

    test.when_the_bytes_are_decoded();

    test.then_the_decoded_packet_is_the_same();
}

#[test]
fn should_skip_an_extension_it_does_not_know() {
    let mut test = Test::default();
    // An unknown extension, then the selective ack, then the payload.
    test.given_the_bytes(&format!("{}{}{}", "0102000100000000000000000000100000020001", "0102abcd", "000401000000ff"));

    test.when_the_bytes_are_decoded();

    test.then_no_exception_occurred();
    test.then_the_decoded_selective_ack_is("01000000");
    test.then_the_decoded_payload_is("ff");
}

#[test]
fn should_refuse_another_version() {
    let mut test = Test::default();
    test.given_the_bytes("0200000100000000000000000000100000020001");

    test.when_the_bytes_are_decoded();

    test.then_exception_occurred();
}

proptest::proptest! {
    #[test]
    fn should_only_fail_with_decode_exception_on_garbage(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=100)) {
        let mut test = Test::default();
        test.given_the_bytes(&hex::encode(data));

        test.when_the_bytes_are_decoded();

        test.then_any_failure_is_a_decode_exception();
    }
}

#[derive(Default)]
struct Test {
    packet: Option<UtpPacket>,
    encoded: Vec<u8>,
    decoded: Option<Result<UtpPacket, DecodeError>>,
}

impl Test {
    fn given_the_packet(&mut self, packet: UtpPacket) {
        self.packet = Some(packet);
    }

    fn given_it_is_encoded(&mut self) {
        self.when_it_is_encoded();
    }

    fn given_the_bytes(&mut self, hex: &str) {
        self.encoded = hex::decode(hex).unwrap();
    }

    fn when_it_is_encoded(&mut self) {
        self.encoded = self.packet.as_ref().unwrap().encode();
    }

    fn when_the_bytes_are_decoded(&mut self) {
        self.decoded = Some(UtpPacket::decode(&self.encoded));
    }

    fn then_the_bytes_are(&self, hex: &str) {
        assert_eq!(hex::encode(&self.encoded), hex);
    }

    fn then_the_decoded_packet_is_the_same(&self) {
        assert_eq!(self.decoded.as_ref().unwrap().as_ref().unwrap(), self.packet.as_ref().unwrap());
    }

    fn then_the_decoded_selective_ack_is(&self, hex: &str) {
        let decoded = self.decoded.as_ref().unwrap().as_ref().unwrap();
        assert_eq!(decoded.selective_ack, Some(hex::decode(hex).unwrap()));
    }

    fn then_the_decoded_payload_is(&self, hex: &str) {
        assert_eq!(self.decoded.as_ref().unwrap().as_ref().unwrap().payload, hex::decode(hex).unwrap());
    }

    fn then_no_exception_occurred(&self) {
        assert!(self.decoded.as_ref().unwrap().is_ok());
    }

    fn then_exception_occurred(&self) {
        assert!(self.decoded.as_ref().unwrap().is_err());
    }

    // Decoding returns, whatever the bytes: a failure can only be a decode error.
    fn then_any_failure_is_a_decode_exception(&self) {
        assert!(self.decoded.is_some());
    }
}

#[allow(clippy::too_many_arguments)]
fn packet(
    kind: Type,
    connection_id: u16,
    timestamp: u32,
    timestamp_difference: u32,
    window: u32,
    seq: u16,
    ack: u16,
    selective_ack: Option<&[u8]>,
    payload: &[u8],
) -> UtpPacket {
    UtpPacket {
        kind,
        connection_id,
        timestamp,
        timestamp_difference,
        window,
        seq,
        ack,
        selective_ack: selective_ack.map(<[u8]>::to_vec),
        payload: payload.to_vec(),
    }
}
