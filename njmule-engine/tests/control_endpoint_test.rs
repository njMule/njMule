// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java ControlEndpointTest that apply to the engine alone, under their names, and the calls the
//! test kit makes first.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing)]

use std::fs;
use std::net::SocketAddr;

use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
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
    // A port the system has free: the preferences take none of 0.
    let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
    fs::write(dirs.config.join("njmule.properties"), format!("net.tcpPort={port}\n{preferences}")).unwrap();
    let engine = Engine::start(dirs).await.unwrap();
    let endpoint = ControlEndpoint::start(engine.clone()).await.unwrap();
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
