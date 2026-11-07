// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's DownloadA4afTest, under its names: one scripted client that is a source of two downloads, asked for
//! one at a time, on a clock the test moves.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::panic
)]

mod download_lab;
mod scripted_source;

use std::time::{Duration, Instant};

use download_lab::{Downloads, Lab};
use njmule_engine::api::download_info::{DownloadPriority, SourceInfo, SourceState};
use njmule_engine::download::download_manager::{EARLIEST_REASK, MIN_REASK, REASK_INTERVAL};
use njmule_engine::protocol::ed2k::client_messages::{self, FileStatus};
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, protocol};
use scripted_source::{Link, ScriptedSource, WAIT};

const OUR_ID: u32 = u32::from_le_bytes([198, 51, 100, 7]);
const PEER: [u8; 16] = [0x5d; 16];
const FIRST: [u8; 16] = [0x61; 16];
const SECOND: [u8; 16] = [0x62; 16];
const UNKNOWN: [u8; 16] = [0x63; 16];
const FIRST_NAME: &str = "first.bin";
const SECOND_NAME: &str = "second.bin";
const SIZE: u64 = 5000;
// Long enough for a few ticks of the downloads.
const QUIET: Duration = Duration::from_secs(1);

struct A4af {
    downloads: Option<Downloads>,
    client: Option<ScriptedSource>,
    link: Option<Link>,
    asked_for: [u8; 16],
    last_upload_request: Option<Packet>,
    swapped: usize,
    done: bool,
    answers: (usize, usize, bool, bool),
}

impl A4af {
    fn new() -> Self {
        Self {
            downloads: None,
            client: None,
            link: None,
            asked_for: [0; 16],
            last_upload_request: None,
            swapped: 0,
            done: false,
            answers: (0, 0, false, false),
        }
    }

    async fn given_a_client_that_is_a_source_of_two_downloads(
        &mut self,
        first: DownloadPriority,
        second: DownloadPriority,
    ) {
        let client = ScriptedSource::new(PEER, 0, 0).await;
        let downloads = Downloads::start([0xab; 16], OUR_ID, Downloads::settings());
        let (one, two) = (client.link(FIRST_NAME, SIZE, FIRST), client.link(SECOND_NAME, SIZE, SECOND));
        // In one go, so that no ask comes between.
        downloads
            .run(move |lab| {
                lab.add(&one);
                lab.add(&two);
                lab.manager.set_priority(&FIRST, first);
                lab.manager.set_priority(&SECOND, second);
            })
            .await;
        self.client = Some(client);
        self.downloads = Some(downloads);
    }

    async fn given_the_client_is_asked_for(&mut self, file: [u8; 16]) {
        self.when_the_client_is_asked().await;
        self.then_it_is_asked_for(file);
    }

    async fn given_the_client_queues_us_at(&mut self, rank: u16) {
        let asked_for = self.asked_for;
        self.link().send(&whole_file(asked_for)).await;
        self.last_upload_request = self.link().until(protocol::EDONKEY, client::STARTUPLOADREQ).await.pop();
        let mut ranking = PacketWriter::new();
        ranking.u16(rank).bytes(&[0; 10]);
        self.link().send(&Packet::written(protocol::EMULE, extended::QUEUERANKING, ranking)).await;
        self.await_source(asked_for, move |s| s.state == SourceState::Queued && s.queue_rank == u32::from(rank))
            .await;
    }

    async fn given_the_client_is_silent_after_the_upload_request(&mut self) {
        let asked_for = self.asked_for;
        self.link().send(&whole_file(asked_for)).await;
        self.last_upload_request = self.link().until(protocol::EDONKEY, client::STARTUPLOADREQ).await.pop();
        self.await_source(asked_for, |s| s.state == SourceState::Queued).await;
    }

    async fn given_the_connection_closes(&mut self) {
        self.link = None;
        let asked_for = self.asked_for;
        self.await_source(asked_for, |s| s.state == SourceState::Waiting).await;
    }

    async fn given_the_download_has_priority(&mut self, file: [u8; 16], priority: DownloadPriority) {
        self.run(move |lab| lab.manager.set_priority(&file, priority)).await;
    }

    async fn given_the_download_takes_a4af_sources_by_itself(&mut self, file: [u8; 16]) {
        self.when_the_download_takes_a4af_sources_by_itself(file).await;
    }

    async fn when_the_client_is_asked(&mut self) {
        self.link = Some(self.client().accept().await);
        self.asked_for = self.requested_file().await;
    }

    async fn when_the_client_has_no_part_we_need_of(&mut self, file: [u8; 16]) {
        self.link().send(&FileStatus { file, part_count: 1, parts: vec![false] }.encode().unwrap()).await;
    }

    async fn when_the_client_lacks(&mut self, file: [u8; 16]) {
        self.link().send(&client_messages::for_file(client::FILEREQANSNOFIL, &file)).await;
    }

    async fn when_the_client_is_asked_again_at_its_time(&mut self) {
        self.downloads().advance(REASK_INTERVAL);
        self.when_the_client_is_asked().await;
    }

    async fn when_the_download_is_paused(&mut self, file: [u8; 16]) {
        self.run(move |lab| {
            let ours = lab.ours.clone();
            lab.manager.pause(&file, &ours)
        })
        .await;
    }

    async fn when_the_download_is_cancelled(&mut self, file: [u8; 16]) {
        self.run(move |lab| {
            let ours = lab.ours.clone();
            lab.manager.cancel(&file, &ours)
        })
        .await;
    }

    async fn when_the_a4af_sources_are_swapped_to(&mut self, file: [u8; 16]) {
        self.swapped = self
            .run(move |lab| {
                let ours = lab.ours.clone();
                lab.manager.swap_sources_here(&file, &ours)
            })
            .await;
    }

    async fn when_the_sources_are_swapped_away_from(&mut self, file: [u8; 16]) {
        self.swapped = self
            .run(move |lab| {
                let ours = lab.ours.clone();
                lab.manager.swap_sources_away(&file, &ours)
            })
            .await;
    }

    async fn when_the_source_is_swapped_to(&mut self, file: [u8; 16]) {
        let address = self.client().address();
        self.done = self
            .run(move |lab| {
                let ours = lab.ours.clone();
                lab.manager.swap_source(&file, address, &ours)
            })
            .await;
    }

    async fn when_the_download_takes_a4af_sources_by_itself(&mut self, file: [u8; 16]) {
        self.run(move |lab| lab.manager.set_auto_swap(&file, true)).await;
    }

    async fn when_the_downloads_are_loaded_again(&mut self) {
        self.downloads.as_mut().unwrap().restart().await;
    }

    async fn when_every_swap_is_asked_for(&mut self, file: [u8; 16]) {
        let address = self.client().address();
        self.answers = self
            .run(move |lab| {
                let ours = lab.ours.clone();
                (
                    lab.manager.swap_sources_here(&file, &ours),
                    lab.manager.swap_sources_away(&file, &ours),
                    lab.manager.swap_source(&file, address, &ours),
                    lab.manager.set_auto_swap(&file, true),
                )
            })
            .await;
    }

    fn then_it_is_asked_for(&self, file: [u8; 16]) {
        assert_eq!(self.asked_for, file);
    }

    // Not before the shortest wait sources allow between two asks, with a margin.
    async fn then_the_client_is_asked_again_once_it_allows_for(&mut self, file: [u8; 16]) {
        self.downloads().advance(EARLIEST_REASK);
        self.when_the_client_is_asked().await;
        self.then_it_is_asked_for(file);
    }

    async fn then_the_client_is_not_asked_again_within(&self, wait: Duration) {
        self.downloads().advance(wait);
        assert!(!self.client().is_connected_to_within(QUIET).await);
    }

    async fn then_the_client_is_asked_again_at_its_time_for(&mut self, file: [u8; 16]) {
        self.when_the_client_is_asked_again_at_its_time().await;
        self.then_it_is_asked_for(file);
    }

    async fn then_the_source_cannot_be_swapped_again_to(&mut self, file: [u8; 16]) {
        self.when_the_source_is_swapped_to(file).await;
        self.then_the_swap_is_done(false);
    }

    async fn then_it_is_asked_on_the_same_connection_for(&mut self, file: [u8; 16]) {
        self.asked_for = self.requested_file().await;
        assert_eq!(self.asked_for, file);
    }

    async fn then_the_client_queues_us_for(&mut self, file: [u8; 16], rank: u16) {
        self.given_the_client_queues_us_at(rank).await;
        assert_eq!(client_messages::file_of(self.last_upload_request.as_ref().unwrap()).unwrap(), file);
    }

    async fn then_the_source_is_a4af_for(&self, file: [u8; 16], other: [u8; 16], other_name: &str) {
        let source = self.await_source(file, |s| s.state == SourceState::A4af).await;
        assert_eq!(source.asked_for, Some(other));
        assert_eq!(source.asked_for_name, other_name);
        assert_eq!(source.queue_rank, 0);
    }

    async fn then_the_source_is(&self, file: [u8; 16], state: SourceState) {
        let source = self.await_source(file, move |s| s.state == state).await;
        assert_eq!(source.asked_for, None);
    }

    async fn then_the_connection_closes(&mut self) {
        assert!(self.link().closes_within(WAIT).await);
    }

    async fn then_the_download_has_sources(&self, file: [u8; 16], count: usize) {
        self.downloads()
            .wait_for("the sources", move |lab| lab.manager.download(&file).is_some_and(|d| d.sources == count))
            .await;
    }

    fn then_swapped_sources(&self, count: usize) {
        assert_eq!(self.swapped, count);
    }

    fn then_the_swap_is_done(&self, expected: bool) {
        assert_eq!(self.done, expected);
    }

    async fn then_the_download_takes_a4af_sources_by_itself(&self, file: [u8; 16], expected: bool) {
        assert_eq!(self.run(move |lab| lab.manager.download(&file).map(|d| d.auto_swap)).await, Some(expected));
    }

    fn then_every_answer_is(&self, expected: (usize, usize, bool, bool)) {
        assert_eq!(self.answers, expected);
    }

    // The file of the next file request, read with what comes before it.
    async fn requested_file(&mut self) -> [u8; 16] {
        let request = self.link().until(protocol::EDONKEY, client::SETREQFILEID).await.pop().unwrap();
        client_messages::file_of(&request).unwrap()
    }

    fn client(&self) -> &ScriptedSource {
        self.client.as_ref().unwrap()
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

    // The client's entry as a source of `file`, once `wanted` holds of it.
    async fn await_source(
        &self,
        file: [u8; 16],
        wanted: impl Fn(&SourceInfo) -> bool + Send + Sync + Copy + 'static,
    ) -> SourceInfo {
        let deadline = Instant::now() + WAIT;
        loop {
            let seen =
                self.run(move |lab| lab.manager.sources().into_iter().find(|s| s.file == file && wanted(s))).await;
            if let Some(seen) = seen {
                return seen;
            }
            assert!(Instant::now() < deadline, "waited in vain for the source of {file:02x?}");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }
}

// The client has the whole file.
fn whole_file(file: [u8; 16]) -> Packet {
    FileStatus { file, part_count: 0, parts: Vec::new() }.encode().unwrap()
}

#[tokio::test]
async fn should_ask_a_client_that_is_a_source_of_two_downloads_for_one_at_a_time() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;

    a4af.when_the_client_is_asked().await;

    a4af.then_it_is_asked_for(FIRST);
    a4af.then_the_source_is_a4af_for(SECOND, FIRST, FIRST_NAME).await;
    a4af.then_the_source_is(FIRST, SourceState::Asking).await;
}

#[tokio::test]
async fn should_ask_for_the_download_of_higher_priority_first() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::High)
        .await;

    a4af.when_the_client_is_asked().await;

    a4af.then_it_is_asked_for(SECOND);
    a4af.then_the_source_is_a4af_for(FIRST, SECOND, SECOND_NAME).await;
}

#[tokio::test]
async fn should_ask_for_another_download_on_the_same_connection_when_the_client_has_no_part_we_need() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;
    a4af.given_the_client_is_asked_for(FIRST).await;

    a4af.when_the_client_has_no_part_we_need_of(FIRST).await;

    a4af.then_it_is_asked_on_the_same_connection_for(SECOND).await;
    a4af.then_the_client_queues_us_for(SECOND, 2).await;
    a4af.then_the_source_is_a4af_for(FIRST, SECOND, SECOND_NAME).await;
}

#[tokio::test]
async fn should_forget_the_source_of_a_download_it_lacks_and_ask_for_another_on_the_same_connection() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;
    a4af.given_the_client_is_asked_for(FIRST).await;

    a4af.when_the_client_lacks(FIRST).await;

    a4af.then_it_is_asked_on_the_same_connection_for(SECOND).await;
    a4af.then_the_download_has_sources(FIRST, 0).await;
    a4af.then_the_download_has_sources(SECOND, 1).await;
}

#[tokio::test]
async fn should_ask_again_for_the_download_where_the_client_keeps_us_in_a_good_place() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;
    a4af.given_the_client_is_asked_for(FIRST).await;
    a4af.given_the_client_queues_us_at(3).await;
    a4af.given_the_connection_closes().await;
    a4af.given_the_download_has_priority(SECOND, DownloadPriority::High).await;

    a4af.when_the_client_is_asked_again_at_its_time().await;

    a4af.then_it_is_asked_for(FIRST);
    a4af.then_the_source_is_a4af_for(SECOND, FIRST, FIRST_NAME).await;
}

#[tokio::test]
async fn should_ask_for_a_better_download_when_the_client_keeps_us_in_no_place() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;
    a4af.given_the_client_is_asked_for(FIRST).await;
    a4af.given_the_client_is_silent_after_the_upload_request().await;
    a4af.given_the_connection_closes().await;
    a4af.given_the_download_has_priority(SECOND, DownloadPriority::High).await;

    a4af.when_the_client_is_asked_again_at_its_time().await;

    a4af.then_it_is_asked_for(SECOND);
}

#[tokio::test]
async fn should_ask_for_another_download_once_the_one_asked_for_is_paused() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;
    a4af.given_the_client_is_asked_for(FIRST).await;
    a4af.given_the_client_queues_us_at(3).await;

    a4af.when_the_download_is_paused(FIRST).await;

    a4af.then_the_connection_closes().await;
    a4af.then_the_source_is_a4af_for(FIRST, SECOND, SECOND_NAME).await;
    a4af.then_the_source_is(SECOND, SourceState::Waiting).await;
    a4af.then_the_client_is_asked_again_once_it_allows_for(SECOND).await;
}

#[tokio::test]
async fn should_wait_a_minute_more_than_sources_allow_before_asking_for_another_download() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;
    a4af.given_the_client_is_asked_for(FIRST).await;
    a4af.given_the_client_queues_us_at(3).await;

    a4af.when_the_download_is_paused(FIRST).await;

    a4af.then_the_connection_closes().await;
    a4af.then_the_client_is_not_asked_again_within(MIN_REASK).await;
    a4af.then_the_client_is_asked_again_once_it_allows_for(SECOND).await;
}

#[tokio::test]
async fn should_ask_for_another_download_once_the_one_asked_for_is_cancelled() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;
    a4af.given_the_client_is_asked_for(FIRST).await;
    a4af.given_the_client_queues_us_at(3).await;

    a4af.when_the_download_is_cancelled(FIRST).await;

    a4af.then_the_connection_closes().await;
    a4af.then_the_source_is(SECOND, SourceState::Waiting).await;
    a4af.then_the_client_is_asked_again_once_it_allows_for(SECOND).await;
}

#[tokio::test]
async fn should_ask_the_sources_swapped_by_hand_for_their_new_download_despite_a_good_place() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;
    a4af.given_the_client_is_asked_for(FIRST).await;
    a4af.given_the_client_queues_us_at(3).await;
    a4af.given_the_connection_closes().await;

    a4af.when_the_a4af_sources_are_swapped_to(SECOND).await;

    a4af.then_swapped_sources(1);
    a4af.then_the_source_is_a4af_for(FIRST, SECOND, SECOND_NAME).await;
    a4af.then_the_client_is_asked_again_once_it_allows_for(SECOND).await;
}

#[tokio::test]
async fn should_swap_one_source_waiting_in_the_queue_by_hand() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;
    a4af.given_the_client_is_asked_for(FIRST).await;
    a4af.given_the_client_queues_us_at(3).await;

    a4af.when_the_source_is_swapped_to(SECOND).await;

    a4af.then_the_swap_is_done(true);
    a4af.then_the_connection_closes().await;
    a4af.then_the_source_is_a4af_for(FIRST, SECOND, SECOND_NAME).await;
    a4af.then_the_source_cannot_be_swapped_again_to(SECOND).await;
}

#[tokio::test]
async fn should_swap_the_sources_of_a_download_away_to_other_downloads() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;
    a4af.given_the_client_is_asked_for(FIRST).await;
    a4af.given_the_client_queues_us_at(3).await;
    a4af.given_the_connection_closes().await;

    a4af.when_the_sources_are_swapped_away_from(FIRST).await;

    a4af.then_swapped_sources(1);
    a4af.then_the_source_is_a4af_for(FIRST, SECOND, SECOND_NAME).await;
    a4af.then_the_client_is_asked_again_once_it_allows_for(SECOND).await;
}

#[tokio::test]
async fn should_take_the_free_a4af_sources_when_a_download_takes_them_by_itself() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;
    a4af.given_the_client_is_asked_for(FIRST).await;
    a4af.given_the_client_is_silent_after_the_upload_request().await;
    a4af.given_the_connection_closes().await;

    a4af.when_the_download_takes_a4af_sources_by_itself(SECOND).await;

    a4af.then_the_source_is_a4af_for(FIRST, SECOND, SECOND_NAME).await;
    a4af.then_the_download_takes_a4af_sources_by_itself(SECOND, true).await;
    a4af.then_the_client_is_asked_again_at_its_time_for(SECOND).await;
}

#[tokio::test]
async fn should_leave_a_client_that_keeps_us_in_a_good_place_when_a_download_takes_a4af_sources_by_itself() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;
    a4af.given_the_client_is_asked_for(FIRST).await;
    a4af.given_the_client_queues_us_at(3).await;
    a4af.given_the_connection_closes().await;

    a4af.when_the_download_takes_a4af_sources_by_itself(SECOND).await;

    a4af.then_the_source_is(FIRST, SourceState::Waiting).await;
    a4af.then_the_source_is_a4af_for(SECOND, FIRST, FIRST_NAME).await;
}

#[tokio::test]
async fn should_remember_that_a_download_takes_a4af_sources_by_itself() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;
    a4af.given_the_download_takes_a4af_sources_by_itself(SECOND).await;

    a4af.when_the_downloads_are_loaded_again().await;

    a4af.then_the_download_takes_a4af_sources_by_itself(SECOND, true).await;
    a4af.then_the_download_takes_a4af_sources_by_itself(FIRST, false).await;
}

#[tokio::test]
async fn should_change_nothing_for_an_unknown_download() {
    let mut a4af = A4af::new();
    a4af.given_a_client_that_is_a_source_of_two_downloads(DownloadPriority::Normal, DownloadPriority::Normal)
        .await;

    a4af.when_every_swap_is_asked_for(UNKNOWN).await;

    a4af.then_every_answer_is((0, 0, false, false));
}
