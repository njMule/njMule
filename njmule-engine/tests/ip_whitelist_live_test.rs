// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's IpWhitelistLiveTest, under its names: live IP whitelist updates against a blocking IP filter.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::arithmetic_side_effects)]

use std::collections::BTreeMap;
use std::fs;
use std::net::{Ipv4Addr, TcpListener};
use std::time::Duration;

use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::persist::format::ip_filter_dat::{self, Range};
use njmule_engine::server::server_list::Address;
use njmule_engine::util::ipv4;
use tokio::sync::broadcast;

use crate::free_port;

#[tokio::test]
async fn should_refuse_a_blocked_server_without_whitelist() {
    let mut lab = Lab::new("").await;

    lab.when_the_server_is_connected().await;

    lab.then_the_connection_is_refused();
    lab.stop().await;
}

#[tokio::test]
async fn should_connect_to_a_blocked_server_once_it_is_always_allowed() {
    let mut lab = Lab::new("").await;
    lab.when_the_whitelist_is_set_to("127.0.0.0/30").await;

    lab.when_the_server_is_connected().await;

    lab.then_the_engine_connects().await;
    lab.stop().await;
}

#[tokio::test]
async fn should_leave_the_server_once_the_whitelist_no_longer_holds_it() {
    let mut lab = Lab::new("127.0.0.1").await;
    lab.when_the_server_is_connected().await;
    lab.then_the_engine_connects().await;

    lab.when_the_whitelist_is_set_to("# nobody now").await;

    lab.then_the_server_is_left().await;
    lab.stop().await;
}

struct Lab {
    _dir: tempfile::TempDir,
    _server: TcpListener,
    server_port: u16,
    engine: Engine,
    events: broadcast::Receiver<EngineEvent>,
    failure: Option<String>,
}

impl Lab {
    async fn new(whitelist: &str) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let dirs = AppDirectories::under_root(&dir.path().join("njmule")).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let tcp_port = free_port::free_port();
        fs::write(
            dirs.config.join("njmule.properties"),
            format!("net.tcpPort={tcp_port}\nnet.kad.enabled=false\nsecurity.ipFilterWhitelist={whitelist}\n"),
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
        let events = engine.subscribe().live;
        Self { _dir: dir, _server: server, server_port, engine, events, failure: None }
    }

    async fn when_the_whitelist_is_set_to(&mut self, whitelist: &str) {
        self.engine
            .update_preferences(&BTreeMap::from([("security.ipFilterWhitelist".to_owned(), whitelist.to_owned())]))
            .unwrap();
    }

    async fn when_the_server_is_connected(&mut self) {
        self.failure = self
            .engine
            .connect_server(Some(Address::new(u32::from(Ipv4Addr::LOCALHOST), self.server_port)))
            .await
            .err()
            .map(|e| format!("{e:?}"));
    }

    fn then_the_connection_is_refused(&self) {
        let err = self.failure.as_deref().expect("connection should be refused");
        assert!(err.contains("IP filter"), "unexpected error: {err}");
    }

    async fn then_the_engine_connects(&mut self) {
        assert!(self.failure.is_none(), "unexpected error: {:?}", self.failure);
        assert!(self.next_event(|e| matches!(e, EngineEvent::ServerConnecting(_))).await);
    }

    async fn then_the_server_is_left(&mut self) {
        assert!(self.next_event(|e| matches!(e, EngineEvent::ServerDisconnected { .. })).await);
    }

    async fn next_event(&mut self, predicate: impl Fn(&EngineEvent) -> bool) -> bool {
        let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        while tokio::time::Instant::now() < deadline {
            match tokio::time::timeout(Duration::from_millis(100), self.events.recv()).await {
                Ok(Ok(event)) if predicate(&event) => return true,
                Ok(Ok(_) | Err(broadcast::error::RecvError::Lagged(_))) | Err(_) => {}
                Ok(Err(broadcast::error::RecvError::Closed)) => return false,
            }
        }
        false
    }

    async fn stop(self) {
        self.engine.stop().await;
    }
}
