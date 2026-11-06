// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's PeerLinkTest, under its names: the side of a client link each packet goes to.

use njmule_engine::peer::peer_link::{self, Side};
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, protocol};

fn basic(opcode: u8) -> Packet {
    Packet::empty(protocol::EDONKEY, opcode)
}

fn emule(opcode: u8) -> Packet {
    Packet::empty(protocol::EMULE, opcode)
}

fn sides(packets: &[Packet]) -> Vec<Side> {
    packets.iter().map(peer_link::side_of).collect()
}

#[test]
fn should_route_the_answers_of_an_uploader_to_the_download() {
    let answers = [
        basic(client::FILESTATUS),
        basic(client::ACCEPTUPLOADREQ),
        basic(client::SENDINGPART),
        emule(extended::COMPRESSEDPART_I64),
        emule(extended::ANSWERSOURCES2),
    ];

    let sides = sides(&answers);

    assert_eq!(sides, [Side::Download; 5]);
}

#[test]
fn should_route_the_requests_of_a_downloader_to_the_upload() {
    let requests = [
        basic(client::STARTUPLOADREQ),
        basic(client::SETREQFILEID),
        basic(client::CANCELTRANSFER),
        emule(extended::REQUESTPARTS_I64),
        emule(extended::REQUESTSOURCES2),
    ];

    let sides = sides(&requests);

    assert_eq!(sides, [Side::Upload; 5]);
}

// A new hello and chat go to the side that answers them.
#[test]
fn should_route_a_hello_a_chat_or_an_unknown_packet_to_the_upload() {
    let others = [basic(client::HELLO), basic(client::MESSAGE), basic(0xFF)];

    let sides = sides(&others);

    assert_eq!(sides, [Side::Upload; 3]);
}

// Beyond the Java engine's examples: what buddies send each other.

#[test]
fn should_route_what_buddies_send_each_other_to_the_buddy_link() {
    let between_buddies = [
        emule(extended::BUDDYPING),
        emule(extended::BUDDYPONG),
        emule(extended::CALLBACK),
        emule(extended::REASKCALLBACKTCP),
    ];

    let sides = sides(&between_buddies);

    assert_eq!(sides, [Side::Buddy; 4]);
}
