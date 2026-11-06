// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ServerAnswersTest, under its names: what a logged-in session makes of the server's answers; a
//! scripted server on the loopback.

#![allow(clippy::unwrap_used)]

mod scripted_server;

use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::persist::format::server_met::Server;
use njmule_engine::protocol::ed2k::server_messages;
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::opcodes::server;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::server_tag;
use njmule_engine::server::server_list;
use njmule_engine::server::server_session::Notice;
use scripted_server::{HIGH_ID, Harness, ScriptedServer, connected, disconnected};

// A session logged in to a server known to be plain: no obfuscation is tried first.
async fn logged_in() -> (ScriptedServer, Harness) {
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(Some(Server { tags: vec![server_list::int(server_tag::UDPFLAGS, 0)], ..server.server() }));
    harness.until(connected).await;
    (server, harness)
}

#[tokio::test]
async fn should_still_answer_the_search_when_its_answer_cannot_be_read() {
    let (server, mut harness) = logged_in().await;
    // One result announcing five tags, and one byte for them.
    let mut answer = PacketWriter::new();
    answer.u32(1).bytes(&[0; 16 + 4 + 2]).u32(5).u8(0x02);

    server.send(&[Packet::written(protocol::EDONKEY, server::SEARCHRESULT, answer)]).await;

    let results = harness.until(|notice| matches!(notice, Notice::SearchResults { .. })).await;
    assert!(matches!(results, Notice::SearchResults { results, .. } if results.files.is_empty()));
}

#[tokio::test]
async fn should_drop_a_packet_that_does_not_inflate_and_go_on_with_the_session() {
    let (server, mut harness) = logged_in().await;

    server
        .send(&[
            Packet::of(protocol::PACKED, server::SERVERMESSAGE, [1, 2, 3, 4]),
            server_messages::message("still here").unwrap(),
        ])
        .await;

    let message = harness.until(|notice| matches!(notice, Notice::Message { .. })).await;
    assert!(matches!(message, Notice::Message { line, .. } if line == "still here"));
    assert!(harness.session.is_connected());
    assert!(!harness.seen.iter().any(disconnected));
}
