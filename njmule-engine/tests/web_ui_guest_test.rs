// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's WebUiGuestTest, under its names: a guest, who logs in with a password of its own and may only look,
//! then logs out.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::remote::passwords;
use njmule_engine::remote::web_ui::WebUi;
use njmule_engine::remote::web_ui_settings::WebUiSettings;

use crate::fake_engine::FakeEngine;
use crate::web_client::{self, Answer, PASSWORD};

const GUEST_PASSWORD: &str = "battery staple";

#[tokio::test]
async fn should_show_the_app_to_the_guest_as_to_a_guest() {
    let mut web = Web::new();
    web.given_the_web_interface_runs_with_a_guest().await;
    web.given_logged_in_with(GUEST_PASSWORD).await;

    web.when_the_page_is_asked("/").await;

    web.then_the_answer_is(200, "name=\"njmule-role\" content=\"guest\"");
}

#[tokio::test]
async fn should_show_the_app_to_the_admin_as_to_the_admin() {
    let mut web = Web::new();
    web.given_the_web_interface_runs_with_a_guest().await;
    web.given_logged_in_with(PASSWORD).await;

    web.when_the_page_is_asked("/").await;

    web.then_the_answer_is(200, "name=\"njmule-role\" content=\"admin\"");
}

#[tokio::test]
async fn should_refuse_the_guest_a_change_even_with_its_sessions_token() {
    let mut web = Web::new();
    web.given_the_web_interface_runs_with_a_guest().await;
    web.given_logged_in_with(GUEST_PASSWORD).await;

    web.when_the_app_calls("POST", "/api/v1/servers/disconnect").await;

    web.then_the_answer_is(403, "A guest can only look");
    web.then_the_engine_was_not_asked();
}

#[tokio::test]
async fn should_refuse_the_guest_the_settings_of_the_web_interface() {
    let mut web = Web::new();
    web.given_the_web_interface_runs_with_a_guest().await;
    web.given_logged_in_with(GUEST_PASSWORD).await;

    web.when_the_page_is_asked("/api/v1/webui").await;

    web.then_the_answer_is(403, "A guest can only look");
}

#[tokio::test]
async fn should_end_the_guests_session_on_logout() {
    let mut web = Web::new();
    web.given_the_web_interface_runs_with_a_guest().await;
    web.given_logged_in_with(GUEST_PASSWORD).await;

    web.when_the_app_logs_out().await;

    web.then_the_answer_leads_to("login");
    web.then_the_session_is_over().await;
}

/// The interface with a guest on a free port of this computer, the browser's cookie and the last answer.
struct Web {
    dir: tempfile::TempDir,
    engine: FakeEngine,
    ui: Option<WebUi>,
    cookie: Option<String>,
    answer: Answer,
}

impl Web {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        Self {
            engine: FakeEngine::new(&dir.path().join("njmule")),
            dir,
            ui: None,
            cookie: None,
            answer: Answer::default(),
        }
    }

    fn port(&self) -> u16 {
        self.ui.as_ref().unwrap().port()
    }

    async fn given_the_web_interface_runs_with_a_guest(&mut self) {
        let settings = WebUiSettings {
            guest_password_hash: passwords::hash_with(GUEST_PASSWORD, &[0u8; 16], 1000),
            ..web_client::settings(PASSWORD)
        };
        let app = web_client::web_app(&self.dir.path().join("app"));
        self.ui = Some(WebUi::start_with(self.engine.clone(), settings, None, 2, app).await.unwrap());
    }

    async fn given_logged_in_with(&mut self, password: &str) {
        let login = self.post("/login", &[("password", password)]).await;
        assert_eq!(login.status, 303);
        self.cookie = Some(login.cookie());
    }

    async fn when_the_page_is_asked(&mut self, path: &str) {
        self.answer = self.send("GET", path, &[], "").await;
    }

    async fn when_the_app_calls(&mut self, method: &str, path: &str) {
        let token = self.token().await;
        self.answer = self.send(method, path, &[("X-NjMule-CSRF", &token)], "").await;
    }

    // The app sends the logout form with the session's token, as a form of the page.
    async fn when_the_app_logs_out(&mut self) {
        let token = self.token().await;
        self.answer = self.post("/logout", &[("csrf", &token)]).await;
    }

    fn then_the_answer_is(&self, status: u16, part: &str) {
        assert_eq!(self.answer.status, status, "{}", self.answer.raw);
        assert!(self.answer.text().contains(part), "{}", self.answer.raw);
    }

    fn then_the_answer_leads_to(&self, location: &str) {
        assert_eq!(self.answer.status, 303, "{}", self.answer.raw);
        assert_eq!(self.answer.header("Location"), Some(location));
    }

    async fn then_the_session_is_over(&self) {
        assert_eq!(self.send("GET", "/", &[], "").await.status, 303);
    }

    fn then_the_engine_was_not_asked(&self) {
        assert!(self.engine.with(|fake| fake.calls.is_empty()));
    }

    async fn token(&self) -> String {
        web_client::page_token(&self.send("GET", "/", &[], "").await.text())
    }

    async fn post(&self, path: &str, fields: &[(&str, &str)]) -> Answer {
        let headers = [("Content-Type", "application/x-www-form-urlencoded")];
        self.send("POST", path, &headers, &web_client::form(fields)).await
    }

    async fn send(&self, method: &str, path: &str, extra: &[(&str, &str)], body: &str) -> Answer {
        let cookie = self.cookie.clone();
        let mut headers: Vec<(&str, &str)> = cookie.iter().map(|cookie| ("Cookie", cookie.as_str())).collect();
        headers.extend_from_slice(extra);
        web_client::exchange(self.port(), &web_client::request(self.port(), method, path, &headers, body)).await
    }
}
