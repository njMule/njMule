// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's HelloOutcomeTest, under its names: hello outcomes when a peer disconnects right after answering
//! or sends a forged SecureIdent proof.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::arithmetic_side_effects)]

use std::fs;
use std::net::{Ipv4Addr, SocketAddr};

use njmule_engine::api::peer_info::Identification;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::crypto::rsa as engine_rsa;
use njmule_engine::engine::Engine;
use njmule_engine::net::tcp_connection::{read_packet, write_packet};
use njmule_engine::peer::identity_handshake::{self, Identity};
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::packet::frame_limits;
use njmule_engine::protocol::packet::opcodes::extended;
use njmule_engine::protocol::packet::{Packet, protocol};
use tokio::io::AsyncReadExt;
use tokio::net::{TcpListener, TcpStream};

use crate::free_port;

const PEER_HASH: [u8; 16] = [0x22; 16];

fn peer_hello() -> Hello {
    let misc1 = identity_handshake::SUPPORTED << 16;
    let ours = Ours { nick: "peer", udp_port: 0, kad_port: 0, misc_options1: misc1, misc_options2: 0 };
    Hello::of(PEER_HASH, 0, 4999, &ours, 0, 0)
}

#[tokio::test]
async fn should_report_a_client_that_leaves_right_after_its_answer_at_once() {
    let (engine, _dir) = start_engine().await;
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let addr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), listener.local_addr().unwrap().port());
    let server = tokio::spawn(async move {
        for _ in 0..3 {
            let (mut socket, _) = listener.accept().await.unwrap();
            let _ = read_packet(&mut socket, &frame_limits::client_handshake).await.unwrap();
            write_packet(&mut socket, &peer_hello().encode(true).unwrap()).await.unwrap();
            drop(socket);
        }
    });

    let mut answers = Vec::new();
    for _ in 0..3 {
        answers.push(engine.hello(addr).await.unwrap());
    }
    server.await.unwrap();

    assert_eq!(answers.len(), 3);
    for info in &answers {
        assert_eq!(info.user_hash, PEER_HASH);
        assert_eq!(info.identification, Identification::Pending);
    }
    engine.stop().await;
}

#[tokio::test]
async fn should_report_a_client_that_cannot_sign_as_failed() {
    let (engine, _dir) = start_engine().await;
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let addr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), listener.local_addr().unwrap().port());

    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let _ = read_packet(&mut socket, &frame_limits::client_handshake).await.unwrap();
        write_packet(&mut socket, &peer_hello().encode(true).unwrap()).await.unwrap();
        send_fake_proof(&mut socket).await;
        // Keep the socket alive briefly until the engine closes or completes.
        let mut buf = [0u8; 64];
        let _ = tokio::time::timeout(std::time::Duration::from_secs(5), socket.read(&mut buf)).await;
    });

    let info = engine.hello(addr).await.unwrap();
    server.await.unwrap();

    assert_eq!(info.identification, Identification::Failed);
    engine.stop().await;
}

async fn start_engine() -> (Engine, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    let dirs = AppDirectories::under_root(&dir.path().join("njmule")).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    let port = free_port::free_port();
    fs::write(dirs.config.join("njmule.properties"), format!("net.tcpPort={port}\nnet.kad.enabled=false\n")).unwrap();
    let engine = Engine::start(dirs).await.unwrap();
    (engine, dir)
}

async fn send_fake_proof(socket: &mut TcpStream) {
    let key = Identity::of(engine_rsa::generate(engine_rsa::NETWORK_KEY_BITS).unwrap()).public_key().to_vec();
    let mut key_payload = vec![u8::try_from(key.len()).unwrap()];
    key_payload.extend_from_slice(&key);
    let key_pkt = Packet::of(protocol::EMULE, extended::PUBLICKEY, key_payload);
    let mut sig_payload = vec![48u8];
    sig_payload.extend_from_slice(&[0u8; 48]);
    let sig_pkt = Packet::of(protocol::EMULE, extended::SIGNATURE, sig_payload);
    write_packet(socket, &key_pkt).await.unwrap();
    write_packet(socket, &sig_pkt).await.unwrap();
}
