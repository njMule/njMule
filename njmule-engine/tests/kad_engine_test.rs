// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Kad in the engine: it runs on the UDP port of the preferences, waits while another program holds that port, stops by
//! hand or when turned off, and moves to a new port. Beyond the Java engine's examples, which read Kad's port in the
//! engine's status.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use crate::free_port;

use std::collections::BTreeMap;
use std::fs;
use std::net::{Ipv4Addr, UdpSocket};
use std::time::Duration;

use njmule_engine::api::kad_info::KadInfo;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::protocol::ed2k::client_messages::PartStatus;
use njmule_engine::protocol::ed2k::client_reasks::{self, Ping};
use njmule_engine::protocol::packet::opcodes::client_udp;
use njmule_engine::protocol::packet::{Packet, udp_framing};
use njmule_engine::search::search_manager::{Scope, StartError};
use rand::RngExt;

// Beyond the engine's own retry interval of 10 seconds.
const RETRY_WAIT: Duration = Duration::from_secs(25);

struct KadEngine {
    profile: tempfile::TempDir,
    udp_port: u16,
    other_program: Option<UdpSocket>,
    engine: Option<Engine>,
    kad: Option<KadInfo>,
    search: Option<Result<u64, StartError>>,
    client: Option<UdpSocket>,
}

impl KadEngine {
    fn new() -> Self {
        Self {
            profile: tempfile::tempdir().unwrap(),
            udp_port: 0,
            other_program: None,
            engine: None,
            kad: None,
            search: None,
            client: None,
        }
    }

    fn given_a_profile_with_kad(&mut self) {
        self.udp_port = free_udp_port();
        let dirs = AppDirectories::under_root(self.profile.path()).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let preferences = format!(
            "net.tcpPort={}\nnet.udpPort={}\nnet.kad.enabled=true\nnet.autoConnect=false\n",
            free_port::free_port(),
            self.udp_port
        );
        fs::write(dirs.config.join("njmule.properties"), preferences).unwrap();
    }

    fn given_another_program_holds_the_kad_port(&mut self) {
        self.other_program = Some(UdpSocket::bind((Ipv4Addr::UNSPECIFIED, self.udp_port)).unwrap());
    }

    async fn given_njmule_running(&mut self) {
        self.when_njmule_starts().await;
    }

    async fn when_njmule_starts(&mut self) {
        let dirs = AppDirectories::under_root(self.profile.path()).unwrap();
        self.engine = Some(Engine::start(dirs).await.unwrap());
        self.read_kad().await;
    }

    async fn when_the_other_program_frees_the_port(&mut self) {
        self.other_program = None;
    }

    async fn when_kad_is_stopped_by_hand(&mut self) {
        self.engine().disconnect_kad().await;
        self.read_kad().await;
    }

    async fn when_kad_is_joined_again(&mut self) {
        self.engine().connect_kad().await;
        self.read_kad().await;
    }

    async fn when_kad_is_turned_off_in_the_preferences(&mut self) {
        self.change("net.kad.enabled", "false");
        tokio::time::sleep(Duration::from_millis(500)).await;
        self.read_kad().await;
    }

    async fn when_the_udp_port_changes(&mut self) {
        self.udp_port = free_udp_port();
        self.change("net.udpPort", &self.udp_port.to_string());
    }

    fn given_a_profile_without_kad(&mut self) {
        self.given_a_profile_with_kad();
        let dirs = AppDirectories::under_root(self.profile.path()).unwrap();
        let file = dirs.config.join("njmule.properties");
        let preferences = fs::read_to_string(&file).unwrap().replace("net.kad.enabled=true", "net.kad.enabled=false");
        fs::write(file, preferences).unwrap();
    }

    async fn when_a_search_starts(&mut self, scope: Option<Scope>) {
        self.search = Some(self.engine().start_search("ubuntu desktop".to_owned(), scope).await);
    }

    fn then_the_search_is_refused_as_unavailable(&self, because: &str) {
        let refused = self.search.as_ref().unwrap();
        let reason = match refused {
            Err(StartError::Unavailable(reason)) => reason.as_str(),
            _ => "",
        };
        assert!(reason.contains(because), "refused as unavailable ({because}), not {refused:?}");
    }

    // A client asks again over UDP for a file we do not share.
    fn when_a_client_asks_again_over_udp_for_a_file_we_do_not_share(&mut self) {
        let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        client.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let ping = Ping { file: [0x77; 16], parts: PartStatus::COMPLETE, complete_sources: 0 };
        let datagram = udp_framing::encode(&ping.encode(client_reasks::UDP_VERSION).unwrap());
        client.send_to(&datagram, (Ipv4Addr::LOCALHOST, self.udp_port)).unwrap();
        self.client = Some(client);
    }

    fn then_the_client_hears_that_we_do_not_have_the_file(&self) {
        let mut buffer = [0u8; 1024];
        let (length, _) = self.client.as_ref().unwrap().recv_from(&mut buffer).expect("an answer in time");
        let answer: Packet = udp_framing::decode(&buffer[..length]).unwrap();
        assert_eq!(answer.opcode(), client_udp::FILENOTFOUND);
    }

    fn then_kad_runs_on_its_port(&self) {
        let kad = self.kad.as_ref().expect("Kad runs");
        assert_eq!(kad.udp_port, self.udp_port);
        assert!(!kad.connected, "no contacts to connect with");
        assert_eq!(kad.id.len(), 32);
    }

    fn then_kad_does_not_run(&self) {
        assert_eq!(self.kad, None);
    }

    async fn then_kad_runs_on_its_port_within(&mut self, wait: Duration) {
        let deadline = tokio::time::Instant::now() + wait;
        loop {
            self.read_kad().await;
            if self.kad.as_ref().is_some_and(|kad| kad.udp_port == self.udp_port) {
                return;
            }
            assert!(tokio::time::Instant::now() < deadline, "Kad on port {} in time", self.udp_port);
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
    }

    async fn then_the_port_is_free_again_within(&self, wait: Duration, port: u16) {
        let deadline = tokio::time::Instant::now() + wait;
        while UdpSocket::bind((Ipv4Addr::UNSPECIFIED, port)).is_err() {
            assert!(tokio::time::Instant::now() < deadline, "port {port} free in time");
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    }

    async fn read_kad(&mut self) {
        self.kad = self.engine().kad().await;
    }

    fn change(&self, key: &str, value: &str) {
        self.engine().update_preferences(&BTreeMap::from([(key.to_owned(), value.to_owned())])).unwrap();
    }

    fn engine(&self) -> &Engine {
        self.engine.as_ref().unwrap()
    }
}

// A UDP port free now, below every system's range for outgoing traffic, picked at random.
fn free_udp_port() -> u16 {
    let mut random = rand::rng();
    loop {
        let port = random.random_range(20_000..32_000);
        if UdpSocket::bind((Ipv4Addr::UNSPECIFIED, port)).is_ok() {
            return port;
        }
    }
}

#[tokio::test]
async fn should_run_kad_on_the_udp_port_of_the_preferences() {
    let mut kad = KadEngine::new();
    kad.given_a_profile_with_kad();

    kad.when_njmule_starts().await;

    kad.then_kad_runs_on_its_port();
}

#[tokio::test]
async fn should_not_run_kad_while_another_program_holds_its_port() {
    let mut kad = KadEngine::new();
    kad.given_a_profile_with_kad();
    kad.given_another_program_holds_the_kad_port();

    kad.when_njmule_starts().await;

    kad.then_kad_does_not_run();
}

#[tokio::test]
async fn should_start_kad_once_the_other_program_frees_its_port() {
    let mut kad = KadEngine::new();
    kad.given_a_profile_with_kad();
    kad.given_another_program_holds_the_kad_port();
    kad.given_njmule_running().await;

    kad.when_the_other_program_frees_the_port().await;

    kad.then_kad_runs_on_its_port_within(RETRY_WAIT).await;
}

#[tokio::test]
async fn should_stop_kad_by_hand_and_free_its_port() {
    let mut kad = KadEngine::new();
    kad.given_a_profile_with_kad();
    kad.given_njmule_running().await;

    kad.when_kad_is_stopped_by_hand().await;

    kad.then_kad_does_not_run();
    kad.then_the_port_is_free_again_within(Duration::from_secs(5), kad.udp_port).await;
}

#[tokio::test]
async fn should_run_kad_again_once_joined_after_a_stop_by_hand() {
    let mut kad = KadEngine::new();
    kad.given_a_profile_with_kad();
    kad.given_njmule_running().await;
    kad.when_kad_is_stopped_by_hand().await;
    kad.then_the_port_is_free_again_within(Duration::from_secs(5), kad.udp_port).await;

    kad.when_kad_is_joined_again().await;

    kad.then_kad_runs_on_its_port();
}

#[tokio::test]
async fn should_stop_kad_when_it_is_turned_off() {
    let mut kad = KadEngine::new();
    kad.given_a_profile_with_kad();
    kad.given_njmule_running().await;

    kad.when_kad_is_turned_off_in_the_preferences().await;

    kad.then_kad_does_not_run();
}

#[tokio::test]
async fn should_move_kad_to_a_new_udp_port() {
    let mut kad = KadEngine::new();
    kad.given_a_profile_with_kad();
    kad.given_njmule_running().await;

    kad.when_the_udp_port_changes().await;

    kad.then_kad_runs_on_its_port_within(Duration::from_secs(5)).await;
}

#[tokio::test]
async fn should_refuse_a_kad_search_while_kad_is_off() {
    let mut kad = KadEngine::new();
    kad.given_a_profile_without_kad();
    kad.given_njmule_running().await;

    kad.when_a_search_starts(Some(Scope::Kad)).await;

    kad.then_the_search_is_refused_as_unavailable("Kad is not running");
}

#[tokio::test]
async fn should_refuse_a_kad_search_with_nobody_to_ask() {
    let mut kad = KadEngine::new();
    kad.given_a_profile_with_kad();
    kad.given_njmule_running().await;

    kad.when_a_search_starts(Some(Scope::Kad)).await;

    kad.then_the_search_is_refused_as_unavailable("Kad cannot search");
}

#[tokio::test]
async fn should_refuse_an_automatic_search_while_no_network_is_connected() {
    let mut kad = KadEngine::new();
    kad.given_a_profile_with_kad();
    kad.given_njmule_running().await;

    kad.when_a_search_starts(None).await;

    kad.then_the_search_is_refused_as_unavailable("Not connected to a server or to Kad");
}

// The client waits for the answer on a thread of its own: the engine answers on another.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn should_answer_a_client_asking_again_over_kads_port_for_a_file_we_do_not_share() {
    let mut kad = KadEngine::new();
    kad.given_a_profile_with_kad();
    kad.given_njmule_running().await;

    kad.when_a_client_asks_again_over_udp_for_a_file_we_do_not_share();

    kad.then_the_client_hears_that_we_do_not_have_the_file();
}
