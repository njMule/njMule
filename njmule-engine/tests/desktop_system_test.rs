// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! ED2K links and start-at-login toggles behind DesktopIntegration.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use njmule_engine::app::desktop_system::{DesktopSystem, Toggle};
use njmule_engine::remote::desktop_integration::{Change, DesktopError, DesktopIntegration, Settings};

#[test]
fn should_tell_both_settings_where_the_system_offers_them() {
    let mut test = Scenario::new();
    test.given_the_system_offers_both();
    test.given_the_links_are_ours();

    test.when_the_settings_are_read();

    test.then_the_settings_are(Some(true), Some(false));
}

#[test]
fn should_tell_nothing_of_the_links_where_the_system_hands_them_over_itself() {
    let mut test = Scenario::new();
    test.given_the_system_offers_only_the_start_at_login();

    test.when_the_settings_are_read();

    test.then_the_settings_are(None, Some(false));
}

#[test]
fn should_turn_the_start_at_login_on() {
    let mut test = Scenario::new();
    test.given_the_system_offers_both();

    test.when_changed(None, Some(true));

    test.then_the_settings_are(Some(false), Some(true));
}

#[test]
fn should_give_the_links_back() {
    let mut test = Scenario::new();
    test.given_the_system_offers_both();
    test.given_the_links_are_ours();

    test.when_changed(Some(false), None);

    test.then_the_settings_are(Some(false), Some(false));
}

#[test]
fn should_change_nothing_when_a_setting_is_not_offered() {
    let mut test = Scenario::new();
    test.given_the_system_offers_only_the_start_at_login();

    test.when_changed(Some(true), Some(true));

    test.then_a_wrong_change_was_refused();
    test.then_the_start_at_login_is_still(false);
}

#[derive(Clone, Default)]
struct Switch(Arc<AtomicBool>);

impl Toggle for Switch {
    fn is_on(&self) -> io::Result<bool> {
        Ok(self.0.load(Ordering::SeqCst))
    }

    fn set(&self, on: bool) -> io::Result<()> {
        self.0.store(on, Ordering::SeqCst);
        Ok(())
    }
}

struct Scenario {
    links: Switch,
    start: Switch,
    system: Option<DesktopSystem>,
    settings: Option<Settings>,
    failure: Option<DesktopError>,
}

impl Scenario {
    fn new() -> Self {
        Self { links: Switch::default(), start: Switch::default(), system: None, settings: None, failure: None }
    }

    fn given_the_system_offers_both(&mut self) {
        self.system = Some(DesktopSystem::new(Some(Box::new(self.links.clone())), Some(Box::new(self.start.clone()))));
    }

    fn given_the_system_offers_only_the_start_at_login(&mut self) {
        self.system = Some(DesktopSystem::new(None, Some(Box::new(self.start.clone()))));
    }

    fn given_the_links_are_ours(&self) {
        self.links.0.store(true, Ordering::SeqCst);
    }

    fn when_the_settings_are_read(&mut self) {
        self.settings = Some(self.system.as_ref().unwrap().settings().unwrap());
    }

    fn when_changed(&mut self, links: Option<bool>, autostart: Option<bool>) {
        match self.system.as_ref().unwrap().change(Change { links, autostart }) {
            Ok(settings) => self.settings = Some(settings),
            Err(error) => self.failure = Some(error),
        }
    }

    fn then_the_settings_are(&self, links: Option<bool>, autostart: Option<bool>) {
        assert_eq!(self.settings, Some(Settings { links, autostart }));
    }

    fn then_a_wrong_change_was_refused(&self) {
        assert!(matches!(self.failure, Some(DesktopError::Unsupported(_))), "{:?}", self.failure);
    }

    fn then_the_start_at_login_is_still(&self, on: bool) {
        assert_eq!(self.start.0.load(Ordering::SeqCst), on);
    }
}
