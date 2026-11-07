// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNotesEngineTest, under its names: the engine searches Kad for notes on the files it knows, and
//! only while Kad is connected.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod free_port;

use std::fs;
use std::net::{Ipv4Addr, UdpSocket};
use std::time::Duration;

use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::kad_note::KadNote;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::{Engine, NotesError};
use rand::RngExt;

const FILM: [u8; 16] = [0xa1; 16];
const WAIT: Duration = Duration::from_secs(10);

#[tokio::test]
async fn should_not_search_while_kad_does_not_run() {
    let mut notes = KadNotesEngine::new();
    notes.given_an_engine_with_kad(false).await;
    notes.given_a_download_of(FILM).await;

    notes.when_kad_is_searched_for(FILM).await;

    notes.then_the_search_fails_because("Kad is not connected");
    notes.then_the_file_has_no_notes(FILM).await;
}

#[tokio::test]
async fn should_not_search_while_kad_knows_no_node() {
    let mut notes = KadNotesEngine::new();
    notes.given_an_engine_with_kad(true).await;
    notes.given_a_download_of(FILM).await;

    notes.when_kad_is_searched_for(FILM).await;

    notes.then_the_search_fails_because("Kad is not connected");
}

#[tokio::test]
async fn should_not_search_for_a_file_it_neither_downloads_nor_shares() {
    let mut notes = KadNotesEngine::new();
    notes.given_an_engine_with_kad(false).await;

    notes.when_kad_is_searched_for(FILM).await;

    notes.then_the_search_fails_because("Neither downloaded nor shared");
}

struct KadNotesEngine {
    profile: tempfile::TempDir,
    engine: Option<Engine>,
    search: Option<Result<Vec<KadNote>, NotesError>>,
}

impl KadNotesEngine {
    fn new() -> Self {
        Self { profile: tempfile::tempdir().unwrap(), engine: None, search: None }
    }

    async fn given_an_engine_with_kad(&mut self, kad: bool) {
        let dirs = AppDirectories::under_root(self.profile.path()).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let preferences = format!(
            "net.tcpPort={}\nnet.udpPort={}\nnet.kad.enabled={kad}\nnet.autoConnect=false\n",
            free_port::free_port(),
            free_udp_port()
        );
        fs::write(dirs.config.join("njmule.properties"), preferences).unwrap();
        self.engine = Some(Engine::start(dirs).await.unwrap());
    }

    async fn given_a_download_of(&self, file: [u8; 16]) {
        let link = FileLink::of("film.mkv", 5000, file).unwrap();
        tokio::time::timeout(WAIT, self.engine().add_download(link, None)).await.unwrap().unwrap();
    }

    async fn when_kad_is_searched_for(&mut self, file: [u8; 16]) {
        self.search = Some(tokio::time::timeout(WAIT, self.engine().search_kad_notes(file)).await.unwrap());
    }

    fn then_the_search_fails_because(&self, why: &str) {
        let refused = self.search.as_ref().unwrap().as_ref().expect_err("the search fails");
        assert!(refused.to_string().contains(why), "{refused} tells {why}");
    }

    async fn then_the_file_has_no_notes(&self, file: [u8; 16]) {
        assert!(tokio::time::timeout(WAIT, self.engine().kad_notes(file)).await.unwrap().is_empty());
    }

    fn engine(&self) -> &Engine {
        self.engine.as_ref().expect("a running engine")
    }
}

impl Drop for KadNotesEngine {
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
