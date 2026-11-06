// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A client greets the engine on its listening port: the engine answers with its hello, the way the transcripts of the
//! Java engine record it.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

mod free_port;

use std::fs;
use std::net::{Ipv4Addr, SocketAddr};

use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::net::tcp_connection::{read_packet, write_packet};
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::packet::frame_limits;
use njmule_engine::protocol::packet::opcodes::client;
use njmule_engine::protocol::tag::client_tag;
use tokio::net::TcpStream;

async fn engine() -> (tempfile::TempDir, Engine) {
    let profile = tempfile::tempdir().unwrap();
    let dirs = AppDirectories::under_root(profile.path()).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    // The preferences take no port of 0.
    let port = free_port::free_port();
    fs::write(dirs.config.join("njmule.properties"), format!("net.tcpPort={port}\n")).unwrap();
    (profile, Engine::start(dirs).await.unwrap())
}

fn their_hello() -> Hello {
    let ours = Ours { nick: "fake peer", udp_port: 0, kad_port: 0, misc_options1: 0, misc_options2: 0 };
    Hello::of([9; 16], 0, 4662, &ours, 0, 0)
}

#[tokio::test]
async fn should_answer_a_hello_with_ours() {
    let (_profile, engine) = engine().await;
    let mut stream = TcpStream::connect(SocketAddr::from((Ipv4Addr::LOCALHOST, engine.listen_port()))).await.unwrap();

    write_packet(&mut stream, &their_hello().encode(false).unwrap()).await.unwrap();
    let answer = read_packet(&mut stream, &frame_limits::client_handshake).await.unwrap().unwrap();

    assert_eq!(answer.opcode(), client::HELLOANSWER);
    let hello = Hello::decode(&answer).unwrap();
    assert_eq!(hello.user_hash, engine.user_hash());
    assert_eq!(hello.tcp_port, engine.listen_port());
    let names: Vec<u8> = hello.tags.iter().filter_map(|tag| tag.name().as_id()).collect();
    assert_eq!(
        names,
        [
            client_tag::NAME,
            client_tag::VERSION,
            client_tag::EMULE_UDPPORTS,
            client_tag::EMULE_MISCOPTIONS1,
            client_tag::EMULE_MISCOPTIONS2,
            client_tag::EMULE_VERSION,
            client_tag::MOD_VERSION,
            client_tag::MOD_MISCOPTIONS,
            client_tag::MOD_YOUR_IP,
        ]
    );
    assert_eq!((hello.misc_options1(), hello.misc_options2()), (0x3013_0014, 0x0000_0D90));
    engine.stop().await;
}

#[tokio::test]
async fn should_close_a_connection_that_does_not_start_with_a_hello() {
    let (_profile, engine) = engine().await;
    let mut stream = TcpStream::connect(SocketAddr::from((Ipv4Addr::LOCALHOST, engine.listen_port()))).await.unwrap();
    let mut answer = their_hello().encode(true).unwrap();
    answer = njmule_engine::protocol::packet::Packet::of(answer.protocol(), client::HELLOANSWER, answer.into_payload());

    write_packet(&mut stream, &answer).await.unwrap();

    assert!(matches!(read_packet(&mut stream, &frame_limits::client_handshake).await, Ok(None) | Err(_)));
    engine.stop().await;
}

#[tokio::test]
async fn should_tell_what_a_greeted_client_said() {
    let (_profile, engine) = engine().await;

    let info = engine.hello(SocketAddr::from((Ipv4Addr::LOCALHOST, engine.listen_port()))).await.unwrap();

    assert_eq!(info.user_hash, engine.user_hash());
    assert_eq!(info.nick.as_deref(), Some("njMule"));
    assert!(!info.obfuscated);
    engine.stop().await;
}

// Beyond the Java engine's examples: what its listener tests show through TcpConnector.accept.

#[tokio::test]
async fn should_answer_a_hello_over_an_obfuscated_connection() {
    let (_profile, engine) = engine().await;
    let mut random = <rand::rngs::StdRng as rand::SeedableRng>::from_rng(&mut rand::rng());
    let connection = njmule_engine::net::tcp_connector::connect_to_client(
        SocketAddr::from((Ipv4Addr::LOCALHOST, engine.listen_port())),
        Some(engine.user_hash()),
        std::time::Duration::from_secs(5),
        &njmule_engine::net::routes::Routes::direct(),
        &mut random,
    )
    .await
    .unwrap();
    let (mut reader, mut writer) = (connection.reader, connection.writer);

    writer.write_packet(&their_hello().encode(false).unwrap()).await.unwrap();
    let answer = reader.read_packet(&frame_limits::client_handshake).await.unwrap().unwrap();

    assert_eq!(answer.opcode(), client::HELLOANSWER);
    assert_eq!(Hello::decode(&answer).unwrap().user_hash, engine.user_hash());
    engine.stop().await;
}

#[tokio::test]
async fn should_answer_a_client_that_greets_us_with_our_own_user_hash_but_take_nothing_from_it() {
    let (_profile, engine) = engine().await;
    let ours = Ours { nick: "posing as us", udp_port: 0, kad_port: 0, misc_options1: 0, misc_options2: 0 };
    let mut stream = TcpStream::connect(SocketAddr::from((Ipv4Addr::LOCALHOST, engine.listen_port()))).await.unwrap();

    write_packet(&mut stream, &Hello::of(engine.user_hash(), 0, 4662, &ours, 0, 0).encode(false).unwrap())
        .await
        .unwrap();

    let answer = read_packet(&mut stream, &frame_limits::client_handshake).await.unwrap().unwrap();
    assert_eq!(answer.opcode(), client::HELLOANSWER);
    assert!(matches!(read_packet(&mut stream, &frame_limits::client).await, Ok(None) | Err(_)));
    assert!(engine.known_clients().await.is_empty());
    engine.stop().await;
}

#[tokio::test]
async fn should_hand_over_a_client_that_greets_us_with_its_own_user_hash() {
    let (_profile, engine) = engine().await;
    let mut stream = TcpStream::connect(SocketAddr::from((Ipv4Addr::LOCALHOST, engine.listen_port()))).await.unwrap();

    write_packet(&mut stream, &their_hello().encode(false).unwrap()).await.unwrap();

    let answer = read_packet(&mut stream, &frame_limits::client_handshake).await.unwrap().unwrap();
    assert_eq!(answer.opcode(), client::HELLOANSWER);
    // The link answers what the client says next.
    write_packet(&mut stream, &their_hello().encode(false).unwrap()).await.unwrap();
    let again = read_packet(&mut stream, &frame_limits::client).await.unwrap().unwrap();
    assert_eq!(again.opcode(), client::HELLOANSWER);
    let known: Vec<([u8; 16], String)> =
        engine.known_clients().await.into_iter().map(|client| (client.user_hash, client.nick)).collect();
    assert_eq!(known, [([9; 16], "fake peer".to_owned())]);
    engine.stop().await;
}
