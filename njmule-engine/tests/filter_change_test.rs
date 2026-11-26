// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Raising the IP filter level drops the server and blocked
//! sources.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::arithmetic_side_effects)]

use std::collections::BTreeMap;
use std::fs;
use std::net::{Ipv4Addr, TcpListener};
use std::time::Duration;

use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::persist::format::ip_filter_dat::{self, Range};
use njmule_engine::server::server_list::Address;
use njmule_engine::util::ipv4;
use tokio::sync::broadcast;

use crate::free_port;

#[tokio::test]
async fn should_drop_the_server_and_the_sources_that_a_stricter_filter_now_refuses() {
    let dir = tempfile::tempdir().unwrap();
    let dirs = AppDirectories::under_root(&dir.path().join("njmule")).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    let tcp_port = free_port::free_port();
    fs::write(
        dirs.config.join("njmule.properties"),
        format!("net.tcpPort={tcp_port}\nnet.kad.enabled=false\nsecurity.ipFilterLevel=40\n"),
    )
    .unwrap();
    let ranges = vec![Range {
        start: ipv4::of(127, 0, 0, 1),
        end: ipv4::of(127, 0, 0, 3),
        level: 50,
        description: "loopback".to_owned(),
    }];
    fs::write(dirs.config.join("ipfilter.dat"), ip_filter_dat::write(&ranges)).unwrap();

    let server = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let server_port = server.local_addr().unwrap().port();
    let engine = Engine::start(dirs).await.unwrap();
    let mut events = engine.subscribe().live;

    let film = FileLink::of("film.mkv", 5000, [0xa1; 16]).unwrap();
    engine.add_download(film.clone(), None).await.unwrap();
    assert!(engine.add_source(film.hash, std::net::SocketAddr::new(Ipv4Addr::new(127, 0, 0, 3).into(), 4662)).await);

    engine.connect_server(Some(Address::new(u32::from(Ipv4Addr::LOCALHOST), server_port))).await.unwrap();
    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerConnecting(_))).await);

    engine
        .update_preferences(&BTreeMap::from([("security.ipFilterLevel".to_owned(), "100".to_owned())]))
        .unwrap();

    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerDisconnected { .. })).await);
    assert!(engine.sources().await.is_empty(), "the source is dropped");

    engine.stop().await;
    drop(server);
}

async fn next_event(events: &mut broadcast::Receiver<EngineEvent>, predicate: impl Fn(&EngineEvent) -> bool) -> bool {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    while tokio::time::Instant::now() < deadline {
        match tokio::time::timeout(Duration::from_millis(100), events.recv()).await {
            Ok(Ok(event)) if predicate(&event) => return true,
            Ok(Ok(_) | Err(broadcast::error::RecvError::Lagged(_))) | Err(_) => {}
            Ok(Err(broadcast::error::RecvError::Closed)) => return false,
        }
    }
    false
}
