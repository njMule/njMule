// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's LowIdMessagesTest, under their names: callbacks, the UDP check and the buddy in
//! the hello.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use std::net::Ipv4Addr;

use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::ed2k::hello::{Hello, MISC2_DIRECT_UDP_CALLBACK, Ours};
use njmule_engine::protocol::ed2k::low_id_messages::{self, Callback, DirectCallbackRequest, UdpCheckRequest};
use njmule_engine::protocol::packet::opcodes::{client_udp, extended};
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::client_tag;

const BUDDY: UInt128 = UInt128(0x0102_0304_0506_0708_090A_0B0C_0D0E_0F10);
const FILE: UInt128 = UInt128(0xA0A1_A2A3_A4A5_A6A7_A8A9_AAAB_ACAD_AEAF);
const USER: [u8; 16] = [0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF];

fn host(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from(Ipv4Addr::new(a, b, c, d))
}

fn assert_packet(packet: &Packet, protocol: u8, opcode: u8, payload: &str) {
    assert_eq!((packet.protocol(), packet.opcode()), (protocol, opcode));
    assert_eq!(hex::encode(packet.payload()), payload);
}

fn our_hello(misc_options2: u32) -> Hello {
    let ours = Ours { nick: "nick", udp_port: 4672, kad_port: 4672, misc_options1: 0, misc_options2 };
    Hello::of([0xAB; 16], 1, 4662, &ours, 0, 0)
}

#[test]
fn should_name_the_buddy_the_file_and_the_requester_of_a_relayed_callback_in_kad_layout() {
    let sent = Callback { buddy_id: BUDDY, file: FILE, requester_ip: host(1, 2, 3, 4), requester_port: 4662 };

    let packet = sent.encode();

    assert_packet(
        &packet,
        protocol::EMULE,
        extended::CALLBACK,
        "04030201080706050c0b0a09100f0e0da3a2a1a0a7a6a5a4abaaa9a8afaeadac040302013612",
    );
    assert_eq!(Callback::decode(&packet), Ok(sent));
}

#[test]
fn should_give_our_port_hash_and_connect_options_in_a_direct_callback_request() {
    let sent = DirectCallbackRequest { tcp_port: 4662, user_hash: USER, connect_options: 0x03 };

    let packet = sent.encode();

    assert_packet(&packet, protocol::EMULE, client_udp::DIRECTCALLBACKREQ, "361200112233445566778899aabbccddeeff03");
    assert_eq!(DirectCallbackRequest::decode(&packet), Ok(sent));
}

#[test]
fn should_give_both_ports_and_the_key_in_a_udp_check_request() {
    let sent = UdpCheckRequest { internal_port: 4672, external_port: 5000, sender_key: 0x0102_0304 };

    let packet = sent.encode();

    assert_packet(&packet, protocol::EMULE, extended::FWCHECKUDPREQ, "4012881304030201");
    assert_eq!(UdpCheckRequest::decode(&packet), Ok(sent));
}

#[test]
fn should_carry_nothing_in_a_buddy_ping() {
    assert_packet(&low_id_messages::buddy_ping(), protocol::EMULE, extended::BUDDYPING, "");
}

#[test]
fn should_carry_nothing_in_a_buddy_pong() {
    assert_packet(&low_id_messages::buddy_pong(), protocol::EMULE, extended::BUDDYPONG, "");
}

#[test]
fn should_name_the_buddy_right_after_the_udp_ports_in_a_hello() {
    let hello =
        Hello::decode(&our_hello(MISC2_DIRECT_UDP_CALLBACK).with_buddy(host(1, 2, 3, 4), 4672).encode(true).unwrap())
            .unwrap();

    let ids: Vec<Option<u8>> = hello.tags.iter().map(|tag| tag.name().as_id()).collect();
    assert_eq!(
        ids,
        [
            client_tag::NAME,
            client_tag::VERSION,
            client_tag::EMULE_UDPPORTS,
            client_tag::EMULE_BUDDYIP,
            client_tag::EMULE_BUDDYUDP,
            client_tag::EMULE_MISCOPTIONS1,
            client_tag::EMULE_MISCOPTIONS2,
            client_tag::EMULE_VERSION,
            client_tag::MOD_VERSION,
            client_tag::MOD_MISCOPTIONS,
        ]
        .map(Some)
    );
    assert_eq!((hello.buddy_ip(), hello.buddy_udp_port()), (host(1, 2, 3, 4), 4672));
    assert!(hello.supports_direct_callback());
}

#[test]
fn should_name_no_buddy_in_a_hello_without_one() {
    let hello = Hello::decode(&our_hello(0).encode(false).unwrap()).unwrap();

    assert_eq!((hello.buddy_ip(), hello.buddy_udp_port()), (0, 0));
    assert!(!hello.supports_direct_callback());
}
