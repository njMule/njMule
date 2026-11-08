// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's DownloadOwnSourceTest, under its names: the downloads take no source that is ourselves, from the
//! server, Kad or another client.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::arithmetic_side_effects,
    clippy::indexing_slicing
)]

use crate::download_lab;
use crate::scripted_source;

use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};

use download_lab::{Downloads, Engine, PORT};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::download::download_manager::KadSource;
use njmule_engine::protocol::ed2k::client_messages::FileStatus;
use njmule_engine::protocol::ed2k::hello;
use njmule_engine::protocol::ed2k::server_messages::{FoundSources, Source as Found};
use njmule_engine::protocol::ed2k::source_exchange as exchange;
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::protocol;
use scripted_source::{Link, ScriptedSource, WAIT};

const OUR_ID: u32 = u32::from_le_bytes([198, 51, 100, 7]);
const OURS: [u8; 16] = [0xab; 16];
const PEER: [u8; 16] = [0x5a; 16];
const OUR_KAD_IP: Ipv4Addr = Ipv4Addr::new(198, 51, 100, 8);
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

    // Kad runs, finding us reachable at our address.
    async fn given_a_download_kad_searches_for(&mut self) {
        let kad = Engine { kad_running: true, kad_public_ip: u32::from(OUR_KAD_IP), ..Engine::default() };
        self.downloads = Some(Downloads::start_with(OURS, OUR_ID, Downloads::settings(), kad));
        self.downloads().add(FileLink::of("ours.bin", 5000, FILE).unwrap()).await;
    }

    async fn given_kad_searches_for_its_sources(&self) {
        self.downloads().wait_for("Kad searched", |lab| !lab.kad_asked().is_empty()).await;
    }

    async fn when_kad_finds_sources(&self, found: Vec<(Ipv4Addr, u16, [u8; 16])>) {
        let sources: Vec<KadSource> = found
            .into_iter()
            .map(|(ip, port, user_hash)| KadSource {
                address: SocketAddrV4::new(ip, port),
                user_hash: Some(user_hash),
                crypt_options: 0,
                callback: None,
                ipv6: None,
            })
            .collect();
        self.downloads().run(move |lab| lab.kad_found(&FILE, &sources)).await;
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

    // Asked for other sources with the file, it tells of them, then queues us.
    async fn when_the_source_tells_of_sources(&mut self, found: Vec<(Ipv4Addr, [u8; 16])>) {
        let mut link = self.source.as_ref().unwrap().accept().await;
        link.until(protocol::EMULE, extended::REQUESTSOURCES2).await;
        let sources = found
            .into_iter()
            .map(|(ip, user_hash)| exchange::Source {
                ip: u32::from(ip),
                low_id: 0,
                port: PORT,
                server_ip: 0,
                server_port: 0,
                user_hash: Some(user_hash),
                crypt_options: 0,
                ipv6: None,
            })
            .collect();
        link.send(&exchange::Answer { version: 4, file: FILE, sources }.encode().unwrap()).await;
        link.send(&FileStatus { file: FILE, part_count: 0, parts: Vec::new() }.encode().unwrap()).await;
        link.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
        self.link = Some(link);
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

#[tokio::test]
async fn should_leave_out_an_exchanged_source_with_our_user_hash() {
    let mut own = OwnSource::new();
    own.given_a_source_of(PEER, hello::MISC2_SOURCE_EXCHANGE2).await;

    own.when_the_source_tells_of_sources(vec![(Ipv4Addr::new(192, 0, 2, 73), OURS), (THEIR_IP, PEER)]).await;

    let source = own.source.as_ref().unwrap().address();
    own.then_the_sources_of_the_download_are(&[source, SocketAddr::from((THEIR_IP, PORT))]).await;
}

#[tokio::test]
async fn should_leave_out_a_kad_source_with_our_user_hash_or_at_our_address() {
    let mut own = OwnSource::new();
    own.given_a_download_kad_searches_for().await;
    own.given_kad_searches_for_its_sources().await;

    own.when_kad_finds_sources(vec![
        (Ipv4Addr::new(192, 0, 2, 72), PORT, OURS),
        (OUR_KAD_IP, PORT, PEER),
        (OUR_KAD_IP, PORT + 1, PEER),
        (THEIR_IP, PORT, PEER),
    ])
    .await;

    own.then_the_sources_of_the_download_are(&[
        SocketAddr::from((OUR_KAD_IP, PORT + 1)),
        SocketAddr::from((THEIR_IP, PORT)),
    ])
    .await;
}
