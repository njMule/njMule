// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's RoutingTableTest, under its names: which contacts the routing table takes, updates and gives as
//! the closest, its shape while contacts come and go, and how many nodes it guesses the network holds.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::{HashMap, HashSet};
use std::net::Ipv4Addr;
use std::time::Instant;

use njmule_engine::kad::kad_contact::KadContact;
use njmule_engine::kad::routing_table::{self, Outcome, RoutingTable};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::UdpKey;
use njmule_engine::util::ipv4;
use proptest::prelude::*;
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};

const PORT: u16 = 4672;

const fn ip(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from_be_bytes([a, b, c, d])
}

#[derive(Clone, Copy)]
enum Asked {
    Verified,
    Unverified,
    Unknown,
}

struct Table {
    random: StdRng,
    base: Instant,
    table: RoutingTable,
    full_subnet: Vec<UInt128>,
    moving: UInt128,
    moved_from: u32,
    first: UInt128,
    second: UInt128,
    old_client: UInt128,
    old_client_ip: u32,
    removed: UInt128,
    removed_ip: u32,
    verified: UInt128,
    unverified: UInt128,
    target: UInt128,
    exact: UInt128,
    other: UInt128,
    lan_table: Option<RoutingTable>,
    lan_added: usize,
    outcome: Option<Outcome>,
    closest: Vec<UInt128>,
    acceptable: bool,
    walk: Option<StdRng>,
    walked: Option<RoutingTable>,
    known: Vec<UInt128>,
    // Centred on zero, so that a contact's id is its distance from us.
    centred: RoutingTable,
    centred_added: u32,
    estimate: u64,
    share: f64,
}

impl Table {
    fn new() -> Self {
        let mut random = StdRng::from_rng(&mut rand::rng());
        let own = UInt128::random(&mut random);
        Self {
            random,
            base: Instant::now(),
            table: RoutingTable::new(own, |_| false),
            full_subnet: Vec::new(),
            moving: UInt128::ZERO,
            moved_from: 0,
            first: UInt128::ZERO,
            second: UInt128::ZERO,
            old_client: UInt128::ZERO,
            old_client_ip: 0,
            removed: UInt128::ZERO,
            removed_ip: 0,
            verified: UInt128::ZERO,
            unverified: UInt128::ZERO,
            target: UInt128::ZERO,
            exact: UInt128::ZERO,
            other: UInt128::ZERO,
            lan_table: None,
            lan_added: 0,
            outcome: None,
            closest: Vec::new(),
            acceptable: false,
            walk: None,
            walked: None,
            known: Vec::new(),
            centred: RoutingTable::new(UInt128::ZERO, |_| false),
            centred_added: 0,
            estimate: 0,
            share: 0.0,
        }
    }

    fn given_a_full_subnet(&mut self, range: u32) {
        self.full_subnet = self.fill_subnet(range);
        assert_eq!(self.full_subnet.len(), 10);
    }

    fn given_the_full_subnet_refuses_a_new_contact_at(&mut self, ip: u32) {
        let contact = self.contact(ip);
        assert_eq!(self.add(contact, false), Outcome::Refused, "the /24 is full");
    }

    fn given_two_contacts_at(&mut self, first_ip: u32, second_ip: u32) {
        let first = self.contact(first_ip);
        let second = self.contact(second_ip);
        (self.first, self.second) = (first.id(), second.id());
        self.add(first, false);
        self.add(second, false);
    }

    fn given_an_old_client_that_said_hello_at(&mut self, ip: u32) {
        let old_client = KadContact::new(UInt128::random(&mut self.random), ip, PORT, 4662, 5, self.base);
        (self.old_client, self.old_client_ip) = (old_client.id(), ip);
        self.add(old_client, false);
        self.table.get_mut(&self.old_client).unwrap().set_hello_received();
    }

    fn given_the_old_client_refused_with_the_port(&mut self, port: u16) {
        let contact = KadContact::new(self.old_client, self.old_client_ip, port, 4662, 5, self.base);
        assert_eq!(self.add(contact, true), Outcome::Refused, "an old client that said hello keeps its port");
    }

    fn given_a_table_taking_lan_addresses(&mut self) {
        self.lan_table = Some(RoutingTable::new(UInt128::random(&mut self.random), ipv4::is_lan));
    }

    fn given_a_contact_at(&mut self, ip: u32) {
        let contact = self.contact(ip);
        (self.removed, self.removed_ip) = (contact.id(), ip);
        self.add(contact, false);
    }

    fn given_the_contact_removed(&mut self) {
        self.table.remove(&self.removed);
        assert!(self.table.by_address(self.removed_ip).is_none());
    }

    fn given_a_target(&mut self) {
        self.target = UInt128::random(&mut self.random);
    }

    fn given_three_contacts_of_which_the_target_and_another_are_verified(&mut self) {
        let exact = KadContact::new(self.target, ip(1, 1, 1, 1), PORT, 4662, 8, self.base);
        let not_verified = self.contact(ip(2, 2, 2, 2));
        let other = self.contact(ip(3, 3, 3, 3));
        (self.exact, self.other) = (exact.id(), other.id());
        for contact in [exact, not_verified, other] {
            self.add(contact, false);
        }
        self.table.get_mut(&self.exact).unwrap().set_verified(true);
        self.table.get_mut(&self.other).unwrap().set_verified(true);
    }

    fn given_the_other_verified_contact_answered(&mut self) {
        let base = self.base;
        self.table.get_mut(&self.other).unwrap().alive(base);
    }

    fn given_a_contact_of_the_table(&mut self, ip: u32, is_verified: bool) {
        let mut contact = self.contact(ip);
        contact.set_verified(is_verified);
        let id = contact.id();
        assert_eq!(self.add(contact, false), Outcome::Added);
        if is_verified {
            self.verified = id;
        } else {
            self.unverified = id;
        }
    }

    fn given_a_random_walk_from_the_seed(&mut self, seed: u64) {
        self.walk = Some(StdRng::seed_from_u64(seed));
    }

    fn given_a_table_whose_lan_is_ten_slash_eight(&mut self) {
        let own = UInt128(self.walk().random());
        self.walked = Some(RoutingTable::new(own, |ip| ip >> 24 == 10));
        self.known.clear();
    }

    fn given_ten_contacts_in_each_zone_of_the_level(&mut self, level: u32) {
        for j in 0..routing_table::K as u32 {
            for zone in 0..1u64 << level {
                assert_eq!(self.add_centred(zone, level, j, 8), Outcome::Added);
            }
        }
    }

    fn given_one_more_contact_in_our_own_zone_of_the_level(&mut self, level: u32) {
        assert_eq!(self.add_centred(0, level, routing_table::K as u32, 8), Outcome::Added);
    }

    fn given_contacts_of_the_versions(&mut self, versions: &[u8]) {
        for version in versions {
            assert_eq!(self.add_centred(u64::from(*version), 4, 0, *version), Outcome::Added);
        }
    }

    fn when_a_contact_of_the_subnet_moves_to(&mut self, ip: u32) {
        self.moving = self.full_subnet[0];
        self.moved_from = self.table.get(&self.moving).unwrap().ip();
        let contact = KadContact::new(self.moving, ip, PORT, 4662, 8, self.base);
        self.outcome = Some(self.add(contact, true));
    }

    fn when_its_own_id_is_offered_at(&mut self, ip: u32) {
        let contact = KadContact::new(self.table.own(), ip, PORT, 4662, 8, self.base);
        self.outcome = Some(self.add(contact, false));
    }

    fn when_a_new_contact_is_offered(&mut self, ip: u32, udp_port: u16, version: u8) {
        let contact = KadContact::new(UInt128::random(&mut self.random), ip, udp_port, 4662, version, self.base);
        self.outcome = Some(self.add(contact, false));
    }

    fn when_the_first_contact_is_offered(&mut self, ip: u32, version: u8, update: bool) {
        let contact = KadContact::new(self.first, ip, PORT, 4662, version, self.base);
        self.outcome = Some(self.add(contact, update));
    }

    fn when_the_old_client_is_updated_with_the_port(&mut self, port: u16) {
        let contact = KadContact::new(self.old_client, self.old_client_ip, port, 4662, 5, self.base);
        self.outcome = Some(self.add(contact, true));
    }

    fn when_contacts_of_the_lan_subnet_are_added(&mut self, range: u32, count: u32) {
        for at in 1..=count {
            let contact = self.contact(range | at);
            let base = self.base;
            if self.lan_table.as_mut().unwrap().add(contact, false, UdpKey::default(), 0, base) == Outcome::Added {
                self.lan_added += 1;
            }
        }
    }

    fn when_the_contact_is_removed(&mut self) {
        self.table.remove(&self.removed);
    }

    fn when_the_closest_are_asked(&mut self, max_type: u8, count: usize) {
        self.closest = self.table.closest(self.target, max_type, count).iter().map(|c| c.id()).collect();
    }

    fn when_a_lookup_would_ask(&mut self, who: Asked, ip: u32, port: u16) {
        let id = match who {
            Asked::Verified => self.verified,
            Asked::Unverified => self.unverified,
            Asked::Unknown => UInt128::random(&mut self.random),
        };
        self.acceptable = self.table.is_acceptable(&id, ip, port);
    }

    fn when_contacts_come_and_go_for_steps(&mut self, steps: u32) {
        for step in 0..steps {
            let walk = self.walk();
            let first = if walk.random_bool(0.5) { 10 } else { 20 + walk.random_range(0..3) };
            let ip = ip(first, walk.random_range(0..3), 1, walk.random_range(0..40));
            let op = walk.random_range(0..10);
            let base = self.base;
            if op < 7 || self.known.is_empty() {
                let id = UInt128(self.walk().random());
                let contact = KadContact::new(id, ip, PORT, 4662, 8, base);
                if self.walked().add(contact, false, UdpKey::default(), 0, base) == Outcome::Added {
                    self.known.push(id);
                }
            } else if op < 9 {
                let known = self.known.len();
                let at = self.walk().random_range(0..known);
                let contact = KadContact::new(self.known[at], ip, PORT, 4662, 8, base);
                self.walked().add(contact, true, UdpKey::default(), 0, base);
            } else {
                let known = self.known.len();
                let at = self.walk().random_range(0..known);
                let id = self.known.remove(at);
                self.walked().remove(&id);
            }
            if step % 97 == 0 {
                self.walked().consolidate();
            }
        }
    }

    fn when_the_nodes_are_estimated_with(&mut self, firewalled: f64) {
        self.estimate = self.centred.estimated_nodes(firewalled);
    }

    fn when_the_share_from_the_version_is_taken(&mut self, version: u8) {
        self.share = self.centred.share_from(version);
    }

    fn then_the_outcome_is(&self, expected: Outcome) {
        assert_eq!(self.outcome, Some(expected));
    }

    fn then_the_contact_is_refused_as(&self, why: &str) {
        assert_eq!(self.outcome, Some(Outcome::Refused), "{why}");
    }

    fn then_the_moved_contact_is_found_at(&self, ip: u32) {
        assert_eq!(self.table.by_address(ip).map(KadContact::id), Some(self.moving));
    }

    fn then_no_contact_is_found_where_it_was(&self) {
        assert!(self.table.by_address(self.moved_from).is_none());
    }

    fn then_the_two_contacts_stay_at(&self, first_ip: u32, second_ip: u32) {
        assert_eq!(self.table.by_address(first_ip).map(KadContact::id), Some(self.first));
        assert_eq!(self.table.by_address(second_ip).map(KadContact::id), Some(self.second));
    }

    fn then_more_contacts_are_added_than(&self, limit: usize) {
        assert!(self.lan_added > limit, "{}", self.lan_added);
    }

    fn then_no_contact_is_found_at(&self, ip: u32) {
        assert!(self.table.by_address(ip).is_none());
    }

    fn then_no_contact_is_the_closest(&self) {
        assert!(self.closest.is_empty());
    }

    fn then_the_closest_are_the_target_and_the_other_verified_contact(&self) {
        assert_eq!(self.closest, [self.exact, self.other]);
    }

    fn then_the_only_closest_is_the_contact_that_answered(&self) {
        assert_eq!(self.closest, [self.other], "only those that answered");
    }

    fn then_a_lookup_may_ask(&self, expected: bool, why: &str) {
        assert_eq!(self.acceptable, expected, "{why}");
    }

    fn then_the_table_is_in_shape(&self) {
        assert_shape(self.walked.as_ref().unwrap(), self.known.len());
    }

    fn then_the_estimate_is(&self, expected: u64) {
        assert_eq!(self.estimate, expected);
    }

    fn then_the_share_is(&self, expected: f64) {
        assert!((self.share - expected).abs() < f64::EPSILON, "{}", self.share);
    }

    // Spreads contacts over many zones, then adds contacts of one /24 until it holds the most allowed.
    fn fill_subnet(&mut self, subnet: u32) -> Vec<UInt128> {
        for at in 0..80u8 {
            let contact = self.contact(ip(30 + at, 1, 1, 1));
            self.add(contact, false);
        }
        let mut added = Vec::new();
        let mut at = 1;
        while at < 10_000 && added.len() < 10 {
            let contact = self.contact(subnet | (at % 250 + 1));
            let id = contact.id();
            if self.add(contact, false) == Outcome::Added {
                added.push(id);
            }
            at += 1;
        }
        added
    }

    // A contact in the zone of the level whose distance starts with the bits of `zone`; the next bit alternates with
    // `j`, so that a zone splitting further shares its contacts.
    fn add_centred(&mut self, zone: u64, level: u32, j: u32, version: u8) -> Outcome {
        let high = zone << (64 - level) | u64::from(j & 1) << (63 - level) | self.random.random::<u64>() >> (level + 1);
        let low = self.random.random::<u64>() | 1;
        let n = self.centred_added;
        self.centred_added += 1;
        let at = ip(u8::try_from(20 + n / 250).unwrap(), u8::try_from(n % 250 + 1).unwrap(), 1, 1);
        let contact =
            KadContact::new(UInt128(u128::from(high) << 64 | u128::from(low)), at, PORT, 4662, version, self.base);
        self.centred.add(contact, false, UdpKey::default(), 0, self.base)
    }

    fn add(&mut self, contact: KadContact, update: bool) -> Outcome {
        self.table.add(contact, update, UdpKey::default(), 0, self.base)
    }

    fn contact(&mut self, ip: u32) -> KadContact {
        KadContact::new(UInt128::random(&mut self.random), ip, PORT, 4662, 8, self.base)
    }

    fn walk(&mut self) -> &mut StdRng {
        self.walk.as_mut().unwrap()
    }

    fn walked(&mut self) -> &mut RoutingTable {
        self.walked.as_mut().unwrap()
    }
}

fn assert_shape(table: &RoutingTable, size: usize) {
    assert_eq!(table.size(), size);
    let mut per_subnet: HashMap<u32, u32> = HashMap::new();
    let mut ips = HashSet::new();
    let mut in_leaves = 0;
    for zone in table.leaves() {
        let bin = zone.contacts();
        assert!(bin.len() <= routing_table::K);
        let mut in_bin: HashMap<u32, u32> = HashMap::new();
        for id in bin {
            let contact = table.get(id).unwrap();
            in_leaves += 1;
            assert!(ips.insert(contact.ip()), "one contact per address");
            for bit in 0..zone.level() {
                assert_eq!((contact.id() ^ table.own()).bit(bit), zone.index().bit(UInt128::BITS - zone.level() + bit));
            }
            if contact.ip() >> 24 != 10 {
                *per_subnet.entry(contact.ip() & 0xFFFF_FF00).or_default() += 1;
                let count = in_bin.entry(contact.ip() & 0xFFFF_FF00).or_default();
                *count += 1;
                assert!(*count <= 2);
            }
        }
    }
    assert_eq!(in_leaves, size);
    assert!(per_subnet.values().all(|count| *count <= 10));
}

#[test]
fn should_let_a_contact_move_inside_its_own_full_subnet() {
    let mut table = Table::new();
    table.given_a_full_subnet(ip(9, 9, 9, 0));
    table.given_the_full_subnet_refuses_a_new_contact_at(ip(9, 9, 9, 251));

    table.when_a_contact_of_the_subnet_moves_to(ip(9, 9, 9, 251));

    table.then_the_outcome_is(Outcome::Updated);
    table.then_the_moved_contact_is_found_at(ip(9, 9, 9, 251));
    table.then_no_contact_is_found_where_it_was();
}

#[test]
fn should_refuse_itself() {
    let mut table = Table::new();

    table.when_its_own_id_is_offered_at(ip(1, 1, 1, 1));

    table.then_the_contact_is_refused_as("ourselves");
}

#[test]
fn should_refuse_a_contact_of_version_one() {
    let mut table = Table::new();

    table.when_a_new_contact_is_offered(ip(1, 1, 1, 2), PORT, 1);

    table.then_the_contact_is_refused_as("version 1");
}

#[test]
fn should_refuse_port53_of_an_old_client() {
    let mut table = Table::new();

    table.when_a_new_contact_is_offered(ip(1, 1, 1, 3), 53, 5);

    table.then_the_contact_is_refused_as("port 53 of an old client");
}

#[test]
fn should_add_port53_of_a_newer_client() {
    let mut table = Table::new();

    table.when_a_new_contact_is_offered(ip(2, 1, 1, 3), 53, 6);

    table.then_the_outcome_is(Outcome::Added);
}

#[test]
fn should_add_the_highest_port() {
    let mut table = Table::new();

    table.when_a_new_contact_is_offered(ip(3, 1, 1, 3), 0xFFFF, 8);

    table.then_the_outcome_is(Outcome::Added);
}

#[test]
fn should_refuse_a_known_contact_at_another_address_unless_it_is_an_update() {
    let mut table = Table::new();
    table.given_two_contacts_at(ip(1, 1, 1, 1), ip(2, 2, 2, 2));

    table.when_the_first_contact_is_offered(ip(4, 4, 4, 4), 8, false);

    table.then_the_contact_is_refused_as("not an update");
    table.then_the_two_contacts_stay_at(ip(1, 1, 1, 1), ip(2, 2, 2, 2));
}

#[test]
fn should_refuse_to_move_a_known_contact_onto_another_ones_address() {
    let mut table = Table::new();
    table.given_two_contacts_at(ip(1, 1, 1, 1), ip(2, 2, 2, 2));

    table.when_the_first_contact_is_offered(ip(2, 2, 2, 2), 8, true);

    table.then_the_contact_is_refused_as("onto another contact's address");
    table.then_the_two_contacts_stay_at(ip(1, 1, 1, 1), ip(2, 2, 2, 2));
}

#[test]
fn should_refuse_to_update_a_known_contact_to_an_older_version() {
    let mut table = Table::new();
    table.given_two_contacts_at(ip(1, 1, 1, 1), ip(2, 2, 2, 2));

    table.when_the_first_contact_is_offered(ip(1, 1, 1, 1), 7, true);

    table.then_the_contact_is_refused_as("an older version");
    table.then_the_two_contacts_stay_at(ip(1, 1, 1, 1), ip(2, 2, 2, 2));
}

#[test]
fn should_keep_the_port_of_an_old_client_that_said_hello() {
    let mut table = Table::new();
    table.given_an_old_client_that_said_hello_at(ip(5, 5, 5, 5));

    table.when_the_old_client_is_updated_with_the_port(PORT + 1);

    table.then_the_contact_is_refused_as("an old client that said hello keeps its port");
}

#[test]
fn should_update_an_old_client_that_said_hello_on_its_own_port() {
    let mut table = Table::new();
    table.given_an_old_client_that_said_hello_at(ip(5, 5, 5, 5));
    table.given_the_old_client_refused_with_the_port(PORT + 1);

    table.when_the_old_client_is_updated_with_the_port(PORT);

    table.then_the_outcome_is(Outcome::Updated);
}

#[test]
fn should_not_hold_lan_addresses_to_the_subnet_limits() {
    let mut table = Table::new();
    table.given_a_table_taking_lan_addresses();

    table.when_contacts_of_the_lan_subnet_are_added(ip(192, 168, 1, 0), 30);

    table.then_more_contacts_are_added_than(10);
}

#[test]
fn should_forget_a_removed_contact() {
    let mut table = Table::new();
    table.given_a_contact_at(ip(7, 7, 7, 7));

    table.when_the_contact_is_removed();

    table.then_no_contact_is_found_at(ip(7, 7, 7, 7));
}

#[test]
fn should_free_the_place_of_a_removed_contact() {
    let mut table = Table::new();
    table.given_a_contact_at(ip(7, 7, 7, 7));
    table.given_the_contact_removed();

    table.when_a_new_contact_is_offered(ip(7, 7, 7, 7), PORT, 8);

    table.then_the_outcome_is(Outcome::Added);
}

#[test]
fn should_find_no_closest_contact_in_an_empty_table() {
    let mut table = Table::new();
    table.given_a_target();

    table.when_the_closest_are_asked(3, 5);

    table.then_no_contact_is_the_closest();
}

#[test]
fn should_give_no_closest_contact_when_none_is_wanted() {
    let mut table = Table::new();
    table.given_a_target();
    table.given_three_contacts_of_which_the_target_and_another_are_verified();

    table.when_the_closest_are_asked(3, 0);

    table.then_no_contact_is_the_closest();
}

#[test]
fn should_give_the_verified_contacts_closest_first() {
    let mut table = Table::new();
    table.given_a_target();
    table.given_three_contacts_of_which_the_target_and_another_are_verified();

    table.when_the_closest_are_asked(3, 10);

    table.then_the_closest_are_the_target_and_the_other_verified_contact();
}

#[test]
fn should_give_only_the_closest_contacts_of_low_enough_type() {
    let mut table = Table::new();
    table.given_a_target();
    table.given_three_contacts_of_which_the_target_and_another_are_verified();
    table.given_the_other_verified_contact_answered();

    table.when_the_closest_are_asked(2, 10);

    table.then_the_only_closest_is_the_contact_that_answered();
}

#[test]
fn should_let_a_lookup_ask_only_contacts_that_agree_with_the_table() {
    let cases = [
        (Asked::Verified, "1.1.1.1", 4672, true),
        (Asked::Verified, "5.5.5.5", 4672, false),
        (Asked::Verified, "1.1.1.1", 4673, false),
        (Asked::Unverified, "5.5.5.5", 4672, true),
        (Asked::Unknown, "2.2.2.2", 4672, false),
        (Asked::Unknown, "9.9.9.251", 4672, false),
        (Asked::Unknown, "5.5.5.5", 4672, true),
    ];
    for (who, address, port, expected) in cases {
        let mut table = Table::new();
        table.given_a_contact_of_the_table(ip(1, 1, 1, 1), true);
        table.given_a_contact_of_the_table(ip(2, 2, 2, 2), false);
        table.given_a_full_subnet(ip(9, 9, 9, 0));

        table.when_a_lookup_would_ask(who, u32::from(address.parse::<Ipv4Addr>().unwrap()), port);

        table.then_a_lookup_may_ask(expected, &format!("{address}:{port}"));
    }
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(50))]

    #[test]
    fn should_keep_the_table_in_shape_while_contacts_come_and_go(seed: u64) {
        let mut table = Table::new();
        table.given_a_random_walk_from_the_seed(seed);
        table.given_a_table_whose_lan_is_ten_slash_eight();

        table.when_contacts_come_and_go_for_steps(400);

        table.then_the_table_is_in_shape();
    }
}

#[test]
fn should_estimate_a_shallow_tree_as_if_its_zones_were_full() {
    let mut table = Table::new();
    table.given_ten_contacts_in_each_zone_of_the_level(3);

    table.when_the_nodes_are_estimated_with(1.5);

    table.then_the_estimate_is(80);
}

#[test]
fn should_estimate_a_deeper_tree_from_the_zone_three_levels_above_its_deepest_leaf() {
    let mut table = Table::new();
    table.given_ten_contacts_in_each_zone_of_the_level(4);
    table.given_one_more_contact_in_our_own_zone_of_the_level(4);

    table.when_the_nodes_are_estimated_with(1.5);

    // The leaves of level 5 sample the 41 contacts of our zone of level 2.
    table.then_the_estimate_is(4 * 41 * 3 / 2);
}

#[test]
fn should_tell_the_share_of_contacts_speaking_a_version_or_later() {
    let mut table = Table::new();
    table.given_contacts_of_the_versions(&[7, 8, 8, 9]);

    table.when_the_share_from_the_version_is_taken(8);

    table.then_the_share_is(0.75);
}

#[test]
fn should_tell_no_share_without_contacts() {
    let mut table = Table::new();

    table.when_the_share_from_the_version_is_taken(8);

    table.then_the_share_is(0.0);
}
