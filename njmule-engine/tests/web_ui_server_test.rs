// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's WebUiServerTest, under its names: what the HTTP server itself does for callers. It names no
//! software, answers a bad request plainly, and gets the answer to a refused request across whenever its body comes.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::remote::web_ui::WebUi;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::fake_engine::FakeEngine;
use crate::web_client::{self, Answer, PASSWORD};

const LOGIN_BODY: &str = "password=correct+horse";

#[tokio::test]
async fn should_name_no_server_software_in_an_answer() {
    let mut server = Server::new();
    server.given_the_web_interface().await;
    server.when_asked("GET /login HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").await;
    server.then_the_answer_is("200");
    server.then_the_answer_names_no_server_software();
}

#[tokio::test]
async fn should_answer_a_bad_request_without_a_stack_trace() {
    let mut server = Server::new();
    server.given_the_web_interface().await;
    server
        .when_asked("GET /%2e%2e/%2e%2e/secret HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
        .await;
    server.then_the_answer_is("400");
    server.then_the_answer_names_no_server_software();
}

#[tokio::test]
async fn should_read_the_body_of_a_refused_post_that_comes_after_the_answer() {
    let mut server = Server::new();
    server.given_the_web_interface().await;
    server.given_a_cross_site_post_answered_before_its_body().await;
    server.when_the_body_comes_with_another_request().await;
    server.then_the_refusal_is("403");
    server.then_the_answer_is("200");
}

#[tokio::test]
async fn should_send_no_continue_after_refusing_a_post_waiting_for_it() {
    let mut server = Server::new();
    server.given_the_web_interface().await;
    server.when_a_cross_site_post_waiting_for_continue_is_sent().await;
    server.then_the_answer_is("403");
    server.then_the_answer_holds_one_status();
}

/// The interface on a free port of this computer, a connection kept open and what came back.
struct Server {
    dir: tempfile::TempDir,
    ui: Option<WebUi>,
    connection: Option<TcpStream>,
    refusal: String,
    answer: String,
}

impl Server {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            ui: None,
            connection: None,
            refusal: String::new(),
            answer: String::new(),
        }
    }

    fn port(&self) -> u16 {
        self.ui.as_ref().unwrap().port()
    }

    async fn given_the_web_interface(&mut self) {
        let engine = FakeEngine::new(&self.dir.path().join("njmule"));
        let app = web_client::web_app(&self.dir.path().join("app"));
        self.ui = Some(WebUi::start_with(engine, web_client::settings(PASSWORD), None, 2, app).await.unwrap());
    }

    // Raw bytes, as a caller may send what no well-behaved client would.
    async fn when_asked(&mut self, request: &str) {
        self.answer = web_client::exchange(self.port(), request).await.raw;
    }

    // The body is sent only once the answer is read, the order a busy computer may give any client.
    async fn given_a_cross_site_post_answered_before_its_body(&mut self) {
        let mut connection = TcpStream::connect(("127.0.0.1", self.port())).await.unwrap();
        connection.write_all(cross_site_post("").as_bytes()).await.unwrap();
        self.refusal = read_answer(&mut connection).await;
        self.connection = Some(connection);
    }

    async fn when_a_cross_site_post_waiting_for_continue_is_sent(&mut self) {
        self.when_asked(&cross_site_post("Expect: 100-continue\r\n")).await;
    }

    async fn when_the_body_comes_with_another_request(&mut self) {
        let connection = self.connection.as_mut().unwrap();
        let next = format!("{LOGIN_BODY}GET /login HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
        connection.write_all(next.as_bytes()).await.unwrap();
        let mut rest = Vec::new();
        connection.read_to_end(&mut rest).await.unwrap();
        self.answer = String::from_utf8_lossy(&rest).into_owned();
    }

    fn then_the_refusal_is(&self, status: &str) {
        assert!(self.refusal.starts_with(&format!("HTTP/1.1 {status} ")), "{}", self.refusal);
    }

    fn then_the_answer_is(&self, status: &str) {
        assert!(self.answer.starts_with(&format!("HTTP/1.1 {status} ")), "{}", self.answer);
    }

    fn then_the_answer_holds_one_status(&self) {
        assert_eq!(self.answer.matches("HTTP/1.1 ").count(), 1, "{}", self.answer);
    }

    fn then_the_answer_names_no_server_software(&self) {
        let lower = self.answer.to_ascii_lowercase();
        for name in ["server:", "hyper", "axum", "panicked"] {
            assert!(!lower.contains(name), "{name} in {}", self.answer);
        }
    }
}

fn cross_site_post(headers: &str) -> String {
    format!(
        "POST /login HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: http://elsewhere.example\r\n\
         Content-Type: application/x-www-form-urlencoded\r\nContent-Length: {}\r\n{headers}\r\n",
        LOGIN_BODY.len()
    )
}

// One answer of the connection: its head, then as many bytes as its Content-Length says.
async fn read_answer(connection: &mut TcpStream) -> String {
    let mut head = Vec::new();
    while !head.ends_with(b"\r\n\r\n") {
        let mut byte = [0u8; 1];
        let count = connection.read(&mut byte).await.unwrap();
        assert!(count > 0, "an answer ending early: {}", String::from_utf8_lossy(&head));
        head.push(byte[0]);
    }
    let answer: Answer = web_client::parse(&head);
    let length: usize = answer.header("Content-Length").expect("a Content-Length").parse().unwrap();
    let mut body = vec![0u8; length];
    connection.read_exact(&mut body).await.unwrap();
    format!("{}{}", String::from_utf8_lossy(&head), String::from_utf8_lossy(&body))
}
