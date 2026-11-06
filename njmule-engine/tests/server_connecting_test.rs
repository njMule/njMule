// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ServerConnectingTest, under its names: how the session reaches servers, obfuscated or not, which
//! first; scripted servers on the loopback, or on this computer's network address where the list must take them.

#![allow(clippy::unwrap_used, clippy::panic, clippy::indexing_slicing)]

mod scripted_server;

use std::time::Duration;

use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::persist::format::server_met::Server;
use njmule_engine::protocol::ed2k::server_messages;
use njmule_engine::protocol::tag::server_tag;
use njmule_engine::server::server_list::{self, Address, Priority};
use njmule_engine::server::server_session::{self, Notice};
use scripted_server::{HIGH_ID, Harness, ScriptedServer, connected, disconnected};

// Pinged before and known to be plain: no obfuscation is tried first.
fn pinged_plain(server: &ScriptedServer) -> Server {
    Server { tags: vec![server_list::int(server_tag::UDPFLAGS, 0)], ..server.server() }
}

// The servers the list knows, pinged before and plain, the first of high priority.
fn knowing(harness: &mut Harness, known: &[&ScriptedServer]) {
    for server in known {
        assert!(harness.servers.add(pinged_plain(server)));
    }
    harness.servers.set_priority(&Address::new(known[0].ip(), known[0].port()), Priority::High);
}

async fn logged_in(harness: &mut Harness, obfuscated: bool) {
    let notice = harness.until(connected).await;
    assert!(matches!(notice, Notice::Connected { id, .. } if id.client_id == HIGH_ID), "{notice:?}");
    assert_eq!(harness.session.is_obfuscated(), obfuscated);
}

#[tokio::test]
async fn should_try_a_server_never_pinged_obfuscated_once_then_plainly() {
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Requested);

    harness.connect(Some(server.server()));

    logged_in(&mut harness, false).await;
    assert_eq!(server.connections(), 2);
}

#[tokio::test]
async fn should_not_try_obfuscated_again_a_server_already_tried_so() {
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Requested);
    harness.connect(Some(server.server()));
    logged_in(&mut harness, false).await;
    harness.disconnect();

    harness.connect(Some(server.server()));

    logged_in(&mut harness, false).await;
    assert_eq!(server.connections(), 3);
}

#[tokio::test]
async fn should_log_in_obfuscated_to_a_server_never_pinged_that_takes_obfuscation_and_remember_it() {
    let server = ScriptedServer::start_obfuscating(HIGH_ID, server_messages::TCP_OBFUSCATION).await;
    let mut harness = Harness::new(ObfuscationMode::Requested);

    harness.connect(Some(server.server()));

    logged_in(&mut harness, true).await;
    assert_eq!(server.obfuscated_logins(), 1);
    let known = harness.session.server().unwrap();
    assert!(server_list::supports_obfuscation(known));
    assert_eq!(known.obfuscated_tcp_port(), Some(u64::from(server.port())));
}

#[tokio::test]
async fn should_try_a_server_never_pinged_obfuscated_when_obfuscation_is_required() {
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Required);

    harness.connect(Some(server.server()));

    harness.until(disconnected).await;
    assert!(!harness.session.is_connected());
    assert_eq!(server.connections(), 1);
}

#[tokio::test]
async fn should_not_let_a_server_that_does_not_answer_hold_up_the_next_one() {
    let Some(lan) = ScriptedServer::lan_address() else { return };
    let silent = ScriptedServer::start_at(lan, 0, 0).await;
    let answering = ScriptedServer::start_at(lan, HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Requested);
    knowing(&mut harness, &[&silent, &answering]);

    harness.connect(None);

    let notice = harness.until_within(Duration::from_secs(10), connected).await;
    assert!(matches!(notice, Notice::Connected { id, .. } if id.client_id == HIGH_ID));
}

#[tokio::test]
async fn should_replace_a_lost_session_when_the_preferences_say_so() {
    let Some(lan) = ScriptedServer::lan_address() else { return };
    let server = ScriptedServer::start_at(lan, HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Requested);
    harness.keep_connected = true;
    knowing(&mut harness, &[&server]);
    harness.connect(None);
    harness.until(connected).await;

    server.hang_up();

    harness.until(disconnected).await;
    harness.until_within(Duration::from_secs(10), connected).await;
}

#[tokio::test]
async fn should_wait_before_the_next_reconnect_when_a_session_is_lost_again_soon() {
    let Some(lan) = ScriptedServer::lan_address() else { return };
    let server = ScriptedServer::start_at(lan, HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Requested);
    harness.keep_connected = true;
    knowing(&mut harness, &[&server]);
    harness.connect(None);
    harness.until(connected).await;
    server.hang_up();
    harness.until(disconnected).await;
    harness.until_within(Duration::from_secs(10), connected).await;

    server.hang_up();

    harness.until(disconnected).await;
    let retrying = harness.until(|notice| matches!(notice, Notice::Retrying(_))).await;
    assert!(matches!(retrying, Notice::Retrying(wait) if wait == server_session::RETRY_AFTER));
    harness.run_for(Duration::from_secs(2)).await;
    assert!(!harness.session.is_connected());
}

#[tokio::test]
async fn should_try_the_servers_again_later_when_every_one_fails() {
    let Some(lan) = ScriptedServer::lan_address() else { return };
    let server = ScriptedServer::start_at(lan, 0, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Requested);
    harness.keep_connected = true;
    knowing(&mut harness, &[&server]);
    server.close().await;

    harness.connect(None);

    let retrying = harness.until(|notice| matches!(notice, Notice::Retrying(_))).await;
    assert!(matches!(retrying, Notice::Retrying(wait) if wait == server_session::RETRY_AFTER));
    assert!(!harness.session.is_connected());
    assert!(!harness.seen.iter().any(disconnected), "still looking");
}

#[tokio::test]
async fn should_give_up_the_session_at_once_without_a_server_to_try() {
    let mut harness = Harness::new(ObfuscationMode::Requested);
    harness.keep_connected = true;

    harness.connect(None);

    harness.until(disconnected).await;
    assert!(!harness.session.is_connected());
}

#[tokio::test]
async fn should_not_replace_a_lost_session_unless_the_preferences_say_so() {
    let Some(lan) = ScriptedServer::lan_address() else { return };
    let server = ScriptedServer::start_at(lan, HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Requested);
    knowing(&mut harness, &[&server]);
    harness.connect(None);
    harness.until(connected).await;

    server.hang_up();

    harness.until(disconnected).await;
    harness.run_for(Duration::from_secs(2)).await;
    assert!(!harness.session.is_connected());
}

#[tokio::test]
async fn should_try_plainly_at_once_a_server_known_to_be_plain() {
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Requested);

    harness.connect(Some(pinged_plain(&server)));

    logged_in(&mut harness, false).await;
    assert_eq!(server.connections(), 1);
}
