// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's WebUiApiTest, under its names: the remote-control API as the web interface serves it next to its
//! pages, for the holders of the API token.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use njmule_engine::remote::api_token;
use njmule_engine::remote::sessions::MAX_FAILURES;
use njmule_engine::remote::web_ui::WebUi;
use njmule_engine::remote::web_ui_settings::WebUiSettings;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::fake_engine::FakeEngine;
use crate::web_client::{self, Answer, PASSWORD};

const SHELL_PAGE: &str = "http://tauri.localhost";

#[tokio::test]
async fn should_answer_under_its_prefix_with_the_token_made_at_the_start() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("GET", "/api/v1", Some(&api.token())).await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&["\"name\":\"njMule\"", "\"api\":1"]);
}

#[tokio::test]
async fn should_count_wrong_tokens_as_failed_logins_of_their_address() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_wrong_tokens_sent(MAX_FAILURES).await;

    api.when_called("GET", "/api/v1/status", Some("wrong")).await;

    api.then_the_status_is(429);
}

#[tokio::test]
async fn should_stop_nj_mule_when_the_api_asks() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("POST", "/api/v1/shutdown", Some(&api.token())).await;

    api.then_the_status_is(202);
    api.then_nj_mule_was_stopped().await;
}

#[tokio::test]
async fn should_end_the_event_streams_when_the_interface_stops() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_an_event_stream_open().await;

    api.when_the_interface_stops().await;

    api.then_the_stream_ends().await;
}

#[tokio::test]
async fn should_answer_not_found_while_the_api_is_off() {
    let mut api = Api::new();
    api.given_the_web_interface_runs_without_the_api().await;

    api.when_called("GET", "/api/v1/status", Some("anything")).await;

    api.then_the_status_is(404);
    api.then_the_body_contains(&["API is off"]);
}

#[tokio::test]
async fn should_let_the_desktop_shells_pages_ask_across_origins() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called_from("OPTIONS", "/api/v1/downloads", None, SHELL_PAGE).await;

    api.then_the_status_is(204);
    api.then_the_answer_allows(SHELL_PAGE);
}

#[tokio::test]
async fn should_let_the_desktop_shells_pages_read_what_the_token_gets() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called_from("GET", "/api/v1", Some(&api.token()), SHELL_PAGE).await;

    api.then_the_status_is(200);
    api.then_the_answer_is_readable_by(Some(SHELL_PAGE));
}

#[tokio::test]
async fn should_let_no_other_page_ask_across_origins() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called_from("OPTIONS", "/api/v1/downloads", None, "https://example.org").await;

    api.then_the_status_is(403);
    api.then_the_answer_is_readable_by(None);
}

/// The interface with its API on a free port of this computer, an event stream open and the last answer.
struct Api {
    dir: tempfile::TempDir,
    engine: FakeEngine,
    ui: Option<WebUi>,
    port: u16,
    exited: Arc<AtomicBool>,
    stream: Option<TcpStream>,
    answer: Answer,
}

impl Api {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        Self {
            engine: FakeEngine::new(&dir.path().join("njmule")),
            dir,
            ui: None,
            port: 0,
            exited: Arc::default(),
            stream: None,
            answer: Answer::default(),
        }
    }

    fn token(&self) -> String {
        api_token::load(&self.engine.dirs.config).unwrap().unwrap()
    }

    async fn given_the_api_runs(&mut self) {
        self.start(WebUiSettings { api: true, ..web_client::settings(PASSWORD) }).await;
    }

    async fn given_the_web_interface_runs_without_the_api(&mut self) {
        self.start(web_client::settings(PASSWORD)).await;
    }

    async fn start(&mut self, settings: WebUiSettings) {
        let exited = Arc::clone(&self.exited);
        let exit = Arc::new(move || exited.store(true, Ordering::SeqCst));
        let app = web_client::web_app(&self.dir.path().join("app"));
        let ui = WebUi::start_with(self.engine.clone(), settings, Some(exit), 1, app).await.unwrap();
        self.port = ui.port();
        self.ui = Some(ui);
    }

    async fn given_wrong_tokens_sent(&mut self, count: u32) {
        for _ in 0..count {
            self.when_called("GET", "/api/v1/status", Some("wrong")).await;
            self.then_the_status_is(401);
        }
    }

    async fn given_an_event_stream_open(&mut self) {
        let mut stream = TcpStream::connect(("127.0.0.1", self.port)).await.unwrap();
        let request = format!(
            "GET /api/v1/events HTTP/1.1\r\nHost: 127.0.0.1:{}\r\nAuthorization: Bearer {}\r\n\r\n",
            self.port,
            self.token()
        );
        stream.write_all(request.as_bytes()).await.unwrap();
        let mut carried = String::new();
        let deadline = Instant::now() + Duration::from_secs(5);
        while !carried.contains("njMule events") {
            let mut read = [0u8; 4096];
            let left = deadline.saturating_duration_since(Instant::now());
            let count = tokio::time::timeout(left, stream.read(&mut read)).await.unwrap().unwrap();
            assert!(count > 0, "the stream ended early: {carried}");
            carried.push_str(&String::from_utf8_lossy(&read[..count]));
        }
        assert!(carried.starts_with("HTTP/1.1 200"), "{carried}");
        self.stream = Some(stream);
    }

    async fn when_called(&mut self, method: &str, path: &str, token: Option<&str>) {
        let bearer = token.map(|token| format!("Bearer {token}"));
        let headers: Vec<(&str, &str)> = bearer.iter().map(|bearer| ("Authorization", bearer.as_str())).collect();
        self.answer =
            web_client::exchange(self.port, &web_client::request(self.port, method, path, &headers, "")).await;
    }

    async fn when_called_from(&mut self, method: &str, path: &str, token: Option<&str>, origin: &str) {
        let bearer = token.map(|token| format!("Bearer {token}"));
        let mut headers: Vec<(&str, &str)> = vec![("Origin", origin)];
        headers.extend(bearer.iter().map(|bearer| ("Authorization", bearer.as_str())));
        self.answer =
            web_client::exchange(self.port, &web_client::request(self.port, method, path, &headers, "")).await;
    }

    async fn when_the_interface_stops(&mut self) {
        self.ui.take().unwrap().stop().await;
    }

    fn then_the_status_is(&self, status: u16) {
        assert_eq!(self.answer.status, status, "{}", self.answer.raw);
    }

    fn then_the_body_contains(&self, parts: &[&str]) {
        let body = self.answer.text();
        for part in parts {
            assert!(body.contains(part), "{part} in {body}");
        }
    }

    fn then_the_answer_allows(&self, origin: &str) {
        self.then_the_answer_is_readable_by(Some(origin));
        let allowed = self.answer.header("Access-Control-Allow-Headers").unwrap_or_default();
        assert!(allowed.contains("Authorization"), "{}", self.answer.raw);
    }

    fn then_the_answer_is_readable_by(&self, origin: Option<&str>) {
        assert_eq!(self.answer.header("Access-Control-Allow-Origin"), origin, "{}", self.answer.raw);
    }

    async fn then_nj_mule_was_stopped(&self) {
        let deadline = Instant::now() + Duration::from_secs(10);
        while !self.exited.load(Ordering::SeqCst) {
            assert!(Instant::now() < deadline, "njMule was not stopped");
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }

    async fn then_the_stream_ends(&mut self) {
        let mut rest = Vec::new();
        let stream = self.stream.as_mut().unwrap();
        tokio::time::timeout(Duration::from_secs(5), stream.read_to_end(&mut rest))
            .await
            .expect("the stream ends")
            .unwrap();
    }
}
