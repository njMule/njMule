// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The engine on the network interface of its preferences: offline while it is gone, on the network again once the
//! interface is looked at anew. Beyond the Java engine's examples, which play the network's binding in its own test.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]

use crate::free_port;

use std::collections::BTreeMap;
use std::fs;
use std::net::Ipv4Addr;
use std::time::Duration;

use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use tokio::net::TcpStream;

// The interface is looked at every ten seconds: a little more is allowed.
const REBOUND_WITHIN: Duration = Duration::from_secs(15);

async fn listens(port: u16) -> bool {
    TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await.is_ok()
}

// An engine on a profile with these preferences, and the TCP port it is to listen on.
async fn started(preferences: &str) -> (tempfile::TempDir, Engine, u16) {
    let profile = tempfile::tempdir().unwrap();
    let dirs = AppDirectories::under_root(profile.path()).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    let port = free_port::free_port();
    fs::write(dirs.config.join("njmule.properties"), format!("net.tcpPort={port}\n{preferences}")).unwrap();
    let engine = Engine::start(dirs).await.unwrap();
    (profile, engine, port)
}

fn choose(engine: &Engine, interface: &str) {
    engine
        .update_preferences(&BTreeMap::from([("net.bindInterface".to_owned(), interface.to_owned())]))
        .unwrap();
}

async fn until_listening_is(port: u16, wanted: bool) {
    let deadline = tokio::time::Instant::now() + REBOUND_WITHIN;
    while listens(port).await != wanted {
        assert!(tokio::time::Instant::now() < deadline, "listening {wanted} in time");
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
}

#[tokio::test]
async fn should_stay_offline_while_the_chosen_interface_is_gone_and_listen_once_any_will_do() {
    let (_profile, engine, port) = started("net.bindInterface=no such interface\n").await;
    assert!(!listens(port).await, "offline while the interface is gone");
    assert_eq!(engine.listen_port(), 0);

    choose(&engine, "");

    until_listening_is(port, true).await;
    assert_eq!(engine.listen_port(), port);
    engine.stop().await;
}

#[tokio::test]
async fn should_stop_listening_once_the_chosen_interface_is_gone() {
    let (_profile, engine, port) = started("").await;
    assert!(listens(port).await);

    choose(&engine, "no such interface");

    until_listening_is(port, false).await;
    assert_eq!(engine.listen_port(), 0);
    engine.stop().await;
}
