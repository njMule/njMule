// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The sources we pass on to a client that asks for them.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::panic
)]

use std::fs;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::{Duration, Instant};

use njmule_engine::api::shared_file_info::SharedFileInfo;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::download::download_manager::passes_on;
use njmule_engine::engine::Engine;
use njmule_engine::net::tcp_connection::{read_packet, write_packet};
use njmule_engine::protocol::ed2k::client_messages;
use njmule_engine::protocol::ed2k::hello::{Hello, MISC2_SOURCE_EXCHANGE2, Ours};
use njmule_engine::protocol::ed2k::source_exchange as exchange;
use njmule_engine::protocol::packet::frame_limit;
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, protocol};
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};
use tempfile::TempDir;
use tokio::net::TcpStream;

use crate::free_port::free_port;

const WAIT: Duration = Duration::from_secs(20);

struct Client {
    stream: TcpStream,
}

impl Client {
    async fn connect(to: IpAddr, port: u16, seed: u64) -> Self {
        let mut stream = TcpStream::connect(SocketAddr::new(to, port)).await.unwrap();
        let mut hash = [0u8; 16];
        StdRng::seed_from_u64(seed).fill(&mut hash[..]);
        let nick = format!("client {seed}");
        let ours =
            Ours { nick: &nick, udp_port: 0, kad_port: 0, misc_options1: 0, misc_options2: MISC2_SOURCE_EXCHANGE2 };
        let listen = u16::try_from(5000 + seed).unwrap();
        let hello = Hello::of(hash, 0, listen, &ours, 0, 0).encode(false).unwrap();
        write_packet(&mut stream, &hello).await.unwrap();
        let mut client = Self { stream };
        let answer = client.next(WAIT).await.unwrap();
        assert_eq!(answer.opcode(), client::HELLOANSWER);
        client
    }

    async fn send(&mut self, packet: &Packet) {
        write_packet(&mut self.stream, packet).await.unwrap();
    }

    async fn next(&mut self, wait: Duration) -> Option<Packet> {
        let limit = frame_limit::fixed(1 << 22);
        tokio::time::timeout(wait, read_packet(&mut self.stream, &limit)).await.ok()?.ok()?
    }

    async fn answer(&mut self, wait: Duration) -> Option<exchange::Answer> {
        let deadline = Instant::now() + wait;
        while let Some(remaining) = deadline.checked_duration_since(Instant::now()) {
            let packet = self.next(remaining).await?;
            if packet.protocol() == protocol::EMULE && packet.opcode() == extended::ANSWERSOURCES2 {
                return Some(exchange::Answer::decode(&packet).unwrap());
            }
        }
        None
    }
}

fn lan_address() -> Option<IpAddr> {
    let interfaces = if_addrs::get_if_addrs().ok()?;
    for iface in interfaces {
        if iface.is_loopback() {
            continue;
        }
        if let IpAddr::V4(v4) = iface.ip()
            && v4.is_private()
        {
            return Some(IpAddr::V4(v4));
        }
    }
    None
}

async fn start_engine(root: &TempDir) -> Engine {
    let base = root.path().join("njmule");
    let dirs = AppDirectories::under_root(&base).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    let mut data = vec![0u8; 300_000];
    StdRng::seed_from_u64(5).fill(&mut data[..]);
    let incoming = base.join("incoming");
    fs::create_dir_all(&incoming).unwrap();
    fs::write(incoming.join("asked.bin"), &data).unwrap();
    let port = free_port();
    fs::write(
        dirs.config.join("njmule.properties"),
        format!("net.tcpPort={port}\nlimits.uploadKiBps=1\nsecurity.filterLan=false\n"),
    )
    .unwrap();
    Engine::start(dirs).await.unwrap()
}

async fn await_shared(engine: &Engine) -> SharedFileInfo {
    let deadline = Instant::now() + WAIT;
    while Instant::now() < deadline {
        if let Some(first) = engine.shared_files().await.into_iter().next() {
            return first;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("not shared");
}

async fn ask_for_sources(client: &mut Client, file: &[u8; 16]) {
    client.send(&client_messages::for_file(client::REQUESTFILENAME, file)).await;
    client.send(&exchange::request(file)).await;
}

#[tokio::test]
async fn should_pass_a_source_on_this_machine_only_to_clients_on_this_machine() {
    let Some(lan) = lan_address() else {
        return;
    };
    let dir = TempDir::new().unwrap();
    let engine = start_engine(&dir).await;
    let file = await_shared(&engine).await;
    let port = engine.preferences().tcp_port;

    let mut downloader = Client::connect(IpAddr::V4(Ipv4Addr::LOCALHOST), port, 1).await;
    downloader.send(&client_messages::for_file(client::SETREQFILEID, &file.hash)).await;
    downloader.send(&client_messages::for_file(client::STARTUPLOADREQ, &file.hash)).await;
    let _ = downloader.next(Duration::from_secs(1)).await;

    if let Ok(mut from_lan) = tokio::time::timeout(Duration::from_secs(3), Client::connect(lan, port, 2)).await {
        ask_for_sources(&mut from_lan, &file.hash).await;
        assert!(
            from_lan.answer(Duration::from_secs(2)).await.is_none(),
            "a loopback address means nothing to another machine"
        );
    }

    let mut local = Client::connect(IpAddr::V4(Ipv4Addr::LOCALHOST), port, 3).await;
    ask_for_sources(&mut local, &file.hash).await;
    let answer = local.answer(WAIT).await.unwrap();
    assert_eq!(answer.sources.len(), 1);
    assert_eq!(Ipv4Addr::from(answer.sources[0].ip).to_string(), "127.0.0.1");

    engine.stop().await;
}

#[test]
fn should_keep_addresses_outside_the_public_network_within_their_own() {
    let cases = [
        ("203.0.113.9", "198.51.100.7", true),
        ("203.0.113.9", "192.168.1.5", true),
        ("10.1.2.3", "198.51.100.7", false),
        ("10.1.2.3", "192.168.1.5", true),
        ("10.1.2.3", "127.0.0.1", true),
        ("127.0.0.1", "198.51.100.7", false),
        ("127.0.0.1", "192.168.1.5", false),
        ("127.0.0.1", "127.0.0.1", true),
        ("224.0.0.1", "127.0.0.1", false),
        ("0.1.2.3", "127.0.0.1", false),
    ];
    for (source, client, expected) in cases {
        let offered = u32::from(source.parse::<Ipv4Addr>().unwrap());
        let asking_from: IpAddr = client.parse().unwrap();
        assert_eq!(passes_on(offered, asking_from), expected, "source={source}, client={client}");
    }
}
