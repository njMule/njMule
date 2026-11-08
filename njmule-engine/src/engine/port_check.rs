// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A test of our ports: the router asked through UPnP first when it is on, then the server checks our TCP port at a new
//! login (a HighID), and Kad nodes check both ports.

use std::time::{Duration, Instant};

use crate::api::port_test::{Forwarding, PortTest, Reach};
use crate::upnp::port_forwarding::Outcome;

/// How long a test waits for its outcomes: Kad's UDP test may take minutes.
pub const WAIT: Duration = Duration::from_secs(3 * 60);
/// A router answers within seconds: past this the other checks start anyway.
pub const UPNP_WAIT: Duration = Duration::from_secs(15);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Login {
    None,
    Waiting,
    HighId,
    LowId,
}

#[derive(Debug)]
pub struct PortCheck {
    tcp_port: u16,
    udp_port: u16,
    upnp: bool,
    started: Instant,
    wait: Duration,
    forwarding: Forwarding,
    router: String,
    reach_started: bool,
    login: Login,
    stopped: bool,
    state: PortTest,
}

impl PortCheck {
    pub fn new(tcp_port: u16, udp_port: u16, upnp: bool, now: Instant, wait: Duration) -> Self {
        let forwarding = if upnp { Forwarding::Asking } else { Forwarding::Off };
        Self {
            tcp_port,
            udp_port,
            upnp,
            started: now,
            wait,
            forwarding,
            router: String::new(),
            reach_started: false,
            login: Login::None,
            stopped: false,
            state: PortTest {
                tcp_port,
                udp_port,
                forwarding,
                router: String::new(),
                tcp: Reach::Testing,
                udp: Reach::Testing,
                done: false,
            },
        }
    }

    /// Whether this test is of these ports, asked through UPnP or not.
    pub fn tests(&self, tcp_port: u16, udp_port: u16, upnp: bool) -> bool {
        self.tcp_port == tcp_port && self.udp_port == udp_port && self.upnp == upnp
    }

    pub fn state(&self) -> &PortTest {
        &self.state
    }

    pub fn is_over(&self) -> bool {
        self.state.done
    }

    /// Whether the server and Kad checks are to start: UPnP answered, or took too long.
    pub fn ready_to_reach(&self, now: Instant) -> bool {
        !self.reach_started
            && (self.forwarding != Forwarding::Asking || now.saturating_duration_since(self.started) >= UPNP_WAIT)
    }

    /// The server and Kad checks start; `login` when a server is asked to take us again.
    pub fn reach_started(&mut self, login: bool) {
        self.reach_started = true;
        self.login = if login { Login::Waiting } else { Login::None };
    }

    pub fn forwarded(&mut self, outcome: Outcome, router: &str) {
        if self.forwarding != Forwarding::Asking {
            return;
        }
        router.clone_into(&mut self.router);
        self.forwarding = forwarding(outcome);
    }

    /// A server took us, with a HighID when it reached our TCP port; only the login the test asked for counts.
    pub fn logged_in(&mut self, high_id: bool) {
        if self.login == Login::Waiting {
            self.login = if high_id { Login::HighId } else { Login::LowId };
        }
    }

    /// The state as the checks stand: `kad_tcp` and `kad_udp` are Kad's outcomes (open or not), none while they run.
    /// The test is over when every outcome is known or the time is up.
    pub fn update(&mut self, kad_runs: bool, kad_tcp: Option<bool>, kad_udp: Option<bool>, now: Instant) -> PortTest {
        if self.is_over() {
            return self.state.clone();
        }
        let late = self.stopped || now.saturating_duration_since(self.started) >= self.wait;
        let kad_counts = kad_runs && self.reach_started;
        let tcp = self.tcp(kad_counts, kad_tcp, late);
        let udp = if !kad_counts {
            if self.reach_started { Reach::Unknown } else { Reach::Testing }
        } else {
            match kad_udp {
                Some(true) => Reach::Open,
                Some(false) => Reach::Closed,
                None if late => Reach::Unknown,
                None => Reach::Testing,
            }
        };
        if late && self.forwarding == Forwarding::Asking {
            self.forwarding = Forwarding::Failed;
        }
        let done = late || (self.forwarding != Forwarding::Asking && tcp != Reach::Testing && udp != Reach::Testing);
        self.state = PortTest {
            tcp_port: self.tcp_port,
            udp_port: self.udp_port,
            forwarding: self.forwarding,
            router: self.router.clone(),
            tcp,
            udp,
            done,
        };
        self.state.clone()
    }

    /// Ends the test at once, as the engine stops: what is not known stays unknown.
    pub fn stop(&mut self) {
        self.stopped = true;
        self.update(false, None, None, self.started);
    }

    // Open as soon as the server or Kad got through; closed once neither did and nothing more will tell.
    fn tcp(&self, kad_counts: bool, kad_tcp: Option<bool>, late: bool) -> Reach {
        if !self.reach_started {
            return Reach::Testing;
        }
        let kad_open = kad_counts && kad_tcp.unwrap_or(false);
        if self.login == Login::HighId || kad_open {
            return Reach::Open;
        }
        let closed = self.login == Login::LowId || (kad_counts && kad_tcp.is_some());
        let pending = self.login == Login::Waiting || (kad_counts && kad_tcp.is_none());
        if !pending || late {
            return if closed { Reach::Closed } else { Reach::Unknown };
        }
        Reach::Testing
    }
}

/// The router's answer as the interfaces tell it.
pub fn forwarding(outcome: Outcome) -> Forwarding {
    match outcome {
        Outcome::Forwarded => Forwarding::Forwarded,
        Outcome::Partly => Forwarding::Partly,
        Outcome::Refused => Forwarding::Refused,
        Outcome::NoRouter => Forwarding::NoRouter,
        Outcome::Failed => Forwarding::Failed,
    }
}
