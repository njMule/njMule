// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's LookupStoppingTest, under its names: when a search or a publish stops sending, and when it ends.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::time::{Duration, Instant};

use njmule_engine::kad::kad_contact::KadContact;
use njmule_engine::kad::lookup::{Kind, Lookup, Requested, Requests};
use njmule_engine::kad::response_times::ResponseTimes;
use njmule_engine::kad::routing_table::RoutingTable;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::UdpKey;
use njmule_engine::protocol::kad::kad_messages::Contact;
use njmule_engine::util::ipv4;
use rand::SeedableRng;
use rand::rngs::StdRng;

const SECOND: Duration = Duration::from_secs(1);
const PORT: u16 = 4672;

// The addresses asked and requested, in order.
#[derive(Default)]
struct Sent(Vec<u32>);

impl Requests for Sent {
    fn ask(&mut self, _: UInt128, _: Kind, contact: &Contact, _: u8) {
        self.0.push(contact.ip);
    }

    fn request(&mut self, _: UInt128, _: Kind, contact: &Contact) -> Requested {
        self.0.push(contact.ip);
        Requested::Sent
    }
}

struct Stopping {
    random: StdRng,
    target: UInt128,
    clock: Instant,
    sent: Sent,
    times: ResponseTimes,
    lookup: Option<Lookup>,
    sent_before: usize,
}

impl Stopping {
    fn new() -> Self {
        let mut random = StdRng::from_rng(&mut rand::rng());
        let target = UInt128::random(&mut random);
        Self {
            random,
            target,
            clock: Instant::now(),
            sent: Sent::default(),
            times: ResponseTimes::new(),
            lookup: None,
            sent_before: 0,
        }
    }

    fn given_a_search_with_silent_nodes(&mut self, kind: Kind, nodes: u64) {
        let mut table = RoutingTable::new(UInt128::random(&mut self.random), ipv4::is_lan);
        for d in 1..=nodes {
            let mut contact = KadContact::new(UInt128::random(&mut self.random), ip(d), PORT, 4662, 8, self.clock);
            contact.set_verified(true);
            table.add(contact, false, UdpKey::default(), 0, self.clock);
        }
        assert!(table.size() >= usize::try_from(nodes.min(40)).unwrap());
        let mut lookup = Lookup::new(self.target, kind, ipv4::is_lan, self.clock);
        lookup.start(&table, &mut self.sent);
        self.lookup = Some(lookup);
    }

    fn given_seconds_passed(&mut self, seconds: u32) {
        self.pass_seconds(seconds);
    }

    fn given_values_arrived(&mut self, values: u32) {
        let clock = self.clock;
        self.lookup().value_received(values, clock);
    }

    fn given_the_closest_node_answered_with_closer_contacts(&mut self) {
        let closer = Contact {
            id: self.target,
            ip: u32::from_be_bytes([30, 0, 0, 1]),
            udp_port: PORT,
            tcp_port: 4662,
            version: 8,
        };
        let clock = self.clock;
        let lookup = self.lookup.as_mut().unwrap();
        lookup.answered(ip(1), PORT, &[closer], clock, &mut self.times, &mut self.sent);
    }

    fn given_a_node_answered_every_second_for(&mut self, seconds: u64) {
        self.answer_every_second_for(seconds);
    }

    fn when_seconds_pass(&mut self, seconds: u32) {
        self.pass_seconds(seconds);
    }

    fn when_a_node_answers_every_second_for(&mut self, seconds: u64) {
        self.answer_every_second_for(seconds);
    }

    fn then_it_still_sends(&self) {
        assert!(self.sent.0.len() > self.sent_before);
    }

    fn then_it_sent_nothing_more(&self) {
        assert_eq!(self.sent.0.len(), self.sent_before);
    }

    fn then_it_runs(&mut self) {
        assert!(!self.lookup().is_stopped());
    }

    fn then_it_has_ended(&mut self) {
        assert!(self.lookup().is_stopped());
    }

    fn pass_seconds(&mut self, seconds: u32) {
        self.sent_before = self.sent.0.len();
        for _ in 0..seconds {
            self.clock += SECOND;
            let clock = self.clock;
            self.lookup.as_mut().unwrap().tick(clock, &self.times, &mut self.sent);
        }
    }

    fn answer_every_second_for(&mut self, seconds: u64) {
        for _ in 0..seconds {
            self.clock += SECOND;
            let clock = self.clock;
            let lookup = self.lookup.as_mut().unwrap();
            lookup.answered(ip(1), PORT, &[], clock, &mut self.times, &mut self.sent);
            lookup.tick(clock, &self.times, &mut self.sent);
        }
    }

    fn lookup(&mut self) -> &mut Lookup {
        self.lookup.as_mut().unwrap()
    }
}

// Each node in a /24 of its own.
fn ip(d: u64) -> u32 {
    u32::from_be_bytes([20, (d >> 8 & 0xFF) as u8, (d & 0xFF) as u8, 1])
}

#[test]
fn should_still_send_in_the_first_twenty_five_seconds_of_a_search() {
    let mut stopping = Stopping::new();
    stopping.given_a_search_with_silent_nodes(Kind::Sources, 80);

    stopping.when_seconds_pass(25);

    stopping.then_it_still_sends();
}

#[test]
fn should_send_nothing_in_the_last_twenty_seconds_of_a_search() {
    let mut stopping = Stopping::new();
    stopping.given_a_search_with_silent_nodes(Kind::Sources, 80);
    stopping.given_seconds_passed(25);

    stopping.when_seconds_pass(14);

    stopping.then_it_sent_nothing_more();
    stopping.then_it_runs();
}

#[test]
fn should_end_a_search_fifteen_seconds_after_it_stopped_sending() {
    let mut stopping = Stopping::new();
    stopping.given_a_search_with_silent_nodes(Kind::Sources, 80);
    stopping.given_seconds_passed(25);
    stopping.given_seconds_passed(14);

    stopping.when_seconds_pass(1);

    stopping.then_it_has_ended();
}

#[test]
fn should_still_take_answers_once_it_has_all_it_wants() {
    let mut stopping = Stopping::new();
    stopping.given_a_search_with_silent_nodes(Kind::Keyword, 3);
    stopping.given_values_arrived(300);
    stopping.given_the_closest_node_answered_with_closer_contacts();

    stopping.when_seconds_pass(14);

    stopping.then_it_sent_nothing_more();
    stopping.then_it_runs();
}

#[test]
fn should_end_fifteen_seconds_after_it_has_all_it_wants() {
    let mut stopping = Stopping::new();
    stopping.given_a_search_with_silent_nodes(Kind::Keyword, 3);
    stopping.given_values_arrived(300);
    stopping.given_the_closest_node_answered_with_closer_contacts();
    stopping.given_seconds_passed(14);

    stopping.when_seconds_pass(1);

    stopping.then_it_has_ended();
}

#[test]
fn should_keep_a_publish_whose_nodes_answer_going_until_twenty_seconds_before_its_end() {
    let mut stopping = Stopping::new();
    stopping.given_a_search_with_silent_nodes(Kind::StoreSource, 2);

    stopping.when_a_node_answers_every_second_for(Kind::StoreSource.lifetime().as_secs() - 6);

    stopping.then_it_runs();
}

#[test]
fn should_end_a_publish_whose_nodes_answer_fifteen_seconds_after_it_stopped_sending() {
    let mut stopping = Stopping::new();
    stopping.given_a_search_with_silent_nodes(Kind::StoreSource, 2);
    stopping.given_a_node_answered_every_second_for(Kind::StoreSource.lifetime().as_secs() - 6);

    stopping.when_a_node_answers_every_second_for(1);

    stopping.then_it_has_ended();
}
