// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's WebUiTest, under its names: the web interface over HTTP, its login, its forms and the host names
//! it answers.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::remote::sessions::MAX_FAILURES;
use njmule_engine::remote::web_ui::WebUi;

use crate::fake_engine::FakeEngine;
use crate::web_client::{self, Answer, PASSWORD};

#[tokio::test]
async fn should_send_visitors_to_the_login_page() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_page_is_asked("/").await;

    web.then_the_status_is(303);
    web.then_the_header_is("Location", "login");
    web.then_the_answer_forbids_frames_and_foreign_content();
}

#[tokio::test]
async fn should_show_the_login_page_without_form_token() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_page_is_asked("/login").await;

    web.then_the_status_is(200);
    web.then_the_body_contains(&["type=\"password\""]);
    web.then_the_body_does_not_contain("csrf");
}

#[tokio::test]
async fn should_name_the_password_field_of_the_login_page_in_sight() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_page_is_asked("/login").await;

    web.then_the_body_contains(&["<label for=\"password\">Password</label>", "id=\"password\""]);
}

#[tokio::test]
async fn should_serve_the_style_sheet_to_visitors() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_page_is_asked("/style.css").await;

    web.then_the_body_contains(&["color-scheme"]);
}

#[tokio::test]
async fn should_refuse_the_forms_of_visitors() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_posted("/logout", &[]).await;

    web.then_the_status_is(403);
}

#[tokio::test]
async fn should_lock_out_after_too_many_wrong_passwords() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;
    web.given_wrong_passwords_sent(MAX_FAILURES).await;

    web.when_posted("/login", &[("password", PASSWORD)]).await;

    web.then_the_status_is(429);
    web.then_no_cookie_is_set();
}

#[tokio::test]
async fn should_let_another_client_of_a_trusted_proxy_log_in_while_one_is_locked_out() {
    let mut web = Web::new();
    web.given_the_web_interface_runs_behind_the_proxies(&["127.0.0.0/8"]).await;
    web.given_wrong_passwords_sent_for("198.51.100.7", MAX_FAILURES).await;

    web.when_logged_in_for("198.51.100.8").await;

    web.then_the_status_is(303);
}

#[tokio::test]
async fn should_believe_the_client_named_by_no_address_it_does_not_trust() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;
    web.given_wrong_passwords_sent_for("198.51.100.7", MAX_FAILURES).await;

    web.when_logged_in_for("198.51.100.8").await;

    web.then_the_status_is(429);
}

#[tokio::test]
async fn should_serve_the_app_to_a_user_logged_in() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;

    web.when_the_page_is_asked("/").await;

    web.then_the_status_is(200);
    web.then_the_body_contains(&["<div id=\"app\"></div>", "name=\"njmule-csrf\""]);
}

#[tokio::test]
async fn should_answer_not_found_for_an_unknown_page() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;

    web.when_the_page_is_asked("/nowhere").await;

    web.then_the_status_is(404);
}

#[tokio::test]
async fn should_refuse_a_method_the_pages_do_not_take() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;

    web.when_the_page_is_asked_with_the_method("/", "PUT").await;

    web.then_the_status_is(405);
}

#[tokio::test]
async fn should_end_the_session_on_logout() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;
    web.given_the_form_token_of_the_page("/").await;

    web.when_posted("/logout", &[]).await;

    web.then_the_status_is(303);
    web.then_the_cookie_expires();
    web.then_the_session_is_over().await;
}

#[tokio::test]
async fn should_refuse_a_form_without_token() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;

    web.when_posted("/logout", &[]).await;

    web.then_the_status_is(403);
    web.then_the_session_goes_on().await;
}

#[tokio::test]
async fn should_refuse_a_form_with_another_token() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;
    web.given_the_form_token("not-the-token");

    web.when_posted("/logout", &[]).await;

    web.then_the_status_is(403);
    web.then_the_session_goes_on().await;
}

#[tokio::test]
async fn should_refuse_a_form_from_another_site() {
    let mut web = Web::new();
    web.given_a_logged_in_user().await;
    web.given_the_form_token_of_the_page("/").await;
    web.given_the_origin("http://evil.example");

    web.when_posted("/logout", &[]).await;

    web.then_the_status_is(403);
    web.then_the_session_goes_on().await;
}

// With no-referrer a browser sends "Origin: null" even to the page's own origin.
#[tokio::test]
async fn should_have_browsers_send_the_origin_of_the_pages() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_page_is_asked("/login").await;

    web.then_the_header_is("Referrer-Policy", "same-origin");
}

#[tokio::test]
async fn should_take_a_login_from_the_origin_of_the_pages() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;
    web.given_the_origin_of_the_pages();

    web.when_posted("/login", &[("password", PASSWORD)]).await;

    web.then_the_status_is(303);
}

#[tokio::test]
async fn should_refuse_a_login_from_an_opaque_origin() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;
    web.given_the_origin("null");

    web.when_posted("/login", &[("password", PASSWORD)]).await;

    web.then_the_status_is(403);
}

#[tokio::test]
async fn should_refuse_other_host_names() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_the_page_is_asked_for_the_host("/login", "rebound.example").await;

    web.then_the_raw_answer_starts_with("HTTP/1.1 421");
}

#[tokio::test]
async fn should_answer_under_a_name_of_the_settings() {
    let mut web = Web::new();
    web.given_the_web_interface_runs_with_the_host_names(&["nas.local"]).await;

    web.when_the_page_is_asked_for_the_host("/login", "nas.local").await;

    web.then_the_raw_answer_starts_with("HTTP/1.1 200");
}

#[tokio::test]
async fn should_refuse_other_host_names_beside_the_names_of_the_settings() {
    let mut web = Web::new();
    web.given_the_web_interface_runs_with_the_host_names(&["nas.local"]).await;

    web.when_the_page_is_asked_for_the_host("/login", "rebound.example").await;

    web.then_the_raw_answer_starts_with("HTTP/1.1 421");
}

// Traefik and Caddy pass the browser's Host on; the browser came over HTTPS.
#[tokio::test]
async fn should_take_a_login_through_a_reverse_proxy_that_passes_the_host_on() {
    let mut web = Web::new();
    web.given_the_web_interface_runs_with_the_host_names(&["njmule.example.com"]).await;

    web.when_logged_in_through("njmule.example.com", "https://njmule.example.com").await;

    web.then_the_raw_answer_starts_with("HTTP/1.1 303");
    web.then_the_raw_answer_sets_a_cookie("Secure");
}

// nginx sends its own address of the upstream as Host unless told otherwise.
#[tokio::test]
async fn should_take_a_login_through_a_reverse_proxy_that_sends_its_own_address() {
    let mut web = Web::new();
    web.given_the_web_interface_runs_with_the_host_names(&["njmule.example.com"]).await;

    web.when_logged_in_through(&format!("127.0.0.1:{}", web.port()), "https://njmule.example.com").await;

    web.then_the_raw_answer_starts_with("HTTP/1.1 303");
}

#[tokio::test]
async fn should_refuse_a_login_through_a_reverse_proxy_whose_name_is_not_in_the_settings() {
    let mut web = Web::new();
    web.given_the_web_interface_runs().await;

    web.when_logged_in_through(&format!("127.0.0.1:{}", web.port()), "https://njmule.example.com").await;

    web.then_the_raw_answer_starts_with("HTTP/1.1 403");
}

#[tokio::test]
async fn should_keep_the_cookie_of_a_plain_http_login_for_plain_http() {
    let mut web = Web::new();
    web.given_the_web_interface_runs_with_the_host_names(&["nas.local"]).await;

    let port = web.port();
    web.when_logged_in_through(&format!("nas.local:{port}"), &format!("http://nas.local:{port}")).await;

    web.then_the_raw_answer_starts_with("HTTP/1.1 303");
    web.then_the_raw_answer_sets_a_cookie_without("Secure");
}

/// The interface on a free port of this computer, the browser's cookie and form token, and the last answer.
struct Web {
    dir: tempfile::TempDir,
    engine: FakeEngine,
    ui: Option<WebUi>,
    cookie: Option<String>,
    form_token: Option<String>,
    origin: Option<String>,
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
            form_token: None,
            origin: None,
            answer: Answer::default(),
        }
    }

    fn port(&self) -> u16 {
        self.ui.as_ref().unwrap().port()
    }

    async fn given_the_web_interface_runs(&mut self) {
        self.given_the_web_interface_runs_with_the_host_names(&[]).await;
    }

    async fn given_the_web_interface_runs_with_the_host_names(&mut self, names: &[&str]) {
        let mut settings = web_client::settings(PASSWORD);
        settings.hosts = names.iter().map(|name| (*name).to_owned()).collect();
        self.start(settings).await;
    }

    async fn given_the_web_interface_runs_behind_the_proxies(&mut self, proxies: &[&str]) {
        let mut settings = web_client::settings(PASSWORD);
        settings.proxies = proxies.iter().map(|proxy| (*proxy).to_owned()).collect();
        self.start(settings).await;
    }

    async fn start(&mut self, settings: njmule_engine::remote::web_ui_settings::WebUiSettings) {
        let app = web_client::web_app(&self.dir.path().join("app"));
        self.ui = Some(WebUi::start_with(self.engine.clone(), settings, None, 2, app).await.unwrap());
    }

    async fn given_a_logged_in_user(&mut self) {
        self.given_the_web_interface_runs().await;
        let login = self.post("/login", &[("password", PASSWORD)], &[]).await;
        assert_eq!(login.status, 303);
        let set = login.header("Set-Cookie").unwrap();
        assert!(set.contains("HttpOnly") && set.contains("SameSite=Strict"), "{set}");
        self.cookie = Some(login.cookie());
    }

    // Sent as a proxy forwards them, naming its client.
    async fn given_wrong_passwords_sent_for(&mut self, client: &str, count: u32) {
        for i in 0..count {
            let guess = format!("guess {i}");
            let wrong = self.post("/login", &[("password", &guess)], &[("X-Forwarded-For", client)]).await;
            assert_eq!(wrong.status, 401);
        }
    }

    async fn given_wrong_passwords_sent(&mut self, count: u32) {
        for i in 0..count {
            let guess = format!("guess {i}");
            let wrong = self.post("/login", &[("password", &guess)], &[]).await;
            assert_eq!(wrong.status, 401);
            assert!(wrong.text().contains("Wrong password"));
        }
    }

    async fn given_the_form_token_of_the_page(&mut self, page: &str) {
        let page = self.get(page).await;
        self.form_token = Some(web_client::page_token(&page.text()));
    }

    fn given_the_form_token(&mut self, token: &str) {
        self.form_token = Some(token.to_owned());
    }

    fn given_the_origin(&mut self, value: &str) {
        self.origin = Some(value.to_owned());
    }

    fn given_the_origin_of_the_pages(&mut self) {
        self.origin = Some(format!("http://127.0.0.1:{}", self.port()));
    }

    async fn when_the_page_is_asked(&mut self, path: &str) {
        self.answer = self.get(path).await;
    }

    async fn when_the_page_is_asked_with_the_method(&mut self, path: &str, method: &str) {
        let cookie = self.cookie.clone();
        let headers: Vec<(&str, &str)> = cookie.iter().map(|cookie| ("Cookie", cookie.as_str())).collect();
        self.answer =
            web_client::exchange(self.port(), &web_client::request(self.port(), method, path, &headers, "")).await;
    }

    async fn when_posted(&mut self, path: &str, fields: &[(&str, &str)]) {
        let mut all = fields.to_vec();
        let token = self.form_token.clone();
        if let Some(token) = &token {
            all.push(("csrf", token));
        }
        let origin = self.origin.clone();
        let headers: Vec<(&str, &str)> = origin.iter().map(|origin| ("Origin", origin.as_str())).collect();
        self.answer = self.post(path, &all, &headers).await;
    }

    async fn when_logged_in_for(&mut self, client: &str) {
        self.answer = self.post("/login", &[("password", PASSWORD)], &[("X-Forwarded-For", client)]).await;
    }

    async fn when_the_page_is_asked_for_the_host(&mut self, path: &str, host: &str) {
        let host = format!("{host}:{}", self.port());
        self.answer =
            web_client::exchange(self.port(), &web_client::request(self.port(), "GET", path, &[("Host", &host)], ""))
                .await;
    }

    // The request a reverse proxy forwards: its Host, the browser's Origin.
    async fn when_logged_in_through(&mut self, host: &str, origin: &str) {
        let body = web_client::form(&[("password", PASSWORD)]);
        let headers = [("Host", host), ("Origin", origin), ("Content-Type", "application/x-www-form-urlencoded")];
        self.answer =
            web_client::exchange(self.port(), &web_client::request(self.port(), "POST", "/login", &headers, &body))
                .await;
    }

    fn then_the_status_is(&self, status: u16) {
        assert_eq!(self.answer.status, status, "{}", self.answer.raw);
    }

    fn then_the_header_is(&self, name: &str, value: &str) {
        assert_eq!(self.answer.header(name), Some(value), "{}", self.answer.raw);
    }

    fn then_the_answer_forbids_frames_and_foreign_content(&self) {
        assert_eq!(self.answer.header("X-Frame-Options"), Some("DENY"));
        let policy = self.answer.header("Content-Security-Policy").unwrap();
        assert!(policy.contains("default-src 'none'") && policy.contains("frame-ancestors 'none'"), "{policy}");
    }

    fn then_the_body_contains(&self, parts: &[&str]) {
        let body = self.answer.text();
        for part in parts {
            assert!(body.contains(part), "{part} in {body}");
        }
    }

    fn then_the_body_does_not_contain(&self, part: &str) {
        assert!(!self.answer.text().contains(part), "{}", self.answer.text());
    }

    fn then_no_cookie_is_set(&self) {
        assert_eq!(self.answer.header("Set-Cookie"), None);
    }

    fn then_the_cookie_expires(&self) {
        let set = self.answer.header("Set-Cookie").unwrap();
        assert!(set.contains("Expires=Thu, 01 Jan 1970 00:00:00 GMT"), "{set}");
    }

    async fn then_the_session_is_over(&mut self) {
        assert_eq!(self.get("/").await.status, 303);
    }

    async fn then_the_session_goes_on(&mut self) {
        assert_eq!(self.get("/").await.status, 200);
    }

    fn then_the_raw_answer_starts_with(&self, start: &str) {
        assert!(self.answer.raw.starts_with(start), "{}", self.answer.raw);
    }

    fn then_the_raw_answer_sets_a_cookie(&self, attribute: &str) {
        let set = self.answer.header("Set-Cookie").unwrap();
        for part in ["HttpOnly", "SameSite=Strict", attribute] {
            assert!(set.contains(part), "{part} in {set}");
        }
    }

    fn then_the_raw_answer_sets_a_cookie_without(&self, attribute: &str) {
        let set = self.answer.header("Set-Cookie").unwrap();
        assert!(set.contains("HttpOnly") && !set.contains(attribute), "{set}");
    }

    async fn get(&self, path: &str) -> Answer {
        let cookie = self.cookie.clone();
        let headers: Vec<(&str, &str)> = cookie.iter().map(|cookie| ("Cookie", cookie.as_str())).collect();
        web_client::exchange(self.port(), &web_client::request(self.port(), "GET", path, &headers, "")).await
    }

    async fn post(&self, path: &str, fields: &[(&str, &str)], extra: &[(&str, &str)]) -> Answer {
        let cookie = self.cookie.clone();
        let mut headers: Vec<(&str, &str)> = vec![("Content-Type", "application/x-www-form-urlencoded")];
        headers.extend(cookie.iter().map(|cookie| ("Cookie", cookie.as_str())));
        headers.extend_from_slice(extra);
        let body = web_client::form(fields);
        web_client::exchange(self.port(), &web_client::request(self.port(), "POST", path, &headers, &body)).await
    }
}
