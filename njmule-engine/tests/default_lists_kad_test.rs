// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Kad contacts shipped with njMule, asked from the start.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::HashSet;
use std::fs;
use std::time::Duration;

use njmule_engine::engine::default_lists;
use njmule_engine::engine::network::load_kad_contacts;
use njmule_engine::kad::kad_node::{self, KadNode};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::{self, Contact, UdpKey};
use njmule_engine::protocol::packet::opcodes::kad;

use crate::kad_lab::{self, KadLab, PORT, Sent, ip};

struct State {
    lab: KadLab,
    node: KadNode,
    default_lists: bool,
}

impl State {
    fn new() -> Self {
        let mut lab = KadLab::new();
        let mut settings = kad_lab::settings();
        settings.udp_port = PORT;
        let node = lab.node(settings);
        Self { lab, node, default_lists: true }
    }

    fn given_the_default_lists_are_disabled(&mut self) {
        self.default_lists = false;
    }

    fn given_a_nodes_dat_of_contacts_that_never_answer(&mut self) {
        let mut contacts = Vec::new();
        for i in 0..5u8 {
            contacts.push(Contact {
                id: UInt128::random(&mut self.lab.random),
                ip: ip(30 + i, 1, 1, 1),
                udp_port: PORT,
                tcp_port: 4662,
                version: 8,
                contact_type: 0,
                udp_key: UdpKey::default(),
                verified: true,
            });
        }
        fs::write(self.lab.dir.path().join(kad_node::NODES_FILE), nodes_dat::write(&contacts).unwrap()).unwrap();
    }

    fn given_kad_loads_its_contacts(&mut self) {
        load_kad_contacts(&mut self.node, self.default_lists);
    }

    fn when_seconds_pass(&mut self, seconds: u64) {
        for _ in 0..seconds {
            self.node.tick();
            self.lab.advance(Duration::from_secs(1));
        }
    }

    fn bootstrap_requests(&self) -> Vec<Sent> {
        self.lab.sent().into_iter().filter(|s| s.packet.opcode() == kad::BOOTSTRAP_REQ).collect()
    }

    fn then_only_shipped_contacts_were_asked(&self) {
        let shipped: HashSet<u32> = default_lists::nodes().contacts.into_iter().map(|c| c.ip).collect();
        let asked = self.bootstrap_requests();
        assert!(!asked.is_empty(), "expected bootstrap requests to shipped contacts");
        for sent in asked {
            assert!(shipped.contains(&sent.ip), "asked contact outside shipped list: {:08x}", sent.ip);
        }
    }

    fn then_no_contact_was_asked_to_bootstrap(&self) {
        assert!(self.bootstrap_requests().is_empty());
    }
}

#[test]
fn should_ask_the_shipped_contacts_at_once_without_a_nodes_dat() {
    let mut t = State::new();
    t.given_kad_loads_its_contacts();

    t.when_seconds_pass(1);

    t.then_only_shipped_contacts_were_asked();
}

#[test]
fn should_ask_the_shipped_contacts_at_once_next_to_a_nodes_dat() {
    let mut t = State::new();
    t.given_a_nodes_dat_of_contacts_that_never_answer();
    t.given_kad_loads_its_contacts();

    t.when_seconds_pass(1);

    t.then_only_shipped_contacts_were_asked();
}

#[test]
fn should_not_ask_the_shipped_contacts_when_the_default_lists_are_disabled() {
    let mut t = State::new();
    t.given_the_default_lists_are_disabled();
    t.given_a_nodes_dat_of_contacts_that_never_answer();
    t.given_kad_loads_its_contacts();

    t.when_seconds_pass(240);

    t.then_no_contact_was_asked_to_bootstrap();
}
