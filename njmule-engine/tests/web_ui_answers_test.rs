// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's WebUiAnswersTest, under its names: how the web interface answers what the engine does, against an
//! engine the test controls.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::io;
use std::time::{Duration, Instant};

use njmule_engine::remote::web_ui::WebUi;
use njmule_engine::remote::web_ui_settings::WebUiSettings;
use tokio::task::JoinHandle;

use crate::fake_engine::FakeEngine;
use crate::web_client::{self, Answer, PASSWORD};

#[tokio::test]
async fn should_check_few_passwords_at_once() {
    let mut web = Web::new();
    web.given_the_web_interface_runs_with_a_slow_password(1).await;
    web.given_a_login_being_checked("first").await;

    web.when_posted_without_token("/login", &[("password", "second")]).await;

    web.then_the_status_is(429);
    web.then_the_body_contains(&["try again"]);
    web.then_the_first_login_gets(401).await;
}

#[tokio::test]
async fn should_answer_head_with_the_headers_only() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_head_is_asked("/login").await;

    web.then_the_status_is(200);
    web.then_the_header_is("Content-Type", "text/html; charset=utf-8");
    web.then_the_content_length_is_positive();
    web.then_the_body_is("");
}

#[tokio::test]
async fn should_send_a_visitor_asking_the_head_of_a_page_to_the_login() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_head_is_asked("/").await;

    web.then_the_status_is(303);
}

#[tokio::test]
async fn should_answer_the_head_of_a_page_to_a_user_logged_in() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;

    web.when_the_head_is_asked("/").await;

    web.then_the_status_is(200);
}

#[tokio::test]
async fn should_answer_the_head_of_an_unknown_page_with_not_found() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;

    web.when_the_head_is_asked("/nowhere").await;

    web.then_the_status_is(404);
}

#[tokio::test]
async fn should_give_its_icon_without_a_login() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_page_is_asked("/favicon.svg").await;

    web.then_the_status_is(200);
    web.then_the_header_is("Content-Type", "image/svg+xml");
    web.then_the_body_contains(&["<svg"]);
}

#[tokio::test]
async fn should_link_its_icons_from_the_pages() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_page_is_asked("/login").await;

    web.then_the_body_contains(&[
        "href=\"favicon.png\" sizes=\"32x32\"",
        "href=\"favicon.svg\" type=\"image/svg+xml\"",
    ]);
}

#[tokio::test]
async fn should_keep_the_sessions_of_two_instances_on_one_computer() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;
    web.given_another_instance_with_the_user_logged_in().await;

    web.when_the_page_is_asked_with_the_cookies_of_both_instances("/").await;

    web.then_the_status_is(200);
    web.then_the_cookies_are_named_after_the_ports_of_their_instances();
}

#[tokio::test]
async fn should_not_start_while_off() {
    let mut web = Web::new();

    web.when_the_web_interface_starts_with(WebUiSettings::default()).await;

    web.then_exception_occurred(io::ErrorKind::InvalidInput);
}

#[tokio::test]
async fn should_not_start_without_a_password() {
    let mut web = Web::new();

    web.when_the_web_interface_starts_with(WebUiSettings { enabled: true, ..WebUiSettings::default() }).await;

    web.then_exception_occurred(io::ErrorKind::InvalidInput);
}

#[tokio::test]
async fn should_answer_the_apps_calls_with_the_login_session() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;

    web.when_the_api_is_called("GET", "/api/v1/downloads", None).await;

    web.then_the_status_is(200);
    web.then_the_body_is("[]");
}

#[tokio::test]
async fn should_turn_away_the_apps_calls_without_login() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_api_is_called("GET", "/api/v1/status", None).await;

    web.then_the_status_is(401);
}

#[tokio::test]
async fn should_refuse_the_apps_changes_without_the_sessions_token() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;

    web.when_the_api_is_called("POST", "/api/v1/servers/disconnect", Some("not-the-token")).await;

    web.then_the_status_is(403);
    web.then_the_engine_was_not_asked_to_disconnect();
}

#[tokio::test]
async fn should_take_the_apps_changes_with_the_sessions_token() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;

    web.when_the_api_is_called_with_the_sessions_token("POST", "/api/v1/servers/disconnect").await;

    web.then_the_status_is(200);
    web.then_the_engine_was_asked_to_disconnect();
}

#[tokio::test]
async fn should_serve_the_apps_translations_before_the_login() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_page_is_asked("/i18n/en.json").await;

    web.then_the_status_is(200);
    web.then_the_body_contains(&["\"IDS_EM_TRANS\": \"Transfers\""]);
    web.then_the_header_is_missing("Content-Encoding");
}

#[tokio::test]
async fn should_serve_the_flag_of_a_country_as_an_image() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_page_is_asked("/flags/it.png").await;

    web.then_the_status_is(200);
    web.then_the_header_is("Content-Type", "image/png");
}

#[tokio::test]
async fn should_send_the_apps_files_compressed_to_a_browser_taking_gzip() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_page_is_asked_taking_gzip("/i18n/en.json").await;

    web.then_it_comes_compressed_holding("\"IDS_EM_TRANS\": \"Transfers\"");
}

#[tokio::test]
async fn should_serve_no_other_resource_as_a_file_of_the_app() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;

    web.when_the_page_is_asked("/index.html").await;

    web.then_the_status_is(404);
}

#[tokio::test]
async fn should_serve_the_app_with_the_sessions_token_at_the_top() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;

    web.when_the_page_is_asked("/").await;

    web.then_the_status_is(200);
    web.then_the_body_contains(&["<meta name=\"njmule-csrf\" content=\""]);
    web.then_the_body_does_not_contain("__NJMULE_CSRF__");
    web.then_the_header_is(
        "Content-Security-Policy",
        "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; \
         connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
    );
}

#[tokio::test]
async fn should_refuse_a_login_that_is_no_post() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_page_is_asked_with_the_method("/login", "PUT").await;

    web.then_the_status_is(405);
}

#[tokio::test]
async fn should_refuse_a_huge_login() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_posted_without_token("/login", &[("password", &"x".repeat(16 * 1024))]).await;

    web.then_the_status_is(400);
    web.then_the_body_is("Bad request.");
}

#[tokio::test]
async fn should_refuse_a_login_from_an_unreadable_origin() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;
    web.given_the_origin("http://bad host");

    web.when_posted_without_token("/login", &[("password", PASSWORD)]).await;

    web.then_the_status_is(403);
}

// The style sheet is only read.
#[tokio::test]
async fn should_refuse_to_take_a_post_on_the_style_sheet() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_posted_without_token("/style.css", &[]).await;

    web.then_the_status_is(403);
}

#[tokio::test]
async fn should_not_open_the_session_with_other_cookies() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;
    web.given_only_the_cookies("other=1; broken");

    web.when_the_page_is_asked("/").await;

    web.then_the_status_is(303);
}

#[tokio::test]
async fn should_not_open_the_session_with_a_forged_cookie() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;
    web.given_the_session_cookie_forged("forged");

    web.when_the_page_is_asked("/").await;

    web.then_the_status_is(303);
}

#[tokio::test]
async fn should_open_the_session_with_its_own_cookie_among_others() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;
    web.given_other_cookies_before_the_session_one("other=1; broken");

    web.when_the_page_is_asked("/").await;

    web.then_the_status_is(200);
}

#[tokio::test]
async fn should_answer_not_found_for_a_form_of_an_unknown_page() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;

    web.when_the_form_is_posted("/nowhere", &[]).await;

    web.then_the_status_is(404);
    web.then_the_body_is("Not found.");
}

#[tokio::test]
async fn should_still_answer_once_the_events_end() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;
    web.given_the_events_end();

    web.when_the_page_is_asked("/").await;

    web.then_the_status_is(200);
}

#[tokio::test]
async fn should_still_show_the_login_page_once_the_events_end() {
    let mut web = Web::new();
    web.given_another_instance_whose_events_ended().await;

    web.when_the_login_page_of_the_other_instance_is_asked().await;

    web.then_the_status_is(200);
}

/// The interface on a free port of this computer, another one beside it, the browser's cookies and the last answer.
struct Web {
    dir: tempfile::TempDir,
    engine: FakeEngine,
    ui: Option<WebUi>,
    cookie: Option<String>,
    origin: Option<String>,
    answer: Answer,
    first_login: Option<JoinHandle<Answer>>,
    other_engine: Option<FakeEngine>,
    other: Option<WebUi>,
    other_cookie: String,
    mine: String,
    failure: Option<io::Error>,
}

impl Web {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        Self {
            engine: FakeEngine::new(&dir.path().join("njmule")),
            dir,
            ui: None,
            cookie: None,
            origin: None,
            answer: Answer::default(),
            first_login: None,
            other_engine: None,
            other: None,
            other_cookie: String::new(),
            mine: String::new(),
            failure: None,
        }
    }

    fn port(&self) -> u16 {
        self.ui.as_ref().unwrap().port()
    }

    async fn given_the_web_interface_runs(&mut self) {
        self.start(web_client::settings(PASSWORD), 2).await;
    }

    async fn given_a_logged_in_user(&mut self) {
        self.given_the_web_interface_runs().await;
        let login = self.post("/login", &[("password", PASSWORD)]).await;
        assert_eq!(login.status, 303);
        self.cookie = Some(login.cookie());
    }

    // Enough rounds to keep the first check busy for a while.
    async fn given_the_web_interface_runs_with_a_slow_password(&mut self, checks_at_once: usize) {
        let slow = format!("pbkdf2-sha512$300000${}${}", "A".repeat(22), "A".repeat(86));
        let settings = WebUiSettings { password_hash: slow, ..web_client::settings(PASSWORD) };
        self.start(settings, checks_at_once).await;
    }

    async fn start(&mut self, settings: WebUiSettings, checks: usize) {
        let app = web_client::web_app(&self.dir.path().join("app"));
        self.ui = Some(WebUi::start_with(self.engine.clone(), settings, None, checks, app).await.unwrap());
    }

    async fn given_a_login_being_checked(&mut self, password: &str) {
        let port = self.port();
        let request = login_request(port, password);
        self.first_login = Some(tokio::spawn(async move { web_client::exchange(port, &request).await }));
        let end = Instant::now() + Duration::from_secs(20);
        while self.ui.as_ref().unwrap().checking_passwords() == 0 {
            assert!(Instant::now() < end, "waited too long");
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    }

    async fn given_another_instance_with_the_user_logged_in(&mut self) {
        self.start_another_instance().await;
        let port = self.other.as_ref().unwrap().port();
        let login = web_client::exchange(port, &login_request(port, PASSWORD)).await;
        self.other_cookie = login.cookie();
    }

    async fn given_another_instance_whose_events_ended(&mut self) {
        self.start_another_instance().await;
        self.other_engine.as_ref().unwrap().stop();
    }

    fn given_the_origin(&mut self, value: &str) {
        self.origin = Some(value.to_owned());
    }

    fn given_only_the_cookies(&mut self, cookies: &str) {
        self.cookie = Some(cookies.to_owned());
    }

    fn given_the_session_cookie_forged(&mut self, value: &str) {
        let cookie = self.cookie.take().unwrap();
        self.cookie = Some(format!("{}={value}", cookie.split_once('=').unwrap().0));
    }

    fn given_other_cookies_before_the_session_one(&mut self, others: &str) {
        self.cookie = Some(format!("{others}; {}", self.cookie.take().unwrap()));
    }

    fn given_the_events_end(&self) {
        self.engine.stop();
    }

    async fn when_the_page_is_asked(&mut self, path: &str) {
        self.answer = self.get(path, &[]).await;
    }

    async fn when_the_page_is_asked_taking_gzip(&mut self, path: &str) {
        self.answer = self.get(path, &[("Accept-Encoding", "gzip, deflate, br")]).await;
    }

    async fn when_the_head_is_asked(&mut self, path: &str) {
        self.when_the_page_is_asked_with_the_method(path, "HEAD").await;
    }

    async fn when_the_page_is_asked_with_the_method(&mut self, path: &str, method: &str) {
        self.answer = self.send(method, path, &[], "").await;
    }

    async fn when_the_api_is_called(&mut self, method: &str, path: &str, token: Option<&str>) {
        let headers: Vec<(&str, &str)> = token.iter().map(|token| ("X-NjMule-CSRF", *token)).collect();
        self.answer = self.send(method, path, &headers, "").await;
    }

    // The token the app's page carries for its session.
    async fn when_the_api_is_called_with_the_sessions_token(&mut self, method: &str, path: &str) {
        let token = web_client::page_token(&self.get("/", &[]).await.text());
        self.when_the_api_is_called(method, path, Some(&token)).await;
    }

    async fn when_the_form_is_posted(&mut self, path: &str, fields: &[(&str, &str)]) {
        let token = web_client::page_token(&self.get("/", &[]).await.text());
        let mut all = fields.to_vec();
        all.push(("csrf", &token));
        self.answer = self.post(path, &all).await;
    }

    async fn when_posted_without_token(&mut self, path: &str, fields: &[(&str, &str)]) {
        self.answer = self.post(path, fields).await;
    }

    // Cookies do not depend on the port: the browser sends both to each.
    async fn when_the_page_is_asked_with_the_cookies_of_both_instances(&mut self, path: &str) {
        self.mine = self.cookie.take().unwrap();
        self.cookie = Some(format!("{}; {}", self.other_cookie, self.mine));
        self.answer = self.get(path, &[]).await;
    }

    async fn when_the_web_interface_starts_with(&mut self, settings: WebUiSettings) {
        let app = web_client::web_app(&self.dir.path().join("app"));
        match WebUi::start_with(self.engine.clone(), settings, None, 2, app).await {
            Ok(ui) => self.ui = Some(ui),
            Err(e) => self.failure = Some(e),
        }
    }

    async fn when_the_login_page_of_the_other_instance_is_asked(&mut self) {
        let port = self.other.as_ref().unwrap().port();
        self.answer = web_client::exchange(port, &web_client::request(port, "GET", "/login", &[], "")).await;
    }

    fn then_the_status_is(&self, status: u16) {
        assert_eq!(self.answer.status, status, "{}", self.answer.raw);
    }

    fn then_the_header_is(&self, name: &str, value: &str) {
        assert_eq!(self.answer.header(name), Some(value), "{}", self.answer.raw);
    }

    fn then_the_content_length_is_positive(&self) {
        let length: u64 = self.answer.header("Content-Length").unwrap_or("0").parse().unwrap();
        assert!(length > 0, "{}", self.answer.raw);
    }

    fn then_the_body_is(&self, body: &str) {
        assert_eq!(self.answer.text(), body);
    }

    fn then_the_header_is_missing(&self, name: &str) {
        assert_eq!(self.answer.header(name), None, "{}", self.answer.raw);
    }

    fn then_it_comes_compressed_holding(&self, part: &str) {
        assert_eq!(self.answer.status, 200);
        assert_eq!(self.answer.header("Content-Encoding"), Some("gzip"));
        assert_eq!(self.answer.header("Vary"), Some("Accept-Encoding"));
        assert!(self.answer.unpacked().contains(part));
    }

    fn then_the_body_contains(&self, parts: &[&str]) {
        let body = self.answer.text();
        for part in parts {
            assert!(body.contains(part), "{part} in {body}");
        }
    }

    fn then_the_body_does_not_contain(&self, part: &str) {
        assert!(!self.answer.text().contains(part));
    }

    async fn then_the_first_login_gets(&mut self, status: u16) {
        assert_eq!(self.first_login.take().unwrap().await.unwrap().status, status);
    }

    fn then_the_cookies_are_named_after_the_ports_of_their_instances(&self) {
        assert!(self.mine.starts_with(&format!("njmule-session-{}=", self.port())), "{}", self.mine);
        let other = self.other.as_ref().unwrap().port();
        assert!(self.other_cookie.starts_with(&format!("njmule-session-{other}=")), "{}", self.other_cookie);
    }

    fn then_the_engine_was_not_asked_to_disconnect(&self) {
        assert!(!self.engine.with(|fake| fake.calls.contains(&"disconnect".to_owned())));
    }

    fn then_the_engine_was_asked_to_disconnect(&self) {
        assert_eq!(self.engine.with(|fake| fake.calls.clone()), ["disconnect"]);
    }

    fn then_exception_occurred(&self, kind: io::ErrorKind) {
        assert_eq!(self.failure.as_ref().map(io::Error::kind), Some(kind));
    }

    async fn start_another_instance(&mut self) {
        let engine = FakeEngine::new(&self.dir.path().join("other"));
        let app = web_client::web_app(&self.dir.path().join("other-app"));
        let settings = web_client::settings(PASSWORD);
        self.other = Some(WebUi::start_with(engine.clone(), settings, None, 2, app).await.unwrap());
        self.other_engine = Some(engine);
    }

    async fn get(&self, path: &str, extra: &[(&str, &str)]) -> Answer {
        self.send("GET", path, extra, "").await
    }

    async fn post(&self, path: &str, fields: &[(&str, &str)]) -> Answer {
        let origin = self.origin.clone();
        let mut headers = vec![("Content-Type", "application/x-www-form-urlencoded")];
        headers.extend(origin.iter().map(|origin| ("Origin", origin.as_str())));
        self.send("POST", path, &headers, &web_client::form(fields)).await
    }

    async fn send(&self, method: &str, path: &str, extra: &[(&str, &str)], body: &str) -> Answer {
        let cookie = self.cookie.clone();
        let mut headers: Vec<(&str, &str)> = cookie.iter().map(|cookie| ("Cookie", cookie.as_str())).collect();
        headers.extend_from_slice(extra);
        web_client::exchange(self.port(), &web_client::request(self.port(), method, path, &headers, body)).await
    }
}

fn login_request(port: u16, password: &str) -> String {
    let body = web_client::form(&[("password", password)]);
    web_client::request(port, "POST", "/login", &[("Content-Type", "application/x-www-form-urlencoded")], &body)
}
