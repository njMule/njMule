// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's DownloadObfuscationTest, under its names: whom the downloads connect to, and ask to call back, by
//! the obfuscation they want and the source takes.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::arithmetic_side_effects,
    clippy::indexing_slicing
)]

mod download_lab;
mod scripted_source;

use std::time::{Duration, Instant};

use download_lab::Downloads;
use njmule_engine::api::download_info::SourceState;
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::download::download_manager::REASK_INTERVAL;
use njmule_engine::protocol::ed2k::client_messages::FileStatus;
use njmule_engine::protocol::ed2k::server_messages::{FoundSources, Source as Found};
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, protocol};
use scripted_source::{ScriptedSource, WAIT};

const OUR_ID: u32 = u32::from_le_bytes([198, 51, 100, 7]);
const PEER: [u8; 16] = [0x5c; 16];
const FILE: [u8; 16] = [0x64; 16];
const FIREWALLED: u32 = 4242;
// Supports, requests and requires obfuscation, in the hello.
const REQUIRES_OBFUSCATION: u32 = 0x07 << 7;
const SUPPORTS: u8 = 0x01;
const QUIET: Duration = Duration::from_secs(1);

struct Obfuscation {
    mode: ObfuscationMode,
    downloads: Option<Downloads>,
    source: Option<ScriptedSource>,
}

impl Obfuscation {
    fn new() -> Self {
        Self { mode: ObfuscationMode::Supported, downloads: None, source: None }
    }

    fn given_obfuscation(&mut self, wanted: ObfuscationMode) {
        self.mode = wanted;
    }

    async fn given_a_download(&mut self) {
        self.open();
        self.downloads().add(FileLink::of("firewalled.bin", 5000, FILE).unwrap()).await;
    }

    // Asked once, plain: it queues us and the connection closes, leaving its user hash and connect options known.
    async fn given_a_source_asked_once_that_announces(&mut self, misc2: u32) {
        let source = ScriptedSource::new(PEER, 0, misc2).await;
        self.open();
        self.downloads().add(source.link("plain.bin", 5000, FILE)).await;
        {
            let mut link = source.accept().await;
            link.until(protocol::EDONKEY, client::SETREQFILEID).await;
            link.send(&FileStatus { file: FILE, part_count: 0, parts: Vec::new() }.encode().unwrap()).await;
            link.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
            let mut ranking = PacketWriter::new();
            ranking.u16(5).bytes(&[0; 10]);
            link.send(&Packet::written(protocol::EMULE, extended::QUEUERANKING, ranking)).await;
            self.await_source(SourceState::Queued).await;
        }
        self.await_source(SourceState::Waiting).await;
        self.source = Some(source);
    }

    async fn when_the_source_is_due_again_with_obfuscation(&mut self, wanted: ObfuscationMode) {
        self.downloads().run(move |lab| lab.settings.obfuscation = wanted).await;
        self.downloads().advance(REASK_INTERVAL);
    }

    async fn when_the_server_finds_a_firewalled_source_with_the_connect_options(&mut self, crypt_options: u8) {
        let found = FoundSources {
            file: FILE,
            sources: vec![Found {
                client_id: FIREWALLED,
                port: download_lab::PORT,
                crypt_options: crypt_options | Found::HAS_USER_HASH,
                user_hash: Some(PEER),
            }],
        };
        self.downloads()
            .run(move |lab| {
                let (settings, ours) = (lab.settings.clone(), lab.ours.clone());
                lab.manager.found_sources(&found, &settings, OUR_ID, &ours);
            })
            .await;
    }

    async fn then_the_source_is_connected_to(&self, expected: bool) {
        let wait = if expected { WAIT } else { QUIET };
        assert_eq!(self.source.as_ref().unwrap().is_connected_to_within(wait).await, expected);
    }

    async fn then_the_server_is_asked_for_callbacks(&self, count: usize) {
        if count > 0 {
            let deadline = Instant::now() + WAIT;
            while self.callbacks() < count {
                assert!(Instant::now() < deadline, "{count} callbacks");
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        } else {
            tokio::time::sleep(QUIET).await;
        }
        assert_eq!(self.callbacks(), count);
    }

    fn open(&mut self) {
        let mut settings = Downloads::settings();
        settings.obfuscation = self.mode;
        self.downloads = Some(Downloads::start([0xab; 16], OUR_ID, settings));
    }

    fn downloads(&self) -> &Downloads {
        self.downloads.as_ref().unwrap()
    }

    fn callbacks(&self) -> usize {
        self.downloads().server.lock().unwrap().callbacks.len()
    }

    async fn await_source(&self, state: SourceState) {
        self.downloads()
            .wait_for("the source", move |lab| lab.manager.sources().iter().any(|s| s.state == state))
            .await;
    }
}

#[tokio::test]
async fn should_ask_a_source_that_cannot_obfuscate_again_while_obfuscation_is_only_supported() {
    let mut obfuscation = Obfuscation::new();
    obfuscation.given_a_source_asked_once_that_announces(0).await;

    obfuscation.when_the_source_is_due_again_with_obfuscation(ObfuscationMode::Supported).await;

    obfuscation.then_the_source_is_connected_to(true).await;
}

#[tokio::test]
async fn should_not_connect_plain_to_a_source_that_cannot_obfuscate_when_obfuscation_is_required() {
    let mut obfuscation = Obfuscation::new();
    obfuscation.given_a_source_asked_once_that_announces(0).await;

    obfuscation.when_the_source_is_due_again_with_obfuscation(ObfuscationMode::Required).await;

    obfuscation.then_the_source_is_connected_to(false).await;
}

#[tokio::test]
async fn should_not_connect_to_a_source_that_requires_obfuscation_when_obfuscation_is_disabled() {
    let mut obfuscation = Obfuscation::new();
    obfuscation.given_a_source_asked_once_that_announces(REQUIRES_OBFUSCATION).await;

    obfuscation.when_the_source_is_due_again_with_obfuscation(ObfuscationMode::Disabled).await;

    obfuscation.then_the_source_is_connected_to(false).await;
}

#[tokio::test]
async fn should_ask_a_firewalled_source_able_to_obfuscate_to_call_back_when_obfuscation_is_required() {
    let mut obfuscation = Obfuscation::new();
    obfuscation.given_obfuscation(ObfuscationMode::Required);
    obfuscation.given_a_download().await;

    obfuscation.when_the_server_finds_a_firewalled_source_with_the_connect_options(SUPPORTS).await;

    obfuscation.then_the_server_is_asked_for_callbacks(1).await;
}

#[tokio::test]
async fn should_not_ask_a_firewalled_source_that_cannot_obfuscate_to_call_back_when_obfuscation_is_required() {
    let mut obfuscation = Obfuscation::new();
    obfuscation.given_obfuscation(ObfuscationMode::Required);
    obfuscation.given_a_download().await;

    obfuscation.when_the_server_finds_a_firewalled_source_with_the_connect_options(0).await;

    obfuscation.then_the_server_is_asked_for_callbacks(0).await;
}
