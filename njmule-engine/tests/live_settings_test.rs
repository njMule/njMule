// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Changing TCP port and Kad enablement without restarting.

#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::time::Duration;

use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::engine::Engine;
use tempfile::TempDir;

use crate::free_port::{free_port, free_udp_port};

fn accepts(port: u16) -> bool {
    TcpStream::connect_timeout(&SocketAddr::from((Ipv4Addr::LOCALHOST, port)), Duration::from_secs(2)).is_ok()
}

#[tokio::test]
async fn should_apply_new_ports_and_kad_without_a_restart() {
    let dir = TempDir::new().unwrap();
    let dirs = AppDirectories::under_root(&dir.path().join("njmule")).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    let first_port = free_port();
    let udp_port = free_udp_port();
    fs::write(
        dirs.config.join("njmule.properties"),
        format!("net.tcpPort={first_port}\nnet.udpPort={udp_port}\nnet.kad.enabled=false\n"),
    )
    .unwrap();
    let engine = Engine::start(dirs).await.unwrap();
    assert!(accepts(first_port));

    let second = free_port();
    engine
        .change_preferences(&|p: &Preferences| {
            let mut next = p.clone();
            next.tcp_port = second;
            Ok(next)
        })
        .unwrap();
    // Round-trip a command so the network task applies the preference change.
    let _ = engine.status().await;
    assert!(accepts(second), "the new port listens at once");
    assert!(!accepts(first_port), "the old one is let go");

    assert!(engine.kad().await.is_none());
    engine.connect_kad().await;
    assert!(engine.kad().await.is_some(), "connected by hand");

    engine.disconnect_kad().await;
    assert!(engine.kad().await.is_none());

    engine
        .change_preferences(&|p: &Preferences| {
            let mut next = p.clone();
            next.kad_enabled = true;
            Ok(next)
        })
        .unwrap();
    assert!(engine.kad().await.is_some(), "turned on in the settings");

    engine
        .change_preferences(&|p: &Preferences| {
            let mut next = p.clone();
            next.kad_enabled = false;
            Ok(next)
        })
        .unwrap();
    assert!(engine.kad().await.is_none());

    engine.stop().await;
    assert!(!accepts(first_port), "closed with the engine");
}
