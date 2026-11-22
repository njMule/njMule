// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Twin of Java's `BindInterfaceEngineTest`: offline while the chosen interface is gone, online at once when changed.

#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::time::Duration;

use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::engine::Engine;
use tempfile::TempDir;

use crate::free_port::free_port;

// An address of the documentation range, which no computer here has.
const GONE: &str = "203.0.113.7";

fn write_profile(dir: &TempDir, choice: &str) -> (AppDirectories, u16) {
    let dirs = AppDirectories::under_root(&dir.path().join("njmule")).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    let tcp_port = free_port();
    fs::write(
        dirs.config.join("njmule.properties"),
        format!("net.tcpPort={tcp_port}\nnet.kad.enabled=false\nnet.bindInterface={choice}\n"),
    )
    .unwrap();
    (dirs, tcp_port)
}

#[tokio::test]
async fn should_stay_offline_while_the_chosen_interface_is_gone() {
    let dir = TempDir::new().unwrap();
    let (dirs, tcp_port) = write_profile(&dir, GONE);
    let engine = Engine::start(dirs).await.unwrap();
    let status = engine.status().await.unwrap();

    assert_eq!(status.missing_interface.as_deref(), Some(GONE));
    // TCP port is free while offline.
    let probe = TcpListener::bind((Ipv4Addr::UNSPECIFIED, tcp_port));
    assert!(probe.is_ok(), "TCP port {tcp_port} should be free while offline");
    drop(probe);
    engine.stop().await;
}

#[tokio::test]
async fn should_come_back_online_when_an_interface_that_is_there_is_chosen() {
    let dir = TempDir::new().unwrap();
    let (dirs, tcp_port) = write_profile(&dir, GONE);
    let engine = Engine::start(dirs).await.unwrap();
    assert_eq!(engine.status().await.unwrap().missing_interface.as_deref(), Some(GONE));

    engine
        .change_preferences(&|p: &Preferences| {
            let mut next = p.clone();
            next.bind_interface = String::new();
            Ok(next)
        })
        .unwrap();
    let status = engine.status().await.unwrap();

    assert_eq!(status.missing_interface, None);
    let connected =
        TcpStream::connect_timeout(&SocketAddr::from((Ipv4Addr::LOCALHOST, tcp_port)), Duration::from_secs(2));
    assert!(connected.is_ok(), "should listen on TCP port {tcp_port} once back online");
    engine.stop().await;
}
