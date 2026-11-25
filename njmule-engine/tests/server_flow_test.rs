// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Black-box server flow tests against the compiled `njmule` executable, checking every wire packet against the
//! recorded transcripts in `tests/transcripts/ServerFlowTest/`.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::HashMap;
use std::fs;
use std::net::{Ipv4Addr, SocketAddrV4, TcpListener};
use std::time::Duration;

use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::nj_mule_info;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::engine::switches::Switches;
use njmule_engine::persist::format::server_met::{self, Server as MetServer};
use njmule_engine::protocol::ed2k::server_messages::Endpoint;
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::{Tag, TagName, server_tag};
use njmule_engine::search::search_manager::Scope;
use njmule_engine::server::server_list::Address;
use njmule_engine::util::ipv4;
use serde_json::{Value, json};

use crate::flow_lab::{self, BlackBoxEngine, FakeServer, Recorder, ServerConfig, WAIT};
use crate::free_port;

#[tokio::test]
async fn should_log_in_and_search_the_server_then_the_others_over_udp() {
    let Some(lan) = flow_lab::lan_address() else {
        return;
    };
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
    let server = FakeServer::start(
        lan,
        ServerConfig {
            client_id: flow_lab::high_id(lan),
            welcome: "server version 17.15\nWelcome to the fake server".to_owned(),
            results: vec![
                FakeServer::file("ubuntu-24.04.iso", 6_000_000_000, 50),
                FakeServer::file("empty.bin", 0, 3),
                FakeServer::file(" ", 100, 1),
            ],
        },
        &mut recorder,
    )
    .await;
    let other = FakeServer::start(
        lan,
        ServerConfig {
            client_id: 1,
            welcome: String::new(),
            results: vec![
                FakeServer::file("ubuntu-24.04.iso", 6_000_000_000, 7),
                FakeServer::file("ubuntu notes.txt", 10, 1),
            ],
        },
        &mut recorder,
    )
    .await;

    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;
    let met = dir.path().join("other-server.met");
    fs::write(
        &met,
        server_met::write(&[MetServer { ip: u32::from(lan), port: other.port(), tags: Vec::new() }]).unwrap(),
    )
    .unwrap();
    let added = engine.api().post("/servers/import", json!({"path": met.to_string_lossy()})).await.unwrap();
    assert_eq!(added["added"].as_u64(), Some(1));

    engine.api().post("/servers/connect", json!({"address": format!("{lan}:{}", server.port())})).await.unwrap();
    let (_, connected) = engine.next_event(WAIT, |name, _| name == "server.connected").await;
    assert_eq!(connected["lowId"].as_bool(), Some(false));
    let _ = engine
        .next_event(WAIT, |name, ev| {
            name == "server.message" && ev["text"].as_str().is_some_and(|t| t.starts_with("Welcome"))
        })
        .await;
    assert_eq!(server.logins().len(), 1);

    let search = engine.api().post("/searches", json!({"query": "ubuntu iso", "scope": "global"})).await.unwrap();
    let search_id = search["id"].as_u64().unwrap();
    let mut results: HashMap<String, Value> = HashMap::new();
    let ended_count;
    loop {
        let (name, ev) = engine
            .next_event(WAIT, |name, ev| {
                (name == "search.results" || name == "search.ended") && ev["search"].as_u64() == Some(search_id)
            })
            .await;
        if name == "search.results" {
            for item in ev["results"].as_array().unwrap() {
                results.insert(item["name"].as_str().unwrap().to_owned(), item.clone());
            }
        } else {
            ended_count = ev["results"].as_u64().unwrap();
            break;
        }
    }

    assert_eq!(ended_count, 2);
    assert_eq!(results["ubuntu-24.04.iso"]["sources"].as_u64(), Some(57));
    assert_eq!(results["ubuntu-24.04.iso"]["size"].as_u64(), Some(6_000_000_000));
    assert!(results.contains_key("ubuntu notes.txt"));
    assert_eq!(server.searches().len(), 1);
    assert_eq!(other.udp_searches().len(), 1);

    engine.stop().await;
    other.close();
    server.close();
    recorder.assert_matches("ServerFlowTest/shouldLogInAndSearchTheServerThenTheOthersOverUdp.txt");
}

#[tokio::test]
async fn should_keep_an_answer_of_the_server_with_the_search_it_answers() {
    let Some(lan) = flow_lab::lan_address() else {
        return;
    };
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
    let server = FakeServer::start(
        lan,
        ServerConfig { client_id: flow_lab::high_id(lan), welcome: String::new(), results: Vec::new() },
        &mut recorder,
    )
    .await;
    server.answer_searches(|expr| {
        let name = if format!("{expr:?}").to_lowercase().contains("first") { "first.bin" } else { "second.bin" };
        vec![FakeServer::file(name, 1000, 1)]
    });

    let root = dir.path().join("njmule");
    let dirs = AppDirectories::under_root(&root).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    let port = free_port::free_port();
    fs::write(dirs.config.join("njmule.properties"), format!("net.tcpPort={port}\nsecurity.filterLan=false\n"))
        .unwrap();
    let engine = Engine::start_with(dirs, Switches::QUIET).await.unwrap();
    let mut events = engine.subscribe().live;

    engine.connect_server(Some(Address::new(u32::from(lan), server.port()))).await.unwrap();
    tokio::time::timeout(WAIT, async {
        while let Ok(ev) = events.recv().await {
            if matches!(ev, EngineEvent::ServerConnected { .. }) {
                break;
            }
        }
    })
    .await
    .unwrap();

    // Two searches sent back to back before the answer to the first returns.
    let first = engine.start_search("first".to_owned(), Some(Scope::Server));
    let second = engine.start_search("second".to_owned(), Some(Scope::Server));
    let (_, latest) = tokio::join!(first, second);
    let latest_id = latest.unwrap();

    let found_names = tokio::time::timeout(WAIT, async {
        loop {
            if let Ok(EngineEvent::SearchResults { search, results }) = events.recv().await
                && search == latest_id
            {
                return results.into_iter().map(|r| r.name).collect::<Vec<_>>();
            }
        }
    })
    .await
    .unwrap();
    assert_eq!(found_names, vec!["second.bin"]);

    engine.stop().await;
    server.close();
    recorder.assert_matches("ServerFlowTest/shouldKeepAnAnswerOfTheServerWithTheSearchItAnswers.txt");
}

#[tokio::test]
async fn should_offer_the_shared_files_to_the_server() {
    let Some(lan) = flow_lab::lan_address() else {
        return;
    };
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
    let server = FakeServer::start(
        lan,
        ServerConfig { client_id: flow_lab::high_id(lan), welcome: "hi".to_owned(), results: Vec::new() },
        &mut recorder,
    )
    .await;
    let incoming = dir.path().join("njmule").join("incoming");
    fs::create_dir_all(&incoming).unwrap();
    fs::write(incoming.join("song.mp3"), [1u8, 2, 3, 4, 5]).unwrap();

    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;
    let deadline = tokio::time::Instant::now() + WAIT;
    loop {
        let shared = engine.api().get("/shared").await.unwrap();
        if !shared.as_array().unwrap().is_empty() {
            break;
        }
        assert!(tokio::time::Instant::now() < deadline, "song.mp3 was not shared");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }

    engine.api().post("/servers/connect", json!({"address": format!("{lan}:{}", server.port())})).await.unwrap();
    let _ = engine.next_event(WAIT, |name, _| name == "server.connected").await;
    flow_lab::wait_until(WAIT, || !server.offers().is_empty(), "server offer").await;

    let offer = server.offers().into_iter().next().unwrap();
    assert_eq!(offer.name(), Some("song.mp3"));
    assert_eq!(offer.size(), Some(5));
    assert_eq!(offer.file_type(), Some("Audio"));
    assert_eq!(offer.client_id, 0xFBFB_FBFB);
    assert_eq!(offer.port, 0xFBFB);

    engine.stop().await;
    server.close();
    recorder.assert_matches("ServerFlowTest/shouldOfferTheSharedFilesToTheServer.txt");
}

#[tokio::test]
async fn should_move_on_to_the_next_server_and_report_a_low_id() {
    let Some(lan) = flow_lab::lan_address() else {
        return;
    };
    let dir = tempfile::tempdir().unwrap();
    let dead_port = {
        let probe = TcpListener::bind((lan, 0)).unwrap();
        probe.local_addr().unwrap().port()
    };
    let mut recorder = Recorder::default();
    let server = FakeServer::start(
        lan,
        ServerConfig { client_id: 1234, welcome: "low".to_owned(), results: Vec::new() },
        &mut recorder,
    )
    .await;

    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;
    let met = dir.path().join("two-servers.met");
    let ip = u32::from(lan);
    fs::write(
        &met,
        server_met::write(&[
            MetServer { ip, port: dead_port, tags: Vec::new() },
            MetServer { ip, port: server.port(), tags: Vec::new() },
        ])
        .unwrap(),
    )
    .unwrap();
    engine.api().post("/servers/import", json!({"path": met.to_string_lossy()})).await.unwrap();
    engine.api().post("/servers/connect", json!({})).await.unwrap();

    let (_, login) = engine.next_event(WAIT, |name, _| name == "server.connected").await;
    assert_eq!(login["lowId"].as_bool(), Some(true));
    assert_eq!(login["clientId"].as_u64(), Some(1234));
    assert!(login["server"]["address"].as_str().unwrap().ends_with(&format!(":{}", server.port())));

    engine.stop().await;
    server.close();
    recorder.assert_matches("ServerFlowTest/shouldMoveOnToTheNextServerAndReportALowId.txt");
}

#[tokio::test]
async fn should_drop_dead_servers_but_static_ones_and_take_the_server_list_when_asked() {
    let Some(lan) = flow_lab::lan_address() else {
        return;
    };
    let dir = tempfile::tempdir().unwrap();
    let dead_port = {
        let probe = TcpListener::bind((lan, 0)).unwrap();
        probe.local_addr().unwrap().port()
    };
    let mut recorder = Recorder::default();
    let server = FakeServer::start(
        lan,
        ServerConfig { client_id: flow_lab::high_id(lan), welcome: "hi".to_owned(), results: Vec::new() },
        &mut recorder,
    )
    .await;
    server.knows_servers(vec![Endpoint { ip: u32::from(Ipv4Addr::new(81, 2, 3, 9)), port: 4661 }]);

    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;
    engine.patch("/preferences", json!({"server.addFromServer": "true"})).await;

    let met = dir.path().join("dead.met");
    let failed_once = vec![Tag::Int { name: TagName::id(server_tag::FAIL), value: 1 }];
    fs::write(
        &met,
        server_met::write(&[
            MetServer { ip: u32::from(lan), port: dead_port, tags: failed_once.clone() },
            MetServer { ip: u32::from(Ipv4Addr::new(81, 2, 3, 7)), port: 4661, tags: failed_once },
        ])
        .unwrap(),
    )
    .unwrap();
    engine.api().post("/servers/import", json!({"path": met.to_string_lossy()})).await.unwrap();
    engine.api().put("/servers/81.2.3.7:4661/static", json!({"static": true})).await.unwrap();

    engine.api().post("/servers/connect", json!({"address": format!("{lan}:{}", server.port())})).await.unwrap();
    let _ = engine.next_event(WAIT, |name, _| name == "server.connected").await;

    let mut expected = vec!["81.2.3.7:4661".to_owned(), "81.2.3.9:4661".to_owned(), format!("{lan}:{}", server.port())];
    expected.sort();
    let deadline = tokio::time::Instant::now() + WAIT;
    let list = loop {
        let servers = engine.api().get("/servers").await.unwrap();
        let arr = servers.as_array().unwrap().clone();
        let mut addresses: Vec<String> = arr.iter().map(|s| s["address"].as_str().unwrap().to_owned()).collect();
        addresses.sort();
        if addresses == expected {
            break arr;
        }
        assert!(tokio::time::Instant::now() < deadline, "servers never settled to {expected:?}, got {addresses:?}");
        tokio::time::sleep(Duration::from_millis(50)).await;
    };
    let added_server = list.iter().find(|s| s["address"].as_str() == Some("81.2.3.9:4661")).unwrap();
    assert_eq!(added_server["priority"].as_str(), Some("low"));

    engine.stop().await;
    server.close();
    recorder.assert_matches("ServerFlowTest/shouldDropDeadServersButStaticOnesAndTakeTheServerListWhenAsked.txt");
}

#[tokio::test]
async fn should_ping_the_known_servers_and_connect_back_on_callback_requests() {
    let Some(lan) = flow_lab::lan_address() else {
        return;
    };
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
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

    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;
    let met = dir.path().join("other-server.met");
    fs::write(
        &met,
        server_met::write(&[MetServer { ip: u32::from(lan), port: other.port(), tags: Vec::new() }]).unwrap(),
    )
    .unwrap();
    engine.api().post("/servers/import", json!({"path": met.to_string_lossy()})).await.unwrap();
    engine.api().post("/servers/connect", json!({"address": format!("{lan}:{}", server.port())})).await.unwrap();
    let _ = engine.next_event(WAIT, |name, _| name == "server.connected").await;

    let (_, pinged) = engine
        .next_event(WAIT, |name, ev| {
            name == "server.updated"
                && ev["server"]["address"].as_str().is_some_and(|a| a.ends_with(&format!(":{}", other.port())))
                && ev["server"]["users"].as_u64() == Some(10)
        })
        .await;
    assert_eq!(pinged["server"]["files"].as_u64(), Some(20));

    let firewalled = TcpListener::bind(SocketAddrV4::new(lan, 0)).unwrap();
    let cb_port = firewalled.local_addr().unwrap().port();
    let mut out = PacketWriter::new();
    out.u32(ipv4::to_ed2k(u32::from(lan))).u16(cb_port);
    let request = Packet::written(protocol::EDONKEY, 0x35, out);
    server.broadcast(request.clone());
    server.broadcast(request);

    let (mut peer_stream, _) = tokio::task::spawn_blocking(move || {
        let accepted = firewalled.accept().unwrap();
        accepted.0.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
        let hello = flow_lab::read_tcp_frame_sync(&mut accepted.0.try_clone().unwrap());
        assert_eq!(hello.opcode(), 0x01);
        drop(accepted.0);
        // Second call must not arrive within 2 seconds.
        firewalled.set_nonblocking(true).unwrap();
        let start = std::time::Instant::now();
        while start.elapsed() < Duration::from_secs(2) {
            match firewalled.accept() {
                Ok(_) => panic!("unexpected second callback"),
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(50));
                }
                Err(e) => panic!("accept failed: {e}"),
            }
        }
        ((), ())
    })
    .await
    .unwrap();
    let _ = &mut peer_stream;

    engine.stop().await;
    other.close();
    server.close();
    recorder.assert_matches("ServerFlowTest/shouldPingTheKnownServersAndConnectBackOnCallbackRequests.txt");
}

#[tokio::test]
async fn should_name_a_known_server_after_the_description_it_gives_over_udp() {
    let Some(lan) = flow_lab::lan_address() else {
        return;
    };
    let dir = tempfile::tempdir().unwrap();
    let mut recorder = Recorder::default();
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
    other.describe_as("Other", "Second server", "17.15");

    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;
    let met = dir.path().join("other-server.met");
    fs::write(
        &met,
        server_met::write(&[MetServer { ip: u32::from(lan), port: other.port(), tags: Vec::new() }]).unwrap(),
    )
    .unwrap();
    engine.api().post("/servers/import", json!({"path": met.to_string_lossy()})).await.unwrap();
    engine.api().post("/servers/connect", json!({"address": format!("{lan}:{}", server.port())})).await.unwrap();
    let _ = engine.next_event(WAIT, |name, _| name == "server.connected").await;

    let (_, described) = engine
        .next_event(WAIT, |name, ev| {
            name == "server.updated"
                && ev["server"]["address"].as_str().is_some_and(|a| a.ends_with(&format!(":{}", other.port())))
                && ev["server"]["name"].as_str() == Some("Other")
        })
        .await;
    assert_eq!(described["server"]["description"].as_str(), Some("Second server"));
    assert_eq!(described["server"]["version"].as_str(), Some("17.15"));

    engine.stop().await;
    other.close();
    server.close();
    recorder.assert_matches("ServerFlowTest/shouldNameAKnownServerAfterTheDescriptionItGivesOverUdp.txt");
}

#[tokio::test]
async fn should_answer_hellos_and_report_the_answer() {
    let dir = tempfile::tempdir().unwrap();
    let recorder = Recorder::default();
    let engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;
    let answer = engine.api().post(&format!("/clients/127.0.0.1:{}/hello", engine.tcp_port), json!({})).await.unwrap();
    assert!(answer["software"].as_str().unwrap().starts_with(&format!("njMule {}", nj_mule_info::version())));
    assert_eq!(answer["nick"].as_str(), Some("njMule"));
    assert_eq!(answer["obfuscated"].as_bool(), Some(false));

    engine.stop().await;
    recorder.assert_matches("ServerFlowTest/shouldAnswerHellosAndReportTheAnswer.txt");
}
