// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodeEstimateTest, under its names: how many nodes and files the Kad network holds, as the node
//! tells, made up for the firewalled nodes no one can take as a contact.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use crate::kad_lab;

use kad_lab::{KadLab, PORT, ip};
use njmule_engine::kad::kad_node::{self, KadNode};
use njmule_engine::kad::routing_table;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::kad::kad_messages::{Hello, Sender};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};

struct Estimate {
    lab: KadLab,
    node: KadNode,
    greetings: u8,
    estimate: u64,
    share: f64,
    modifier: f64,
}

impl Estimate {
    fn new() -> Self {
        let mut lab = KadLab::new();
        let node = lab.node(kad_lab::settings());
        Self { lab, node, greetings: 0, estimate: 0, share: 0.0, modifier: 0.0 }
    }

    fn given_nodes_greet_us(&mut self, count: u8, udp_firewalled: bool) {
        for _ in 0..count {
            let sender = Sender { id: UInt128::random(&mut self.lab.random), tcp_port: 4662, version: 8 };
            let tags = if udp_firewalled {
                vec![Tag::Int { name: TagName::id(file_tag::KADMISCOPTIONS), value: 0x01 }]
            } else {
                Vec::new()
            };
            let from = ip(20 + self.greetings, 1, 1, 1);
            self.greetings += 1;
            self.node.received(from, PORT, &Hello { sender, tags }.encode(false).unwrap(), 0, 0);
        }
    }

    fn when_the_nodes_are_estimated(&mut self) {
        self.estimate = self.node.estimated_nodes();
    }

    fn when_the_files_are_estimated_with_the_servers_average(&mut self, average: u64) {
        self.estimate = self.node.estimated_files(average);
    }

    fn when_the_firewalled_share_is_taken(&mut self) {
        self.share = self.node.udp_firewalled_share();
    }

    fn when_the_modifier_is_taken(&mut self, firewalled_udp: bool, firewalled_share: f64, newer_share: f64) {
        self.modifier = kad_node::firewalled_modifier(firewalled_udp, firewalled_share, newer_share);
    }

    fn then_the_estimate_is(&self, expected: u64) {
        assert_eq!(self.estimate, expected);
    }

    fn then_the_share_is(&self, expected: f64) {
        assert!((self.share - expected).abs() < 1e-9, "{}", self.share);
    }

    fn then_the_modifier_is(&self, expected: f64) {
        assert!((self.modifier - expected).abs() < 1e-9, "{}", self.modifier);
    }
}

#[test]
fn should_estimate_a_full_bin_of_nodes_without_contacts() {
    let mut estimate = Estimate::new();

    estimate.when_the_nodes_are_estimated();

    estimate.then_the_estimate_is(routing_table::K as u64);
}

#[test]
fn should_estimate_the_files_of_an_average_node_when_the_servers_tell_none() {
    let mut estimate = Estimate::new();

    estimate.when_the_files_are_estimated_with_the_servers_average(0);

    estimate.then_the_estimate_is(108 * routing_table::K as u64);
}

#[test]
fn should_estimate_the_files_from_the_servers_average_when_larger() {
    let mut estimate = Estimate::new();

    estimate.when_the_files_are_estimated_with_the_servers_average(500);

    estimate.then_the_estimate_is(500 * routing_table::K as u64);
}

#[test]
fn should_tell_the_share_of_greeting_nodes_that_are_udp_firewalled() {
    let mut estimate = Estimate::new();
    estimate.given_nodes_greet_us(11, false);
    estimate.given_nodes_greet_us(1, true);

    estimate.when_the_firewalled_share_is_taken();

    estimate.then_the_share_is(1.0 / 12.0);
}

#[test]
fn should_tell_no_firewalled_share_until_more_than_ten_open_nodes_greet_us() {
    let mut estimate = Estimate::new();
    estimate.given_nodes_greet_us(10, false);
    estimate.given_nodes_greet_us(5, true);

    estimate.when_the_firewalled_share_is_taken();

    estimate.then_the_share_is(0.0);
}

#[test]
fn should_weigh_the_firewalled_nodes_by_the_share_of_newer_contacts() {
    let cases = [
        (false, 0.0, 0.5, 1.20),
        (true, 0.0, 0.0, 1.20),
        (true, 0.0, 0.5, 1.30),
        (false, 0.25, 1.0, 1.25),
        (false, 0.25, 0.0, 1.20),
        (true, 0.25, 1.0, 1.40),
    ];
    for (firewalled_udp, firewalled_share, newer_share, expected) in cases {
        let mut estimate = Estimate::new();

        estimate.when_the_modifier_is_taken(firewalled_udp, firewalled_share, newer_share);

        estimate.then_the_modifier_is(expected);
    }
}
