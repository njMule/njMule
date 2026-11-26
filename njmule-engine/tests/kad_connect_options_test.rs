// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What Kad tells of how to reach us.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::sync::{Arc, Mutex};

use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::config::proxy_settings::{ProxySettings, ProxyType};
use njmule_engine::engine::network::kad_options;
use njmule_engine::protocol::ed2k::nat_traversal_messages::{OPTION_QUIC, OPTION_UTP};

const TRAVERSAL: u8 = OPTION_UTP | OPTION_QUIC;

#[test]
fn should_tell_that_we_are_reached_through_the_routers() {
    let mut test = Options::new();

    test.when_kad_tells_how_to_reach_us();

    test.then_traversal_is_told(true);
}

#[test]
fn should_not_tell_traversal_where_obfuscation_is_required() {
    let mut test = Options::new();
    test.given_the_obfuscation(ObfuscationMode::Required);

    test.when_kad_tells_how_to_reach_us();

    test.then_traversal_is_told(false);
}

#[test]
fn should_not_tell_traversal_behind_a_proxy() {
    let mut test = Options::new();
    test.given_the_proxy(ProxySettings {
        kind: ProxyType::Socks,
        host: "proxy.lan".to_owned(),
        port: 1080,
        ..ProxySettings::default()
    });

    test.when_kad_tells_how_to_reach_us();

    test.then_traversal_is_told(false);
}

// Kad started before the change still tells the options of now.
#[test]
fn should_tell_the_options_of_the_preferences_as_they_are_now() {
    let mut test = Options::new();
    test.given_kad_started();
    test.given_the_obfuscation(ObfuscationMode::Required);

    test.when_kad_tells_how_to_reach_us();

    test.then_traversal_is_told(false);
}

struct Options {
    preferences: Arc<Mutex<Preferences>>,
    // What the node asks for the options, as Kad's settings hold it once Kad started.
    options: Option<Box<dyn Fn() -> u8>>,
    told: u8,
}

impl Options {
    fn new() -> Self {
        Self { preferences: Arc::default(), options: None, told: 0 }
    }

    fn given_kad_started(&mut self) {
        self.options = Some(self.kad_settings());
    }

    fn given_the_obfuscation(&self, mode: ObfuscationMode) {
        self.preferences.lock().unwrap().obfuscation = mode;
    }

    fn given_the_proxy(&self, proxy: ProxySettings) {
        self.preferences.lock().unwrap().proxy = proxy;
    }

    fn when_kad_tells_how_to_reach_us(&mut self) {
        self.told = match &self.options {
            Some(options) => options(),
            None => self.kad_settings()(),
        };
    }

    fn then_traversal_is_told(&self, expected: bool) {
        assert_eq!(self.told & TRAVERSAL == TRAVERSAL, expected);
        assert!(self.told & TRAVERSAL == 0 || self.told & TRAVERSAL == TRAVERSAL);
    }

    fn kad_settings(&self) -> Box<dyn Fn() -> u8> {
        let preferences = Arc::clone(&self.preferences);
        Box::new(move || kad_options(&preferences.lock().unwrap()))
    }
}
