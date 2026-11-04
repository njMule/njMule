// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's ServerDescriptionDatagramsTest, under their names.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use njmule_engine::protocol::ed2k::server_datagrams::{self, Description};
use njmule_engine::protocol::io::{PacketWriter, StringEncoding};
use njmule_engine::protocol::packet::opcodes::server_udp;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::{Tag, TagName, server_tag};

fn tags() -> Vec<Tag> {
    vec![
        Tag::String { name: TagName::id(server_tag::SERVERNAME), value: "Name".into() },
        Tag::String { name: TagName::id(server_tag::DESCRIPTION), value: "Text".into() },
    ]
}

#[test]
fn should_ask_with_a_challenge_whose_low_half_is_no_valid_string_length() {
    let packet = server_datagrams::description_request(server_datagrams::description_challenge(0xABCD_1234));

    assert_eq!((packet.protocol(), packet.opcode()), (protocol::EDONKEY, server_udp::SERVER_DESC_REQ));
    assert_eq!(hex::encode(packet.payload()), "fff03412");
}

#[test]
fn should_read_back_the_challenge_and_the_tags_of_a_description() {
    let sent = Description { challenge: server_datagrams::description_challenge(0x1234), tags: tags() };

    assert_eq!(Description::decode(&sent.encode().unwrap()), Ok(Description { challenge: 0x1234_F0FF, tags: tags() }));
}

#[test]
fn should_refuse_the_old_description_that_carries_no_challenge() {
    let mut out = PacketWriter::new();
    out.string16("Name", StringEncoding::Utf8).unwrap().string16("Text", StringEncoding::Utf8).unwrap();

    assert!(Description::decode(&Packet::written(protocol::EDONKEY, server_udp::SERVER_DESC_RES, out)).is_err());
}
