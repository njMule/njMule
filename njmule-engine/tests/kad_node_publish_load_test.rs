// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodePublishLoadTest, under its names: a keyword whose nodes report a heavy load is left alone
//! for days, in proportion to the load.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod kad_lab;

use std::time::Duration;

use kad_lab::{KadLab, PORT, ip};
use njmule_engine::kad::kad_node::KadNode;
use njmule_engine::kad::kad_publisher::Node;
use njmule_engine::kad::keywords;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::kad::kad_messages::{Answer, Entry, PublishAnswer};
use njmule_engine::protocol::packet::opcodes::kad;

const PEER: u32 = ip(20, 1, 1, 1);
const WORD: &str = "ubuntu";
const DAY: Duration = Duration::from_secs(24 * 3600);

struct PublishLoad {
    lab: KadLab,
    node: KadNode,
    started: bool,
}

impl PublishLoad {
    fn new() -> Self {
        let mut lab = KadLab::new();
        let mut settings = kad_lab::settings();
        settings.udp_port = PORT;
        let mut node = lab.node(settings);
        node.add_contacts(&kad_lab::one_contact(keywords::id(WORD), PEER));
        Self { lab, node, started: false }
    }

    fn given_a_keyword_publish_whose_node_reports_the_load(&mut self, load: u8) {
        let target = keywords::id(WORD);
        assert!(self.node.publish_keyword(WORD, vec![file()]));
        let answer = Answer { target, contacts: Vec::new() };
        self.node.received(PEER, PORT, &answer.encode().unwrap(), 0, 0);
        self.seconds(4);
        assert!(self.lab.sent().iter().any(|sent| sent.packet.opcode() == kad::PUBLISH_KEY_REQ), "the publish");
        self.node.received(PEER, PORT, &PublishAnswer { target, load }.encode(), 0, 0);
        self.seconds(30);
        assert_eq!(self.node.running_keyword_publishes(), 0, "the publish ended");
    }

    fn given_the_publish_was_refused_after(&mut self, wait: Duration) {
        self.when_the_keyword_is_published_again_after(wait);
        self.then_the_publish_started(false);
    }

    fn when_the_keyword_is_published_again_after(&mut self, wait: Duration) {
        self.lab.advance(wait);
        self.started = self.node.publish_keyword(WORD, vec![file()]);
    }

    fn then_the_publish_started(&self, started: bool) {
        assert_eq!(self.started, started);
    }

    fn seconds(&mut self, seconds: u32) {
        for _ in 0..seconds {
            self.lab.advance(Duration::from_secs(1));
            self.node.tick();
        }
    }
}

fn file() -> Entry {
    kad_lab::file(UInt128(7u128 << 64 | 7), "ubuntu.iso", 1_000)
}

#[test]
fn should_leave_a_heavily_loaded_keyword_alone_for_days() {
    let mut publish = PublishLoad::new();
    publish.given_a_keyword_publish_whose_node_reports_the_load(60);

    publish.when_the_keyword_is_published_again_after(DAY * 4);

    publish.then_the_publish_started(false);
}

#[test]
fn should_publish_a_heavily_loaded_keyword_again_once_its_days_of_rest_are_over() {
    let mut publish = PublishLoad::new();
    publish.given_a_keyword_publish_whose_node_reports_the_load(60);
    publish.given_the_publish_was_refused_after(DAY * 4);

    publish.when_the_keyword_is_published_again_after(Duration::from_secs(5 * 3600));

    publish.then_the_publish_started(true);
}

#[test]
fn should_publish_a_lightly_loaded_keyword_again_at_once() {
    let mut publish = PublishLoad::new();
    publish.given_a_keyword_publish_whose_node_reports_the_load(20);

    publish.when_the_keyword_is_published_again_after(Duration::ZERO);

    publish.then_the_publish_started(true);
}
