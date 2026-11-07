// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadPortEngineTest, under its names: Kad whose UDP port another program holds waits, tries the
//! port again, and starts once the port is free.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::arithmetic_side_effects)]

mod free_port;

use std::collections::BTreeMap;
use std::fs;
use std::net::{Ipv4Addr, UdpSocket};
use std::path::PathBuf;
use std::time::{Duration, Instant};

use njmule_engine::api::engine_status::EngineStatus;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::kad::kad_identity;
use rand::RngExt;

// Beyond the engine's own retry interval of 10 seconds.
const RETRY_WAIT: Duration = Duration::from_secs(25);

#[tokio::test]
async fn should_tell_the_kad_port_another_program_holds() {
    let mut kad = KadPort::new();
    kad.given_a_profile_with_kad();
    kad.given_another_program_holds_the_kad_port();

    kad.when_nj_mule_starts().await;

    kad.then_kad_waits_for_its_port();
    kad.then_kad_does_not_run();
}

#[tokio::test]
async fn should_start_kad_once_the_other_program_frees_its_port() {
    let mut kad = KadPort::new();
    kad.given_a_profile_with_kad();
    kad.given_another_program_holds_the_kad_port();
    kad.given_nj_mule_running().await;

    kad.when_the_other_program_frees_the_port();

    kad.then_kad_runs_within(RETRY_WAIT).await;
    kad.then_kad_waits_for_no_port();
}

#[tokio::test]
async fn should_stop_waiting_for_the_kad_port_when_kad_is_stopped_by_hand() {
    let mut kad = KadPort::new();
    kad.given_a_profile_with_kad();
    kad.given_another_program_holds_the_kad_port();
    kad.given_nj_mule_running().await;

    kad.when_kad_is_stopped_by_hand().await;

    kad.then_kad_waits_for_no_port();
}

#[tokio::test]
async fn should_stop_waiting_for_the_kad_port_when_kad_is_turned_off() {
    let mut kad = KadPort::new();
    kad.given_a_profile_with_kad();
    kad.given_another_program_holds_the_kad_port();
    kad.given_nj_mule_running().await;

    kad.when_kad_is_turned_off_in_the_preferences().await;

    kad.then_kad_waits_for_no_port();
}

#[tokio::test]
async fn should_not_wait_for_a_port_when_kad_cannot_start_for_another_reason() {
    let mut kad = KadPort::new();
    kad.given_a_profile_with_kad();
    kad.given_kad_cannot_write_its_key_file();

    kad.when_nj_mule_starts().await;

    kad.then_kad_waits_for_no_port();
    kad.then_kad_does_not_run();
}

struct KadPort {
    dir: tempfile::TempDir,
    udp_port: u16,
    other_program: Option<UdpSocket>,
    engine: Option<Engine>,
    status: Option<EngineStatus>,
}

impl KadPort {
    fn new() -> Self {
        Self { dir: tempfile::tempdir().unwrap(), udp_port: 0, other_program: None, engine: None, status: None }
    }

    fn given_a_profile_with_kad(&mut self) {
        let dirs = self.dirs();
        fs::create_dir_all(&dirs.config).unwrap();
        self.udp_port = free_udp_port();
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

    // A folder that is not empty where Kad writes its UDP key: the write fails, the port is free.
    fn given_kad_cannot_write_its_key_file(&self) {
        let key: PathBuf = self.dirs().config.join(kad_identity::KEY_FILE_NAME);
        fs::create_dir_all(&key).unwrap();
        fs::write(key.join("inside.txt"), "taken").unwrap();
    }

    async fn given_nj_mule_running(&mut self) {
        self.start_nj_mule().await;
    }

    async fn when_nj_mule_starts(&mut self) {
        self.start_nj_mule().await;
    }

    fn when_the_other_program_frees_the_port(&mut self) {
        self.other_program = None;
    }

    async fn when_kad_is_stopped_by_hand(&mut self) {
        self.engine().disconnect_kad().await;
        self.read_status().await;
    }

    async fn when_kad_is_turned_off_in_the_preferences(&mut self) {
        let changes = BTreeMap::from([("net.kad.enabled".to_owned(), "false".to_owned())]);
        self.engine().update_preferences(&changes).unwrap();
        self.read_status().await;
    }

    fn then_kad_waits_for_its_port(&self) {
        assert_eq!(self.status().kad_port_taken, Some(self.udp_port));
    }

    fn then_kad_waits_for_no_port(&self) {
        assert_eq!(self.status().kad_port_taken, None);
    }

    fn then_kad_does_not_run(&self) {
        assert_eq!(self.status().kad, None);
    }

    async fn then_kad_runs_within(&mut self, wait: Duration) {
        let end = Instant::now() + wait;
        loop {
            tokio::time::sleep(Duration::from_millis(500)).await;
            self.read_status().await;
            if self.status().kad.is_some() || Instant::now() > end {
                break;
            }
        }
        assert!(self.status().kad.is_some(), "Kad started on the freed port");
    }

    async fn start_nj_mule(&mut self) {
        self.engine = Some(Engine::start(self.dirs()).await.unwrap());
        self.read_status().await;
    }

    async fn read_status(&mut self) {
        self.status = tokio::time::timeout(Duration::from_secs(5), self.engine().status()).await.unwrap();
    }

    fn status(&self) -> &EngineStatus {
        self.status.as_ref().expect("a status")
    }

    fn engine(&self) -> Engine {
        self.engine.clone().expect("a running engine")
    }

    fn dirs(&self) -> AppDirectories {
        AppDirectories::under_root(&self.dir.path().join("njmule")).unwrap()
    }
}

impl Drop for KadPort {
    fn drop(&mut self) {
        if let Some(engine) = self.engine.take() {
            tokio::spawn(async move { engine.stop().await });
        }
    }
}

fn free_udp_port() -> u16 {
    let mut random = rand::rng();
    loop {
        let port = random.random_range(20_000..32_000);
        if UdpSocket::bind((Ipv4Addr::UNSPECIFIED, port)).is_ok() {
            return port;
        }
    }
}
