// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodeTest, under its names: the contacts the node learns, the searches it runs, how it lists
//! them, and the checks of our TCP port.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::HashSet;
use std::net::Ipv4Addr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use njmule_engine::api::kad_contact_info::KadContactInfo;
use njmule_engine::api::kad_search_info::{self, KadSearchInfo};
use njmule_engine::api::search_result::{Media, SearchResult};
use njmule_engine::kad::kad_identity::KadIdentity;
use njmule_engine::kad::kad_node::{
    Buddies, Buddy, BuddyState, FileResult, Host, KadEvent, KadNode, NoBuddies, SearchHandle, Settings, Source,
    Transport,
};
use njmule_engine::kad::kad_notes::Finder;
use njmule_engine::kad::kad_publisher;
use njmule_engine::kad::keywords;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::{self, UdpKey};
use njmule_engine::protocol::kad::kad_messages::{self, Contact, Entry, Request, Sender};
use njmule_engine::protocol::packet::opcodes::kad;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};
use rand::SeedableRng;
use rand::rngs::StdRng;

const PORT: u16 = 4672;
const PEER: u32 = ip(20, 1, 1, 1);
const SECOND: Duration = Duration::from_secs(1);

const fn ip(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from_be_bytes([a, b, c, d])
}

/// Records what goes to the peer.
struct ToPeer(Arc<Mutex<Vec<Packet>>>);

impl Transport for ToPeer {
    fn send(&mut self, ip: u32, _: u16, packet: &Packet, _: Option<UInt128>, _: u32, _: u32) {
        if ip == PEER {
            self.0.lock().unwrap().push(packet.clone());
        }
    }
}

struct NoChecks;

impl Host for NoChecks {
    fn check_reachable(&mut self, _: u32, _: u16, _: [u8; 16], _: u8) {}
}

struct Node {
    _dir: tempfile::TempDir,
    random: StdRng,
    time: Arc<Mutex<Instant>>,
    sent_to_peer: Arc<Mutex<Vec<Packet>>>,
    blocked: Arc<Mutex<HashSet<u32>>>,
    stops: Vec<SearchHandle>,
    found: Vec<String>,
    node: KadNode,
    rounds: u32,
    target: UInt128,
    search: Option<SearchHandle>,
    contact_infos: Vec<KadContactInfo>,
    lookup_infos: Vec<KadSearchInfo>,
}

impl Node {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let mut random = StdRng::from_rng(&mut rand::rng());
        let time = Arc::new(Mutex::new(Instant::now()));
        let sent_to_peer = Arc::new(Mutex::new(Vec::new()));
        let blocked: Arc<Mutex<HashSet<u32>>> = Arc::default();
        let refused = Arc::clone(&blocked);
        let settings = Settings::new(
            4662,
            [0; 16],
            0,
            Arc::new(|_| false),
            Arc::new(move |ip| refused.lock().unwrap().contains(&ip)),
        );
        let clock = Arc::clone(&time);
        let node = KadNode::new(
            KadIdentity::load_or_create(dir.path(), &mut random).unwrap(),
            Box::new(ToPeer(Arc::clone(&sent_to_peer))),
            Box::new(NoChecks),
            settings,
            dir.path(),
            Box::new(move || *clock.lock().unwrap()),
        );
        Self {
            _dir: dir,
            random,
            time,
            sent_to_peer,
            blocked,
            stops: Vec::new(),
            found: Vec::new(),
            node,
            rounds: 0,
            target: UInt128::ZERO,
            search: None,
            contact_infos: Vec::new(),
            lookup_infos: Vec::new(),
        }
    }

    fn given_contacts_at(&mut self, ips: &[u32]) {
        let contacts = ips.iter().map(|ip| self.record(*ip)).collect();
        self.node.add_contacts(&nodes_dat::Contents { file_version: 2, bootstrap: false, contacts });
    }

    fn given_a_contact_at_the_keyword(&mut self, word: &str) {
        self.target = keywords::id(word);
        let contact = nodes_dat::Contact {
            id: self.target,
            ip: PEER,
            udp_port: PORT,
            tcp_port: 4662,
            version: 8,
            contact_type: 0,
            udp_key: UdpKey::default(),
            verified: true,
        };
        self.node.add_contacts(&nodes_dat::Contents { file_version: 2, bootstrap: false, contacts: vec![contact] });
    }

    fn given_the_address_is_filtered(&mut self, ip: u32) {
        self.blocked.lock().unwrap().insert(ip);
    }

    fn given_a_keyword_search_started(&mut self, text: &str) {
        self.when_a_keyword_search_starts(text);
        self.then_the_search_started();
        self.stops.push(self.search.unwrap());
    }

    fn given_a_keyword_search_was_refused(&mut self, text: &str) {
        self.when_a_keyword_search_starts(text);
        self.then_the_search_did_not_start("refused");
    }

    fn given_the_first_search_stopped(&mut self) {
        self.node.end_search(self.stops[0]);
    }

    fn given_a_source_search_started(&mut self) {
        assert!(self.node.find_sources([7; 16], 1000));
    }

    fn given_a_node_said_hello(&mut self, ip: u32) {
        self.when_a_node_says_hello(ip);
        self.then_a_connection_is_awaited_from(ip);
    }

    fn given_nodes_connected_to_us(&mut self, ips: &[u32]) {
        for ip in ips {
            self.hello(*ip);
            self.node.reachable_confirmed(*ip);
        }
        self.then_we_are_not_firewalled("connected to us");
    }

    fn given_a_firewall_check_runs(&mut self) {
        self.when_the_firewall_is_rechecked();
        self.then_we_are_not_firewalled("while the check runs");
    }

    fn given_the_contact_answered_the_lookup(&mut self) {
        self.answer(self.target, &[]);
    }

    fn given_seconds_passed(&mut self, seconds: u32) {
        *self.time.lock().unwrap() += SECOND * seconds;
        self.node.tick();
    }

    fn given_seconds_passed_with_two_ticks(&mut self, seconds: u32) {
        *self.time.lock().unwrap() += SECOND * seconds;
        self.node.tick();
        self.node.tick();
    }

    fn given_the_lookup_reached_the_contact(&mut self) {
        self.given_the_contact_answered_the_lookup();
        self.given_seconds_passed(4);
        assert!(self.sent_to_peer.lock().unwrap().iter().any(|packet| packet.opcode() == kad::SEARCH_KEY_REQ));
    }

    fn given_unusable_results_came(&mut self, count: usize) {
        let junk: Vec<Entry> =
            (0..count).map(|_| Entry { id: UInt128::random(&mut self.random), tags: Vec::new() }).collect();
        let answer = kad_messages::SearchAnswer { sender: self.target, target: self.target, results: junk };
        self.node.received(PEER, PORT, &answer.encode().unwrap(), 0, 0);
        self.node.tick();
    }

    fn given_a_node_pinged_us(&mut self, ip: u32) {
        self.node.received(ip, PORT, &Packet::of(protocol::KAD, kad::PING, Vec::new()), 0, 0);
        assert!(self.node.is_connected());
    }

    fn given_the_checks_counted(&mut self) {
        self.when_the_checks_are_counted();
    }

    fn when_the_contact_answers_for_another_target(&mut self, ips: &[u32]) {
        let other = UInt128::random(&mut self.random);
        let contacts = self.contacts(ips);
        self.answer(other, &contacts);
    }

    fn when_the_contact_answers_with_one_contact_more_than_asked_for(&mut self) {
        let request = self.last_request();
        let too_many: Vec<Contact> = (0..=request.count).map(|at| self.contact(ip(60 + at, 1, 1, 1))).collect();
        self.answer(request.target, &too_many);
    }

    fn when_the_contact_answers_the_last_request_with(&mut self, ips: &[u32]) {
        let target = self.last_request().target;
        let contacts = self.contacts(ips);
        self.answer(target, &contacts);
    }

    fn when_the_contacts_are_listed(&mut self) {
        self.contact_infos = self.node.contact_infos();
    }

    fn when_the_lookups_are_listed(&mut self) {
        self.lookup_infos = self.node.lookup_infos();
    }

    fn when_a_keyword_search_starts(&mut self, text: &str) {
        self.search = self.node.search_keyword(text, None);
    }

    fn when_the_first_search_stops_again(&mut self) {
        self.node.end_search(self.stops[0]);
    }

    fn when_the_filters_change(&mut self) {
        self.node.filters_changed();
    }

    fn when_a_node_says_hello(&mut self, ip: u32) {
        self.hello(ip);
    }

    fn when_its_connection_is_confirmed(&mut self, ip: u32) {
        self.node.reachable_confirmed(ip);
    }

    fn when_the_checks_are_counted(&mut self) {
        self.rounds = self.node.firewall_rounds();
    }

    fn when_a_recheck_is_asked_for(&mut self) {
        self.node.recheck_firewall();
    }

    fn when_the_firewall_is_rechecked(&mut self) {
        self.node.recheck_firewall();
        self.node.tick();
    }

    fn when_the_contact_answers_the_search_with_a_file(&mut self, name: &str, size: u64) {
        let file = Entry {
            id: UInt128::random(&mut self.random),
            tags: vec![
                Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() },
                Tag::Int { name: TagName::id(file_tag::FILESIZE), value: size },
            ],
        };
        let answer = kad_messages::SearchAnswer { sender: self.target, target: self.target, results: vec![file] };
        self.node.received(PEER, PORT, &answer.encode().unwrap(), 0, 0);
    }

    fn when_minutes_pass_in_steps_of_ten_seconds(&mut self, minutes: u32) {
        for _ in (0..minutes * 60).step_by(10) {
            *self.time.lock().unwrap() += SECOND * 10;
            self.node.tick();
        }
    }

    fn then_the_contacts_are(&self, count: usize, because: &str) {
        assert_eq!(self.node.contacts(), count, "{because}");
    }

    fn then_the_contacts_listed_are(&self, count: usize) {
        assert_eq!(self.contact_infos.len(), count);
    }

    fn then_the_contacts_are_listed_closest_first(&self) {
        let distances: Vec<&String> = self.contact_infos.iter().map(|info| &info.distance).collect();
        assert!(distances.windows(2).all(|pair| pair[0] <= pair[1]), "closest first");
    }

    fn then_the_distance_of_a_contact_is_its_id_xor_ours(&self) {
        let first = &self.contact_infos[0];
        assert_eq!(first.distance, (UInt128::parse_hex(&first.id).unwrap() ^ self.node.id()).to_hex());
    }

    fn then_the_only_lookup_is(&self, kind: kad_search_info::Kind) {
        assert_eq!(self.lookup_infos.len(), 1);
        assert_eq!(self.lookup_infos[0].kind, kind);
    }

    fn then_the_search_started(&self) {
        assert!(self.search.is_some());
    }

    fn then_the_search_did_not_start(&self, because: &str) {
        assert!(self.search.is_none(), "{because}");
    }

    fn then_the_lookups_are(&self, count: usize, because: &str) {
        assert_eq!(self.node.lookup_infos().len(), count, "{because}");
    }

    fn then_the_contact_addresses_are(&self, addresses: &[&str]) {
        let listed: Vec<String> = self.node.contact_infos().into_iter().map(|info| info.address).collect();
        assert_eq!(listed, addresses);
    }

    fn then_a_connection_is_awaited_from(&self, ip: u32) {
        assert!(self.node.awaits_connection_from(ip));
    }

    fn then_no_connection_is_awaited_from(&self, ip: u32) {
        assert!(!self.node.awaits_connection_from(ip));
    }

    fn then_we_are_not_firewalled(&self, because: &str) {
        assert!(!self.node.is_firewalled(), "{because}");
    }

    fn then_the_tcp_check_tells(&self, open: Option<bool>) {
        assert_eq!(self.node.tcp_check_open(), open);
    }

    fn then_the_checks_started_since(&self, started: u32) {
        assert_eq!(self.node.firewall_rounds() - self.rounds, started);
    }

    fn then_the_files_found_are(&mut self, names: &[&str]) {
        for event in self.node.take_events() {
            if let KadEvent::Files { results, .. } = event {
                self.found.extend(results.into_iter().map(|result| result.name));
            }
        }
        assert_eq!(self.found, names);
    }

    fn then_the_node_is_not_connected(&self) {
        assert!(!self.node.is_connected());
    }

    // A hello from a node recent enough to check our TCP port.
    fn hello(&mut self, ip: u32) {
        let sender = Sender { id: UInt128::random(&mut self.random), tcp_port: 4662, version: 8 };
        let hello = kad_messages::Hello { sender, tags: Vec::new() };
        self.node.received(ip, PORT, &hello.encode(false).unwrap(), 0, 0);
    }

    fn answer(&mut self, lookup: UInt128, contacts: &[Contact]) {
        let answer = kad_messages::Answer { target: lookup, contacts: contacts.to_vec() };
        self.node.received(PEER, PORT, &answer.encode().unwrap(), 0, 0);
    }

    fn contacts(&mut self, ips: &[u32]) -> Vec<Contact> {
        ips.iter().map(|ip| self.contact(*ip)).collect()
    }

    fn contact(&mut self, ip: u32) -> Contact {
        Contact { id: UInt128::random(&mut self.random), ip, udp_port: PORT, tcp_port: 4662, version: 8 }
    }

    fn record(&mut self, ip: u32) -> nodes_dat::Contact {
        nodes_dat::Contact {
            id: UInt128::random(&mut self.random),
            ip,
            udp_port: PORT,
            tcp_port: 4662,
            version: 8,
            contact_type: 0,
            udp_key: UdpKey::default(),
            verified: true,
        }
    }

    fn last_request(&self) -> Request {
        let sent = self.sent_to_peer.lock().unwrap();
        let last = sent.iter().rev().find(|packet| packet.opcode() == kad::REQ).expect("a lookup request sent");
        Request::decode(last).unwrap()
    }
}

#[test]
fn should_learn_no_contacts_from_an_answer_for_another_target() {
    let mut node = Node::new();
    node.given_contacts_at(&[PEER]);
    node.given_a_keyword_search_started("ubuntu");

    node.when_the_contact_answers_for_another_target(&[ip(30, 1, 1, 1), ip(40, 1, 1, 1)]);

    node.then_the_contacts_are(1, "an answer for another target");
}

#[test]
fn should_learn_no_contacts_from_an_answer_with_more_than_asked_for() {
    let mut node = Node::new();
    node.given_contacts_at(&[PEER]);
    node.given_a_source_search_started();

    node.when_the_contact_answers_with_one_contact_more_than_asked_for();

    node.then_the_contacts_are(1, "more contacts than asked for");
}

#[test]
fn should_leave_out_a_filtered_address_of_an_answer() {
    let mut node = Node::new();
    node.given_contacts_at(&[PEER]);
    node.given_the_address_is_filtered(ip(50, 1, 1, 1));
    node.given_a_source_search_started();

    node.when_the_contact_answers_the_last_request_with(&[ip(30, 1, 1, 1), ip(50, 1, 1, 1)]);

    node.then_the_contacts_are(2, "the filtered address is left out");
}

#[test]
fn should_list_its_contacts_closest_first() {
    let mut node = Node::new();
    node.given_contacts_at(&[PEER, ip(70, 1, 1, 1), ip(71, 1, 1, 1)]);
    node.given_a_keyword_search_started("ubuntu");

    node.when_the_contacts_are_listed();

    node.then_the_contacts_listed_are(3);
    node.then_the_contacts_are_listed_closest_first();
    node.then_the_distance_of_a_contact_is_its_id_xor_ours();
}

#[test]
fn should_list_its_lookups() {
    let mut node = Node::new();
    node.given_contacts_at(&[PEER, ip(70, 1, 1, 1), ip(71, 1, 1, 1)]);
    node.given_a_keyword_search_started("ubuntu");

    node.when_the_lookups_are_listed();

    node.then_the_only_lookup_is(kad_search_info::Kind::Keyword);
}

#[test]
fn should_not_start_a_keyword_search_already_running() {
    let mut node = Node::new();
    node.given_contacts_at(&[PEER]);
    node.given_a_keyword_search_started("ubuntu");

    node.when_a_keyword_search_starts("ubuntu");

    node.then_the_search_did_not_start("already running");
}

#[test]
fn should_start_a_stopped_keyword_search_again_at_once() {
    let mut node = Node::new();
    node.given_contacts_at(&[PEER]);
    node.given_a_keyword_search_started("ubuntu");
    node.given_a_keyword_search_was_refused("ubuntu");
    node.given_the_first_search_stopped();

    node.when_a_keyword_search_starts("ubuntu");

    node.then_the_search_started();
}

// The first search's own end comes later.
#[test]
fn should_leave_a_newer_search_for_the_same_keyword_when_an_old_one_stops() {
    let mut node = Node::new();
    node.given_contacts_at(&[PEER]);
    node.given_a_keyword_search_started("ubuntu");
    node.given_the_first_search_stopped();
    node.given_a_keyword_search_started("ubuntu iso");

    node.when_the_first_search_stops_again();

    node.then_the_lookups_are(1, "the newer search goes on");
}

#[test]
fn should_drop_contacts_a_filter_now_refuses() {
    let mut node = Node::new();
    node.given_contacts_at(&[PEER, ip(70, 1, 1, 1)]);
    node.given_the_address_is_filtered(PEER);

    node.when_the_filters_change();

    node.then_the_contact_addresses_are(&[&format!("{}:{PORT}", Ipv4Addr::new(70, 1, 1, 1))]);
}

#[test]
fn should_await_a_node_asked_to_connect_to_us() {
    let mut node = Node::new();

    node.when_a_node_says_hello(PEER);

    node.then_a_connection_is_awaited_from(PEER);
    node.then_no_connection_is_awaited_from(ip(21, 1, 1, 1));
}

#[test]
fn should_no_longer_await_a_node_that_connected_to_us() {
    let mut node = Node::new();
    node.given_a_node_said_hello(PEER);

    node.when_its_connection_is_confirmed(PEER);

    node.then_no_connection_is_awaited_from(PEER);
}

#[test]
fn should_stay_open_while_a_firewall_check_runs() {
    let mut node = Node::new();
    node.given_nodes_connected_to_us(&[PEER, ip(21, 1, 1, 1)]);

    node.when_the_firewall_is_rechecked();

    node.then_we_are_not_firewalled("while the check runs");
}

#[test]
fn should_tell_the_tcp_port_open_once_nodes_connected_to_us() {
    let mut node = Node::new();
    node.given_nodes_connected_to_us(&[PEER, ip(21, 1, 1, 1)]);

    node.when_the_checks_are_counted();

    node.then_the_tcp_check_tells(Some(true));
}

#[test]
fn should_tell_no_tcp_outcome_once_a_recheck_started() {
    let mut node = Node::new();
    node.given_nodes_connected_to_us(&[PEER, ip(21, 1, 1, 1)]);
    node.given_the_checks_counted();

    node.when_the_firewall_is_rechecked();

    node.then_the_tcp_check_tells(None);
    node.then_the_checks_started_since(1);
}

#[test]
fn should_start_the_recheck_asked_for_at_the_next_tick() {
    let mut node = Node::new();
    node.given_nodes_connected_to_us(&[PEER, ip(21, 1, 1, 1)]);
    node.given_the_checks_counted();

    node.when_a_recheck_is_asked_for();

    node.then_the_checks_started_since(0);
    node.then_the_tcp_check_tells(Some(true));
}

#[test]
fn should_keep_the_last_outcome_of_a_check_started_during_another() {
    let mut node = Node::new();
    node.given_nodes_connected_to_us(&[PEER, ip(21, 1, 1, 1)]);
    node.given_a_firewall_check_runs();
    node.given_nodes_connected_to_us(&[ip(22, 1, 1, 1)]);

    node.when_the_firewall_is_rechecked();

    node.then_we_are_not_firewalled("a check started before the last one ended");
}

#[test]
fn should_still_pass_an_answer_that_comes_after_the_last_request_to_the_search() {
    let mut node = Node::new();
    node.given_a_contact_at_the_keyword("ubuntu");
    node.given_a_keyword_search_started("ubuntu");
    node.given_the_contact_answered_the_lookup();
    node.given_seconds_passed(4);
    node.given_seconds_passed(4);

    node.when_the_contact_answers_the_search_with_a_file("ubuntu.iso", 1000);

    node.then_the_files_found_are(&["ubuntu.iso"]);
}

#[test]
fn should_go_on_searching_a_keyword_after_unusable_results() {
    let mut node = Node::new();
    node.given_a_contact_at_the_keyword("ubuntu");
    node.given_a_keyword_search_started("ubuntu");
    node.given_the_lookup_reached_the_contact();
    node.given_unusable_results_came(300);

    node.when_a_keyword_search_starts("ubuntu");

    node.then_the_search_did_not_start("the search still runs");
}

// With no node left to ask, it ends after waiting a little for late answers.
#[test]
fn should_end_a_keyword_search_with_no_node_left_to_ask() {
    let mut node = Node::new();
    node.given_a_contact_at_the_keyword("ubuntu");
    node.given_a_keyword_search_started("ubuntu");
    node.given_the_lookup_reached_the_contact();
    node.given_unusable_results_came(300);
    node.given_a_keyword_search_was_refused("ubuntu");
    node.given_seconds_passed_with_two_ticks(16);

    node.when_a_keyword_search_starts("ubuntu");

    node.then_the_search_started();
}

#[test]
fn should_no_longer_be_connected_when_nothing_arrives_for_twenty_minutes() {
    let mut node = Node::new();
    node.given_contacts_at(&[PEER]);
    node.given_a_node_pinged_us(PEER);

    node.when_minutes_pass_in_steps_of_ten_seconds(21);

    node.then_the_node_is_not_connected();
}

/// What the node answers directly and through the traits the publisher and the notes see it by.
#[derive(Debug, Default, PartialEq, Eq)]
struct Answers {
    ready: bool,
    connected: bool,
    firewalled: bool,
    reachable: bool,
    buddy_ip: u32,
    keyword_published: bool,
    source_published: bool,
    note_published: bool,
    notes_found: bool,
}

// Rust's own: what Java's tests reach through the engine.
impl Node {
    fn when_the_node_is_read_directly(&mut self) -> Answers {
        let node = &mut self.node;
        Answers {
            ready: node.is_ready(),
            connected: node.is_connected(),
            firewalled: node.is_firewalled(),
            reachable: node.is_reachable(),
            buddy_ip: node.buddy_ip(),
            keyword_published: node.publish_keyword("ubuntu", vec![file()]),
            source_published: node.publish_source([1; 16], 1_000),
            note_published: node.publish_note([1; 16], "ubuntu.iso", 1_000),
            notes_found: node.find_notes([1; 16], 1_000),
        }
    }

    fn when_the_node_is_read_through_the_traits(&mut self) -> Answers {
        let publisher: &mut dyn kad_publisher::Node = &mut self.node;
        let mut answers = Answers {
            ready: publisher.is_ready(),
            connected: publisher.is_connected(),
            firewalled: publisher.is_firewalled(),
            reachable: publisher.is_reachable(),
            buddy_ip: publisher.buddy_ip(),
            keyword_published: publisher.publish_keyword("ubuntu", vec![file()]),
            source_published: publisher.publish_source([1; 16], 1_000),
            note_published: publisher.publish_note([1; 16], "ubuntu.iso", 1_000),
            notes_found: false,
        };
        let finder: &mut dyn Finder = &mut self.node;
        assert_eq!(finder.is_ready(), answers.ready);
        answers.notes_found = finder.find_notes([1; 16], 1_000);
        answers
    }

    fn when_the_index_is_loaded(&mut self) {
        self.node.load_index();
    }

    fn then_the_traits_answer_as_the_node(&self, direct: &Answers, through: &Answers) {
        assert_eq!(direct, through);
    }

    fn then_there_is_no_buddy(&self) {
        assert_eq!(self.node.buddy_state(), BuddyState::None);
        assert_eq!(self.node.buddy_ip(), 0);
    }

    fn then_our_own_udp_port_is_announced_and_untested(&self) {
        assert_eq!(self.node.announced_udp_port(), 0, "none in the settings");
        assert_eq!(self.node.udp_test_open(), None);
    }

    fn then_the_index_is_empty(&self) {
        assert_eq!(self.node.indexed(), (0, 0));
        assert_eq!(self.node.indexed_notes(), 0);
    }

    fn then_the_table_holds_the_contacts(&self, count: usize) {
        assert_eq!(self.node.table().size(), count);
    }
}

fn file() -> Entry {
    Entry {
        id: UInt128(7),
        tags: vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: "ubuntu.iso".to_owned() },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: 1_000 },
        ],
    }
}

fn source(buddy: Option<Buddy>, direct_callback: bool) -> Source {
    Source {
        user_hash: [3; 16],
        ip: ip(30, 1, 1, 1),
        tcp_port: 4662,
        connect_options: 0,
        udp_port: PORT,
        buddy,
        direct_callback,
        ipv6: None,
    }
}

#[test]
fn should_answer_the_publisher_and_the_notes_as_the_node_itself() {
    let (mut node, mut twin) = (Node::new(), Node::new());
    node.given_contacts_at(&[ip(70, 1, 1, 1)]);
    twin.given_contacts_at(&[ip(70, 1, 1, 1)]);

    let (direct, through) = (node.when_the_node_is_read_directly(), twin.when_the_node_is_read_through_the_traits());

    node.then_the_traits_answer_as_the_node(&direct, &through);
}

#[test]
fn should_have_no_buddy_without_buddy_links() {
    let node = Node::new();

    let links = when_buddy_links_that_are_none_are_asked();

    then_the_links_have_no_buddy_and_take_none(links);
    node.then_there_is_no_buddy();
}

#[test]
fn should_announce_its_own_udp_port_while_no_test_says_otherwise() {
    let node = Node::new();

    node.then_our_own_udp_port_is_announced_and_untested();
}

#[test]
fn should_start_with_an_empty_index_when_none_was_saved() {
    let mut node = Node::new();

    node.when_the_index_is_loaded();

    node.then_the_index_is_empty();
}

#[test]
fn should_hold_the_contacts_in_its_table() {
    let mut node = Node::new();

    node.given_contacts_at(&[ip(70, 1, 1, 1), ip(71, 1, 1, 1)]);

    node.then_the_table_holds_the_contacts(2);
}

#[test]
fn should_tell_a_source_reached_through_a_buddy_or_a_callback_firewalled() {
    let buddy = Some(Buddy { ip: ip(31, 1, 1, 1), udp_port: PORT, id: UInt128(9) });

    let firewalled = when_sources_tell_whether_they_are_firewalled(&[(buddy, false), (None, true), (None, false)]);

    then_the_firewalled_ones_are(&firewalled, &[true, true, false]);
}

#[test]
fn should_leave_udp_tests_and_known_clients_to_hosts_that_have_them() {
    let mut host = NoChecks;

    when_the_host_is_asked_for_a_udp_test(&mut host);

    then_the_host_knows_no_client_and_has_no_high_id(&host);
}

fn when_buddy_links_that_are_none_are_asked() -> NoBuddies {
    let mut links = NoBuddies;
    links.offered(1, 2, 3, [0; 16], 0);
    links.release();
    links
}

fn then_the_links_have_no_buddy_and_take_none(mut links: NoBuddies) {
    assert_eq!(links.state(), BuddyState::None);
    assert_eq!((links.buddy_ip(), links.buddy_udp_port()), (0, 0));
    assert!(!links.asked(1, 2, 3, [0; 16], UInt128(4)));
    assert!(!links.relay(UInt128(4), UInt128(5), 1, 2));
}

fn when_sources_tell_whether_they_are_firewalled(sources: &[(Option<Buddy>, bool)]) -> Vec<bool> {
    sources.iter().map(|&(buddy, direct_callback)| source(buddy, direct_callback).is_firewalled()).collect()
}

fn then_the_firewalled_ones_are(firewalled: &[bool], expected: &[bool]) {
    assert_eq!(firewalled, expected);
}

fn when_the_host_is_asked_for_a_udp_test(host: &mut NoChecks) {
    host.check_udp(ip(30, 1, 1, 1), 4662, PORT, PORT, 7);
}

fn then_the_host_knows_no_client_and_has_no_high_id(host: &NoChecks) {
    assert!(!host.is_known_client(ip(30, 1, 1, 1)));
    assert!(!host.has_high_id());
}

fn when_a_kad_result_is_listed(found: FileResult) -> SearchResult {
    found.into_search_result()
}

fn then_it_is_listed_with_all_kad_told(listed: &SearchResult, found: &FileResult) {
    assert_eq!((listed.hash, listed.name.as_str(), listed.size), (found.hash, found.name.as_str(), found.size));
    assert_eq!((listed.sources, listed.complete_sources), (found.sources, None), "Kad does not tell complete sources");
    assert_eq!((listed.file_type.as_deref(), &listed.media), (found.file_type.as_deref(), &found.media));
    assert_eq!(listed.aich_hash, found.aich_hash);
}

#[test]
fn should_list_a_kad_result_with_all_kad_tells_of_it() {
    let found = FileResult {
        hash: [4; 16],
        name: "Night Sky.avi".to_owned(),
        size: 700_000_000,
        sources: 9,
        file_type: Some("Video".to_owned()),
        media: Media { title: "Night Sky".to_owned(), length: 5400, ..Media::default() },
        aich_hash: Some([6; 20]),
    };

    let listed = when_a_kad_result_is_listed(found.clone());

    then_it_is_listed_with_all_kad_told(&listed, &found);
}
