// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's KadLowIdMessagesTest, under their names: buddy requests and offers, callback
//! requests and the UDP test datagram.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::kad::kad_messages::{BuddyOffer, CallbackRequest, FindBuddy, FirewallUdp};
use njmule_engine::protocol::packet::opcodes::kad;
use njmule_engine::protocol::packet::{Packet, protocol};

const ID: UInt128 = UInt128(0x0102_0304_0506_0708_090A_0B0C_0D0E_0F10);
const HASH: UInt128 = UInt128(0xA0A1_A2A3_A4A5_A6A7_A8A9_AAAB_ACAD_AEAF);
const ID_WIRE: &str = "04030201080706050c0b0a09100f0e0d";
const HASH_WIRE: &str = "a3a2a1a0a7a6a5a4abaaa9a8afaeadac";

fn assert_packet(packet: &Packet, opcode: u8, payload: &str) {
    assert_eq!((packet.protocol(), packet.opcode()), (protocol::KAD, opcode));
    assert_eq!(hex::encode(packet.payload()), payload);
}

#[test]
fn should_give_the_buddy_id_the_user_hash_and_the_port_in_a_buddy_request() {
    let sent = FindBuddy { buddy_id: ID, user_hash: HASH, tcp_port: 4662, connect_options: None };

    let packet = sent.encode();

    assert_packet(&packet, kad::FINDBUDDY_REQ, &format!("{ID_WIRE}{HASH_WIRE}3612"));
    assert_eq!(FindBuddy::decode(&packet), Ok(sent));
}

#[test]
fn should_carry_the_connect_options_in_a_buddy_offer_when_given() {
    let sent = BuddyOffer { buddy_id: ID, user_hash: HASH, tcp_port: 4662, connect_options: Some(3) };

    let packet = sent.encode();

    assert_packet(&packet, kad::FINDBUDDY_RES, &format!("{ID_WIRE}{HASH_WIRE}361203"));
    assert_eq!(BuddyOffer::decode(&packet), Ok(sent));
}

#[test]
fn should_leave_the_connect_options_out_of_a_buddy_offer_of_an_older_node() {
    let sent = BuddyOffer { buddy_id: ID, user_hash: HASH, tcp_port: 4662, connect_options: None };

    let packet = sent.encode();

    assert_packet(&packet, kad::FINDBUDDY_RES, &format!("{ID_WIRE}{HASH_WIRE}3612"));
    assert_eq!(BuddyOffer::decode(&packet), Ok(sent));
}

#[test]
fn should_give_the_buddy_id_the_file_and_our_port_in_a_callback_request() {
    let sent = CallbackRequest { buddy_id: ID, file: HASH, tcp_port: 4662 };

    let packet = sent.encode();

    assert_packet(&packet, kad::CALLBACK_REQ, &format!("{ID_WIRE}{HASH_WIRE}3612"));
    assert_eq!(CallbackRequest::decode(&packet), Ok(sent));
}

#[test]
fn should_give_the_error_and_the_port_in_the_udp_test_datagram() {
    let sent = FirewallUdp { error: 1, port: 4672 };

    let packet = sent.encode();

    assert_packet(&packet, kad::FIREWALLUDP, "014012");
    assert_eq!(FirewallUdp::decode(&packet), Ok(sent));
}

#[test]
fn should_refuse_a_callback_request_too_short() {
    assert!(CallbackRequest::decode(&Packet::of(protocol::KAD, kad::CALLBACK_REQ, vec![0; 33])).is_err());
}

#[test]
fn should_refuse_a_buddy_request_too_short() {
    assert!(FindBuddy::decode(&Packet::of(protocol::KAD, kad::FINDBUDDY_REQ, vec![0; 33])).is_err());
}
