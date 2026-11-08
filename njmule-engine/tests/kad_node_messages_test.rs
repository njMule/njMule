// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodeMessagesTest, under its names: the Kad messages a node answers and takes.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use crate::kad_lab;

use std::net::Ipv4Addr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use kad_lab::{KadLab, PORT, Reach, Sent, ip};
use njmule_engine::api::kad_search_info::Kind;
use njmule_engine::api::search_result::Media;
use njmule_engine::kad::kad_contact;
use njmule_engine::kad::kad_identity::KadIdentity;
use njmule_engine::kad::kad_node::{self, FileResult, KadEvent, KadNode, SearchHandle, Settings, Source};
use njmule_engine::kad::kad_publisher::Node;
use njmule_engine::kad::keywords;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::{self, UdpKey};
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::kad::kad_messages::{
    self, Answer, BootstrapAnswer, Contact, Entry, FirewallCheck, Hello, KeywordPublish, KeywordSearch, PublishAnswer,
    Request, SearchAnswer, Sender, SourcePublish, SourceSearch,
};
use njmule_engine::protocol::packet::opcodes::kad;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag, tags};
use njmule_engine::search::search_expr::SearchExpr;
use njmule_engine::search::search_tree_codec;
use rand::RngExt;

const PEER: u32 = ip(20, 1, 1, 1);
const MINUTE: Duration = Duration::from_secs(60);
const USER: [u8; 16] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16];
// Recent nodes that greet us and so are asked to check our port.
const HELPERS: [u32; 4] = [ip(21, 1, 1, 1), ip(22, 1, 1, 1), ip(23, 1, 1, 1), ip(24, 1, 1, 1)];

struct Messages {
    lab: KadLab,
    identity: KadIdentity,
    node: KadNode,
    asker: UInt128,
    sources: Vec<Source>,
    file_results: Vec<FileResult>,
    peer_id: UInt128,
    contact_id: UInt128,
    target: UInt128,
    file_hash: [u8; 16],
    published_file: UInt128,
    published_source: UInt128,
    to_publish: Vec<Entry>,
    answered: Vec<Entry>,
    expression: Option<SearchExpr>,
    started: bool,
    search: Option<SearchHandle>,
    pongs: usize,
    direct: UInt128,
    large: UInt128,
    good: UInt128,
}

impl Messages {
    fn new() -> Self {
        let mut lab = KadLab::new();
        let identity = KadIdentity::load_or_create(lab.dir.path(), &mut lab.random).unwrap();
        let node = lab.node(Settings::new(4662, USER, 3, Arc::new(|_| false), Arc::new(|_| false)));
        let asker = UInt128::random(&mut lab.random);
        Self {
            lab,
            identity,
            node,
            asker,
            sources: Vec::new(),
            file_results: Vec::new(),
            peer_id: UInt128(0),
            contact_id: UInt128(0),
            target: UInt128(0),
            file_hash: [0; 16],
            published_file: UInt128(0),
            published_source: UInt128(0),
            to_publish: Vec::new(),
            answered: Vec::new(),
            expression: None,
            started: false,
            search: None,
            pongs: 0,
            direct: UInt128(0),
            large: UInt128(0),
            good: UInt128(0),
        }
    }

    // Steps.

    fn given_contacts_at(&mut self, ips: &[u32]) {
        for &ip in ips {
            let id = self.random_id();
            self.node.add_contacts(&contents(record(id, ip)));
        }
    }

    fn given_a_known_contact_at(&mut self, ip: u32) {
        self.contact_id = self.random_id();
        self.node.add_contacts(&contents(record(self.contact_id, ip)));
    }

    // A contact that greeted us: alive for an hour, so lookups take it.
    fn given_an_alive_contact_at(&mut self, ip: u32) {
        let id = self.random_id();
        self.node.add_contacts(&contents(record(id, ip)));
        let hello = Hello { sender: Sender { id, tcp_port: 4662, version: 8 }, tags: Vec::new() };
        self.receive(ip, &hello.encode(false).unwrap());
    }

    fn given_a_file(&mut self) {
        self.file_hash = self.lab.random.random();
        self.target = UInt128::from_big_endian(self.file_hash);
    }

    fn given_a_contact_at_the_file(&mut self) {
        self.contact_at(self.target);
    }

    fn given_a_contact_at_the_keyword(&mut self, word: &str) {
        self.target = keywords::id(word);
        self.contact_at(self.target);
    }

    fn given_a_bootstrap_request_went_to(&mut self, helper: u32) {
        self.node.bootstrap(helper, PORT);
        assert!(self.last(helper, kad::BOOTSTRAP_REQ).is_some());
    }

    fn given_a_node_said_hello(&mut self, ip: u32, sender_key: u32) {
        self.when_a_node_says_hello(ip, sender_key);
    }

    // An acknowledgement without our key proves nothing.
    fn given_the_node_acknowledged_without_our_key(&mut self, ip: u32) {
        self.receive_with(ip, &kad_messages::hello_ack(&self.peer_id), 0, 0);
        self.then_the_verified_contacts_are(0, "no key");
    }

    fn given_a_node_said_hello_with_version(&mut self, ip: u32, version: u8) {
        self.when_a_node_says_hello_with_version(ip, version);
    }

    fn given_the_contact_was_asked_whether_it_is_still_there(&mut self, ip: u32) {
        self.when_minutes_pass(1);
        self.then_the_contact_is_asked_whether_it_is_still_there(ip);
    }

    // A new contact is checked within a minute: unanswered, it counts as dead until it is removed.
    fn given_the_contact_left_its_check_unanswered(&mut self, ip: u32) {
        self.given_the_contact_was_asked_whether_it_is_still_there(ip);
        let types: Vec<u8> = self.node.contact_infos().into_iter().map(|info| info.contact_type).collect();
        assert_eq!(types, [kad_contact::DEAD]);
    }

    fn given_a_minute_passed_while_the_contact_was_waited_for(&mut self) {
        self.when_minutes_pass(1);
        self.then_the_contacts_are(1, "still waited for");
    }

    fn given_its_hello_answer_came_unasked(&mut self, ip: u32, sender_key: u32) {
        self.when_the_contact_sends_its_hello_answer(ip, sender_key);
        self.then_no_acknowledgement_went_to(ip);
    }

    fn given_the_contact_answered_its_check(&mut self, ip: u32, sender_key: u32) {
        self.when_the_contact_sends_its_hello_answer(ip, sender_key);
        self.then_our_acknowledgement_went_to(ip, sender_key);
    }

    fn given_a_file_published_to_us_under_our_id(&mut self, name: &str, size: u64) {
        self.when_a_file_is_published_to_us_under_our_id(name, size);
    }

    fn given_a_source_published_to_us_by(&mut self, ip: u32) {
        self.when_a_source_is_published_to_us_by(ip);
    }

    fn given_a_source_of_no_type_published_to_us_by(&mut self, ip: u32) {
        self.when_a_source_of_no_type_is_published_to_us_by(ip);
    }

    fn given_a_firewall_check_came_from(&mut self, ip: u32, port: u16) {
        self.when_a_firewall_check_comes_from(ip, port);
    }

    fn given_the_helpers_said_hello(&mut self) {
        for helper in HELPERS {
            self.hello(helper, 8);
        }
    }

    fn given_a_node_reported_our_address(&mut self, ip: u32, address: u32) {
        self.when_a_node_reports_our_address(ip, address);
    }

    fn given_the_helpers_reported_our_address(&mut self, address: u32) {
        for helper in HELPERS {
            self.hello(helper, 8);
            self.receive(helper, &kad_messages::firewall_answer(address));
        }
    }

    fn given_connections_confirmed_from(&mut self, ips: &[u32]) {
        for &ip in ips {
            self.node.reachable_confirmed(ip);
        }
    }

    fn given_the_firewall_was_rechecked_and_a_connection_is_awaited_from(&mut self, ip: u32) {
        self.when_the_firewall_is_rechecked();
        self.then_we_are_firewalled("rechecked");
        self.then_a_connection_is_awaited_from(ip, "still awaited");
    }

    fn given_a_node_pinged_from_port(&mut self, ip: u32, port: u16) {
        self.when_a_node_pings_from_port(ip, port);
    }

    // A pong nobody waits for changes nothing.
    fn given_a_pong_nobody_waits_for(&mut self, ip: u32) {
        self.receive(ip, &kad_messages::pong(PORT));
    }

    fn given_a_node_flooded_us_with_pings(&mut self, ip: u32) {
        self.given_a_node_pinged_from_port(ip, 5555);
        self.given_a_pong_nobody_waits_for(ip);
        self.when_a_node_pings(ip, 20);
        self.then_fewer_pongs_went_to(ip, 5);
        self.pongs = self.count(ip, kad::PONG);
    }

    fn given_the_banned_node_got_no_bootstrap_answer(&mut self, ip: u32) {
        self.when_a_node_asks_for_a_bootstrap(ip);
        self.then_no_bootstrap_answer_went_to(ip, "banned for everything");
    }

    fn given_a_source_search_started(&mut self, size: u64) {
        self.when_the_source_search_starts(size);
        self.then_it_started();
    }

    fn given_a_source_search_for_our_own_id_started(&mut self) {
        self.file_hash = self.node.id().to_big_endian();
        self.given_a_source_search_started(10);
    }

    fn given_the_lookup_reached_the_contact(&mut self) {
        self.when_the_lookup_reaches_the_contact();
    }

    fn given_a_keyword_search_started(&mut self, text: &str) {
        self.when_a_keyword_search_starts(text);
        assert!(self.search.is_some());
    }

    fn given_a_keyword_search_with_an_expression_started(&mut self, text: &str) {
        let expression = SearchExpr::Keyword(text.to_owned());
        self.search = self.node.search_keyword(text, Some(expression.clone()));
        self.expression = Some(expression);
        assert!(self.search.is_some());
    }

    fn given_the_contact_answered_with_files_of_every_kind(&mut self) {
        self.when_the_contact_answers_with_files_of_every_kind();
    }

    fn given_files_to_publish(&mut self, count: usize) {
        self.to_publish = (0..count)
            .map(|at| entry(UInt128::random(&mut self.lab.random), &format!("ubuntu {at}.iso"), 1000 + at as u64))
            .collect();
    }

    fn given_the_keyword_publish_started(&mut self, word: &str) {
        self.when_the_keyword_is_published_with_the_files(word);
        self.then_it_started();
    }

    fn given_the_keyword_publish_with_a_file_started(&mut self, word: &str, name: &str, size: u64) {
        self.when_the_keyword_is_published_with_a_file(word, name, size);
        self.then_it_started();
    }

    fn given_a_node_answered_the_publish(&mut self, ip: u32) {
        self.when_a_node_answers_the_publish(ip);
    }

    // Two nodes connected to our TCP port and said so: the node is open.
    fn given_the_node_is_reachable_through_tcp(&mut self) {
        for ip in [ip(26, 1, 1, 1), ip(27, 1, 1, 1)] {
            self.hello(ip, 8);
            self.node.reachable_confirmed(ip);
        }
    }

    fn given_a_source_publish_started(&mut self, size: u64) {
        self.when_we_are_published_as_a_source(size);
        self.then_it_started();
    }

    fn given_the_node_is_not_ready(&self) {
        self.then_the_node_is_not_ready();
    }

    fn given_our_own_id_is_looked_up_after_minutes(&mut self, minutes: u32) {
        self.when_minutes_pass(minutes);
        self.then_our_own_id_is_looked_up();
        self.then_the_node_is_not_ready();
    }

    fn given_the_first_lookups_were_sent(&mut self) {
        self.lab.advance(Duration::from_secs(11));
        self.node.tick();
        self.lab.advance(Duration::from_secs(10));
        self.node.tick();
        self.lab.take_sent();
    }

    fn given_a_quiet_node_looked_for_contacts_again_after_minutes(&mut self, minutes: u32) {
        self.when_minutes_pass(minutes);
        self.then_a_zone_is_looked_up_again();
        self.then_the_node_is_connected("heard from within twenty minutes");
    }

    fn given_an_unreadable_nodes_dat(&self) {
        std::fs::write(self.nodes_file(), [1, 2, 3]).unwrap();
    }

    fn given_the_contacts_were_loaded(&mut self) {
        self.when_the_contacts_are_loaded();
        self.then_the_contacts_are(0, "none readable");
    }

    fn given_the_nodes_dat_is_deleted(&self) {
        std::fs::remove_file(self.nodes_file()).unwrap();
    }

    fn given_the_temporary_nodes_file_cannot_be_written(&self) {
        let tmp = self.lab.dir.path().join(format!("{}.tmp", kad_node::NODES_FILE));
        std::fs::create_dir_all(&tmp).unwrap();
        std::fs::write(tmp.join("keep"), "x").unwrap();
    }

    fn when_a_node_asks_for_a_bootstrap(&mut self, ip: u32) {
        self.receive(ip, &kad_messages::bootstrap_request());
    }

    fn when_a_helper_answers_a_bootstrap(&mut self, helper: u32) {
        let contacts = vec![
            self.contact(ip(30, 1, 1, 1)),
            self.contact(ip(192, 168, 1, 9)),
            Contact { id: self.random_id(), ip: ip(31, 1, 1, 1), udp_port: PORT, tcp_port: 0, version: 1 },
        ];
        let answer = BootstrapAnswer { sender: Sender { id: self.random_id(), tcp_port: 4662, version: 8 }, contacts };
        self.receive(helper, &answer.encode().unwrap());
    }

    fn when_a_node_says_hello(&mut self, ip: u32, sender_key: u32) {
        self.peer_id = self.random_id();
        let hello = Hello { sender: Sender { id: self.peer_id, tcp_port: 4662, version: 8 }, tags: Vec::new() };
        self.receive_with(ip, &hello.encode(false).unwrap(), 0, sender_key);
    }

    fn when_the_node_acknowledges_with_our_key(&mut self, ip: u32) {
        let key = self.identity.verify_key(ip);
        self.receive_with(ip, &kad_messages::hello_ack(&self.peer_id), key, 0);
    }

    fn when_a_node_says_hello_with_version(&mut self, ip: u32, version: u8) {
        self.hello(ip, version);
    }

    fn when_a_node_behind_a_firewall_says_hello(&mut self, ip: u32) {
        let udp_firewalled = Tag::Int { name: TagName::id(file_tag::KADMISCOPTIONS), value: 0x01 };
        let hello =
            Hello { sender: Sender { id: self.random_id(), tcp_port: 4662, version: 8 }, tags: vec![udp_firewalled] };
        self.receive(ip, &hello.encode(false).unwrap());
    }

    fn when_minutes_pass(&mut self, minutes: u32) {
        self.lab.advance(MINUTE * minutes);
        self.node.tick();
    }

    fn when_minutes_pass_one_by_one(&mut self, minutes: u32) {
        for _ in 0..minutes {
            self.when_minutes_pass(1);
        }
    }

    fn when_minutes_pass_without_a_tick(&self, minutes: u32) {
        self.lab.advance(MINUTE * minutes);
    }

    // A hello answer that asks for an acknowledgement.
    fn when_the_contact_sends_its_hello_answer(&mut self, ip: u32, sender_key: u32) {
        let hello = Hello {
            sender: Sender { id: self.contact_id, tcp_port: 4662, version: 8 },
            tags: vec![Tag::Int { name: TagName::id(file_tag::KADMISCOPTIONS), value: 0x04 }],
        };
        self.receive_with(ip, &hello.encode(true).unwrap(), 0, sender_key);
    }

    fn when_a_lookup_meant_for_us_comes(&mut self) {
        self.target = self.random_id();
        let request = Request { count: 2, target: self.target, receiver: self.node.id() };
        self.receive(PEER, &request.encode().unwrap());
    }

    fn when_a_lookup_meant_for_another_node_comes(&mut self) {
        self.target = self.random_id();
        let request = Request { count: 2, target: self.target, receiver: self.random_id() };
        self.receive(PEER, &request.encode().unwrap());
    }

    fn when_a_file_is_published_to_us_under_our_id(&mut self, name: &str, size: u64) {
        self.published_file = self.random_id();
        let publish = KeywordPublish { keyword: self.node.id(), files: vec![entry(self.published_file, name, size)] };
        self.receive(PEER, &publish.encode().unwrap());
    }

    fn when_a_node_searches_our_id(&mut self, ip: u32) {
        let search = KeywordSearch { target: self.node.id(), start: 0, expression: Vec::new() };
        self.receive(ip, &search.encode());
    }

    fn when_a_node_searches_our_id_for(&mut self, ip: u32, word: &str) {
        let expression = tree(&SearchExpr::Keyword(word.to_owned()));
        self.receive(ip, &KeywordSearch { target: self.node.id(), start: 0, expression }.encode());
    }

    fn when_a_keyword_far_from_us_is_published_by(&mut self, ip: u32) {
        let far = far_from(self.node.id());
        let publish = KeywordPublish { keyword: far, files: vec![entry(far, "far away.iso", 10)] };
        self.receive(ip, &publish.encode().unwrap());
    }

    fn when_a_source_far_from_us_is_published_by(&mut self, ip: u32) {
        let publish = SourcePublish { file: far_from(self.node.id()), source: self.random_id(), tags: source_tags(1) };
        self.receive(ip, &publish.encode().unwrap());
    }

    fn when_a_source_is_published_to_us_by(&mut self, ip: u32) {
        self.published_source = self.random_id();
        let publish = SourcePublish { file: self.node.id(), source: self.published_source, tags: source_tags(1) };
        self.receive(ip, &publish.encode().unwrap());
    }

    fn when_a_source_of_no_type_is_published_to_us_by(&mut self, ip: u32) {
        let publish = SourcePublish { file: self.node.id(), source: self.random_id(), tags: Vec::new() };
        self.receive(ip, &publish.encode().unwrap());
    }

    fn when_a_node_searches_sources_of_our_id(&mut self, ip: u32) {
        self.receive(ip, &SourceSearch { target: self.node.id(), start: 0, size: 0 }.encode());
    }

    fn when_a_firewall_check_comes_from(&mut self, ip: u32, port: u16) {
        self.receive(ip, &FirewallCheck { tcp_port: port, user_hash: self.asker, connect_options: 1 }.encode());
    }

    fn when_a_node_reports_our_address(&mut self, ip: u32, address: u32) {
        self.receive(ip, &kad_messages::firewall_answer(address));
    }

    fn when_the_firewall_is_rechecked(&mut self) {
        self.node.recheck_firewall();
        self.node.tick();
    }

    fn when_a_node_pings_from_port(&mut self, ip: u32, port: u16) {
        self.node.received(ip, port, &kad_messages::ping(), 0, 0);
    }

    fn when_a_node_pings(&mut self, ip: u32, times: u32) {
        for _ in 0..times {
            self.receive(ip, &kad_messages::ping());
        }
    }

    fn when_the_node_pings_again_after_minutes(&mut self, ip: u32, minutes: u32) {
        self.lab.advance(MINUTE * minutes);
        self.receive(ip, &kad_messages::ping());
    }

    fn when_a_node_sends(&mut self, ip: u32, packet: &Packet) {
        self.receive(ip, packet);
    }

    fn when_the_source_search_starts(&mut self, size: u64) {
        self.started = self.node.find_sources(self.file_hash, size);
    }

    // Lets the lookup towards the target ask the contact at that very id, which then gets the request.
    fn when_the_lookup_reaches_the_contact(&mut self) {
        self.receive(PEER, &Answer { target: self.target, contacts: Vec::new() }.encode().unwrap());
        self.lab.advance(Duration::from_secs(4));
        self.node.tick();
    }

    fn when_the_contact_answers_with_sources_of_every_kind(&mut self) {
        self.direct = self.random_id();
        self.large = self.random_id();
        let entries = vec![
            Entry { id: self.direct, tags: source(1, ip(90, 1, 1, 1), 4662, 0) },
            Entry { id: self.large, tags: source(4, ip(91, 1, 1, 1), 4663, 3) },
            Entry { id: self.random_id(), tags: source(3, ip(92, 1, 1, 1), 4662, 0) },
            Entry { id: self.random_id(), tags: source(1, 0, 4662, 0) },
            Entry { id: self.random_id(), tags: source(1, ip(93, 1, 1, 1), 0, 0) },
        ];
        let answer = SearchAnswer { sender: self.target, target: self.target, results: entries };
        self.receive(PEER, &answer.encode().unwrap());
    }

    fn when_the_contact_answers_with_files_of_every_kind(&mut self) {
        self.good = self.random_id();
        self.answered = vec![
            Entry {
                id: self.good,
                tags: vec![
                    string(file_tag::FILENAME, "Ubuntu Desktop 24.iso"),
                    int(file_tag::FILESIZE, 5000),
                    int(file_tag::SOURCES, 7),
                    string(file_tag::FILETYPE, "Iso"),
                    string(file_tag::MEDIA_TITLE, "Noble Numbat"),
                    int(file_tag::MEDIA_LENGTH, 95),
                ],
            },
            entry(self.random_id(), "ubuntu server.iso", 5000),
            entry(self.random_id(), "ubuntu desktop.iso", 0),
            Entry { id: self.random_id(), tags: vec![int(file_tag::FILESIZE, 5)] },
        ];
        let answer = SearchAnswer { sender: self.target, target: self.target, results: self.answered.clone() };
        self.receive(PEER, &answer.encode().unwrap());
    }

    fn when_the_same_files_come_for_the_keyword(&mut self, word: &str) {
        let other = keywords::id(word);
        let answer = SearchAnswer { sender: other, target: other, results: self.answered.clone() };
        self.receive(PEER, &answer.encode().unwrap());
    }

    fn when_a_keyword_search_starts(&mut self, text: &str) {
        self.search = self.node.search_keyword(text, None);
    }

    fn when_we_are_published_as_a_source(&mut self, size: u64) {
        self.started = self.node.publish_source(self.file_hash, size);
    }

    fn when_the_keyword_is_published_with_a_file(&mut self, word: &str, name: &str, size: u64) {
        let file = entry(self.random_id(), name, size);
        self.started = self.node.publish_keyword(word, vec![file]);
    }

    fn when_the_keyword_is_published_without_files(&mut self, word: &str) {
        self.started = self.node.publish_keyword(word, Vec::new());
    }

    fn when_the_keyword_is_published_with_the_files(&mut self, word: &str) {
        self.started = self.node.publish_keyword(word, self.to_publish.clone());
    }

    fn when_a_node_answers_the_publish(&mut self, ip: u32) {
        self.receive(ip, &PublishAnswer { target: self.target, load: 3 }.encode());
    }

    fn when_the_node_looks_for_contacts(&mut self) {
        self.node.look_for_contacts();
        self.node.tick();
    }

    fn when_the_contacts_are_loaded(&mut self) {
        self.node.load_nodes();
    }

    fn when_the_node_closes(&mut self) {
        self.node.close();
    }

    fn when_the_contacts_are_saved(&mut self) {
        self.node.save_nodes();
    }

    fn when_a_stranger_answers_a_lookup(&mut self, stranger: u32) {
        let contacts = vec![self.contact(ip(41, 1, 1, 1))];
        self.receive(stranger, &Answer { target: keywords::id("ubuntu"), contacts }.encode().unwrap());
    }

    fn when_a_stranger_answers_a_search(&mut self, ip: u32) {
        let keyword = keywords::id("ubuntu");
        let answer = SearchAnswer { sender: keyword, target: keyword, results: vec![entry(keyword, "ubuntu.iso", 10)] };
        self.receive(ip, &answer.encode().unwrap());
    }

    fn when_the_contact_answers_with_contacts_of_every_kind(&mut self) {
        let contacts = vec![
            Contact { id: self.random_id(), ip: ip(41, 1, 1, 1), udp_port: PORT, tcp_port: 0, version: 1 },
            self.contact(ip(192, 168, 7, 7)),
            Contact { id: self.random_id(), ip: ip(42, 1, 1, 1), udp_port: 53, tcp_port: 0, version: 5 },
            self.contact(ip(43, 1, 1, 1)),
        ];
        self.receive(PEER, &Answer { target: self.target, contacts }.encode().unwrap());
    }

    fn then_our_bootstrap_answer_to_lists_the_contacts_at(&self, ip: u32, addresses: &[u32]) {
        let ours = BootstrapAnswer::decode(&self.last(ip, kad::BOOTSTRAP_RES).unwrap()).unwrap();
        assert_eq!(ours.sender.id, self.node.id());
        assert_eq!(ours.sender.tcp_port, 4662);
        let mut listed: Vec<u32> = ours.contacts.iter().map(|contact| contact.ip).collect();
        let mut expected = addresses.to_vec();
        listed.sort_unstable();
        expected.sort_unstable();
        assert_eq!(listed, expected);
    }

    fn then_the_contacts_are(&self, count: usize, because: &str) {
        assert_eq!(self.node.contacts(), count, "{because}");
    }

    fn then_the_verified_contacts_are(&self, count: usize, because: &str) {
        assert_eq!(self.node.verified_contacts(), count, "{because}");
    }

    fn then_our_hello_answer_is_obfuscated_with_its_id_and_key(&self, ip: u32, key: u32) {
        let reply = self.sent_with(ip, kad::HELLO_RES);
        assert_eq!(reply.receiver_id, Some(self.peer_id), "obfuscated with its id");
        assert_eq!(reply.receiver_key, key, "and the key it sent");
    }

    fn then_our_hello_answer_asks_for_an_acknowledgement(&self, ip: u32) {
        let hello = Hello::decode(&self.sent_with(ip, kad::HELLO_RES).packet).unwrap();
        assert_eq!(hello.sender.id, self.node.id());
        assert_eq!(
            tags::int_value(&hello.tags, file_tag::KADMISCOPTIONS),
            Some(0x02 | 0x04),
            "TCP-firewalled, UDP not known to be firewalled yet, and asking for an acknowledgement"
        );
    }

    fn then_the_node_is_asked_to_check_our_port(&self, ip: u32, because: &str) {
        assert!(self.last(ip, kad::FIREWALLED2_REQ).is_some(), "{because}");
    }

    fn then_the_node_is_not_asked_to_check_our_port(&self, ip: u32, because: &str) {
        assert!(self.last(ip, kad::FIREWALLED2_REQ).is_none(), "{because}");
    }

    fn then_our_hello_answer_is_plain(&self, ip: u32) {
        let plain = self.sent_with(ip, kad::HELLO_RES);
        assert_eq!(plain.receiver_id, None, "too old for obfuscation");
        assert!(Hello::decode(&plain.packet).unwrap().tags.is_empty());
    }

    fn then_our_hello_answer_went_to(&self, ip: u32) {
        self.sent_with(ip, kad::HELLO_RES);
    }

    fn then_the_contact_is_asked_whether_it_is_still_there(&self, ip: u32) {
        assert!(self.last(ip, kad::HELLO_REQ).is_some(), "asked whether it is still there");
    }

    fn then_no_acknowledgement_went_to(&self, ip: u32) {
        assert!(self.last(ip, kad::HELLO_RES_ACK).is_none(), "not asked for");
    }

    fn then_our_acknowledgement_went_to(&self, ip: u32, key: u32) {
        let ack = self.sent_with(ip, kad::HELLO_RES_ACK);
        assert_eq!(kad_messages::decode_hello_ack(&ack.packet).unwrap(), self.node.id());
        assert_eq!(ack.receiver_key, key);
    }

    fn then_the_lookup_is_answered(&self) {
        assert_eq!(Answer::decode(&self.last(PEER, kad::RES).unwrap()).unwrap().target, self.target);
    }

    fn then_the_lookup_is_not_answered(&self) {
        assert!(self.last(PEER, kad::RES).is_none());
    }

    fn then_the_publish_is_answered_for_our_id(&self, ip: u32) {
        let load = PublishAnswer::decode(&self.last(ip, kad::PUBLISH_RES).unwrap()).unwrap();
        assert_eq!(load.target, self.node.id());
    }

    fn then_the_keywords_indexed_are(&self, count: usize) {
        assert_eq!(self.node.indexed().0, count);
    }

    fn then_the_search_answer_lists_the_published_file(&self, ip: u32) {
        let answer = SearchAnswer::decode(&self.last(ip, kad::SEARCH_RES).unwrap()).unwrap();
        assert_eq!(answer.results.iter().map(|entry| entry.id).collect::<Vec<_>>(), [self.published_file]);
    }

    fn then_a_search_answer_went_to(&self, ip: u32, because: &str) {
        assert!(self.last(ip, kad::SEARCH_RES).is_some(), "{because}");
    }

    fn then_no_search_answer_went_to(&self, ip: u32, because: &str) {
        assert!(self.last(ip, kad::SEARCH_RES).is_none(), "{because}");
    }

    fn then_no_publish_answer_went_to(&self, ip: u32, because: &str) {
        assert!(self.last(ip, kad::PUBLISH_RES).is_none(), "{because}");
    }

    fn then_nothing_is_indexed(&self) {
        assert_eq!(self.node.indexed(), (0, 0));
    }

    fn then_the_search_answer_lists_the_published_source_at(&self, searcher: u32, ip: u32) {
        let found = SearchAnswer::decode(&self.last(searcher, kad::SEARCH_RES).unwrap()).unwrap().results;
        assert_eq!(found.iter().map(|entry| entry.id).collect::<Vec<_>>(), [self.published_source]);
        assert_eq!(tags::int_value(&found[0].tags, file_tag::SOURCEIP), Some(u64::from(ip)));
    }

    fn then_our_firewall_answer_to_says_its_address(&self, ip: u32) {
        let answer = self.last(ip, kad::FIREWALLED_RES).unwrap();
        assert_eq!(kad_messages::decode_firewall_answer(&answer).unwrap(), ip);
    }

    fn then_the_host_is_asked_to_connect_to(&self, ip: u32, port: u16) {
        let expected = Reach { ip, port, user_hash: self.asker.to_big_endian(), connect_options: 1 };
        assert_eq!(*self.lab.checks.reaches.lock().unwrap(), [expected]);
    }

    fn then_no_firewall_answer_went_to(&self, ip: u32) {
        assert!(self.last(ip, kad::FIREWALLED_RES).is_none());
    }

    fn then_the_host_is_asked_for_checks(&self, count: usize) {
        assert_eq!(self.lab.checks.reaches.lock().unwrap().len(), count);
    }

    fn then_our_address_is(&self, address: u32, because: &str) {
        assert_eq!(self.node.public_ip(), address, "{because}");
    }

    fn then_we_are_firewalled(&self, because: &str) {
        assert!(self.node.is_firewalled(), "{because}");
    }

    fn then_a_connection_is_awaited_from(&self, ip: u32, because: &str) {
        assert!(self.node.awaits_connection_from(ip), "{because}");
    }

    fn then_no_connection_is_awaited_from(&self, ip: u32, because: &str) {
        assert!(!self.node.awaits_connection_from(ip), "{because}");
    }

    fn then_our_pong_to_says_the_port(&self, ip: u32, port: u16) {
        assert_eq!(kad_messages::decode_pong(&self.last(ip, kad::PONG).unwrap()).unwrap(), port);
    }

    fn then_fewer_pongs_went_to(&self, ip: u32, limit: usize) {
        assert!(self.count(ip, kad::PONG) < limit, "only a few are answered");
    }

    fn then_no_bootstrap_answer_went_to(&self, ip: u32, because: &str) {
        assert!(self.last(ip, kad::BOOTSTRAP_RES).is_none(), "{because}");
    }

    fn then_one_more_pong_went_to(&self, ip: u32, because: &str) {
        assert_eq!(self.count(ip, kad::PONG), self.pongs + 1, "{because}");
    }

    fn then_nothing_is_sent(&self, because: &str) {
        assert!(self.lab.sent().is_empty(), "{because}");
    }

    fn then_it_started(&self) {
        assert!(self.started);
    }

    fn then_it_did_not_start(&self, because: &str) {
        assert!(!self.started, "{because}");
    }

    fn then_the_source_search_asks_for_the_size(&self, size: u64) {
        let request = SourceSearch::decode(&self.last(PEER, kad::SEARCH_SOURCE_REQ).unwrap()).unwrap();
        assert_eq!(request.size, size);
    }

    fn then_only_the_sources_that_can_be_reached_directly_are_found(&mut self) {
        self.take_events();
        let reached = |hash: UInt128, ip: u32, tcp_port: u16, connect_options: u8| Source {
            user_hash: hash.to_big_endian(),
            ip,
            tcp_port,
            connect_options,
            udp_port: 0,
            buddy: None,
            direct_callback: false,
            ipv6: None,
        };
        let expected = [reached(self.direct, ip(90, 1, 1, 1), 4662, 0), reached(self.large, ip(91, 1, 1, 1), 4663, 3)];
        assert_eq!(self.sources, expected);
    }

    fn then_the_expression_goes_along(&self) {
        let request = KeywordSearch::decode(&self.last(PEER, kad::SEARCH_KEY_REQ).unwrap()).unwrap();
        assert_eq!(request.expression, tree(self.expression.as_ref().unwrap()), "the expression goes along");
    }

    fn then_only_the_file_that_matches_all_the_words_is_found(&mut self) {
        self.take_events();
        let expected = FileResult {
            hash: self.good.to_big_endian(),
            name: "Ubuntu Desktop 24.iso".to_owned(),
            size: 5000,
            sources: 7,
            file_type: Some("Iso".to_owned()),
            media: Media {
                artist: String::new(),
                album: String::new(),
                title: "Noble Numbat".to_owned(),
                length: 95,
                bitrate: 0,
                codec: String::new(),
            },
            aich_hash: None,
        };
        assert_eq!(self.file_results, [expected]);
    }

    fn then_the_files_found_are(&mut self, count: usize) {
        self.take_events();
        assert_eq!(self.file_results.len(), count);
    }

    fn then_the_search_did_not_start(&self, because: &str) {
        assert!(self.search.is_none(), "{because}");
    }

    fn then_no_lookup_runs(&self) {
        assert_eq!(self.node.running_lookups(), 0);
    }

    fn then_the_keyword_publishes_running_are(&self, count: usize) {
        assert_eq!(self.node.running_keyword_publishes(), count);
    }

    fn then_the_source_publishes_running_are(&self, count: usize) {
        assert_eq!(self.node.running_source_publishes(), count);
    }

    fn then_keyword_publishes_went_to_the_contact(&self, count: usize) {
        assert_eq!(self.count(PEER, kad::PUBLISH_KEY_REQ), count);
    }

    fn then_the_last_keyword_publish_carries_files(&self, count: usize) {
        let publish = KeywordPublish::decode(&self.last(PEER, kad::PUBLISH_KEY_REQ).unwrap()).unwrap();
        assert_eq!(publish.files.len(), count);
    }

    fn then_the_only_lookup_is(&self, kind: Kind, answers: Option<u32>) {
        let lookups = self.node.lookup_infos();
        assert_eq!(lookups.len(), 1);
        assert_eq!(lookups[0].kind, kind);
        if let Some(answers) = answers {
            assert_eq!(lookups[0].answers, answers);
        }
    }

    fn then_the_first_lookup_has_answers(&self, answers: u32) {
        assert_eq!(self.node.lookup_infos()[0].answers, answers);
    }

    fn then_our_source_publish_names_the_file_and_us(&self) {
        let publish = self.source_publish();
        assert_eq!(publish.file, self.target);
        assert_eq!(publish.source, UInt128::from_big_endian(USER));
    }

    fn then_our_source_publish_carries(&self, source_type: u64, port: u64, size: u64, encryption: u64) {
        let found = self.source_publish().tags;
        assert_eq!(tags::int_value(&found, file_tag::SOURCETYPE), Some(source_type), "a large file");
        assert_eq!(tags::int_value(&found, file_tag::SOURCEPORT), Some(port));
        assert_eq!(tags::int_value(&found, file_tag::FILESIZE), Some(size));
        assert_eq!(tags::int_value(&found, file_tag::ENCRYPTION), Some(encryption));
    }

    fn then_the_lookups_include(&self, kinds: &[Kind]) {
        let running: Vec<Kind> = self.node.lookup_infos().into_iter().map(|info| info.kind).collect();
        assert!(kinds.iter().all(|kind| running.contains(kind)), "{running:?}");
    }

    fn then_every_running_lookup_is_shown(&self) {
        assert_eq!(self.node.running_lookups(), self.node.lookup_infos().len());
    }

    fn then_the_node_is_ready(&self) {
        assert!(self.node.is_ready());
    }

    fn then_the_node_is_not_ready(&self) {
        assert!(!self.node.is_ready());
    }

    fn then_our_own_id_is_looked_up(&self) {
        assert!(self.requested_targets().contains(&self.node.id()), "our own id is looked up");
    }

    fn then_our_own_id_is_looked_up_once(&self) {
        assert_eq!(self.requested_targets().iter().filter(|&&target| target == self.node.id()).count(), 1);
    }

    fn then_a_zone_is_looked_up_again(&self) {
        let own = self.node.id();
        assert!(
            self.requested_targets().iter().any(|&target| target != own),
            "a zone is looked up again, besides our own id"
        );
    }

    fn then_the_node_is_connected(&self, because: &str) {
        assert!(self.node.is_connected(), "{because}");
    }

    fn then_the_node_is_not_connected(&self) {
        assert!(!self.node.is_connected());
    }

    fn then_there_is_no_nodes_dat(&self, because: &str) {
        assert!(!self.nodes_file().exists(), "{because}");
    }

    fn then_the_contact_addresses_are(&self, addresses: &[&str]) {
        let mut found: Vec<String> = self.node.contact_infos().into_iter().map(|info| info.address).collect();
        let mut expected: Vec<String> = addresses.iter().map(|&address| address.to_owned()).collect();
        found.sort_unstable();
        expected.sort_unstable();
        assert_eq!(found, expected);
    }

    // Helpers.

    fn take_events(&mut self) {
        for event in self.node.take_events() {
            match event {
                KadEvent::Sources { sources, .. } => self.sources.extend(sources),
                KadEvent::Files { results, .. } => self.file_results.extend(results),
                _ => {}
            }
        }
    }

    fn nodes_file(&self) -> PathBuf {
        self.lab.dir.path().join(kad_node::NODES_FILE)
    }

    fn source_publish(&self) -> SourcePublish {
        SourcePublish::decode(&self.last(PEER, kad::PUBLISH_SOURCE_REQ).unwrap()).unwrap()
    }

    fn requested_targets(&self) -> Vec<UInt128> {
        self.lab
            .sent()
            .iter()
            .filter(|sent| sent.packet.opcode() == kad::REQ)
            .map(|sent| Request::decode(&sent.packet).unwrap().target)
            .collect()
    }

    fn contact_at(&mut self, id: UInt128) {
        self.node.add_contacts(&contents(record(id, PEER)));
    }

    fn hello(&mut self, ip: u32, version: u8) {
        let hello = Hello { sender: Sender { id: self.random_id(), tcp_port: 4662, version }, tags: Vec::new() };
        self.receive(ip, &hello.encode(false).unwrap());
    }

    fn receive(&mut self, ip: u32, packet: &Packet) {
        self.receive_with(ip, packet, 0, 0);
    }

    fn receive_with(&mut self, ip: u32, packet: &Packet, receiver_key: u32, sender_key: u32) {
        self.node.received(ip, PORT, packet, receiver_key, sender_key);
    }

    fn last(&self, ip: u32, opcode: u8) -> Option<Packet> {
        self.last_sent(ip, opcode).map(|sent| sent.packet)
    }

    fn sent_with(&self, ip: u32, opcode: u8) -> Sent {
        let sent = self.last_sent(ip, opcode);
        assert!(sent.is_some(), "{opcode:#x} sent to {}", Ipv4Addr::from(ip));
        sent.unwrap()
    }

    fn last_sent(&self, ip: u32, opcode: u8) -> Option<Sent> {
        self.lab.sent().into_iter().rev().find(|sent| sent.ip == ip && sent.packet.opcode() == opcode)
    }

    fn count(&self, ip: u32, opcode: u8) -> usize {
        self.lab.sent().iter().filter(|sent| sent.ip == ip && sent.packet.opcode() == opcode).count()
    }

    fn contact(&mut self, ip: u32) -> Contact {
        Contact { id: self.random_id(), ip, udp_port: PORT, tcp_port: 4662, version: 8 }
    }

    fn random_id(&mut self) -> UInt128 {
        UInt128::random(&mut self.lab.random)
    }
}

fn record(id: UInt128, ip: u32) -> nodes_dat::Contact {
    nodes_dat::Contact {
        id,
        ip,
        udp_port: PORT,
        tcp_port: 4662,
        version: 8,
        contact_type: 0,
        udp_key: UdpKey::default(),
        verified: true,
    }
}

fn contents(contact: nodes_dat::Contact) -> nodes_dat::Contents {
    nodes_dat::Contents { file_version: 2, bootstrap: false, contacts: vec![contact] }
}

fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

fn string(id: u8, value: &str) -> Tag {
    Tag::String { name: TagName::id(id), value: value.to_owned() }
}

fn entry(id: UInt128, name: &str, size: u64) -> Entry {
    Entry { id, tags: vec![string(file_tag::FILENAME, name), int(file_tag::FILESIZE, size)] }
}

fn source_tags(source_type: u64) -> Vec<Tag> {
    vec![int(file_tag::SOURCETYPE, source_type), int(file_tag::SOURCEPORT, 4662)]
}

fn source(source_type: u64, ip: u32, port: u64, encryption: u64) -> Vec<Tag> {
    vec![
        int(file_tag::SOURCETYPE, source_type),
        int(file_tag::SOURCEIP, u64::from(ip)),
        int(file_tag::SOURCEPORT, port),
        int(file_tag::ENCRYPTION, encryption),
    ]
}

fn tree(expression: &SearchExpr) -> Vec<u8> {
    let mut out = PacketWriter::new();
    search_tree_codec::write(&mut out, expression, true).unwrap();
    out.into_byte_array()
}

// An id whose top bits differ from ours: far beyond the tolerance of a publish.
fn far_from(id: UInt128) -> UInt128 {
    id ^ UInt128(u128::from(u64::MAX) << 64)
}

fn dropped_packets() -> [(&'static str, Packet); 4] {
    [
        ("unknown opcode", Packet::of(protocol::KAD, 0xEE, Vec::new())),
        ("buddy request without buddies", Packet::of(protocol::KAD, kad::FINDBUDDY_REQ, vec![0; 20])),
        ("malformed hello", Packet::of(protocol::KAD, kad::HELLO_REQ, vec![0; 3])),
        ("malformed firewall answer", Packet::of(protocol::KAD, kad::FIREWALLED_RES, vec![0; 3])),
    ]
}

// Loaded contacts we never heard from may be gone: the asking node would take them as verified.
#[test]
fn should_answer_a_bootstrap_request_with_the_contacts_we_heard_from() {
    let mut messages = Messages::new();
    messages.given_contacts_at(&[ip(70, 1, 1, 1), ip(71, 1, 1, 1)]);
    messages.given_a_node_said_hello_with_version(ip(72, 1, 1, 1), 8);
    messages.given_a_node_said_hello_with_version(ip(73, 1, 1, 1), 8);

    messages.when_a_node_asks_for_a_bootstrap(PEER);

    messages.then_our_bootstrap_answer_to_lists_the_contacts_at(PEER, &[ip(72, 1, 1, 1), ip(73, 1, 1, 1)]);
}

#[test]
fn should_leave_a_dead_contact_out_of_a_bootstrap_answer() {
    let mut messages = Messages::new();
    messages.given_a_node_said_hello_with_version(ip(72, 1, 1, 1), 8);
    messages.given_the_contact_left_its_check_unanswered(ip(72, 1, 1, 1));

    messages.when_a_node_asks_for_a_bootstrap(PEER);

    messages.then_our_bootstrap_answer_to_lists_the_contacts_at(PEER, &[]);
}

#[test]
fn should_drop_a_bootstrap_answer_nobody_asked_for() {
    let mut messages = Messages::new();
    messages.given_contacts_at(&[ip(70, 1, 1, 1), ip(71, 1, 1, 1)]);

    messages.when_a_helper_answers_a_bootstrap(ip(25, 1, 1, 1));

    messages.then_the_contacts_are(2, "an answer nobody asked for");
}

#[test]
fn should_take_only_the_bootstrap_answer_to_its_own_request() {
    let mut messages = Messages::new();
    messages.given_contacts_at(&[ip(70, 1, 1, 1), ip(71, 1, 1, 1)]);
    messages.given_a_bootstrap_request_went_to(ip(25, 1, 1, 1));

    messages.when_a_helper_answers_a_bootstrap(ip(25, 1, 1, 1));

    messages.then_the_contacts_are(4, "the helper and its one usable contact, not a LAN address or an old version");
    messages.then_the_verified_contacts_are(3, "the helper proved itself by answering");
}

#[test]
fn should_answer_a_hello_obfuscated_and_ask_for_an_acknowledgement() {
    let mut messages = Messages::new();

    messages.when_a_node_says_hello(PEER, 77);

    messages.then_the_contacts_are(1, "the node");
    messages.then_the_verified_contacts_are(0, "not yet");
    messages.then_our_hello_answer_is_obfuscated_with_its_id_and_key(PEER, 77);
    messages.then_our_hello_answer_asks_for_an_acknowledgement(PEER);
    messages.then_the_node_is_asked_to_check_our_port(PEER, "a recent node is asked to check our port");
}

#[test]
fn should_verify_a_node_that_acknowledges_with_our_key() {
    let mut messages = Messages::new();
    messages.given_a_node_said_hello(PEER, 77);
    messages.given_the_node_acknowledged_without_our_key(PEER);

    messages.when_the_node_acknowledges_with_our_key(PEER);

    messages.then_the_verified_contacts_are(1, "our key");
}

#[test]
fn should_answer_an_old_node_plain_and_take_it() {
    let mut messages = Messages::new();

    messages.when_a_node_says_hello_with_version(ip(21, 1, 1, 1), 5);

    messages.then_our_hello_answer_is_plain(ip(21, 1, 1, 1));
    messages.then_the_contacts_are(1, "the old node");
    messages.then_the_node_is_not_asked_to_check_our_port(ip(21, 1, 1, 1), "too old to check our port");
}

#[test]
fn should_answer_a_node_behind_a_firewall_but_not_take_it() {
    let mut messages = Messages::new();
    messages.given_a_node_said_hello_with_version(ip(21, 1, 1, 1), 5);

    messages.when_a_node_behind_a_firewall_says_hello(ip(22, 1, 1, 1));

    messages.then_our_hello_answer_went_to(ip(22, 1, 1, 1));
    messages.then_the_contacts_are(1, "a node others cannot reach is no contact");
}

#[test]
fn should_check_the_oldest_contact_after_a_minute() {
    let mut messages = Messages::new();
    messages.given_contacts_at(&[ip(70, 1, 1, 1)]);

    messages.when_minutes_pass(1);

    messages.then_the_contact_is_asked_whether_it_is_still_there(ip(70, 1, 1, 1));
}

#[test]
fn should_drop_a_silent_contact_once_its_check_expired() {
    let mut messages = Messages::new();
    messages.given_contacts_at(&[ip(70, 1, 1, 1)]);
    messages.given_the_contact_was_asked_whether_it_is_still_there(ip(70, 1, 1, 1));
    messages.given_a_minute_passed_while_the_contact_was_waited_for();

    messages.when_minutes_pass(1);

    messages.then_the_contacts_are(0, "gone once its check expired");
}

#[test]
fn should_not_acknowledge_a_hello_answer_not_asked_for() {
    let mut messages = Messages::new();
    messages.given_a_known_contact_at(ip(70, 1, 1, 1));

    messages.when_the_contact_sends_its_hello_answer(ip(70, 1, 1, 1), 99);

    messages.then_no_acknowledgement_went_to(ip(70, 1, 1, 1));
}

#[test]
fn should_acknowledge_the_answer_to_its_check_when_the_contact_asks() {
    let mut messages = Messages::new();
    messages.given_a_known_contact_at(ip(70, 1, 1, 1));
    messages.given_its_hello_answer_came_unasked(ip(70, 1, 1, 1), 99);
    messages.given_the_contact_was_asked_whether_it_is_still_there(ip(70, 1, 1, 1));

    messages.when_the_contact_sends_its_hello_answer(ip(70, 1, 1, 1), 99);

    messages.then_our_acknowledgement_went_to(ip(70, 1, 1, 1), 99);
}

#[test]
fn should_keep_a_contact_that_answers_its_check() {
    let mut messages = Messages::new();
    messages.given_a_known_contact_at(ip(70, 1, 1, 1));
    messages.given_its_hello_answer_came_unasked(ip(70, 1, 1, 1), 99);
    messages.given_the_contact_was_asked_whether_it_is_still_there(ip(70, 1, 1, 1));
    messages.given_the_contact_answered_its_check(ip(70, 1, 1, 1), 99);

    messages.when_minutes_pass_one_by_one(3);

    messages.then_the_contacts_are(1, "it answered: no longer checked to death");
}

#[test]
fn should_answer_a_lookup_meant_for_us() {
    let mut messages = Messages::new();
    messages.given_contacts_at(&[ip(70, 1, 1, 1)]);

    messages.when_a_lookup_meant_for_us_comes();

    messages.then_the_lookup_is_answered();
}

#[test]
fn should_not_answer_a_lookup_meant_for_another_node() {
    let mut messages = Messages::new();
    messages.given_contacts_at(&[ip(70, 1, 1, 1)]);

    messages.when_a_lookup_meant_for_another_node_comes();

    messages.then_the_lookup_is_not_answered();
}

#[test]
fn should_store_a_keyword_published_to_us() {
    let mut messages = Messages::new();

    messages.when_a_file_is_published_to_us_under_our_id("ubuntu desktop.iso", 4000);

    messages.then_the_publish_is_answered_for_our_id(PEER);
    messages.then_the_keywords_indexed_are(1);
}

#[test]
fn should_find_a_keyword_published_to_us_without_an_expression() {
    let mut messages = Messages::new();
    messages.given_a_file_published_to_us_under_our_id("ubuntu desktop.iso", 4000);

    messages.when_a_node_searches_our_id(ip(23, 1, 1, 1));

    messages.then_the_search_answer_lists_the_published_file(ip(23, 1, 1, 1));
}

#[test]
fn should_find_a_keyword_published_to_us_with_an_expression_that_matches() {
    let mut messages = Messages::new();
    messages.given_a_file_published_to_us_under_our_id("ubuntu desktop.iso", 4000);

    messages.when_a_node_searches_our_id_for(ip(24, 1, 1, 1), "ubuntu");

    messages.then_a_search_answer_went_to(ip(24, 1, 1, 1), "the expression matches");
}

#[test]
fn should_send_nothing_when_an_expression_finds_nothing() {
    let mut messages = Messages::new();
    messages.given_a_file_published_to_us_under_our_id("ubuntu desktop.iso", 4000);

    messages.when_a_node_searches_our_id_for(ip(26, 1, 1, 1), "windows");

    messages.then_no_search_answer_went_to(ip(26, 1, 1, 1), "nothing found, nothing sent");
}

#[test]
fn should_not_store_a_keyword_published_far_from_us() {
    let mut messages = Messages::new();

    messages.when_a_keyword_far_from_us_is_published_by(PEER);

    messages.then_no_publish_answer_went_to(PEER, "far from us");
    messages.then_nothing_is_indexed();
}

#[test]
fn should_not_store_a_source_published_far_from_us() {
    let mut messages = Messages::new();

    messages.when_a_source_far_from_us_is_published_by(ip(27, 1, 1, 1));

    messages.then_no_publish_answer_went_to(ip(27, 1, 1, 1), "far from us");
    messages.then_nothing_is_indexed();
}

#[test]
fn should_store_a_source_published_to_us() {
    let mut messages = Messages::new();

    messages.when_a_source_is_published_to_us_by(PEER);

    messages.then_the_publish_is_answered_for_our_id(PEER);
}

#[test]
fn should_not_store_a_source_of_no_type() {
    let mut messages = Messages::new();

    messages.when_a_source_of_no_type_is_published_to_us_by(ip(28, 1, 1, 1));

    messages.then_no_publish_answer_went_to(ip(28, 1, 1, 1), "a source of no type");
}

#[test]
fn should_find_a_source_published_to_us_at_the_address_it_came_from() {
    let mut messages = Messages::new();
    messages.given_a_source_published_to_us_by(PEER);
    messages.given_a_source_of_no_type_published_to_us_by(ip(28, 1, 1, 1));

    messages.when_a_node_searches_sources_of_our_id(ip(23, 1, 1, 1));

    messages.then_the_search_answer_lists_the_published_source_at(ip(23, 1, 1, 1), PEER);
}

#[test]
fn should_answer_a_firewall_check_and_connect_to_the_asker() {
    let mut messages = Messages::new();

    messages.when_a_firewall_check_comes_from(PEER, 5000);

    messages.then_our_firewall_answer_to_says_its_address(PEER);
    messages.then_the_host_is_asked_to_connect_to(PEER, 5000);
}

#[test]
fn should_not_answer_a_firewall_check_from_a_lan_address() {
    let mut messages = Messages::new();
    messages.given_a_firewall_check_came_from(PEER, 5000);

    messages.when_a_firewall_check_comes_from(ip(192, 168, 1, 9), 5000);

    messages.then_no_firewall_answer_went_to(ip(192, 168, 1, 9));
    messages.then_the_host_is_asked_for_checks(1);
}

#[test]
fn should_not_answer_a_firewall_check_without_a_port() {
    let mut messages = Messages::new();
    messages.given_a_firewall_check_came_from(PEER, 5000);

    messages.when_a_firewall_check_comes_from(ip(29, 1, 1, 1), 0);

    messages.then_no_firewall_answer_went_to(ip(29, 1, 1, 1));
    messages.then_the_host_is_asked_for_checks(1);
}

#[test]
fn should_ignore_our_address_from_a_node_never_asked() {
    let mut messages = Messages::new();
    messages.given_the_helpers_said_hello();

    messages.when_a_node_reports_our_address(ip(25, 1, 1, 1), ip(80, 1, 1, 1));

    messages.then_our_address_is(0, "never asked");
}

#[test]
fn should_take_the_first_address_reported() {
    let mut messages = Messages::new();
    messages.given_the_helpers_said_hello();

    messages.when_a_node_reports_our_address(ip(21, 1, 1, 1), ip(80, 1, 1, 1));

    messages.then_our_address_is(ip(80, 1, 1, 1), "the first report");
}

#[test]
fn should_keep_our_address_when_only_one_node_reports_another() {
    let mut messages = Messages::new();
    messages.given_the_helpers_said_hello();
    messages.given_a_node_reported_our_address(ip(21, 1, 1, 1), ip(80, 1, 1, 1));

    messages.when_a_node_reports_our_address(ip(22, 1, 1, 1), ip(81, 1, 1, 1));

    messages.then_our_address_is(ip(80, 1, 1, 1), "one report is not enough");
}

#[test]
fn should_change_our_address_when_two_nodes_agree_on_the_new_one() {
    let mut messages = Messages::new();
    messages.given_the_helpers_said_hello();
    messages.given_a_node_reported_our_address(ip(21, 1, 1, 1), ip(80, 1, 1, 1));
    messages.given_a_node_reported_our_address(ip(22, 1, 1, 1), ip(81, 1, 1, 1));

    messages.when_a_node_reports_our_address(ip(23, 1, 1, 1), ip(81, 1, 1, 1));

    messages.then_our_address_is(ip(81, 1, 1, 1), "two agree");
}

#[test]
fn should_keep_the_new_address_when_another_node_agrees() {
    let mut messages = Messages::new();
    messages.given_the_helpers_said_hello();
    messages.given_a_node_reported_our_address(ip(21, 1, 1, 1), ip(80, 1, 1, 1));
    messages.given_a_node_reported_our_address(ip(22, 1, 1, 1), ip(81, 1, 1, 1));
    messages.given_a_node_reported_our_address(ip(23, 1, 1, 1), ip(81, 1, 1, 1));

    messages.when_a_node_reports_our_address(ip(24, 1, 1, 1), ip(81, 1, 1, 1));

    messages.then_our_address_is(ip(81, 1, 1, 1), "another agrees");
}

// Four reports: no further node is asked until the next check.
#[test]
fn should_ask_no_further_node_to_check_our_port_after_four_reports() {
    let mut messages = Messages::new();
    messages.given_the_helpers_said_hello();
    messages.given_a_node_reported_our_address(ip(21, 1, 1, 1), ip(80, 1, 1, 1));
    messages.given_a_node_reported_our_address(ip(22, 1, 1, 1), ip(81, 1, 1, 1));
    messages.given_a_node_reported_our_address(ip(23, 1, 1, 1), ip(81, 1, 1, 1));
    messages.given_a_node_reported_our_address(ip(24, 1, 1, 1), ip(81, 1, 1, 1));

    messages.when_a_node_says_hello_with_version(ip(26, 1, 1, 1), 8);

    messages.then_the_node_is_not_asked_to_check_our_port(ip(26, 1, 1, 1), "four reports");
    messages.then_we_are_firewalled("no node connected to us");
}

#[test]
fn should_stay_firewalled_when_a_check_ends_without_two_connections() {
    let mut messages = Messages::new();
    messages.given_the_helpers_reported_our_address(ip(80, 1, 1, 1));
    messages.given_connections_confirmed_from(&[ip(21, 1, 1, 1), ip(99, 1, 1, 1)]);

    messages.when_the_firewall_is_rechecked();

    messages.then_we_are_firewalled("one connection");
    messages.then_a_connection_is_awaited_from(ip(22, 1, 1, 1), "still awaited");
}

#[test]
fn should_forget_a_node_awaited_too_long() {
    let mut messages = Messages::new();
    messages.given_the_helpers_reported_our_address(ip(80, 1, 1, 1));
    messages.given_connections_confirmed_from(&[ip(21, 1, 1, 1), ip(99, 1, 1, 1)]);
    messages.given_the_firewall_was_rechecked_and_a_connection_is_awaited_from(ip(22, 1, 1, 1));

    messages.when_minutes_pass(13);

    messages.then_no_connection_is_awaited_from(ip(22, 1, 1, 1), "forgotten after three minutes");
}

#[test]
fn should_answer_a_ping_with_the_port_it_came_from() {
    let mut messages = Messages::new();

    messages.when_a_node_pings_from_port(PEER, 5555);

    messages.then_our_pong_to_says_the_port(PEER, 5555);
}

#[test]
fn should_answer_only_a_few_pings_of_a_flood() {
    let mut messages = Messages::new();
    messages.given_a_node_pinged_from_port(PEER, 5555);
    messages.given_a_pong_nobody_waits_for(PEER);

    messages.when_a_node_pings(PEER, 20);

    messages.then_fewer_pongs_went_to(PEER, 5);
}

#[test]
fn should_ban_a_flooding_node_for_everything() {
    let mut messages = Messages::new();
    messages.given_a_node_flooded_us_with_pings(PEER);

    messages.when_a_node_asks_for_a_bootstrap(PEER);

    messages.then_no_bootstrap_answer_went_to(PEER, "banned for everything");
}

#[test]
fn should_end_the_ban_of_a_flooding_node_after_two_hours() {
    let mut messages = Messages::new();
    messages.given_a_node_flooded_us_with_pings(PEER);
    messages.given_the_banned_node_got_no_bootstrap_answer(PEER);

    messages.when_the_node_pings_again_after_minutes(PEER, 121);

    messages.then_one_more_pong_went_to(PEER, "the ban ends");
}

#[test]
fn should_drop_unknown_buddy_and_malformed_packets() {
    for (kind, packet) in dropped_packets() {
        let mut messages = Messages::new();

        messages.when_a_node_sends(PEER, &packet);

        messages.then_nothing_is_sent(kind);
        messages.then_the_contacts_are(0, kind);
    }
}

#[test]
fn should_not_start_a_source_search_already_running() {
    let mut messages = Messages::new();
    messages.given_a_file();
    messages.given_a_contact_at_the_file();
    messages.given_a_source_search_started(1000);

    messages.when_the_source_search_starts(1000);

    messages.then_it_did_not_start("already running");
}

#[test]
fn should_ask_the_contact_for_sources_of_a_file_of_that_size() {
    let mut messages = Messages::new();
    messages.given_a_file();
    messages.given_a_contact_at_the_file();
    messages.given_a_source_search_started(1000);

    messages.when_the_lookup_reaches_the_contact();

    messages.then_the_source_search_asks_for_the_size(1000);
}

#[test]
fn should_keep_only_sources_that_can_be_reached_directly() {
    let mut messages = Messages::new();
    messages.given_a_file();
    messages.given_a_contact_at_the_file();
    messages.given_a_source_search_started(1000);
    messages.given_the_lookup_reached_the_contact();

    messages.when_the_contact_answers_with_sources_of_every_kind();

    messages.then_only_the_sources_that_can_be_reached_directly_are_found();
}

#[test]
fn should_send_the_expression_along_a_keyword_search() {
    let mut messages = Messages::new();
    messages.given_a_contact_at_the_keyword("ubuntu");
    messages.given_a_keyword_search_with_an_expression_started("ubuntu desktop");

    messages.when_the_lookup_reaches_the_contact();

    messages.then_the_expression_goes_along();
}

#[test]
fn should_keep_only_files_that_match_all_the_words_of_a_keyword_search() {
    let mut messages = Messages::new();
    messages.given_a_contact_at_the_keyword("ubuntu");
    messages.given_a_keyword_search_with_an_expression_started("ubuntu desktop");
    messages.given_the_lookup_reached_the_contact();

    messages.when_the_contact_answers_with_files_of_every_kind();

    messages.then_only_the_file_that_matches_all_the_words_is_found();
}

#[test]
fn should_drop_results_for_a_search_nobody_runs() {
    let mut messages = Messages::new();
    messages.given_a_contact_at_the_keyword("ubuntu");
    messages.given_a_keyword_search_with_an_expression_started("ubuntu desktop");
    messages.given_the_lookup_reached_the_contact();
    messages.given_the_contact_answered_with_files_of_every_kind();

    messages.when_the_same_files_come_for_the_keyword("debian");

    messages.then_the_files_found_are(1);
}

#[test]
fn should_not_search_without_a_word_long_enough() {
    let mut messages = Messages::new();

    messages.when_a_keyword_search_starts("a b");

    messages.then_the_search_did_not_start("no word long enough");
    messages.then_no_lookup_runs();
}

#[test]
fn should_not_search_a_keyword_with_nobody_to_ask() {
    let mut messages = Messages::new();

    messages.when_a_keyword_search_starts("ubuntu");

    messages.then_the_search_did_not_start("nobody to ask");
    messages.then_no_lookup_runs();
}

#[test]
fn should_not_search_sources_with_nobody_to_ask() {
    let mut messages = Messages::new();
    messages.given_a_file();

    messages.when_the_source_search_starts(10);

    messages.then_it_did_not_start("nobody to ask");
    messages.then_no_lookup_runs();
}

#[test]
fn should_not_publish_a_source_with_nobody_to_ask() {
    let mut messages = Messages::new();
    messages.given_a_file();

    messages.when_we_are_published_as_a_source(10);

    messages.then_it_did_not_start("nobody to ask");
    messages.then_no_lookup_runs();
}

#[test]
fn should_not_publish_a_keyword_with_nobody_to_ask() {
    let mut messages = Messages::new();

    messages.when_the_keyword_is_published_with_a_file("ubuntu", "ubuntu.iso", 1);

    messages.then_it_did_not_start("nobody to ask");
    messages.then_no_lookup_runs();
}

#[test]
fn should_publish_no_keyword_without_files() {
    let mut messages = Messages::new();
    messages.given_a_contact_at_the_keyword("ubuntu");

    messages.when_the_keyword_is_published_without_files("ubuntu");

    messages.then_it_did_not_start("nothing to publish");
}

#[test]
fn should_start_one_keyword_publish() {
    let mut messages = Messages::new();
    messages.given_a_contact_at_the_keyword("ubuntu");
    messages.given_files_to_publish(kad_messages::MAX_PUBLISHED_FILES + 10);

    messages.when_the_keyword_is_published_with_the_files("ubuntu");

    messages.then_it_started();
    messages.then_the_keyword_publishes_running_are(1);
    messages.then_the_source_publishes_running_are(0);
}

#[test]
fn should_not_start_a_keyword_publish_already_running() {
    let mut messages = Messages::new();
    messages.given_a_contact_at_the_keyword("ubuntu");
    messages.given_files_to_publish(kad_messages::MAX_PUBLISHED_FILES + 10);
    messages.given_the_keyword_publish_started("ubuntu");

    messages.when_the_keyword_is_published_with_the_files("ubuntu");

    messages.then_it_did_not_start("already running");
    messages.then_the_keyword_publishes_running_are(1);
    messages.then_the_source_publishes_running_are(0);
}

#[test]
fn should_publish_the_files_of_a_keyword_in_batches() {
    let mut messages = Messages::new();
    messages.given_a_contact_at_the_keyword("ubuntu");
    messages.given_files_to_publish(kad_messages::MAX_PUBLISHED_FILES + 10);
    messages.given_the_keyword_publish_started("ubuntu");

    messages.when_the_lookup_reaches_the_contact();

    messages.then_keyword_publishes_went_to_the_contact(2);
    messages.then_the_last_keyword_publish_carries_files(10);
}

#[test]
fn should_count_the_answers_to_a_keyword_publish() {
    let mut messages = Messages::new();
    messages.given_a_contact_at_the_keyword("ubuntu");
    messages.given_files_to_publish(kad_messages::MAX_PUBLISHED_FILES + 10);
    messages.given_the_keyword_publish_started("ubuntu");
    messages.given_the_lookup_reached_the_contact();

    messages.when_a_node_answers_the_publish(PEER);

    messages.then_the_only_lookup_is(Kind::StoreKeyword, Some(1));
}

#[test]
fn should_not_count_an_answer_to_no_request() {
    let mut messages = Messages::new();
    messages.given_a_contact_at_the_keyword("ubuntu");
    messages.given_files_to_publish(kad_messages::MAX_PUBLISHED_FILES + 10);
    messages.given_the_keyword_publish_started("ubuntu");
    messages.given_the_lookup_reached_the_contact();
    messages.given_a_node_answered_the_publish(PEER);

    messages.when_a_node_answers_the_publish(ip(70, 1, 1, 1));

    messages.then_the_first_lookup_has_answers(1);
}

#[test]
fn should_start_one_source_publish() {
    let mut messages = Messages::new();
    messages.given_a_file();
    messages.given_a_contact_at_the_file();
    messages.given_the_node_is_reachable_through_tcp();

    messages.when_we_are_published_as_a_source(5_000_000_000);

    messages.then_it_started();
    messages.then_the_source_publishes_running_are(1);
    messages.then_the_only_lookup_is(Kind::StoreSource, None);
}

#[test]
fn should_not_start_a_source_publish_already_running() {
    let mut messages = Messages::new();
    messages.given_a_file();
    messages.given_a_contact_at_the_file();
    messages.given_the_node_is_reachable_through_tcp();
    messages.given_a_source_publish_started(5_000_000_000);

    messages.when_we_are_published_as_a_source(5_000_000_000);

    messages.then_it_did_not_start("already running");
    messages.then_the_source_publishes_running_are(1);
    messages.then_the_only_lookup_is(Kind::StoreSource, None);
}

#[test]
fn should_publish_us_as_a_source_with_our_port_and_the_kind_of_size() {
    let mut messages = Messages::new();
    messages.given_a_file();
    messages.given_a_contact_at_the_file();
    messages.given_the_node_is_reachable_through_tcp();
    messages.given_a_source_publish_started(5_000_000_000);

    messages.when_the_lookup_reaches_the_contact();

    messages.then_our_source_publish_names_the_file_and_us();
    messages.then_our_source_publish_carries(4, 4662, 5_000_000_000, 3);
}

#[test]
fn should_show_what_the_lookups_look_for() {
    let mut messages = Messages::new();
    messages.given_a_file();
    messages.given_an_alive_contact_at(PEER);
    messages.given_a_source_search_started(10);
    messages.given_a_keyword_search_started("ubuntu");

    messages.when_the_node_looks_for_contacts();

    messages.then_the_lookups_include(&[Kind::Sources, Kind::Keyword, Kind::Node]);
    messages.then_every_running_lookup_is_shown();
}

#[test]
fn should_look_up_our_own_id_before_being_ready() {
    let mut messages = Messages::new();
    messages.given_an_alive_contact_at(PEER);
    messages.given_the_node_is_not_ready();

    messages.when_minutes_pass(3);

    messages.then_our_own_id_is_looked_up();
    messages.then_the_node_is_not_ready();
}

#[test]
fn should_be_ready_once_the_first_lookup_of_our_own_id_is_done() {
    let mut messages = Messages::new();
    messages.given_an_alive_contact_at(PEER);
    messages.given_the_node_is_not_ready();
    messages.given_our_own_id_is_looked_up_after_minutes(3);

    messages.when_minutes_pass(1);

    messages.then_the_node_is_ready();
}

#[test]
fn should_look_for_contacts_again_when_quiet() {
    let mut messages = Messages::new();
    messages.given_an_alive_contact_at(PEER);
    messages.given_the_first_lookups_were_sent();

    messages.when_minutes_pass(16);

    messages.then_a_zone_is_looked_up_again();
    messages.then_the_node_is_connected("heard from within twenty minutes");
}

#[test]
fn should_no_longer_be_connected_without_news_for_twenty_minutes() {
    let mut messages = Messages::new();
    messages.given_an_alive_contact_at(PEER);
    messages.given_the_first_lookups_were_sent();
    messages.given_a_quiet_node_looked_for_contacts_again_after_minutes(16);

    messages.when_minutes_pass_without_a_tick(5);

    messages.then_the_node_is_not_connected();
}

#[test]
fn should_load_no_contacts_from_an_unreadable_nodes_dat() {
    let mut messages = Messages::new();
    messages.given_an_unreadable_nodes_dat();

    messages.when_the_contacts_are_loaded();

    messages.then_the_contacts_are(0, "unreadable");
}

#[test]
fn should_save_no_nodes_dat_without_contacts() {
    let mut messages = Messages::new();
    messages.given_an_unreadable_nodes_dat();
    messages.given_the_contacts_were_loaded();
    messages.given_the_nodes_dat_is_deleted();

    messages.when_the_node_closes();

    messages.then_there_is_no_nodes_dat("no contacts, no file");
}

#[test]
fn should_keep_contacts_that_cannot_be_saved_in_the_table() {
    let mut messages = Messages::new();
    messages.given_contacts_at(&[PEER]);
    messages.given_the_temporary_nodes_file_cannot_be_written();

    messages.when_the_contacts_are_saved();

    messages.then_there_is_no_nodes_dat("cannot be written");
    messages.then_the_contacts_are(1, "kept");
}

#[test]
fn should_drop_a_lookup_answer_nobody_asked_for() {
    let mut messages = Messages::new();

    messages.when_a_stranger_answers_a_lookup(ip(40, 1, 1, 1));

    messages.then_the_contacts_are(0, "nobody asked");
    messages.then_nothing_is_sent("nobody asked");
}

#[test]
fn should_drop_a_search_answer_nobody_asked_for() {
    let mut messages = Messages::new();

    messages.when_a_stranger_answers_a_search(ip(40, 1, 1, 1));

    messages.then_the_contacts_are(0, "nobody asked");
    messages.then_nothing_is_sent("nobody asked");
}

#[test]
fn should_leave_out_contacts_an_answer_cannot_vouch_for() {
    let mut messages = Messages::new();
    messages.given_a_contact_at_the_keyword("ubuntu");
    messages.given_the_keyword_publish_with_a_file_started("ubuntu", "ubuntu.iso", 10);

    messages.when_the_contact_answers_with_contacts_of_every_kind();

    messages
        .then_the_contact_addresses_are(&[&format!("{}:{PORT}", Ipv4Addr::from(PEER)), &format!("43.1.1.1:{PORT}")]);
}

#[test]
fn should_answer_a_node_on_this_machine_but_not_take_it() {
    let mut messages = Messages::new();

    messages.when_a_node_says_hello_with_version(ip(127, 0, 0, 1), 8);

    messages.then_our_hello_answer_went_to(ip(127, 0, 0, 1));
    messages.then_the_contacts_are(0, "this machine");
    messages.then_the_node_is_not_asked_to_check_our_port(ip(127, 0, 0, 1), "it could not check our port");
}

#[test]
fn should_not_look_up_our_own_id_twice_at_once() {
    let mut messages = Messages::new();
    messages.given_an_alive_contact_at(PEER);
    messages.given_a_source_search_for_our_own_id_started();

    messages.when_the_node_looks_for_contacts();

    messages.then_our_own_id_is_looked_up_once();
    messages.then_the_node_is_not_ready();
}
