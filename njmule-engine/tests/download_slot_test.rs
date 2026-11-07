// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's DownloadSlotTest, under its names: a scripted source gives the downloads a slot, then keeps or
//! loses it by what it sends and when, on the downloads' clock.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::panic
)]

mod download_lab;
mod scripted_source;

use std::time::Duration;

use download_lab::Downloads;
use njmule_engine::api::download_info::SourceState;
use njmule_engine::download::download_session::{DATA_TIMEOUT, MAX_UNEXPECTED, UNEXPECTED_GAP};
use njmule_engine::files::hash::ed2k::{self, EMBLOCKSIZE};
use njmule_engine::protocol::ed2k::client_messages::{CompressedPart, FileStatus, Range, RequestParts, SendingPart};
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, protocol};
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};
use scripted_source::{Link, ScriptedSource, WAIT};

const OUR_ID: u32 = u32::from_le_bytes([198, 51, 100, 7]);
const PEER: [u8; 16] = [0x5b; 16];
const CHUNK: u64 = 10_240;
// Long enough for a few ticks of the downloads.
const QUIET: Duration = Duration::from_secs(1);

struct Slot {
    // Five blocks of one part, three asked for at a time.
    data: Vec<u8>,
    hash: [u8; 16],
    source: Option<ScriptedSource>,
    downloads: Option<Downloads>,
    link: Option<Link>,
    requested: Vec<Range>,
    sent: u64,
}

impl Slot {
    fn new() -> Self {
        let mut data = vec![0u8; 4 * EMBLOCKSIZE as usize + 30_000];
        StdRng::seed_from_u64(data.len() as u64).fill(&mut data[..]);
        let hash = ed2k::md4(&data);
        Self { data, hash, source: None, downloads: None, link: None, requested: Vec::new(), sent: 0 }
    }

    async fn given_a_source_that_gave_us_a_slot(&mut self) {
        let source = ScriptedSource::new(PEER, 0, 0).await;
        let downloads = Downloads::start([0xab; 16], OUR_ID, Downloads::settings());
        downloads.add(source.link("slot.bin", self.data.len() as u64, self.hash)).await;
        let mut link = source.accept().await;
        link.until(protocol::EDONKEY, client::SETREQFILEID).await;
        link.send(&FileStatus { file: self.hash, part_count: 0, parts: Vec::new() }.encode().unwrap()).await;
        link.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
        link.send(&Packet::empty(protocol::EDONKEY, client::ACCEPTUPLOADREQ)).await;
        let asked = link.until(protocol::EDONKEY, client::REQUESTPARTS).await.pop().unwrap();
        self.requested = RequestParts::decode(&asked).unwrap().ranges.into_iter().filter(|r| r.end > r.start).collect();
        assert!(!self.requested.is_empty());
        assert!(self.requested.iter().all(|range| range.end <= 3 * EMBLOCKSIZE));
        self.source = Some(source);
        self.downloads = Some(downloads);
        self.link = Some(link);
    }

    async fn given_the_source_sent_some_data_after(&mut self, wait: Duration) {
        self.send_some_data_after(wait).await;
    }

    async fn given_the_source_sent_a_compressed_block_that_does_not_inflate(&mut self, block: usize, chunks: u32) {
        self.send_a_compressed_block_that_does_not_inflate(block, chunks).await;
    }

    async fn when_the_source_talks_without_sending_data_for(&mut self, silence: Duration) {
        let mut ranking = PacketWriter::new();
        ranking.u16(1).bytes(&[0; 10]);
        self.link().send(&Packet::written(protocol::EMULE, extended::QUEUERANKING, ranking)).await;
        self.downloads().advance(silence);
        let mut rank = PacketWriter::new();
        rank.u32(1);
        self.link().send(&Packet::written(protocol::EDONKEY, client::QUEUERANK, rank)).await;
    }

    async fn when_the_source_sends_some_data_after(&mut self, wait: Duration) {
        self.send_some_data_after(wait).await;
    }

    async fn when_the_source_sends_data_we_did_not_ask_for(&mut self, times: u32) {
        self.send_data_we_did_not_ask_for(times).await;
    }

    async fn when_the_source_sends_data_we_did_not_ask_for_every(&mut self, gap: Duration, times: u32) {
        for _ in 0..times {
            self.send_data_we_did_not_ask_for(1).await;
            // Data asked for, behind it, tells when it was read.
            self.send_some_data_after(Duration::ZERO).await;
            self.downloads().advance(gap);
        }
    }

    async fn when_the_source_sends_a_compressed_block_that_does_not_inflate(&mut self, block: usize, chunks: u32) {
        self.send_a_compressed_block_that_does_not_inflate(block, chunks).await;
    }

    async fn then_the_block_is_asked_for_again(&mut self, block: usize) {
        let wanted = self.requested[block];
        let asked = self.link().until(protocol::EDONKEY, client::REQUESTPARTS).await.pop().unwrap();
        assert!(RequestParts::decode(&asked).unwrap().ranges.contains(&wanted));
    }

    async fn then_the_download_has_no_source(&mut self) {
        self.downloads().wait_for("no source", |lab| lab.manager.sources().is_empty()).await;
    }

    async fn then_we_cancel_the_transfer(&mut self) {
        self.link().until(protocol::EDONKEY, client::CANCELTRANSFER).await;
    }

    async fn then_the_connection_closes(&mut self) {
        assert!(self.link().closes_within(WAIT).await);
    }

    async fn then_the_slot_is_kept(&mut self) {
        tokio::time::sleep(QUIET).await;
        assert_eq!(self.downloads().sources().await[0].state, SourceState::Downloading);
    }

    // The next chunk of the first block asked for, once the clock moved on.
    async fn send_some_data_after(&mut self, wait: Duration) {
        self.downloads().advance(wait);
        let start = self.requested[0].start + self.sent;
        let end = start + CHUNK;
        let part = SendingPart { file: self.hash, start, end, data: self.data[start as usize..end as usize].to_vec() };
        self.link().send(&part.encode(false).unwrap()).await;
        self.sent += CHUNK;
        let sent = self.sent;
        self.downloads()
            .wait_for("the data received", move |lab| lab.manager.sources().first().is_some_and(|s| s.received == sent))
            .await;
    }

    // A chunk of the last block, which is not asked for while the first three are.
    async fn send_data_we_did_not_ask_for(&mut self, times: u32) {
        let start = 4 * EMBLOCKSIZE;
        let end = start + CHUNK;
        let part = SendingPart { file: self.hash, start, end, data: self.data[start as usize..end as usize].to_vec() };
        let packet = part.encode(false).unwrap();
        for _ in 0..times {
            self.link().send(&packet).await;
        }
    }

    // A stream of `chunks` chunks for the block, whose first one is no zlib data.
    async fn send_a_compressed_block_that_does_not_inflate(&mut self, block: usize, chunks: u32) {
        let block_start = self.requested[block].start;
        let part = CompressedPart { file: self.hash, block_start, compressed_size: 64 * chunks, chunk: vec![0xFF; 64] };
        let packet = part.encode(false).unwrap();
        for _ in 0..chunks {
            self.link().send(&packet).await;
        }
    }

    fn downloads(&self) -> &Downloads {
        self.downloads.as_ref().unwrap()
    }

    fn link(&mut self) -> &mut Link {
        self.link.as_mut().unwrap()
    }
}

#[tokio::test]
async fn should_leave_a_slot_whose_blocks_bring_no_data_for_too_long_whatever_else_the_source_sends() {
    let mut slot = Slot::new();
    slot.given_a_source_that_gave_us_a_slot().await;

    slot.when_the_source_talks_without_sending_data_for(DATA_TIMEOUT).await;

    slot.then_we_cancel_the_transfer().await;
    slot.then_the_connection_closes().await;
}

#[tokio::test]
async fn should_keep_a_slot_whose_blocks_bring_data_in_time() {
    let mut slot = Slot::new();
    slot.given_a_source_that_gave_us_a_slot().await;
    slot.given_the_source_sent_some_data_after(DATA_TIMEOUT - Duration::from_secs(10)).await;

    slot.when_the_source_sends_some_data_after(DATA_TIMEOUT - Duration::from_secs(10)).await;

    slot.then_the_slot_is_kept().await;
}

#[tokio::test]
async fn should_cancel_a_transfer_that_brings_blocks_we_did_not_ask_for() {
    let mut slot = Slot::new();
    slot.given_a_source_that_gave_us_a_slot().await;

    slot.when_the_source_sends_data_we_did_not_ask_for(MAX_UNEXPECTED).await;

    slot.then_we_cancel_the_transfer().await;
    slot.then_the_connection_closes().await;
}

#[tokio::test]
async fn should_bear_data_we_did_not_ask_for_that_comes_now_and_then() {
    let mut slot = Slot::new();
    slot.given_a_source_that_gave_us_a_slot().await;

    slot.when_the_source_sends_data_we_did_not_ask_for_every(UNEXPECTED_GAP + Duration::from_secs(1), MAX_UNEXPECTED)
        .await;

    slot.then_the_slot_is_kept().await;
}

#[tokio::test]
async fn should_ask_again_for_a_block_whose_compressed_data_does_not_inflate_and_drop_the_rest_of_it_quietly() {
    let mut slot = Slot::new();
    slot.given_a_source_that_gave_us_a_slot().await;

    slot.when_the_source_sends_a_compressed_block_that_does_not_inflate(0, 4).await;

    slot.then_the_block_is_asked_for_again(0).await;
    slot.then_the_slot_is_kept().await;
}

#[tokio::test]
async fn should_drop_a_source_whose_compressed_data_does_not_inflate_again_and_again() {
    let mut slot = Slot::new();
    slot.given_a_source_that_gave_us_a_slot().await;
    slot.given_the_source_sent_a_compressed_block_that_does_not_inflate(0, 1).await;
    slot.given_the_source_sent_a_compressed_block_that_does_not_inflate(1, 1).await;

    slot.when_the_source_sends_a_compressed_block_that_does_not_inflate(2, 1).await;

    slot.then_the_connection_closes().await;
    slot.then_the_download_has_no_source().await;
}
