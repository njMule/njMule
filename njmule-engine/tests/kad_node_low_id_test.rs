// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodeLowIdTest, under its names: what keeps a firewalled Kad node reachable, the UDP firewall
//! test, buddies and callbacks.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use crate::kad_lab;

use std::collections::HashSet;
use std::net::Ipv6Addr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use kad_lab::{KadLab, PORT, Sent, UdpCheck, ip};
use njmule_engine::kad::kad_identity::KadIdentity;
use njmule_engine::kad::kad_node::{self, Buddies, Buddy, BuddyState, KadNode, Settings, Source};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::{self, UdpKey};
use njmule_engine::protocol::kad::kad_messages::{
    self, Answer, BuddyOffer, CallbackRequest, Contact, Entry, FindBuddy, FirewallUdp, Hello, NotesPublish, Request,
    Sender, SourcePublish,
};
use njmule_engine::protocol::packet::opcodes::kad;
use njmule_engine::protocol::tag::{Tag, TagName, file_tag, tags};

const TCP: u16 = 4662;
const USER: [u8; 16] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16];
const CONTACTS: [u32; 3] = [ip(20, 1, 1, 1), ip(21, 1, 1, 1), ip(22, 1, 1, 1)];
const HELPERS: [u32; 3] = [ip(60, 1, 1, 1), ip(61, 1, 1, 1), ip(62, 1, 1, 1)];
const ALL_ONES: UInt128 = UInt128(u128::MAX);

/// A node that offered to be our buddy, or asked us to be its own.
#[derive(Debug, Clone, PartialEq, Eq)]
struct BuddyCall {
    ip: u32,
    tcp_port: u16,
    udp_port: u16,
    user_hash: [u8; 16],
    buddy_id: Option<UInt128>,
}

/// A callback request relayed to the node we serve.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Relay {
    buddy_id: UInt128,
    file: UInt128,
    requester_ip: u32,
    requester_port: u16,
}

struct Calls {
    state: BuddyState,
    offers: Vec<BuddyCall>,
    asks: Vec<BuddyCall>,
    relays: Vec<Relay>,
}

/// Buddy links that only record what the node asks of them.
struct Links(Arc<Mutex<Calls>>);

impl Buddies for Links {
    fn state(&self) -> BuddyState {
        self.0.lock().unwrap().state
    }

    fn buddy_ip(&self) -> u32 {
        self.0.lock().unwrap().offers.first().map_or(0, |offer| offer.ip)
    }

    fn buddy_udp_port(&self) -> u16 {
        self.0.lock().unwrap().offers.first().map_or(0, |offer| offer.udp_port)
    }

    fn offered(&mut self, ip: u32, tcp_port: u16, udp_port: u16, user_hash: [u8; 16], _: u8) {
        let offer = BuddyCall { ip, tcp_port, udp_port, user_hash, buddy_id: None };
        self.0.lock().unwrap().offers.push(offer);
    }

    fn asked(&mut self, ip: u32, tcp_port: u16, udp_port: u16, user_hash: [u8; 16], buddy_id: UInt128) -> bool {
        let ask = BuddyCall { ip, tcp_port, udp_port, user_hash, buddy_id: Some(buddy_id) };
        self.0.lock().unwrap().asks.push(ask);
        true
    }

    fn relay(&mut self, buddy_id: UInt128, file: UInt128, requester_ip: u32, requester_port: u16) -> bool {
        self.0.lock().unwrap().relays.push(Relay { buddy_id, file, requester_ip, requester_port });
        true
    }
}

struct LowId {
    lab: KadLab,
    identity: KadIdentity,
    node: KadNode,
    calls: Arc<Mutex<Calls>>,
    our_ipv6: Arc<Mutex<Option<Ipv6Addr>>>,
    found: Vec<Source>,
    results: Vec<Entry>,
    buddy_id: UInt128,
    callback_file: UInt128,
    via_buddy: UInt128,
    direct: UInt128,
    published: bool,
    callback_requested: bool,
}

impl LowId {
    fn new() -> Self {
        let mut lab = KadLab::new();
        let identity = KadIdentity::load_or_create(lab.dir.path(), &mut lab.random).unwrap();
        let our_ipv6: Arc<Mutex<Option<Ipv6Addr>>> = Arc::default();
        let mut settings = Settings::new(TCP, USER, 3, Arc::new(|_| false), Arc::new(|_| false));
        settings.udp_port = PORT;
        let ipv6 = Arc::clone(&our_ipv6);
        settings.ipv6 = Arc::new(move || *ipv6.lock().unwrap());
        let mut node = lab.node(settings);
        let calls = Arc::new(Mutex::new(Calls {
            state: BuddyState::None,
            offers: Vec::new(),
            asks: Vec::new(),
            relays: Vec::new(),
        }));
        node.use_buddies(Box::new(Links(Arc::clone(&calls))));
        let mut ids = [UInt128(0); 4];
        for id in &mut ids {
            *id = UInt128::random(&mut lab.random);
        }
        Self {
            lab,
            identity,
            node,
            calls,
            our_ipv6,
            found: Vec::new(),
            results: Vec::new(),
            buddy_id: ids[0],
            callback_file: ids[1],
            via_buddy: ids[2],
            direct: ids[3],
            published: false,
            callback_requested: false,
        }
    }

    fn given_a_connected_node(&mut self) {
        for ip in CONTACTS {
            let id = UInt128::random(&mut self.lab.random);
            self.add_contact(ip, id);
        }
    }

    // Each node asked says which port it sees; two that agree decide it.
    fn given_the_nodes_see_our_udp_port_as(&mut self, port: u16) {
        let mut answered = Vec::new();
        for _ in 0..40 {
            if answered.len() >= 2 {
                break;
            }
            self.tick(15);
            if let Some(ping) = self.last_sent(kad::PING)
                && !answered.contains(&ping.ip)
            {
                answered.push(ping.ip);
                self.node.received(ping.ip, PORT, &kad_messages::pong(port), 0, 0);
            }
        }
        assert_eq!(answered.len(), 2, "nodes asked for our port");
    }

    fn given_a_lookup_found_helpers(&mut self) {
        self.when_a_lookup_finds_helpers();
        self.then_helpers_are_asked(2);
    }

    // A TCP-firewalled node whose test brings no outcome: right when it times out.
    fn given_udp_is_firewalled(&mut self) {
        for _ in 0..8 * 60 {
            if self.node.is_firewalled_udp() {
                break;
            }
            self.tick(1);
        }
        self.then_udp_is(true, false);
    }

    fn given_an_open_node_on_both_ports(&mut self) {
        self.given_a_connected_node();
        for ip in [ip(26, 1, 1, 1), ip(27, 1, 1, 1)] {
            let id = UInt128::random(&mut self.lab.random);
            self.add_contact(ip, id);
            self.node.reachable_confirmed(ip);
        }
        self.given_the_nodes_see_our_udp_port_as(PORT);
        self.given_a_lookup_found_helpers();
        self.given_the_first_helper_sent_its_datagram();
        assert!(!self.node.is_firewalled());
        self.then_udp_is(false, true);
    }

    fn given_the_first_helper_sent_its_datagram(&mut self) {
        self.when_the_first_helper_sends_its_datagram(true);
    }

    fn given_a_client_asked_for_its_udp_test(&mut self, ip: u32, internal: u16, external: u16, key: u32) {
        self.when_a_client_asks_for_its_udp_test(ip, internal, external, key);
    }

    fn given_a_contact_near_our_complement_at(&mut self, ip: u32) {
        let complement = self.complement();
        self.add_contact(ip, complement);
    }

    fn given_the_buddy_search_asked(&mut self, near: u32) {
        self.when_the_buddy_search_asks(near);
        self.then_a_buddy_request_went_to(near);
    }

    fn given_we_serve_a_buddy(&mut self) {
        self.given_a_connected_node();
        self.calls.lock().unwrap().state = BuddyState::Serving;
    }

    fn given_our_buddy_is_at(&self, ip: u32, port: u16) {
        let mut calls = self.calls.lock().unwrap();
        calls.state = BuddyState::Connected;
        calls.offers.push(BuddyCall { ip, tcp_port: TCP, udp_port: port, user_hash: USER, buddy_id: None });
    }

    fn given_a_search_result_reached_through_a_buddy(&mut self, id: UInt128, source_type: u64, ip: u32, buddy_ip: u32) {
        self.results.push(Entry { id, tags: buddy_source(source_type, ip, buddy_ip, self.buddy_id) });
    }

    fn given_a_search_result_for_direct_callbacks(&mut self, id: UInt128, options: u64) {
        self.results.push(Entry { id, tags: direct_callback_source(options) });
    }

    fn given_a_search_result_for_direct_callbacks_with_the_ipv6_tag(&mut self, id: UInt128, hex: &str) {
        let mut tags = direct_callback_source(0x08 | 0x01);
        tags.push(Tag::String { name: TagName::of_bytes(b"ip6"), value: hex.to_owned() });
        self.results.push(Entry { id, tags });
    }

    fn given_we_have_the_ipv6_address(&self, address: &str) {
        *self.our_ipv6.lock().unwrap() = Some(address.parse().unwrap());
    }

    fn given_a_search_result_of_source_type_only(&mut self, id: UInt128, source_type: u64) {
        self.results.push(Entry { id, tags: vec![int(file_tag::SOURCETYPE, source_type)] });
    }

    fn when_a_lookup_finds_helpers(&mut self) {
        self.tick(1);
        let target = self.node.udp_helpers_target().expect("a lookup for helpers");
        let request = self
            .lab
            .sent()
            .into_iter()
            .rfind(|sent| sent.packet.opcode() == kad::REQ && Request::decode(&sent.packet).unwrap().target == target)
            .expect("a node asked for helpers");
        let helpers: Vec<Contact> = HELPERS
            .iter()
            .map(|&ip| Contact {
                id: UInt128::random(&mut self.lab.random),
                ip,
                udp_port: PORT,
                tcp_port: TCP,
                version: 8,
            })
            .collect();
        self.node.received(request.ip, PORT, &Answer { target, contacts: helpers }.encode().unwrap(), 0, 0);
    }

    fn when_the_first_helper_sends_its_datagram(&mut self, with_our_key: bool) {
        let first = self.udp_checks()[0].ip;
        self.when_a_helper_sends_its_datagram(first, PORT, with_our_key);
    }

    fn when_a_helper_sends_its_datagram(&mut self, ip: u32, port: u16, with_our_key: bool) {
        let key = if with_our_key { self.identity.verify_key(ip) } else { 0 };
        self.node.received(ip, PORT, &FirewallUdp { error: 0, port }.encode(), key, 0);
    }

    fn when_the_helpers_links_end_without_a_datagram(&mut self) {
        for check in self.udp_checks() {
            self.node.udp_check_ended(check.ip, true);
        }
    }

    fn when_the_first_helper_could_not_be_asked(&mut self) {
        let first = self.udp_checks()[0].ip;
        self.node.udp_check_ended(first, false);
    }

    fn when_minutes_pass(&mut self, minutes: u64) {
        self.tick(minutes * 60);
    }

    fn when_a_client_asks_for_its_udp_test(&mut self, ip: u32, internal: u16, external: u16, key: u32) {
        self.node.udp_check_requested(ip, internal, external, key, false);
    }

    fn when_the_buddy_search_asks(&mut self, near: u32) {
        self.tick(1);
        let complement = self.complement();
        let asked = self.lab.sent().iter().any(|sent| {
            sent.ip == near
                && sent.packet.opcode() == kad::REQ
                && Request::decode(&sent.packet).unwrap().target == complement
        });
        assert!(asked, "the buddy search asks the node near our complement");
        self.node.received(near, PORT, &Answer { target: complement, contacts: Vec::new() }.encode().unwrap(), 0, 0);
        self.tick(4);
    }

    fn when_the_node_offers_to_be_the_buddy_of_our_complement(&mut self, ip: u32) {
        let complement = self.complement();
        self.when_the_node_offers_to_be_our_buddy(ip, complement);
    }

    fn when_the_node_offers_to_be_the_buddy_of_another_id(&mut self, ip: u32) {
        let other = UInt128::random(&mut self.lab.random);
        self.when_the_node_offers_to_be_our_buddy(ip, other);
    }

    fn when_the_node_offers_to_be_our_buddy(&mut self, ip: u32, buddy_id: UInt128) {
        let offer = BuddyOffer {
            buddy_id,
            user_hash: UInt128::from_big_endian([0xbb; 16]),
            tcp_port: 4663,
            connect_options: Some(1),
        };
        self.node.received(ip, PORT, &offer.encode(), 0, 0);
    }

    fn when_a_firewalled_node_asks_us_to_be_its_buddy(&mut self, ip: u32, sender_key: u32) {
        let ask = FindBuddy {
            buddy_id: self.buddy_id,
            user_hash: UInt128::from_big_endian([0xcc; 16]),
            tcp_port: 4664,
            connect_options: None,
        };
        self.node.received(ip, PORT, &ask.encode(), 0, sender_key);
    }

    fn when_a_callback_request_comes(&mut self, ip: u32, port: u16) {
        let request = CallbackRequest { buddy_id: self.buddy_id, file: self.callback_file, tcp_port: port };
        self.node.received(ip, PORT, &request.encode(), 0, 0);
    }

    fn when_the_buddy_of_a_source_is_asked_for_a_callback(&mut self, ip: u32, port: u16, file: [u8; 16]) {
        let buddy = Buddy { ip, udp_port: port, id: self.buddy_id };
        self.callback_requested = self.node.request_callback(&buddy, file);
    }

    fn when_the_search_results_are_read(&mut self) {
        self.found.extend(kad_node::source_results(&self.results));
    }

    fn when_a_note_on_a_file_near_us_is_published(&mut self) {
        let note = vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: "film.mkv".to_owned() },
            int(file_tag::FILERATING, 4),
            int(file_tag::FILESIZE, 1000),
        ];
        let publish =
            NotesPublish { file: self.identity.id(), publisher: UInt128::random(&mut self.lab.random), tags: note };
        self.node.received(CONTACTS[0], PORT, &publish.encode().unwrap(), 0, 0);
    }

    fn when_we_are_published_as_a_source(&mut self, size: u64) {
        let hash = [0xdd; 16];
        let target = UInt128::from_big_endian(hash);
        let holder = ip(35, 1, 1, 1);
        self.add_contact(holder, target);
        self.published = self.node.publish_source(hash, size);
        self.node.received(holder, PORT, &Answer { target, contacts: Vec::new() }.encode().unwrap(), 0, 0);
        self.tick(4);
    }

    fn then_helpers_are_asked(&self, count: usize) {
        let checks = self.udp_checks();
        assert_eq!(checks.len(), count);
        assert!(checks.iter().all(|check| HELPERS.contains(&check.ip)));
        assert_eq!(checks.iter().map(|check| check.ip).collect::<HashSet<_>>().len(), count);
    }

    fn then_the_helpers_are_asked_for_our_ports_with_our_key(&self) {
        for check in self.udp_checks() {
            assert_eq!(check.tcp_port, TCP);
            assert_eq!(check.internal_port, PORT);
            assert_eq!(check.external_port, PORT);
            assert_eq!(check.sender_key, self.identity.verify_key(check.ip));
        }
        assert!(!self.lab.sent().iter().any(|sent| HELPERS.contains(&sent.ip)), "helpers never hear from us over UDP");
    }

    fn then_udp_is(&self, firewalled: bool, verified: bool) {
        assert_eq!(self.node.is_firewalled_udp(), firewalled, "UDP firewalled");
        assert_eq!(self.node.is_udp_verified(), verified, "UDP tested");
    }

    fn then_our_hello_says_udp_firewalled(&mut self) {
        let id = UInt128::random(&mut self.lab.random);
        self.add_contact(ip(28, 1, 1, 1), id);
        let hello = Hello::decode(&self.last_sent(kad::HELLO_RES).unwrap().packet).unwrap();
        let options = tags::int_value(&hello.tags, file_tag::KADMISCOPTIONS).unwrap_or(0);
        assert_eq!(options & 0x03, 0x03, "UDP and TCP firewalled");
    }

    fn then_datagrams_for_the_udp_test_went_to(&self, ip: u32, ports: &[u16], error: u8, key: u32) {
        let datagrams: Vec<Sent> = self
            .lab
            .sent()
            .into_iter()
            .filter(|sent| sent.ip == ip && sent.packet.opcode() == kad::FIREWALLUDP)
            .collect();
        assert_eq!(datagrams.iter().map(|sent| sent.port).collect::<Vec<_>>(), ports);
        for sent in &datagrams {
            let datagram = FirewallUdp::decode(&sent.packet).unwrap();
            assert_eq!(datagram.port, sent.port);
            assert_eq!(datagram.error, error);
            assert_eq!(sent.receiver_key, key, "obfuscated with the key it gave");
        }
    }

    fn then_a_buddy_request_went_to(&self, ip: u32) {
        let request = self.last_sent(kad::FINDBUDDY_REQ).unwrap();
        assert_eq!(request.ip, ip);
        let find_buddy = FindBuddy::decode(&request.packet).unwrap();
        assert_eq!(find_buddy.buddy_id, self.complement());
        assert_eq!(find_buddy.user_hash, UInt128::from_big_endian(USER));
        assert_eq!(find_buddy.tcp_port, TCP);
        assert_eq!(find_buddy.connect_options, Some(3), "our connect options");
    }

    fn then_the_offer_is_taken_from(&self, ip: u32) {
        let expected = BuddyCall { ip, tcp_port: 4663, udp_port: PORT, user_hash: [0xbb; 16], buddy_id: None };
        assert_eq!(self.calls.lock().unwrap().offers, [expected]);
    }

    fn then_no_offer_is_taken(&self) {
        assert!(self.calls.lock().unwrap().offers.is_empty());
    }

    fn then_we_are_asked_to_be_the_buddy_of(&self, ip: u32) {
        let expected =
            BuddyCall { ip, tcp_port: 4664, udp_port: PORT, user_hash: [0xcc; 16], buddy_id: Some(self.buddy_id) };
        assert_eq!(self.calls.lock().unwrap().asks, [expected]);
    }

    fn then_our_offer_went_to(&self, ip: u32, options: Option<u8>) {
        let answer = self.last_sent(kad::FINDBUDDY_RES).unwrap();
        assert_eq!(answer.ip, ip);
        let offer = BuddyOffer::decode(&answer.packet).unwrap();
        let expected = BuddyOffer {
            buddy_id: self.buddy_id,
            user_hash: UInt128::from_big_endian(USER),
            tcp_port: TCP,
            connect_options: options,
        };
        assert_eq!(offer, expected);
    }

    fn then_no_offer_went_to(&self, ip: u32) {
        assert!(self.calls.lock().unwrap().asks.is_empty());
        assert!(!self.lab.sent().iter().any(|sent| sent.ip == ip && sent.packet.opcode() == kad::FINDBUDDY_RES));
    }

    fn then_the_callback_is_relayed_from(&self, ip: u32, port: u16) {
        let expected =
            Relay { buddy_id: self.buddy_id, file: self.callback_file, requester_ip: ip, requester_port: port };
        assert_eq!(self.calls.lock().unwrap().relays, [expected]);
    }

    fn then_a_plain_callback_request_went_to(&self, ip: u32, port: u16, file: [u8; 16]) {
        assert!(self.callback_requested);
        let request = self.last_sent(kad::CALLBACK_REQ).unwrap();
        assert_eq!(request.ip, ip);
        assert_eq!(request.port, port);
        assert_eq!(request.receiver_id, None);
        assert_eq!(request.receiver_key, 0);
        let expected = CallbackRequest { buddy_id: self.buddy_id, file: UInt128::from_big_endian(file), tcp_port: TCP };
        assert_eq!(CallbackRequest::decode(&request.packet).unwrap(), expected);
    }

    fn then_no_note_is_kept(&self) {
        assert_eq!(self.node.indexed_notes(), 0);
    }

    fn then_the_source_found_gives_no_ipv6_address(&self, hex: &str) {
        assert_eq!(self.found.len(), 1, "{hex}");
        assert_eq!(self.found[0].ipv6, None, "{hex}");
    }

    fn then_the_source_publish_gives_the_ipv6_address(&self, hex: &str) {
        assert!(self.published, "the publish started");
        let name = TagName::of_bytes(b"ip6");
        let expected = Tag::String { name: name.clone(), value: hex.to_owned() };
        assert_eq!(tags::find(&self.source_publish().tags, &name), Some(&expected));
    }

    fn then_the_sources_found_are(&self, sources: &[Source]) {
        assert_eq!(self.found, sources);
    }

    fn then_the_source_type_is(&self, source_type: u64) {
        assert!(self.published, "the publish started");
        assert_eq!(tags::int_value(&self.source_publish().tags, file_tag::SOURCETYPE), Some(source_type));
    }

    fn then_the_source_names_our_buddy(&self, ip: u32, port: u64) {
        let publish = self.source_publish();
        assert_eq!(tags::int_value(&publish.tags, file_tag::SERVERIP), Some(u64::from(ip.swap_bytes())));
        assert_eq!(tags::int_value(&publish.tags, file_tag::SERVERPORT), Some(port));
        let named = tags::string_value(&publish.tags, file_tag::BUDDYHASH).and_then(kad_node::parse_buddy_hash);
        assert_eq!(named, Some(self.complement()), "the hash names our complement");
        assert!(self.node.is_reachable());
    }

    fn then_the_connect_options_are(&self, options: u64) {
        assert_eq!(tags::int_value(&self.source_publish().tags, file_tag::ENCRYPTION), Some(options));
    }

    fn then_the_node_takes_direct_callbacks(&self) {
        assert!(self.node.supports_direct_callback());
        assert!(self.node.is_reachable());
    }

    fn then_no_source_publish_went(&self) {
        assert!(self.last_sent(kad::PUBLISH_SOURCE_REQ).is_none());
    }

    fn then_the_node_is_not_reachable(&self) {
        assert!(!self.node.is_reachable());
    }

    fn add_contact(&mut self, ip: u32, id: UInt128) {
        let contact = nodes_dat::Contact {
            id,
            ip,
            udp_port: PORT,
            tcp_port: TCP,
            version: 8,
            contact_type: 0,
            udp_key: UdpKey::default(),
            verified: true,
        };
        self.node.add_contacts(&nodes_dat::Contents { file_version: 2, bootstrap: false, contacts: vec![contact] });
        let hello = Hello { sender: Sender { id, tcp_port: TCP, version: 8 }, tags: Vec::new() };
        self.node.received(ip, PORT, &hello.encode(false).unwrap(), 0, 0);
    }

    fn complement(&self) -> UInt128 {
        self.identity.id() ^ ALL_ONES
    }

    fn source_publish(&self) -> SourcePublish {
        let publish = self.last_sent(kad::PUBLISH_SOURCE_REQ).expect("a source publish");
        SourcePublish::decode(&publish.packet).unwrap()
    }

    fn udp_checks(&self) -> Vec<UdpCheck> {
        self.lab.checks.udp_checks.lock().unwrap().clone()
    }

    fn tick(&mut self, seconds: u64) {
        for _ in 0..seconds {
            self.lab.advance(Duration::from_secs(1));
            self.node.tick();
        }
    }

    fn last_sent(&self, opcode: u8) -> Option<Sent> {
        self.lab.sent().into_iter().rev().find(|sent| sent.packet.opcode() == opcode)
    }
}

fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

fn buddy_source(source_type: u64, ip: u32, buddy_ip: u32, buddy_id: UInt128) -> Vec<Tag> {
    vec![
        int(file_tag::SOURCETYPE, source_type),
        int(file_tag::SOURCEIP, u64::from(ip)),
        int(file_tag::SOURCEPORT, u64::from(TCP)),
        int(file_tag::SOURCEUPORT, u64::from(PORT)),
        int(file_tag::SERVERIP, u64::from(buddy_ip.swap_bytes())),
        int(file_tag::SERVERPORT, 4673),
        Tag::String { name: TagName::id(file_tag::BUDDYHASH), value: kad_node::buddy_hash(buddy_id ^ ALL_ONES) },
    ]
}

fn direct_callback_source(options: u64) -> Vec<Tag> {
    vec![
        int(file_tag::SOURCETYPE, 6),
        int(file_tag::SOURCEIP, u64::from(ip(93, 1, 1, 1))),
        int(file_tag::SOURCEPORT, u64::from(TCP)),
        int(file_tag::SOURCEUPORT, u64::from(PORT)),
        int(file_tag::ENCRYPTION, options),
    ]
}

#[test]
fn should_ask_the_helpers_found_by_a_lookup_once_our_port_is_known() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_the_nodes_see_our_udp_port_as(PORT);

    low_id.when_a_lookup_finds_helpers();

    low_id.then_helpers_are_asked(2);
    low_id.then_the_helpers_are_asked_for_our_ports_with_our_key();
}

#[test]
fn should_take_one_datagram_of_a_helper_as_proof_that_udp_is_open() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_the_nodes_see_our_udp_port_as(PORT);
    low_id.given_a_lookup_found_helpers();

    low_id.when_the_first_helper_sends_its_datagram(true);

    low_id.then_udp_is(false, true);
}

#[test]
fn should_be_udp_firewalled_when_the_datagrams_of_two_helpers_never_came() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_the_nodes_see_our_udp_port_as(PORT);
    low_id.given_a_lookup_found_helpers();

    low_id.when_the_helpers_links_end_without_a_datagram();

    low_id.then_udp_is(true, true);
    low_id.then_our_hello_says_udp_firewalled();
}

#[test]
fn should_take_no_proof_from_a_datagram_of_a_node_not_asked() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_the_nodes_see_our_udp_port_as(PORT);
    low_id.given_a_lookup_found_helpers();

    low_id.when_a_helper_sends_its_datagram(ip(70, 1, 1, 1), PORT, true);

    low_id.then_udp_is(false, false);
}

#[test]
fn should_take_no_proof_from_a_datagram_without_our_key() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_the_nodes_see_our_udp_port_as(PORT);
    low_id.given_a_lookup_found_helpers();

    low_id.when_the_first_helper_sends_its_datagram(false);

    low_id.then_udp_is(false, false);
}

#[test]
fn should_replace_a_helper_that_could_not_be_asked() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_the_nodes_see_our_udp_port_as(PORT);
    low_id.given_a_lookup_found_helpers();

    low_id.when_the_first_helper_could_not_be_asked();

    low_id.then_helpers_are_asked(3);
    low_id.then_udp_is(false, false);
}

#[test]
fn should_count_a_tcp_firewalled_node_without_an_outcome_as_udp_firewalled_after_six_minutes() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();

    low_id.when_minutes_pass(7);

    low_id.then_udp_is(true, false);
}

#[test]
fn should_send_the_udp_test_of_a_client_one_datagram_per_port_and_only_once_in_a_while() {
    let mut low_id = LowId::new();
    low_id.given_a_client_asked_for_its_udp_test(ip(80, 1, 1, 1), 4673, 5000, 77);

    low_id.when_a_client_asks_for_its_udp_test(ip(80, 1, 1, 1), 4673, 5000, 77);

    low_id.then_datagrams_for_the_udp_test_went_to(ip(80, 1, 1, 1), &[4673, 5000], 0, 77);
}

#[test]
fn should_tell_a_client_of_ours_that_its_udp_test_is_worthless() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();

    low_id.when_a_client_asks_for_its_udp_test(CONTACTS[0], 4673, 0, 77);

    low_id.then_datagrams_for_the_udp_test_went_to(CONTACTS[0], &[4673], 1, 77);
}

#[test]
fn should_ask_the_nodes_near_its_complement_to_be_its_buddy_when_firewalled_on_both_ports() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_a_contact_near_our_complement_at(ip(30, 1, 1, 1));
    low_id.given_udp_is_firewalled();

    low_id.when_the_buddy_search_asks(ip(30, 1, 1, 1));

    low_id.then_a_buddy_request_went_to(ip(30, 1, 1, 1));
}

#[test]
fn should_take_the_buddy_offer_of_a_node_it_asked() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_a_contact_near_our_complement_at(ip(30, 1, 1, 1));
    low_id.given_udp_is_firewalled();
    low_id.given_the_buddy_search_asked(ip(30, 1, 1, 1));

    low_id.when_the_node_offers_to_be_the_buddy_of_our_complement(ip(30, 1, 1, 1));

    low_id.then_the_offer_is_taken_from(ip(30, 1, 1, 1));
}

#[test]
fn should_leave_out_a_buddy_offer_for_another_id() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_a_contact_near_our_complement_at(ip(30, 1, 1, 1));
    low_id.given_udp_is_firewalled();
    low_id.given_the_buddy_search_asked(ip(30, 1, 1, 1));

    low_id.when_the_node_offers_to_be_the_buddy_of_another_id(ip(30, 1, 1, 1));

    low_id.then_no_offer_is_taken();
}

#[test]
fn should_leave_out_a_buddy_offer_not_asked_for() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_a_contact_near_our_complement_at(ip(30, 1, 1, 1));
    low_id.given_udp_is_firewalled();
    low_id.given_the_buddy_search_asked(ip(30, 1, 1, 1));

    low_id.when_the_node_offers_to_be_the_buddy_of_our_complement(ip(31, 1, 1, 1));

    low_id.then_no_offer_is_taken();
}

#[test]
fn should_offer_to_be_the_buddy_of_a_firewalled_node_when_open() {
    let mut low_id = LowId::new();
    low_id.given_an_open_node_on_both_ports();

    low_id.when_a_firewalled_node_asks_us_to_be_its_buddy(ip(40, 1, 1, 1), 55);

    low_id.then_we_are_asked_to_be_the_buddy_of(ip(40, 1, 1, 1));
    low_id.then_our_offer_went_to(ip(40, 1, 1, 1), Some(3));
}

#[test]
fn should_offer_no_buddy_link_when_firewalled_or_serving_already() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();

    low_id.when_a_firewalled_node_asks_us_to_be_its_buddy(ip(40, 1, 1, 1), 55);

    low_id.then_no_offer_went_to(ip(40, 1, 1, 1));
}

#[test]
fn should_relay_a_callback_request_to_the_node_we_serve() {
    let mut low_id = LowId::new();
    low_id.given_we_serve_a_buddy();

    low_id.when_a_callback_request_comes(ip(50, 1, 1, 1), 4711);

    low_id.then_the_callback_is_relayed_from(ip(50, 1, 1, 1), 4711);
}

#[test]
fn should_ask_the_buddy_of_a_firewalled_source_plain_for_a_callback() {
    let mut low_id = LowId::new();

    low_id.when_the_buddy_of_a_source_is_asked_for_a_callback(ip(45, 1, 1, 1), 4673, [0xaa; 16]);

    low_id.then_a_plain_callback_request_went_to(ip(45, 1, 1, 1), 4673, [0xaa; 16]);
}

#[test]
fn should_find_firewalled_sources_with_their_buddy_or_their_direct_callback() {
    let mut low_id = LowId::new();
    let (via_buddy, direct) = (low_id.via_buddy, low_id.direct);
    low_id.given_a_search_result_reached_through_a_buddy(via_buddy, 5, ip(91, 1, 1, 1), ip(92, 1, 1, 1));
    low_id.given_a_search_result_for_direct_callbacks(direct, 0x08 | 0x01);
    low_id.given_a_search_result_for_direct_callbacks(UInt128(0x0102), 0x01);
    low_id.given_a_search_result_of_source_type_only(UInt128(0x0304), 3);

    low_id.when_the_search_results_are_read();

    low_id.then_the_sources_found_are(&[
        Source {
            user_hash: via_buddy.to_big_endian(),
            ip: ip(91, 1, 1, 1),
            tcp_port: TCP,
            connect_options: 0,
            udp_port: PORT,
            buddy: Some(Buddy { ip: ip(92, 1, 1, 1), udp_port: 4673, id: low_id.buddy_id }),
            direct_callback: false,
            ipv6: None,
        },
        Source {
            user_hash: direct.to_big_endian(),
            ip: ip(93, 1, 1, 1),
            tcp_port: TCP,
            connect_options: 0x09,
            udp_port: PORT,
            buddy: None,
            direct_callback: true,
            ipv6: None,
        },
    ]);
}

#[test]
fn should_read_the_ipv6_address_a_firewalled_source_gives() {
    let mut low_id = LowId::new();
    let direct = low_id.direct;
    low_id.given_a_search_result_for_direct_callbacks_with_the_ipv6_tag(direct, "20010DB8000000000000000000000007");

    low_id.when_the_search_results_are_read();

    low_id.then_the_sources_found_are(&[Source {
        user_hash: direct.to_big_endian(),
        ip: ip(93, 1, 1, 1),
        tcp_port: TCP,
        connect_options: 0x09,
        udp_port: PORT,
        buddy: None,
        direct_callback: true,
        ipv6: Some("2001:db8::7".parse().unwrap()),
    }]);
}

#[test]
fn should_leave_out_an_ipv6_tag_no_peer_can_be_reached_at() {
    for hex in ["FE800000000000000000000000000001", "20010DB80000000000000000000007", "not hex at all, 32 characters!!"]
    {
        let mut low_id = LowId::new();
        let direct = low_id.direct;
        low_id.given_a_search_result_for_direct_callbacks_with_the_ipv6_tag(direct, hex);

        low_id.when_the_search_results_are_read();

        low_id.then_the_source_found_gives_no_ipv6_address(hex);
    }
}

#[test]
fn should_publish_our_ipv6_address_when_we_have_one() {
    let mut low_id = LowId::new();
    low_id.given_we_have_the_ipv6_address("2001:db8::99");
    low_id.given_a_connected_node();
    low_id.given_udp_is_firewalled();
    low_id.given_our_buddy_is_at(ip(44, 1, 1, 1), 4673);

    low_id.when_we_are_published_as_a_source(1000);

    low_id.then_the_source_publish_gives_the_ipv6_address("20010DB8000000000000000000000099");
}

#[test]
fn should_publish_a_firewalled_node_with_a_buddy_as_a_source_reached_through_it() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_udp_is_firewalled();
    low_id.given_our_buddy_is_at(ip(44, 1, 1, 1), 4673);

    low_id.when_we_are_published_as_a_source(1000);

    low_id.then_the_source_type_is(3);
    low_id.then_the_source_names_our_buddy(ip(44, 1, 1, 1), 4673);
}

#[test]
fn should_publish_a_firewalled_node_with_an_open_udp_port_for_direct_callbacks() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_the_nodes_see_our_udp_port_as(PORT);
    low_id.given_a_lookup_found_helpers();
    low_id.given_the_first_helper_sent_its_datagram();

    low_id.when_we_are_published_as_a_source(1000);

    low_id.then_the_source_type_is(6);
    low_id.then_the_connect_options_are(0x08 | 3);
    low_id.then_the_node_takes_direct_callbacks();
}

#[test]
fn should_keep_no_note_of_others_when_firewalled_on_udp() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_udp_is_firewalled();

    low_id.when_a_note_on_a_file_near_us_is_published();

    low_id.then_no_note_is_kept();
}

#[test]
fn should_not_publish_a_firewalled_node_nobody_can_reach_as_a_source() {
    let mut low_id = LowId::new();
    low_id.given_a_connected_node();
    low_id.given_udp_is_firewalled();

    low_id.when_we_are_published_as_a_source(1000);

    low_id.then_no_source_publish_went();
    low_id.then_the_node_is_not_reachable();
}
