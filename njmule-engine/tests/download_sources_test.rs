// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's DownloadSourcesTest, under its names: downloads talking with scripted sources on the loopback
//! address, on a clock the test moves. Those of features not ported yet are listed in `tests/not_ported.txt`.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::panic
)]

mod download_lab;
mod scripted_source;

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::time::Duration;

use download_lab::{Downloads, Engine, Lab};
use njmule_engine::api::category;
use njmule_engine::api::download_info::{DownloadInfo, DownloadStatus, SourceInfo, SourceState};
use njmule_engine::api::ed2k_link::{FileLink, Source};
use njmule_engine::download::download_manager::{self, DownloadEvent, MIN_REASK};
use njmule_engine::download::download_queue::SPACE_CHECK;
use njmule_engine::protocol::ed2k::client_messages::{FileName, FileStatus};
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, protocol};
use scripted_source::{Link, ScriptedSource, WAIT};

const OUR_ID: u32 = u32::from_le_bytes([198, 51, 100, 7]);
const PEER: [u8; 16] = [0x5e; 16];

struct Sources {
    downloads: Option<Downloads>,
    min_free: u64,
    source: Option<ScriptedSource>,
    link: Option<Link>,
    hash: [u8; 16],
    info: Option<DownloadInfo>,
    results: Vec<bool>,
    accepted: bool,
    passed_on: bool,
}

impl Sources {
    fn new() -> Self {
        Self {
            downloads: None,
            min_free: 0,
            source: None,
            link: None,
            hash: [0; 16],
            info: None,
            results: Vec::new(),
            accepted: false,
            passed_on: false,
        }
    }

    async fn given_a_source(&mut self, misc1: u32, misc2: u32) {
        self.source = Some(ScriptedSource::new(PEER, misc1, misc2).await);
    }

    fn given_the_manager(&mut self) {
        let engine = Engine { min_free: self.min_free, ..Engine::default() };
        self.downloads = Some(Downloads::start_with([0xab; 16], OUR_ID, Downloads::settings(), engine));
    }

    async fn given_a_download_from_the_source(&mut self, name: &str, file: [u8; 16]) {
        self.hash = file;
        let link = self.source().link(name, 5000, file);
        self.downloads().add(link).await;
    }

    async fn given_a_paused_download(&mut self, name: &str, file: [u8; 16]) {
        self.hash = file;
        let link = FileLink::of(name, 5000, file).unwrap();
        self.run(move |lab| {
            lab.add(&link);
            let ours = lab.ours.clone();
            lab.manager.pause(&file, &ours)
        })
        .await;
    }

    async fn given_the_source_queued_us_at(&mut self, rank: u16) {
        let hash = self.hash;
        let mut link = self.source().accept().await;
        link.until(protocol::EDONKEY, client::SETREQFILEID).await;
        link.send(&whole_file(hash)).await;
        link.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
        let mut ranking = PacketWriter::new();
        ranking.u16(rank).bytes(&[0; 10]);
        link.send(&Packet::written(protocol::EMULE, extended::QUEUERANKING, ranking)).await;
        self.link = Some(link);
        self.await_source(move |s| s.state == SourceState::Queued && s.queue_rank == u32::from(rank)).await;
    }

    async fn given_the_connection_closed_while_queued(&mut self) {
        self.link = None;
        self.await_source(|s| s.state == SourceState::Waiting).await;
    }

    async fn given_the_source_asked_for_the_file(&mut self) {
        let mut link = self.source().accept().await;
        link.until(protocol::EDONKEY, client::SETREQFILEID).await;
        self.link = Some(link);
    }

    async fn given_the_source_named_the_file(&mut self, name: &str) {
        self.when_the_source_names_the_file(name).await;
        self.then_the_source_gives_the_name(name).await;
    }

    async fn given_the_download_stopped(&mut self) {
        let hash = self.hash;
        self.run(move |lab| {
            let ours = lab.ours.clone();
            lab.manager.stop(&hash, &ours)
        })
        .await;
    }

    async fn given_the_download_stopped_by_schedule(&mut self) {
        let hash = self.hash;
        let stopped = self
            .run(move |lab| {
                let ours = lab.ours.clone();
                lab.manager.stop_by_schedule(&hash, &ours)
            })
            .await;
        assert!(stopped);
    }

    fn given_a_minimum_free_space_of(&mut self, bytes: u64) {
        self.min_free = bytes;
        if let Some(downloads) = &self.downloads {
            downloads.engine.lock().unwrap().min_free = bytes;
        }
    }

    // A listener that does not care about the disk: the lab hears every event, and none is needed.
    fn given_a_listener_that_does_not_care_about_the_disk(&mut self) {}

    // Asks for the file and for an upload slot.
    async fn given_we_asked_the_source_for_an_upload_slot(&mut self) {
        let hash = self.hash;
        let mut link = self.source().accept().await;
        link.until(protocol::EDONKEY, client::SETREQFILEID).await;
        link.send(&whole_file(hash)).await;
        link.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
        self.link = Some(link);
    }

    // The free space is looked at again on the downloads' clock, which started just before the queue's.
    async fn given_the_downloads_wait_for_space(&mut self) {
        self.downloads().advance(SPACE_CHECK + Duration::from_secs(1));
        self.await_disk_space(false).await;
        assert!(self.link().closes_within(WAIT).await, "the sessions end");
        let hash = self.hash;
        assert_eq!(self.run(move |lab| lab.manager.download(&hash).unwrap().status).await, DownloadStatus::NoSpace);
    }

    async fn given_the_disk_has_room_again(&mut self) {
        self.downloads().advance(SPACE_CHECK + Duration::from_secs(1));
        self.await_disk_space(true).await;
    }

    async fn when_the_connection_closes_while_queued(&mut self) {
        self.given_the_connection_closed_while_queued().await;
    }

    async fn when_the_source_names_the_file(&mut self, name: &str) {
        let answer = FileName { file: self.hash, name: name.to_owned() }.encode().unwrap();
        self.link().send(&answer).await;
    }

    // The parts come after the name: once they are known, the name was read.
    async fn when_the_source_names_another_file_before_telling_its_parts(&mut self, name: &str) {
        let hash = self.hash;
        let answer = FileName { file: [0x46; 16], name: name.to_owned() }.encode().unwrap();
        self.link().send(&answer).await;
        self.link().send(&whole_file(hash)).await;
        self.await_source(|s| s.parts > 0).await;
    }

    async fn when_the_manager_starts_again(&mut self) {
        self.downloads.as_mut().unwrap().restart().await;
    }

    async fn when_a_download_names_the_source_by(&mut self, host: &str) {
        self.hash = [0x3d; 16];
        let source = Source { host: host.to_owned(), port: self.source().port() };
        let named = FileLink::new("named.bin".to_owned(), 5000, self.hash, None, Vec::new(), vec![source], Vec::new());
        self.downloads().add(named.unwrap()).await;
    }

    async fn when_a_download_is_added(&mut self, name: &str, file: [u8; 16]) {
        self.hash = file;
        self.info = Some(self.downloads().add(FileLink::of(name, 5000, file).unwrap()).await);
    }

    fn when_a_source_is_offered_to_a_client(&mut self, source: Ipv4Addr, client: IpAddr) {
        self.passed_on = download_manager::passes_on(u32::from(source), client);
    }

    async fn when_sources_are_added_by_hand(&mut self, addresses: Vec<SocketAddr>) {
        let hash = self.hash;
        self.results = self
            .run(move |lab| {
                let settings = lab.settings.clone();
                addresses.into_iter().map(|address| lab.manager.add_source_by_hand(&hash, address, &settings)).collect()
            })
            .await;
    }

    async fn when_the_download_is_set_to_preview_first(&mut self) {
        let hash = self.hash;
        self.accepted = self.run(move |lab| lab.manager.set_preview_first(&hash, true)).await;
    }

    async fn when_the_download_is_stopped_by_schedule_again(&mut self) {
        let hash = self.hash;
        self.accepted = self
            .run(move |lab| {
                let ours = lab.ours.clone();
                lab.manager.stop_by_schedule(&hash, &ours)
            })
            .await;
    }

    async fn when_the_category_is_set(&mut self, category: u32) {
        let hash = self.hash;
        self.accepted = self.run(move |lab| lab.manager.set_category(&hash, category)).await;
    }

    fn when_time_passes(&mut self, duration: Duration) {
        self.downloads().advance(duration);
    }

    async fn then_the_source_gives_the_name(&mut self, name: &str) {
        let seen = self.await_source(|s| s.reported_name.is_some()).await;
        assert_eq!(seen.reported_name.as_deref(), Some(name));
    }

    async fn then_each_part_is_known_at_sources(&self, sources: u32) {
        let hash = self.hash;
        let known = self.run(move |lab| lab.manager.download(&hash).unwrap().parts.sources).await;
        assert!(known.iter().all(|count| *count == sources), "{known:?}");
    }

    async fn then_the_source_is_known_at(&self, ip: &str) {
        let seen = self.await_source(|_| true).await;
        assert_eq!(seen.address, format!("{ip}:{}", self.source().port()));
    }

    async fn then_the_download_has_sources(&self, count: usize) {
        let hash = self.hash;
        // Taken at the first tick, if any.
        tokio::time::sleep(download_lab::TICK * 3).await;
        assert_eq!(self.run(move |lab| lab.manager.download(&hash).unwrap().sources).await, count);
    }

    async fn then_the_source_is_connected_to(&mut self) {
        assert!(self.source().called_within(WAIT).await);
        let mut link = self.source().accept().await;
        link.until(protocol::EDONKEY, client::SETREQFILEID).await;
        self.link = Some(link);
    }

    async fn then_the_download_can_be_paused(&self) {
        let hash = self.hash;
        let paused = self
            .run(move |lab| {
                let ours = lab.ours.clone();
                lab.manager.pause(&hash, &ours)
            })
            .await;
        assert!(paused);
    }

    fn then_the_download_is_added_as(&self, status: DownloadStatus) {
        assert_eq!(self.info.as_ref().unwrap().status, status);
    }

    fn then_it_is_not_passed_on(&self) {
        assert!(!self.passed_on, "a LAN source goes to no client without an IPv4 address");
    }

    fn then_every_one_is_refused(&self) {
        assert!(self.results.iter().all(|taken| !taken), "{:?}", self.results);
    }

    fn then_every_one_is_taken(&self) {
        assert!(self.results.iter().all(|taken| *taken), "{:?}", self.results);
    }

    async fn then_the_paused_download_has_no_source_to_pass_on(&self) {
        let hash = self.hash;
        assert!(self.run(move |lab| lab.manager.exchangeable(&hash)).await.is_empty(), "paused");
    }

    async fn then_the_first_source_is_shown_at(&self, address: &str) {
        assert_eq!(self.run(|lab| lab.manager.sources()[0].address.clone()).await, address);
    }

    fn then_it_is_accepted(&self) {
        assert!(self.accepted);
    }

    async fn then_the_download_is_previewed_first(&self) {
        let hash = self.hash;
        assert!(self.run(move |lab| lab.manager.download(&hash).unwrap().preview_first).await);
    }

    fn then_the_stop_is_accepted_as_stopped_already(&self) {
        assert!(self.accepted, "stopped already");
    }

    async fn then_the_status_of_the_download_is(&self, status: DownloadStatus) {
        let hash = self.hash;
        assert_eq!(self.run(move |lab| lab.manager.download(&hash).unwrap().status).await, status);
    }

    async fn then_the_source_is_asked_again(&mut self) {
        let mut link = self.source().accept().await;
        link.until(protocol::EDONKEY, client::SETREQFILEID).await;
        self.link = Some(link);
    }

    fn source(&self) -> &ScriptedSource {
        self.source.as_ref().unwrap()
    }

    fn link(&mut self) -> &mut Link {
        self.link.as_mut().unwrap()
    }

    fn downloads(&self) -> &Downloads {
        self.downloads.as_ref().unwrap()
    }

    async fn run<T: Send + 'static>(&self, work: impl FnOnce(&mut Lab) -> T + Send + 'static) -> T {
        self.downloads().run(work).await
    }

    // The scripted source's entry in the list of sources, once `wanted` holds of it.
    async fn await_source(&self, wanted: impl Fn(&SourceInfo) -> bool + Send + Sync + Copy + 'static) -> SourceInfo {
        let address = self.source().address().to_string();
        let deadline = std::time::Instant::now() + WAIT;
        loop {
            let at = address.clone();
            let seen =
                self.run(move |lab| lab.manager.sources().into_iter().find(|s| s.address == at && wanted(s))).await;
            if let Some(seen) = seen {
                return seen;
            }
            assert!(std::time::Instant::now() < deadline, "waited in vain for the source {address}");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    async fn await_disk_space(&self, enough: bool) {
        self.downloads()
            .wait_for("the disk space told", move |lab| {
                lab.events.iter().any(|event| matches!(event, DownloadEvent::DiskSpace(told) if *told == enough))
            })
            .await;
    }
}

// The source has the whole file.
fn whole_file(file: [u8; 16]) -> Packet {
    FileStatus { file, part_count: 0, parts: Vec::new() }.encode().unwrap()
}

#[tokio::test]
async fn should_show_the_name_a_source_gives_the_file_without_control_characters() {
    let mut sources = Sources::new();
    sources.given_a_source(0, 0).await;
    sources.given_the_manager();
    sources.given_a_download_from_the_source("sintel.mkv", [0x44; 16]).await;
    sources.given_the_source_asked_for_the_file().await;

    sources.when_the_source_names_the_file("Sintel\t2010.mkv\u{7}").await;

    sources.then_the_source_gives_the_name("Sintel 2010.mkv").await;
}

#[tokio::test]
async fn should_take_no_name_a_source_gives_a_file_not_asked_for() {
    let mut sources = Sources::new();
    sources.given_a_source(0, 0).await;
    sources.given_the_manager();
    sources.given_a_download_from_the_source("sintel.mkv", [0x45; 16]).await;
    sources.given_the_source_asked_for_the_file().await;
    sources.given_the_source_named_the_file("Sintel.mkv").await;

    sources.when_the_source_names_another_file_before_telling_its_parts("Other.mkv").await;

    sources.then_the_source_gives_the_name("Sintel.mkv").await;
}

#[tokio::test]
async fn should_count_the_parts_of_a_source_that_keeps_us_queued_in_their_rarity() {
    let mut sources = Sources::new();
    sources.given_a_source(0, 0).await;
    sources.given_the_manager();
    sources.given_a_download_from_the_source("rare.bin", [0x43; 16]).await;
    sources.given_the_source_queued_us_at(5).await;

    sources.when_the_connection_closes_while_queued().await;

    sources.then_each_part_is_known_at_sources(1).await;
}

#[tokio::test]
async fn should_take_again_after_a_restart_the_source_that_queued_us() {
    let mut sources = Sources::new();
    sources.given_a_source(0, 0).await;
    sources.given_the_manager();
    sources.given_a_download_from_the_source("kept.bin", [0x3a; 16]).await;
    sources.given_the_source_queued_us_at(3).await;

    sources.when_the_manager_starts_again().await;

    sources.then_the_source_is_known_at("127.0.0.1").await;
}

#[tokio::test]
async fn should_keep_no_source_of_a_stopped_download_for_the_next_run() {
    let mut sources = Sources::new();
    sources.given_a_source(0, 0).await;
    sources.given_the_manager();
    sources.given_a_download_from_the_source("stopped.bin", [0x3b; 16]).await;
    sources.given_the_source_queued_us_at(3).await;
    sources.given_the_download_stopped().await;

    sources.when_the_manager_starts_again().await;

    sources.then_the_download_has_sources(0).await;
}

#[tokio::test]
async fn should_look_up_a_source_named_by_its_host() {
    let mut sources = Sources::new();
    sources.given_a_source(0, 0).await;
    sources.given_the_manager();

    sources.when_a_download_names_the_source_by("localhost").await;

    sources.then_the_source_is_connected_to().await;
    sources.then_the_source_is_known_at("127.0.0.1").await;
    sources.then_the_download_can_be_paused().await;
}

#[tokio::test]
async fn should_hold_every_download_on_a_disk_short_of_space_from_the_start() {
    let mut sources = Sources::new();
    sources.given_a_minimum_free_space_of(u64::MAX);
    sources.given_a_listener_that_does_not_care_about_the_disk();
    sources.given_the_manager();

    sources.when_a_download_is_added("held.bin", [0x3e; 16]).await;

    sources.then_the_download_is_added_as(DownloadStatus::NoSpace);
}

#[test]
fn should_pass_a_lan_source_on_to_no_client_without_an_ipv4_address() {
    let mut sources = Sources::new();

    sources.when_a_source_is_offered_to_a_client(Ipv4Addr::new(10, 0, 0, 1), IpAddr::V6(Ipv6Addr::LOCALHOST));

    sources.then_it_is_not_passed_on();
}

#[tokio::test]
async fn should_refuse_sources_by_hand_without_a_port_or_at_an_address_no_host_has() {
    let mut sources = Sources::new();
    sources.given_the_manager();
    sources.given_a_paused_download("known.bin", [0x3b; 16]).await;

    sources
        .when_sources_are_added_by_hand(vec![
            SocketAddr::new("ff02::1".parse().unwrap(), download_lab::PORT),
            SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), download_lab::PORT),
            SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0),
        ])
        .await;

    sources.then_every_one_is_refused();
    sources.then_the_paused_download_has_no_source_to_pass_on().await;
}

#[tokio::test]
async fn should_take_an_ipv6_source_by_hand_and_show_it_in_brackets() {
    let mut sources = Sources::new();
    sources.given_the_manager();
    sources.given_a_paused_download("six.bin", [0x4f; 16]).await;

    sources
        .when_sources_are_added_by_hand(vec![SocketAddr::new("2001:db8::5".parse().unwrap(), download_lab::PORT)])
        .await;

    sources.then_every_one_is_taken();
    sources.then_the_first_source_is_shown_at("[2001:db8::5]:4662").await;
}

#[tokio::test]
async fn should_set_a_paused_download_to_preview_first() {
    let mut sources = Sources::new();
    sources.given_the_manager();
    sources.given_a_paused_download("known.bin", [0x3b; 16]).await;

    sources.when_the_download_is_set_to_preview_first().await;

    sources.then_it_is_accepted();
    sources.then_the_download_is_previewed_first().await;
}

#[tokio::test]
async fn should_accept_a_stop_by_schedule_of_a_download_stopped_already() {
    let mut sources = Sources::new();
    sources.given_the_manager();
    sources.given_a_paused_download("known.bin", [0x3b; 16]).await;
    sources.given_the_download_stopped_by_schedule().await;

    sources.when_the_download_is_stopped_by_schedule_again().await;

    sources.then_the_stop_is_accepted_as_stopped_already();
    sources.then_the_status_of_the_download_is(DownloadStatus::Stopped).await;
}

#[tokio::test]
async fn should_set_the_category_of_a_stopped_download() {
    let mut sources = Sources::new();
    sources.given_the_manager();
    sources.given_a_paused_download("known.bin", [0x3b; 16]).await;
    sources.given_the_download_stopped_by_schedule().await;

    sources.when_the_category_is_set(category::NONE).await;

    sources.then_it_is_accepted();
}

#[tokio::test]
async fn should_let_downloads_wait_while_the_disk_is_short_of_space() {
    let mut sources = Sources::new();
    sources.given_a_source(0, 0).await;
    sources.given_the_manager();
    sources.given_a_download_from_the_source("space.bin", [0x3c; 16]).await;
    sources.given_we_asked_the_source_for_an_upload_slot().await;
    sources.given_a_minimum_free_space_of(u64::MAX);
    sources.given_the_downloads_wait_for_space().await;
    sources.given_a_minimum_free_space_of(0);
    sources.given_the_disk_has_room_again().await;

    sources.when_time_passes(MIN_REASK + Duration::from_secs(60));

    sources.then_the_source_is_asked_again().await;
    sources.then_the_download_can_be_paused().await;
}
