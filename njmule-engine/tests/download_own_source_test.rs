// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's DownloadOwnSourceTest, under its names: the downloads take no source that is ourselves. Kad's
//! sources and those other clients tell of wait for their ports (`tests/not_ported.txt`).

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::arithmetic_side_effects,
    clippy::indexing_slicing
)]

mod download_lab;
mod scripted_source;

use std::net::{Ipv4Addr, SocketAddr};

use download_lab::{Downloads, PORT};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::protocol::ed2k::server_messages::{FoundSources, Source as Found};
use scripted_source::{Link, ScriptedSource, WAIT};

const OUR_ID: u32 = u32::from_le_bytes([198, 51, 100, 7]);
const OURS: [u8; 16] = [0xab; 16];
const PEER: [u8; 16] = [0x5a; 16];
const THEIR_IP: Ipv4Addr = Ipv4Addr::new(192, 0, 2, 70);
const FILE: [u8; 16] = [0x65; 16];
const OTHER_FILE: [u8; 16] = [0x66; 16];

struct OwnSource {
    downloads: Option<Downloads>,
    source: Option<ScriptedSource>,
    link: Option<Link>,
}

impl OwnSource {
    fn new() -> Self {
        Self { downloads: None, source: None, link: None }
    }

    async fn given_a_download(&mut self) {
        self.downloads = Some(Downloads::start(OURS, OUR_ID, Downloads::settings()));
        self.downloads().add(FileLink::of("ours.bin", 5000, FILE).unwrap()).await;
    }

    async fn given_a_source_of(&mut self, user_hash: [u8; 16], misc2: u32) {
        let source = ScriptedSource::new(user_hash, 0, misc2).await;
        self.downloads = Some(Downloads::start(OURS, OUR_ID, Downloads::settings()));
        self.downloads().add(source.link("ours.bin", 5000, FILE)).await;
        self.source = Some(source);
    }

    async fn given_the_source_is_a_source_of_another_download_too(&mut self) {
        let link = self.source.as_ref().unwrap().link("other.bin", 5000, OTHER_FILE);
        self.downloads().add(link).await;
    }

    async fn when_the_server_finds_sources(&mut self, found: Vec<(Ipv4Addr, [u8; 16])>) {
        let sources = found
            .into_iter()
            .map(|(ip, user_hash)| Found {
                client_id: u32::from_le_bytes(ip.octets()),
                port: PORT,
                crypt_options: Found::HAS_USER_HASH,
                user_hash: Some(user_hash),
            })
            .collect();
        let found = FoundSources { file: FILE, sources };
        self.downloads()
            .run(move |lab| {
                let (settings, ours) = (lab.settings.clone(), lab.ours.clone());
                lab.manager.found_sources(&found, &settings, OUR_ID, &ours);
            })
            .await;
    }

    async fn when_the_source_answers_our_hello(&mut self) {
        self.link = Some(self.source.as_ref().unwrap().accept().await);
    }

    async fn then_the_sources_of_the_download_are(&self, expected: &[SocketAddr]) {
        let count = expected.len();
        self.downloads().wait_for("the sources", move |lab| lab.manager.sources().len() == count).await;
        let mut seen: Vec<String> =
            self.downloads().run(|lab| lab.manager.sources().into_iter().map(|s| s.address).collect()).await;
        seen.sort();
        let mut wanted: Vec<String> = expected.iter().map(ToString::to_string).collect();
        wanted.sort();
        assert_eq!(seen, wanted);
    }

    async fn then_the_connection_closes(&mut self) {
        assert!(self.link.as_mut().unwrap().closes_within(WAIT).await);
    }

    async fn then_no_download_has_a_source(&self) {
        self.downloads().wait_for("no source", |lab| lab.manager.sources().is_empty()).await;
    }

    fn downloads(&self) -> &Downloads {
        self.downloads.as_ref().unwrap()
    }
}

#[tokio::test]
async fn should_leave_out_a_server_source_with_our_user_hash() {
    let mut own = OwnSource::new();
    own.given_a_download().await;

    own.when_the_server_finds_sources(vec![(Ipv4Addr::new(192, 0, 2, 71), OURS), (THEIR_IP, PEER)]).await;

    own.then_the_sources_of_the_download_are(&[SocketAddr::from((THEIR_IP, PORT))]).await;
}

#[tokio::test]
async fn should_drop_a_source_that_turns_out_to_be_ourselves_from_every_download() {
    let mut own = OwnSource::new();
    own.given_a_source_of(OURS, 0).await;
    own.given_the_source_is_a_source_of_another_download_too().await;

    own.when_the_source_answers_our_hello().await;

    own.then_the_connection_closes().await;
    own.then_no_download_has_a_source().await;
}
