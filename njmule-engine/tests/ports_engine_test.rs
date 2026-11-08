// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The test of our ports through a running engine: the server it logs in to again, and what nothing could test.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::arithmetic_side_effects)]

mod free_port;
mod scripted_server;

use std::fs;
use std::time::{Duration, Instant};

use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::nj_mule_engine::Subscription;
use njmule_engine::api::port_test::{Forwarding, PortTest, Reach};
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::server::server_list::Address;
use scripted_server::{HIGH_ID, ScriptedServer};

const WAIT: Duration = Duration::from_secs(10);

#[tokio::test]
async fn should_find_the_tcp_port_open_when_the_server_logs_us_in_again_with_a_high_id() {
    let mut test = PortsEngine::new();
    test.given_a_server_giving(HIGH_ID).await;
    test.given_a_running_engine().await;
    test.given_the_engine_is_connected_to_the_server().await;

    test.when_the_ports_are_tested().await;

    test.then_the_test_ends_with(Reach::Open, Reach::Unknown).await;
    test.then_the_server_was_logged_in_to(2);
    test.then_the_log_says("Port test: TCP ").await;
}

#[tokio::test]
async fn should_tell_at_once_what_nothing_can_test_as_unknown() {
    let mut test = PortsEngine::new();
    test.given_a_running_engine().await;

    test.when_the_ports_are_tested().await;

    test.then_the_answer_is(Reach::Unknown, Reach::Unknown, true);
}

#[tokio::test]
async fn should_go_on_with_the_test_running_on_the_same_ports() {
    let mut test = PortsEngine::new();
    test.given_a_server_giving(0).await;
    test.given_a_running_engine().await;
    test.given_the_engine_tries_the_server().await;
    test.given_the_ports_are_tested().await;

    test.when_the_ports_are_tested().await;

    test.then_the_answer_is(Reach::Testing, Reach::Unknown, false);
    test.then_the_log_says_once("Testing the ports").await;
}

struct PortsEngine {
    profile: tempfile::TempDir,
    server: Option<ScriptedServer>,
    engine: Option<Engine>,
    events: Option<Subscription>,
    logs: Vec<String>,
    answer: Option<PortTest>,
}

impl PortsEngine {
    fn new() -> Self {
        Self {
            profile: tempfile::tempdir().unwrap(),
            server: None,
            engine: None,
            events: None,
            logs: Vec::new(),
            answer: None,
        }
    }

    // A client id of 0 leaves the login unanswered.
    async fn given_a_server_giving(&mut self, client_id: u32) {
        self.server = Some(ScriptedServer::start(client_id, 0).await);
    }

    // The loopback is no LAN address it filters.
    async fn given_a_running_engine(&mut self) {
        let dirs = AppDirectories::under_root(self.profile.path()).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let preferences = format!(
            "net.tcpPort={}\nnet.kad.enabled=false\nsecurity.filterLan=false\nsecurity.obfuscation=disabled\n",
            free_port::free_port()
        );
        fs::write(dirs.config.join("njmule.properties"), preferences).unwrap();
        let engine = Engine::start(dirs).await.unwrap();
        self.events = Some(engine.subscribe());
        self.engine = Some(engine);
    }

    async fn given_the_engine_is_connected_to_the_server(&mut self) {
        self.given_the_engine_tries_the_server().await;
        self.until(|event| matches!(event, EngineEvent::ServerConnected { .. })).await;
    }

    async fn given_the_engine_tries_the_server(&self) {
        let server = self.server.as_ref().unwrap();
        let address = Address::parse(&format!("127.0.0.1:{}", server.port())).unwrap();
        within(self.engine().connect_server(Some(address))).await.unwrap();
    }

    async fn given_the_ports_are_tested(&mut self) {
        self.when_the_ports_are_tested().await;
    }

    async fn when_the_ports_are_tested(&mut self) {
        self.answer = within(self.engine().test_ports()).await;
    }

    async fn then_the_test_ends_with(&self, tcp: Reach, udp: Reach) {
        let deadline = Instant::now() + WAIT;
        loop {
            let test = within(self.engine().port_test()).await.expect("a test");
            if test.done {
                assert_eq!((test.tcp, test.udp, test.forwarding), (tcp, udp, Forwarding::Off));
                return;
            }
            assert!(Instant::now() < deadline, "the test still runs: {test:?}");
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    }

    fn then_the_server_was_logged_in_to(&self, times: u32) {
        assert_eq!(self.server.as_ref().unwrap().connections(), times);
    }

    fn then_the_answer_is(&self, tcp: Reach, udp: Reach, done: bool) {
        let answer = self.answer.as_ref().expect("an answer");
        assert_eq!((answer.tcp, answer.udp, answer.forwarding, answer.done), (tcp, udp, Forwarding::Off, done));
        assert_eq!(answer.udp_port, 0, "Kad does not run");
    }

    async fn then_the_log_says(&mut self, start: &str) {
        self.until(|event| matches!(event, EngineEvent::Log(line) if line.message.starts_with(start))).await;
    }

    // A line of our own marks the end of what was logged before it.
    async fn then_the_log_says_once(&mut self, start: &str) {
        self.engine().user_log(njmule_engine::api::log_line::Level::Info, "done");
        self.until(|event| matches!(event, EngineEvent::Log(line) if line.message == "done")).await;
        assert_eq!(self.logs.iter().filter(|message| message.starts_with(start)).count(), 1, "{:?}", self.logs);
    }

    // Reads the events, the log kept, until one is the one wanted.
    async fn until(&mut self, wanted: impl Fn(&EngineEvent) -> bool) {
        let events = self.events.as_mut().unwrap();
        let mut missed = std::mem::take(&mut events.missed).into_iter();
        let deadline = Instant::now() + WAIT;
        loop {
            let event = match missed.next() {
                Some(event) => event,
                None => {
                    let left = deadline.saturating_duration_since(Instant::now());
                    tokio::time::timeout(left, events.live.recv()).await.expect("the event in time").unwrap()
                }
            };
            if let EngineEvent::Log(line) = &event {
                self.logs.push(line.message.clone());
            }
            if wanted(&event) {
                events.missed = missed.collect();
                return;
            }
        }
    }

    fn engine(&self) -> Engine {
        self.engine.clone().expect("a running engine")
    }
}

impl Drop for PortsEngine {
    fn drop(&mut self) {
        if let Some(engine) = self.engine.take() {
            tokio::spawn(async move { engine.stop().await });
        }
    }
}

async fn within<T>(work: impl Future<Output = T>) -> T {
    tokio::time::timeout(WAIT, work).await.expect("answered in time")
}
