// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadBasicsTest, under its names: keywords and their ids, the network's lower case, the routing
//! table's first rules, and the flood control and answer checking of Kad datagrams.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::time::{Duration, Instant};

use njmule_engine::files::hash::ed2k;
use njmule_engine::kad::kad_contact::KadContact;
use njmule_engine::kad::kad_identity::KadIdentity;
use njmule_engine::kad::packet_tracking::{self, PacketTracking};
use njmule_engine::kad::routing_table::{self, Outcome, RoutingTable};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::kad::{kad_lower_case, keywords};
use njmule_engine::persist::format::nodes_dat::UdpKey;
use njmule_engine::protocol::packet::opcodes::kad;
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};

const MINUTE: Duration = Duration::from_secs(60);
const ADDRESS: u32 = ip(4, 4, 4, 4);

const fn ip(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from_be_bytes([a, b, c, d])
}

struct Basics {
    random: StdRng,
    base: Instant,
    tracking: PacketTracking,
    keywords: Vec<String>,
    keyword_id: UInt128,
    lower_case: u16,
    own: UInt128,
    table: Option<RoutingTable>,
    outcome: Option<Outcome>,
    added: usize,
    target: UInt128,
    closest: Vec<UInt128>,
    // Each leaf's level and index, with the id drawn in it.
    random_ids: Vec<(u32, UInt128, UInt128)>,
    real: UInt128,
    our_ip: u32,
    pings_let_through: u32,
    answer_taken: bool,
}

impl Basics {
    fn new() -> Self {
        Self {
            random: StdRng::from_rng(&mut rand::rng()),
            base: Instant::now(),
            tracking: PacketTracking::new(),
            keywords: Vec::new(),
            keyword_id: UInt128::ZERO,
            lower_case: 0,
            own: UInt128::ZERO,
            table: None,
            outcome: None,
            added: 0,
            target: UInt128::ZERO,
            closest: Vec::new(),
            random_ids: Vec::new(),
            real: UInt128::ZERO,
            our_ip: 0,
            pings_let_through: 0,
            answer_taken: false,
        }
    }

    fn given_an_empty_routing_table(&mut self) {
        self.own = UInt128::random(&mut self.random);
        self.table = Some(RoutingTable::new(self.own, |_| false));
    }

    fn given_a_contact_at(&mut self, ip: u32, port: u16) {
        let contact = self.contact(ip, port);
        let base = self.base;
        assert_eq!(self.table().add(contact, false, UdpKey::default(), 0, base), Outcome::Added);
    }

    fn given_random_contacts_added_and_verified(&mut self, count: usize) {
        self.add_random_verified_contacts(count);
    }

    fn given_a_contact_proven_by_the_key(&mut self, key: u32) {
        self.table = Some(RoutingTable::new(UInt128::random(&mut self.random), |_| false));
        let real = self.contact(ip(1, 1, 1, 1), 4672);
        self.real = real.id();
        self.our_ip = ip(2, 2, 2, 2);
        let (our_ip, base) = (self.our_ip, self.base);
        self.table().add(real, false, UdpKey { key, ip: our_ip }, our_ip, base);
    }

    fn given_a_request_sent_to(&mut self, ip: u32, request: u8, millis: u64) {
        let now = self.at(millis);
        self.tracking.sent(ip, request, now);
    }

    fn given_the_answer_is_taken(&mut self, ip: u32, answer: u8, millis: u64) {
        let now = self.at(millis);
        assert!(self.tracking.expected(ip, answer, now));
    }

    fn when_the_keywords_of_a_name_are_made(&mut self, name: &str) {
        self.keywords = keywords::of(name);
    }

    fn when_the_id_of_a_keyword_is_made(&mut self, keyword: &str) {
        self.keyword_id = keywords::id(keyword);
    }

    fn when_the_case_of_a_character_is_lowered(&mut self, character: char) {
        self.lower_case = kad_lower_case::lower_unit(u16::try_from(u32::from(character)).unwrap());
    }

    fn when_a_contact_is_added_at(&mut self, ip: u32, port: u16) {
        let contact = self.contact(ip, port);
        let base = self.base;
        self.outcome = Some(self.table().add(contact, false, UdpKey::default(), 0, base));
    }

    fn when_our_own_id_is_added_at(&mut self, ip: u32) {
        let contact = KadContact::new(self.own, ip, 1, 1, 8, self.base);
        let base = self.base;
        self.outcome = Some(self.table().add(contact, false, UdpKey::default(), 0, base));
    }

    fn when_contacts_are_added_in_the_subnet_of(&mut self, subnet: u32, count: u32) {
        for at in 1..=count {
            let contact = self.contact(subnet + at, 4672);
            let base = self.base;
            if self.table().add(contact, false, UdpKey::default(), 0, base) == Outcome::Added {
                self.added += 1;
            }
        }
    }

    fn when_random_contacts_are_added_and_verified(&mut self, count: usize) {
        self.add_random_verified_contacts(count);
    }

    fn when_the_closest_contacts_to_a_random_target_are_asked(&mut self, max_type: u8, count: usize) {
        self.target = UInt128::random(&mut self.random);
        let target = self.target;
        self.closest = self.table.as_ref().unwrap().closest(target, max_type, count).iter().map(|c| c.id()).collect();
    }

    fn when_a_random_id_is_drawn_in_every_zone(&mut self) {
        let table = self.table.as_ref().unwrap();
        for zone in table.leaves() {
            let id = table.random_id_in(zone, &mut self.random);
            self.random_ids.push((zone.level(), zone.index(), id));
        }
    }

    fn when_a_contact_with_its_id_is_added_from(&mut self, ip: u32, version: u8, key: u32) {
        let contact = KadContact::new(self.real, ip, 4672, 4662, version, self.base);
        let (our_ip, later) = (self.our_ip, self.base + MINUTE);
        self.outcome = Some(self.table().add(contact, true, UdpKey { key, ip: our_ip }, our_ip, later));
    }

    fn when_pings_arrive_from(&mut self, ip: u32, count: u32, millis: u64) {
        let now = self.at(millis);
        for _ in 0..count {
            if self.tracking.allow(ip, kad::PING, now) {
                self.pings_let_through += 1;
            }
        }
    }

    fn when_an_answer_arrives(&mut self, ip: u32, answer: u8, millis: u64) {
        let now = self.at(millis);
        self.answer_taken = self.tracking.expected(ip, answer, now);
    }

    fn then_the_keywords_are(&self, expected: &str, why: &str) {
        assert_eq!(self.keywords, expected.split(' ').collect::<Vec<_>>(), "{why}");
    }

    fn then_the_id_is_the_md4_of_the_utf8_bytes(&self, keyword: &str) {
        assert_eq!(self.keyword_id, UInt128::from_big_endian(ed2k::md4(keyword.as_bytes())));
    }

    fn then_the_lower_case_is(&self, expected: char, why: &str) {
        assert_eq!(u32::from(self.lower_case), u32::from(expected), "{why}");
    }

    fn then_the_outcome_is(&self, expected: Outcome, why: &str) {
        assert_eq!(self.outcome, Some(expected), "{why}");
    }

    fn then_at_most_contacts_were_added(&self, count: usize) {
        assert!(self.added <= count, "at most 10 per /24: {}", self.added);
    }

    fn then_the_table_holds_the_added_contacts_and(&self, others: usize) {
        assert_eq!(self.table.as_ref().unwrap().size(), others + self.added);
    }

    fn then_the_table_holds_more_contacts_than(&self, count: usize) {
        assert!(self.table.as_ref().unwrap().size() > count);
    }

    fn then_the_table_has_more_leaves_than(&self, count: usize) {
        assert!(self.table.as_ref().unwrap().leaves().len() > count);
    }

    fn then_the_closest_contacts_are(&self, count: usize) {
        assert_eq!(self.closest.len(), count);
    }

    fn then_the_closest_contacts_are_in_order_of_distance(&self) {
        for pair in self.closest.windows(2) {
            assert!(pair[0] ^ self.target <= pair[1] ^ self.target);
        }
    }

    fn then_every_random_id_stays_in_its_zone(&self) {
        for (level, index, id) in &self.random_ids {
            for bit in 0..*level {
                assert_eq!(
                    (*id ^ self.own).bit(bit),
                    index.bit(UInt128::BITS - level + bit),
                    "random ids stay in their zone"
                );
            }
        }
    }

    fn then_the_contact_is_at(&self, ip: u32) {
        assert_eq!(self.table.as_ref().unwrap().get(&self.real).unwrap().ip(), ip);
    }

    fn then_the_type_of_the_contact_is(&self, contact_type: u8) {
        assert_eq!(self.table.as_ref().unwrap().get(&self.real).unwrap().contact_type(), contact_type);
    }

    fn then_the_pings_let_through_are(&self, count: u32) {
        assert_eq!(self.pings_let_through, count, "two pings a minute");
    }

    fn then_the_sender_is_banned_at(&mut self, millis: u64) {
        let now = self.at(millis);
        assert!(self.tracking.is_banned(ADDRESS, now), "9 back to back ban");
    }

    fn then_the_sender_is_no_longer_banned_at(&mut self, millis: u64) {
        let now = self.at(millis);
        assert!(!self.tracking.is_banned(ADDRESS, now));
    }

    fn then_the_answer_is_taken(&self) {
        assert!(self.answer_taken);
    }

    fn then_the_answer_is_not_taken(&self, why: &str) {
        assert!(!self.answer_taken, "{why}");
    }

    fn add_random_verified_contacts(&mut self, count: usize) {
        for _ in 0..count {
            let at = self.random.random_range(0..0x7000_0000) + 0x0100_0000;
            let contact = self.contact(at, 4672);
            let (id, base) = (contact.id(), self.base);
            self.table().add(contact, false, UdpKey::default(), 0, base);
            if let Some(added) = self.table().get_mut(&id) {
                added.set_verified(true);
                added.alive(base);
            }
        }
    }

    fn contact(&mut self, ip: u32, port: u16) -> KadContact {
        KadContact::new(UInt128::random(&mut self.random), ip, port, 4662, 8, self.base)
    }

    fn table(&mut self) -> &mut RoutingTable {
        self.table.as_mut().unwrap()
    }

    fn at(&self, millis: u64) -> Instant {
        self.base + Duration::from_millis(millis)
    }
}

#[test]
fn should_make_keywords_of_long_enough_lower_case_words_without_the_extension() {
    let cases = [
        ("Ubuntu-24.04.iso", "ubuntu"),
        ("The.Matrix.1999.mkv", "the matrix 1999"),
        ("iso", "iso"),
        ("ÀÉÎ ΣΟΦΙΑ", "àéî σοφια"),
    ];
    for (name, expected) in cases {
        let mut basics = Basics::new();

        basics.when_the_keywords_of_a_name_are_made(name);

        basics.then_the_keywords_are(expected, name);
    }
}

#[test]
fn should_move_duplicate_keywords_to_the_end_and_keep_a_four_letter_extension() {
    let mut basics = Basics::new();

    basics.when_the_keywords_of_a_name_are_made("song - SONG (live).flac");

    basics.then_the_keywords_are("song live flac", "duplicates move to the end; a 4-letter extension stays");
}

#[test]
fn should_keep_the_last_word_before_a_trailing_delimiter() {
    let mut basics = Basics::new();

    basics.when_the_keywords_of_a_name_are_made("readme.txt.");

    basics.then_the_keywords_are("readme txt", "a trailing delimiter keeps the last word");
}

#[test]
fn should_publish_a_keyword_under_the_md4_of_its_utf8_bytes() {
    let mut basics = Basics::new();

    basics.when_the_id_of_a_keyword_is_made("ubuntu");

    basics.then_the_id_is_the_md4_of_the_utf8_bytes("ubuntu");
}

#[test]
fn should_lower_the_case_with_the_network_table_not_todays_unicode() {
    for (character, expected) in [('A', 'a'), ('ῃ', 'ῌ'), ('7', '7')] {
        let mut basics = Basics::new();

        basics.when_the_case_of_a_character_is_lowered(character);

        basics.then_the_lower_case_is(expected, &character.to_string());
    }
}

#[test]
fn should_leave_the_dotted_capital_i_unchanged() {
    let mut basics = Basics::new();

    basics.when_the_case_of_a_character_is_lowered('İ');

    basics.then_the_lower_case_is('İ', "dotted capital I unchanged");
}

#[test]
fn should_add_a_contact() {
    let mut basics = Basics::new();
    basics.given_an_empty_routing_table();

    basics.when_a_contact_is_added_at(ip(1, 2, 3, 4), 4672);

    basics.then_the_outcome_is(Outcome::Added, "a new contact");
}

#[test]
fn should_keep_one_contact_per_address() {
    let mut basics = Basics::new();
    basics.given_an_empty_routing_table();
    basics.given_a_contact_at(ip(1, 2, 3, 4), 4672);

    basics.when_a_contact_is_added_at(ip(1, 2, 3, 4), 4673);

    basics.then_the_outcome_is(Outcome::Refused, "same address");
}

// Ports above 0xFFFF and below 0 do not fit the u16 a port is.
#[test]
fn should_refuse_a_contact_with_an_invalid_port() {
    let mut basics = Basics::new();
    basics.given_an_empty_routing_table();
    basics.given_a_contact_at(ip(1, 2, 3, 4), 4672);

    basics.when_a_contact_is_added_at(ip(9, 8, 7, 6), 0);

    basics.then_the_outcome_is(Outcome::Refused, "port 0");
}

#[test]
fn should_refuse_ourselves() {
    let mut basics = Basics::new();
    basics.given_an_empty_routing_table();
    basics.given_a_contact_at(ip(1, 2, 3, 4), 4672);

    basics.when_our_own_id_is_added_at(ip(5, 5, 5, 5));

    basics.then_the_outcome_is(Outcome::Refused, "ourselves");
}

#[test]
fn should_keep_few_contacts_per_subnet() {
    let mut basics = Basics::new();
    basics.given_an_empty_routing_table();
    basics.given_a_contact_at(ip(1, 2, 3, 4), 4672);

    basics.when_contacts_are_added_in_the_subnet_of(ip(9, 9, 9, 0), 20);

    basics.then_at_most_contacts_were_added(10);
    basics.then_the_table_holds_the_added_contacts_and(1);
}

#[test]
fn should_split_the_table_near_us() {
    let mut basics = Basics::new();
    basics.given_an_empty_routing_table();

    basics.when_random_contacts_are_added_and_verified(2_000);

    basics.then_the_table_holds_more_contacts_than(routing_table::K * 16);
    basics.then_the_table_has_more_leaves_than(16);
}

#[test]
fn should_answer_the_closest_contacts_in_order_of_distance() {
    let mut basics = Basics::new();
    basics.given_an_empty_routing_table();
    basics.given_random_contacts_added_and_verified(2_000);

    basics.when_the_closest_contacts_to_a_random_target_are_asked(3, 5);

    basics.then_the_closest_contacts_are(5);
    basics.then_the_closest_contacts_are_in_order_of_distance();
}

#[test]
fn should_draw_random_ids_that_stay_in_their_zone() {
    let mut basics = Basics::new();
    basics.given_an_empty_routing_table();
    basics.given_random_contacts_added_and_verified(2_000);

    basics.when_a_random_id_is_drawn_in_every_zone();

    basics.then_every_random_id_stays_in_its_zone();
}

#[test]
fn should_refuse_an_impostor_of_a_contact_proven_by_its_key() {
    let mut basics = Basics::new();
    basics.given_a_contact_proven_by_the_key(77);

    basics.when_a_contact_with_its_id_is_added_from(ip(3, 3, 3, 3), 8, 78);

    basics.then_the_outcome_is(Outcome::Refused, "another key");
}

#[test]
fn should_move_a_contact_proven_by_its_key() {
    let mut basics = Basics::new();
    basics.given_a_contact_proven_by_the_key(77);

    basics.when_a_contact_with_its_id_is_added_from(ip(3, 3, 3, 3), 9, 77);

    basics.then_the_outcome_is(Outcome::Updated, "the same key");
    basics.then_the_contact_is_at(ip(3, 3, 3, 3));
    basics.then_the_type_of_the_contact_is(2);
}

#[test]
fn should_drop_a_flood_then_ban_its_sender() {
    let mut basics = Basics::new();

    basics.when_pings_arrive_from(ADDRESS, 12, 1000);

    basics.then_the_pings_let_through_are(2);
    basics.then_the_sender_is_banned_at(1000);
    basics.then_the_sender_is_no_longer_banned_at(1000 + packet_tracking::BAN_TIME.as_millis() as u64);
}

#[test]
fn should_not_take_an_answer_not_asked_for() {
    let mut basics = Basics::new();

    basics.when_an_answer_arrives(ADDRESS, kad::PONG, 2000);

    basics.then_the_answer_is_not_taken("not asked for");
}

#[test]
fn should_not_take_an_answer_from_another_address() {
    let mut basics = Basics::new();
    basics.given_a_request_sent_to(ADDRESS, kad::PING, 2000);

    basics.when_an_answer_arrives(ADDRESS + 1, kad::PONG, 2100);

    basics.then_the_answer_is_not_taken("other address");
}

#[test]
fn should_not_take_another_answer() {
    let mut basics = Basics::new();
    basics.given_a_request_sent_to(ADDRESS, kad::PING, 2000);

    basics.when_an_answer_arrives(ADDRESS, kad::RES, 2100);

    basics.then_the_answer_is_not_taken("other answer");
}

#[test]
fn should_take_the_answer_asked_for() {
    let mut basics = Basics::new();
    basics.given_a_request_sent_to(ADDRESS, kad::PING, 2000);

    basics.when_an_answer_arrives(ADDRESS, kad::PONG, 2100);

    basics.then_the_answer_is_taken();
}

#[test]
fn should_take_an_answer_only_once() {
    let mut basics = Basics::new();
    basics.given_a_request_sent_to(ADDRESS, kad::PING, 2000);
    basics.given_the_answer_is_taken(ADDRESS, kad::PONG, 2100);

    basics.when_an_answer_arrives(ADDRESS, kad::PONG, 2200);

    basics.then_the_answer_is_not_taken("consumed");
}

#[test]
fn should_not_take_an_answer_too_late() {
    let mut basics = Basics::new();
    basics.given_a_request_sent_to(ADDRESS, kad::REQ, 3000);

    basics.when_an_answer_arrives(ADDRESS, kad::RES, 3000 + packet_tracking::ANSWER_WINDOW.as_millis() as u64 + 1);

    basics.then_the_answer_is_not_taken("too late");
}

// Rust's own: what Java's tests reach through the node.

fn when_an_identity_is_made_of(id: u128) -> Option<UInt128> {
    KadIdentity::new(UInt128(id), 7).map(|identity| identity.id())
}

fn then_the_identity_is(made: Option<UInt128>, expected: Option<UInt128>) {
    assert_eq!(made, expected);
}

fn when_lookups_hold_and_release_a_contact(holds: u32, releases: u32) -> (bool, Option<Instant>) {
    let mut contact = KadContact::new(UInt128(5), u32::from_be_bytes([20, 1, 1, 1]), 4672, 4662, 8, Instant::now());
    for _ in 0..holds {
        contact.hold();
    }
    for _ in 0..releases {
        contact.release();
    }
    (contact.in_use(), contact.expires())
}

fn then_the_contact_is_in_use(state: (bool, Option<Instant>), in_use: bool) {
    assert_eq!(state.0, in_use);
}

#[test]
fn should_refuse_the_zero_id_for_an_identity() {
    let made = when_an_identity_is_made_of(0);

    then_the_identity_is(made, None);
}

#[test]
fn should_make_an_identity_of_any_other_id() {
    let made = when_an_identity_is_made_of(5);

    then_the_identity_is(made, Some(UInt128(5)));
}

#[test]
fn should_keep_a_contact_in_use_while_a_lookup_holds_it() {
    let state = when_lookups_hold_and_release_a_contact(2, 1);

    then_the_contact_is_in_use(state, true);
}

#[test]
fn should_free_a_contact_every_lookup_released() {
    let state = when_lookups_hold_and_release_a_contact(1, 2);

    then_the_contact_is_in_use(state, false);
}
