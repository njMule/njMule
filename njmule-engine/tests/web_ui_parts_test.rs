// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's WebUiPartsTest, under its names: password hashes, sessions, settings and page building of the web
//! interface.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects, clippy::panic)]

use std::fs;
use std::net::IpAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use njmule_engine::remote::sessions::{self, IDLE, LOCKOUT, MAX_ADDRESSES, MAX_FAILURES, Session, Sessions};
use njmule_engine::remote::web_ui_settings::{self, FILE_NAME, WebUiSettings};
use njmule_engine::remote::{html, passwords, web_ui};

const STORED: &str = "pbkdf2-sha512$1$AA$AA";

#[test]
fn should_hash_a_password_with_pbkdf2_and_the_default_rounds() {
    let mut test = Parts::new();

    test.when_the_password_is_hashed("secret");

    test.then_the_hash_starts_with(&format!("pbkdf2-sha512${}$", passwords::ROUNDS));
}

#[test]
fn should_match_the_hashed_password() {
    let mut test = Parts::new();
    test.given_a_stored_hash_of("secret");

    test.when_the_password_is_checked("secret");

    test.then_the_password_matches(true);
}

#[test]
fn should_not_match_the_password_written_with_other_cases() {
    let mut test = Parts::new();
    test.given_a_stored_hash_of("secret");

    test.when_the_password_is_checked("Secret");

    test.then_the_password_matches(false);
}

#[test]
fn should_salt_every_hash() {
    let mut test = Parts::new();
    test.given_a_stored_hash_of("secret");

    test.when_the_password_is_hashed("secret");

    test.then_the_hash_differs_from_the_stored_one();
}

#[test]
fn should_match_a_hash_of_the_given_salt_and_rounds() {
    let mut test = Parts::new();
    test.given_the_stored_hash(&passwords::hash_with("x", &[0; 16], 10));

    test.when_the_password_is_checked("x");

    test.then_the_password_matches(true);
}

#[test]
fn should_not_match_a_stored_hash_it_cannot_read() {
    for unreadable in ["", "md5$1$a$b", "pbkdf2-sha512$0$AA$AA", "pbkdf2-sha512$ten$AA$AA", "pbkdf2-sha512$10$%%$AA"] {
        let mut test = Parts::new();
        test.given_the_stored_hash(unreadable);

        test.when_the_password_is_checked("x");

        test.then_the_password_matches(false);
    }
}

#[test]
fn should_not_match_a_stored_hash_of_too_many_rounds_to_check() {
    let mut test = Parts::new();
    test.given_the_stored_hash("pbkdf2-sha512$10000001$AA$AA");

    test.when_the_password_is_checked("x");

    test.then_the_password_matches(false);
}

#[test]
fn should_open_a_session_found_by_its_id_with_a_form_token_of_its_own() {
    let mut test = Parts::new();

    test.when_a_session_is_opened();

    test.then_the_session_id_has_the_length_and_differs_from_the_form_token(22);
    test.then_the_session_is_open();
    test.then_no_session_is_found_for("unknown");
}

#[test]
fn should_take_a_form_with_the_token_of_the_session() {
    let mut test = Parts::new();
    test.given_a_session_open();

    test.when_a_form_comes_with_the_token_of_the_session_followed_by("");

    test.then_the_form_is_valid(true);
}

#[test]
fn should_refuse_a_form_with_another_token() {
    let mut test = Parts::new();
    test.given_a_session_open();

    test.when_a_form_comes_with_the_token_of_the_session_followed_by("x");

    test.then_the_form_is_valid(false);
}

#[test]
fn should_refuse_a_form_without_token() {
    let mut test = Parts::new();
    test.given_a_session_open();

    test.when_a_form_comes_without_token();

    test.then_the_form_is_valid(false);
}

#[test]
fn should_keep_a_session_within_the_idle_time() {
    let mut test = Parts::new();
    test.given_a_session_open();

    test.when_time_passes(IDLE - Duration::from_nanos(1));

    test.then_the_session_is_open();
}

#[test]
fn should_end_a_session_left_idle() {
    let mut test = Parts::new();
    test.given_a_session_open();
    test.given_time_passed(IDLE - Duration::from_nanos(1));
    test.given_the_session_is_still_open();

    test.when_time_passes(IDLE);

    test.then_the_session_is_gone();
    test.then_the_open_sessions_are(0);
}

#[test]
fn should_end_a_session_that_is_closed() {
    let mut test = Parts::new();
    test.given_a_session_open();

    test.when_the_session_is_closed();

    test.then_the_session_is_gone();
}

#[test]
fn should_count_the_attempts_before_their_password_is_checked() {
    let mut test = Parts::new();
    test.given_attempts_from("10.0.0.1", MAX_FAILURES);

    test.when_an_attempt_comes_from("10.0.0.1");

    // Attempts count before their password is checked: parallel ones too.
    test.then_the_attempt_is_allowed(false);
}

#[test]
fn should_let_another_address_try_while_one_is_locked_out() {
    let mut test = Parts::new();
    test.given_attempts_from("10.0.0.1", MAX_FAILURES);

    test.when_an_attempt_comes_from("10.0.0.2");

    test.then_the_attempt_is_allowed(true);
}

#[test]
fn should_let_an_address_try_again_once_the_lockout_is_over() {
    let mut test = Parts::new();
    test.given_attempts_from("10.0.0.1", MAX_FAILURES);
    test.given_time_passed(LOCKOUT);

    test.when_an_attempt_comes_from("10.0.0.1");

    test.then_the_attempt_is_allowed(true);
}

#[test]
fn should_forget_the_attempts_of_an_address_that_logs_in() {
    let test = Parts::new();
    test.given_attempts_from("10.0.0.2", MAX_FAILURES);

    test.when_the_login_succeeds_from("10.0.0.2");

    test.then_the_address_may_try_again("10.0.0.2", MAX_FAILURES);
}

#[test]
fn should_keep_the_attempts_of_a_bounded_number_of_addresses() {
    let test = Parts::new();

    test.when_attempts_come_from_addresses(MAX_ADDRESSES + 10);

    test.then_the_addresses_counted_are(MAX_ADDRESSES);
}

#[test]
fn should_forget_the_oldest_address_first() {
    let mut test = Parts::new();
    test.given_attempts_from_addresses(MAX_ADDRESSES + 10);

    test.when_an_attempt_comes_from("10.0.0.0");

    // The oldest went first.
    test.then_the_attempt_is_allowed(true);
}

#[test]
fn should_forget_the_addresses_once_the_lockout_is_over() {
    let mut test = Parts::new();
    test.given_attempts_from_addresses(MAX_ADDRESSES + 10);
    test.given_time_passed(LOCKOUT);

    test.when_an_attempt_comes_from("10.1.0.1");

    test.then_the_addresses_counted_are(1);
}

#[test]
fn should_send_the_apps_files_compressed_to_clients_taking_gzip() {
    for accept_encoding in ["gzip", "gzip, deflate, br, zstd", "br;q=1.0, gzip;q=0.5", "*", "GZIP"] {
        let mut test = Parts::new();

        test.when_the_clients_encodings_are_read(Some(accept_encoding));

        test.then_the_client_takes_gzip(true);
    }
}

#[test]
fn should_send_the_apps_files_unpacked_to_other_clients() {
    for accept_encoding in
        [None, Some(""), Some("identity"), Some("br, zstd"), Some("gzip;q=0"), Some("deflate, gzip;q=0.0")]
    {
        let mut test = Parts::new();

        test.when_the_clients_encodings_are_read(accept_encoding);

        test.then_the_client_takes_gzip(false);
    }
}

#[test]
fn should_let_the_loopback_hosts_in_while_the_lan_is_off() {
    for host in ["localhost:4711", "127.0.0.1:4711", "[::1]:4711"] {
        let mut test = Parts::new();
        test.given_the_lan_allowed(false);

        test.when_the_host_is_checked(Some(host));

        test.then_the_host_is_trusted(true);
    }
}

#[test]
fn should_keep_the_other_hosts_out_while_the_lan_is_off() {
    for host in [None, Some("192.168.1.10:4711")] {
        let mut test = Parts::new();
        test.given_the_lan_allowed(false);

        test.when_the_host_is_checked(host);

        test.then_the_host_is_trusted(false);
    }
}

#[test]
fn should_let_addresses_and_loopback_names_in_while_the_lan_is_allowed() {
    for host in ["192.168.1.10:4711", "192.168.1.10", "[fe80::1]:4711", "LOCALHOST:4711"] {
        let mut test = Parts::new();
        test.given_the_lan_allowed(true);

        test.when_the_host_is_checked(Some(host));

        test.then_the_host_is_trusted(true);
    }
}

// A name of the attacker's that resolves to this computer: DNS rebinding.
#[test]
fn should_keep_other_names_out_while_the_lan_is_allowed() {
    for host in [None, Some("rebound.example:4711"), Some("192.168.1.10.nip.io:4711"), Some("[rebound.example]:4711")] {
        let mut test = Parts::new();
        test.given_the_lan_allowed(true);

        test.when_the_host_is_checked(host);

        test.then_the_host_is_trusted(false);
    }
}

#[test]
fn should_keep_out_a_host_with_an_unclosed_bracket() {
    let mut test = Parts::new();
    test.given_the_lan_allowed(true);

    test.when_the_host_is_checked(Some("[fe80::1"));

    test.then_the_host_is_trusted(false);
}

// Synology's DNS gives such a name the address it spells only.
#[test]
fn should_let_in_a_quick_connect_name_of_the_address_reached_while_the_lan_is_allowed() {
    for host in ["192-168-1-80.mynas.direct.quickconnect.to:4711", "192-168-1-80.MyNas.Direct.QuickConnect.to"] {
        let mut test = Parts::new();
        test.given_the_lan_allowed(true);
        test.given_the_request_reached("192.168.1.80");

        test.when_the_host_is_checked(Some(host));

        test.then_the_host_is_trusted(true);
    }
}

#[test]
fn should_keep_out_a_quick_connect_name_of_another_address() {
    for host in [
        "192-168-1-81.mynas.direct.quickconnect.to",
        "192-168-1-800.mynas.direct.quickconnect.to",
        "192-168-1-80.direct.quickconnect.to",
        "192-168-1-80.a.b.direct.quickconnect.to",
        "192-168-1-80.mynas.direct.quickconnect.to.rebound.example",
        "192-168-1-80.mynas.direct.quickconnect.example",
    ] {
        let mut test = Parts::new();
        test.given_the_lan_allowed(true);
        test.given_the_request_reached("192.168.1.80");

        test.when_the_host_is_checked(Some(host));

        test.then_the_host_is_trusted(false);
    }
}

#[test]
fn should_keep_out_a_quick_connect_name_while_the_lan_is_off() {
    let mut test = Parts::new();
    test.given_the_lan_allowed(false);
    test.given_the_request_reached("192.168.1.80");

    test.when_the_host_is_checked(Some("192-168-1-80.mynas.direct.quickconnect.to"));

    test.then_the_host_is_trusted(false);
}

#[test]
fn should_let_the_names_of_the_settings_in_on_any_port_while_the_lan_is_off() {
    for host in ["nas.local", "NAS.Local:4711", "njmule.example.com", "njmule.example.com:443"] {
        let mut test = Parts::new();
        test.given_the_lan_allowed(false);
        test.given_the_trusted_names(&["nas.local", "njmule.example.com"]);

        test.when_the_host_is_checked(Some(host));

        test.then_the_host_is_trusted(true);
    }
}

// The rebinding name may contain or extend a trusted one.
#[test]
fn should_keep_other_names_out_beside_the_names_of_the_settings() {
    for host in ["nas.local.rebound.example:4711", "rebound.example", "nas.locals:4711", "local:4711"] {
        let mut test = Parts::new();
        test.given_the_lan_allowed(true);
        test.given_the_trusted_names(&["nas.local"]);

        test.when_the_host_is_checked(Some(host));

        test.then_the_host_is_trusted(false);
    }
}

#[test]
fn should_take_a_form_without_origin() {
    let mut test = Parts::new();

    test.when_the_origin_is_checked(None, "127.0.0.1:4711");

    test.then_the_origin_is_trusted(true);
}

#[test]
fn should_take_the_origin_of_the_host_itself() {
    let mut test = Parts::new();

    test.when_the_origin_is_checked(Some("http://127.0.0.1:4711"), "127.0.0.1:4711");

    test.then_the_origin_is_trusted(true);
}

// A reverse proxy may show the pages on another scheme and port, and pass its own address as Host.
#[test]
fn should_take_an_origin_of_a_name_of_the_settings_on_any_port_and_web_scheme() {
    for origin in ["https://njmule.example.com", "https://NJMULE.example.com:8443", "http://nas.local:4711"] {
        let mut test = Parts::new();
        test.given_the_trusted_names(&["nas.local", "njmule.example.com"]);

        test.when_the_origin_is_checked(Some(origin), "127.0.0.1:4711");

        test.then_the_origin_is_trusted(true);
    }
}

#[test]
fn should_refuse_an_origin_of_another_name_or_scheme() {
    for origin in [
        "https://evil.example",
        "https://njmule.example.com.evil.example",
        "ftp://njmule.example.com",
        "https://user@njmule.example.com",
        "null",
        "https://bad host",
    ] {
        let mut test = Parts::new();
        test.given_the_trusted_names(&["njmule.example.com"]);

        test.when_the_origin_is_checked(Some(origin), "127.0.0.1:4711");

        test.then_the_origin_is_trusted(false);
    }
}

#[test]
fn should_refuse_the_origin_of_a_proxy_whose_name_is_not_in_the_settings() {
    let mut test = Parts::new();

    test.when_the_origin_is_checked(Some("https://njmule.example.com"), "127.0.0.1:4711");

    test.then_the_origin_is_trusted(false);
}

#[test]
fn should_read_host_names_in_lower_case_with_ipv6_addresses_in_brackets() {
    let mut test = Parts::new();

    test.when_the_host_names_are_read(" NAS.local, fe80::1 [::2],,nas.local 192.168.1.10 ");

    test.then_the_host_names_are(&["nas.local", "[fe80::1]", "[::2]", "192.168.1.10"]);
}

#[test]
fn should_refuse_a_host_name_with_a_port_a_scheme_or_other_than_ascii() {
    for name in ["nas.local:4711", "https://njmule.example.com", "njmule.example.com/", "[nas.local]", "café.local"] {
        let mut test = Parts::new();

        test.when_the_host_names_are_read(name);

        test.then_they_are_refused();
    }
}

#[test]
fn should_load_the_host_names_saved() {
    let mut test = Parts::new();
    test.given_the_settings_saved(settings(true, 4711, true, STORED).with_hosts(&["nas.local", "[fe80::1]"]));

    test.when_the_settings_are_loaded();

    test.then_the_settings_are_the_chosen_ones();
}

#[test]
fn should_load_the_trusted_proxies_saved() {
    let mut test = Parts::new();
    let proxies = ["172.17.0.0/16", "::1", "10.0.0.1-10.0.0.9"];
    test.given_the_settings_saved(settings(true, 4711, true, STORED).with_proxies(&proxies));

    test.when_the_settings_are_loaded();

    test.then_the_settings_are_the_chosen_ones();
}

#[test]
fn should_load_the_defaults_from_a_file_trusting_a_proxy_by_its_name() {
    let mut test = Parts::new();
    test.given_the_settings_file_holds(&format!("enabled=true\npassword={STORED}\nproxies=proxy.example\n"));

    test.when_the_settings_are_loaded();

    test.then_the_settings_are(&WebUiSettings::default());
}

#[test]
fn should_load_no_host_names_from_a_file_written_before_them() {
    let mut test = Parts::new();
    test.given_the_settings_file_holds(&format!("enabled=true\nport=4711\npassword={STORED}\n"));

    test.when_the_settings_are_loaded();

    test.then_the_settings_are_usable(true);
    test.then_the_loaded_host_names_are(&[]);
}

#[test]
fn should_load_the_defaults_from_a_file_of_a_host_name_with_a_port() {
    let mut test = Parts::new();
    test.given_the_settings_file_holds(&format!("enabled=true\npassword={STORED}\nhosts=nas.local:4711\n"));

    test.when_the_settings_are_loaded();

    test.then_the_settings_are(&WebUiSettings::default());
}

#[test]
fn should_load_the_defaults_when_nothing_was_saved() {
    let mut test = Parts::new();

    test.when_the_settings_are_loaded();

    test.then_the_settings_are(&WebUiSettings::default());
    test.then_the_settings_are_usable(false);
}

#[test]
fn should_load_the_settings_saved() {
    let mut test = Parts::new();
    test.given_the_settings_saved(settings(true, 8080, true, STORED));

    test.when_the_settings_are_loaded();

    test.then_the_settings_are_the_chosen_ones();
    test.then_the_settings_are_usable(true);
}

#[test]
fn should_hide_the_password_hash_in_the_text_of_the_settings() {
    let mut test = Parts::new();
    test.given_the_settings(settings(true, 8080, true, STORED));

    test.when_the_settings_are_shown_as_text();

    test.then_the_text_does_not_contain("pbkdf2");
}

#[test]
fn should_not_use_settings_without_a_password() {
    let mut test = Parts::new();

    test.when_the_settings_are_made(true, 80, false, "");

    test.then_the_settings_are_usable(false);
}

#[test]
fn should_refuse_settings_of_port_zero() {
    let mut test = Parts::new();

    test.when_the_settings_are_made(true, 0, false, "");

    test.then_they_are_refused();
}

#[test]
fn should_load_the_defaults_from_a_file_of_a_port_out_of_range() {
    let mut test = Parts::new();
    test.given_the_settings_file_holds("port=99999\n");

    test.when_the_settings_are_loaded();

    test.then_the_settings_are(&WebUiSettings::default());
}

#[test]
fn should_let_only_the_owner_read_the_password_hash() {
    let test = Parts::new();
    test.given_the_settings_file_holds("enabled=false\n");

    test.when_the_settings_are_saved(settings(true, 8080, false, STORED));

    test.then_only_the_owner_may_read_the_settings_file();
}

#[test]
fn should_escape_text() {
    let mut test = Parts::new();

    test.when_the_text_is_escaped(r#"<a href='x'>"&"</a>"#);

    test.then_the_text_is("&lt;a href=&#39;x&#39;&gt;&quot;&amp;&quot;&lt;/a&gt;");
}

#[test]
fn should_leave_text_with_nothing_to_escape_as_it_is() {
    let mut test = Parts::new();

    test.when_the_text_is_escaped("Ubuntu 24.04 – 東京.iso");

    test.then_the_text_is("Ubuntu 24.04 – 東京.iso");
}

#[test]
fn should_make_a_page_without_navigation_outside_a_session() {
    let mut test = Parts::new();

    test.when_a_page_outside_a_session_is_made("T<", "body");

    test.then_the_text_contains("<h1>T&lt;</h1>");
    test.then_the_text_does_not_contain("<nav");
}

trait Choosing {
    fn with_hosts(self, hosts: &[&str]) -> Self;
    fn with_proxies(self, proxies: &[&str]) -> Self;
}

impl Choosing for WebUiSettings {
    fn with_hosts(self, hosts: &[&str]) -> Self {
        Self { hosts: hosts.iter().map(|host| (*host).to_owned()).collect(), ..self }
    }

    fn with_proxies(self, proxies: &[&str]) -> Self {
        Self { proxies: proxies.iter().map(|proxy| (*proxy).to_owned()).collect(), ..self }
    }
}

fn settings(enabled: bool, port: u16, lan: bool, password_hash: &str) -> WebUiSettings {
    WebUiSettings { enabled, port, lan, password_hash: password_hash.to_owned(), ..WebUiSettings::default() }
}

struct Parts {
    dir: tempfile::TempDir,
    now: Arc<AtomicU64>,
    sessions: Sessions,
    stored: String,
    hash: String,
    matches: Option<bool>,
    session: Option<Session>,
    valid_form: Option<bool>,
    allowed: Option<bool>,
    lan_allowed: bool,
    trusted_names: Vec<String>,
    reached: IpAddr,
    trusted: Option<bool>,
    gzip: Option<bool>,
    host_names: Option<Result<Vec<String>, String>>,
    chosen: Option<WebUiSettings>,
    settings: Option<Result<WebUiSettings, String>>,
    text: String,
}

impl Parts {
    fn new() -> Self {
        let now = Arc::new(AtomicU64::new(0));
        let clock = Arc::clone(&now);
        Self {
            dir: tempfile::tempdir().unwrap(),
            sessions: Sessions::new(move || Duration::from_nanos(clock.load(Ordering::SeqCst))),
            now,
            stored: String::new(),
            hash: String::new(),
            matches: None,
            session: None,
            valid_form: None,
            allowed: None,
            lan_allowed: false,
            trusted_names: Vec::new(),
            reached: "192.168.1.80".parse().unwrap(),
            trusted: None,
            gzip: None,
            host_names: None,
            chosen: None,
            settings: None,
            text: String::new(),
        }
    }

    fn given_a_stored_hash_of(&mut self, plain: &str) {
        self.stored = passwords::hash(plain);
    }

    fn given_the_stored_hash(&mut self, stored: &str) {
        stored.clone_into(&mut self.stored);
    }

    fn given_a_session_open(&mut self) {
        self.when_a_session_is_opened();
    }

    fn given_time_passed(&self, time: Duration) {
        self.when_time_passes(time);
    }

    fn given_the_session_is_still_open(&self) {
        self.then_the_session_is_open();
    }

    fn given_attempts_from(&self, address: &str, count: u32) {
        self.then_the_address_may_try_again(address, count);
    }

    fn given_attempts_from_addresses(&self, count: usize) {
        self.when_attempts_come_from_addresses(count);
    }

    fn given_the_lan_allowed(&mut self, allowed: bool) {
        self.lan_allowed = allowed;
    }

    fn given_the_trusted_names(&mut self, names: &[&str]) {
        self.trusted_names = names.iter().map(|name| (*name).to_owned()).collect();
    }

    fn given_the_request_reached(&mut self, address: &str) {
        self.reached = address.parse().unwrap();
    }

    fn given_the_settings_saved(&mut self, chosen: WebUiSettings) {
        chosen.save(self.dir.path()).unwrap();
        self.chosen = Some(chosen);
    }

    fn given_the_settings(&mut self, chosen: WebUiSettings) {
        self.chosen = Some(chosen);
    }

    fn given_the_settings_file_holds(&self, content: &str) {
        fs::write(self.dir.path().join(FILE_NAME), content).unwrap();
    }

    fn when_the_password_is_hashed(&mut self, plain: &str) {
        self.hash = passwords::hash(plain);
    }

    fn when_the_password_is_checked(&mut self, plain: &str) {
        self.matches = Some(passwords::matches(plain, &self.stored));
    }

    fn when_a_session_is_opened(&mut self) {
        self.session = Some(self.sessions.open(false));
    }

    fn when_a_form_comes_with_the_token_of_the_session_followed_by(&mut self, more: &str) {
        let session = self.session.as_ref().unwrap();
        let given = format!("{}{more}", session.csrf);
        self.valid_form = Some(sessions::valid_form(session, Some(&given)));
    }

    fn when_a_form_comes_without_token(&mut self) {
        self.valid_form = Some(sessions::valid_form(self.session.as_ref().unwrap(), None));
    }

    fn when_time_passes(&self, time: Duration) {
        self.now.fetch_add(u64::try_from(time.as_nanos()).unwrap(), Ordering::SeqCst);
    }

    fn when_the_session_is_closed(&self) {
        self.sessions.close(&self.session.as_ref().unwrap().id);
    }

    fn when_an_attempt_comes_from(&mut self, address: &str) {
        self.allowed = Some(self.sessions.may_try(address));
    }

    fn when_the_login_succeeds_from(&self, address: &str) {
        self.sessions.succeeded(address);
    }

    fn when_attempts_come_from_addresses(&self, count: usize) {
        for i in 0..count {
            self.sessions.may_try(&format!("10.0.{}.{}", i >> 8, i & 0xFF));
        }
    }

    fn when_the_clients_encodings_are_read(&mut self, accept_encoding: Option<&str>) {
        self.gzip = Some(web_ui::takes_gzip(accept_encoding));
    }

    fn when_the_host_is_checked(&mut self, host: Option<&str>) {
        self.trusted = Some(web_ui::trusted_host(host, self.lan_allowed, &self.trusted_names, self.reached));
    }

    fn when_the_origin_is_checked(&mut self, origin: Option<&str>, host: &str) {
        self.trusted = Some(web_ui::trusted_origin(origin, Some(host), &self.trusted_names));
    }

    fn when_the_host_names_are_read(&mut self, text: &str) {
        self.host_names = Some(web_ui_settings::host_names(text));
    }

    fn when_the_settings_are_loaded(&mut self) {
        self.settings = Some(Ok(WebUiSettings::load(self.dir.path())));
    }

    fn when_the_settings_are_shown_as_text(&mut self) {
        self.text = format!("{:?}", self.chosen.as_ref().unwrap());
    }

    fn when_the_settings_are_made(&mut self, enabled: bool, port: u16, lan: bool, password_hash: &str) {
        self.settings = Some(settings(enabled, port, lan, password_hash).validated());
    }

    fn when_the_settings_are_saved(&self, chosen: WebUiSettings) {
        chosen.save(self.dir.path()).unwrap();
    }

    fn when_the_text_is_escaped(&mut self, text: &str) {
        self.text = html::text(text);
    }

    fn when_a_page_outside_a_session_is_made(&mut self, title: &str, content: &str) {
        self.text = html::bare(title, content);
    }

    fn then_the_hash_starts_with(&self, prefix: &str) {
        assert!(self.hash.starts_with(prefix), "{}", self.hash);
    }

    fn then_the_hash_differs_from_the_stored_one(&self) {
        assert_ne!(self.hash, self.stored);
    }

    fn then_the_password_matches(&self, expected: bool) {
        assert_eq!(self.matches, Some(expected), "{}", self.stored);
    }

    fn then_the_session_id_has_the_length_and_differs_from_the_form_token(&self, length: usize) {
        let session = self.session.as_ref().unwrap();
        assert_eq!(session.id.len(), length);
        assert_ne!(session.id, session.csrf);
    }

    fn then_the_session_is_open(&self) {
        let session = self.session.as_ref().unwrap();
        assert_eq!(self.sessions.find(&session.id).as_ref(), Some(session));
    }

    fn then_the_session_is_gone(&self) {
        assert_eq!(self.sessions.find(&self.session.as_ref().unwrap().id), None);
    }

    fn then_no_session_is_found_for(&self, id: &str) {
        assert_eq!(self.sessions.find(id), None);
    }

    fn then_the_open_sessions_are(&self, count: usize) {
        assert_eq!(self.sessions.count(), count);
    }

    fn then_the_form_is_valid(&self, expected: bool) {
        assert_eq!(self.valid_form, Some(expected));
    }

    fn then_the_attempt_is_allowed(&self, expected: bool) {
        assert_eq!(self.allowed, Some(expected));
    }

    fn then_the_address_may_try_again(&self, address: &str, times: u32) {
        for _ in 0..times {
            assert!(self.sessions.may_try(address));
        }
    }

    fn then_the_addresses_counted_are(&self, count: usize) {
        assert_eq!(self.sessions.addresses(), count);
    }

    fn then_the_client_takes_gzip(&self, expected: bool) {
        assert_eq!(self.gzip, Some(expected));
    }

    fn then_the_host_is_trusted(&self, expected: bool) {
        assert_eq!(self.trusted, Some(expected));
    }

    fn then_the_origin_is_trusted(&self, expected: bool) {
        assert_eq!(self.trusted, Some(expected));
    }

    fn then_the_host_names_are(&self, names: &[&str]) {
        assert_eq!(self.host_names.as_ref().unwrap().as_ref().unwrap(), names);
    }

    fn then_they_are_refused(&self) {
        let refused = matches!(self.host_names, Some(Err(_))) || matches!(self.settings, Some(Err(_)));
        assert!(refused, "{:?} {:?}", self.host_names, self.settings);
    }

    fn then_the_settings_are_the_chosen_ones(&self) {
        assert_eq!(self.loaded(), self.chosen.as_ref().unwrap());
    }

    fn then_the_settings_are(&self, expected: &WebUiSettings) {
        assert_eq!(self.loaded(), expected);
    }

    fn then_the_settings_are_usable(&self, expected: bool) {
        assert_eq!(self.loaded().usable(), expected);
    }

    fn then_the_loaded_host_names_are(&self, names: &[&str]) {
        assert_eq!(self.loaded().hosts, names);
    }

    #[cfg_attr(not(unix), allow(clippy::unused_self))]
    fn then_only_the_owner_may_read_the_settings_file(&self) {
        // Where the file system has POSIX permissions only.
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(self.dir.path().join(FILE_NAME)).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o600);
        }
    }

    fn then_the_text_is(&self, expected: &str) {
        assert_eq!(self.text, expected);
    }

    fn then_the_text_contains(&self, part: &str) {
        assert!(self.text.contains(part), "{}", self.text);
    }

    fn then_the_text_does_not_contain(&self, part: &str) {
        assert!(!self.text.contains(part), "{}", self.text);
    }

    fn loaded(&self) -> &WebUiSettings {
        match &self.settings {
            Some(Ok(settings)) => settings,
            other => panic!("no settings: {other:?}"),
        }
    }
}
