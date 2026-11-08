// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The servers through a running engine: added by hand, removed and given a priority.

#![allow(clippy::unwrap_used, clippy::expect_used)]

use crate::free_port;

use std::fs;
use std::time::Duration;

use njmule_engine::api::server_info::ServerInfo;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::server::server_list::{Address, Priority, STATIC_FILE_NAME};

const WAIT: Duration = Duration::from_secs(5);
const SERVER: &str = "81.2.3.4:4661";

#[tokio::test]
async fn should_add_a_server_given_by_hand_once() {
    let mut test = ServersEngine::new();
    test.given_a_running_engine("").await;
    test.given_the_server_is_added(SERVER).await;

    test.when_the_server_is_added(SERVER).await;

    test.then_the_answer_is(false);
    test.then_the_servers_are(&[SERVER]).await;
}

#[tokio::test]
async fn should_add_a_server_given_by_hand_with_a_high_priority_when_the_preferences_ask_for_it() {
    let mut test = ServersEngine::new();
    test.given_a_running_engine("server.manualHighPriority=true\n").await;

    test.when_the_server_is_added(SERVER).await;

    test.then_the_answer_is(true);
    test.then_the_priority_is(SERVER, Priority::High).await;
}

#[tokio::test]
async fn should_remove_a_server_from_the_static_list_too() {
    let mut test = ServersEngine::new();
    test.given_a_running_engine("").await;
    test.given_the_server_is_added(SERVER).await;
    test.given_the_server_is_static(SERVER).await;

    test.when_the_server_is_removed(SERVER).await;

    test.then_the_answer_is(true);
    test.then_the_servers_are(&[]).await;
    test.then_the_static_list_holds_no(SERVER);
}

#[tokio::test]
async fn should_set_the_priority_of_a_known_server() {
    let mut test = ServersEngine::new();
    test.given_a_running_engine("").await;
    test.given_the_server_is_added(SERVER).await;

    test.when_the_priority_is_set(SERVER, Priority::Low).await;

    test.then_the_answer_is(true);
    test.then_the_priority_is(SERVER, Priority::Low).await;
}

#[tokio::test]
async fn should_not_set_the_priority_of_an_unknown_server() {
    let mut test = ServersEngine::new();
    test.given_a_running_engine("").await;

    test.when_the_priority_is_set(SERVER, Priority::High).await;

    test.then_the_answer_is(false);
}

struct ServersEngine {
    profile: tempfile::TempDir,
    engine: Option<Engine>,
    answer: Option<bool>,
}

impl ServersEngine {
    fn new() -> Self {
        Self { profile: tempfile::tempdir().unwrap(), engine: None, answer: None }
    }

    async fn given_a_running_engine(&mut self, preferences: &str) {
        let dirs = self.directories();
        fs::create_dir_all(&dirs.config).unwrap();
        let preferences = format!("net.tcpPort={}\nnet.kad.enabled=false\n{preferences}", free_port::free_port());
        fs::write(dirs.config.join("njmule.properties"), preferences).unwrap();
        self.engine = Some(Engine::start(dirs).await.unwrap());
    }

    async fn given_the_server_is_added(&mut self, address: &str) {
        self.when_the_server_is_added(address).await;
        assert_eq!(self.answer, Some(true), "added");
    }

    async fn given_the_server_is_static(&self, address: &str) {
        assert!(within(self.engine().set_static_server(at(address), true)).await);
    }

    async fn when_the_server_is_added(&mut self, address: &str) {
        self.answer = Some(within(self.engine().add_server(at(address))).await);
    }

    async fn when_the_server_is_removed(&mut self, address: &str) {
        self.answer = Some(within(self.engine().remove_server(at(address))).await);
    }

    async fn when_the_priority_is_set(&mut self, address: &str, priority: Priority) {
        self.answer = Some(within(self.engine().set_server_priority(at(address), priority)).await);
    }

    fn then_the_answer_is(&self, answer: bool) {
        assert_eq!(self.answer, Some(answer));
    }

    async fn then_the_servers_are(&self, addresses: &[&str]) {
        let servers = within(self.engine().servers()).await;
        let known: Vec<&str> = servers.iter().map(|server| server.address.as_str()).collect();
        assert_eq!(known, addresses);
    }

    async fn then_the_priority_is(&self, address: &str, priority: Priority) {
        assert_eq!(self.server(address).await.priority, priority);
    }

    fn then_the_static_list_holds_no(&self, address: &str) {
        let statics = fs::read_to_string(self.directories().config.join(STATIC_FILE_NAME)).unwrap_or_default();
        assert!(!statics.contains(address), "{statics}");
    }

    async fn server(&self, address: &str) -> ServerInfo {
        let servers = within(self.engine().servers()).await;
        servers.into_iter().find(|server| server.address == address).expect("the server")
    }

    fn directories(&self) -> AppDirectories {
        AppDirectories::under_root(self.profile.path()).unwrap()
    }

    fn engine(&self) -> Engine {
        self.engine.clone().expect("a running engine")
    }
}

impl Drop for ServersEngine {
    fn drop(&mut self) {
        if let Some(engine) = self.engine.take() {
            tokio::spawn(async move { engine.stop().await });
        }
    }
}

fn at(address: &str) -> Address {
    Address::parse(address).unwrap()
}

async fn within<T>(work: impl Future<Output = T>) -> T {
    tokio::time::timeout(WAIT, work).await.expect("answered in time")
}
