// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's DownloadKadCallbackTest, under its names: sources firewalled on Kad, asked to connect to us
//! through their buddy or directly, then taken when they do; with a scripted source on the loopback address and a
//! clock the test moves.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::panic
)]

mod download_lab;
mod scripted_source;

use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::time::Duration;

use download_lab::{Downloads, Engine, Lab};
use njmule_engine::api::download_info::SourceState;
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::download::download_manager::{KadCallback, KadSource, REASK_INTERVAL};
use njmule_engine::protocol::ed2k::client_messages::{FileStatus, PartStatus};
use njmule_engine::protocol::ed2k::client_reasks::{self, ThroughBuddy};
use njmule_engine::protocol::ed2k::hello;
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::opcodes::{client, client_udp, extended};
use njmule_engine::protocol::packet::{Packet, protocol};
use scripted_source::{Link, ScriptedSource};

const OUR_ID: u32 = u32::from_le_bytes([198, 51, 100, 7]);
const OURS: [u8; 16] = [0xab; 16];
const PEER: [u8; 16] = [0x5f; 16];
const FILE: [u8; 16] = [0x61; 16];
const BUDDY_IP: Ipv4Addr = Ipv4Addr::new(198, 51, 100, 20);
const BUDDY_ID: [u8; 16] = [0x77; 16];
const SOURCE_UDP_PORT: u16 = 4673;

#[tokio::test]
async fn should_ask_a_source_behind_a_buddy_through_it_and_take_it_when_it_calls_back() {
    let mut callbacks = KadCallbacks::new().await;
    callbacks.given_a_download_that_kad_searches().await;
    callbacks.given_kad_found(callbacks.behind_a_buddy(PEER)).await;
    callbacks.given_a_callback_was_asked_for(1, PEER, true).await;

    callbacks.when_the_source_calls_back_as(PEER).await;

    callbacks.then_the_call_is("adopted").await;
    callbacks.then_the_source_is_asked_for_the_file().await;
}

#[tokio::test]
async fn should_reach_a_firewalled_source_at_its_ipv6_address_without_a_callback_when_we_have_ipv6() {
    let callbacks = KadCallbacks::with_our_ipv6("2001:db8::99").await;
    callbacks.given_a_download_that_kad_searches().await;

    callbacks.when_kad_finds(vec![callbacks.behind_a_buddy_at_the_ipv6_address(PEER, "2001:db8::5")]).await;

    callbacks.then_the_source_is_shown_at(&format!("[2001:db8::5]:{}", callbacks.source.port())).await;
    callbacks.then_no_callback_is_asked().await;
}

#[tokio::test]
async fn should_ask_a_source_taking_direct_callbacks_directly() {
    let callbacks = KadCallbacks::new().await;
    callbacks.given_a_download_that_kad_searches().await;

    callbacks.when_kad_finds(vec![callbacks.taking_direct_callbacks(PEER)]).await;

    callbacks.then_a_callback_is_asked_for(1, PEER, false).await;
}

#[tokio::test]
async fn should_not_take_a_client_we_did_not_ask_for_the_source() {
    let mut callbacks = KadCallbacks::new().await;
    callbacks.given_a_download_that_kad_searches().await;
    callbacks.given_kad_found(callbacks.behind_a_buddy(PEER)).await;

    callbacks.when_the_source_calls_back_as([0x46; 16]).await;

    callbacks.then_the_call_is("refused").await;
}

#[tokio::test]
async fn should_never_ask_us_to_call_ourselves() {
    let callbacks = KadCallbacks::new().await;
    callbacks.given_a_download_that_kad_searches().await;

    callbacks
        .when_kad_finds(vec![callbacks.behind_a_buddy(OURS), callbacks.taking_direct_callbacks(PEER)])
        .await;

    callbacks.then_a_callback_is_asked_for(1, PEER, false).await;
    callbacks.then_no_more_callbacks_are_asked().await;
}

#[tokio::test]
async fn should_ask_a_source_that_called_back_through_kad_again_and_never_pass_it_on() {
    let mut callbacks = KadCallbacks::new().await;
    callbacks.given_a_download_that_kad_searches().await;
    callbacks.given_kad_found(callbacks.behind_a_buddy(PEER)).await;
    callbacks.given_the_source_called_back_and_keeps_us_queued().await;

    callbacks.when_its_turn_comes_again();

    callbacks.then_a_callback_is_asked_for(2, PEER, true).await;
    callbacks.then_the_source_is_not_passed_on().await;
}

#[tokio::test]
async fn should_ask_a_source_behind_a_buddy_again_over_udp_through_the_buddy() {
    let mut callbacks = KadCallbacks::new().await;
    callbacks.given_the_manager_asking_again_over_udp();
    callbacks.given_a_download_that_kad_searches().await;
    callbacks.given_kad_found(callbacks.behind_a_buddy(PEER)).await;
    callbacks.given_the_source_called_back_and_queued_us_at(5).await;

    callbacks.when_time_passes(REASK_INTERVAL - Duration::from_secs(60));

    callbacks.then_the_buddy_is_asked_again_over_udp_for_the_file().await;
}

#[tokio::test]
async fn should_not_ask_a_source_to_call_back_when_it_answered_through_its_buddy() {
    let mut callbacks = KadCallbacks::new().await;
    callbacks.given_the_manager_asking_again_over_udp();
    callbacks.given_a_download_that_kad_searches().await;
    callbacks.given_kad_found(callbacks.behind_a_buddy(PEER)).await;
    callbacks.given_the_source_called_back_and_queued_us_at(5).await;
    callbacks.given_the_buddy_was_asked_again_over_udp().await;
    callbacks.given_the_source_answered_over_udp_with_the_rank(2).await;

    callbacks.when_time_passes(Duration::from_secs(60));

    callbacks.then_no_callback_is_asked_again_within(Duration::from_secs(3)).await;
}

/// The downloads, with Kad running as a stand-in, and the source that calls back.
struct KadCallbacks {
    downloads: Downloads,
    source: ScriptedSource,
    link: Option<Link>,
}

impl KadCallbacks {
    async fn new() -> Self {
        Self::started(Engine { kad_running: true, ..Engine::default() }).await
    }

    async fn with_our_ipv6(address: &str) -> Self {
        Self::started(Engine { kad_running: true, our_ipv6: Some(address.parse().unwrap()), ..Engine::default() }).await
    }

    async fn started(engine: Engine) -> Self {
        let misc1 = client_reasks::UDP_VERSION << hello::MISC1_UDP_VERSION_SHIFT;
        let source = ScriptedSource::with_udp_port(PEER, misc1, 0, SOURCE_UDP_PORT).await;
        let downloads = Downloads::start_with(OURS, OUR_ID, Downloads::settings(), engine);
        Self { downloads, source, link: None }
    }

    // Re-asks over UDP go through Kad's port, which runs in every scenario here.
    fn given_the_manager_asking_again_over_udp(&self) {
        assert!(self.downloads.engine.lock().unwrap().kad_running);
    }

    async fn given_a_download_that_kad_searches(&self) {
        self.downloads.add(FileLink::of("kad.bin", 5000, FILE).unwrap()).await;
        self.downloads.wait_for("Kad searched", |lab| lab.kad_asked().contains(&FILE)).await;
    }

    async fn given_kad_found(&self, found: KadSource) {
        let (user_hash, via_buddy) = (found.user_hash.unwrap(), found.callback.unwrap().via_buddy());
        self.when_kad_finds(vec![found]).await;
        self.then_a_callback_is_asked_for(1, user_hash, via_buddy).await;
    }

    async fn given_a_callback_was_asked_for(&self, times: usize, user_hash: [u8; 16], via_buddy: bool) {
        self.then_a_callback_is_asked_for(times, user_hash, via_buddy).await;
    }

    async fn given_the_source_called_back_and_keeps_us_queued(&mut self) {
        self.when_the_source_calls_back_as(PEER).await;
        self.then_the_call_is("adopted").await;
        let link = self.link.as_mut().unwrap();
        link.until(protocol::EDONKEY, client::SETREQFILEID).await;
        link.send(&whole_file()).await;
        link.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
        self.link = None;
        self.await_sources("the session ends", |state, _| state == SourceState::Waiting).await;
    }

    async fn given_the_source_called_back_and_queued_us_at(&mut self, rank: u16) {
        self.when_the_source_calls_back_as(PEER).await;
        self.then_the_call_is("adopted").await;
        let link = self.link.as_mut().unwrap();
        link.until(protocol::EDONKEY, client::SETREQFILEID).await;
        link.send(&whole_file()).await;
        link.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
        let mut ranking = PacketWriter::new();
        ranking.u16(rank).bytes(&[0; 10]);
        link.send(&Packet::written(protocol::EMULE, extended::QUEUERANKING, ranking)).await;
        self.await_sources("queued", move |_, queue_rank| queue_rank == u32::from(rank)).await;
        self.link = None;
        self.await_sources("the session ends", |state, _| {
            state != SourceState::Connecting && state != SourceState::Downloading
        })
        .await;
    }

    async fn given_the_buddy_was_asked_again_over_udp(&self) {
        self.when_time_passes(REASK_INTERVAL - Duration::from_secs(60));
        self.then_the_buddy_is_asked_again_over_udp_for_the_file().await;
    }

    async fn given_the_source_answered_over_udp_with_the_rank(&self, rank: u32) {
        let ack = client_reasks::Ack { parts: Some(PartStatus::COMPLETE), rank }.encode(client_reasks::UDP_VERSION);
        let ack = ack.unwrap();
        let from = SocketAddr::from((Ipv4Addr::LOCALHOST, SOURCE_UDP_PORT));
        self.run(move |lab| lab.reask_answered(from, &ack)).await;
        self.await_sources("the rank", move |_, queue_rank| queue_rank == rank).await;
    }

    async fn when_kad_finds(&self, found: Vec<KadSource>) {
        self.run(move |lab| lab.kad_found(&FILE, &found)).await;
    }

    async fn when_the_source_calls_back_as(&mut self, user_hash: [u8; 16]) {
        self.link = Some(ScriptedSource::call(self.downloads.port, &self.source.hello_as(user_hash, 1, 0)).await);
    }

    fn when_its_turn_comes_again(&self) {
        self.downloads.advance(REASK_INTERVAL);
    }

    fn when_time_passes(&self, duration: Duration) {
        self.downloads.advance(duration);
    }

    async fn then_a_callback_is_asked_for(&self, times: usize, user_hash: [u8; 16], via_buddy: bool) {
        self.downloads.wait_for("callbacks asked", move |lab| asked(lab).len() >= times).await;
        let asked = self.run(asked).await;
        assert_eq!(asked.len(), times);
        let (source, file) = asked.last().unwrap();
        assert_eq!(*file, FILE);
        assert_eq!(source.user_hash, Some(user_hash));
        assert_eq!(source.callback.unwrap().via_buddy(), via_buddy);
    }

    // A callback queued on one tick goes out on the next: three ticks see one that is due.
    async fn then_no_callback_is_asked_again_within(&self, wait: Duration) {
        tokio::time::sleep(wait).await;
        assert_eq!(self.run(asked).await.len(), 1);
    }

    async fn then_the_source_is_shown_at(&self, address: &str) {
        let shown: Vec<String> = self.run(|lab| lab.manager.sources().into_iter().map(|s| s.address).collect()).await;
        assert_eq!(shown, vec![address.to_owned()]);
    }

    async fn then_no_callback_is_asked(&self) {
        tokio::time::sleep(Duration::from_millis(1500)).await;
        assert!(self.run(asked).await.is_empty());
    }

    async fn then_no_more_callbacks_are_asked(&self) {
        tokio::time::sleep(Duration::from_millis(1500)).await;
        assert_eq!(self.run(asked).await.len(), 1);
    }

    async fn then_the_call_is(&self, outcome: &'static str) {
        self.downloads.wait_for("routed", |lab| !lab.routed.is_empty()).await;
        assert_eq!(self.run(|lab| lab.routed.pop()).await.as_deref(), Some(outcome));
    }

    async fn then_the_source_is_asked_for_the_file(&mut self) {
        let asks = self.link.as_mut().unwrap().until(protocol::EDONKEY, client::SETREQFILEID).await;
        assert_eq!(asks.last().unwrap().payload()[..16], FILE);
    }

    async fn then_the_source_is_not_passed_on(&self) {
        assert!(self.run(|lab| lab.manager.exchangeable(&FILE)).await.is_empty());
    }

    async fn then_the_buddy_is_asked_again_over_udp_for_the_file(&self) {
        self.downloads.wait_for("a re-ask through the buddy", |lab| !lab.kad_sent().is_empty()).await;
        let (to, packet, key) = self.run(|lab| lab.kad_sent().remove(0)).await;
        assert_eq!(to, SocketAddr::V4(SocketAddrV4::new(BUDDY_IP, 4672)));
        assert_eq!(key, None, "a plain datagram");
        assert_eq!(packet.opcode(), client_udp::REASKCALLBACKUDP);
        let reask = ThroughBuddy::decode(&packet).unwrap();
        assert_eq!(reask.buddy_id, BUDDY_ID);
        assert_eq!(client_reasks::Ping::file(&reask.ping).unwrap(), FILE);
    }

    fn behind_a_buddy(&self, user_hash: [u8; 16]) -> KadSource {
        KadSource {
            address: SocketAddrV4::new(Ipv4Addr::new(203, 0, 113, 5), self.source.port()),
            user_hash: Some(user_hash),
            crypt_options: 0,
            callback: Some(KadCallback {
                buddy_ip: u32::from(BUDDY_IP),
                buddy_port: 4672,
                buddy_id: Some(BUDDY_ID),
                udp_port: 4672,
            }),
            ipv6: None,
        }
    }

    fn behind_a_buddy_at_the_ipv6_address(&self, user_hash: [u8; 16], ipv6: &str) -> KadSource {
        KadSource { ipv6: Some(ipv6.parse().unwrap()), ..self.behind_a_buddy(user_hash) }
    }

    fn taking_direct_callbacks(&self, user_hash: [u8; 16]) -> KadSource {
        KadSource {
            address: SocketAddrV4::new(Ipv4Addr::new(203, 0, 113, 6), self.source.port()),
            user_hash: Some(user_hash),
            crypt_options: 0x08,
            callback: Some(KadCallback { buddy_ip: 0, buddy_port: 0, buddy_id: None, udp_port: 4672 }),
            ipv6: None,
        }
    }

    async fn await_sources(
        &self,
        what: &str,
        wanted: impl Fn(SourceState, u32) -> bool + Send + Sync + Copy + 'static,
    ) {
        let check = move |lab: &mut Lab| {
            let sources = lab.manager.sources();
            !sources.is_empty() && sources.iter().all(|s| wanted(s.state, s.queue_rank))
        };
        self.downloads.wait_for(what, check).await;
    }

    async fn run<T: Send + 'static>(&self, work: impl FnOnce(&mut Lab) -> T + Send + 'static) -> T {
        self.downloads.run(work).await
    }
}

fn asked(lab: &mut Lab) -> Vec<(KadSource, [u8; 16])> {
    lab.kad_callbacks()
}

fn whole_file() -> Packet {
    FileStatus { file: FILE, part_count: 0, parts: Vec::new() }.encode().unwrap()
}
