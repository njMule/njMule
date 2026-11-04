// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's ClientReasksTest, under their names: pings, answers, pings through a buddy and
//! relayed pings, by UDP version.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use std::net::Ipv4Addr;

use njmule_engine::protocol::ed2k::client_messages::PartStatus;
use njmule_engine::protocol::ed2k::client_reasks::{Ack, Ping, Relayed, ThroughBuddy};
use njmule_engine::protocol::packet::opcodes::{client_udp, extended};
use njmule_engine::protocol::packet::{Packet, protocol};

const FILE: [u8; 16] = [0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF];
const FILE_HEX: &str = "00112233445566778899aabbccddeeff";
const BUDDY_ID: [u8; 16] =
    [0xFF, 0xEE, 0xDD, 0xCC, 0xBB, 0xAA, 0x99, 0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0x00];
const BUDDY_ID_HEX: &str = "ffeeddccbbaa99887766554433221100";

fn first_and_third() -> PartStatus {
    PartStatus { part_count: 3, parts: vec![true, false, true] }
}

fn ping(complete_sources: u32) -> Ping {
    Ping { file: FILE, parts: first_and_third(), complete_sources }
}

fn assert_packet(packet: &Packet, opcode: u8, payload: &str) {
    assert_eq!((packet.protocol(), packet.opcode()), (protocol::EMULE, opcode));
    assert_eq!(hex::encode(packet.payload()), payload);
}

fn host(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from(Ipv4Addr::new(a, b, c, d))
}

#[test]
fn should_give_the_file_our_parts_and_the_complete_sources_in_a_ping_of_version4() {
    assert_packet(&ping(5).encode(4).unwrap(), client_udp::REASKFILEPING, &format!("{FILE_HEX}0300050500"));
}

#[test]
fn should_give_only_the_file_in_a_ping_of_version2() {
    assert_packet(&ping(5).encode(2).unwrap(), client_udp::REASKFILEPING, FILE_HEX);
}

#[test]
fn should_read_back_a_ping_of_version4() {
    assert_eq!(Ping::decode(&ping(5).encode(4).unwrap(), 4), Ok(ping(5)));
}

#[test]
fn should_give_the_uploader_parts_and_the_rank_in_an_answer_of_version4() {
    let answer = Ack { parts: Some(PartStatus::COMPLETE), rank: 12 };

    assert_packet(&answer.encode(4).unwrap(), client_udp::REASKACK, "00000c00");
}

#[test]
fn should_read_the_rank_of_an_answer_of_version3() {
    let sent = Ack { parts: Some(first_and_third()), rank: 300 };

    assert_eq!(Ack::decode(&sent.encode(3).unwrap(), 3), Ok(Ack { parts: None, rank: 300 }));
}

#[test]
fn should_put_the_buddy_id_before_a_ping_sent_through_the_buddy() {
    let packet = ping(2).encode_through_buddy(&BUDDY_ID, 3).unwrap();

    assert_packet(&packet, client_udp::REASKCALLBACKUDP, &format!("{BUDDY_ID_HEX}{FILE_HEX}0200"));
}

#[test]
fn should_read_the_buddy_id_and_the_ping_of_a_ping_through_the_buddy() {
    let read = ThroughBuddy::decode(&ping(2).encode_through_buddy(&BUDDY_ID, 3).unwrap()).unwrap();

    assert_eq!(read.buddy_id, BUDDY_ID);
    assert_eq!(read.ping, ping(2).encode(3).unwrap());
}

#[test]
fn should_refuse_a_ping_through_the_buddy_without_its_file() {
    let packet = Packet::of(
        protocol::EMULE,
        client_udp::REASKCALLBACKUDP,
        hex::decode(format!("{BUDDY_ID_HEX}0011223344556677")).unwrap(),
    );

    assert!(ThroughBuddy::decode(&packet).is_err());
}

#[test]
fn should_give_the_requester_address_before_the_relayed_ping() {
    let packet =
        Relayed { requester_ip: host(1, 2, 3, 4), requester_port: 4672, ping: ping(2).encode(4).unwrap() }.encode();

    assert_packet(&packet, extended::REASKCALLBACKTCP, &format!("010203044012{FILE_HEX}0300050200"));
}

#[test]
fn should_read_back_a_relayed_ping() {
    let sent = Relayed { requester_ip: host(1, 2, 3, 4), requester_port: 4672, ping: ping(2).encode(4).unwrap() };

    assert_eq!(Relayed::decode(&sent.encode()), Ok(sent));
}
