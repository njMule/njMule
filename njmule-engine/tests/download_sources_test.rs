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
use std::ops::ControlFlow;
use std::path::PathBuf;
use std::time::Duration;

use download_lab::{Downloads, Engine, Lab};
use njmule_engine::api::category;
use njmule_engine::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus, SourceInfo, SourceState};
use njmule_engine::api::ed2k_link::{FileLink, Source};
use njmule_engine::api::file_comment::FileComment;
use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::download::download_manager::REASK_INTERVAL;
use njmule_engine::download::download_manager::{self, CALLBACK_WAIT, DownloadEvent, MIN_REASK};
use njmule_engine::download::download_queue::SPACE_CHECK;
use njmule_engine::files::hash::aich_recovery;
use njmule_engine::files::hash::ed2k::EMBLOCKSIZE;
use njmule_engine::files::hash::file_hasher;
use njmule_engine::protocol::ed2k::client_messages::{
    self, CompressedPart, FileName, FileStatus, HashSet, Range, RequestParts, SendingPart,
};
use njmule_engine::protocol::ed2k::file_description;
use njmule_engine::protocol::ed2k::hello;
use njmule_engine::protocol::ed2k::server_messages::{FoundSources, Source as Found};
use njmule_engine::protocol::ed2k::source_exchange as exchange;
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, protocol};
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};
use scripted_source::{Link, ScriptedSource, WAIT};

const OUR_ID: u32 = u32::from_le_bytes([198, 51, 100, 7]);
const PEER: [u8; 16] = [0x5e; 16];
const UNKNOWN: [u8; 16] = [0x3a; 16];
const BLOCKED_IP: Ipv4Addr = Ipv4Addr::new(203, 0, 113, 9);

// A download of three blocks with a trusted AICH hash, whose first source, unable to send recovery data, sent the last
// block corrupt; a second source, able to, was then added by hand.
struct Recovery {
    tree_master: [u8; 20],
    recovery_data: Vec<u8>,
    first: Link,
    helper: ScriptedSource,
}

struct Sources {
    downloads: Option<Downloads>,
    min_free: u64,
    max_sources: usize,
    our_ipv6: Option<Ipv6Addr>,
    incoming: Option<PathBuf>,
    data: Vec<u8>,
    recovery: Option<Recovery>,
    second: Option<Link>,
    request: Option<Packet>,
    others: Vec<ScriptedSource>,
    closed_port: u16,
    callbacks_before: usize,
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
            max_sources: 100,
            our_ipv6: None,
            incoming: None,
            data: Vec::new(),
            recovery: None,
            second: None,
            request: None,
            others: Vec::new(),
            closed_port: 0,
            callbacks_before: 0,
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

    fn given_the_data(&mut self, data: Vec<u8>) {
        self.data = data;
    }

    fn given_a_file_where_the_incoming_directory_should_be(&mut self) {
        let blocker = std::env::temp_dir().join(format!("njmule-blocker-{}", std::process::id()));
        let blocker = tempfile::Builder::new()
            .prefix("blocker")
            .tempfile()
            .map_or(blocker, |file| file.into_temp_path().keep().unwrap());
        std::fs::write(&blocker, "a file where the directory should be").unwrap();
        self.incoming = Some(blocker);
    }

    async fn given_a_download_of_the_data_from_the_source(&mut self, name: &str) {
        self.hash = njmule_engine::files::hash::ed2k::md4(&self.data);
        let link = self.source().link(name, self.data.len() as u64, self.hash);
        self.downloads().add(link).await;
    }

    async fn given_the_source_gave_us_a_slot(&mut self) {
        self.given_we_asked_the_source_for_an_upload_slot().await;
        self.link().send(&Packet::empty(protocol::EDONKEY, client::ACCEPTUPLOADREQ)).await;
    }

    async fn given_the_whole_file_asked_for(&mut self) {
        let length = self.data.len() as u64;
        assert_eq!(requested(self.link()).await, [Range { start: 0, end: length }]);
    }

    async fn given_the_source_shown_as_downloading(&mut self) {
        self.await_source(|s| s.state == SourceState::Downloading).await;
        let hash = self.hash;
        assert_eq!(self.run(move |lab| lab.manager.download(&hash).unwrap().transferring).await, 1);
        assert!(self.run(|lab| lab.manager.describe()).await.contains("DOWNLOADING [0-5000 missing"));
    }

    async fn given_the_source_ended_our_slot(&mut self) {
        self.link().send(&Packet::empty(protocol::EDONKEY, client::OUTOFPARTREQS)).await;
        self.await_source(|s| s.state == SourceState::Queued).await;
    }

    async fn given_the_source_gave_us_the_slot_again(&mut self) {
        self.link().send(&Packet::empty(protocol::EDONKEY, client::ACCEPTUPLOADREQ)).await;
    }

    async fn given_the_blocks_let_go_asked_for_again(&mut self) {
        let length = self.data.len() as u64;
        assert_eq!(requested(self.link()).await, [Range { start: 0, end: length }], "the blocks let go");
    }

    async fn given_the_source_sent_the_file_among_data_that_does_not_fit(&mut self) {
        let (hash, data) = (self.hash, self.data.clone());
        let length = data.len() as u64;
        let other = SendingPart { file: [0x33; 16], start: 0, end: 10, data: vec![0; 10] };
        self.link().send(&other.encode(false).unwrap()).await;
        let beyond = SendingPart { file: hash, start: 4990, end: 5010, data: vec![0; 20] };
        self.link().send(&beyond.encode(false).unwrap()).await;
        let whole = SendingPart { file: hash, start: 0, end: length, data };
        self.link().send(&whole.encode(true).unwrap()).await;
    }

    async fn given_the_download_failed_with(&mut self, reason: &str) {
        let hash = self.hash;
        let failed = self
            .wait_for_event("failed", move |event| match event {
                DownloadEvent::Failed { info, reason } if info.hash == hash => Some(reason.clone()),
                _ => None,
            })
            .await;
        assert!(failed.contains(reason), "{failed}");
    }

    async fn given_the_download_in_error(&mut self) {
        let hash = self.hash;
        assert_eq!(self.run(move |lab| lab.manager.download(&hash).unwrap().status).await, DownloadStatus::Error);
    }

    async fn given_its_sessions_ended(&mut self) {
        assert!(self.link().closes_within(WAIT).await, "its sessions end");
    }

    fn given_the_incoming_directory_freed(&mut self) {
        std::fs::remove_file(self.incoming.as_ref().unwrap()).unwrap();
    }

    async fn given_we_asked_the_source_for_the_file(&mut self) {
        self.given_the_source_asked_for_the_file().await;
    }

    // Nothing of this changes anything: a rank before the queue, an answer not asked for, a hash set not needed.
    async fn given_the_source_sent_what_does_not_fit_before_queuing_us(&mut self) {
        let hash = self.hash;
        let mut ranking = PacketWriter::new();
        ranking.u16(9).bytes(&[0; 10]);
        self.link().send(&Packet::written(protocol::EMULE, extended::QUEUERANKING, ranking)).await;
        let mut answer = PacketWriter::new();
        answer.hash16(&hash).u16(0).hash20(&[0x08; 20]);
        self.link().send(&Packet::written(protocol::EMULE, extended::AICHANSWER, answer)).await;
        let set = HashSet { file: hash, parts: vec![[0x36; 16]] };
        self.link().send(&set.encode().unwrap()).await;
        self.link().send(&whole_file(hash)).await;
        self.link().send(&whole_file(hash)).await;
        self.link().until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
    }

    async fn given_we_were_queued_with_nothing_of_it_taken(&mut self) {
        assert_eq!(self.await_source(|s| s.state == SourceState::Queued).await.queue_rank, 0);
        let hash = self.hash;
        assert!(self.run(move |lab| lab.manager.find(&hash).unwrap().file.part_hashes().is_empty()).await);
    }

    // Neither does the end of a slot not given yet, nor data of no block asked for.
    async fn given_the_source_ended_a_slot_not_given_yet_then_gave_us_one(&mut self) {
        self.link().send(&Packet::empty(protocol::EDONKEY, client::OUTOFPARTREQS)).await;
        self.link().send(&Packet::empty(protocol::EDONKEY, client::ACCEPTUPLOADREQ)).await;
        let length = self.data.len() as u64;
        assert_eq!(requested(self.link()).await, [Range { start: 0, end: length }]);
    }

    async fn given_the_source_sent_data_of_no_block_asked_for_then_the_first_bytes(&mut self) {
        let hash = self.hash;
        let odd = CompressedPart { file: hash, block_start: 7, compressed_size: 10, chunk: vec![0; 10] };
        self.link().send(&odd.encode(false).unwrap()).await;
        let other = CompressedPart { file: [0x37; 16], block_start: 0, compressed_size: 10, chunk: vec![0; 10] };
        self.link().send(&other.encode(false).unwrap()).await;
        let empty = SendingPart { file: hash, start: 0, end: 0, data: Vec::new() };
        self.link().send(&empty.encode(false).unwrap()).await;
        let first = SendingPart { file: hash, start: 0, end: 1000, data: self.data[..1000].to_vec() };
        self.link().send(&first.encode(false).unwrap()).await;
        self.downloads()
            .wait_for("the first bytes written", move |lab| {
                lab.manager.download(&hash).is_some_and(|d| d.completed == 1000)
            })
            .await;
    }

    async fn given_a_download_with_a_corrupt_part_held_for_its_recovery_data(&mut self, seed: u64) {
        self.recovery(seed).await;
    }

    async fn given_the_second_source_asked_for_recovery_data(&mut self) {
        let hash = self.hash;
        let mut second = self.recovery.as_ref().unwrap().helper.accept().await;
        second.until(protocol::EDONKEY, client::SETREQFILEID).await;
        second.send(&whole_file(hash)).await;
        self.request = second.until(protocol::EMULE, extended::AICHREQUEST).await.pop();
        self.second = Some(second);
    }

    fn given_the_recovery_data_asked_for_the_file(&mut self) {
        assert_eq!(self.request.as_ref().unwrap().reader().hash16().unwrap(), self.hash);
    }

    async fn given_the_second_source_told_another_aich_hash(&mut self) {
        let hash = self.hash;
        let mut told = PacketWriter::new();
        told.hash16(&hash).hash20(&[0x29; 20]);
        self.second().send(&Packet::written(protocol::EMULE, extended::AICHFILEHASHANS, told)).await;
    }

    async fn given_the_second_source_sent_the_recovery_data_of_the_part(&mut self) {
        let recovery = self.recovery.as_ref().unwrap();
        let answer = aich_answer(self.hash, recovery.tree_master, &recovery.recovery_data);
        self.second().send(&answer).await;
    }

    async fn given_only_the_corrupt_block_asked_for_again(&mut self) {
        let length = self.data.len() as u64;
        let asked = requested(&mut self.recovery.as_mut().unwrap().first).await;
        assert_eq!(asked, [Range { start: 2 * EMBLOCKSIZE, end: length }], "only the corrupt block again");
    }

    async fn given_the_second_source_sent_recovery_data_that_proves_nothing(&mut self) {
        let master = self.recovery.as_ref().unwrap().tree_master;
        let answer = aich_answer(self.hash, master, &random(200, 7));
        self.second().send(&answer).await;
    }

    async fn given_the_part_asked_for_again_whole(&mut self) {
        let whole = self.whole_part();
        assert_eq!(requested(&mut self.recovery.as_mut().unwrap().first).await, whole);
    }

    async fn given_sources_without_the_file_and_out_of_reach(&mut self) {
        for _ in 0..4 {
            self.others.push(ScriptedSource::new(PEER, 0, 0).await);
        }
        let probe = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        self.closed_port = probe.local_addr().unwrap().port();
    }

    async fn given_a_download_from_all_of_them(&mut self, file: [u8; 16]) {
        self.hash = file;
        let mut sources: Vec<Source> =
            self.others.iter().map(|other| Source { host: "127.0.0.1".to_owned(), port: other.port() }).collect();
        sources.push(Source { host: "127.0.0.1".to_owned(), port: self.closed_port });
        let gone = FileLink::new("gone.bin".to_owned(), 5000, file, None, Vec::new(), sources, Vec::new()).unwrap();
        self.downloads().add(gone).await;
    }

    // The first has no such file, the second tells parts of another size, the third has no part we need.
    async fn given_the_sources_answered_without_a_file_we_can_use(&mut self) -> Vec<Link> {
        let hash = self.hash;
        let mut no_file = self.others[0].accept().await;
        no_file.until(protocol::EDONKEY, client::SETREQFILEID).await;
        no_file.send(&client_messages::for_file(client::FILEREQANSNOFIL, &[0x35; 16])).await;
        no_file.send(&client_messages::for_file(client::FILEREQANSNOFIL, &hash)).await;
        let mut too_many = self.others[1].accept().await;
        too_many.until(protocol::EDONKEY, client::SETREQFILEID).await;
        too_many.send(&FileStatus { file: hash, part_count: 5, parts: vec![true; 5] }.encode().unwrap()).await;
        let mut nothing = self.others[2].accept().await;
        nothing.until(protocol::EDONKEY, client::SETREQFILEID).await;
        nothing.send(&FileStatus { file: hash, part_count: 1, parts: vec![false] }.encode().unwrap()).await;
        vec![no_file, too_many, nothing]
    }

    async fn given_the_silent_source_refused_us(&mut self) {
        self.others[3].refuse_next().await;
    }

    async fn given_the_sources_without_a_file_we_can_use_hung_up_on(&mut self, mut links: Vec<Link>) {
        for link in &mut links {
            assert!(link.closes_within(WAIT).await);
        }
    }

    async fn given_the_useless_and_the_unreachable_sources_kept(&mut self) {
        let hash = self.hash;
        self.downloads()
            .wait_for("three sources", move |lab| lab.manager.download(&hash).is_some_and(|d| d.sources == 3))
            .await;
        let mut kept = self.run(|lab| lab.manager.sources().into_iter().map(|s| s.address).collect::<Vec<_>>()).await;
        kept.sort();
        let mut expected = vec![
            format!("127.0.0.1:{}", self.others[2].port()),
            format!("127.0.0.1:{}", self.others[3].port()),
            format!("127.0.0.1:{}", self.closed_port),
        ];
        expected.sort();
        assert_eq!(kept, expected);
    }

    // Three failures in a row drop a source; the one without parts we need waits its time.
    async fn given_the_unreachable_sources_failed_twice(&mut self) {
        self.await_failures(1).await;
        self.downloads().advance(MIN_REASK);
        self.others[3].refuse_next().await;
        self.await_failures(2).await;
    }

    fn given_a_firewalled_source_the_server_cannot_ask(&mut self, low_id: u32) {
        self.downloads().server.lock().unwrap().refused.push(low_id);
    }

    async fn given_the_server_found_firewalled_sources(&mut self) {
        let hash = self.hash;
        let blocked = u32::from_le_bytes([203, 0, 113, 9]);
        self.found(hash, vec![(5000, Some(PEER)), (5000, Some(PEER)), (6000, None), (0, None), (blocked, None)])
            .await;
        self.found([0x37; 16], vec![(7000, None)]).await;
    }

    async fn given_each_asked_for_once_one_a_tick(&mut self) {
        self.await_callbacks(2).await;
        assert_eq!(self.callbacks(), [5000, 6000], "one a tick, each once");
    }

    // One the server could not ask is forgotten, and may be asked for again.
    async fn given_one_the_server_could_not_ask_asked_again_when_named_again(&mut self) {
        let hash = self.hash;
        self.found(hash, vec![(6000, None)]).await;
        self.await_callbacks(3).await;
    }

    // One that does not call back in time is forgotten too: the server names it again, and it is asked again.
    async fn given_one_that_did_not_call_back_in_time_asked_again_when_named_again(&mut self) {
        self.downloads().advance(CALLBACK_WAIT);
        let hash = self.hash;
        let deadline = std::time::Instant::now() + WAIT;
        while self.callbacks().iter().rposition(|id| *id == 5000) == Some(0) {
            assert!(std::time::Instant::now() < deadline, "asked once more");
            self.found(hash, vec![(5000, Some(PEER))]).await;
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }

    async fn given_a_download(&mut self, name: &str, file: [u8; 16]) {
        self.hash = file;
        self.downloads().add(FileLink::of(name, 5000, file).unwrap()).await;
    }

    async fn given_the_download_paused(&mut self) {
        let hash = self.hash;
        self.run(move |lab| {
            let ours = lab.ours.clone();
            lab.manager.pause(&hash, &ours)
        })
        .await;
    }

    async fn when_the_download_is_resumed(&mut self) {
        let hash = self.hash;
        self.accepted = self
            .run(move |lab| {
                let ours = lab.ours.clone();
                lab.manager.resume(&hash, &ours)
            })
            .await;
    }

    async fn when_the_source_sends_a_status_that_does_not_decode(&mut self) {
        let mut status = PacketWriter::new();
        status.hash16(&self.hash).u16(5);
        self.link().send(&Packet::written(protocol::EDONKEY, client::FILESTATUS, status)).await;
    }

    async fn when_the_first_source_sends_the_block_again(&mut self) {
        let (hash, data) = (self.hash, self.data.clone());
        let length = data.len() as u64;
        send_blocks(&mut self.recovery.as_mut().unwrap().first, hash, &data, 2 * EMBLOCKSIZE, length).await;
    }

    // Corrupt once more: its recovery was tried, so it comes again whole at once.
    async fn when_the_first_source_sends_the_part_corrupt_again(&mut self) {
        let hash = self.hash;
        let mut corrupt = self.data.clone();
        corrupt[0] ^= 0x55;
        let length = corrupt.len() as u64;
        send_blocks(&mut self.recovery.as_mut().unwrap().first, hash, &corrupt, 0, length).await;
    }

    async fn when_a_part_arrives_with_a_bad_byte(&mut self) {
        self.recovery(8).await;
    }

    async fn when_the_unreachable_sources_are_asked_again(&mut self) {
        self.downloads().advance(MIN_REASK);
        self.others[3].refuse_next().await;
    }

    async fn when_the_server_finds(&mut self, low_id: u32) {
        self.callbacks_before = self.callbacks().len();
        let hash = self.hash;
        self.found(hash, vec![(low_id, None)]).await;
    }

    fn given_the_manager(&mut self) {
        let engine = Engine {
            min_free: self.min_free,
            incoming: self.incoming.clone(),
            our_ipv6: self.our_ipv6,
            blocked: vec![u32::from(BLOCKED_IP)],
            ..Engine::default()
        };
        let mut settings = Downloads::settings();
        settings.max_sources_per_file = self.max_sources;
        self.downloads = Some(Downloads::start_with([0xab; 16], OUR_ID, settings, engine));
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

    fn given_we_have_the_ipv6_address(&mut self, address: &str) {
        self.our_ipv6 = Some(address.parse().unwrap());
    }

    async fn given_a_source_announcing_the_ipv6_address(&mut self, misc2: u32, address: &str) {
        let source = ScriptedSource::new(PEER, 0, misc2).await;
        source.announce_ipv6(address.parse().unwrap());
        self.source = Some(source);
    }

    fn given_a_maximum_of_sources(&mut self, max: usize) {
        self.max_sources = max;
    }

    async fn given_we_asked_the_source_for_other_sources_the_extended_way(&mut self) {
        let mut link = self.source().accept().await;
        let asked = link.until(protocol::EMULE, extended::REQUESTSOURCES2).await.pop().unwrap();
        assert_eq!(asked.payload()[0], exchange::EXTENDED_VERSION, "version 1 asks for the extended form");
        self.link = Some(link);
    }

    async fn given_we_asked_the_source_for_the_aich_hash_and_other_sources(&mut self) {
        let mut link = self.source().accept().await;
        let asked = names(&link.until(protocol::EMULE, extended::REQUESTSOURCES2).await);
        assert_eq!(asked, ["REQUESTFILENAME", "SETREQFILEID", "AICHFILEHASHREQ", "REQUESTSOURCES2"]);
        self.link = Some(link);
    }

    async fn given_the_source_shown_as_asked(&mut self) {
        let asking = self.await_source(|s| s.state == SourceState::Asking).await;
        assert_eq!(asking.nick.as_deref(), Some("scripted"));
        let described = self.run(|lab| lab.manager.describe()).await;
        assert!(described.contains("talk.bin missing 5000:") && described.contains("REQUESTING"), "{described}");
    }

    // From now on only sources known to take obfuscation are asked: none of those it tells of, nor this one.
    async fn given_only_sources_known_to_take_obfuscation_asked(&mut self) {
        self.run(|lab| lab.settings.obfuscation = ObfuscationMode::Required).await;
    }

    async fn given_the_source_told_its_comments_and_other_sources_and_queued_us_at(&mut self, rank: u32) {
        let hash = self.hash;
        let link = self.link();
        link.send(&file_description::encode(&FileComment::of("a fine copy", 4)).unwrap()).await;
        link.send(&file_description::encode(&FileComment::of("free SPAM inside", 1)).unwrap()).await;
        link.send(&file_description::encode(&FileComment::of("", 0)).unwrap()).await;
        link.send(&aich_hash([0x32; 16], [0; 20])).await;
        link.send(&aich_hash(hash, [0; 20])).await;
        let answer = exchange::Answer {
            version: 4,
            file: hash,
            sources: vec![
                exchange::Source { low_id: 1234, ..exchanged(Ipv4Addr::UNSPECIFIED) },
                exchanged(BLOCKED_IP),
                exchanged(Ipv4Addr::new(10, 1, 2, 3)),
                exchanged(Ipv4Addr::new(192, 0, 2, 50)),
                exchanged(Ipv4Addr::new(192, 0, 2, 51)),
            ],
        };
        link.send(&answer.encode().unwrap()).await;
        let again = exchange::Answer { version: 4, file: hash, sources: vec![exchanged(Ipv4Addr::new(192, 0, 2, 52))] };
        link.send(&again.encode().unwrap()).await;
        link.send(&whole_file([0x32; 16])).await;
        link.send(&whole_file(hash)).await;
        link.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
        let mut ranked = PacketWriter::new();
        ranked.u32(rank);
        link.send(&Packet::written(protocol::EDONKEY, client::QUEUERANK, ranked)).await;
    }

    // Queued from the upload request on; the rank comes after.
    async fn given_we_were_queued_at(&mut self, rank: u32) {
        let queued = self.await_source(|s| s.state == SourceState::Queued && s.queue_rank != 0).await;
        assert_eq!((queued.queue_rank, queued.parts), (rank, 1));
    }

    async fn given_the_good_comment_and_the_public_sources_it_told_of_taken(&mut self) {
        let hash = self.hash;
        assert_eq!(self.run(move |lab| lab.manager.download(&hash).unwrap().sources).await, 3, "the public sources");
        let comments = self.run(move |lab| lab.manager.comments(&hash)).await;
        assert_eq!(comments.len(), 1);
        assert_eq!(comments[0].user, "scripted");
        assert_eq!(comments[0].comment, FileComment::of("a fine copy", 4));
    }

    async fn given_the_aich_hash_of_one_source_not_trusted(&mut self) {
        let hash = self.hash;
        let aich = self.run(move |lab| lab.manager.find(&hash).unwrap().file.aich_hash()).await;
        assert_eq!(aich, None, "one source alone is no reason to trust a hash");
    }

    async fn given_the_source_not_passed_on_yet(&mut self) {
        let hash = self.hash;
        assert!(self.run(move |lab| lab.manager.exchangeable(&hash)).await.is_empty(), "not before its parts");
    }

    async fn given_the_source_can_be_passed_on(&mut self) {
        let hash = self.hash;
        let passed = self.run(move |lab| lab.manager.exchangeable(&hash)).await;
        assert_eq!(passed.len(), 1);
        assert_eq!((passed[0].port, passed[0].user_hash), (self.source().port(), Some(PEER)));
    }

    // Asked again in time, plain once allowed: not for more sources, asked for less than 40 minutes ago.
    async fn given_plain_connections_allowed_again(&mut self) {
        self.run(|lab| lab.settings.obfuscation = ObfuscationMode::Supported).await;
    }

    fn given_time_passed(&mut self, duration: Duration) {
        self.downloads().advance(duration);
    }

    async fn given_the_source_asked_again_but_not_for_more_sources(&mut self) {
        let asked = self.ask_for_an_upload_slot().await;
        assert!(!asked.contains(&"REQUESTSOURCES2".to_owned()), "{asked:?}");
    }

    async fn given_the_source_asked_for_no_more_sources(&mut self) {
        let asked = self.ask_for_an_upload_slot().await;
        assert!(!asked.contains(&"REQUESTSOURCES2".to_owned()), "a full list wants no more sources");
    }

    async fn when_the_source_answers_the_extended_way_with_a_high_id_a_low_id_and_a_source_at_an_ipv6_address_alone(
        &mut self,
    ) {
        let hash = self.hash;
        let answer = exchange::Answer {
            version: exchange::EXTENDED_VERSION,
            file: hash,
            sources: vec![
                exchange::Source {
                    ipv6: Some("2001:db8::60".parse().unwrap()),
                    ..exchanged(Ipv4Addr::new(192, 0, 2, 60))
                },
                exchange::Source::ipv6_only("2001:db8::61".parse().unwrap(), download_lab::PORT, None),
                exchange::Source {
                    low_id: 1234,
                    ipv6: Some("2001:db8::62".parse().unwrap()),
                    ..exchanged(Ipv4Addr::UNSPECIFIED)
                },
                // Ourselves, at our own IPv6 address.
                exchange::Source::ipv6_only("2001:db8::99".parse().unwrap(), download_lab::PORT, None),
            ],
        };
        self.link().send(&answer.encode_extended().unwrap()).await;
    }

    async fn when_the_download_is_paused(&mut self) {
        let hash = self.hash;
        self.run(move |lab| {
            let ours = lab.ours.clone();
            lab.manager.pause(&hash, &ours)
        })
        .await;
    }

    async fn when_the_server_finds_a_low_id(&mut self, low_id: u32) {
        self.callbacks_before = self.callbacks().len();
        let hash = self.hash;
        self.found(hash, vec![(low_id, None)]).await;
    }

    async fn then_a_source_is_shown_at(&self, address: &str) {
        let wanted = address.to_owned();
        let shown = self.run(|lab| lab.manager.sources().into_iter().map(|s| s.address).collect::<Vec<_>>()).await;
        assert!(shown.contains(&wanted), "{shown:?}");
    }

    async fn then_no_callback_is_asked_for(&self) {
        tokio::time::sleep(Duration::from_millis(1500)).await;
        assert!(self.callbacks().is_empty());
    }

    // Asks for the file and for an upload slot; the names of the packets read.
    async fn ask_for_an_upload_slot(&mut self) -> Vec<String> {
        let hash = self.hash;
        let mut link = self.source().accept().await;
        let mut asked = names(&link.until(protocol::EDONKEY, client::SETREQFILEID).await);
        link.send(&whole_file(hash)).await;
        asked.extend(names(&link.until(protocol::EDONKEY, client::STARTUPLOADREQ).await));
        self.link = Some(link);
        asked
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

    async fn when_every_change_is_asked_for_the_unknown_download(&mut self) {
        self.results = self
            .run(|lab| {
                let (ours, settings) = (lab.ours.clone(), lab.settings.clone());
                let at = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), download_lab::PORT);
                vec![
                    lab.manager.stop_by_schedule(&UNKNOWN, &ours),
                    lab.manager.set_priority(&UNKNOWN, DownloadPriority::High),
                    lab.manager.cancel(&UNKNOWN, &ours),
                    lab.manager.resume(&UNKNOWN, &ours),
                    lab.manager.set_preview_first(&UNKNOWN, true),
                    lab.manager.set_category(&UNKNOWN, category::NONE),
                    lab.manager.add_source_by_hand(&UNKNOWN, at, &settings),
                ]
            })
            .await;
    }

    async fn then_the_unknown_download_has_no_comments(&self) {
        assert!(self.run(|lab| lab.manager.comments(&UNKNOWN)).await.is_empty());
    }

    async fn then_the_unknown_download_has_no_source_to_pass_on(&self) {
        assert!(self.run(|lab| lab.manager.exchangeable(&UNKNOWN)).await.is_empty());
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

    fn then_it_is_accepted_to_resume(&self) {
        assert!(self.accepted);
    }

    async fn then_the_download_completes_with_the_data(&self) {
        let hash = self.hash;
        let path = self
            .wait_for_event("completed", move |event| match event {
                DownloadEvent::Completed { info, path, .. } if info.hash == hash => Some(path.clone()),
                _ => None,
            })
            .await;
        assert_eq!(std::fs::read(path).unwrap(), self.data);
    }

    async fn then_the_download_is_gone(&self) {
        let hash = self.hash;
        assert!(self.run(move |lab| lab.manager.download(&hash).is_none()).await);
    }

    async fn then_the_file_is_no_longer_found(&self) {
        let hash = self.hash;
        assert!(self.run(move |lab| lab.manager.find(&hash).is_none()).await);
    }

    async fn then_the_connection_closes(&mut self) {
        assert!(self.link().closes_within(WAIT).await);
    }

    async fn then_the_download_has_completed(&self, bytes: u64) {
        let hash = self.hash;
        assert_eq!(self.run(move |lab| lab.manager.download(&hash).unwrap().completed).await, bytes);
    }

    async fn then_it_was_told_that_the_part_is_corrupt(&self, part: usize) {
        let told = self
            .wait_for_event("corrupt", |event| match event {
                DownloadEvent::CorruptPart { info, part } => Some((info.name.clone(), *part)),
                _ => None,
            })
            .await;
        assert_eq!(told, ("recover.bin".to_owned(), part));
    }

    async fn then_the_part_is_asked_for_again_whole(&mut self) {
        self.given_the_part_asked_for_again_whole().await;
    }

    async fn then_only_the_useless_source_is_left(&self) {
        let hash = self.hash;
        self.downloads()
            .wait_for("one source", move |lab| lab.manager.download(&hash).is_some_and(|d| d.sources == 1))
            .await;
        let left = self.run(|lab| lab.manager.sources()[0].address.clone()).await;
        assert_eq!(left, format!("127.0.0.1:{}", self.others[2].port()));
    }

    async fn then_no_more_callbacks_are_asked_for(&self) {
        tokio::time::sleep(Duration::from_millis(1500)).await;
        assert_eq!(self.callbacks().len(), self.callbacks_before, "not for a paused download");
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

    fn given_the_ip_filter_blocks_the_loopback(&self) {
        self.downloads().engine.lock().unwrap().blocked.push(u32::from(Ipv4Addr::LOCALHOST));
    }

    async fn when_the_blocked_sources_are_dropped(&self) {
        self.run(|lab| {
            let ours = lab.ours.clone();
            lab.manager.drop_blocked_sources(&ours)
        })
        .await;
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

    // A download of three blocks whose first source sends the last one corrupt and has no recovery data, while a second,
    // able to send some, is added by hand once the part waits for it.
    async fn recovery(&mut self, seed: u64) {
        let data = random(2 * EMBLOCKSIZE as usize + 30_000, seed);
        let length = data.len() as u64;
        let hashes = file_hasher::hash(&mut &data[..], length, |_| ControlFlow::Continue(())).unwrap();
        let tree = hashes.aich.unwrap();
        let first = ScriptedSource::new(PEER, 0, 0).await;
        let helper = ScriptedSource::new(PEER, 1 << hello::MISC1_AICH_SHIFT, 0).await;
        let hash = hashes.ed2k.file_hash();
        let source = Source { host: "127.0.0.1".to_owned(), port: first.port() };
        let link = FileLink::new(
            "recover.bin".to_owned(),
            length,
            hash,
            Some(tree.master_hash()),
            hashes.ed2k.part_hashes().to_vec(),
            vec![source],
            Vec::new(),
        )
        .unwrap();
        self.given_the_manager();
        self.downloads().add(link).await;
        let mut served = first.accept().await;
        served.until(protocol::EDONKEY, client::SETREQFILEID).await;
        served.send(&whole_file(hash)).await;
        served.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
        served.send(&Packet::empty(protocol::EDONKEY, client::ACCEPTUPLOADREQ)).await;
        assert_eq!(requested(&mut served).await.len(), 3);
        let mut corrupt = data.clone();
        corrupt[data.len() - 1] ^= 0x55;
        send_blocks(&mut served, hash, &corrupt, 0, length).await;
        self.downloads()
            .wait_for("the corrupt part held for its recovery data", move |lab| {
                lab.manager.find(&hash).is_some_and(|d| d.file.is_held(0))
            })
            .await;
        let helper_address = helper.address();
        let added = self
            .run(move |lab| {
                let settings = lab.settings.clone();
                lab.manager.add_source_by_hand(&hash, helper_address, &settings)
            })
            .await;
        assert!(added);
        self.hash = hash;
        self.data = data;
        self.recovery = Some(Recovery {
            tree_master: tree.master_hash(),
            recovery_data: aich_recovery::create(&tree, 0).unwrap(),
            first: served,
            helper,
        });
    }

    fn whole_part(&self) -> Vec<Range> {
        let length = self.data.len() as u64;
        vec![
            Range { start: 0, end: EMBLOCKSIZE },
            Range { start: EMBLOCKSIZE, end: 2 * EMBLOCKSIZE },
            Range { start: 2 * EMBLOCKSIZE, end: length },
        ]
    }

    fn second(&mut self) -> &mut Link {
        self.second.as_mut().unwrap()
    }

    fn callbacks(&self) -> Vec<u32> {
        self.downloads().server.lock().unwrap().callbacks.clone()
    }

    async fn await_callbacks(&self, count: usize) {
        let deadline = std::time::Instant::now() + WAIT;
        while self.callbacks().len() < count {
            assert!(std::time::Instant::now() < deadline, "{count} callbacks");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    // What the server found for `file`: LowIDs or HighIDs, with their user hashes.
    async fn found(&self, file: [u8; 16], sources: Vec<(u32, Option<[u8; 16]>)>) {
        let sources = sources
            .into_iter()
            .map(|(client_id, user_hash)| Found {
                client_id,
                port: download_lab::PORT,
                crypt_options: if user_hash.is_some() { Found::HAS_USER_HASH } else { 0 },
                user_hash,
            })
            .collect();
        let found = FoundSources { file, sources };
        self.run(move |lab| {
            let (settings, ours) = (lab.settings.clone(), lab.ours.clone());
            lab.manager.found_sources(&found, &settings, OUR_ID, &ours);
        })
        .await;
    }

    async fn await_failures(&self, failures: u32) {
        let ports = [self.others[3].port(), self.closed_port];
        let deadline = std::time::Instant::now() + WAIT;
        loop {
            let now = self.run(|lab| lab.manager.describe()).await;
            if ports.iter().all(|port| now.contains(&format!("127.0.0.1:{port} (failed {failures})"))) {
                return;
            }
            assert!(std::time::Instant::now() < deadline, "{failures} failures: {now}");
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }

    // The first event `wanted` takes, of those the downloads told.
    async fn wait_for_event<T: Send + 'static>(
        &self,
        what: &str,
        wanted: impl Fn(&DownloadEvent) -> Option<T> + Send + Sync + Copy + 'static,
    ) -> T {
        let deadline = std::time::Instant::now() + WAIT;
        loop {
            if let Some(found) = self.run(move |lab| lab.events.iter().find_map(wanted)).await {
                return found;
            }
            assert!(std::time::Instant::now() < deadline, "no event {what}");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
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

// A HighID source at `ip`, as another client tells of it.
fn exchanged(ip: Ipv4Addr) -> exchange::Source {
    exchange::Source {
        ip: u32::from(ip),
        low_id: 0,
        port: download_lab::PORT,
        server_ip: 0,
        server_port: 0,
        user_hash: None,
        crypt_options: 0,
        ipv6: None,
    }
}

fn aich_hash(file: [u8; 16], master: [u8; 20]) -> Packet {
    let mut out = PacketWriter::new();
    out.hash16(&file).hash20(&master);
    Packet::written(protocol::EMULE, extended::AICHFILEHASHANS, out)
}

// The names of the packets a downloader sends a source, as the Java tests read them.
fn names(packets: &[Packet]) -> Vec<String> {
    packets
        .iter()
        .map(|packet| {
            match (packet.protocol(), packet.opcode()) {
                (protocol::EDONKEY, client::REQUESTFILENAME) => "REQUESTFILENAME",
                (protocol::EDONKEY, client::SETREQFILEID) => "SETREQFILEID",
                (protocol::EDONKEY, client::STARTUPLOADREQ) => "STARTUPLOADREQ",
                (protocol::EDONKEY, client::HASHSETREQUEST) => "HASHSETREQUEST",
                (protocol::EMULE, extended::AICHFILEHASHREQ) => "AICHFILEHASHREQ",
                (protocol::EMULE, extended::REQUESTSOURCES2) => "REQUESTSOURCES2",
                _ => "?",
            }
            .to_owned()
        })
        .collect()
}

fn random(size: usize, seed: u64) -> Vec<u8> {
    let mut data = vec![0u8; size];
    StdRng::seed_from_u64(seed).fill(&mut data[..]);
    data
}

fn aich_answer(file: [u8; 16], master: [u8; 20], data: &[u8]) -> Packet {
    let mut answer = PacketWriter::new();
    answer.hash16(&file).u16(0).hash20(&master).bytes(data);
    Packet::written(protocol::EMULE, extended::AICHANSWER, answer)
}

// The blocks the downloader asks for next.
async fn requested(link: &mut Link) -> Vec<Range> {
    let asked = link.until(protocol::EDONKEY, client::REQUESTPARTS).await.pop().unwrap();
    RequestParts::decode(&asked).unwrap().ranges.into_iter().filter(|range| range.end > range.start).collect()
}

// In packets of 10 KB, as clients send them.
async fn send_blocks(link: &mut Link, file: [u8; 16], data: &[u8], start: u64, end: u64) {
    let mut at = start;
    while at < end {
        let to = end.min(at + 10_240);
        let part = SendingPart { file, start: at, end: to, data: data[at as usize..to as usize].to_vec() };
        link.send(&part.encode(false).unwrap()).await;
        at = to;
    }
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

#[tokio::test]
async fn should_ask_a_source_that_ends_our_slot_for_the_same_blocks_and_fail_a_file_that_cannot_be_moved_until_resumed()
{
    let mut sources = Sources::new();
    sources.given_the_data(random(5000, 3));
    sources.given_a_file_where_the_incoming_directory_should_be();
    sources.given_a_source(0, 0).await;
    sources.given_the_manager();
    sources.given_a_download_of_the_data_from_the_source("data.bin").await;
    sources.given_the_source_gave_us_a_slot().await;
    sources.given_the_whole_file_asked_for().await;
    sources.given_the_source_shown_as_downloading().await;
    sources.given_the_source_ended_our_slot().await;
    sources.given_the_source_gave_us_the_slot_again().await;
    sources.given_the_blocks_let_go_asked_for_again().await;
    sources.given_the_source_sent_the_file_among_data_that_does_not_fit().await;
    sources.given_the_download_failed_with("cannot move").await;
    sources.given_the_download_in_error().await;
    sources.given_its_sessions_ended().await;
    sources.given_the_incoming_directory_freed();

    sources.when_the_download_is_resumed().await;

    sources.then_it_is_accepted_to_resume();
    sources.then_the_download_completes_with_the_data().await;
    sources.then_the_download_is_gone().await;
}

#[tokio::test]
async fn should_ignore_what_does_not_fit_from_a_source_until_it_breaks_the_protocol() {
    let mut sources = Sources::new();
    sources.given_the_data(random(5000, 4));
    sources.given_a_source(0, 0).await;
    sources.given_the_manager();
    sources.given_a_download_of_the_data_from_the_source("odd.bin").await;
    sources.given_we_asked_the_source_for_the_file().await;
    sources.given_the_source_sent_what_does_not_fit_before_queuing_us().await;
    sources.given_we_were_queued_with_nothing_of_it_taken().await;
    sources.given_the_source_ended_a_slot_not_given_yet_then_gave_us_one().await;
    sources.given_the_source_sent_data_of_no_block_asked_for_then_the_first_bytes().await;

    sources.when_the_source_sends_a_status_that_does_not_decode().await;

    sources.then_the_connection_closes().await;
    sources.then_the_download_has_sources(0).await;
    sources.then_the_download_has_completed(1000).await;
}

#[tokio::test]
async fn should_recover_a_corrupt_block_with_the_aich_data_of_a_source_that_comes_later() {
    let mut sources = Sources::new();
    sources.given_a_download_with_a_corrupt_part_held_for_its_recovery_data(5).await;
    sources.given_the_second_source_asked_for_recovery_data().await;
    sources.given_the_recovery_data_asked_for_the_file();
    sources.given_the_second_source_told_another_aich_hash().await;
    sources.given_the_second_source_sent_the_recovery_data_of_the_part().await;
    sources.given_only_the_corrupt_block_asked_for_again().await;

    sources.when_the_first_source_sends_the_block_again().await;

    sources.then_the_download_completes_with_the_data().await;
    sources.then_the_file_is_no_longer_found().await;
}

#[tokio::test]
async fn should_tell_of_a_part_that_arrived_corrupt() {
    let mut sources = Sources::new();

    sources.when_a_part_arrives_with_a_bad_byte().await;

    sources.then_it_was_told_that_the_part_is_corrupt(0).await;
}

#[tokio::test]
async fn should_download_the_part_again_whole_with_recovery_data_that_proves_nothing() {
    let mut sources = Sources::new();
    sources.given_a_download_with_a_corrupt_part_held_for_its_recovery_data(6).await;
    sources.given_the_second_source_asked_for_recovery_data().await;
    sources.given_the_second_source_sent_recovery_data_that_proves_nothing().await;
    sources.given_the_part_asked_for_again_whole().await;

    sources.when_the_first_source_sends_the_part_corrupt_again().await;

    sources.then_the_part_is_asked_for_again_whole().await;
    sources.then_the_download_can_be_paused().await;
}

#[tokio::test]
async fn should_drop_sources_without_the_file_or_out_of_reach() {
    let mut sources = Sources::new();
    sources.given_sources_without_the_file_and_out_of_reach().await;
    sources.given_the_manager();
    sources.given_a_download_from_all_of_them([0x34; 16]).await;
    let answered = sources.given_the_sources_answered_without_a_file_we_can_use().await;
    sources.given_the_silent_source_refused_us().await;
    sources.given_the_sources_without_a_file_we_can_use_hung_up_on(answered).await;
    sources.given_the_useless_and_the_unreachable_sources_kept().await;
    sources.given_the_unreachable_sources_failed_twice().await;

    sources.when_the_unreachable_sources_are_asked_again().await;

    sources.then_only_the_useless_source_is_left().await;
    sources.then_the_download_can_be_paused().await;
}

#[tokio::test]
async fn should_ask_firewalled_sources_through_the_server_one_at_a_time_and_not_for_a_paused_download() {
    let mut sources = Sources::new();
    sources.given_the_manager();
    sources.given_a_download("lowid.bin", [0x36; 16]).await;
    sources.given_a_firewalled_source_the_server_cannot_ask(6000);
    sources.given_the_server_found_firewalled_sources().await;
    sources.given_each_asked_for_once_one_a_tick().await;
    sources.given_one_the_server_could_not_ask_asked_again_when_named_again().await;
    sources.given_one_that_did_not_call_back_in_time_asked_again_when_named_again().await;
    sources.given_the_download_paused().await;

    sources.when_the_server_finds(8000).await;

    sources.then_no_more_callbacks_are_asked_for().await;
}

#[tokio::test]
async fn should_change_nothing_for_an_unknown_download() {
    let mut sources = Sources::new();
    sources.given_the_manager();
    sources.given_a_paused_download("known.bin", [0x3b; 16]).await;

    sources.when_every_change_is_asked_for_the_unknown_download().await;

    sources.then_every_one_is_refused();
    sources.then_the_unknown_download_has_no_comments().await;
    sources.then_the_unknown_download_has_no_source_to_pass_on().await;
}

#[tokio::test]
async fn should_take_the_ipv6_sources_of_an_extended_answer_when_we_have_ipv6() {
    let mut sources = Sources::new();
    sources.given_we_have_the_ipv6_address("2001:db8::99");
    sources.given_a_source_announcing_the_ipv6_address(hello::MISC2_SOURCE_EXCHANGE2, "2001:db8::50").await;
    sources.given_the_manager();
    sources.given_a_download_from_the_source("six.bin", [0x4e; 16]).await;
    sources.given_we_asked_the_source_for_other_sources_the_extended_way().await;

    sources
        .when_the_source_answers_the_extended_way_with_a_high_id_a_low_id_and_a_source_at_an_ipv6_address_alone()
        .await;

    sources.then_the_download_has_sources(4).await;
    sources.then_a_source_is_shown_at("[2001:db8::61]:4662").await;
}

#[tokio::test]
async fn should_hear_the_comment_and_other_sources_of_a_source_that_keeps_us_in_its_queue() {
    let mut sources = Sources::new();
    sources.given_a_source(1 << hello::MISC1_AICH_SHIFT, hello::MISC2_SOURCE_EXCHANGE2).await;
    sources.given_the_manager();
    sources.given_a_download_from_the_source("talk.bin", [0x31; 16]).await;
    sources.given_we_asked_the_source_for_the_aich_hash_and_other_sources().await;
    sources.given_the_source_shown_as_asked().await;
    sources.given_only_sources_known_to_take_obfuscation_asked().await;
    sources.given_the_source_told_its_comments_and_other_sources_and_queued_us_at(3).await;
    sources.given_we_were_queued_at(3).await;
    sources.given_the_good_comment_and_the_public_sources_it_told_of_taken().await;
    sources.given_the_aich_hash_of_one_source_not_trusted().await;
    sources.given_the_source_not_passed_on_yet().await;
    sources.given_the_connection_closed_while_queued().await;
    sources.given_the_source_can_be_passed_on().await;
    sources.given_plain_connections_allowed_again().await;
    sources.given_time_passed(REASK_INTERVAL);
    sources.given_the_source_asked_again_but_not_for_more_sources().await;

    sources.when_the_download_is_paused().await;

    sources.then_the_connection_closes().await;
}

#[tokio::test]
async fn should_take_no_more_firewalled_sources_into_a_full_source_list() {
    let mut sources = Sources::new();
    sources.given_a_maximum_of_sources(1);
    sources.given_a_source(0, hello::MISC2_SOURCE_EXCHANGE2).await;
    sources.given_the_manager();
    sources.given_a_download_from_the_source("full.bin", [0x38; 16]).await;
    sources.given_the_source_asked_for_no_more_sources().await;

    sources.when_the_server_finds_a_low_id(5000).await;

    sources.then_no_callback_is_asked_for().await;
}

#[tokio::test]
async fn should_take_no_source_by_hand_into_a_full_source_list() {
    let mut sources = Sources::new();
    sources.given_a_maximum_of_sources(1);
    sources.given_a_source(0, hello::MISC2_SOURCE_EXCHANGE2).await;
    sources.given_the_manager();
    sources.given_a_download_from_the_source("full.bin", [0x38; 16]).await;
    sources.given_the_source_asked_for_no_more_sources().await;

    sources
        .when_sources_are_added_by_hand(vec![SocketAddr::new("127.0.0.2".parse().unwrap(), download_lab::PORT)])
        .await;

    sources.then_every_one_is_refused();
}

// Beyond the Java engine's twins: a source connected when the IP filter comes to block it is hung up on.
#[tokio::test]
async fn should_hang_up_on_a_connected_source_the_ip_filter_comes_to_block() {
    let mut sources = Sources::new();
    sources.given_the_data(random(5000, 31));
    sources.given_a_source(0, 0).await;
    sources.given_the_manager();
    sources.given_a_download_of_the_data_from_the_source("blocked.bin").await;
    sources.given_the_source_gave_us_a_slot().await;
    sources.given_the_ip_filter_blocks_the_loopback();

    sources.when_the_blocked_sources_are_dropped().await;

    sources.then_the_connection_closes().await;
    sources.then_the_download_has_sources(0).await;
}
