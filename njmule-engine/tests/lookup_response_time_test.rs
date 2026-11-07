// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's LookupResponseTimeTest, under its names: a lookup waits for silent nodes as long as nodes have
//! lately taken to answer, three seconds at most, and times the answers of the nodes it asked.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::time::{Duration, Instant};

use njmule_engine::kad::kad_contact::KadContact;
use njmule_engine::kad::lookup::{Kind, Lookup, Requested, Requests};
use njmule_engine::kad::response_times::ResponseTimes;
use njmule_engine::kad::routing_table::RoutingTable;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::UdpKey;
use njmule_engine::protocol::kad::kad_messages::Contact;
use rand::SeedableRng;
use rand::rngs::StdRng;

const SECOND: Duration = Duration::from_secs(1);
const PORT: u16 = 4672;

// The addresses asked.
#[derive(Default)]
struct Asked(Vec<u32>);

impl Requests for Asked {
    fn ask(&mut self, _: UInt128, _: Kind, contact: &Contact, _: u8) {
        self.0.push(contact.ip);
    }

    fn request(&mut self, _: UInt128, _: Kind, _: &Contact) -> Requested {
        Requested::Sent
    }
}

struct ResponseTime {
    random: StdRng,
    target: UInt128,
    times: ResponseTimes,
    asked: Asked,
    clock: Instant,
    lookup: Option<Lookup>,
}

impl ResponseTime {
    fn new() -> Self {
        let mut random = StdRng::from_rng(&mut rand::rng());
        let target = UInt128::random(&mut random);
        Self {
            random,
            target,
            times: ResponseTimes::new(),
            asked: Asked::default(),
            clock: Instant::now(),
            lookup: None,
        }
    }

    fn given_nodes_answered_in(&mut self, nodes: u8, took: Duration) {
        for at in 0..nodes {
            self.times.answered(u32::from_be_bytes([50, at, 1, 1]), took);
        }
    }

    fn given_a_lookup_of_silent_nodes(&mut self, kind: Kind) {
        let mut table = RoutingTable::new(UInt128::random(&mut self.random), |_| false);
        for at in 1..=20 {
            let ip = u32::from_be_bytes([20, at, 1, 1]);
            let mut contact = KadContact::new(UInt128::random(&mut self.random), ip, PORT, 4662, 8, self.clock);
            contact.set_verified(true);
            table.add(contact, false, UdpKey::default(), 0, self.clock);
        }
        let mut lookup = Lookup::new(self.target, kind, |_| false, self.clock);
        lookup.start(&table, &mut self.asked);
        self.lookup = Some(lookup);
    }

    fn given_seconds_passed(&mut self, seconds: u32) {
        self.tick(seconds);
    }

    fn when_seconds_pass(&mut self, seconds: u32) {
        self.tick(seconds);
    }

    fn when_the_asked_nodes_answer_after(&mut self, took: Duration) {
        let at = self.clock + took;
        let lookup = self.lookup.as_mut().unwrap();
        for ip in self.asked.0.clone() {
            lookup.answered(ip, PORT, &[], at, &mut self.times, &mut self.asked);
        }
    }

    fn then_nodes_asked(&self, count: usize) {
        assert_eq!(self.asked.0.len(), count);
    }

    fn then_answers_timed(&self, count: usize) {
        assert_eq!(self.times.samples(), count);
    }

    fn tick(&mut self, seconds: u32) {
        for _ in 0..seconds {
            self.clock += SECOND;
            let clock = self.clock;
            self.lookup.as_mut().unwrap().tick(clock, &self.times, &mut self.asked);
        }
    }
}

#[test]
fn should_move_on_sooner_while_nodes_answer_fast() {
    let mut lookup = ResponseTime::new();
    lookup.given_nodes_answered_in(100, Duration::from_millis(50));
    lookup.given_a_lookup_of_silent_nodes(Kind::Keyword);

    lookup.when_seconds_pass(1);

    lookup.then_nodes_asked(4);
}

#[test]
fn should_still_wait_after_two_seconds_while_nodes_answer_slowly() {
    let mut lookup = ResponseTime::new();
    lookup.given_nodes_answered_in(100, Duration::from_secs(10));
    lookup.given_a_lookup_of_silent_nodes(Kind::Keyword);

    lookup.when_seconds_pass(2);

    lookup.then_nodes_asked(3);
}

#[test]
fn should_wait_no_longer_than_three_seconds_while_nodes_answer_slowly() {
    let mut lookup = ResponseTime::new();
    lookup.given_nodes_answered_in(100, Duration::from_secs(10));
    lookup.given_a_lookup_of_silent_nodes(Kind::Keyword);
    lookup.given_seconds_passed(2);

    lookup.when_seconds_pass(1);

    lookup.then_nodes_asked(4);
}

#[test]
fn should_wait_three_seconds_until_enough_answers_were_timed() {
    let mut lookup = ResponseTime::new();
    lookup.given_nodes_answered_in(5, Duration::from_millis(50));
    lookup.given_a_lookup_of_silent_nodes(Kind::Keyword);

    lookup.when_seconds_pass(2);

    lookup.then_nodes_asked(3);
}

#[test]
fn should_always_give_a_publish_three_seconds() {
    let mut lookup = ResponseTime::new();
    lookup.given_nodes_answered_in(100, Duration::from_millis(50));
    lookup.given_a_lookup_of_silent_nodes(Kind::StoreKeyword);

    lookup.when_seconds_pass(2);

    lookup.then_nodes_asked(3);
}

#[test]
fn should_time_the_answers_of_the_nodes_it_asked() {
    let mut lookup = ResponseTime::new();
    lookup.given_a_lookup_of_silent_nodes(Kind::Keyword);

    lookup.when_the_asked_nodes_answer_after(Duration::from_millis(200));

    lookup.then_answers_timed(3);
}
