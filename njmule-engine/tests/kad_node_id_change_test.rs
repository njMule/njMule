// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodeIdChangeTest, under its names: a node that proves another id too soon is banned, and an
//! answer under another id than the one proven at its address is ignored.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod kad_lab;

use std::time::Duration;

use kad_lab::{KadLab, PORT, ip};
use njmule_engine::kad::kad_identity::KadIdentity;
use njmule_engine::kad::kad_node::{KadEvent, KadNode};
use njmule_engine::kad::keywords;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::kad::kad_messages::{Answer, Entry, Hello, SearchAnswer, Sender};
use njmule_engine::protocol::packet::opcodes::kad;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};

const PEER: u32 = ip(20, 1, 1, 1);
const WORD: &str = "ubuntu";

struct IdChange {
    lab: KadLab,
    identity: KadIdentity,
    node: KadNode,
    found: Vec<String>,
}

impl IdChange {
    fn new() -> Self {
        let mut lab = KadLab::new();
        let identity = KadIdentity::load_or_create(lab.dir.path(), &mut lab.random).unwrap();
        let mut settings = kad_lab::settings();
        settings.udp_port = PORT;
        let node = lab.node_as(identity, settings);
        Self { lab, identity, node, found: Vec::new() }
    }

    fn given_the_peer_proves_the_id(&mut self, id: UInt128) {
        self.when_the_peer_proves_the_id(id);
    }

    fn given_a_keyword_search_that_asked_the_peer(&mut self) {
        assert!(self.node.search_keyword(WORD, None).is_some());
        let answer = Answer { target: keywords::id(WORD), contacts: Vec::new() };
        self.node.received(PEER, PORT, &answer.encode().unwrap(), self.identity.verify_key(PEER), 55);
        self.lab.advance(Duration::from_secs(4));
        self.node.tick();
    }

    fn given_the_peer_answers_under_the_id(&mut self, sender: UInt128, name: &str) {
        self.when_the_peer_answers_under_the_id(sender, name);
    }

    fn given_time_passes(&mut self, duration: Duration) {
        self.lab.advance(duration);
    }

    // A hello carrying the key we give that address: the node there knows it from us.
    fn when_the_peer_proves_the_id(&mut self, id: UInt128) {
        let hello = Hello { sender: Sender { id, tcp_port: 4662, version: 8 }, tags: Vec::new() };
        self.node.received(PEER, PORT, &hello.encode(false).unwrap(), self.identity.verify_key(PEER), 55);
    }

    fn when_the_peer_answers_under_the_id(&mut self, sender: UInt128, name: &str) {
        let file = Entry {
            id: UInt128::random(&mut self.lab.random),
            tags: vec![
                Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() },
                Tag::Int { name: TagName::id(file_tag::FILESIZE), value: 1_000 },
            ],
        };
        let answer = SearchAnswer { sender, target: keywords::id(WORD), results: vec![file] };
        self.node.received(PEER, PORT, &answer.encode().unwrap(), self.identity.verify_key(PEER), 55);
    }

    fn then_the_peer_is_ignored(&mut self, ignored: bool) {
        self.lab.take_sent();
        self.node.received(PEER, PORT, &Packet::of(protocol::KAD, kad::PING, Vec::new()), 0, 55);
        let answers = self.lab.sent().iter().filter(|sent| sent.ip == PEER).count();
        assert_eq!(answers, usize::from(!ignored), "the answer to a ping");
    }

    fn then_the_node_has_contacts(&self, count: usize) {
        assert_eq!(self.node.contacts(), count);
    }

    fn then_the_files_found_are(&mut self, names: &[&str]) {
        for event in self.node.take_events() {
            if let KadEvent::Files { results, .. } = event {
                self.found.extend(results.into_iter().map(|result| result.name));
            }
        }
        assert_eq!(self.found, names);
    }
}

fn id(high: u64, low: u64) -> UInt128 {
    UInt128(u128::from(high) << 64 | u128::from(low))
}

#[test]
fn should_ban_a_node_that_proves_another_id_within_an_hour() {
    let mut change = IdChange::new();
    change.given_the_peer_proves_the_id(id(1, 1));
    change.given_time_passes(Duration::from_secs(30 * 60));

    change.when_the_peer_proves_the_id(id(2, 2));

    change.then_the_peer_is_ignored(true);
    change.then_the_node_has_contacts(0);
}

#[test]
fn should_let_a_node_change_its_id_after_an_hour() {
    let mut change = IdChange::new();
    change.given_the_peer_proves_the_id(id(1, 1));
    change.given_time_passes(Duration::from_secs(61 * 60));

    change.when_the_peer_proves_the_id(id(2, 2));

    change.then_the_peer_is_ignored(false);
}

#[test]
fn should_ignore_a_search_answer_under_another_id_than_the_one_proven_there() {
    let mut change = IdChange::new();
    change.given_the_peer_proves_the_id(keywords::id(WORD));
    change.given_a_keyword_search_that_asked_the_peer();
    change.given_the_peer_answers_under_the_id(id(3, 3), "ubuntu forged.iso");

    change.when_the_peer_answers_under_the_id(keywords::id(WORD), "ubuntu real.iso");

    change.then_the_files_found_are(&["ubuntu real.iso"]);
}
