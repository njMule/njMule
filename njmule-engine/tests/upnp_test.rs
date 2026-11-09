// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's UpnpTest, under its names, and the examples of its NetworkTest about a router that answers late:
//! UPnP through a running engine, with a gateway on the loopback (the real router is never asked).

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fs;
use std::time::{Duration, Instant};

use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::nj_mule_engine::Subscription;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::engine::switches::{Switches, Upnp};
use njmule_engine::server::server_list::Address;

use crate::fake_gateway::FakeGateway;
use crate::free_port;
use crate::scripted_server::{HIGH_ID, ScriptedServer};

const IP: &str = "urn:schemas-upnp-org:service:WANIPConnection:1";
const WAIT: Duration = Duration::from_secs(15);
// A router that answered no search is looked for again this soon.
const RETRY: Duration = Duration::from_millis(100);

#[tokio::test]
async fn should_tell_in_the_status_that_the_router_forwards_the_ports() {
    let mut test = Routing::new();
    test.given_a_router().await;

    test.when_an_engine_starts_with_upnp_on().await;

    test.then_the_status_tells_the_router_answered("forwarded").await;
}

#[tokio::test]
async fn should_tell_in_the_status_that_upnp_is_off_once_turned_off() {
    let mut test = Routing::new();
    test.given_a_router().await;
    test.given_an_engine_that_mapped_its_port_on_the_router().await;

    test.when_upnp_is_turned_off();

    test.then_the_status_tells_the_router_answered("off").await;
}

// A router that takes long to answer must not hold the saves back.
#[tokio::test]
async fn should_save_the_files_at_the_end_before_the_router_is_asked() {
    let mut test = Routing::new();
    test.given_a_router().await;
    test.given_the_router_takes_seconds_to_delete_a_mapping(8);
    test.given_an_engine_that_mapped_its_port_on_the_router().await;

    test.when_the_engine_stops().await;

    test.then_the_server_list_was_saved_before_the_router_was_asked_to_delete_the_mapping();
}

#[tokio::test]
async fn should_log_in_again_and_recheck_kad_once_the_router_answers_a_later_search() {
    let mut test = Routing::new();
    test.given_kad_is_enabled();
    test.given_a_router_that_answers_no_search().await;
    test.given_a_login_to(12_345).await;
    test.given_the_server_got_a_login().await;
    test.given_the_log_tells("UPnP, NAT-PMP and PCP: no router answered").await;

    test.when_the_router_answers_again();

    test.then_the_server_got_a_login_again().await;
    test.then_the_log_tells("UPnP: the router answered at last").await;
    test.then_the_log_tells("Kad: checking the firewall again").await;
}

#[tokio::test]
async fn should_not_log_in_again_when_the_router_answers_late_to_a_high_id() {
    let mut test = Routing::new();
    test.given_a_router_that_answers_no_search().await;
    test.given_a_login_to(HIGH_ID).await;
    test.given_the_server_got_a_login().await;
    test.given_the_log_tells("UPnP, NAT-PMP and PCP: no router answered").await;

    test.when_the_router_answers_again();

    test.then_the_log_tells("UPnP: TCP").await;
    test.then_the_server_got_no_login_again().await;
}

/// A gateway on the loopback, an engine that asks it, a server it logs in to, and the user's log as it came.
struct Routing {
    profile: tempfile::TempDir,
    gateway: Option<FakeGateway>,
    server: Option<ScriptedServer>,
    engine: Option<Engine>,
    events: Option<Subscription>,
    kad: bool,
}

impl Routing {
    fn new() -> Self {
        Self {
            profile: tempfile::tempdir().unwrap(),
            gateway: None,
            server: None,
            engine: None,
            events: None,
            kad: false,
        }
    }

    fn gateway(&self) -> &FakeGateway {
        self.gateway.as_ref().unwrap()
    }

    fn engine(&self) -> Engine {
        self.engine.clone().expect("a running engine")
    }

    fn dirs(&self) -> AppDirectories {
        AppDirectories::under_root(self.profile.path()).unwrap()
    }

    async fn given_a_router(&mut self) {
        self.gateway = Some(FakeGateway::start(IP, "/ctl").await);
    }

    fn given_kad_is_enabled(&mut self) {
        self.kad = true;
    }

    async fn given_a_router_that_answers_no_search(&mut self) {
        self.given_a_router().await;
        self.gateway().stop_answering();
    }

    fn given_the_router_takes_seconds_to_delete_a_mapping(&self, seconds: u64) {
        self.gateway().delay_deletes(Duration::from_secs(seconds));
    }

    async fn given_an_engine_that_mapped_its_port_on_the_router(&mut self) {
        self.when_an_engine_starts_with_upnp_on().await;
        assert!(until(|| self.gateway().actions_named("AddPortMapping").len() == 1).await);
    }

    // The loopback is no LAN address it filters.
    async fn given_a_login_to(&mut self, client_id: u32) {
        self.server = Some(ScriptedServer::start(client_id, 0).await);
        self.when_an_engine_starts_with_upnp_on().await;
        let address = Address::parse(&format!("127.0.0.1:{}", self.server.as_ref().unwrap().port())).unwrap();
        tokio::time::timeout(WAIT, self.engine().connect_server(Some(address))).await.unwrap().unwrap();
    }

    async fn given_the_server_got_a_login(&self) {
        assert!(until(|| self.server.as_ref().unwrap().connections() == 1).await, "a login");
    }

    async fn given_the_log_tells(&mut self, text: &str) {
        self.then_the_log_tells(text).await;
    }

    async fn when_an_engine_starts_with_upnp_on(&mut self) {
        let dirs = self.dirs();
        fs::create_dir_all(&dirs.config).unwrap();
        let preferences = format!(
            "net.tcpPort={}\nnet.udpPort={}\nnet.kad.enabled={}\nnet.upnp=true\nsecurity.filterLan=false\n\
             security.obfuscation=disabled\n",
            free_port::free_port(),
            free_port::free_port(),
            self.kad
        );
        fs::write(dirs.config.join("njmule.properties"), preferences).unwrap();
        let switches = Switches { upnp: Upnp::At(self.gateway().ssdp(), RETRY), ..Switches::QUIET };
        let engine = Engine::start_with(dirs, switches).await.unwrap();
        self.events = Some(engine.subscribe());
        self.engine = Some(engine);
    }

    fn when_upnp_is_turned_off(&self) {
        let changes = [("net.upnp".to_owned(), "false".to_owned())].into_iter().collect();
        self.engine().update_preferences(&changes).unwrap();
    }

    async fn when_the_engine_stops(&mut self) {
        self.engine.take().unwrap().stop().await;
    }

    fn when_the_router_answers_again(&self) {
        self.gateway().answer_again();
    }

    // The router is named once it answered; never while UPnP is off.
    async fn then_the_status_tells_the_router_answered(&self, answer: &str) {
        let engine = self.engine();
        let deadline = Instant::now() + WAIT;
        loop {
            let status = engine.status().await.expect("a status");
            if status.forwarding == answer && status.router.is_empty() == (answer == "off") {
                return;
            }
            assert!(Instant::now() < deadline, "the status tells {answer}: {} {:?}", status.forwarding, status.router);
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }

    fn then_the_server_list_was_saved_before_the_router_was_asked_to_delete_the_mapping(&self) {
        let delete = &self.gateway().actions_named("DeletePortMapping")[0];
        let saved = fs::metadata(self.dirs().config.join("server.met")).unwrap().modified().unwrap();
        assert!(saved < delete.when + Duration::from_secs(1));
    }

    async fn then_the_server_got_a_login_again(&self) {
        assert!(until(|| self.server.as_ref().unwrap().connections() == 2).await, "a new login checks the port");
    }

    async fn then_the_server_got_no_login_again(&self) {
        tokio::time::sleep(Duration::from_secs(1)).await;
        assert_eq!(self.server.as_ref().unwrap().connections(), 1);
    }

    async fn then_the_log_tells(&mut self, text: &str) {
        let events = self.events.as_mut().unwrap();
        let mut missed = std::mem::take(&mut events.missed).into_iter();
        let deadline = Instant::now() + WAIT;
        loop {
            let event = match missed.next() {
                Some(event) => event,
                None => {
                    let left = deadline.saturating_duration_since(Instant::now());
                    let received = tokio::time::timeout(left, events.live.recv()).await;
                    received.expect("the log tells it in time").unwrap()
                }
            };
            if matches!(&event, EngineEvent::Log(line) if line.message.contains(text)) {
                events.missed = missed.collect();
                return;
            }
        }
    }
}

impl Drop for Routing {
    fn drop(&mut self) {
        if let Some(engine) = self.engine.take() {
            tokio::spawn(async move { engine.stop().await });
        }
    }
}

async fn until(condition: impl Fn() -> bool) -> bool {
    let deadline = Instant::now() + WAIT;
    while Instant::now() < deadline {
        if condition() {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    false
}
