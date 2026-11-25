// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Black-box download flow tests against the compiled `njmule` executable, checking every wire packet against the
//! recorded transcripts in `tests/transcripts/DownloadFlowTest/`.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::HashSet;
use std::fs;
use std::net::{Ipv4Addr, SocketAddrV4};
use std::path::PathBuf;
use std::time::Duration;

use njmule_engine::api::ed2k_link::{Ed2kLink, FileLink};
use njmule_engine::persist::format::server_met::{self, Server as MetServer};
use njmule_engine::protocol::ed2k::server_datagrams;
use njmule_engine::protocol::tag::{Tag, TagName, server_tag};
use serde_json::{Value, json};

use crate::flow_lab::{
    self, BlackBoxEngine, DOWNLOAD_WAIT, FakePeer, FakeServer, PART, PeerConfig, Recorder, ServerConfig, SlotMode, WAIT,
};
use crate::free_port;

fn contains_subsequence(haystack: &[String], needle: &[&str]) -> bool {
    let mut it = needle.iter();
    let Some(mut wanted) = it.next() else {
        return true;
    };
    for item in haystack {
        if item == wanted {
            match it.next() {
                Some(next) => wanted = next,
                None => return true,
            }
        }
    }
    false
}

async fn download_and_wait(engine: &mut BlackBoxEngine, link: &FileLink) -> (Value, PathBuf, Value) {
    let added = engine.api().post("/downloads", json!({"links": [link.to_uri()]})).await.unwrap();
    let first = added.as_array().unwrap()[0].clone();
    let hash = first["hash"].as_str().unwrap().to_owned();
    let (name, ev) = engine
        .next_event(DOWNLOAD_WAIT, |name, ev| {
            (name == "download.completed" && ev["download"]["hash"].as_str() == Some(hash.as_str()))
                || (name == "download.failed" && ev["download"]["hash"].as_str() == Some(hash.as_str()))
        })
        .await;
    assert_eq!(name, "download.completed", "download failed: {ev}");
    let file = PathBuf::from(ev["file"].as_str().unwrap());
    (first, file, ev)
}

#[allow(clippy::too_many_arguments)]
async fn start_peer_with(
    dir: &std::path::Path,
    address: Ipv4Addr,
    folder: &str,
    name: &str,
    size: usize,
    seed: i64,
    config: PeerConfig,
    recorder: &mut Recorder,
) -> (Vec<u8>, FakePeer) {
    let data = flow_lab::demo_content(size, seed);
    let shared_dir = dir.join(folder);
    fs::create_dir_all(&shared_dir).unwrap();
    let file_path = shared_dir.join(name);
    fs::write(&file_path, &data).unwrap();
    let peer = FakePeer::start(address, &file_path, config, recorder).await;
    (data, peer)
}

#[tokio::test]
async fn should_download_a_file_sent_in_compressed_blocks() {
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
    let (data, peer) = start_peer_with(
        dir.path(),
        Ipv4Addr::LOCALHOST,
        "shared",
        "shared.bin",
        2 * PART + 12_345,
        1,
        PeerConfig::simple(flow_lab::java_hash(1)),
        &mut recorder,
    )
    .await;
    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;

    let (added, file, completed) = download_and_wait(&mut engine, &peer.link()).await;
    assert_eq!(added["status"].as_str(), Some("downloading"));
    assert_eq!(added["sources"].as_u64(), Some(1));
    assert_eq!(completed["download"]["completed"], completed["download"]["size"]);
    let downloads = engine.api().get("/downloads").await.unwrap();
    assert_eq!(downloads.as_array().unwrap().len(), 1);
    assert_eq!(downloads[0]["status"].as_str(), Some("complete"));
    assert_eq!(fs::read(&file).unwrap(), data);
    assert_eq!(file.file_name().unwrap().to_string_lossy(), "shared.bin");
    let received = peer.received();
    assert!(contains_subsequence(&received, &["HELLO", "SETREQFILEID", "HASHSETREQUEST", "STARTUPLOADREQ"]));
    assert!(received.iter().any(|m| m == "REQUESTPARTS"));
    assert_eq!(received.iter().filter(|m| *m == "STARTUPLOADREQ").count(), 1);

    engine.stop().await;
    peer.close();
    recorder.assert_matches("DownloadFlowTest/shouldDownloadAFileSentInCompressedBlocks.txt");
}

#[tokio::test]
async fn should_keep_a_completed_download_listed_until_the_completed_are_cleared() {
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
    let (data, peer) = start_peer_with(
        dir.path(),
        Ipv4Addr::LOCALHOST,
        "shared",
        "shared.bin",
        12_345,
        2,
        PeerConfig::simple(flow_lab::java_hash(2)),
        &mut recorder,
    )
    .await;
    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;

    let (added, file, _) = download_and_wait(&mut engine, &peer.link()).await;
    let hash = added["hash"].as_str().unwrap().to_owned();
    let cleared = engine.api().delete("/downloads/completed").await.unwrap();
    assert_eq!(cleared["removed"].as_u64(), Some(1));
    let (_, removed) = engine.next_event(WAIT, |name, _| name == "download.removed").await;
    assert_eq!(removed["hash"].as_str(), Some(hash.as_str()));
    assert!(engine.api().get("/downloads").await.unwrap().as_array().unwrap().is_empty());
    assert_eq!(fs::read(&file).unwrap(), data);

    engine.stop().await;
    peer.close();
    recorder.assert_matches("DownloadFlowTest/shouldKeepACompletedDownloadListedUntilTheCompletedAreCleared.txt");
}

#[tokio::test]
async fn should_take_one_completed_download_off_the_list_and_keep_its_file() {
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
    let (data, peer) = start_peer_with(
        dir.path(),
        Ipv4Addr::LOCALHOST,
        "shared",
        "shared.bin",
        12_345,
        2,
        PeerConfig::simple(flow_lab::java_hash(2)),
        &mut recorder,
    )
    .await;
    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;

    let (added, file, _) = download_and_wait(&mut engine, &peer.link()).await;
    let hash = added["hash"].as_str().unwrap().to_owned();
    engine.api().delete(&format!("/downloads/completed/{hash}")).await.unwrap();
    let (_, removed) = engine.next_event(WAIT, |name, _| name == "download.removed").await;
    assert_eq!(removed["hash"].as_str(), Some(hash.as_str()));
    assert!(engine.api().get("/downloads").await.unwrap().as_array().unwrap().is_empty());
    assert_eq!(fs::read(&file).unwrap(), data);

    engine.stop().await;
    peer.close();
    recorder.assert_matches("DownloadFlowTest/shouldTakeOneCompletedDownloadOffTheListAndKeepItsFile.txt");
}

#[tokio::test]
async fn should_let_a_source_download_from_us_over_the_connection_we_download_on() {
    let dir = tempfile::tempdir().unwrap();
    let incoming = dir.path().join("njmule").join("incoming");
    fs::create_dir_all(&incoming).unwrap();
    fs::write(incoming.join("ours.bin"), flow_lab::demo_content(300_000, 9)).unwrap();

    let mut recorder = Recorder::default();
    let (_, peer) = start_peer_with(
        dir.path(),
        Ipv4Addr::LOCALHOST,
        "shared",
        "shared.bin",
        PART / 2,
        10,
        PeerConfig::simple(flow_lab::java_hash(10)),
        &mut recorder,
    )
    .await;
    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;

    let deadline = tokio::time::Instant::now() + WAIT;
    let our_hash = loop {
        let shared = engine.api().get("/shared").await.unwrap();
        if let Some(first) = shared.as_array().unwrap().first() {
            let hex = first["hash"].as_str().unwrap();
            let bytes = data_encoding::HEXUPPER_PERMISSIVE.decode(hex.as_bytes()).unwrap();
            break <[u8; 16]>::try_from(bytes).unwrap();
        }
        assert!(tokio::time::Instant::now() < deadline, "ours.bin not shared");
        tokio::time::sleep(Duration::from_millis(50)).await;
    };
    peer.also_ask_for(our_hash);

    let _ = download_and_wait(&mut engine, &peer.link()).await;
    flow_lab::wait_until(DOWNLOAD_WAIT, || peer.received().iter().any(|m| m == "ACCEPTUPLOADREQ"), "slot for peer")
        .await;
    assert!(peer.received().iter().any(|m| m == "FILESTATUS"));

    engine.stop().await;
    peer.close();
    recorder.assert_matches("DownloadFlowTest/shouldLetASourceDownloadFromUsOverTheConnectionWeDownloadOn.txt");
}

#[tokio::test]
async fn should_wait_in_the_queue_and_get_a_slot_again_after_the_session_ends() {
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
    let (data, peer) = start_peer_with(
        dir.path(),
        Ipv4Addr::LOCALHOST,
        "shared",
        "shared.bin",
        PART + 500_000,
        2,
        PeerConfig {
            user_hash: flow_lab::java_hash(2),
            compress: false,
            slot: SlotMode::AfterQueue,
            session_limit: 1_000_000,
            corrupt_parts: HashSet::new(),
        },
        &mut recorder,
    )
    .await;
    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;

    let (added, file, completed) = download_and_wait(&mut engine, &peer.link()).await;
    assert_eq!(added["status"].as_str(), Some("downloading"));
    assert_eq!(added["sources"].as_u64(), Some(1));
    assert_eq!(completed["download"]["completed"], completed["download"]["size"]);
    assert_eq!(fs::read(&file).unwrap(), data);
    assert_eq!(peer.received().iter().filter(|m| *m == "STARTUPLOADREQ").count(), 1);

    engine.stop().await;
    peer.close();
    recorder.assert_matches("DownloadFlowTest/shouldWaitInTheQueueAndGetASlotAgainAfterTheSessionEnds.txt");
}

#[tokio::test]
async fn should_download_a_corrupt_part_again() {
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
    let (data, peer) = start_peer_with(
        dir.path(),
        Ipv4Addr::LOCALHOST,
        "shared",
        "shared.bin",
        2 * PART + 1000,
        3,
        PeerConfig {
            user_hash: flow_lab::java_hash(3),
            compress: true,
            slot: SlotMode::Now,
            session_limit: 0,
            corrupt_parts: HashSet::from([1]),
        },
        &mut recorder,
    )
    .await;
    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;

    let (added, file, _) = download_and_wait(&mut engine, &peer.link()).await;
    assert_eq!(added["status"].as_str(), Some("downloading"));
    assert_eq!(fs::read(&file).unwrap(), data);
    assert!(peer.bytes_sent() > data.len() as u64);

    engine.stop().await;
    peer.close();
    recorder.assert_matches("DownloadFlowTest/shouldDownloadACorruptPartAgain.txt");
}

#[tokio::test]
async fn should_save_the_good_blocks_of_a_corrupt_part_with_aich_recovery_data() {
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
    let (data, peer) = start_peer_with(
        dir.path(),
        Ipv4Addr::LOCALHOST,
        "shared",
        "shared.bin",
        3 * PART + 1000,
        6,
        PeerConfig {
            user_hash: flow_lab::java_hash(6),
            compress: false,
            slot: SlotMode::Now,
            session_limit: 0,
            corrupt_parts: HashSet::from([1]),
        },
        &mut recorder,
    )
    .await;
    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;

    let (added, file, _) = download_and_wait(&mut engine, &peer.link()).await;
    assert_eq!(added["status"].as_str(), Some("downloading"));
    assert_eq!(fs::read(&file).unwrap(), data);
    assert!(peer.received().iter().any(|m| m == "AICHREQUEST"));
    assert!(peer.bytes_sent() < (data.len() + PART / 10) as u64, "one bad block costs one block");

    engine.stop().await;
    peer.close();
    recorder.assert_matches("DownloadFlowTest/shouldSaveTheGoodBlocksOfACorruptPartWithAichRecoveryData.txt");
}

#[tokio::test]
async fn should_get_the_slot_when_the_source_calls_back() {
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
    let (data, peer) = start_peer_with(
        dir.path(),
        Ipv4Addr::LOCALHOST,
        "shared",
        "shared.bin",
        PART / 2,
        4,
        PeerConfig {
            user_hash: flow_lab::java_hash(4),
            compress: true,
            slot: SlotMode::CallBack,
            session_limit: 0,
            corrupt_parts: HashSet::new(),
        },
        &mut recorder,
    )
    .await;
    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;

    let (added, file, _) = download_and_wait(&mut engine, &peer.link()).await;
    assert_eq!(added["status"].as_str(), Some("downloading"));
    assert_eq!(fs::read(&file).unwrap(), data);
    assert!(contains_subsequence(&peer.received(), &["STARTUPLOADREQ", "HELLOANSWER"]));

    engine.stop().await;
    peer.close();
    recorder.assert_matches("DownloadFlowTest/shouldGetTheSlotWhenTheSourceCallsBack.txt");
}

#[tokio::test]
async fn should_download_from_the_sources_the_server_knows() {
    let Some(lan) = flow_lab::lan_address() else {
        return;
    };
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
    let (data, peer) = start_peer_with(
        dir.path(),
        lan,
        "shared",
        "served.bin",
        1_000_000,
        5,
        PeerConfig::simple(flow_lab::java_hash(5)),
        &mut recorder,
    )
    .await;
    let server = FakeServer::start(
        lan,
        ServerConfig { client_id: flow_lab::high_id(lan), welcome: "hi".to_owned(), results: Vec::new() },
        &mut recorder,
    )
    .await;
    server.add_source(peer.hash(), SocketAddrV4::new(lan, peer.port()));

    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;
    engine.api().post("/servers/connect", json!({"address": format!("{lan}:{}", server.port())})).await.unwrap();
    let link = FileLink {
        name: "served.bin".to_owned(),
        size: data.len() as u64,
        hash: peer.hash(),
        aich_hash: None,
        part_hashes: Vec::new(),
        sources: Vec::new(),
        http_sources: Vec::new(),
    };
    let (added, file, _) = download_and_wait(&mut engine, &link).await;
    assert_eq!(added["sources"].as_u64(), Some(0));
    assert_eq!(fs::read(&file).unwrap(), data);

    engine.stop().await;
    server.close();
    peer.close();
    recorder.assert_matches("DownloadFlowTest/shouldDownloadFromTheSourcesTheServerKnows.txt");
}

#[tokio::test]
async fn should_download_from_the_sources_another_server_knows() {
    let Some(lan) = flow_lab::lan_address() else {
        return;
    };
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
    let (data, peer) = start_peer_with(
        dir.path(),
        lan,
        "elsewhere",
        "elsewhere.bin",
        700_000,
        7,
        PeerConfig::simple(flow_lab::java_hash(7)),
        &mut recorder,
    )
    .await;
    let server = FakeServer::start(
        lan,
        ServerConfig { client_id: flow_lab::high_id(lan), welcome: "hi".to_owned(), results: Vec::new() },
        &mut recorder,
    )
    .await;
    let other = FakeServer::start(
        lan,
        ServerConfig { client_id: 1, welcome: String::new(), results: Vec::new() },
        &mut recorder,
    )
    .await;
    other.add_source(peer.hash(), SocketAddrV4::new(lan, peer.port()));

    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;
    let met = dir.path().join("other.met");
    let flags = Tag::Int {
        name: TagName::id(server_tag::UDPFLAGS),
        value: u64::from(server_datagrams::UDP_EXT_GETSOURCES | server_datagrams::UDP_EXT_GETSOURCES2),
    };
    fs::write(
        &met,
        server_met::write(&[MetServer { ip: u32::from(lan), port: other.port(), tags: vec![flags] }]).unwrap(),
    )
    .unwrap();
    let imported = engine.api().post("/servers/import", json!({"path": met.to_string_lossy()})).await.unwrap();
    assert_eq!(imported["added"].as_u64(), Some(1));
    engine.api().post("/servers/connect", json!({"address": format!("{lan}:{}", server.port())})).await.unwrap();

    let link = FileLink {
        name: "elsewhere.bin".to_owned(),
        size: data.len() as u64,
        hash: peer.hash(),
        aich_hash: None,
        part_hashes: Vec::new(),
        sources: Vec::new(),
        http_sources: Vec::new(),
    };
    let (_, file, _) = download_and_wait(&mut engine, &link).await;
    assert_eq!(fs::read(&file).unwrap(), data);
    assert!(other.udp_source_requests().contains(&peer.hash()));

    engine.stop().await;
    other.close();
    server.close();
    peer.close();
    recorder.assert_matches("DownloadFlowTest/shouldDownloadFromTheSourcesAnotherServerKnows.txt");
}

#[tokio::test]
async fn should_ask_a_firewalled_source_the_server_knows_to_call_us() {
    let Some(lan) = flow_lab::lan_address() else {
        return;
    };
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
    let (data, peer) = start_peer_with(
        dir.path(),
        lan,
        "lowid",
        "firewalled.bin",
        800_000,
        6,
        PeerConfig::simple(flow_lab::java_hash(6)),
        &mut recorder,
    )
    .await;
    let server = FakeServer::start(
        lan,
        ServerConfig { client_id: flow_lab::high_id(lan), welcome: "hi".to_owned(), results: Vec::new() },
        &mut recorder,
    )
    .await;
    server.add_low_id_source(peer.hash(), 4242, peer.clone());

    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;
    engine.api().post("/servers/connect", json!({"address": format!("{lan}:{}", server.port())})).await.unwrap();
    let link = FileLink {
        name: "firewalled.bin".to_owned(),
        size: data.len() as u64,
        hash: peer.hash(),
        aich_hash: None,
        part_hashes: Vec::new(),
        sources: Vec::new(),
        http_sources: Vec::new(),
    };
    let (_, file, _) = download_and_wait(&mut engine, &link).await;
    assert_eq!(fs::read(&file).unwrap(), data);
    assert_eq!(server.callback_requests(), vec![4242]);
    assert!(contains_subsequence(&peer.received(), &["SETREQFILEID", "STARTUPLOADREQ"]));

    engine.stop().await;
    server.close();
    peer.close();
    recorder.assert_matches("DownloadFlowTest/shouldAskAFirewalledSourceTheServerKnowsToCallUs.txt");
}

#[tokio::test]
async fn should_pause_resume_and_cancel_a_download() {
    let dir = tempfile::tempdir().unwrap();
    let recorder = Recorder::default();
    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;

    let closed_port = free_port::free_port();
    let link_text = format!("ed2k://|file|nowhere.bin|5000|{}|/|sources,127.0.0.1:{closed_port}|/", "12".repeat(16));
    let Some(Ed2kLink::File(_)) = Ed2kLink::try_parse(&link_text) else {
        panic!("invalid link");
    };
    let added = engine.api().post("/downloads", json!({"links": [link_text]})).await.unwrap()[0].clone();
    let hash = added["hash"].as_str().unwrap().to_owned();
    let (_, ev) = engine.next_event(WAIT, |name, _| name == "download.added").await;
    assert_eq!(ev["download"]["name"].as_str(), Some("nowhere.bin"));

    let sources = engine.api().get("/sources").await.unwrap();
    let list = sources.as_array().unwrap();
    assert_eq!(list.len(), 1);
    assert_eq!(list[0]["file"].as_str(), Some(hash.as_str()));
    assert_eq!(list[0]["fileName"].as_str(), Some("nowhere.bin"));
    assert_eq!(list[0]["address"].as_str(), Some(format!("127.0.0.1:{closed_port}").as_str()));
    assert_eq!(list[0]["received"].as_u64(), Some(0));
    assert_eq!(list[0]["partCount"].as_u64(), Some(1));

    engine.api().post(&format!("/downloads/{hash}/pause"), json!({})).await.unwrap();
    assert_eq!(engine.api().get("/downloads").await.unwrap()[0]["status"].as_str(), Some("paused"));

    engine.api().post(&format!("/downloads/{hash}/resume"), json!({})).await.unwrap();
    assert_eq!(engine.api().get("/downloads").await.unwrap()[0]["status"].as_str(), Some("downloading"));

    engine.api().delete(&format!("/downloads/{hash}")).await.unwrap();
    let (_, removed) = engine.next_event(WAIT, |name, _| name == "download.removed").await;
    assert_eq!(removed["hash"].as_str(), Some(hash.as_str()));
    assert!(engine.api().get("/downloads").await.unwrap().as_array().unwrap().is_empty());
    assert!(engine.api().post(&format!("/downloads/{hash}/pause"), json!({})).await.is_err());

    let temp_dir = engine.dirs.temp.clone();
    flow_lab::wait_until(
        Duration::from_secs(30),
        || fs::read_dir(&temp_dir).map(|entries| entries.count() == 0).unwrap_or(false),
        "temp files to be deleted after cancel undo window",
    )
    .await;

    engine.stop().await;
    recorder.assert_matches("DownloadFlowTest/shouldPauseResumeAndCancelADownload.txt");
}
