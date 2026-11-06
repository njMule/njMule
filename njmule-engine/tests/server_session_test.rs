// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ServerSessionTest, under its names: what the session sends and takes, against a server the test
//! plays packet by packet on the loopback.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

mod scripted_server;

use std::net::{Ipv4Addr, SocketAddr};
use std::time::{Duration, Instant};

use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::net::tcp_connection::{read_packet, write_packet};
use njmule_engine::persist::format::server_met::Server;
use njmule_engine::protocol::ed2k::server_datagrams;
use njmule_engine::protocol::ed2k::server_messages::{self, IdChange, ServerIdent};
use njmule_engine::protocol::packet::opcodes::server;
use njmule_engine::protocol::packet::{Packet, frame_limits};
use njmule_engine::protocol::tag::server_tag;
use njmule_engine::server::server_list;
use njmule_engine::server::server_session::{self, Notice};
use scripted_server::{HIGH_ID, Harness, WAIT, connected, disconnected};
use tokio::net::{TcpListener, TcpStream};

/// The server's side: a listener on the loopback, and the connection the session opened to it.
struct Played {
    listener: TcpListener,
    stream: Option<TcpStream>,
}

impl Played {
    async fn new() -> Self {
        Self { listener: TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap(), stream: None }
    }

    fn port(&self) -> u16 {
        self.listener.local_addr().unwrap().port()
    }

    // Pinged before and known to be plain: no obfuscation is tried first.
    fn plain(&self) -> Server {
        Server {
            ip: u32::from(Ipv4Addr::LOCALHOST),
            port: self.port(),
            tags: vec![server_list::int(server_tag::UDPFLAGS, 0)],
        }
    }

    // Takes the session's connection and its login.
    async fn accept(&mut self) -> Packet {
        let taken = tokio::time::timeout(WAIT, async {
            let (mut stream, _) = self.listener.accept().await.unwrap();
            let login = read_packet(&mut stream, &frame_limits::server).await.unwrap().unwrap();
            (stream, login)
        });
        let (stream, login) = taken.await.expect("the login in time");
        self.stream = Some(stream);
        login
    }

    async fn send(&mut self, packets: &[Packet]) {
        for packet in packets {
            write_packet(self.stream.as_mut().unwrap(), packet).await.unwrap();
        }
    }

    // The next packet the session sends, if one comes within `wait`.
    async fn next(&mut self, wait: Duration) -> Option<Packet> {
        let stream = self.stream.as_mut().unwrap();
        tokio::time::timeout(wait, read_packet(stream, &frame_limits::server)).await.ok()?.ok()?
    }
}

fn id(client_id: u32) -> Packet {
    IdChange { client_id, server_flags: 0, reported_ip: 0 }.encode()
}

async fn logged_in(harness: &mut Harness, played: &mut Played) {
    played.send(&[id(HIGH_ID)]).await;
    let notice = harness.until(connected).await;
    assert!(matches!(notice, Notice::Connected { id, .. } if id.client_id == HIGH_ID));
}

#[tokio::test]
async fn should_ignore_an_obfuscated_port_beyond_the_range_and_use_the_plain_one() {
    let mut played = Played::new().await;
    let mut harness = Harness::new(ObfuscationMode::Requested);
    let target = Server {
        ip: u32::from(Ipv4Addr::LOCALHOST),
        port: played.port(),
        tags: vec![
            server_list::int(server_tag::UDPFLAGS, u64::from(server_datagrams::UDP_TCP_OBFUSCATION)),
            server_list::int(server_tag::TCPPORTOBFUSCATION, 70_000),
        ],
    };

    harness.connect(Some(target));

    let login = harness.during(played.accept()).await;
    assert_eq!(login.opcode(), server::LOGINREQUEST);
}

#[tokio::test]
async fn should_drop_the_control_characters_from_the_server() {
    let mut played = Played::new().await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(Some(played.plain()));
    harness.during(played.accept()).await;
    let identity = ServerIdent {
        hash: [2; 16],
        ip: u32::from(Ipv4Addr::LOCALHOST),
        port: played.port(),
        name: Some("Evil\r\n6.6.6.6:4661,0,Injected".to_owned()),
        description: Some("bad\u{0}description".to_owned()),
    };

    played
        .send(&[
            id(HIGH_ID),
            server_messages::message("\u{1b}[31mWelcome\u{7}\r\nsecond\tline").unwrap(),
            identity.encode().unwrap(),
        ])
        .await;

    let message = |line: &'static str| move |notice: &Notice| matches!(notice, Notice::Message { line: told, .. } if told == line);
    harness.until(message("[31mWelcome")).await;
    harness.until(message("secondline")).await;
    let updated = harness.until(|notice| matches!(notice, Notice::Updated(_))).await;
    let Notice::Updated(server) = updated else { panic!() };
    assert_eq!((server.name(), server.description()), (Some("Evil6.6.6.6:4661,0,Injected"), Some("baddescription")));
}

#[tokio::test]
async fn should_take_an_id_of_zero_as_no_login_and_a_later_id_as_one() {
    let mut played = Played::new().await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(Some(played.plain()));
    harness.during(played.accept()).await;

    played.send(&[id(0), id(HIGH_ID)]).await;

    harness.until(connected).await;
    assert_eq!(harness.session.id().map(|id| id.client_id), Some(HIGH_ID));
    harness.run_for(Duration::from_millis(200)).await;
    assert_eq!(harness.seen.iter().filter(|notice| connected(notice)).count(), 1, "logged in once");
}

#[tokio::test]
async fn should_give_up_soon_on_a_server_that_answers_with_an_id_of_zero_alone() {
    let mut played = Played::new().await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(Some(played.plain()));
    harness.during(played.accept()).await;

    played.send(&[id(0)]).await;

    let failed = harness.until_within(server_session::LOGIN_TIMEOUT / 2, disconnected).await;
    assert!(matches!(&failed, Notice::Disconnected { reason, .. } if reason.contains("id 0")), "{failed:?}");
    assert!(!harness.session.is_connected());
}

#[tokio::test]
async fn should_offer_no_file_to_the_server_once_nothing_was_sent_for_a_while() {
    let mut played = Played::new().await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(Some(played.plain()));
    harness.during(played.accept()).await;
    logged_in(&mut harness, &mut played).await;

    harness.session.keep_alive_if_idle(Instant::now() + server_session::KEEP_ALIVE);

    let offer = played.next(WAIT).await.unwrap();
    assert_eq!(offer.opcode(), server::OFFERFILES);
    assert_eq!(offer.payload(), [0, 0, 0, 0]);
}

#[tokio::test]
async fn should_send_nothing_to_keep_alive_a_connection_in_use() {
    let mut played = Played::new().await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(Some(played.plain()));
    harness.during(played.accept()).await;
    logged_in(&mut harness, &mut played).await;

    harness.session.keep_alive_if_idle(Instant::now() + server_session::KEEP_ALIVE - Duration::from_secs(60));

    assert!(played.next(Duration::from_millis(500)).await.is_none());
}

#[tokio::test]
async fn should_end_the_attempt_when_the_server_hangs_up_before_the_id() {
    let mut played = Played::new().await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(Some(played.plain()));
    harness.during(played.accept()).await;

    played.stream = None;

    harness.until(disconnected).await;
    assert!(!harness.session.is_connected());
    assert!(harness.session.server().is_none());
}

#[tokio::test]
async fn should_never_dial_a_server_known_by_a_host_name_at_no_address() {
    let played = Played::new().await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    // localhost resolves without any network, to an address no server may have.
    let target = Server { ip: 0, port: played.port(), tags: vec![server_list::string(server_tag::DYNIP, "localhost")] };

    harness.connect(Some(target));

    harness.until(disconnected).await;
    let dialed = harness.seen.iter().any(|notice| matches!(notice, Notice::Connecting(server) if server.ip == 0));
    assert!(!dialed, "no server is dialed at 0.0.0.0");
    let arrived = tokio::time::timeout(Duration::from_millis(500), played.listener.accept()).await;
    assert!(arrived.is_err(), "no connection arrives");
    let _: SocketAddr = played.listener.local_addr().unwrap();
}
