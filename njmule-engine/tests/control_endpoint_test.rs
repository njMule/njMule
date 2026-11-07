// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java ControlEndpointTest that apply to the engine alone, under their names, and the calls the
//! test kit makes first.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing)]

mod free_port;

use std::fs;
use std::net::SocketAddr;

use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::net::tcp_connection::{read_packet, write_packet};
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::io::{PacketWriter, StringEncoding};
use njmule_engine::protocol::packet::opcodes::client;
use njmule_engine::protocol::packet::{Packet, frame_limits, protocol};
use njmule_engine::protocol::tag::tag_codec::write_ed2k_list;
use njmule_engine::protocol::tag::{Ed2kTagFormat, Tag, TagName, file_tag};
use njmule_engine::remote::control_endpoint::ControlEndpoint;
use serde_json::Value;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

struct Running {
    _profile: tempfile::TempDir,
    engine: Engine,
    endpoint: ControlEndpoint,
    address: SocketAddr,
    token: String,
}

async fn running() -> Running {
    running_with("").await
}

async fn running_with(preferences: &str) -> Running {
    let profile = tempfile::tempdir().unwrap();
    let dirs = AppDirectories::under_root(profile.path()).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    // The preferences take no port of 0.
    let port = free_port::free_port();
    fs::write(dirs.config.join("njmule.properties"), format!("net.tcpPort={port}\n{preferences}")).unwrap();
    let engine = Engine::start(dirs).await.unwrap();
    let endpoint = ControlEndpoint::start(engine.clone(), None).await.unwrap();
    let line = endpoint.control_line();
    let control: Value = serde_json::from_str(line.strip_prefix("njmule-control ").expect("the control line")).unwrap();
    let url = control["url"].as_str().unwrap();
    let address = url.strip_prefix("http://").unwrap().strip_suffix("/api/v1").unwrap().parse().unwrap();
    let token = control["token"].as_str().unwrap().to_owned();
    Running { _profile: profile, engine, endpoint, address, token }
}

impl Running {
    async fn call(&self, request_line: &str, headers: &[String]) -> (u16, String) {
        let mut stream = TcpStream::connect(self.address).await.unwrap();
        let mut request = format!("{request_line} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
        for header in headers {
            request.push_str(header);
            request.push_str("\r\n");
        }
        request.push_str("\r\n");
        stream.write_all(request.as_bytes()).await.unwrap();
        let mut answer = Vec::new();
        stream.read_to_end(&mut answer).await.unwrap();
        let answer = String::from_utf8_lossy(&answer).into_owned();
        (answer[9..12].parse().unwrap(), answer)
    }

    fn bearer(&self) -> String {
        format!("Authorization: Bearer {}", self.token)
    }

    async fn stop(self) {
        self.engine.stop().await;
        self.endpoint.stop().await;
    }
}

#[tokio::test]
async fn should_listen_on_the_loopback_address_only() {
    let running = running().await;

    assert!(running.address.ip().is_loopback());
    running.stop().await;
}

#[tokio::test]
async fn should_answer_the_token_of_the_run() {
    let running = running().await;

    let (status, answer) = running.call("GET /api/v1/preferences", &[running.bearer()]).await;

    assert_eq!(status, 200, "{answer}");
    assert!(answer.contains("\"net.tcpPort\""), "{answer}");
    assert_eq!(running.token.len(), 43);
    running.stop().await;
}

#[tokio::test]
async fn should_never_give_the_passwords_of_the_preferences() {
    let running = running_with("proxy.password=secret\n").await;

    let (_, preferences) = running.call("GET /api/v1/preferences", &[running.bearer()]).await;
    let (status, passwords) = running.call("GET /api/v1/preferences/passwords", &[running.bearer()]).await;

    assert!(!preferences.contains("secret") && !preferences.contains("proxy.password"), "{preferences}");
    assert_eq!(status, 200, "{passwords}");
    let set: Value = serde_json::from_str(&passwords[passwords.find("\r\n\r\n").unwrap() + 4..]).unwrap();
    assert_eq!(set, serde_json::json!({"notify.smtpPassword": false, "proxy.password": true}));
    running.stop().await;
}

#[tokio::test]
async fn should_refuse_a_caller_without_the_token() {
    let running = running().await;

    let (without, _) = running.call("GET /api/v1/preferences", &[]).await;
    let (wrong, answer) = running.call("GET /api/v1/preferences", &["Authorization: Bearer nope".to_owned()]).await;

    assert_eq!((without, wrong), (401, 401));
    assert!(answer.to_ascii_lowercase().contains("www-authenticate: bearer"), "{answer}");
    running.stop().await;
}

#[tokio::test]
async fn should_refuse_a_page_of_another_origin_even_with_the_token() {
    let running = running().await;

    let (status, _) =
        running.call("GET /api/v1/preferences", &[running.bearer(), "Origin: https://example.org".to_owned()]).await;

    assert_eq!(status, 403);
    running.stop().await;
}

#[tokio::test]
async fn should_let_the_shells_pages_call_across_origins() {
    let running = running().await;

    let (status, answer) =
        running.call("GET /api/v1/preferences", &[running.bearer(), "Origin: tauri://localhost".to_owned()]).await;

    assert_eq!(status, 200);
    assert!(answer.to_ascii_lowercase().contains("access-control-allow-origin: tauri://localhost"), "{answer}");
    running.stop().await;
}

#[tokio::test]
async fn should_answer_the_calls_the_test_kit_makes_first() {
    let running = running().await;

    for call in ["GET /api/v1/preferences", "GET /api/v1/schedule", "GET /api/v1/update", "GET /api/v1/interfaces"] {
        let (status, answer) = running.call(call, &[running.bearer()]).await;

        assert_eq!(status, 200, "{call}: {answer}");
        assert!(answer.to_ascii_lowercase().contains("content-type: application/json"), "{call}: {answer}");
    }
    running.stop().await;
}

#[tokio::test]
async fn should_answer_an_unknown_call_with_an_error() {
    let running = running().await;

    let (status, answer) = running.call("GET /api/v1/nothing", &[running.bearer()]).await;

    assert_eq!(status, 404);
    assert!(answer.ends_with("{\"error\":\"No such call\"}"), "{answer}");
    running.stop().await;
}

#[tokio::test]
async fn should_say_hello_to_ourselves_through_the_api() {
    let running = running().await;
    let port = running.engine.listen_port();

    let (status, answer) =
        running.call(&format!("POST /api/v1/clients/127.0.0.1:{port}/hello"), &[running.bearer()]).await;

    assert_eq!(status, 200, "{answer}");
    let body: Value = serde_json::from_str(&answer[answer.find("\r\n\r\n").unwrap() + 4..]).unwrap();
    assert_eq!(body["nick"], "njMule");
    assert_eq!(body["obfuscated"], false);
    assert!(body["software"].as_str().unwrap().starts_with("njMule "));
    assert_eq!(body["userHash"], data_encoding::HEXUPPER.encode(&running.engine.user_hash()));
    running.stop().await;
}

#[tokio::test]
async fn should_list_the_clients_met_through_the_api() {
    let running = running().await;
    let mut client = TcpStream::connect(("127.0.0.1", running.engine.listen_port())).await.unwrap();
    let ours = Ours { nick: "fake peer", udp_port: 0, kad_port: 0, misc_options1: 0, misc_options2: 0 };
    write_packet(&mut client, &Hello::of([9; 16], 0, 4662, &ours, 0, 0).encode(false).unwrap()).await.unwrap();
    read_packet(&mut client, &frame_limits::client_handshake).await.unwrap().expect("the answer");

    let (status, answer) = running.call("GET /api/v1/clients/known", &[running.bearer()]).await;

    assert_eq!(status, 200, "{answer}");
    let body: Value = serde_json::from_str(&answer[answer.find("\r\n\r\n").unwrap() + 4..]).unwrap();
    let known = body.as_array().unwrap();
    assert_eq!(known.len(), 1);
    assert_eq!(known[0]["userHash"], "09".repeat(16));
    assert_eq!(known[0]["nick"], "fake peer");
    assert_eq!((known[0]["uploaded"].as_u64(), known[0]["downloaded"].as_u64()), (Some(0), Some(0)));
    assert!(known[0]["country"].is_null());
    running.stop().await;
}

#[tokio::test]
async fn should_list_the_files_a_client_shares_through_the_api() {
    let running = running().await;
    let peer = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let port = peer.local_addr().unwrap().port();
    let client = tokio::spawn(async move {
        let (mut stream, _) = peer.accept().await.unwrap();
        read_packet(&mut stream, &frame_limits::client).await.unwrap().expect("our hello");
        let ours = Ours { nick: "them", udp_port: 0, kad_port: 0, misc_options1: 0, misc_options2: 0 };
        write_packet(&mut stream, &Hello::of([2; 16], 0, 4662, &ours, 0, 0).encode(true).unwrap()).await.unwrap();
        read_packet(&mut stream, &frame_limits::client).await.unwrap().expect("the request");
        let mut list = PacketWriter::new();
        list.u32(1).hash16(&[0x0F; 16]).u32(0).u16(0).u32(2);
        let tags = [
            Tag::String { name: TagName::id(file_tag::FILENAME), value: "film.avi".to_owned() },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: 700 },
        ];
        write_ed2k_list(&mut list, &tags, Ed2kTagFormat::New, StringEncoding::Utf8).unwrap();
        let answer = Packet::written(protocol::EDONKEY, client::ASKSHAREDFILESANSWER, list);
        write_packet(&mut stream, &answer).await.unwrap();
        let _ = read_packet(&mut stream, &frame_limits::client).await;
    });

    let (status, answer) =
        running.call(&format!("POST /api/v1/clients/127.0.0.1:{port}/files"), &[running.bearer()]).await;

    assert_eq!(status, 200, "{answer}");
    let body: Value = serde_json::from_str(&answer[answer.find("\r\n\r\n").unwrap() + 4..]).unwrap();
    assert_eq!(body[0]["hash"], "0F".repeat(16));
    assert_eq!(body[0]["name"], "film.avi");
    assert_eq!(body[0]["size"], 700);
    assert_eq!(body[0]["link"], format!("ed2k://|file|film.avi|700|{}|/", "0F".repeat(16)));
    drop(client);
    running.stop().await;
}
