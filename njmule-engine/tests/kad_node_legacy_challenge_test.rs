// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodeLegacyChallengeTest, under its names: nodes older than the hello acknowledgement prove
//! their address by answering a request only they were sent, a ping or a plain lookup for a random target.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod kad_lab;

use std::net::Ipv4Addr;
use std::time::Duration;

use kad_lab::{KadLab, PORT, Sent, ip};
use njmule_engine::kad::kad_node::KadNode;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::{self, UdpKey};
use njmule_engine::protocol::kad::kad_messages::{self, Answer, Hello, Request, Sender};
use njmule_engine::protocol::packet::opcodes::kad;
use njmule_engine::protocol::packet::protocol;

const PEER: u32 = ip(20, 1, 1, 1);
const PEER_ID: UInt128 = UInt128(0x1234u128 << 64 | 0x5678);

struct Challenge {
    lab: KadLab,
    node: KadNode,
}

impl Challenge {
    fn new() -> Self {
        let mut lab = KadLab::new();
        let mut settings = kad_lab::settings();
        settings.udp_port = PORT;
        let node = lab.node(settings);
        Self { lab, node }
    }

    fn given_a_hello_from(&mut self, version: u8) {
        self.when_a_hello_arrives_from(version);
    }

    fn given_a_contact_we_say_hello_to(&mut self, version: u8) {
        let contact = |id, ip, version, verified| nodes_dat::Contact {
            id,
            ip,
            udp_port: PORT,
            tcp_port: 4662,
            version,
            contact_type: 0,
            udp_key: UdpKey::default(),
            verified,
        };
        let other = contact(UInt128::random(&mut self.lab.random), ip(30, 1, 1, 1), 8, true);
        let contents = nodes_dat::Contents {
            file_version: 2,
            bootstrap: false,
            contacts: vec![contact(PEER_ID, PEER, version, false), other],
        };
        self.node.add_contacts(&contents);
        self.lab.advance(Duration::from_secs(61));
        self.node.tick();
        assert!(self.lab.sent().iter().any(|sent| sent.packet.opcode() == kad::HELLO_REQ), "our hello");
    }

    fn given_the_peer_is_not_verified_yet(&self) {
        self.assert_the_peer_is_verified(false);
    }

    fn given_the_peer_answers_our_hello(&mut self, version: u8) {
        let hello = Hello { sender: Sender { id: PEER_ID, tcp_port: 4662, version }, tags: Vec::new() };
        self.node.received(PEER, PORT, &hello.encode(true).unwrap(), 0, 0);
    }

    fn given_the_challenge_is_a_plain_lookup_addressed_to_the_peer(&self) {
        let challenge = self.sent_of(kad::REQ).into_iter().next().unwrap();
        assert_eq!(challenge.receiver_id, None);
        assert_eq!(Request::decode(&challenge.packet).unwrap().receiver, PEER_ID);
    }

    fn when_a_hello_arrives_from(&mut self, version: u8) {
        let hello = Hello { sender: Sender { id: PEER_ID, tcp_port: 4662, version }, tags: Vec::new() };
        self.node.received(PEER, PORT, &hello.encode(false).unwrap(), 0, 0);
    }

    fn when_the_peer_answers_our_ping(&mut self) {
        assert_eq!(self.sent_of(kad::PING).len(), 1, "our ping");
        self.node.received(PEER, PORT, &kad_messages::pong(5_000), 0, 0);
    }

    fn when_the_peer_answers_the_challenge(&mut self) {
        let target = self.challenge();
        self.answer_the_lookup_for(target);
    }

    fn when_the_peer_answers_another_lookup(&mut self) {
        let target = self.challenge() ^ UInt128(1);
        self.answer_the_lookup_for(target);
    }

    fn then_the_peer_is_verified(&self, verified: bool) {
        self.assert_the_peer_is_verified(verified);
    }

    fn then_challenges_sent(&self, count: usize) {
        assert_eq!(self.sent_of(kad::REQ).len(), count);
    }

    fn answer_the_lookup_for(&mut self, target: UInt128) {
        let answer = Answer { target, contacts: Vec::new() };
        self.node.received(PEER, PORT, &answer.encode().unwrap(), 0, 0);
    }

    fn assert_the_peer_is_verified(&self, verified: bool) {
        let address = format!("{}:{PORT}", Ipv4Addr::from(PEER));
        let peer: Vec<bool> = self
            .node
            .contact_infos()
            .into_iter()
            .filter(|info| info.address == address)
            .map(|info| info.verified)
            .collect();
        assert_eq!(peer, [verified]);
    }

    fn challenge(&self) -> UInt128 {
        let requests = self.sent_of(kad::REQ);
        Request::decode(&requests.last().expect("our challenge").packet).unwrap().target
    }

    fn sent_of(&self, opcode: u8) -> Vec<Sent> {
        self.lab
            .sent()
            .into_iter()
            .filter(|sent| sent.ip == PEER && sent.packet.protocol() == protocol::KAD && sent.packet.opcode() == opcode)
            .collect()
    }
}

#[test]
fn should_verify_a_node_with_keys_that_answers_our_ping() {
    let mut challenge = Challenge::new();
    challenge.given_a_hello_from(7);
    challenge.given_the_peer_is_not_verified_yet();

    challenge.when_the_peer_answers_our_ping();

    challenge.then_the_peer_is_verified(true);
}

#[test]
fn should_verify_an_older_node_that_answers_a_lookup_only_it_was_sent() {
    let mut challenge = Challenge::new();
    challenge.given_a_hello_from(5);
    challenge.given_the_challenge_is_a_plain_lookup_addressed_to_the_peer();

    challenge.when_the_peer_answers_the_challenge();

    challenge.then_the_peer_is_verified(true);
}

#[test]
fn should_not_verify_an_older_node_that_answers_another_lookup() {
    let mut challenge = Challenge::new();
    challenge.given_a_hello_from(5);

    challenge.when_the_peer_answers_another_lookup();

    challenge.then_the_peer_is_verified(false);
}

#[test]
fn should_challenge_a_node_once_at_a_time() {
    let mut challenge = Challenge::new();
    challenge.given_a_hello_from(5);

    challenge.when_a_hello_arrives_from(5);

    challenge.then_challenges_sent(1);
}

#[test]
fn should_challenge_an_older_node_answering_our_hello() {
    let mut challenge = Challenge::new();
    challenge.given_a_contact_we_say_hello_to(5);
    challenge.given_the_peer_answers_our_hello(5);

    challenge.when_the_peer_answers_the_challenge();

    challenge.then_the_peer_is_verified(true);
}
