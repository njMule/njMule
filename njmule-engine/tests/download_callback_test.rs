// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's DownloadCallbackTest, under its names: sources that connect to us, called back or for our files,
//! offered to the downloads as the engine does.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::panic
)]

use crate::download_lab;
use crate::scripted_source;

use std::time::{Duration, Instant};

use download_lab::{Downloads, Lab};
use njmule_engine::api::download_info::{DownloadStatus, SourceState};
use njmule_engine::api::ed2k_link::{FileLink, Source};
use njmule_engine::download::download_manager::REASK_INTERVAL;
use njmule_engine::protocol::ed2k::client_messages::FileStatus;
use njmule_engine::protocol::ed2k::hello;
use njmule_engine::protocol::ed2k::server_messages::{FoundSources, Source as Found};
use njmule_engine::protocol::packet::opcodes::client;
use njmule_engine::protocol::packet::{Packet, protocol};
use scripted_source::{Link, ScriptedSource, WAIT};
use tokio::net::TcpStream;

const OUR_ID: u32 = u32::from_le_bytes([198, 51, 100, 7]);
const SERVER_IP: u32 = u32::from_le_bytes([198, 51, 100, 99]);
const OTHER_SERVER_IP: u32 = u32::from_le_bytes([198, 51, 100, 98]);
const OURS: [u8; 16] = [0xab; 16];
const PEER: [u8; 16] = [0x5f; 16];

struct Callback {
    downloads: Option<Downloads>,
    source: Option<ScriptedSource>,
    second: Option<ScriptedSource>,
    asked: Option<Link>,
    queued: Option<Link>,
    early: Option<Link>,
    call: Option<Link>,
    silent: Option<TcpStream>,
    hash: [u8; 16],
    gone: [u8; 16],
    request: Vec<Packet>,
}

impl Callback {
    fn new() -> Self {
        Self {
            downloads: None,
            source: None,
            second: None,
            asked: None,
            queued: None,
            early: None,
            call: None,
            silent: None,
            hash: [0; 16],
            gone: [0; 16],
            request: Vec::new(),
        }
    }

    async fn given_a_source(&mut self) {
        self.source = Some(ScriptedSource::new(PEER, 0, 0).await);
    }

    async fn given_two_sources(&mut self) {
        self.source = Some(ScriptedSource::new(PEER, 0, 0).await);
        self.second = Some(ScriptedSource::new(PEER, 0, 0).await);
    }

    async fn given_a_source_supporting_obfuscation(&mut self) {
        self.source = Some(ScriptedSource::new(PEER, 0, hello::MISC2_SUPPORTS_CRYPT).await);
    }

    fn given_the_download_manager_with_a_listener(&mut self) {
        let downloads = Downloads::start(OURS, OUR_ID, Downloads::settings());
        downloads.server.lock().unwrap().server_ip = SERVER_IP;
        self.downloads = Some(downloads);
    }

    async fn given_a_paused_download_and_a_download_from_both_sources(&mut self, paused: [u8; 16], hash: [u8; 16]) {
        self.hash = hash;
        let sources = [self.source(), self.second.as_ref().unwrap()]
            .iter()
            .map(|source| Source { host: "127.0.0.1".to_owned(), port: source.port() })
            .collect();
        let both = FileLink::new("taken.bin".to_owned(), 5000, hash, None, Vec::new(), sources, Vec::new()).unwrap();
        self.run(move |lab| {
            lab.add(&FileLink::of("paused.bin", 5000, paused).unwrap());
            let ours = lab.ours.clone();
            lab.manager.pause(&paused, &ours);
            lab.add(&both);
        })
        .await;
    }

    async fn given_a_download_from_the_source(&mut self, name: &str, hash: [u8; 16]) {
        self.hash = hash;
        let link = self.source().link(name, 5000, hash);
        self.downloads().add(link).await;
    }

    async fn given_two_downloads_with_firewalled_sources_from_the_server(&mut self, hash: [u8; 16], gone: [u8; 16]) {
        self.hash = hash;
        self.gone = gone;
        self.run(move |lab| {
            lab.add(&FileLink::of("lowid.bin", 5000, hash).unwrap());
            lab.add(&FileLink::of("gone.bin", 5000, gone).unwrap());
            let (settings, ours) = (lab.settings.clone(), lab.ours.clone());
            let with_hash =
                Found { client_id: 5000, port: download_lab::PORT, crypt_options: 0, user_hash: Some(PEER) };
            lab.manager.found_sources(&FoundSources { file: hash, sources: vec![with_hash] }, &settings, OUR_ID, &ours);
            let without = Found { client_id: 6000, port: download_lab::PORT, crypt_options: 0, user_hash: None };
            lab.manager.found_sources(&FoundSources { file: gone, sources: vec![without] }, &settings, OUR_ID, &ours);
        })
        .await;
    }

    async fn given_the_first_source_is_still_asked(&mut self) {
        let mut asked = self.source().accept().await;
        asked.until(protocol::EDONKEY, client::SETREQFILEID).await;
        self.asked = Some(asked);
    }

    async fn given_the_second_source_keeps_us_in_its_queue(&mut self) {
        let hash = self.hash;
        let mut queued = self.second.as_ref().unwrap().accept().await;
        queued.until(protocol::EDONKEY, client::SETREQFILEID).await;
        queued.send(&whole_file(hash)).await;
        queued.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
        self.queued = Some(queued);
    }

    // One still being asked cannot call back; one keeping us queued can, with a slot.
    async fn given_the_first_source_called_back_and_was_refused(&mut self) {
        let hello = self.source().hello();
        self.early = Some(ScriptedSource::call(self.downloads().port, &hello).await);
        assert_eq!(self.routed_as().await, "refused");
    }

    async fn given_the_second_source_called_back_and_was_adopted(&mut self) {
        let hello = self.second.as_ref().unwrap().hello();
        self.call = Some(ScriptedSource::call(self.downloads().port, &hello).await);
        assert_eq!(self.routed_as().await, "adopted");
    }

    async fn given_the_queued_connection_ended(&mut self) {
        assert!(self.queued.as_mut().unwrap().closes_within(WAIT).await, "the queued connection ends");
    }

    async fn given_the_source_connected_to_us_for_our_files(&mut self) {
        let hello = self.source().hello();
        self.call = Some(ScriptedSource::call(self.downloads().port, &hello).await);
        assert_eq!(self.routed_as().await, "refused");
    }

    async fn given_both_sources_were_asked_through_the_server(&mut self) {
        self.await_callbacks(2).await;
    }

    async fn given_the_second_download_paused(&mut self) {
        let gone = self.gone;
        self.run(move |lab| {
            let ours = lab.ours.clone();
            lab.manager.pause(&gone, &ours)
        })
        .await;
    }

    // Only the client with the LowID, the server and the user hash the server told calls back.
    async fn given_calls_back_with_another_client_id_server_or_hash_were_refused(&mut self) {
        let port = self.downloads().port;
        for hello in [
            self.source().hello_as(PEER, 5000, 0),
            self.source().hello_as(PEER, 5000, OTHER_SERVER_IP),
            self.source().hello_as([0x46; 16], 5000, SERVER_IP),
            self.source().hello_as(PEER, 6000, SERVER_IP),
        ] {
            drop(ScriptedSource::call(port, &hello).await);
        }
        self.downloads().wait_for("four calls routed", |lab| lab.routed.len() == 4).await;
        let routed = self.run(|lab| std::mem::take(&mut lab.routed)).await;
        assert!(routed.iter().all(|taken| taken == "refused"), "{routed:?}");
    }

    async fn given_the_source_called_back_with_its_server_and_hash_and_was_adopted(&mut self) {
        let hello = self.source().hello_as(PEER, 5000, SERVER_IP);
        self.call = Some(ScriptedSource::call(self.downloads().port, &hello).await);
        assert_eq!(self.routed_as().await, "adopted");
    }

    async fn given_the_source_kept_us_in_its_queue_and_hung_up(&mut self) {
        let hash = self.hash;
        let mut call = self.call.take().unwrap();
        call.until(protocol::EDONKEY, client::SETREQFILEID).await;
        call.send(&whole_file(hash)).await;
        call.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
        drop(call);
        self.await_waiting(hash).await;
    }

    async fn given_the_source_kept_us_in_its_queue_over_a_plain_connection(&mut self) {
        let hash = self.hash;
        let mut plain = self.source().accept().await;
        plain.until(protocol::EDONKEY, client::SETREQFILEID).await;
        plain.send(&whole_file(hash)).await;
        plain.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
        drop(plain);
        self.await_waiting(hash).await;
    }

    // Its user hash and its taste for obfuscation known now, it is asked again obfuscated.
    async fn given_the_source_is_asked_again_obfuscated_and_does_not_answer(&mut self) {
        self.downloads().advance(REASK_INTERVAL);
        self.silent = Some(self.source().accept_silently().await);
        self.downloads()
            .wait_for("connecting", |lab| {
                lab.manager.sources().first().is_some_and(|s| s.state == SourceState::Connecting)
            })
            .await;
        tokio::time::sleep(Duration::from_millis(1200)).await;
    }

    // Its parts known from the queued connection, the slot comes at once.
    async fn when_the_second_source_gives_us_a_slot_over_its_call(&mut self) {
        let call = self.call.as_mut().unwrap();
        call.send(&Packet::empty(protocol::EDONKEY, client::ACCEPTUPLOADREQ)).await;
        self.request = call.until(protocol::EDONKEY, client::REQUESTPARTS).await;
    }

    async fn when_a_download_from_the_source_is_added(&mut self, name: &str, hash: [u8; 16]) {
        self.given_a_download_from_the_source(name, hash).await;
    }

    fn when_it_is_time_to_ask_the_source_again(&mut self) {
        self.downloads().advance(REASK_INTERVAL);
    }

    async fn when_the_download_is_paused_before_the_source_answers(&mut self) {
        let hash = self.hash;
        self.run(move |lab| {
            let ours = lab.ours.clone();
            lab.manager.pause(&hash, &ours)
        })
        .await;
        self.silent = None;
    }

    fn then_we_do_not_ask_for_the_file_again(&self) {
        assert!(
            self.request.iter().all(|p| p.opcode() != client::SETREQFILEID),
            "no request again for the file whose parts it told"
        );
    }

    fn then_we_request_parts_without_asking_for_the_slot_it_gave(&self) {
        assert!(self.request.iter().all(|p| p.opcode() != client::STARTUPLOADREQ), "no request for a slot it gave");
    }

    async fn then_the_source_is_asked_for_the_file_over_its_own_connection(&mut self) {
        let asked = self.call.as_mut().unwrap().until(protocol::EDONKEY, client::SETREQFILEID).await;
        assert!(asked.iter().any(|p| p.opcode() == client::REQUESTFILENAME));
    }

    async fn then_the_server_is_asked_for_callbacks_of(&self, low_ids: &[u32]) {
        self.await_callbacks(low_ids.len()).await;
        assert_eq!(self.downloads().server.lock().unwrap().callbacks, low_ids);
    }

    async fn then_the_source_is(&self, expected: SourceState) {
        assert_eq!(self.run(|lab| lab.manager.sources()[0].state).await, expected);
    }

    async fn then_the_download_is(&self, expected: DownloadStatus) {
        let hash = self.hash;
        assert_eq!(self.run(move |lab| lab.manager.download(&hash).unwrap().status).await, expected);
    }

    // How the last client that connected to us was taken.
    async fn routed_as(&self) -> String {
        self.downloads().wait_for("routed", |lab| !lab.routed.is_empty()).await;
        self.run(|lab| lab.routed.pop().unwrap()).await
    }

    async fn await_waiting(&self, hash: [u8; 16]) {
        self.downloads()
            .wait_for("waiting", move |lab| {
                lab.manager.sources().iter().filter(|s| s.file == hash).all(|s| s.state == SourceState::Waiting)
            })
            .await;
    }

    async fn await_callbacks(&self, count: usize) {
        let deadline = Instant::now() + WAIT;
        while self.downloads().server.lock().unwrap().callbacks.len() < count {
            assert!(Instant::now() < deadline, "{count} callbacks");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    fn source(&self) -> &ScriptedSource {
        self.source.as_ref().unwrap()
    }

    fn downloads(&self) -> &Downloads {
        self.downloads.as_ref().unwrap()
    }

    async fn run<T: Send + 'static>(&self, work: impl FnOnce(&mut Lab) -> T + Send + 'static) -> T {
        self.downloads().run(work).await
    }
}

fn whole_file(file: [u8; 16]) -> Packet {
    FileStatus { file, part_count: 0, parts: Vec::new() }.encode().unwrap()
}

#[tokio::test]
async fn should_let_a_source_that_calls_back_while_we_wait_in_its_queue_take_the_session_over() {
    let mut callback = Callback::new();
    callback.given_two_sources().await;
    callback.given_the_download_manager_with_a_listener();
    callback.given_a_paused_download_and_a_download_from_both_sources([0x41; 16], [0x42; 16]).await;
    callback.given_the_first_source_is_still_asked().await;
    callback.given_the_second_source_keeps_us_in_its_queue().await;
    callback.given_the_first_source_called_back_and_was_refused().await;
    callback.given_the_second_source_called_back_and_was_adopted().await;
    callback.given_the_queued_connection_ended().await;

    callback.when_the_second_source_gives_us_a_slot_over_its_call().await;

    callback.then_we_request_parts_without_asking_for_the_slot_it_gave();
    callback.then_we_do_not_ask_for_the_file_again();
}

#[tokio::test]
async fn should_ask_a_client_connected_to_us_over_its_own_connection() {
    let mut callback = Callback::new();
    callback.given_a_source().await;
    callback.given_the_download_manager_with_a_listener();
    callback.given_the_source_connected_to_us_for_our_files().await;

    callback.when_a_download_from_the_source_is_added("asked.bin", [0x43; 16]).await;

    callback.then_the_source_is_asked_for_the_file_over_its_own_connection().await;
}

#[tokio::test]
async fn should_take_a_firewalled_source_calling_back_only_with_its_server_and_hash_and_ask_it_through_the_server_again()
 {
    let mut callback = Callback::new();
    callback.given_a_source().await;
    callback.given_the_download_manager_with_a_listener();
    callback.given_two_downloads_with_firewalled_sources_from_the_server([0x44; 16], [0x45; 16]).await;
    callback.given_both_sources_were_asked_through_the_server().await;
    callback.given_the_second_download_paused().await;
    callback.given_calls_back_with_another_client_id_server_or_hash_were_refused().await;
    callback.given_the_source_called_back_with_its_server_and_hash_and_was_adopted().await;
    callback.given_the_source_kept_us_in_its_queue_and_hung_up().await;

    callback.when_it_is_time_to_ask_the_source_again();

    callback.then_the_server_is_asked_for_callbacks_of(&[5000, 6000, 5000]).await;
}

#[tokio::test]
async fn should_keep_a_source_asked_obfuscated_connecting_until_it_answers() {
    let mut callback = Callback::new();
    callback.given_a_source_supporting_obfuscation().await;
    callback.given_the_download_manager_with_a_listener();
    callback.given_a_download_from_the_source("crypt.bin", [0x47; 16]).await;
    callback.given_the_source_kept_us_in_its_queue_over_a_plain_connection().await;
    callback.given_the_source_is_asked_again_obfuscated_and_does_not_answer().await;

    callback.when_the_download_is_paused_before_the_source_answers().await;

    callback.then_the_source_is(SourceState::Waiting).await;
    callback.then_the_download_is(DownloadStatus::Paused).await;
}
