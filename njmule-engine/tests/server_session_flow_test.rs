// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ServerSessionFlowTest, under its names: a session through its connection attempts and with a
//! server a test scripts; loopback only.

#![allow(clippy::unwrap_used, clippy::panic)]

use crate::scripted_server;

use std::net::Ipv4Addr;
use std::time::Duration;

use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::persist::format::server_met::Server;
use njmule_engine::protocol::ed2k::server_datagrams;
use njmule_engine::protocol::ed2k::server_messages::{self, Endpoint, IdChange, ServerIdent, ServerStatus};
use njmule_engine::protocol::packet::opcodes::server;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::server_tag;
use njmule_engine::server::server_list;
use njmule_engine::server::server_session::{self, Notice};
use njmule_engine::util::ipv4;
use scripted_server::{HIGH_ID, Harness, ScriptedServer, WAIT, connected, disconnected};
use tokio::io::AsyncReadExt;
use tokio::net::TcpListener;

const OTHER_ID: u32 = 0x0807_0605;
const NO_SERVER: &str = "no server accepted the connection";

// A server known by a host name only.
fn named(host: &str, port: u16) -> Server {
    Server { ip: 0, port, tags: vec![server_list::string(server_tag::DYNIP, host)] }
}

fn loopback(port: u16) -> Server {
    Server { ip: u32::from(Ipv4Addr::LOCALHOST), port, tags: Vec::new() }
}

// A server that announced it takes obfuscation on `port`.
fn announcing_obfuscation(server: Server, port: u64) -> Server {
    let mut tags = server.tags.clone();
    tags.push(server_list::int(server_tag::UDPFLAGS, u64::from(server_datagrams::UDP_TCP_OBFUSCATION)));
    tags.push(server_list::int(server_tag::TCPPORTOBFUSCATION, port));
    Server { tags, ..server }
}

async fn gives_up_because(harness: &mut Harness, reason: &str) {
    let notice = harness.until(disconnected).await;
    assert!(matches!(&notice, Notice::Disconnected { reason: told, .. } if told == reason), "{notice:?}");
}

fn dialed(harness: &Harness) -> bool {
    harness.seen.iter().any(|notice| matches!(notice, Notice::Connecting(_)))
}

// Two servers known by host names that do not work, the first static, with static servers only and obfuscation
// required.
fn unusable_servers(harness: &mut Harness) {
    harness.preferences.static_servers_only = true;
    for server in [named("[bad", 4661), named("[bad2", 4661)] {
        harness.servers.add(server);
    }
    harness.servers.set_static(&server_list::address_of(&named("[bad", 4661)), true);
}

async fn logged_in(harness: &mut Harness, target: Server) {
    harness.connect(Some(target));
    let notice = harness.until(connected).await;
    assert!(matches!(notice, Notice::Connected { id, .. } if id.client_id == HIGH_ID), "{notice:?}");
}

async fn server_list_asked(harness: &mut Harness, server: &ScriptedServer) {
    assert!(!harness.session.is_obfuscated());
    assert!(harness.session.send(server_messages::get_server_list()));
    assert!(server.next(server::GETSERVERLIST, WAIT).await.is_some());
}

#[tokio::test]
async fn should_try_servers_that_cannot_be_reached_one_after_the_other() {
    let mut harness = Harness::new(ObfuscationMode::Requested);
    // Neither is looked up: a malformed address and one with no IPv4 form.
    for server in [named("[bad", 4661), named("::1", 4662)] {
        harness.servers.add(server);
    }

    harness.connect(None);

    gives_up_because(&mut harness, NO_SERVER).await;
    assert!(harness.servers.all().iter().all(|server| server.failed_count() == Some(1)), "each failure counted");
    assert!(!dialed(&harness));
}

#[tokio::test]
async fn should_skip_servers_that_cannot_be_used_and_try_only_static_ones_when_asked() {
    let mut harness = Harness::new(ObfuscationMode::Required);
    unusable_servers(&mut harness);

    harness.connect(None);

    gives_up_because(&mut harness, NO_SERVER).await;
    assert!(harness.servers.all().iter().all(|server| server.failed_count().is_none()));
}

#[tokio::test]
async fn should_not_dial_a_server_without_a_port() {
    let mut harness = Harness::new(ObfuscationMode::Required);
    unusable_servers(&mut harness);
    harness.connect(None);
    gives_up_because(&mut harness, NO_SERVER).await;

    harness.connect(Some(loopback(0)));

    gives_up_because(&mut harness, NO_SERVER).await;
    assert!(!dialed(&harness), "no port to connect to");
}

#[tokio::test]
async fn should_fail_to_reach_a_server_without_an_address() {
    let mut harness = Harness::new(ObfuscationMode::Requested);

    harness.connect(Some(Server { ip: ipv4::of(0, 0, 0, 5), port: 4661, tags: Vec::new() }));

    gives_up_because(&mut harness, "no address to connect to").await;
}

#[tokio::test]
async fn should_fail_to_reach_a_server_that_refuses() {
    let closed = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let port = closed.local_addr().unwrap().port();
    drop(closed);
    let mut harness = Harness::new(ObfuscationMode::Requested);

    harness.connect(Some(loopback(port)));

    let dialing = harness.until(|notice| matches!(notice, Notice::Connecting(_))).await;
    assert!(matches!(dialing, Notice::Connecting(server) if server.ip == u32::from(Ipv4Addr::LOCALHOST)));
    harness.until(disconnected).await;
    assert!(!harness.session.is_connected());
}

#[tokio::test]
async fn should_dial_a_server_that_must_obfuscate_on_its_obfuscated_port() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let mut harness = Harness::new(ObfuscationMode::Required);

    harness.connect(Some(announcing_obfuscation(loopback(9), u64::from(port))));

    let first = harness
        .during(async {
            let (mut stream, _) = tokio::time::timeout(WAIT, listener.accept()).await.unwrap().unwrap();
            let mut first = [0u8; 1];
            stream.read_exact(&mut first).await.unwrap();
            first[0]
        })
        .await;
    assert!(!protocol::is_tcp(first), "not a plain frame");
    harness.until(disconnected).await;
}

#[tokio::test]
async fn should_take_what_the_server_says_and_drop_what_it_cannot_say() {
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Requested);
    logged_in(&mut harness, server.server()).await;
    server_list_asked(&mut harness, &server).await;

    server
        .send(&[
            Packet::of(protocol::EDONKEY, 0xEE, [0]),
            Packet::empty(protocol::EDONKEY, server::REJECT),
            Packet::of(protocol::EDONKEY, server::LOGINREQUEST, [0]),
            Packet::of(protocol::EDONKEY, server::CALLBACKREQUESTED, [0, 0]),
            server_messages::message("first\n \nsecond").unwrap(),
            server_messages::server_list(&[Endpoint { ip: ipv4::of(198, 51, 100, 1), port: 4661 }]).unwrap(),
            ServerStatus { users: 7, files: 8 }.encode(),
        ])
        .await;

    let message = |line: &'static str| move |notice: &Notice| matches!(notice, Notice::Message { line: told, .. } if told == line);
    harness.until(message("first")).await;
    harness.until(message("second")).await;
    let listed = harness.until(|notice| matches!(notice, Notice::ServerList { .. })).await;
    assert!(matches!(listed, Notice::ServerList { servers, .. } if servers.len() == 1));
    let status = harness.until(|notice| matches!(notice, Notice::Updated(server) if server.users().is_some())).await;
    assert!(matches!(status, Notice::Updated(server) if (server.users(), server.files()) == (Some(7), Some(8))));
    assert!(
        !harness.seen.iter().any(|notice| matches!(notice, Notice::Message { line, .. } if line.trim().is_empty())),
        "the blank line is left out"
    );
}

#[tokio::test]
async fn should_end_the_session_when_the_server_hangs_up() {
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Requested);
    logged_in(&mut harness, server.server()).await;
    server_list_asked(&mut harness, &server).await;

    server.hang_up();

    harness.until(disconnected).await;
    assert!(harness.session.server().is_none());
    assert!(!harness.session.send(server_messages::get_server_list()), "no session");
}

#[tokio::test]
async fn should_dial_a_server_that_can_obfuscate_plainly_when_obfuscation_is_only_supported() {
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Supported);

    logged_in(&mut harness, announcing_obfuscation(server.server(), 9)).await;
}

#[tokio::test]
async fn should_take_a_new_id_while_logged_in() {
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Supported);
    logged_in(&mut harness, announcing_obfuscation(server.server(), 9)).await;
    let identity = ServerIdent { hash: [2; 16], ip: server.ip(), port: server.port(), name: None, description: None };

    server
        .send(&[
            IdChange { client_id: OTHER_ID, server_flags: 0, reported_ip: 0 }.encode(),
            identity.encode().unwrap(),
            ServerStatus { users: 3, files: 4 }.encode(),
        ])
        .await;

    let status = harness.until(|notice| matches!(notice, Notice::Updated(server) if server.users() == Some(3))).await;
    assert!(matches!(status, Notice::Updated(server) if server.files() == Some(4)));
    assert_eq!(harness.session.id().map(|id| id.client_id), Some(OTHER_ID), "a new id while logged in");
    harness.run_for(Duration::from_millis(100)).await;
    assert_eq!(harness.seen.iter().filter(|notice| connected(notice)).count(), 1, "logged in once");
}

#[test]
fn should_tell_in_the_capabilities_how_much_obfuscation_we_want() {
    let plain = server_messages::CAP_ZLIB
        | server_messages::CAP_NEWTAGS
        | server_messages::CAP_UNICODE
        | server_messages::CAP_LARGEFILES;
    let supports = server_messages::CAP_SUPPORTS_CRYPT;
    let requests = server_messages::CAP_REQUESTS_CRYPT;
    let requires = server_messages::CAP_REQUIRES_CRYPT;

    assert_eq!(server_session::capabilities(ObfuscationMode::Disabled), plain);
    assert_eq!(server_session::capabilities(ObfuscationMode::Supported), plain | supports);
    assert_eq!(server_session::capabilities(ObfuscationMode::Requested), plain | supports | requests);
    assert_eq!(server_session::capabilities(ObfuscationMode::Required), plain | supports | requests | requires);
    assert_eq!(server_session::capabilities(ObfuscationMode::Requested), 0x719);
}

// Beyond the Java engine's examples: the branches its other tests reach.

#[tokio::test]
async fn should_log_in_to_a_server_known_by_a_host_name_at_the_address_it_has() {
    let Some(lan) = ScriptedServer::lan_address() else { return };
    let server = ScriptedServer::start_at(lan, HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    let by_name = named(&lan.to_string(), server.port());
    harness.servers.add(by_name.clone());

    logged_in(&mut harness, by_name.clone()).await;

    let known = harness.servers.get(&server_list::address_of(&by_name)).unwrap();
    assert_eq!(known.ip, server.ip(), "the address it has now");
}

#[tokio::test]
async fn should_look_again_once_the_wait_after_every_failure_is_over() {
    let Some(lan) = ScriptedServer::lan_address() else { return };
    let server = ScriptedServer::start_at(lan, HIGH_ID, 0).await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.keep_connected = true;
    harness.servers.add(server.server());
    server.close().await;
    harness.connect(None);
    harness.until(|notice| matches!(notice, Notice::Retrying(_))).await;
    let again = ScriptedServer::start_at(lan, HIGH_ID, 0).await;
    harness.servers.add(again.server());

    harness.with(|session, ctx| session.handle(server_session::Outcome::Retry, ctx));

    harness.until(connected).await;
    harness.with(|session, ctx| session.handle(server_session::Outcome::ReconnectPauseOver, ctx));
    harness.with(|session, ctx| session.handle(server_session::Outcome::KeepAliveCheck, ctx));
    assert!(harness.session.is_connected());
}
