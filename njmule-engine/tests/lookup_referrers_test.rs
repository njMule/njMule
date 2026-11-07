// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's LookupReferrersTest, under its names: a search goes first to the nodes two others vouch for, and
//! never to one two others place at different addresses.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::sync::Arc;
use std::time::{Duration, Instant};

use njmule_engine::kad::kad_contact::KadContact;
use njmule_engine::kad::lookup::{Kind, Lookup, Requested, Requests};
use njmule_engine::kad::response_times::ResponseTimes;
use njmule_engine::kad::routing_table::{Outcome, RoutingTable};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::UdpKey;
use njmule_engine::protocol::kad::kad_messages::Contact;
use rand::SeedableRng;
use rand::rngs::StdRng;

const SECOND: Duration = Duration::from_secs(1);
const PORT: u16 = 4672;
// Nodes of our table: three far from the target, one near it.
const FIRST: u8 = 1;
const SECOND_NODE: u8 = 2;
const OURS_NEAR: u8 = 4;
// Nodes the answers tell of, near the target.
const NEAR: u8 = 11;
const NEARER: u8 = 12;
const ELSEWHERE: u32 = u32::from_be_bytes([60, 1, 1, 1]);

// The addresses asked, and those sent the search.
#[derive(Default)]
struct Sent {
    asked: Vec<u32>,
    requested: Vec<u32>,
}

impl Requests for Sent {
    fn ask(&mut self, _: UInt128, _: Kind, contact: &Contact, _: u8) {
        self.asked.push(contact.ip);
    }

    fn request(&mut self, _: UInt128, _: Kind, contact: &Contact) -> Requested {
        self.requested.push(contact.ip);
        Requested::Sent
    }
}

struct Referrers {
    target: UInt128,
    sent: Sent,
    times: ResponseTimes,
    clock: Instant,
    lookup: Lookup,
}

impl Referrers {
    fn new() -> Self {
        let mut random = StdRng::from_rng(&mut rand::rng());
        let target = UInt128::random(&mut random);
        let clock = Instant::now();
        let mut table = RoutingTable::new(UInt128::random(&mut random), |_| false);
        for node in [FIRST, SECOND_NODE, 3, OURS_NEAR] {
            let mut contact = KadContact::new(id_of(target, node), ip(node), PORT, 4662, 8, clock);
            contact.set_verified(true);
            assert_eq!(table.add(contact, false, UdpKey::default(), 0, clock), Outcome::Added);
        }
        let mut sent = Sent::default();
        let mut lookup = Lookup::new(target, Kind::Keyword, Arc::new(|_| false), clock);
        lookup.start(&table, &mut sent);
        Self { target, sent, times: ResponseTimes::new(), clock, lookup }
    }

    fn given_a_node_tells(&mut self, responder: u8, contacts: &[Contact]) {
        self.lookup.answered(ip(responder), PORT, contacts, self.clock, &mut self.times, &mut self.sent);
    }

    fn given_these_nodes_answered(&mut self, nodes: &[u8]) {
        for node in nodes {
            self.lookup.answered(ip(*node), PORT, &[], self.clock, &mut self.times, &mut self.sent);
        }
    }

    fn given_the_search_went_after_seconds_only_to(&mut self, seconds: u32, ips: &[u32]) {
        self.when_seconds_pass(seconds);
        self.then_the_search_went_to(ips);
    }

    fn when_seconds_pass(&mut self, seconds: u32) {
        for _ in 0..seconds {
            self.clock += SECOND;
            self.lookup.tick(self.clock, &self.times, &mut self.sent);
        }
    }

    fn then_the_search_went_to(&self, ips: &[u32]) {
        assert_eq!(self.sent.requested, ips);
    }

    fn then_the_search_never_went_to(&self, ip: u32) {
        assert!(!self.sent.requested.contains(&ip));
    }

    fn then_no_one_asked(&self, ip: u32) {
        assert!(!self.sent.asked.contains(&ip));
    }

    fn contact(&self, node: u8, ip: u32) -> Contact {
        Contact { id: id_of(self.target, node), ip, udp_port: PORT, tcp_port: 4662, version: 8 }
    }
}

// The nodes of our table below OURS_NEAR are beyond the request tolerance; the others within, nearer the higher.
fn id_of(target: UInt128, node: u8) -> UInt128 {
    let top = if node < OURS_NEAR { (1u128 << 28) + u128::from(node) } else { 100 - u128::from(node) };
    target ^ UInt128(top << 96)
}

fn ip(node: u8) -> u32 {
    u32::from_be_bytes([20, node, 1, 1])
}

#[test]
fn should_send_the_search_first_to_a_node_two_others_vouch_for() {
    let mut referrers = Referrers::new();
    referrers.given_a_node_tells(FIRST, &[referrers.contact(NEAR, ip(NEAR)), referrers.contact(NEARER, ip(NEARER))]);
    referrers.given_a_node_tells(SECOND_NODE, &[referrers.contact(NEAR, ip(NEAR))]);
    referrers.given_these_nodes_answered(&[NEAR, NEARER]);

    referrers.when_seconds_pass(3);

    referrers.then_the_search_went_to(&[ip(NEAR)]);
}

#[test]
fn should_send_the_search_to_a_node_a_single_other_knows_of_only_at_the_end() {
    let mut referrers = Referrers::new();
    referrers.given_a_node_tells(FIRST, &[referrers.contact(NEAR, ip(NEAR)), referrers.contact(NEARER, ip(NEARER))]);
    referrers.given_a_node_tells(SECOND_NODE, &[referrers.contact(NEAR, ip(NEAR))]);
    referrers.given_these_nodes_answered(&[NEAR, NEARER]);
    referrers.given_the_search_went_after_seconds_only_to(3, &[ip(NEAR)]);

    referrers.when_seconds_pass(2);

    referrers.then_the_search_went_to(&[ip(NEAR), ip(NEARER)]);
}

#[test]
fn should_never_send_the_search_to_a_node_two_others_place_at_different_addresses() {
    let mut referrers = Referrers::new();
    referrers.given_a_node_tells(FIRST, &[referrers.contact(NEAR, ip(NEAR))]);
    referrers.given_a_node_tells(SECOND_NODE, &[referrers.contact(NEAR, ELSEWHERE)]);
    referrers.given_these_nodes_answered(&[NEAR]);

    referrers.when_seconds_pass(30);

    referrers.then_the_search_never_went_to(ip(NEAR));
    referrers.then_no_one_asked(ELSEWHERE);
}

#[test]
fn should_keep_the_address_of_our_own_contact() {
    let mut referrers = Referrers::new();
    referrers.given_a_node_tells(FIRST, &[referrers.contact(OURS_NEAR, ELSEWHERE)]);
    referrers.given_these_nodes_answered(&[OURS_NEAR]);

    referrers.when_seconds_pass(4);

    referrers.then_the_search_went_to(&[ip(OURS_NEAR)]);
    referrers.then_no_one_asked(ELSEWHERE);
}
