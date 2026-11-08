// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's LoginThrottleTest, under its names: login attempts are counted per client, one IPv4 address or one
//! IPv6 network of a /64 whose host part it picks.

#![allow(clippy::unwrap_used)]

use std::time::Duration;

use njmule_engine::remote::sessions::{self, MAX_FAILURES, Sessions};

#[test]
fn should_count_the_addresses_of_one_ipv6_network_together() {
    let mut test = Throttle::new();
    test.given_failed_attempts_from_addresses_of("2001:db8:1:2::", MAX_FAILURES);

    test.when_an_attempt_comes_from("2001:db8:1:2:abcd::99");

    test.then_the_attempt_is_allowed(false);
}

#[test]
fn should_count_each_ipv6_network_on_its_own() {
    let mut test = Throttle::new();
    test.given_failed_attempts_from_addresses_of("2001:db8:1:2::", MAX_FAILURES);

    test.when_an_attempt_comes_from("2001:db8:1:3::1");

    test.then_the_attempt_is_allowed(true);
}

#[test]
fn should_count_each_ipv4_address_on_its_own() {
    let mut test = Throttle::new();
    test.given_failed_attempts_from_addresses_of("192.0.2.", MAX_FAILURES);

    test.when_an_attempt_comes_from("192.0.2.200");

    test.then_the_attempt_is_allowed(true);
}

struct Throttle {
    sessions: Sessions,
    allowed: Option<bool>,
}

impl Throttle {
    fn new() -> Self {
        Self { sessions: Sessions::new(|| Duration::ZERO), allowed: None }
    }

    fn given_failed_attempts_from_addresses_of(&self, prefix: &str, count: u32) {
        for i in 1..=count {
            assert!(self.sessions.may_try(&sessions::client_key(format!("{prefix}{i}").parse().unwrap())));
        }
    }

    fn when_an_attempt_comes_from(&mut self, address: &str) {
        self.allowed = Some(self.sessions.may_try(&sessions::client_key(address.parse().unwrap())));
    }

    fn then_the_attempt_is_allowed(&self, expected: bool) {
        assert_eq!(self.allowed, Some(expected));
    }
}
