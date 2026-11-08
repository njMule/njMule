// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The IP filter through a running engine: read from ipfilter.dat at the start, and again at a new level.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

mod free_port;

use std::collections::BTreeMap;
use std::fs;
use std::time::Duration;

use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::engine::network::ConnectError;
use njmule_engine::server::server_list::Address;

const WAIT: Duration = Duration::from_secs(5);
// A range below the default level of 127, which blocks it.
const LOOPBACK_BLOCKED: &str = "127.0.0.0 - 127.255.255.255 , 100 , loopback\n";
const SERVER: &str = "127.0.0.1:4661";

#[tokio::test]
async fn should_refuse_a_server_the_ip_filter_blocks() {
    let mut test = IpFilterEngine::new();
    test.given_the_ip_filter(LOOPBACK_BLOCKED);
    test.given_a_running_engine().await;

    test.when_the_engine_connects_to(SERVER).await;

    test.then_the_connection_is_refused_with(&format!("The IP filter blocks {SERVER}"));
}

#[tokio::test]
async fn should_let_through_a_server_whose_range_the_new_level_no_longer_blocks() {
    let mut test = IpFilterEngine::new();
    test.given_the_ip_filter(LOOPBACK_BLOCKED);
    test.given_a_running_engine().await;
    test.given_the_level_is(50);

    test.when_the_engine_connects_to(SERVER).await;

    test.then_the_connection_is_tried();
}

struct IpFilterEngine {
    profile: tempfile::TempDir,
    engine: Option<Engine>,
    connected: Option<Result<(), ConnectError>>,
}

impl IpFilterEngine {
    fn new() -> Self {
        Self { profile: tempfile::tempdir().unwrap(), engine: None, connected: None }
    }

    fn given_the_ip_filter(&self, list: &str) {
        let config = self.directories().config;
        fs::create_dir_all(&config).unwrap();
        fs::write(config.join("ipfilter.dat"), list).unwrap();
    }

    // The loopback is no LAN address it filters.
    async fn given_a_running_engine(&mut self) {
        let dirs = self.directories();
        fs::create_dir_all(&dirs.config).unwrap();
        let preferences =
            format!("net.tcpPort={}\nnet.kad.enabled=false\nsecurity.filterLan=false\n", free_port::free_port());
        fs::write(dirs.config.join("njmule.properties"), preferences).unwrap();
        self.engine = Some(Engine::start(dirs).await.unwrap());
    }

    fn given_the_level_is(&self, level: u32) {
        let changes = BTreeMap::from([("security.ipFilterLevel".to_owned(), level.to_string())]);
        self.engine().update_preferences(&changes).unwrap();
    }

    async fn when_the_engine_connects_to(&mut self, address: &str) {
        let address = Address::parse(address).unwrap();
        self.connected = Some(within(self.engine().connect_server(Some(address))).await);
    }

    fn then_the_connection_is_refused_with(&self, reason: &str) {
        match self.connected.as_ref().unwrap() {
            Err(ConnectError::Refused(refused)) => assert_eq!(refused, reason),
            other => panic!("not refused: {other:?}"),
        }
    }

    fn then_the_connection_is_tried(&self) {
        assert!(self.connected.as_ref().unwrap().is_ok(), "{:?}", self.connected);
    }

    fn directories(&self) -> AppDirectories {
        AppDirectories::under_root(self.profile.path()).unwrap()
    }

    fn engine(&self) -> Engine {
        self.engine.clone().expect("a running engine")
    }
}

impl Drop for IpFilterEngine {
    fn drop(&mut self) {
        if let Some(engine) = self.engine.take() {
            tokio::spawn(async move { engine.stop().await });
        }
    }
}

async fn within<T>(work: impl Future<Output = T>) -> T {
    tokio::time::timeout(WAIT, work).await.expect("answered in time")
}
