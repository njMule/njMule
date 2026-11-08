// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The test of our ports: a new login to the server, which checks our TCP port, and Kad's checks of both ports.

use std::time::Instant;

use super::Network;
use crate::api::log_line::Level as LogLevel;
use crate::api::port_test::PortTest;
use crate::config::bind_interface::Binding;
use crate::engine::port_check::{self, PortCheck};

/// The Kad node a test counts on, and the rounds of checks it had started when the test started its own.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct KadRound {
    node: u64,
    rounds: u32,
}

impl Network {
    /// Tests our ports, a test already running on them going on; answers the test as it stands.
    pub(super) fn test_ports(&mut self) -> PortTest {
        // No router is asked through UPnP yet: the test runs without it.
        let upnp = false;
        let tcp = self.shared.listen_port();
        let udp = self.kad_udp_port();
        // A test that is over gives way to the new one.
        if let Some(mut check) = self.port_check.take()
            && !check.is_over()
        {
            if check.tests(tcp, udp, upnp) {
                let state = check.state().clone();
                self.port_check = Some(check);
                return state;
            }
            // Other ports: the test of those left ends as it stands.
            check.stop();
            self.port_test_done(check.state());
        }
        let mut check = PortCheck::new(tcp, udp, upnp, Instant::now(), port_check::WAIT);
        let kad_port = if udp == 0 { String::new() } else { format!(", UDP {udp}") };
        self.user_log(LogLevel::Info, format!("Testing the ports: TCP {tcp}{kad_port}"));
        self.port_check_step(&mut check);
        let state = check.state().clone();
        self.port_check = Some(check);
        state
    }

    /// The test of our ports running or run last; none before the first.
    pub(super) fn port_test(&self) -> Option<PortTest> {
        self.port_check.as_ref().map(|check| check.state().clone())
    }

    /// Follows a running test, once a second.
    pub(super) fn tick_port_check(&mut self) {
        if let Some(mut check) = self.port_check.take() {
            if !check.is_over() {
                self.port_check_step(&mut check);
            }
            self.port_check = Some(check);
        }
    }

    /// A server took us, with a HighID when it reached our TCP port.
    pub(super) fn port_check_logged_in(&mut self, high_id: bool) {
        if let Some(check) = &mut self.port_check {
            check.logged_in(high_id);
        }
    }

    /// Ends a running test as it stands, as the engine stops.
    pub(super) fn stop_port_check(&mut self) {
        if let Some(mut check) = self.port_check.take() {
            if !check.is_over() {
                check.stop();
                self.port_test_done(check.state());
            }
            self.port_check = Some(check);
        }
    }

    fn port_check_step(&mut self, check: &mut PortCheck) {
        let now = Instant::now();
        if check.ready_to_reach(now) {
            self.start_reach_tests(check);
        }
        let state = match self.kad_checks() {
            None => check.update(false, None, None, now),
            Some(kad) => {
                // Until the check asked for has started, Kad's outcomes are those of an earlier one.
                let fresh = self.port_check_kad.is_none_or(|at| kad.node != at.node || kad.rounds > at.rounds);
                if fresh { check.update(true, kad.tcp, kad.udp, now) } else { check.update(true, None, None, now) }
            }
        };
        if state.done {
            self.port_test_done(&state);
        }
    }

    // The server checks our TCP port at login, Kad nodes check both ports.
    fn start_reach_tests(&mut self, check: &mut PortCheck) {
        let login = self.preferences().ed2k_enabled
            && !matches!(self.binding, Binding::Missing(_))
            && (self.session.server().is_some() || !self.servers.connectable().is_empty());
        check.reach_started(login);
        if login {
            let server = self.session.server().cloned();
            self.with_session(|session, ctx| session.connect(server, ctx));
        }
        self.port_check_kad = self.kad_checks().map(|kad| KadRound { node: kad.node, rounds: kad.rounds });
        self.recheck_kad();
    }

    fn port_test_done(&self, test: &PortTest) {
        let udp =
            if test.udp_port == 0 { String::new() } else { format!(", UDP {} {}", test.udp_port, test.udp.name()) };
        let router = if test.router.is_empty() { String::new() } else { format!(" ({})", test.router) };
        self.user_log(
            LogLevel::Info,
            format!(
                "Port test: TCP {} {}{udp}, UPnP {}{router}",
                test.tcp_port,
                test.tcp.name(),
                test.forwarding.name().replace('_', " ")
            ),
        );
    }
}
