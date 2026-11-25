// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Black-box search flow tests against the compiled `njmule` executable, checking every wire packet against the
//! recorded transcripts in `tests/transcripts/SearchFlowTest/`.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::HashSet;
use std::fs;

use njmule_engine::persist::format::server_met::{self, Server as MetServer};
use njmule_engine::protocol::tag::{Tag, TagName, server_tag};
use serde_json::json;

use crate::flow_lab::{self, BlackBoxEngine, FakeServer, Recorder, ServerConfig, WAIT};

#[tokio::test]
async fn should_end_the_global_search_before_a_new_one_so_udp_answers_reach_the_new_one() {
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
    let other = FakeServer::start(
        lan,
        ServerConfig { client_id: 1, welcome: String::new(), results: vec![FakeServer::file("udp.bin", 2000, 1)] },
        &mut recorder,
    )
    .await;

    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;
    let met = dir.path().join("other.met");
    fs::write(
        &met,
        server_met::write(&[MetServer { ip: u32::from(lan), port: other.port(), tags: Vec::new() }]).unwrap(),
    )
    .unwrap();
    engine.api().post("/servers/import", json!({"path": met.to_string_lossy()})).await.unwrap();
    engine.api().post("/servers/connect", json!({"address": format!("{lan}:{}", server.port())})).await.unwrap();
    let _ = engine.next_event(WAIT, |name, _| name == "server.connected").await;

    let earlier = engine.api().post("/searches", json!({"query": "first", "scope": "global"})).await.unwrap()["id"]
        .as_u64()
        .unwrap();
    flow_lab::wait_until(WAIT, || other.udp_searches().len() == 1, "first UDP search").await;

    let latest = engine.api().post("/searches", json!({"query": "second", "scope": "global"})).await.unwrap()["id"]
        .as_u64()
        .unwrap();

    let mut latest_results = HashSet::new();
    let mut earlier_ended = false;
    loop {
        let (name, ev) = engine.next_event(WAIT, |name, _| name == "search.ended" || name == "search.results").await;
        let id = ev["search"].as_u64().unwrap();
        if name == "search.ended" && id == earlier {
            earlier_ended = true;
        } else if name == "search.results" && id == latest {
            for item in ev["results"].as_array().unwrap() {
                latest_results.insert(item["name"].as_str().unwrap().to_owned());
            }
        } else if name == "search.ended" && id == latest {
            break;
        }
    }

    assert!(earlier_ended, "the first search ended before the second");
    assert_eq!(latest_results, HashSet::from(["second.bin".to_owned(), "udp.bin".to_owned()]));
    assert_eq!(other.udp_searches().len(), 2);

    engine.stop().await;
    other.close();
    server.close();
    recorder.assert_matches("SearchFlowTest/shouldEndTheGlobalSearchBeforeANewOneSoUdpAnswersReachTheNewOne.txt");
}

#[tokio::test]
async fn should_ask_more_results_only_for_the_latest_server_search() {
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
    server.answer_searches(|expr| vec![FakeServer::file(&format!("{expr:?}.bin"), 1000, 1)]);
    server.announce_more_results(true);

    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;
    engine.api().post("/servers/connect", json!({"address": format!("{lan}:{}", server.port())})).await.unwrap();
    let _ = engine.next_event(WAIT, |name, _| name == "server.connected").await;

    let earlier = engine.api().post("/searches", json!({"query": "first", "scope": "server"})).await.unwrap()["id"]
        .as_u64()
        .unwrap();
    let _ =
        engine.next_event(WAIT, |name, ev| name == "search.results" && ev["search"].as_u64() == Some(earlier)).await;

    let latest = engine.api().post("/searches", json!({"query": "second", "scope": "server"})).await.unwrap()["id"]
        .as_u64()
        .unwrap();
    let _ = engine.next_event(WAIT, |name, ev| name == "search.results" && ev["search"].as_u64() == Some(latest)).await;

    // Asking more results for the earlier search must be refused (409).
    let earlier_more = engine.api().post(&format!("/searches/{earlier}/more"), json!({})).await;
    assert!(earlier_more.is_err(), "more results for earlier search should be refused");

    // Asking more results for the latest search succeeds.
    engine.api().post(&format!("/searches/{latest}/more"), json!({})).await.unwrap();
    flow_lab::wait_until(WAIT, || server.more_requests().len() == 1, "more results request").await;
    assert!(format!("{:?}", server.more_requests()[0]).to_lowercase().contains("second"));

    engine.stop().await;
    server.close();
    recorder.assert_matches("SearchFlowTest/shouldAskMoreResultsOnlyForTheLatestServerSearch.txt");
}

#[tokio::test]
async fn should_skip_servers_without_a_udp_port_in_a_global_search() {
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
    let other = FakeServer::start(
        lan,
        ServerConfig { client_id: 1, welcome: String::new(), results: vec![FakeServer::file("udp.bin", 2000, 1)] },
        &mut recorder,
    )
    .await;

    let mut engine = BlackBoxEngine::start(&dir.path().join("njmule")).await;
    let met_bad = dir.path().join("bad.met");
    fs::write(
        &met_bad,
        server_met::write(&[MetServer {
            ip: u32::from(lan),
            port: 65_533,
            tags: vec![Tag::Int { name: TagName::id(server_tag::PREFERENCE), value: 1 }],
        }])
        .unwrap(),
    )
    .unwrap();
    engine.api().post("/servers/import", json!({"path": met_bad.to_string_lossy()})).await.unwrap();
    let met_other = dir.path().join("other.met");
    fs::write(
        &met_other,
        server_met::write(&[MetServer { ip: u32::from(lan), port: other.port(), tags: Vec::new() }]).unwrap(),
    )
    .unwrap();
    engine.api().post("/servers/import", json!({"path": met_other.to_string_lossy()})).await.unwrap();
    engine.api().post("/servers/connect", json!({"address": format!("{lan}:{}", server.port())})).await.unwrap();
    let _ = engine.next_event(WAIT, |name, _| name == "server.connected").await;

    let search_id =
        engine.api().post("/searches", json!({"query": "anything", "scope": "global"})).await.unwrap()["id"]
            .as_u64()
            .unwrap();
    let (_, ended) =
        engine.next_event(WAIT, |name, ev| name == "search.ended" && ev["search"].as_u64() == Some(search_id)).await;
    assert_eq!(ended["results"].as_u64(), Some(1));
    assert_eq!(other.udp_searches().len(), 1);
    assert!(!engine.engine_log().contains("global search failed"));

    engine.stop().await;
    other.close();
    server.close();
    recorder.assert_matches("SearchFlowTest/shouldSkipServersWithoutAUdpPortInAGlobalSearch.txt");
}
