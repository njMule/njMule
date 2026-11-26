// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Kad reacting to nick and UDP port preference changes.

#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::net::{Ipv4Addr, UdpSocket};

use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::engine::Engine;
use tempfile::TempDir;

use crate::free_port::{free_port, free_udp_port};

async fn start(dir: &TempDir, udp_port: u16, kad: bool) -> Engine {
    let dirs = AppDirectories::under_root(&dir.path().join("njmule")).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    let tcp_port = free_port();
    fs::write(
        dirs.config.join("njmule.properties"),
        format!("net.tcpPort={tcp_port}\nnet.udpPort={udp_port}\nnet.kad.enabled={kad}\n"),
    )
    .unwrap();
    Engine::start(dirs).await.unwrap()
}

#[tokio::test]
async fn should_keep_kad_connected_by_hand_when_other_settings_change() {
    let dir = TempDir::new().unwrap();
    let engine = start(&dir, free_udp_port(), false).await;
    engine.connect_kad().await;
    assert!(engine.kad().await.is_some());

    engine
        .change_preferences(&|p: &Preferences| {
            let mut next = p.clone();
            next.nick = "renamed".to_owned();
            Ok(next)
        })
        .unwrap();

    assert!(engine.kad().await.is_some(), "still connected");
    engine.stop().await;
}

#[tokio::test]
async fn should_start_kad_that_could_not_take_its_port_on_the_next_one() {
    let dir = TempDir::new().unwrap();
    let taken = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).unwrap();
    let taken_port = taken.local_addr().unwrap().port();
    let engine = start(&dir, taken_port, true).await;
    assert!(engine.kad().await.is_none());

    let free = free_udp_port();
    engine
        .change_preferences(&|p: &Preferences| {
            let mut next = p.clone();
            next.udp_port = free;
            Ok(next)
        })
        .unwrap();

    assert!(engine.kad().await.is_some());
    engine.stop().await;
    drop(taken);
}

#[tokio::test]
async fn should_keep_kad_stopped_by_hand_stopped_when_the_port_changes() {
    let dir = TempDir::new().unwrap();
    let engine = start(&dir, free_udp_port(), true).await;
    assert!(engine.kad().await.is_some());
    engine.disconnect_kad().await;

    let free = free_udp_port();
    engine
        .change_preferences(&|p: &Preferences| {
            let mut next = p.clone();
            next.udp_port = free;
            Ok(next)
        })
        .unwrap();

    assert!(engine.kad().await.is_none());
    engine.stop().await;
}
