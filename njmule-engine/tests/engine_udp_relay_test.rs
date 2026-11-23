// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Twin of Java's `EngineUdpRelayTest`: both server and Kad UDP sockets go through a SOCKS5 proxy relay.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::arithmetic_side_effects)]

use std::fs;
use std::time::{Duration, Instant};

use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::engine::Engine;
use tempfile::TempDir;

use crate::fake_socks5_proxy::FakeSocks5Proxy;
use crate::free_port::{free_port, free_udp_port};

async fn start_engine(dir: &TempDir, settings: &str) -> Engine {
    let dirs = AppDirectories::under_root(&dir.path().join("njmule")).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    let tcp_port = free_port();
    let udp_port = free_udp_port();
    fs::write(
        dirs.config.join("njmule.properties"),
        format!("net.tcpPort={tcp_port}\nnet.udpPort={udp_port}\nnet.kad.enabled=true\n{settings}\n"),
    )
    .unwrap();
    Engine::start(dirs).await.unwrap()
}

fn proxy_props(proxy: &FakeSocks5Proxy) -> String {
    let settings = proxy.settings();
    format!("proxy.host={}\nproxy.port={}\nproxy.type=SOCKS", settings.host, settings.port)
}

async fn wait_for(proxy: &FakeSocks5Proxy, event: &str, count: usize) {
    let deadline = Instant::now() + Duration::from_secs(20);
    while proxy.seen().iter().filter(|e| e.as_str() == event).count() < count {
        assert!(Instant::now() < deadline, "expected {count} '{event}' in {:?}", proxy.seen());
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

#[tokio::test]
async fn should_ask_the_proxy_for_a_relay_for_the_server_and_kad_sockets() {
    let dir = TempDir::new().unwrap();
    let proxy = FakeSocks5Proxy::start();
    let engine = start_engine(&dir, &proxy_props(&proxy)).await;

    wait_for(&proxy, "associate", 2).await;

    engine.stop().await;
}

#[tokio::test]
async fn should_ask_the_proxy_for_relays_when_it_is_chosen() {
    let dir = TempDir::new().unwrap();
    let proxy = FakeSocks5Proxy::start();
    let engine = start_engine(&dir, "").await;

    let chosen = proxy.settings();
    engine
        .change_preferences(&|p: &Preferences| {
            let mut next = p.clone();
            next.proxy = chosen.clone();
            Ok(next)
        })
        .unwrap();

    wait_for(&proxy, "associate", 2).await;

    engine.stop().await;
}

#[tokio::test]
async fn should_end_the_relays_when_nj_mule_stops() {
    let dir = TempDir::new().unwrap();
    let proxy = FakeSocks5Proxy::start();
    let engine = start_engine(&dir, &proxy_props(&proxy)).await;
    wait_for(&proxy, "associate", 2).await;

    engine.stop().await;

    wait_for(&proxy, "ended", 2).await;
}
