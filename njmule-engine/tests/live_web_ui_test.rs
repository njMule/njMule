// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java launcher's LiveWebUiTest, under its names: the web interface started, stopped or started again as its
//! settings change while njMule runs.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::remote::live_web_ui::LiveWebUi;
use njmule_engine::remote::web_ui_settings::WebUiSettings;
use tokio::net::TcpStream;

use crate::fake_engine::FakeEngine;
use crate::free_port;
use crate::web_client::{self, PASSWORD};

#[tokio::test]
async fn should_start_the_interface_when_it_is_turned_on() {
    let mut web = Live::new();
    web.given_the_interface_off().await;

    web.when_the_settings_are(true, web.first).await;

    web.then_it_answers_on(web.first).await;
}

#[tokio::test]
async fn should_start_the_interface_again_on_the_new_port() {
    let mut web = Live::new();
    web.given_the_interface_runs_on_the_first_port().await;

    web.when_the_settings_are(true, web.second).await;

    web.then_it_answers_on(web.second).await;
    web.then_nothing_answers_on(web.first).await;
}

#[tokio::test]
async fn should_start_the_interface_again_on_the_same_port_when_its_host_names_change() {
    let mut web = Live::new();
    web.given_the_interface_runs_on_the_first_port().await;

    web.when_the_host_names_become("njmule.example").await;

    web.then_it_answers_on(web.first).await;
}

#[tokio::test]
async fn should_stop_the_interface_when_it_is_turned_off() {
    let mut web = Live::new();
    web.given_the_interface_runs_on_the_first_port().await;

    web.when_the_settings_are(false, web.first).await;

    web.then_it_does_not_run().await;
    web.then_nothing_answers_on(web.first).await;
}

// An interface started again would have forgotten the session.
#[tokio::test]
async fn should_keep_the_interface_running_when_the_settings_stay_the_same() {
    let mut web = Live::new();
    web.given_the_interface_runs_on_the_first_port().await;
    web.given_a_user_logged_in().await;

    web.when_the_settings_are(true, web.first).await;

    web.then_it_answers_on(web.first).await;
    web.then_it_started_once().await;
}

/// The interface as its settings ask, two free ports for it, and a session opened.
struct Live {
    _dir: tempfile::TempDir,
    web: LiveWebUi<FakeEngine>,
    first: u16,
    second: u16,
    cookie: String,
}

impl Live {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let web = LiveWebUi::new(FakeEngine::new(dir.path()), None);
        let first = free_port::free_port();
        let mut second = free_port::free_port();
        while second == first {
            second = free_port::free_port();
        }
        Self { _dir: dir, web, first, second, cookie: String::new() }
    }

    async fn given_the_interface_off(&mut self) {
        self.web.apply(settings(false, self.first)).await;
    }

    async fn given_the_interface_runs_on_the_first_port(&mut self) {
        self.given_the_interface_off().await;
        self.web.apply(settings(true, self.first)).await;
    }

    async fn given_a_user_logged_in(&mut self) {
        let body = web_client::form(&[("password", PASSWORD)]);
        let headers = [("Content-Type", "application/x-www-form-urlencoded")];
        let login =
            web_client::exchange(self.first, &web_client::request(self.first, "POST", "/login", &headers, &body)).await;
        assert_eq!(login.status, 303);
        self.cookie = login.cookie();
    }

    async fn when_the_settings_are(&mut self, enabled: bool, port: u16) {
        self.web.apply(settings(enabled, port)).await;
    }

    async fn when_the_host_names_become(&mut self, name: &str) {
        self.web.apply(WebUiSettings { hosts: vec![name.to_owned()], ..settings(true, self.first) }).await;
    }

    async fn then_it_answers_on(&self, port: u16) {
        assert_eq!(self.web.port().await, Some(port));
        assert!(answers(port).await);
    }

    async fn then_nothing_answers_on(&self, port: u16) {
        assert!(!answers(port).await);
    }

    async fn then_it_does_not_run(&self) {
        assert_eq!(self.web.port().await, None);
    }

    async fn then_it_started_once(&self) {
        let headers = [("Cookie", self.cookie.as_str())];
        let page = web_client::exchange(self.first, &web_client::request(self.first, "GET", "/", &headers, "")).await;
        assert_eq!(page.status, 200, "{}", page.raw);
    }
}

fn settings(enabled: bool, port: u16) -> WebUiSettings {
    WebUiSettings { enabled, port, ..web_client::settings(PASSWORD) }
}

async fn answers(port: u16) -> bool {
    TcpStream::connect(("127.0.0.1", port)).await.is_ok()
}
