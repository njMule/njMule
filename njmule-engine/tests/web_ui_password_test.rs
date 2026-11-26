// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Tests for web ui password.

#![allow(clippy::unwrap_used)]

use std::fs;
use std::io::{self, Write};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use njmule_engine::app::launch_options::LaunchOptions;
use njmule_engine::app::web_ui_password::{self, PasswordError};
use njmule_engine::remote::passwords;
use njmule_engine::remote::web_ui_settings::{self, WebUiSettings};
use tempfile::TempDir;

const PASSWORD: &str = "long enough";
const GUEST_PASSWORD: &str = "only looking";
const GUEST_HASH: &str = "pbkdf2-sha512$1$BB$BB";

#[test]
fn should_read_the_first_line_of_the_password_file() {
    let cases = [
        PASSWORD.to_owned(),
        format!("{PASSWORD}\n"),
        format!("{PASSWORD}\r\n"),
        format!("{PASSWORD}\nnext line"),
        format!("\u{FEFF}{PASSWORD}"),
    ];
    for content in cases {
        let mut test = Scenario::new();
        test.given_the_password_file(&content);

        test.when_the_password_is_read();

        test.then_the_password_is(PASSWORD);
    }
}

#[test]
fn should_keep_the_spaces_of_the_password() {
    let mut test = Scenario::new();
    test.given_the_password_file("  spaced out  \n");

    test.when_the_password_is_read();

    test.then_the_password_is("  spaced out  ");
}

#[test]
fn should_refuse_a_password_file_that_does_not_exist() {
    let mut test = Scenario::new();
    test.given_a_password_file_that_does_not_exist();

    test.when_the_password_is_read();

    test.then_exception_occurred("does not exist");
}

#[test]
fn should_refuse_a_password_too_short_without_telling_it() {
    let mut test = Scenario::new();
    test.given_the_password_file("shhh!\n");

    test.when_the_password_is_read();

    test.then_exception_occurred(&format!("at least {} characters", passwords::MIN_LENGTH));
    test.then_the_message_does_not_contain("shhh!");
}

#[test]
fn should_refuse_a_password_file_too_long() {
    let mut test = Scenario::new();
    test.given_the_password_file(&"x".repeat(web_ui_password::MAX_FILE_BYTES.saturating_add(1)));

    test.when_the_password_is_read();

    test.then_exception_occurred(&format!("longer than {} bytes", web_ui_password::MAX_FILE_BYTES));
}

#[test]
fn should_refuse_a_password_file_that_is_not_utf8() {
    let mut test = Scenario::new();
    test.given_the_password_file_bytes(b"long\xFFenough");

    test.when_the_password_is_read();

    test.then_exception_occurred("not UTF-8");
}

#[test]
fn should_turn_the_web_interface_on_with_the_password_of_the_file() {
    let mut test = Scenario::new();
    test.given_the_password_file(&format!("{PASSWORD}\n"));

    test.when_the_command_line_sets_the_password_from_the_file(&["--webui-lan"]);

    test.then_the_web_interface_is_on_with_the_password(PASSWORD);
    test.then_the_web_interface_is_open_to_other_computers();
}

// What the web interface set for itself survives the next start of a container.
#[test]
fn should_keep_the_settings_the_command_line_does_not_give_at_each_start() {
    let mut test = Scenario::new();
    test.given_the_web_interface_settings(WebUiSettings {
        enabled: true,
        port: 8080,
        lan: true,
        password_hash: "pbkdf2-sha512$1$AA$AA".to_owned(),
        api: true,
        ..WebUiSettings::default()
    });
    test.given_the_password_file(PASSWORD);

    test.when_the_command_line_sets_the_password_from_the_file(&[]);

    test.then_the_web_interface_is_on_with_the_password(PASSWORD);
    test.then_the_saved_settings_are(8080, true, true);
}

#[test]
fn should_set_the_web_interface_up_anew_with_the_password_command() {
    let mut test = Scenario::new();
    test.given_the_web_interface_settings(WebUiSettings {
        enabled: true,
        port: 8080,
        lan: true,
        password_hash: "pbkdf2-sha512$1$AA$AA".to_owned(),
        api: true,
        hosts: vec!["old.example".to_owned()],
        ..WebUiSettings::default()
    });
    test.given_the_password_file(PASSWORD);

    test.when_the_password_command_sets_the_password_from_the_file(&[]);

    test.then_the_web_interface_is_on_with_the_password(PASSWORD);
    test.then_the_saved_settings_are(8080, false, true);
    test.then_the_saved_host_names_are(&[]);
}

#[test]
fn should_take_the_host_names_from_the_command_line_in_place_of_the_saved_ones() {
    let mut test = Scenario::new();
    test.given_the_web_interface_settings(WebUiSettings {
        enabled: true,
        port: 8080,
        lan: true,
        password_hash: "pbkdf2-sha512$1$AA$AA".to_owned(),
        api: false,
        hosts: vec!["old.example".to_owned()],
        ..WebUiSettings::default()
    });
    test.given_the_password_file(PASSWORD);

    test.when_the_command_line_sets_the_password_from_the_file(&[
        "--webui-host",
        "nas.local",
        "--webui-host",
        "njmule.example.com",
    ]);

    test.then_the_saved_host_names_are(&["nas.local", "njmule.example.com"]);
}

#[test]
fn should_keep_the_saved_host_names_the_command_line_does_not_give() {
    let mut test = Scenario::new();
    test.given_the_web_interface_settings(WebUiSettings {
        enabled: true,
        port: 8080,
        lan: true,
        password_hash: "pbkdf2-sha512$1$AA$AA".to_owned(),
        api: false,
        hosts: vec!["old.example".to_owned()],
        ..WebUiSettings::default()
    });
    test.given_the_password_file(PASSWORD);

    test.when_the_command_line_sets_the_password_from_the_file(&[]);

    test.then_the_saved_host_names_are(&["old.example"]);
}

#[test]
fn should_log_the_file_not_the_password() {
    let mut test = Scenario::new();
    test.given_the_password_file(PASSWORD);
    test.given_the_log_is_listened_to();

    test.when_the_command_line_sets_the_password_from_the_file(&[]);

    test.then_the_log_names_the_file_but_not_the_password();
}

#[test]
fn should_let_the_guest_of_the_file_log_in() {
    let mut test = Scenario::new();
    test.given_the_password_file(PASSWORD);
    test.given_the_guest_password_file(&format!("{GUEST_PASSWORD}\n"));
    let guest = test.guest_file.to_string_lossy().into_owned();

    test.when_the_command_line_sets_the_password_from_the_file(&["--webui-guest-password-file", &guest]);

    test.then_the_guest_logs_in_with(GUEST_PASSWORD);
}

#[test]
fn should_refuse_a_guest_password_that_is_the_web_interface_password() {
    let mut test = Scenario::new();
    test.given_the_password_file(PASSWORD);
    test.given_the_guest_password_file(PASSWORD);
    let guest = test.guest_file.to_string_lossy().into_owned();

    test.when_the_password_command_sets_the_password_from_the_file(&["--webui-guest-password-file", &guest]);

    test.then_exception_occurred("is the web interface password");
    test.then_the_settings_file_does_not_exist();
}

#[test]
fn should_let_no_guest_in_any_more() {
    let mut test = Scenario::new();
    test.given_the_web_interface_settings_with_a_guest();
    test.given_the_password_file(PASSWORD);

    test.when_the_command_line_sets_the_password_from_the_file(&["--webui-no-guest"]);

    test.then_the_saved_guest_hash_is("");
}

#[test]
fn should_keep_the_saved_guest_the_command_line_does_not_name() {
    let mut test = Scenario::new();
    test.given_the_web_interface_settings_with_a_guest();
    test.given_the_password_file(PASSWORD);

    test.when_the_command_line_sets_the_password_from_the_file(&[]);

    test.then_the_saved_guest_hash_is(GUEST_HASH);
}

#[test]
fn should_set_nothing_without_a_password_file() {
    let mut test = Scenario::new();

    test.when_the_command_line_sets_no_password_file();

    test.then_nothing_was_saved();
}

// Global fallback subscriber that keeps callsite interest at `sometimes` (without emitting anything), so parallel tests
// on other threads never cache `Interest::never()` on a callsite while this test captures it with `with_default`.
struct AlwaysCheck;

impl tracing::Subscriber for AlwaysCheck {
    fn register_callsite(&self, _metadata: &'static tracing::Metadata<'static>) -> tracing::subscriber::Interest {
        tracing::subscriber::Interest::sometimes()
    }

    fn enabled(&self, _metadata: &tracing::Metadata<'_>) -> bool {
        false
    }

    fn new_span(&self, _span: &tracing::span::Attributes<'_>) -> tracing::span::Id {
        tracing::span::Id::from_u64(1)
    }

    fn record(&self, _span: &tracing::span::Id, _values: &tracing::span::Record<'_>) {}

    fn record_follows_from(&self, _span: &tracing::span::Id, _follows: &tracing::span::Id) {}

    fn event(&self, _event: &tracing::Event<'_>) {}

    fn enter(&self, _span: &tracing::span::Id) {}

    fn exit(&self, _span: &tracing::span::Id) {}
}

#[derive(Clone, Default)]
struct LogBuffer(Arc<Mutex<Vec<u8>>>);

impl LogBuffer {
    fn text(&self) -> String {
        String::from_utf8_lossy(&self.0.lock().unwrap()).into_owned()
    }
}

impl Write for LogBuffer {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

struct Scenario {
    dir: TempDir,
    file: PathBuf,
    guest_file: PathBuf,
    password: Option<String>,
    set: bool,
    usage_error: Option<String>,
    io_error: Option<String>,
    capture_log: bool,
    log: LogBuffer,
}

impl Scenario {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("webui-password");
        let guest_file = dir.path().join("webui-guest-password");
        Self {
            dir,
            file,
            guest_file,
            password: None,
            set: false,
            usage_error: None,
            io_error: None,
            capture_log: false,
            log: LogBuffer::default(),
        }
    }

    fn config(&self) -> PathBuf {
        self.dir.path().join("config")
    }

    fn given_the_password_file(&mut self, content: &str) {
        self.given_the_password_file_bytes(content.as_bytes());
    }

    fn given_the_password_file_bytes(&mut self, content: &[u8]) {
        self.file = self.dir.path().join("webui-password");
        fs::write(&self.file, content).unwrap();
    }

    fn given_the_guest_password_file(&mut self, content: &str) {
        self.guest_file = self.dir.path().join("webui-guest-password");
        fs::write(&self.guest_file, content).unwrap();
    }

    fn given_the_web_interface_settings_with_a_guest(&self) {
        self.given_the_web_interface_settings(WebUiSettings {
            enabled: true,
            port: 8080,
            lan: false,
            password_hash: "pbkdf2-sha512$1$AA$AA".to_owned(),
            api: false,
            hosts: Vec::new(),
            proxies: Vec::new(),
            guest_password_hash: GUEST_HASH.to_owned(),
        });
    }

    fn given_a_password_file_that_does_not_exist(&mut self) {
        self.file = self.dir.path().join("missing");
    }

    fn given_the_web_interface_settings(&self, settings: WebUiSettings) {
        settings.save(&self.config()).unwrap();
    }

    fn given_the_log_is_listened_to(&mut self) {
        self.capture_log = true;
    }

    fn when_the_password_is_read(&mut self) {
        match web_ui_password::read(&self.file) {
            Ok(password) => self.password = Some((*password).clone()),
            Err(e) => self.usage_error = Some(e.to_string()),
        }
    }

    fn when_the_command_line_sets_the_password_from_the_file(&mut self, options: &[&str]) {
        let mut args = vec!["--webui-password-file".to_owned(), self.file.to_string_lossy().into_owned()];
        args.extend(options.iter().map(|s| (*s).to_owned()));
        self.when_the_command_line_sets_the_password(&args);
    }

    fn when_the_password_command_sets_the_password_from_the_file(&mut self, options: &[&str]) {
        let mut args = vec![
            "--webui-password".to_owned(),
            "--webui-password-file".to_owned(),
            self.file.to_string_lossy().into_owned(),
        ];
        args.extend(options.iter().map(|s| (*s).to_owned()));
        self.when_the_command_line_sets_the_password(&args);
    }

    fn when_the_command_line_sets_no_password_file(&mut self) {
        let args = vec!["--config-dir".to_owned(), self.dir.path().to_string_lossy().into_owned()];
        self.when_the_command_line_sets_the_password(&args);
    }

    fn when_the_command_line_sets_the_password(&mut self, args: &[String]) {
        let refs: Vec<&str> = args.iter().map(String::as_str).collect();
        let cli = match LaunchOptions::parse(&refs) {
            Ok(cli) => cli,
            Err(e) => {
                self.usage_error = Some(e.to_string());
                return;
            }
        };
        let config = self.config();
        let run = || web_ui_password::set_from_file(&cli, &config);
        let result = if self.capture_log {
            let _ = tracing::subscriber::set_global_default(AlwaysCheck);
            tracing::callsite::rebuild_interest_cache();
            let log = self.log.clone();
            let subscriber = tracing_subscriber::fmt()
                .with_ansi(false)
                .with_max_level(tracing::Level::INFO)
                .with_writer(move || log.clone())
                .finish();
            tracing::subscriber::with_default(subscriber, run)
        } else {
            run()
        };
        match result {
            Ok(set) => self.set = set,
            Err(PasswordError::Usage(e)) => self.usage_error = Some(e.to_string()),
            Err(PasswordError::Io(e)) => self.io_error = Some(e.to_string()),
        }
    }

    fn then_the_password_is(&self, expected: &str) {
        assert!(self.usage_error.is_none(), "{:?}", self.usage_error);
        assert_eq!(self.password.as_deref(), Some(expected));
    }

    fn then_exception_occurred(&self, message_part: &str) {
        let message = self.usage_error.as_deref().unwrap();
        assert!(message.contains(message_part), "missing {message_part:?} in {message:?}");
    }

    fn then_the_message_does_not_contain(&self, text: &str) {
        let message = self.usage_error.as_deref().unwrap();
        assert!(!message.contains(text), "unexpected {text:?} in {message:?}");
    }

    fn then_the_web_interface_is_on_with_the_password(&self, expected: &str) {
        assert!(self.usage_error.is_none(), "{:?}", self.usage_error);
        assert!(self.io_error.is_none(), "{:?}", self.io_error);
        let settings = WebUiSettings::load(&self.config());
        assert!(settings.usable(), "on, with a password");
        assert!(passwords::matches(expected, &settings.password_hash), "the password of the file");
        assert!(self.set, "a password file was given");
    }

    fn then_the_web_interface_is_open_to_other_computers(&self) {
        assert!(WebUiSettings::load(&self.config()).lan);
    }

    fn then_the_saved_settings_are(&self, port: u16, lan: bool, api: bool) {
        let settings = WebUiSettings::load(&self.config());
        assert_eq!(settings.port, port);
        assert_eq!(settings.lan, lan);
        assert_eq!(settings.api, api);
    }

    fn then_the_saved_host_names_are(&self, names: &[&str]) {
        assert!(self.usage_error.is_none(), "{:?}", self.usage_error);
        assert_eq!(WebUiSettings::load(&self.config()).hosts, names);
    }

    fn then_the_guest_logs_in_with(&self, expected: &str) {
        assert!(self.usage_error.is_none(), "{:?}", self.usage_error);
        let settings = WebUiSettings::load(&self.config());
        assert!(passwords::matches(expected, &settings.guest_password_hash), "the guest password of the file");
    }

    fn then_the_settings_file_does_not_exist(&self) {
        assert!(!self.config().join(web_ui_settings::FILE_NAME).exists());
    }

    fn then_the_saved_guest_hash_is(&self, expected: &str) {
        assert!(self.usage_error.is_none(), "{:?}", self.usage_error);
        assert_eq!(WebUiSettings::load(&self.config()).guest_password_hash, expected);
    }

    fn then_the_log_names_the_file_but_not_the_password(&self) {
        let text = self.log.text();
        let file = self.file.to_string_lossy();
        assert!(text.contains(file.as_ref()), "missing {file:?} in log:\n{text}");
        assert!(!text.contains(PASSWORD), "password leaked into log:\n{text}");
    }

    fn then_nothing_was_saved(&self) {
        assert!(self.usage_error.is_none(), "{:?}", self.usage_error);
        assert!(self.io_error.is_none(), "{:?}", self.io_error);
        assert!(!self.set, "a password file was given");
        assert!(!self.config().join(web_ui_settings::FILE_NAME).exists());
    }
}
