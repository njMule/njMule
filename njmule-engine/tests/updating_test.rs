// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java UpdatingTest, under its names: restart arguments, systemd detection and waiting for old processes.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::collections::BTreeMap;
use std::time::{Duration, Instant};

use njmule_engine::app::updating;

#[test]
fn should_start_again_without_waiting_for_an_earlier_process() {
    let mut test = Scenario::default();
    test.given_the_arguments(&["--wait", "4242", "--config-dir", "/data", "--wait=77", "--headless"]);

    test.when_the_arguments_to_start_again_are_taken();

    test.then_they_are(&["--config-dir", "/data", "--headless"]);
}

#[test]
fn should_tell_a_service_of_systemd() {
    let mut test = Scenario::default();
    test.given_the_environment(&[("INVOCATION_ID", "8f3c"), ("JOURNAL_STREAM", "8:1234")]);

    test.when_asked_whether_systemd_restarts_it();

    test.then_the_answer_is(true);
}

#[test]
fn should_not_take_a_terminal_for_a_service() {
    let mut test = Scenario::default();
    test.given_the_environment(&[("TERM", "xterm")]);

    test.when_asked_whether_systemd_restarts_it();

    test.then_the_answer_is(false);
}

#[test]
fn should_not_wait_for_a_process_that_has_ended() {
    let mut test = Scenario::default();

    test.when_waiting_for(u64::MAX);

    test.then_it_waited_less_than(Duration::from_secs(5));
}

#[derive(Default)]
struct Scenario {
    args: Vec<String>,
    again: Vec<String>,
    environment: BTreeMap<String, String>,
    supervised: bool,
    waited: Duration,
}

impl Scenario {
    fn given_the_arguments(&mut self, given: &[&str]) {
        self.args = given.iter().map(|arg| (*arg).to_owned()).collect();
    }

    fn given_the_environment(&mut self, chosen: &[(&str, &str)]) {
        self.environment = chosen.iter().map(|(k, v)| ((*k).to_owned(), (*v).to_owned())).collect();
    }

    fn when_the_arguments_to_start_again_are_taken(&mut self) {
        self.again = updating::again(&self.args);
    }

    fn when_asked_whether_systemd_restarts_it(&mut self) {
        self.supervised = updating::under_systemd(&self.environment);
    }

    fn when_waiting_for(&mut self, pid: u64) {
        let start = Instant::now();
        updating::await_end(&[pid]);
        self.waited = start.elapsed();
    }

    fn then_they_are(&self, expected: &[&str]) {
        assert_eq!(self.again, expected);
    }

    fn then_the_answer_is(&self, expected: bool) {
        assert_eq!(self.supervised, expected);
    }

    fn then_it_waited_less_than(&self, limit: Duration) {
        assert!(self.waited < limit, "waited {:?}", self.waited);
    }
}
