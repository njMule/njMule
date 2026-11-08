// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's PortCheckTest, under its names: a test of our ports, the router first, then the server and Kad.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]

use std::time::{Duration, Instant};

use njmule_engine::api::port_test::{Forwarding, PortTest, Reach};
use njmule_engine::engine::port_check::{PortCheck, UPNP_WAIT, WAIT};
use njmule_engine::upnp::port_forwarding::Outcome;

#[test]
fn should_ask_the_router_before_the_server_and_kad() {
    let mut test = PortCheckTest::new();
    test.given_a_test_with_upnp();

    test.when_it_is_asked_whether_to_reach_after(Duration::from_secs(1));

    test.then_the_other_checks_wait();
    test.then_the_state_is(Forwarding::Asking, Reach::Testing, Reach::Testing, false);
}

#[test]
fn should_start_the_other_checks_once_the_router_forwarded_the_ports() {
    let mut test = PortCheckTest::new();
    test.given_a_test_with_upnp();
    test.given_the_router_answered(Outcome::Forwarded, "192.168.1.1");

    test.when_it_is_asked_whether_to_reach_after(Duration::from_secs(1));

    test.then_the_other_checks_start();
    test.then_the_router_is("192.168.1.1");
}

#[test]
fn should_start_the_other_checks_when_the_router_takes_too_long() {
    let mut test = PortCheckTest::new();
    test.given_a_test_with_upnp();

    test.when_it_is_asked_whether_to_reach_after(UPNP_WAIT);

    test.then_the_other_checks_start();
}

#[test]
fn should_find_both_ports_open_when_kad_reached_them_and_the_server_gave_a_low_id() {
    let mut test = PortCheckTest::new();
    test.given_a_test_without_upnp();
    test.given_the_checks_started(true);
    test.given_the_server_took_us(false);

    test.when_kad_tells(Some(true), Some(true), Duration::from_secs(20));

    test.then_the_state_is(Forwarding::Off, Reach::Open, Reach::Open, true);
}

#[test]
fn should_wait_for_kad_before_calling_the_tcp_port_closed_on_a_low_id() {
    let mut test = PortCheckTest::new();
    test.given_a_test_without_upnp();
    test.given_the_checks_started(true);
    test.given_the_server_took_us(false);

    test.when_kad_tells(None, None, Duration::from_secs(20));

    test.then_the_state_is(Forwarding::Off, Reach::Testing, Reach::Testing, false);
}

#[test]
fn should_find_the_tcp_port_closed_when_neither_the_server_nor_kad_reached_it() {
    let mut test = PortCheckTest::new();
    test.given_a_test_without_upnp();
    test.given_the_checks_started(true);
    test.given_the_server_took_us(false);

    test.when_kad_tells(Some(false), Some(false), Duration::from_secs(20));

    test.then_the_state_is(Forwarding::Off, Reach::Closed, Reach::Closed, true);
}

#[test]
fn should_count_only_the_login_the_test_asked_for() {
    let mut test = PortCheckTest::new();
    test.given_a_test_without_upnp();
    test.given_the_server_took_us(true);
    test.given_the_checks_started(true);

    test.when_nothing_runs(Duration::from_secs(20));

    test.then_the_state_is(Forwarding::Off, Reach::Testing, Reach::Unknown, false);
}

#[test]
fn should_tell_what_nothing_tested_in_time_as_unknown() {
    let mut test = PortCheckTest::new();
    test.given_a_test_with_upnp();
    test.given_the_checks_started(false);

    test.when_kad_tells(None, None, WAIT);

    test.then_the_state_is(Forwarding::Failed, Reach::Unknown, Reach::Unknown, true);
}

#[test]
fn should_end_at_once_when_the_engine_stops() {
    let mut test = PortCheckTest::new();
    test.given_a_test_without_upnp();
    test.given_the_checks_started(true);

    test.when_the_engine_stops();

    test.then_the_state_is(Forwarding::Off, Reach::Unknown, Reach::Unknown, true);
    test.then_the_test_is_over_with_the_state();
}

struct PortCheckTest {
    start: Instant,
    check: Option<PortCheck>,
    state: Option<PortTest>,
    ready_to_reach: bool,
}

impl PortCheckTest {
    fn new() -> Self {
        Self { start: Instant::now(), check: None, state: None, ready_to_reach: false }
    }

    fn given_a_test_with_upnp(&mut self) {
        self.check = Some(PortCheck::new(4662, 4672, true, self.start, WAIT));
    }

    fn given_a_test_without_upnp(&mut self) {
        self.check = Some(PortCheck::new(4662, 4672, false, self.start, WAIT));
    }

    fn given_the_router_answered(&mut self, outcome: Outcome, router: &str) {
        self.check().forwarded(outcome, router);
    }

    fn given_the_checks_started(&mut self, login: bool) {
        self.check().reach_started(login);
    }

    fn given_the_server_took_us(&mut self, high_id: bool) {
        self.check().logged_in(high_id);
    }

    fn when_it_is_asked_whether_to_reach_after(&mut self, time: Duration) {
        let now = self.start + time;
        self.ready_to_reach = self.check().ready_to_reach(now);
        self.state = Some(self.check().update(false, None, None, now));
    }

    fn when_kad_tells(&mut self, tcp: Option<bool>, udp: Option<bool>, time: Duration) {
        let now = self.start + time;
        self.state = Some(self.check().update(true, tcp, udp, now));
    }

    fn when_nothing_runs(&mut self, time: Duration) {
        let now = self.start + time;
        self.state = Some(self.check().update(false, None, None, now));
    }

    fn when_the_engine_stops(&mut self) {
        self.check().stop();
        self.state = Some(self.check().state().clone());
    }

    fn then_the_other_checks_wait(&self) {
        assert!(!self.ready_to_reach);
    }

    fn then_the_other_checks_start(&self) {
        assert!(self.ready_to_reach);
    }

    fn then_the_router_is(&self, router: &str) {
        let state = self.state.as_ref().unwrap();
        assert_eq!(state.forwarding, Forwarding::Forwarded);
        assert_eq!(state.router, router);
    }

    fn then_the_state_is(&self, forwarding: Forwarding, tcp: Reach, udp: Reach, done: bool) {
        let state = self.state.as_ref().unwrap();
        let expected =
            PortTest { tcp_port: 4662, udp_port: 4672, forwarding, router: state.router.clone(), tcp, udp, done };
        assert_eq!(state, &expected);
        assert_eq!(self.check.as_ref().unwrap().is_over(), done, "the test over");
    }

    fn then_the_test_is_over_with_the_state(&self) {
        let check = self.check.as_ref().unwrap();
        assert!(check.is_over());
        assert_eq!(check.state(), self.state.as_ref().unwrap());
    }

    fn check(&mut self) -> &mut PortCheck {
        self.check.as_mut().unwrap()
    }
}
