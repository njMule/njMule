// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The engine proves its user hash to the clients it meets and asks them to prove theirs, remembering the key each
//! proved to own; a hello asked through the API tells how far the client went.

#![allow(clippy::unwrap_used, clippy::expect_used)]

use crate::free_port;

use std::fs;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;

use njmule_engine::api::peer_info::Identification;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::crypto::rsa::{self, NETWORK_KEY_BITS};
use njmule_engine::engine::Engine;
use njmule_engine::net::tcp_connection::{read_packet, write_packet};
use njmule_engine::peer::identity_handshake::{Identity, IdentityHandshake, Outcome};
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::packet::frame_limits;
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::protocol;
use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::net::{TcpListener, TcpStream};

const THEIRS: [u8; 16] = [9; 16];

async fn engine() -> (tempfile::TempDir, Engine) {
    let profile = tempfile::tempdir().unwrap();
    let dirs = AppDirectories::under_root(profile.path()).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    // The preferences take no port of 0.
    let port = free_port::free_port();
    fs::write(dirs.config.join("njmule.properties"), format!("net.tcpPort={port}\n")).unwrap();
    (profile, Engine::start(dirs).await.unwrap())
}

// A client taking part in the identification with both versions, or not at all.
fn their_hello(versions: u32) -> Hello {
    let ours = Ours { nick: "fake peer", udp_port: 0, kad_port: 0, misc_options1: versions << 16, misc_options2: 0 };
    Hello::of(THEIRS, 0, 4662, &ours, 0, 0)
}

fn local_engine(engine: &Engine) -> SocketAddr {
    SocketAddr::from((Ipv4Addr::LOCALHOST, engine.listen_port()))
}

#[tokio::test]
async fn should_prove_its_user_hash_to_a_client_and_remember_the_key_the_client_proved() {
    let (_profile, engine) = engine().await;
    let ours = Arc::new(Identity::of(rsa::generate(NETWORK_KEY_BITS).unwrap()));
    let outcome = identify_with(&engine, &ours).await;

    let mut again = TcpStream::connect(local_engine(&engine)).await.unwrap();
    write_packet(&mut again, &their_hello(3).encode(false).unwrap()).await.unwrap();
    let state = loop {
        let packet = read_packet(&mut again, &frame_limits::client).await.unwrap().expect("the identification");
        if packet.protocol() == protocol::EMULE && packet.opcode() == extended::SECIDENTSTATE {
            break packet;
        }
    };

    assert!(matches!(outcome, Outcome::Identified { user_hash, .. } if user_hash == engine.user_hash()));
    // Only the signature: the engine knows our key.
    assert_eq!(state.payload().first(), Some(&1));
    engine.stop().await;
}

// Runs the identification with the engine over a new connection; what we made of the engine's proof, once the engine
// checked ours.
async fn identify_with(engine: &Engine, ours: &Arc<Identity>) -> Outcome {
    let mut stream = TcpStream::connect(local_engine(engine)).await.unwrap();
    write_packet(&mut stream, &their_hello(3).encode(false).unwrap()).await.unwrap();
    let answer = read_packet(&mut stream, &frame_limits::client_handshake).await.unwrap().unwrap();
    let engine_hello = Hello::decode(&answer).unwrap();
    let mut handshake = IdentityHandshake::new(
        Arc::clone(ours),
        &engine_hello,
        IpAddr::V4(Ipv4Addr::LOCALHOST),
        None,
        None,
        Some(IpAddr::V4(Ipv4Addr::LOCALHOST)),
    );
    for packet in handshake.start(&mut StdRng::from_rng(&mut rand::rng())) {
        write_packet(&mut stream, &packet).await.unwrap();
    }
    let mut outcome = None;
    while outcome.is_none() {
        let packet = read_packet(&mut stream, &frame_limits::client).await.unwrap().expect("the identification");
        if let Some(handled) = handshake.received(&packet).unwrap() {
            for answer in handled.send {
                write_packet(&mut stream, &answer).await.unwrap();
            }
            outcome = handled.outcome;
        }
    }
    // Packets of a connection are taken in order: once a new hello is answered, our signature was checked.
    write_packet(&mut stream, &their_hello(3).encode(false).unwrap()).await.unwrap();
    while read_packet(&mut stream, &frame_limits::client).await.unwrap().expect("an answer").opcode()
        != client::HELLOANSWER
    {}
    outcome.unwrap()
}

#[tokio::test]
async fn should_tell_that_a_greeted_client_proved_its_user_hash() {
    let (_first_profile, first) = engine().await;
    let (_second_profile, second) = engine().await;

    let info = first.hello(local_engine(&second)).await.unwrap();

    assert_eq!((info.user_hash, info.identification), (second.user_hash(), Identification::Verified));
    first.stop().await;
    second.stop().await;
}

#[tokio::test]
async fn should_tell_that_a_greeted_client_does_not_take_part() {
    let (info, _) = greet_a_client_answering_with(0).await;

    assert_eq!(info.identification, Identification::NotSupported);
}

// The connection closes before the client proves anything.
#[tokio::test]
async fn should_tell_that_the_identification_of_a_greeted_client_is_pending() {
    let (info, _) = greet_a_client_answering_with(3).await;

    assert_eq!(info.identification, Identification::Pending);
}

// A client that answers our hello with `versions`, then closes.
async fn greet_a_client_answering_with(versions: u32) -> (njmule_engine::api::peer_info::PeerInfo, Engine) {
    let (_profile, engine) = engine().await;
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let address = listener.local_addr().unwrap();
    let client = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let hello = read_packet(&mut stream, &frame_limits::client_handshake).await.unwrap().unwrap();
        assert_eq!(hello.opcode(), client::HELLO);
        write_packet(&mut stream, &their_hello(versions).encode(true).unwrap()).await.unwrap();
    });

    let info = engine.hello(address).await.unwrap();

    client.await.unwrap();
    engine.stop().await;
    (info, engine)
}
