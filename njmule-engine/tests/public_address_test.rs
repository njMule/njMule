// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Twin of Java's `PublicAddressTest`: the public address secure identification binds signatures to.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::net::Ipv4Addr;

use njmule_engine::engine::network::public_address;
use njmule_engine::protocol::ed2k::server_messages::IdChange;
use njmule_engine::util::ipv4;

fn ours() -> u32 {
    u32::from("198.51.100.7".parse::<Ipv4Addr>().unwrap())
}

fn seen_by_kad() -> u32 {
    u32::from("198.51.100.8".parse::<Ipv4Addr>().unwrap())
}

#[derive(Default)]
struct State {
    server: Option<IdChange>,
    kad: u32,
    address: Option<Ipv4Addr>,
}

impl State {
    fn given_a_server_that_gave_us(&mut self, id: IdChange) {
        self.server = Some(id);
    }

    fn given_kad_saw_us_at(&mut self, ip: u32) {
        self.kad = ip;
    }

    fn when_our_public_address_is_wanted(&mut self) {
        self.address = public_address(self.server.as_ref(), self.kad);
    }

    fn then_it_is(&self, expected: &str) {
        assert_eq!(self.address.map(|ip| ip.to_string()).as_deref(), Some(expected));
    }

    fn then_it_is_unknown(&self) {
        assert_eq!(self.address, None);
    }
}

#[test]
fn should_take_a_high_id_as_our_address() {
    let mut t = State::default();
    t.given_a_server_that_gave_us(IdChange { client_id: ipv4::to_ed2k(ours()), server_flags: 0, reported_ip: 0 });

    t.when_our_public_address_is_wanted();

    t.then_it_is("198.51.100.7");
}

#[test]
fn should_take_the_address_the_server_saw_as_ours_with_a_low_id() {
    let mut t = State::default();
    t.given_a_server_that_gave_us(IdChange { client_id: 1234, server_flags: 0, reported_ip: ours() });
    t.given_kad_saw_us_at(seen_by_kad());

    t.when_our_public_address_is_wanted();

    t.then_it_is("198.51.100.7");
}

#[test]
fn should_let_kad_tell_our_address_without_a_server() {
    let mut t = State::default();
    t.given_kad_saw_us_at(seen_by_kad());

    t.when_our_public_address_is_wanted();

    t.then_it_is("198.51.100.8");
}

#[test]
fn should_leave_our_address_unknown_with_a_low_id_from_a_server_that_does_not_say_it() {
    let mut t = State::default();
    t.given_a_server_that_gave_us(IdChange { client_id: 1234, server_flags: 0, reported_ip: 0 });

    t.when_our_public_address_is_wanted();

    t.then_it_is_unknown();
}
