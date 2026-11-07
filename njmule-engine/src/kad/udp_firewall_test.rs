// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The UDP firewall test. A helper is a node we never wrote to, so that no NAT mapping lets its datagram through by
//! chance; asked over TCP, it sends one to our UDP port. One that arrives means the port is open, two helpers whose
//! datagram never came mean it is firewalled. Before asking, a few nodes tell us the UDP port they see us on, which a
//! NAT may change: helpers try that one too.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

use indexmap::IndexMap;

use crate::kad::uint128::UInt128;
use crate::protocol::kad::kad_messages::Contact;

/// Helpers with an outcome that end a test: more raise the odds of a false "open", fewer of a false "firewalled".
pub const HELPERS: u32 = 2;
/// A TCP-firewalled node whose test brings no outcome this long counts as UDP-firewalled too.
pub const TIMEOUT: Duration = Duration::from_secs(6 * 60);
/// Nodes asked the UDP port they see us on; two that agree decide it.
pub const PORT_SAMPLES: usize = 3;

// The second datagram of a helper may come soon after the first, on the other port.
const LATE_DATAGRAM: Duration = Duration::from_secs(10);
const MAX_CANDIDATES: usize = 64;
// Helpers are never asked twice; the oldest are forgotten past this many.
const MAX_ASKED: usize = 256;

/// A helper asked in test `round`, and whether its outcome came.
#[derive(Debug, Clone, Copy)]
struct Asked {
    round: u32,
    answered: bool,
}

#[derive(Debug)]
pub struct UdpFirewallTest {
    candidates: VecDeque<Contact>,
    asked: IndexMap<u32, Asked>,
    port_answerers: Vec<u32>,
    ports_seen: Vec<u16>,
    port_decided: bool,
    external_port: u16,
    use_external_port: bool,
    firewalled: bool,
    last_firewalled: bool,
    verified: bool,
    timed_out: bool,
    round: u32,
    running: u32,
    finished: u32,
    // When the test that waits for its outcome started; none when none waits.
    started: Option<Instant>,
    succeeded_once: bool,
    last_success: Option<Instant>,
}

impl Default for UdpFirewallTest {
    fn default() -> Self {
        Self {
            candidates: VecDeque::new(),
            asked: IndexMap::new(),
            port_answerers: Vec::new(),
            ports_seen: Vec::new(),
            port_decided: false,
            external_port: 0,
            use_external_port: true,
            firewalled: false,
            last_firewalled: false,
            verified: false,
            timed_out: false,
            round: 0,
            running: 0,
            finished: 0,
            started: None,
            succeeded_once: false,
            last_success: None,
        }
    }
}

impl UdpFirewallTest {
    pub fn new() -> Self {
        Self::default()
    }

    /// Whether a test runs: from the start, and again after each recheck until it has its outcome.
    pub fn is_running(&self) -> bool {
        self.finished < HELPERS
    }

    /// Whether our UDP port is firewalled, as far as we know; while a test runs the last outcome stands when
    /// `last_while_testing`. A test that timed out says firewalled.
    pub fn is_firewalled(&self, last_while_testing: bool) -> bool {
        let testing = self.is_running();
        if self.timed_out && testing {
            return true;
        }
        if last_while_testing && testing { self.last_firewalled } else { self.firewalled }
    }

    /// Whether a test ever ended with an outcome.
    pub fn is_verified(&self) -> bool {
        self.verified
    }

    /// The UDP port a NAT shows other nodes, 0 while unknown or when the nodes disagree.
    pub fn external_port(&self) -> u16 {
        self.external_port
    }

    /// Whether others should reach us on the port a NAT shows them rather than on our own.
    pub fn use_external_port(&self) -> bool {
        self.use_external_port
    }

    /// Whether nodes are still asked the UDP port they see us on.
    pub fn finds_external_port(&self) -> bool {
        !self.port_decided
    }

    /// Whether helpers are missing and none is left to ask.
    pub fn wants_candidates(&self) -> bool {
        self.needs_helpers() && self.candidates.is_empty()
    }

    /// Starts a new test; the outcome of the last one stands meanwhile.
    pub fn recheck(&mut self, connected: bool, now: Instant) {
        self.round = self.round.saturating_add(1);
        self.running = 0;
        self.finished = 0;
        self.succeeded_once = false;
        self.started = connected.then_some(now);
        self.timed_out = false;
        self.last_firewalled = self.firewalled;
        self.candidates.clear();
        self.port_answerers.clear();
        self.ports_seen.clear();
        self.port_decided = false;
    }

    /// Runs every second: the test's time counts from when Kad is connected; one without an outcome times out.
    pub fn tick(&mut self, connected: bool, tcp_firewalled: bool, now: Instant) {
        if !self.is_running() {
            return;
        }
        if self.started.is_none() && connected {
            self.started = Some(now);
        }
        // A port proven open once is not lost to a timeout.
        if !self.timed_out
            && !self.firewalled
            && tcp_firewalled
            && !self.verified
            && self.started.is_some_and(|started| now.saturating_duration_since(started) >= TIMEOUT)
        {
            self.timed_out = true;
        }
    }

    /// A node told us the UDP port it saw us on: one sample per node, a port seen twice is the one.
    pub fn port_seen(&mut self, port: u16, from: u32) {
        if self.port_decided || self.port_answerers.contains(&from) {
            return;
        }
        self.port_answerers.push(from);
        if self.ports_seen.contains(&port) {
            self.external_port = port;
            self.port_decided = true;
            return;
        }
        self.ports_seen.push(port);
        if self.port_answerers.len() >= PORT_SAMPLES {
            // The nodes disagree: a NAT that changes the port for each of them cannot be used.
            self.external_port = 0;
            self.port_decided = true;
        }
    }

    /// A contact that a lookup for helpers found.
    pub fn candidate(&mut self, contact: Contact) {
        if !self.is_running() {
            return;
        }
        self.candidates.push_front(contact);
        self.candidates.truncate(MAX_CANDIDATES);
    }

    /// The next helper to ask, once the external port is known: a recent enough node that is not us, never asked
    /// before and `usable`; it counts as asked from now on.
    pub fn next_helper(
        &mut self,
        mut usable: impl FnMut(&Contact) -> bool,
        our_ip: u32,
        our_id: UInt128,
    ) -> Option<Contact> {
        if !self.needs_helpers() || self.finds_external_port() {
            return None;
        }
        while let Some(contact) = self.candidates.pop_front() {
            // Older nodes cannot help.
            if contact.version < 6
                || (our_ip != 0 && contact.ip == our_ip)
                || contact.id == our_id
                || self.asked.contains_key(&contact.ip)
                || !usable(&contact)
            {
                continue;
            }
            self.asked.insert(contact.ip, Asked { round: self.round, answered: false });
            while self.asked.len() > MAX_ASKED {
                self.asked.shift_remove_index(0);
            }
            self.running = self.running.saturating_add(1);
            return Some(contact);
        }
        None
    }

    /// Whether the helper at `ip` was asked and its outcome is awaited or came.
    pub fn asked(&self, ip: u32) -> bool {
        self.asked.contains_key(&ip)
    }

    /// The outcome a helper gave: `succeeded` when its datagram reached `port` of ours, `cancelled` when the helper
    /// could not test (it knew us, or the request never went). Returns whether another helper should be asked now.
    pub fn outcome(
        &mut self,
        ip: u32,
        succeeded: bool,
        cancelled: bool,
        port: u16,
        internal_port: u16,
        now: Instant,
    ) -> bool {
        let Some(helper) = self.asked.get(&ip).copied() else {
            return false;
        };
        // The helper's datagram to our own port came right after the one to the NAT's: our own port is better.
        if !self.is_running()
            && !self.firewalled
            && self.succeeded_once
            && self.last_success.is_some_and(|last| now.saturating_duration_since(last) < LATE_DATAGRAM)
            && succeeded
            && port == internal_port
            && self.use_external_port
        {
            self.use_external_port = false;
            return false;
        }
        if helper.answered || helper.round != self.round {
            return false;
        }
        self.asked.insert(ip, Asked { round: helper.round, answered: true });
        if !self.is_running() {
            return false;
        }
        self.running = self.running.saturating_sub(1);
        if cancelled {
            return true;
        }
        self.finished = self.finished.saturating_add(1);
        if succeeded {
            self.started = None;
            self.firewalled = false;
            self.verified = true;
            self.timed_out = false;
            self.finished = HELPERS;
            self.running = 0;
            self.candidates.clear();
            self.succeeded_once = true;
            self.last_success = Some(now);
            if port == internal_port {
                self.use_external_port = false;
            } else if port == self.external_port && port != 0 {
                self.use_external_port = true;
            }
            return false;
        }
        if self.finished >= HELPERS {
            self.started = None;
            self.firewalled = true;
            self.verified = true;
            self.timed_out = false;
            self.candidates.clear();
            return false;
        }
        true
    }

    fn needs_helpers(&self) -> bool {
        self.is_running() && self.running.saturating_add(self.finished) < HELPERS
    }
}
