// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's LookupTest, under its names: whom a lookup asks, whose answers it hears, when it moves on and
//! when it ends, on a clock the tests move a second at a time.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::net::Ipv4Addr;
use std::time::{Duration, Instant};

use njmule_engine::kad::kad_contact::KadContact;
use njmule_engine::kad::lookup::{self, Kind, Lookup, Requested, Requests};
use njmule_engine::kad::response_times::ResponseTimes;
use njmule_engine::kad::routing_table::{Outcome, RoutingTable};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::UdpKey;
use njmule_engine::protocol::kad::kad_messages::Contact;
use njmule_engine::util::ipv4;
use rand::SeedableRng;
use rand::rngs::StdRng;

const SECOND: Duration = Duration::from_secs(1);
const PORT: u16 = 4672;
const TOLERANCE: u64 = lookup::TOLERANCE as u64;

// The asks, as `address/count`, and the requests a lookup sends.
#[derive(Default)]
struct Sent {
    asks: Vec<String>,
    requested: Vec<Contact>,
}

impl Requests for Sent {
    fn ask(&mut self, _: UInt128, _: Kind, contact: &Contact, count: u8) {
        self.asks.push(format!("{}/{count}", Ipv4Addr::from(contact.ip)));
    }

    fn request(&mut self, _: UInt128, _: Kind, contact: &Contact) -> Requested {
        self.requested.push(*contact);
        Requested::Sent
    }
}

struct Lookups {
    t0: Instant,
    clock: Instant,
    target: UInt128,
    sent: Sent,
    times: ResponseTimes,
    lookup: Option<Lookup>,
}

impl Lookups {
    fn new() -> Self {
        let t0 = Instant::now();
        let target = UInt128::random(&mut StdRng::from_rng(&mut rand::rng()));
        Self { t0, clock: t0, target, sent: Sent::default(), times: ResponseTimes::new(), lookup: None }
    }

    fn given_a_lookup(&mut self, kind: Kind, distances: &[u64]) {
        self.start(kind, distances);
    }

    fn given_it_asked_nodes(&self, count: usize) {
        assert_eq!(self.sent.asks.len(), count);
    }

    fn given_the_node_answered(&mut self, node: u64) {
        self.answer(ip(node), PORT, &[]);
    }

    fn given_the_clock_at_second(&mut self, second: u64) {
        self.tick_until(second);
    }

    fn given_the_clock_runs_until_it_asked_nodes(&mut self, count: usize) {
        while self.sent.asks.len() < count {
            let next = self.seconds() + 1;
            self.tick_until(next);
        }
    }

    fn given_the_asks_so_far_forgotten(&mut self) {
        self.sent.asks.clear();
    }

    fn given_the_ignored_answers_of_a_stranger_another_port_and_too_many_contacts(&mut self) {
        let closer = ip4(30, 0, 0, 1);
        let one = [self.contact_at(distance(1), closer)];
        self.answer(ip(100), PORT + 1, &one);
        self.answer(ip4(40, 0, 0, 1), PORT, &one);
        let three = [
            self.contact_at(distance(1), closer),
            self.contact_at(distance(2), ip4(31, 0, 0, 1)),
            self.contact_at(distance(3), ip4(32, 0, 0, 1)),
        ];
        self.answer(ip(100), PORT, &three);
        assert_eq!(self.sent.asks.len(), 3, "a stranger, another port, too many contacts");
    }

    fn when_a_lookup_starts(&mut self, kind: Kind, distances: &[u64]) {
        self.start(kind, distances);
    }

    fn when_the_clock_reaches_second(&mut self, second: u64) {
        self.tick_until(second);
    }

    fn when_the_clock_runs_for_seconds(&mut self, seconds: u64) {
        let until = self.seconds() + seconds;
        self.tick_until(until);
    }

    fn when_the_node_answers(&mut self, node: u64, contacts: &[Contact]) {
        self.answer(ip(node), PORT, contacts);
    }

    fn when_an_answer_comes_from(&mut self, ip: u32, port: u16, contacts: &[Contact]) {
        self.answer(ip, port, contacts);
    }

    fn then_the_lookup_goes_on(&self, why: &str) {
        assert!(!self.lookup().is_stopped(), "{why}");
    }

    fn then_the_lookup_is_stopped(&self, why: &str) {
        assert!(self.lookup().is_stopped(), "{why}");
    }

    fn then_the_node_is_asked_for(&self, node: u64, count: u8) {
        assert!(self.sent.asks.contains(&format!("{}/{count}", Ipv4Addr::from(ip(node)))), "{:?}", self.sent.asks);
    }

    fn then_only_the_node_is_asked_for(&self, node: u64, count: u8) {
        assert_eq!(self.sent.asks, [format!("{}/{count}", Ipv4Addr::from(ip(node)))]);
    }

    fn then_the_node_gets_the_request(&self, node: u64) {
        assert!(self.sent.requested.iter().any(|contact| contact.ip == ip(node)));
    }

    fn then_only_the_node_gets_the_request(&self, node: u64) {
        assert_eq!(self.sent.requested.iter().map(|contact| contact.ip).collect::<Vec<_>>(), [ip(node)]);
    }

    // Only the node asked again may send that many contacts.
    fn then_no_node_is_asked_at(&self, ip: u32) {
        let address = format!("{}/", Ipv4Addr::from(ip));
        assert!(!self.sent.asks.iter().any(|ask| ask.starts_with(&address)), "{:?}", self.sent.asks);
    }

    fn then_no_node_is_asked_for_more(&self) {
        assert!(!self.sent.asks.iter().any(|ask| ask.ends_with("/11")), "{:?}", self.sent.asks);
    }

    fn then_no_node_is_asked(&self) {
        assert!(self.sent.asks.is_empty());
    }

    fn then_the_number_of_asks_is(&self, count: usize, why: &str) {
        assert_eq!(self.sent.asks.len(), count, "{why}");
    }

    fn start(&mut self, kind: Kind, distances: &[u64]) {
        let mut random = StdRng::from_rng(&mut rand::rng());
        let mut table = RoutingTable::new(UInt128::random(&mut random), ipv4::is_lan);
        for d in distances {
            let mut contact = KadContact::new(self.target ^ distance(*d), ip(*d), PORT, 4662, 8, self.t0);
            contact.set_verified(true);
            assert_eq!(table.add(contact, false, UdpKey::default(), 0, self.t0), Outcome::Added);
        }
        let mut started = Lookup::new(self.target, kind, ipv4::is_lan, self.t0);
        started.start(&table, &mut self.sent);
        self.lookup = Some(started);
    }

    fn answer(&mut self, ip: u32, port: u16, contacts: &[Contact]) {
        let clock = self.clock;
        let lookup = self.lookup.as_mut().unwrap();
        lookup.answered(ip, port, contacts, clock, &mut self.times, &mut self.sent);
    }

    // Ticks once a second up to `second` seconds after the start.
    fn tick_until(&mut self, second: u64) {
        while self.clock < self.t0 + SECOND * u32::try_from(second).unwrap() {
            self.clock += SECOND;
            let clock = self.clock;
            self.lookup.as_mut().unwrap().tick(clock, &self.times, &mut self.sent);
        }
    }

    fn seconds(&self) -> u64 {
        (self.clock - self.t0).as_secs()
    }

    fn contact_at(&self, distance: UInt128, ip: u32) -> Contact {
        Contact { id: self.target ^ distance, ip, udp_port: PORT, tcp_port: 4662, version: 8 }
    }

    fn lookup(&self) -> &Lookup {
        self.lookup.as_ref().unwrap()
    }
}

const fn ip4(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from_be_bytes([a, b, c, d])
}

// Each node in a /24 of its own; small distances are within the request tolerance.
fn ip(d: u64) -> u32 {
    ip4(20, (d >> 8 & 0xFF) as u8, (d & 0xFF) as u8, 1)
}

fn in_the_subnet_of(node: u64, host: u32) -> u32 {
    ip(node) & 0xFFFF_FF00 | host
}

fn distance(d: u64) -> UInt128 {
    UInt128(u128::from(d) << 96)
}

#[test]
fn should_keep_a_silent_search_waiting_for_late_answers_after_the_stall() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::Keyword, &[1, 2, 3]);
    lookups.given_it_asked_nodes(3);

    lookups.when_the_clock_reaches_second(3);

    lookups.then_the_lookup_goes_on("waiting for late answers");
}

#[test]
fn should_keep_a_silent_search_going_until_seventeen_seconds() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::Keyword, &[1, 2, 3]);
    lookups.given_it_asked_nodes(3);

    lookups.when_the_clock_reaches_second(17);

    lookups.then_the_lookup_goes_on("until 17 s");
}

#[test]
fn should_end_a_silent_search_after_the_late_answer_wait() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::Keyword, &[1, 2, 3]);
    lookups.given_it_asked_nodes(3);

    lookups.when_the_clock_reaches_second(18);

    lookups.then_the_lookup_is_stopped("15 s after the stall, not at the end of its 45 s");
}

#[test]
fn should_keep_a_silent_node_lookup_going_until_the_stall() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::NodeComplete, &[1, 2]);

    lookups.when_the_clock_reaches_second(2);

    lookups.then_the_lookup_goes_on("before the stall");
}

#[test]
fn should_end_a_silent_node_lookup_at_the_stall() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::NodeComplete, &[1, 2]);

    lookups.when_the_clock_reaches_second(3);

    lookups.then_the_lookup_is_stopped("at the stall");
}

#[test]
fn should_ask_the_closest_node_that_answered_for_more_when_the_two_closest_are_silent() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::Keyword, &[1, 2, 3, 4, 5, 6, 7, 8]);
    // The third closest answers, the two closest stay silent.
    lookups.given_the_node_answered(3);

    lookups.when_the_clock_reaches_second(10);

    lookups.then_the_node_is_asked_for(3, 11);
    lookups.then_the_node_gets_the_request(3);
}

#[test]
fn should_take_that_many_contacts_only_from_the_node_asked_again() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::Keyword, &[1, 2, 3, 4, 5, 6, 7, 8]);
    lookups.given_the_node_answered(3);
    lookups.given_the_clock_at_second(10);

    lookups.when_the_node_answers(
        4,
        &[
            lookups.contact_at(UInt128((2u128 << 32 | 1) << 64), ip4(30, 0, 0, 1)),
            lookups.contact_at(UInt128((2u128 << 32 | 2) << 64), ip4(31, 0, 0, 1)),
            lookups.contact_at(UInt128((2u128 << 32 | 3) << 64), ip4(32, 0, 0, 1)),
        ],
    );

    lookups.then_no_node_is_asked_at(ip4(30, 0, 0, 1));
}

#[test]
fn should_never_ask_a_node_again_for_more_in_a_publish() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::StoreKeyword, &[1, 2, 3, 4, 5, 6, 7, 8]);
    // Six nodes asked, then the sixth answers while it still waits for its publish.
    lookups.given_the_clock_runs_until_it_asked_nodes(6);
    lookups.given_the_node_answered(6);

    lookups.when_the_clock_runs_for_seconds(10);

    lookups.then_no_node_is_asked_for_more();
}

#[test]
fn should_not_let_an_answer_fill_the_responders_own_subnet() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::NodeComplete, &[100, 101, 102]);

    lookups.when_the_node_answers(
        100,
        &[
            lookups.contact_at(distance(1), in_the_subnet_of(100, 0x10)),
            lookups.contact_at(distance(2), in_the_subnet_of(100, 0x11)),
        ],
    );

    lookups.then_the_number_of_asks_is(4, "one more of the responder's /24 at most");
}

#[test]
fn should_not_hold_lan_addresses_to_the_subnet_rule() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::NodeComplete, &[100, 101, 102]);
    lookups.given_the_asks_so_far_forgotten();

    lookups.when_the_node_answers(
        100,
        &[
            lookups.contact_at(distance(1), ip4(192, 168, 1, 1)),
            lookups.contact_at(distance(2), ip4(192, 168, 1, 2)),
            lookups.contact_at(distance(3), ip4(192, 168, 1, 3)),
        ],
    );

    lookups.then_the_number_of_asks_is(3, "LAN contacts are not held to the /24 rule");
}

#[test]
fn should_end_a_lookup_without_contacts_at_once() {
    let mut lookups = Lookups::new();

    lookups.when_a_lookup_starts(Kind::Keyword, &[]);

    lookups.then_the_lookup_is_stopped("no contacts");
    lookups.then_no_node_is_asked();
}

#[test]
fn should_ask_one_node_in_a_node_lookup() {
    let mut lookups = Lookups::new();

    lookups.when_a_lookup_starts(Kind::Node, &[1, 2, 3]);

    lookups.then_only_the_node_is_asked_for(1, 11);
}

#[test]
fn should_end_a_node_lookup_with_the_answer_of_its_node() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::Node, &[1, 2, 3]);

    lookups.when_the_node_answers(1, &[lookups.contact_at(distance(0), ip4(30, 0, 0, 1))]);

    lookups.then_the_lookup_is_stopped("its node answered");
}

#[test]
fn should_not_hear_an_asked_node_from_another_port() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::Keyword, &[100, 101, 102]);

    lookups.when_an_answer_comes_from(ip(100), PORT + 1, &[lookups.contact_at(distance(1), ip4(30, 0, 0, 1))]);

    lookups.then_the_number_of_asks_is(3, "another port");
}

#[test]
fn should_not_hear_a_stranger() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::Keyword, &[100, 101, 102]);

    lookups.when_an_answer_comes_from(ip4(40, 0, 0, 1), PORT, &[lookups.contact_at(distance(1), ip4(30, 0, 0, 1))]);

    lookups.then_the_number_of_asks_is(3, "a stranger");
}

#[test]
fn should_not_hear_an_answer_with_more_contacts_than_asked() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::Keyword, &[100, 101, 102]);

    lookups.when_the_node_answers(
        100,
        &[
            lookups.contact_at(distance(1), ip4(30, 0, 0, 1)),
            lookups.contact_at(distance(2), ip4(31, 0, 0, 1)),
            lookups.contact_at(distance(3), ip4(32, 0, 0, 1)),
        ],
    );

    lookups.then_the_number_of_asks_is(3, "too many contacts");
}

#[test]
fn should_ask_the_same_contact_only_once() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::Keyword, &[100, 101, 102]);
    lookups.given_the_ignored_answers_of_a_stranger_another_port_and_too_many_contacts();

    lookups.when_the_node_answers(
        100,
        &[lookups.contact_at(distance(1), ip4(30, 0, 0, 1)), lookups.contact_at(distance(1), ip4(31, 0, 0, 1))],
    );

    lookups.then_the_number_of_asks_is(4, "the same contact twice is asked once");
}

#[test]
fn should_request_only_nodes_within_the_tolerance() {
    let mut lookups = Lookups::new();
    lookups.given_a_lookup(Kind::Keyword, &[TOLERANCE, TOLERANCE + 1]);
    lookups.given_the_node_answered(TOLERANCE);
    lookups.given_the_node_answered(TOLERANCE + 1);

    lookups.when_the_clock_reaches_second(4);

    lookups.then_only_the_node_gets_the_request(TOLERANCE);
}
