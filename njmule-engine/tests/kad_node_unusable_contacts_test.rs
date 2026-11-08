// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodeUnusableContactsTest, under its names: what the node leaves alone, from a plain packet of
//! the DNS port to contacts it cannot ask, and an availability no file has.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use crate::kad_lab;

use std::time::Duration;

use kad_lab::{KadLab, PORT, ip};
use njmule_engine::kad::kad_node::{KadEvent, KadNode};
use njmule_engine::kad::keywords;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::kad::kad_messages::{Answer, Contact, Entry, Request, SearchAnswer};
use njmule_engine::protocol::packet::opcodes::kad;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};
use rand::RngExt;

const PEER: u32 = ip(20, 1, 1, 1);
const OTHER: u32 = ip(21, 1, 1, 1);
const FILE: [u8; 16] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef];

struct Unusable {
    lab: KadLab,
    node: KadNode,
    target: UInt128,
}

impl Unusable {
    fn new() -> Self {
        let mut lab = KadLab::new();
        let mut settings = kad_lab::settings();
        settings.udp_port = PORT;
        let node = lab.node(settings);
        Self { lab, node, target: UInt128::from_big_endian(FILE) }
    }

    fn given_a_contact_at(&mut self, ip: u32, id: UInt128) {
        self.node.add_contacts(&kad_lab::one_contact(id, ip));
    }

    fn given_a_contact_far_from_the_target_at(&mut self, ip: u32) {
        let far = self.far_from_target();
        self.given_a_contact_at(ip, far);
    }

    fn given_a_source_search(&mut self) {
        assert!(self.node.find_sources(FILE, 1_000));
    }

    fn given_a_keyword_search_that_asked_the_contact(&mut self, word: &str) {
        assert!(self.node.search_keyword(word, None).is_some());
        let answer = Answer { target: keywords::id(word), contacts: Vec::new() };
        self.node.received(PEER, PORT, &answer.encode().unwrap(), 0, 0);
        self.lab.advance(Duration::from_secs(4));
        self.node.tick();
    }

    fn when_a_ping_arrives_from(&mut self, from: u32, port: u16, sender_key: u32) {
        self.node.received(from, port, &Packet::of(protocol::KAD, kad::PING, Vec::new()), 0, sender_key);
    }

    fn when_the_contact_answers_with(&mut self, contacts: &[Contact]) {
        let answer = Answer { target: self.target, contacts: contacts.to_vec() };
        self.node.received(PEER, PORT, &answer.encode().unwrap(), 0, 0);
    }

    fn when_the_contact_finds_files_with_sources(&mut self, sources: &[u64]) {
        let files: Vec<Entry> = sources
            .iter()
            .enumerate()
            .map(|(at, count)| {
                let n = u128::try_from(at).unwrap() + 1;
                Entry {
                    id: UInt128(n << 64 | n),
                    tags: vec![
                        Tag::String { name: TagName::id(file_tag::FILENAME), value: format!("ubuntu {at}.iso") },
                        Tag::Int { name: TagName::id(file_tag::FILESIZE), value: 1_000 },
                        Tag::Int { name: TagName::id(file_tag::SOURCES), value: *count },
                    ],
                }
            })
            .collect();
        let word = keywords::id("ubuntu");
        let answer = SearchAnswer { sender: word, target: word, results: files };
        self.node.received(PEER, PORT, &answer.encode().unwrap(), 0, 0);
    }

    fn then_nothing_went_to(&self, to: u32) {
        assert!(!self.lab.sent().iter().any(|sent| sent.ip == to));
    }

    fn then_a_pong_went_to(&self, to: u32, port: u16) {
        assert!(
            self.lab.sent().iter().any(|sent| sent.ip == to && sent.port == port && sent.packet.opcode() == kad::PONG)
        );
    }

    fn then_a_lookup_request_went_to(&self, to: u32) {
        assert!(self.lab.sent().iter().any(|sent| sent.ip == to && sent.packet.opcode() == kad::REQ));
    }

    fn then_no_lookup_request_went_to_the_id(&self, to: u32, id: UInt128) {
        for sent in self.lab.sent().iter().filter(|sent| sent.ip == to && sent.packet.opcode() == kad::REQ) {
            assert_ne!(Request::decode(&sent.packet).unwrap().receiver, id);
        }
    }

    fn then_the_sources_found_are(&mut self, sources: &[u32]) {
        let found: Vec<u32> = self
            .node
            .take_events()
            .into_iter()
            .flat_map(|event| match event {
                KadEvent::Files { results, .. } => results.into_iter().map(|result| result.sources).collect(),
                _ => Vec::new(),
            })
            .collect();
        assert_eq!(found, sources);
    }

    // Ids the search target shares its top bits with, closer the smaller n.
    fn near_target(&self, n: u128) -> UInt128 {
        self.target ^ UInt128(n)
    }

    fn far_from_target(&mut self) -> UInt128 {
        let high = u64::MAX - self.lab.random.random_range(0..1_000u64);
        self.target ^ UInt128(u128::from(high) << 64)
    }
}

#[test]
fn should_drop_a_plain_packet_from_the_dns_port() {
    let mut unusable = Unusable::new();

    unusable.when_a_ping_arrives_from(PEER, 53, 0);

    unusable.then_nothing_went_to(PEER);
}

#[test]
fn should_answer_an_obfuscated_packet_from_the_dns_port() {
    let mut unusable = Unusable::new();

    unusable.when_a_ping_arrives_from(PEER, 53, 77);

    unusable.then_a_pong_went_to(PEER, 53);
}

#[test]
fn should_not_ask_a_contact_without_a_udp_port() {
    let mut unusable = Unusable::new();
    unusable.given_a_contact_far_from_the_target_at(PEER);
    unusable.given_a_source_search();

    unusable.when_the_contact_answers_with(&[
        Contact { id: unusable.near_target(1), ip: ip(30, 1, 1, 1), udp_port: 0, tcp_port: 4662, version: 8 },
        Contact { id: unusable.near_target(2), ip: ip(31, 1, 1, 1), udp_port: PORT, tcp_port: 4662, version: 8 },
    ]);

    unusable.then_nothing_went_to(ip(30, 1, 1, 1));
    unusable.then_a_lookup_request_went_to(ip(31, 1, 1, 1));
}

#[test]
fn should_not_ask_a_contact_at_the_address_of_another_of_our_contacts() {
    let mut unusable = Unusable::new();
    unusable.given_a_contact_far_from_the_target_at(PEER);
    unusable.given_a_contact_far_from_the_target_at(OTHER);
    unusable.given_a_source_search();

    unusable.when_the_contact_answers_with(&[Contact {
        id: unusable.near_target(1),
        ip: OTHER,
        udp_port: PORT,
        tcp_port: 4662,
        version: 8,
    }]);

    unusable.then_no_lookup_request_went_to_the_id(OTHER, unusable.near_target(1));
}

#[test]
fn should_take_an_availability_beyond_any_real_one_as_unknown() {
    let mut unusable = Unusable::new();
    unusable.given_a_contact_at(PEER, keywords::id("ubuntu"));
    unusable.given_a_keyword_search_that_asked_the_contact("ubuntu");

    unusable.when_the_contact_finds_files_with_sources(&[70_000, 65_500]);

    unusable.then_the_sources_found_are(&[0, 65_500]);
}
