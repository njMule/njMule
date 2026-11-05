// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's NatTraversalMessagesTest, under their names: rendezvous, forwards,
//! capabilities, QUIC frames and proofs.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use std::net::{Ipv4Addr, SocketAddr};

use njmule_engine::protocol::ed2k::nat_traversal_messages::{
    self, Caps, FRAME_CAPS, FRAME_CAPS_ACK, FRAME_QUIC, FRAME_UTP, Forward, QuicProof, Rendezvous, TRANSPORT_UTP,
};
use njmule_engine::protocol::packet::opcodes::{client_udp, extended};
use njmule_engine::protocol::packet::{Packet, protocol};

const A: [u8; 16] = [0xAA; 16];
const B: [u8; 16] = [0xBB; 16];
const FILE: [u8; 16] = [0xDD; 16];

fn forward(hex: &str) -> Packet {
    Packet::of(protocol::EMULE, extended::REASKCALLBACKTCP, hex::decode(hex).unwrap())
}

fn assert_packet(packet: &Packet, protocol: u8, opcode: u8, payload: &str) {
    assert_eq!((packet.protocol(), packet.opcode()), (protocol, opcode));
    assert_eq!(hex::encode(packet.payload()), payload);
}

#[test]
fn should_write_a_rendezvous_through_the_buddy_as_a_re_ask_with_a_zero_file_hash() {
    let rendezvous = Rendezvous {
        requester: A,
        options: 0xA1,
        file: FILE,
        requester_ip: u32::from(Ipv4Addr::new(198, 51, 100, 20)),
        requester_port: 4672,
        transport: TRANSPORT_UTP,
    };

    let packet = rendezvous.encode_through_buddy(&[0xCC; 16]);

    assert_packet(
        &packet,
        protocol::EMULE,
        client_udp::REASKCALLBACKUDP,
        &format!("{}{}a0{}a1{}c6336414401201", "cc".repeat(16), "00".repeat(16), "aa".repeat(16), "dd".repeat(16)),
    );
}

#[test]
fn should_read_a_rendezvous_passed_on_by_the_buddy_with_the_requesters_address() {
    let packet = forward(&format!("0a0000014012{}a0{}80{}", "00".repeat(16), "aa".repeat(16), "dd".repeat(16)));

    let read = Forward::decode(&packet).unwrap().unwrap();
    let rendezvous = Rendezvous::decode(&read.payload).unwrap();

    assert_eq!(read.requester, "10.0.0.1:4672".parse::<SocketAddr>().unwrap());
    assert_eq!(read.opcode, client_udp::RENDEZVOUS);
    assert_eq!((rendezvous.requester, rendezvous.options, rendezvous.file), (A, 0x80, FILE));
    assert_eq!(rendezvous.requester_ip, 0);
}

#[test]
fn should_read_an_ipv6_requester_in_a_forward() {
    let packet =
        forward(&format!("ffffffff20010db80000000000000000000000014012{}a0{}80", "00".repeat(16), "aa".repeat(16)));

    let read = Forward::decode(&packet).unwrap().unwrap();

    assert_eq!(read.requester, "[2001:db8::1]:4672".parse::<SocketAddr>().unwrap());
}

#[test]
fn should_take_a_forward_with_a_file_hash_for_a_re_ask() {
    assert_eq!(Forward::decode(&forward(&format!("0a0000014012{}0000", "dd".repeat(16)))), Ok(None));
}

#[test]
fn should_write_capabilities_in_fifty_four_bytes_after_their_magic() {
    let packet = Caps { answer: false, options: 0x81, sender: A, expected: B, file: FILE }.encode();

    assert_packet(
        &packet,
        protocol::UDP_RESERVED_2,
        FRAME_CAPS,
        &format!("454151430181{}{}{}", "aa".repeat(16), "bb".repeat(16), "dd".repeat(16)),
    );
}

#[test]
fn should_read_a_capability_answer() {
    let packet = Packet::of(
        protocol::UDP_RESERVED_2,
        FRAME_CAPS_ACK,
        hex::decode(format!("454151430180{}{}{}", "bb".repeat(16), "aa".repeat(16), "00".repeat(16))).unwrap(),
    );

    assert_eq!(Caps::decode(&packet), Ok(Caps { answer: true, options: 0x80, sender: B, expected: A, file: [0; 16] }));
}

#[test]
fn should_carry_a_quic_datagram_in_frame_one() {
    assert_packet(
        &nat_traversal_messages::quic_frame(&[0xC0, 0, 0, 0]),
        protocol::UDP_RESERVED_2,
        FRAME_QUIC,
        "c0000000",
    );
}

#[test]
fn should_write_a_quic_proof_as_its_magic_and_both_hashes() {
    let proof = QuicProof { sender: A, receiver: [0; 16] }.encode();

    assert_eq!(hex::encode(proof), format!("4541514e31{}{}", "aa".repeat(16), "00".repeat(16)));
}

#[test]
fn should_read_a_quic_proof() {
    let proof = hex::decode(format!("4541514e31{}{}", "bb".repeat(16), "aa".repeat(16))).unwrap();

    assert_eq!(QuicProof::decode(&proof), Ok(QuicProof { sender: B, receiver: A }));
}

#[test]
fn should_reject_a_quic_proof_with_another_magic() {
    let proof = hex::decode(format!("4541514e32{}{}", "bb".repeat(16), "aa".repeat(16))).unwrap();

    assert!(QuicProof::decode(&proof).is_err());
}

#[test]
fn should_reject_a_short_quic_proof() {
    let proof = hex::decode(format!("4541514e31{}", "bb".repeat(16))).unwrap();

    assert!(QuicProof::decode(&proof).is_err());
}

// A forward of garbage is read or refused as malformed: never a panic.
proptest::proptest! {
    #[test]
    fn should_only_fail_with_decode_exception_on_a_garbage_forward(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=80)) {
        let _ = njmule_engine::protocol::ed2k::nat_traversal_messages::Forward::decode(&Packet::of(protocol::EMULE, 0x01, data));
    }
}

#[test]
fn should_open_a_hole_with_an_empty_datagram() {
    assert_packet(&nat_traversal_messages::hole_punch(), protocol::EMULE, client_udp::HOLEPUNCH, "");
}

#[test]
fn should_frame_a_utp_packet_as_it_is() {
    assert_packet(&nat_traversal_messages::utp_frame(&[1, 2, 3]), protocol::UDP_RESERVED_2, FRAME_UTP, "010203");
}

#[test]
fn should_refuse_a_capability_frame_of_another_version() {
    let mut payload = Caps { answer: false, options: 0, sender: A, expected: B, file: FILE }.encode().into_payload();
    payload[4] = 2;

    assert!(Caps::decode(&Packet::of(protocol::UDP_RESERVED_2, FRAME_CAPS, payload)).is_err());
}
