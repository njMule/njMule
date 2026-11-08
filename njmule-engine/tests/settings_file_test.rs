// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's SettingsFileTest, under its names, and the password hashes: the web interface's settings as the
//! API changes them in their file.

#![allow(clippy::unwrap_used, clippy::panic)]

use std::fs;
use std::sync::{Arc, Mutex};

use njmule_engine::remote::passwords;
use njmule_engine::remote::settings_file::{ChangeError, SettingsFile, WebChange, WebView};
use njmule_engine::remote::web_ui_settings::{self, DEFAULT_PORT, WebUiSettings};

const PASSWORD: &str = "long enough";

#[test]
fn should_keep_the_changes_without_their_passwords_in_the_clear() {
    let mut test = Settings::new();
    test.given_the_settings_file();

    test.when_changed(change(Some(true), Some(PASSWORD), Some(&["NAS.local"])));

    test.then_no_error_occurred();
    test.then_the_settings_say(true, true, &["nas.local"]);
    test.then_the_file_holds_no(PASSWORD);
}

#[test]
fn should_refuse_to_turn_the_interface_on_without_a_password() {
    let mut test = Settings::new();
    test.given_the_settings_file();

    test.when_changed(change(Some(true), None, None));

    test.then_it_is_refused_as("needs a password");
    test.then_the_interface_is_off_in_the_file();
    test.then_nothing_was_heard();
}

#[test]
fn should_hand_over_the_settings_saved() {
    let mut test = Settings::new();
    test.given_the_settings_file();

    test.when_changed(change(Some(true), Some(PASSWORD), None));

    test.then_the_settings_heard_are_usable();
}

#[test]
fn should_refuse_a_host_that_is_no_name() {
    let mut test = Settings::new();
    test.given_the_settings_file();

    test.when_changed(change(None, None, Some(&["http://nas"])));

    test.then_it_is_refused_as("not a host name");
}

#[test]
fn should_refuse_a_guest_password_that_is_the_admins() {
    let mut test = Settings::new();
    test.given_the_settings_file();
    test.given_a_password();

    test.when_changed(WebChange { guest_password: Some(PASSWORD.to_owned()), ..WebChange::default() });

    test.then_it_is_refused_as("must differ");
}

#[test]
fn should_match_a_hash_of_the_java_engine() {
    let mut test = Settings::new();

    // PBKDF2-HMAC-SHA512 of "long enough" with the salt "saltsaltsaltsalt" and 2 rounds, as the Java engine writes it.
    test.when_the_password_is_checked(
        PASSWORD,
        "pbkdf2-sha512$2$c2FsdHNhbHRzYWx0c2FsdA$VMCDs8b494TpZYj59cDgL23mVKJNiEQ5u2q1ftKnWom6pwiQuGzgWGwYPu5+PwHIVZuA+ciaQ4CdjPUK4gV41A",
    );

    test.then_it_matches(true);
}

#[test]
fn should_match_no_other_password_nor_a_malformed_hash() {
    for (password, stored) in [
        ("other one", passwords::hash_with(PASSWORD, b"saltsaltsaltsalt", 2)),
        (PASSWORD, "pbkdf2-sha512$0$c2FsdA$AAAA".to_owned()),
        (PASSWORD, "sha1$2$c2FsdA$AAAA".to_owned()),
        (PASSWORD, "pbkdf2-sha512$2$not base64$AAAA".to_owned()),
        (PASSWORD, String::new()),
    ] {
        let mut test = Settings::new();

        test.when_the_password_is_checked(password, &stored);

        test.then_it_matches(false);
    }
}

#[test]
fn should_keep_the_settings_through_their_file() {
    let mut test = Settings::new();
    test.given_the_settings_saved(WebUiSettings {
        enabled: true,
        port: 8080,
        lan: true,
        password_hash: passwords::hash_with(PASSWORD, b"saltsaltsaltsalt", 2),
        api: true,
        hosts: vec!["nas.local".to_owned(), "[::1]".to_owned()],
        proxies: vec!["10.0.0.0/8".to_owned()],
        guest_password_hash: passwords::hash_with("guest password", b"saltsaltsaltsalt", 2),
    });

    test.when_the_settings_are_loaded();

    test.then_they_are_the_settings_saved();
}

struct Settings {
    dir: tempfile::TempDir,
    file: Option<SettingsFile>,
    heard: Arc<Mutex<Vec<WebUiSettings>>>,
    changed: Option<Result<WebView, ChangeError>>,
    matched: Option<bool>,
    saved: Option<WebUiSettings>,
    loaded: Option<WebUiSettings>,
}

impl Settings {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            file: None,
            heard: Arc::default(),
            changed: None,
            matched: None,
            saved: None,
            loaded: None,
        }
    }

    fn given_the_settings_file(&mut self) {
        let heard = Arc::clone(&self.heard);
        self.file = Some(SettingsFile::new(self.dir.path().to_path_buf(), move |settings| {
            heard.lock().unwrap().push(settings.clone());
        }));
    }

    fn given_a_password(&self) {
        self.file.as_ref().unwrap().change(change(None, Some(PASSWORD), None)).unwrap();
    }

    fn given_the_settings_saved(&mut self, settings: WebUiSettings) {
        settings.save(self.dir.path()).unwrap();
        self.saved = Some(settings);
    }

    fn when_changed(&mut self, change: WebChange) {
        self.changed = Some(self.file.as_ref().unwrap().change(change));
    }

    fn when_the_password_is_checked(&mut self, password: &str, stored: &str) {
        self.matched = Some(passwords::matches(password, stored));
    }

    fn when_the_settings_are_loaded(&mut self) {
        self.loaded = Some(WebUiSettings::load(self.dir.path()));
    }

    fn then_no_error_occurred(&self) {
        assert!(matches!(self.changed, Some(Ok(_))), "{:?}", self.changed);
    }

    fn then_the_settings_say(&self, enabled: bool, has_password: bool, hosts: &[&str]) {
        let Some(Ok(settings)) = &self.changed else { panic!("{:?}", self.changed) };
        assert_eq!(settings.enabled, enabled);
        assert_eq!(settings.has_password, has_password);
        assert_eq!(settings.hosts, hosts);
        assert_eq!(settings.url, Some(format!("http://127.0.0.1:{DEFAULT_PORT}/")));
    }

    fn then_the_file_holds_no(&self, text: &str) {
        let written = fs::read_to_string(self.dir.path().join(web_ui_settings::FILE_NAME)).unwrap();
        assert!(!written.contains(text), "{written}");
    }

    fn then_it_is_refused_as(&self, why: &str) {
        match &self.changed {
            Some(Err(ChangeError::Invalid(reason))) => assert!(reason.contains(why), "{reason}"),
            other => panic!("not refused: {other:?}"),
        }
    }

    fn then_the_interface_is_off_in_the_file(&self) {
        assert!(!WebUiSettings::load(self.dir.path()).enabled);
    }

    fn then_nothing_was_heard(&self) {
        assert!(self.heard.lock().unwrap().is_empty());
    }

    fn then_the_settings_heard_are_usable(&self) {
        let heard = self.heard.lock().unwrap();
        assert_eq!(heard.len(), 1);
        assert!(heard.iter().all(WebUiSettings::usable));
    }

    fn then_it_matches(&self, expected: bool) {
        assert_eq!(self.matched, Some(expected));
    }

    fn then_they_are_the_settings_saved(&self) {
        assert_eq!(self.loaded, self.saved);
    }
}

fn change(enabled: Option<bool>, password: Option<&str>, hosts: Option<&[&str]>) -> WebChange {
    WebChange {
        enabled,
        password: password.map(str::to_owned),
        hosts: hosts.map(|hosts| hosts.iter().map(|host| (*host).to_owned()).collect()),
        ..WebChange::default()
    }
}
