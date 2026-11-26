// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A change of our public IPv4 address as the server and Kad see it.

use njmule_engine::engine::address_watch::{AddressWatch, Reaction};

const OLD: u32 = u32::from_be_bytes([93, 1, 1, 1]);
const NEW: u32 = u32::from_be_bytes([93, 2, 2, 2]);
const OTHER: u32 = u32::from_be_bytes([93, 3, 3, 3]);

#[derive(Default)]
struct State {
    watch: AddressWatch,
    reaction: Option<Reaction>,
}

impl State {
    fn given_both_saw_us_at(&mut self, ip: u32) {
        let _ = self.watch.seen(ip, ip);
    }

    fn given_seen_by_the_server_and_kad_at(&mut self, server_ip: u32, kad_ip: u32) {
        let _ = self.watch.seen(server_ip, kad_ip);
    }

    fn when_seen_by_the_server_and_kad_at(&mut self, server_ip: u32, kad_ip: u32) {
        self.reaction = Some(self.watch.seen(server_ip, kad_ip));
    }

    fn then_the_reaction_is(&self, expected: Reaction) {
        assert_eq!(self.reaction, Some(expected));
    }

    fn then_the_agreed_address_is(&self, expected: u32) {
        assert_eq!(self.watch.agreed(), expected);
    }
}

#[test]
fn should_do_nothing_while_the_server_and_kad_never_agreed() {
    let mut t = State::default();
    t.when_seen_by_the_server_and_kad_at(OLD, OTHER);
    t.then_the_reaction_is(Reaction::None);
}

#[test]
fn should_have_kad_look_again_when_the_server_sees_us_at_a_new_address() {
    let mut t = State::default();
    t.given_both_saw_us_at(OLD);
    t.when_seen_by_the_server_and_kad_at(NEW, OLD);
    t.then_the_reaction_is(Reaction::RecheckKad);
}

#[test]
fn should_reconnect_to_the_server_when_kad_sees_us_at_a_new_address() {
    let mut t = State::default();
    t.given_both_saw_us_at(OLD);
    t.when_seen_by_the_server_and_kad_at(OLD, NEW);
    t.then_the_reaction_is(Reaction::ReconnectServer);
}

#[test]
fn should_act_on_a_new_address_once() {
    let mut t = State::default();
    t.given_both_saw_us_at(OLD);
    t.given_seen_by_the_server_and_kad_at(NEW, OLD);
    t.when_seen_by_the_server_and_kad_at(NEW, OLD);
    t.then_the_reaction_is(Reaction::None);
}

#[test]
fn should_do_nothing_when_kad_sees_a_new_address_without_a_server() {
    let mut t = State::default();
    t.given_both_saw_us_at(OLD);
    t.when_seen_by_the_server_and_kad_at(0, NEW);
    t.then_the_reaction_is(Reaction::None);
}

#[test]
fn should_take_the_new_address_once_both_see_it() {
    let mut t = State::default();
    t.given_both_saw_us_at(OLD);
    t.given_seen_by_the_server_and_kad_at(NEW, OLD);
    t.when_seen_by_the_server_and_kad_at(NEW, NEW);
    t.then_the_agreed_address_is(NEW);
}
