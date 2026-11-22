// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Twin of Java's `DefaultListsEngineTest`: the shipped server list, with both networks off.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::collections::BTreeSet;
use std::fs;
use std::net::Ipv4Addr;

use njmule_engine::api::server_info::ServerInfo;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::switches::Switches;
use njmule_engine::engine::{Engine, default_lists};
use njmule_engine::persist::format::server_met;
use tempfile::TempDir;

use crate::free_port::free_port;

struct State {
    dir: TempDir,
    dirs: Option<AppDirectories>,
    servers: Vec<ServerInfo>,
}

impl State {
    fn new() -> Self {
        Self { dir: TempDir::new().unwrap(), dirs: None, servers: Vec::new() }
    }

    fn given_a_new_profile_without_networks(&mut self) {
        let dirs = AppDirectories::under_root(&self.dir.path().join("njmule")).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let port = free_port();
        fs::write(
            dirs.config.join("njmule.properties"),
            format!("net.tcpPort={port}\nnet.ed2k.enabled=false\nnet.kad.enabled=false\n"),
        )
        .unwrap();
        self.dirs = Some(dirs);
    }

    fn given_an_empty_server_list(&self) {
        let dirs = self.dirs.as_ref().unwrap();
        fs::write(dirs.config.join("server.met"), server_met::write(&[]).unwrap()).unwrap();
    }

    async fn when_the_engine_starts(&mut self) {
        let dirs = self.dirs.clone().unwrap();
        let switches = Switches { default_lists: true, ..Switches::QUIET };
        let engine = Engine::start_with(dirs, switches).await.unwrap();
        self.servers = engine.servers().await;
        engine.stop().await;
    }

    fn then_it_knows_the_shipped_servers(&self) {
        let actual: BTreeSet<String> = self.servers.iter().map(|s| s.address.clone()).collect();
        let expected: BTreeSet<String> =
            default_lists::servers().into_iter().map(|s| format!("{}:{}", Ipv4Addr::from(s.ip), s.port)).collect();
        assert_eq!(actual, expected);
    }

    fn then_it_knows_no_server(&self) {
        assert!(self.servers.is_empty());
    }
}

#[tokio::test]
async fn should_start_a_new_profile_with_the_shipped_servers() {
    let mut t = State::new();
    t.given_a_new_profile_without_networks();
    t.when_the_engine_starts().await;
    t.then_it_knows_the_shipped_servers();
}

#[tokio::test]
async fn should_keep_empty_the_server_list_a_profile_emptied() {
    let mut t = State::new();
    t.given_a_new_profile_without_networks();
    t.given_an_empty_server_list();
    t.when_the_engine_starts().await;
    t.then_it_knows_no_server();
}
