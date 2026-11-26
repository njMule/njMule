// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The server list and Kad bootstrap contacts shipped with njMule.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::net::Ipv4Addr;

use njmule_engine::engine::default_lists;
use njmule_engine::persist::format::nodes_dat::Contents;
use njmule_engine::persist::format::server_met::Server;
use njmule_engine::util::ipv4;

#[derive(Default)]
struct State {
    servers: Vec<Server>,
    nodes: Option<Contents>,
}

impl State {
    fn when_the_shipped_servers_are_read(&mut self) {
        self.servers = default_lists::servers();
    }

    fn when_the_shipped_contacts_are_read(&mut self) {
        self.nodes = Some(default_lists::nodes());
    }

    fn then_there_are_servers(&self) {
        assert!(self.servers.len() >= 5, "expected at least 5 shipped servers, got {}", self.servers.len());
    }

    fn then_every_server_has_a_public_address_and_a_port(&self) {
        for s in &self.servers {
            assert!(
                ipv4::is_unicast_public_candidate(s.ip) && !ipv4::is_lan(s.ip),
                "unexpected server ip {}",
                Ipv4Addr::from(s.ip)
            );
            assert!(s.port > 0, "server port must be positive");
        }
    }

    fn then_they_are_only_to_bootstrap_from(&self) {
        let nodes = self.nodes.as_ref().unwrap();
        assert!(nodes.bootstrap);
        assert!(nodes.contacts.len() >= 50, "expected at least 50 bootstrap contacts, got {}", nodes.contacts.len());
    }

    fn then_every_contact_has_a_public_address_and_a_port(&self) {
        for c in &self.nodes.as_ref().unwrap().contacts {
            assert!(
                ipv4::is_unicast_public_candidate(c.ip) && !ipv4::is_lan(c.ip),
                "unexpected contact ip {}",
                Ipv4Addr::from(c.ip)
            );
            assert!(c.udp_port > 0, "contact udp_port must be positive");
        }
    }
}

#[test]
fn should_ship_only_servers_on_public_addresses() {
    let mut t = State::default();
    t.when_the_shipped_servers_are_read();
    t.then_there_are_servers();
    t.then_every_server_has_a_public_address_and_a_port();
}

#[test]
fn should_ship_contacts_only_to_bootstrap_from() {
    let mut t = State::default();
    t.when_the_shipped_contacts_are_read();
    t.then_they_are_only_to_bootstrap_from();
    t.then_every_contact_has_a_public_address_and_a_port();
}
