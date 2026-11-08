// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ChatTest, under its names: chats between running engines and with scripted clients, and the
//! friends kept across a restart.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::panic)]

use std::fs;
use std::net::{Ipv4Addr, SocketAddr};
use std::time::Duration;

use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::friend::Friend;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::protocol::ed2k::chat_messages;
use njmule_engine::protocol::packet::opcodes::client;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::server::server_list::Address;
use tokio::sync::broadcast;

use crate::free_port;
use crate::scripted_source::{Link, ScriptedSource};

const WAIT: Duration = Duration::from_secs(20);
const PAL: [u8; 16] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF];
const ASKER: [u8; 16] =
    [0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0x00, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF];

#[tokio::test]
async fn should_let_two_clients_chat_over_one_connection() {
    let mut test = Chat::new();
    test.given_the_engines_of_alice_and_bob().await;
    test.given_alice_wrote_to_bob("hello bob, ça va?").await;
    test.given_bob_got_from_alice("hello bob, ça va?").await;
    test.given_bob_answered_alice("hi alice").await;
    test.given_alice_got_from_bob("hi alice").await;
    test.given_alice_wrote_to_bob("again").await;
    test.given_bob_got("again").await;

    test.when_alice_writes_to_bob("  ").await;

    test.then_no_exception_occurred();
}

#[tokio::test]
async fn should_ignore_a_captcha_nobody_asked_for() {
    let mut test = Chat::new();
    test.given_an_engine("me").await;
    test.given_a_client_that_called_us().await;

    test.when_the_client_sends(&[
        chat_messages::captcha_request(&bmp(40, 24)),
        chat_messages::message("unasked", true).unwrap(),
    ])
    .await;

    test.then_the_next_message_shown_is("unasked").await;
}

#[tokio::test]
async fn should_show_the_captcha_of_a_client_we_wrote_to_and_tell_its_result() {
    let mut test = Chat::new();
    test.given_an_engine("me").await;
    test.given_we_wrote_to_a_client("may I write?").await;

    // A picture declaring a huge size is refused; a plausible one is shown.
    test.when_the_client_sends(&[
        chat_messages::captcha_request(&bmp(30_000, 24)),
        chat_messages::captcha_request(&bmp(40, 24)),
        chat_messages::captcha_result(chat_messages::SOLVED),
        chat_messages::message(&"x".repeat(500), true).unwrap(),
    ])
    .await;

    test.then_the_captcha_shown_is(&bmp(40, 24)).await;
    test.then_the_captcha_is_solved().await;
    test.then_the_next_message_shown_has_the_length(chat_messages::MAX_LENGTH).await;
}

#[tokio::test]
async fn should_report_an_unreachable_client() {
    let mut test = Chat::new();
    test.given_an_engine("me").await;
    test.given_a_closed_port();

    test.when_we_write_to_the_closed_port("anyone?").await;

    test.then_the_message_to_the_closed_port_fails().await;
}

#[tokio::test]
async fn should_keep_the_friends_across_a_restart() {
    let mut test = Chat::new();
    test.given_an_engine("me").await;
    test.given_a_friend_added(PAL, "pal", "10.0.0.7:4662").await;
    test.given_a_friend_added(PAL, "pal again", "pal.example:1").await;

    test.when_the_engine_restarts().await;

    test.then_the_friends_are(&[Friend::new(PAL, "pal again", None)]).await;
}

#[tokio::test]
async fn should_remove_a_kept_friend_only_once() {
    let mut test = Chat::new();
    test.given_an_engine("me").await;
    test.given_a_friend_added(PAL, "pal", "10.0.0.7:4662").await;
    test.given_a_friend_added(PAL, "pal again", "pal.example:1").await;
    test.given_the_engine_restarted().await;
    test.given_the_friend_removed(PAL).await;

    test.when_the_friend_is_removed(PAL).await;

    test.then_no_friend_was_removed();
    test.then_there_are_no_friends().await;
}

/// Engines on profiles of a test folder, the events each tells, and the scripted clients they chat with.
struct Chat {
    dir: tempfile::TempDir,
    engine: Option<Engine>,
    seen: Option<broadcast::Receiver<EngineEvent>>,
    alice: Option<(Engine, broadcast::Receiver<EngineEvent>)>,
    bob: Option<(Engine, broadcast::Receiver<EngineEvent>)>,
    to_alice: Option<SocketAddr>,
    peer: Option<ScriptedSource>,
    client: Option<Link>,
    closed_port: u16,
    answered: bool,
    removed: Option<bool>,
}

impl Chat {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            engine: None,
            seen: None,
            alice: None,
            bob: None,
            to_alice: None,
            peer: None,
            client: None,
            closed_port: 0,
            answered: false,
            removed: None,
        }
    }

    async fn given_the_engines_of_alice_and_bob(&mut self) {
        let alice = self.start("alice").await;
        let bob = self.start("bob").await;
        let (alice_sees, bob_sees) = (alice.subscribe().live, bob.subscribe().live);
        self.alice = Some((alice, alice_sees));
        self.bob = Some((bob, bob_sees));
    }

    async fn given_alice_wrote_to_bob(&self, text: &str) {
        let (alice, _) = self.alice.as_ref().unwrap();
        within(alice.send_message(local(&self.bob.as_ref().unwrap().0), text.to_owned())).await;
    }

    async fn given_bob_got_from_alice(&mut self, text: &str) {
        let alice_port = self.alice.as_ref().unwrap().0.preferences().tcp_port;
        let (address, nick, got) = next_message(&mut self.bob.as_mut().unwrap().1).await;
        assert_eq!(got, text);
        assert_eq!(nick.as_deref(), Some("alice"));
        assert!(address.ends_with(&format!(":{alice_port}")), "{address}");
        self.to_alice = Some(address.parse().unwrap());
    }

    async fn given_bob_answered_alice(&self, text: &str) {
        let (bob, _) = self.bob.as_ref().unwrap();
        within(bob.send_message(self.to_alice.unwrap(), text.to_owned())).await;
    }

    async fn given_alice_got_from_bob(&mut self, text: &str) {
        let (_, nick, got) = next_message(&mut self.alice.as_mut().unwrap().1).await;
        assert_eq!(got, text);
        assert_eq!(nick.as_deref(), Some("bob"));
    }

    async fn given_bob_got(&mut self, text: &str) {
        assert_eq!(next_message(&mut self.bob.as_mut().unwrap().1).await.2, text);
    }

    async fn given_an_engine(&mut self, nick: &str) {
        let engine = self.start(nick).await;
        self.seen = Some(engine.subscribe().live);
        self.engine = Some(engine);
    }

    async fn given_a_client_that_called_us(&mut self) {
        let asker = ScriptedSource::new(ASKER, 0, 0).await;
        self.client = Some(ScriptedSource::call(self.engine().preferences().tcp_port, &asker.hello()).await);
        self.peer = Some(asker);
    }

    async fn given_we_wrote_to_a_client(&mut self, text: &str) {
        let peer = ScriptedSource::new(ASKER, 0, 0).await;
        within(self.engine().send_message(peer.address(), text.to_owned())).await;
        let mut client = peer.accept().await;
        let read = client.until(protocol::EDONKEY, client::MESSAGE).await;
        assert_eq!(chat_messages::text(read.last().unwrap()).unwrap(), text);
        self.client = Some(client);
        self.peer = Some(peer);
    }

    fn given_a_closed_port(&mut self) {
        self.closed_port = free_port::free_port();
    }

    async fn given_a_friend_added(&self, user_hash: [u8; 16], name: &str, address: &str) {
        let friend = Friend::new(user_hash, name, Address::parse(address));
        within(self.engine().add_friend(friend)).await.unwrap();
    }

    async fn given_the_engine_restarted(&mut self) {
        self.when_the_engine_restarts().await;
    }

    async fn given_the_friend_removed(&self, user_hash: [u8; 16]) {
        assert!(within(self.engine().remove_friend(user_hash)).await.unwrap());
    }

    async fn when_alice_writes_to_bob(&mut self, text: &str) {
        let (alice, _) = self.alice.as_ref().unwrap();
        let to = local(&self.bob.as_ref().unwrap().0);
        self.answered = tokio::time::timeout(WAIT, alice.send_message(to, text.to_owned())).await.is_ok();
    }

    async fn when_the_client_sends(&mut self, packets: &[Packet]) {
        let client = self.client.as_mut().unwrap();
        for packet in packets {
            client.send(packet).await;
        }
    }

    async fn when_we_write_to_the_closed_port(&self, text: &str) {
        let to = SocketAddr::from((Ipv4Addr::LOCALHOST, self.closed_port));
        within(self.engine().send_message(to, text.to_owned())).await;
    }

    async fn when_the_engine_restarts(&mut self) {
        within(self.engine().stop()).await;
        self.given_an_engine("me").await;
    }

    async fn when_the_friend_is_removed(&mut self, user_hash: [u8; 16]) {
        self.removed = Some(within(self.engine().remove_friend(user_hash)).await.unwrap());
    }

    fn then_no_exception_occurred(&self) {
        assert!(self.answered, "the engine took the message");
    }

    async fn then_the_next_message_shown_is(&mut self, text: &str) {
        assert_eq!(next_message(self.seen.as_mut().unwrap()).await.2, text);
    }

    async fn then_the_captcha_shown_is(&mut self, image: &[u8]) {
        let port = self.peer.as_ref().unwrap().port();
        let asked = next_event(self.seen.as_mut().unwrap(), |event| match event {
            EngineEvent::CaptchaRequested { address, image, .. } => Some((address, image)),
            _ => None,
        })
        .await;
        assert_eq!(asked.1, image);
        assert!(asked.0.ends_with(&format!(":{port}")), "{}", asked.0);
    }

    async fn then_the_captcha_is_solved(&mut self) {
        let solved = next_event(self.seen.as_mut().unwrap(), |event| match event {
            EngineEvent::CaptchaAnswered { solved, .. } => Some(solved),
            _ => None,
        })
        .await;
        assert!(solved);
    }

    async fn then_the_next_message_shown_has_the_length(&mut self, length: usize) {
        assert_eq!(next_message(self.seen.as_mut().unwrap()).await.2.encode_utf16().count(), length);
    }

    async fn then_the_message_to_the_closed_port_fails(&mut self) {
        let failed = next_event(self.seen.as_mut().unwrap(), |event| match event {
            EngineEvent::MessageFailed { address, .. } => Some(address),
            _ => None,
        })
        .await;
        assert_eq!(failed, format!("127.0.0.1:{}", self.closed_port));
    }

    async fn then_the_friends_are(&self, expected: &[Friend]) {
        assert_eq!(within(self.engine().friends()).await, expected);
    }

    fn then_no_friend_was_removed(&self) {
        assert_eq!(self.removed, Some(false));
    }

    async fn then_there_are_no_friends(&self) {
        assert_eq!(within(self.engine().friends()).await, Vec::new());
    }

    // An engine on the profile of `nick`, on a free port without Kad; its preferences stay across restarts.
    async fn start(&self, nick: &str) -> Engine {
        let dirs = AppDirectories::under_root(&self.dir.path().join(nick)).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let preferences = dirs.config.join("njmule.properties");
        if !preferences.exists() {
            let port = free_port::free_port();
            fs::write(&preferences, format!("net.tcpPort={port}\nnet.kad.enabled=false\nuser.nick={nick}\n")).unwrap();
        }
        within(Engine::start(dirs)).await.unwrap()
    }

    fn engine(&self) -> Engine {
        self.engine.clone().expect("a running engine")
    }
}

impl Drop for Chat {
    fn drop(&mut self) {
        let engines =
            [self.engine.take(), self.alice.take().map(|(alice, _)| alice), self.bob.take().map(|(bob, _)| bob)];
        for engine in engines.into_iter().flatten() {
            tokio::spawn(async move { engine.stop().await });
        }
    }
}

// The next message shown: its address, nick and text.
async fn next_message(events: &mut broadcast::Receiver<EngineEvent>) -> (String, Option<String>, String) {
    next_event(events, |event| match event {
        EngineEvent::MessageReceived { address, nick, text, .. } => Some((address, nick, text)),
        _ => None,
    })
    .await
}

// The next event of a kind, the others left out.
async fn next_event<T>(events: &mut broadcast::Receiver<EngineEvent>, of: impl Fn(EngineEvent) -> Option<T>) -> T {
    tokio::time::timeout(WAIT, async {
        loop {
            match events.recv().await {
                Ok(event) => {
                    if let Some(found) = of(event) {
                        return found;
                    }
                }
                Err(broadcast::error::RecvError::Lagged(_)) => {}
                Err(broadcast::error::RecvError::Closed) => panic!("the engine stopped"),
            }
        }
    })
    .await
    .expect("the event in time")
}

fn local(engine: &Engine) -> SocketAddr {
    SocketAddr::from((Ipv4Addr::LOCALHOST, engine.preferences().tcp_port))
}

// The start of a BMP file of the given size; the pixels do not matter here.
fn bmp(width: i32, height: i32) -> Vec<u8> {
    let mut file = vec![0u8; 300];
    file[0] = b'B';
    file[1] = b'M';
    file[18..22].copy_from_slice(&width.to_le_bytes());
    file[22..26].copy_from_slice(&height.to_le_bytes());
    file
}

async fn within<T>(work: impl Future<Output = T>) -> T {
    tokio::time::timeout(WAIT, work).await.expect("answered in time")
}
