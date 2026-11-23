// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ServerControlTest, under its names: refusing filtered/LAN servers and ignoring IPv6 server UDP
//! datagrams.

#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::net::Ipv4Addr;

use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::persist::format::ip_filter_dat::{self, Range};
use njmule_engine::protocol::ed2k::file_entry::FileEntry;
use njmule_engine::protocol::ed2k::server_datagrams;
use njmule_engine::protocol::packet::opcodes::server_udp;
use njmule_engine::protocol::packet::udp_framing;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};
use njmule_engine::server::server_list::Address;
use tokio::net::UdpSocket;

use crate::free_port;

#[tokio::test]
async fn should_not_connect_to_a_server_the_filters_refuse() {
    let cases =
        [("127.0.0.2", "blocked by the IP filter"), ("0.1.2.3", "a LAN address while LAN addresses are filtered")];
    for (server, why) in cases {
        let dir = tempfile::tempdir().unwrap();
        let dirs = AppDirectories::under_root(&dir.path().join("njmule")).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let tcp_port = free_port::free_port();
        fs::write(
            dirs.config.join("njmule.properties"),
            format!("net.tcpPort={tcp_port}\nnet.kad.enabled=false\nsecurity.filterLan=true\n"),
        )
        .unwrap();
        let ip = u32::from(Ipv4Addr::new(127, 0, 0, 2));
        fs::write(
            dirs.config.join("ipfilter.dat"),
            ip_filter_dat::write(&[Range { start: ip, end: ip, level: 0, description: "blocked server".to_owned() }]),
        )
        .unwrap();

        let engine = Engine::start(dirs).await.unwrap();
        let addr = Address::new(u32::from(server.parse::<Ipv4Addr>().unwrap()), 4661);
        let outcome = engine.connect_server(Some(addr)).await;
        engine.stop().await;

        assert!(outcome.is_err(), "{why}");
    }
}

#[tokio::test]
async fn should_ignore_datagrams_from_ipv6_addresses() {
    let dir = tempfile::tempdir().unwrap();
    let dirs = AppDirectories::under_root(&dir.path().join("njmule")).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    let tcp_port = free_port::free_port();
    let udp_port = free_port::free_udp_port();
    fs::write(
        dirs.config.join("njmule.properties"),
        format!("net.tcpPort={tcp_port}\nnet.udpPort={udp_port}\nnet.kad.enabled=false\n"),
    )
    .unwrap();
    let engine = Engine::start(dirs).await.unwrap();

    // Send search-results and server-status datagrams over IPv6 loopback if available; the engine must remain healthy.
    if let Ok(sender) = UdpSocket::bind("[::1]:0").await {
        let file = FileEntry {
            hash: [0xab; 16],
            client_id: 0,
            port: 0,
            tags: vec![
                Tag::String { name: TagName::id(file_tag::FILENAME), value: "x.iso".to_owned() },
                Tag::Int { name: TagName::id(file_tag::FILESIZE), value: 10 },
            ],
        };
        let results = server_datagrams::search_results(&[file]).unwrap();
        let status = Packet::of(protocol::EDONKEY, server_udp::GLOBSERVSTATRES, vec![0u8; 12]);
        let _ = sender.send_to(&udp_framing::encode(&results), format!("[::1]:{udp_port}")).await;
        let _ = sender.send_to(&udp_framing::encode(&status), format!("[::1]:{udp_port}")).await;
    }

    assert!(engine.status().await.is_some());
    engine.stop().await;
}
