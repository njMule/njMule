// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Examples of the Java engine's ServerSessionTest and ServerConnectingTest, under their names, against a scripted
//! server on the loopback: the session's attempts, its login and what it makes of the server's packets.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::panic,
    clippy::arithmetic_side_effects
)]

use std::collections::VecDeque;
use std::net::{Ipv4Addr, SocketAddr};
use std::time::Duration;

use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::net::tcp_connection::{FrameReader, FrameWriter, read_packet, write_packet};
use njmule_engine::persist::format::server_met::Server;
use njmule_engine::protocol::crypt::stream_obfuscation;
use njmule_engine::protocol::ed2k::server_messages::{self, IdChange};
use njmule_engine::protocol::packet::opcodes::server;
use njmule_engine::protocol::packet::{Packet, frame_limits, protocol};
use njmule_engine::server::server_list::{self, ServerList};
use njmule_engine::server::server_session::{self, Context, Notice, Outcome, ServerSession};
use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::io::AsyncReadExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

const WAIT: Duration = Duration::from_secs(10);
const USER_HASH: [u8; 16] = [7; 16];

struct Harness {
    session: ServerSession,
    servers: ServerList,
    outcomes: mpsc::UnboundedReceiver<Outcome>,
    preferences: Preferences,
    // Notices told but not yet looked at.
    told: VecDeque<Notice>,
}

impl Harness {
    fn new(obfuscation: ObfuscationMode) -> Self {
        let (outcomes_in, outcomes) = mpsc::unbounded_channel();
        Self {
            session: ServerSession::new(outcomes_in),
            servers: ServerList::default(),
            outcomes,
            preferences: Preferences { obfuscation, ..Preferences::default() },
            told: VecDeque::new(),
        }
    }

    fn connect(&mut self, target: Server) {
        let told = self.with(|session, ctx| session.connect(Some(target), ctx));
        self.told.extend(told);
    }

    fn with(&mut self, work: impl FnOnce(&mut ServerSession, &mut Context<'_>)) -> Vec<Notice> {
        let mut notices = Vec::new();
        let mut ctx = Context {
            servers: &mut self.servers,
            preferences: &self.preferences,
            user_hash: USER_HASH,
            listen_port: 4662,
            keep_connected: false,
            notices: &mut notices,
        };
        work(&mut self.session, &mut ctx);
        notices
    }

    /// Handles what the session's tasks report until a notice matches.
    async fn until(&mut self, wanted: impl Fn(&Notice) -> bool) -> Notice {
        let waiting = async {
            loop {
                while let Some(notice) = self.told.pop_front() {
                    if wanted(&notice) {
                        return notice;
                    }
                }
                let outcome = self.outcomes.recv().await.expect("the session's outcomes");
                let told = self.with(|session, ctx| session.handle(outcome, ctx));
                self.told.extend(told);
            }
        };
        tokio::time::timeout(WAIT, waiting).await.expect("the notice in time")
    }
}

/// A server on the loopback that runs `script` on each connection in turn, with its number from 1.
async fn scripted<F, R>(script: F) -> (Server, JoinHandle<()>)
where
    F: Fn(u32, TcpStream) -> R + Send + 'static,
    R: Future<Output = ()> + Send + 'static,
{
    let listener = TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let serving = tokio::spawn(async move {
        let mut number = 0;
        while let Ok((stream, _)) = listener.accept().await {
            number += 1;
            tokio::spawn(script(number, stream));
        }
    });
    (Server { ip: u32::from(Ipv4Addr::LOCALHOST), port, tags: Vec::new() }, serving)
}

fn id_change(client_id: u32) -> Packet {
    IdChange { client_id, server_flags: 0x119, reported_ip: 0x0A00_0001 }.encode()
}

// A plain server: reads the login, answers `answers`, then keeps the connection open.
async fn plain_login(mut stream: TcpStream, answers: Vec<Packet>) {
    let login = read_packet(&mut stream, &frame_limits::server).await.unwrap().unwrap();
    assert_eq!((login.protocol(), login.opcode()), (protocol::EDONKEY, server::LOGINREQUEST));
    for answer in answers {
        write_packet(&mut stream, &answer).await.unwrap();
    }
    let mut rest = Vec::new();
    let _ = stream.read_to_end(&mut rest).await;
}

// Closes a connection that does not start plainly: the server takes no obfuscation.
async fn refuse_obfuscation(stream: TcpStream) -> Option<TcpStream> {
    let mut first = [0u8; 1];
    stream.peek(&mut first).await.ok()?;
    protocol::is_tcp(first[0]).then_some(stream)
}

#[tokio::test]
async fn should_try_a_server_never_pinged_obfuscated_once_then_plainly() {
    let (seen, mut first_bytes) = mpsc::unbounded_channel();
    let (target, _serving) = scripted(move |number, stream| {
        let seen = seen.clone();
        async move {
            let mut first = [0u8; 1];
            stream.peek(&mut first).await.unwrap();
            seen.send((number, first[0])).unwrap();
            if let Some(stream) = refuse_obfuscation(stream).await {
                plain_login(stream, vec![id_change(0x0A00_0001)]).await;
            }
        }
    })
    .await;
    let mut harness = Harness::new(ObfuscationMode::Requested);
    harness.connect(target);

    let connected = harness.until(|notice| matches!(notice, Notice::Connected { .. })).await;

    assert!(matches!(connected, Notice::Connected { obfuscated: false, .. }));
    let (one, two) = (first_bytes.recv().await.unwrap(), first_bytes.recv().await.unwrap());
    assert_eq!(one.0, 1);
    assert!(!protocol::is_tcp(one.1), "connection 1 is obfuscated");
    assert_eq!(two, (2, protocol::EDONKEY));
}

#[tokio::test]
async fn should_log_in_obfuscated_to_a_server_never_pinged_that_takes_obfuscation_and_remember_it() {
    let (target, _serving) = scripted(|_, stream| async move {
        let (mut input, mut output) = stream.into_split();
        let mut marker = [0u8; 1];
        input.read_exact(&mut marker).await.unwrap();
        let mut random = StdRng::from_rng(&mut rand::rng());
        let ciphers = stream_obfuscation::accept_as_server(&mut input, &mut output, &mut random).await.unwrap();
        let mut reader = FrameReader::new(input, Some(ciphers.receive));
        let mut writer = FrameWriter::new(output, Some(ciphers.send));
        let login = reader.read_packet(&frame_limits::server).await.unwrap().unwrap();
        assert_eq!(login.opcode(), server::LOGINREQUEST);
        writer.write_packet(&id_change(0x0A00_0001)).await.unwrap();
        let _ = reader.read_packet(&frame_limits::server).await;
    })
    .await;
    let mut harness = Harness::new(ObfuscationMode::Requested);
    let port = target.port;
    harness.connect(target);

    let connected = harness.until(|notice| matches!(notice, Notice::Connected { .. })).await;

    let Notice::Connected { server, obfuscated, .. } = connected else { panic!("{connected:?}") };
    assert!(obfuscated);
    assert!(server_list::supports_obfuscation(&server));
    assert_eq!(server.obfuscated_tcp_port(), Some(u64::from(port)));
}

#[tokio::test]
async fn should_take_an_id_of_zero_as_no_login_and_a_later_id_as_one() {
    let (target, _serving) = scripted(|_, stream| async move {
        plain_login(stream, vec![id_change(0), id_change(1234)]).await;
    })
    .await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(target);

    let connected = harness.until(|notice| matches!(notice, Notice::Connected { .. })).await;

    assert!(matches!(connected, Notice::Connected { id, .. } if id.client_id == 1234 && id.is_low_id()));
}

#[tokio::test]
async fn should_give_up_soon_on_a_server_that_answers_with_an_id_of_zero_alone() {
    let (target, _serving) = scripted(|_, stream| async move {
        plain_login(stream, vec![id_change(0)]).await;
    })
    .await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(target);
    let started = std::time::Instant::now();

    let failed = harness.until(|notice| matches!(notice, Notice::AttemptFailed { .. })).await;

    assert!(matches!(failed, Notice::AttemptFailed { reason, .. } if reason.contains("id 0")));
    assert!(started.elapsed() < server_session::LOGIN_TIMEOUT);
}

#[tokio::test]
async fn should_drop_the_control_characters_from_the_server() {
    let (target, _serving) = scripted(|_, stream| async move {
        let message = server_messages::message("\u{1b}[31mhi\u{7}\nserver version 17.15").unwrap();
        plain_login(stream, vec![id_change(0x0A00_0001), message]).await;
    })
    .await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(target);

    let message = harness.until(|notice| matches!(notice, Notice::Message { .. })).await;
    let version = harness.until(|notice| matches!(notice, Notice::Message { .. })).await;

    assert!(matches!(message, Notice::Message { line, .. } if line == "[31mhi"));
    assert!(matches!(version, Notice::Message { server, .. } if server.version().as_deref() == Some("17.15")));
}

#[tokio::test]
async fn should_end_the_session_when_the_server_hangs_up() {
    let (target, _serving) = scripted(|_, mut stream| async move {
        let _ = read_packet(&mut stream, &frame_limits::server).await;
        write_packet(&mut stream, &id_change(0x0A00_0001)).await.unwrap();
        tokio::time::sleep(Duration::from_millis(200)).await;
    })
    .await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(target);
    harness.until(|notice| matches!(notice, Notice::Connected { .. })).await;

    let ended = harness.until(|notice| matches!(notice, Notice::Disconnected { .. })).await;

    assert!(matches!(ended, Notice::Disconnected { server: Some(_), .. }));
    assert!(!harness.session.is_connected());
}

#[tokio::test]
async fn should_take_a_new_id_while_logged_in() {
    let (target, _serving) = scripted(|_, stream| async move {
        plain_login(stream, vec![id_change(0x0A00_0001), id_change(0x0A00_0002)]).await;
    })
    .await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(target);

    let changed = harness.until(|notice| matches!(notice, Notice::IdChanged { .. })).await;

    assert!(matches!(changed, Notice::IdChanged { id, .. } if id.client_id == 0x0A00_0002));
    assert_eq!(harness.session.id().map(|id| id.client_id), Some(0x0A00_0002));
}

#[tokio::test]
async fn should_fail_to_reach_a_server_that_refuses() {
    let closed = TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).await.unwrap();
    let port = closed.local_addr().unwrap().port();
    drop(closed);
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(Server { ip: u32::from(Ipv4Addr::LOCALHOST), port, tags: Vec::new() });

    let failed = harness.until(|notice| matches!(notice, Notice::AttemptFailed { .. })).await;

    assert!(matches!(failed, Notice::AttemptFailed { .. }));
}

#[tokio::test]
async fn should_drop_a_packet_that_does_not_inflate_and_go_on_with_the_session() {
    let (target, _serving) = scripted(|_, stream| async move {
        let broken = Packet::of(protocol::PACKED, server::SERVERMESSAGE, [1, 2, 3]);
        let message = server_messages::message("still here").unwrap();
        plain_login(stream, vec![id_change(0x0A00_0001), broken, message]).await;
    })
    .await;
    let mut harness = Harness::new(ObfuscationMode::Disabled);
    harness.connect(target);

    let message = harness.until(|notice| matches!(notice, Notice::Message { .. })).await;

    assert!(matches!(message, Notice::Message { line, .. } if line == "still here"));
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
