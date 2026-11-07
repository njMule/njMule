// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadSimulationTest, under its names: two hundred nodes on a simulated wire bootstrap from each
//! other, run for four hours, publish and find a keyword and a source, and one starts again from its nodes.dat.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod kad_wire;

use std::sync::Arc;

use kad_wire::Wire;
use njmule_engine::kad::kad_node::{self, FileResult, KadEvent, KadNode, Source};
use njmule_engine::kad::keywords;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::kad::kad_messages::Entry;
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};
use rand::RngExt;

const NODES: usize = 200;
// Publish lookups live 140 s: searching earlier may miss a store still on its way.
const PUBLISH_WAIT: u64 = 150;
const SEARCHES: usize = 3;
const SIZE: u64 = 123_456;

struct Simulation {
    wire: Wire,
    word: String,
    file: [u8; 16],
    restarted: Option<KadNode>,
}

impl Simulation {
    fn new() -> Self {
        Self { wire: Wire::new(20_261_013), word: String::new(), file: [0; 16], restarted: None }
    }

    fn given_nodes_that_bootstrapped_from_each_other(&mut self, count: usize) {
        for at in 0..count {
            let ip = u32::from_be_bytes([20 + u8::try_from(at / 200).unwrap(), u8::try_from(at % 200).unwrap(), 7, 1]);
            self.wire.add_node(ip, Arc::new(|_| None));
        }
        self.wire.bootstrap_from_each_other();
    }

    fn given_the_network_ran_for_seconds(&mut self, seconds: u64) {
        self.wire.run(seconds);
    }

    fn given_every_node_is_connected_to_enough_contacts(&self) {
        let nodes = &self.wire.nodes;
        let lonely: Vec<String> = (0..NODES)
            .filter(|&at| !nodes[at].is_connected())
            .map(|at| format!("{at}:{}", nodes[at].contacts()))
            .collect();
        let open = nodes.iter().filter(|node| !node.is_firewalled()).count();
        let checks = *self.wire.reachability_checks.lock().unwrap();
        assert!(lonely.is_empty(), "nodes not connected (index:contacts) {lonely:?}, {open} open, {checks} checks");
        let total: usize = nodes.iter().map(KadNode::contacts).sum();
        let verified: usize = nodes.iter().map(KadNode::verified_contacts).sum();
        assert!(total / NODES >= 30, "contacts per node {} ({} verified)", total / NODES, verified / NODES);
        assert!(verified / NODES >= 10, "verified contacts per node {}", verified / NODES);
        let ready = nodes.iter().filter(|node| node.is_ready()).count();
        assert!(ready > NODES * 9 / 10, "self lookups done: {ready}");
    }

    // A keyword published by one node is found by another; with few nodes, the keyword is picked so that some node is
    // close enough to store it.
    fn given_a_keyword_published_by_node(&mut self, index: usize) {
        self.word = (0..).map(|n| format!("pictures{n}")).find(|word| self.near(keywords::id(word))).unwrap();
        self.file = loop {
            let hash: [u8; 16] = self.wire.random.random();
            if self.near(UInt128::from_big_endian(hash)) {
                break hash;
            }
        };
        let tags = vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: format!("Night Sky {}.zip", self.word) },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: SIZE },
            Tag::Int { name: TagName::id(file_tag::SOURCES), value: 3 },
        ];
        let entry = Entry { id: UInt128::from_big_endian(self.file), tags };
        assert!(self.wire.nodes[index].publish_keyword(&self.word, vec![entry]));
        self.wire.run(PUBLISH_WAIT);
        let storing = self.wire.nodes.iter().filter(|node| node.indexed().0 > 0).count();
        assert!(storing > 0, "nodes that store the keyword");
    }

    // A lookup may miss the one node near the key in so small a network: like a user, the searcher tries again.
    fn given_the_keyword_found_by_node(&mut self, index: usize) {
        let mut found: Vec<FileResult> = Vec::new();
        for _ in 0..SEARCHES {
            if !found.is_empty() {
                break;
            }
            assert!(self.wire.nodes[index].search_keyword(&self.word, None).is_some());
            self.wire.run(60);
            for event in self.wire.nodes[index].take_events() {
                if let KadEvent::Files { results, .. } = event {
                    found.extend(results);
                }
            }
        }
        assert!(!found.is_empty(), "the keyword is found");
        assert_eq!(found[0].name, format!("Night Sky {}.zip", self.word));
        assert_eq!(found[0].hash, self.file);
        assert_eq!(found[0].size, SIZE);
    }

    // A source published by one node is found by another.
    fn given_a_source_published_by_node(&mut self, index: usize) {
        assert!(self.wire.nodes[index].publish_source(self.file, SIZE));
        self.wire.run(PUBLISH_WAIT);
    }

    fn given_the_source_found_by_node(&mut self, index: usize, source: usize) {
        let mut sources: Vec<Source> = Vec::new();
        for _ in 0..SEARCHES {
            if !sources.is_empty() {
                break;
            }
            assert!(self.wire.nodes[index].find_sources(self.file, SIZE));
            self.wire.run(60);
            for event in self.wire.nodes[index].take_events() {
                if let KadEvent::Sources { sources: found, .. } = event {
                    sources.extend(found);
                }
            }
        }
        assert!(!sources.is_empty(), "the source is found");
        assert_eq!(sources[0].ip, self.wire.ips[source]);
        assert_eq!(sources[0].tcp_port, 4662);
    }

    // Nodes asked to check our TCP port connected back: most nodes know they are reachable and where other nodes see
    // them.
    fn given_most_nodes_know_they_are_reachable_and_where_others_see_them(&self) {
        assert!(*self.wire.reachability_checks.lock().unwrap() > 0);
        let open = self.wire.nodes.iter().filter(|node| !node.is_firewalled()).count();
        assert!(open > NODES * 9 / 10, "open nodes: {open}");
        for (node, &ip) in self.wire.nodes.iter().zip(&self.wire.ips) {
            if !node.is_firewalled() {
                assert_eq!(node.public_ip(), ip);
            }
        }
    }

    fn given_a_node_saved_its_contacts(&mut self, index: usize) {
        self.wire.nodes[index].save_nodes();
        assert!(self.wire.config(index).join(kad_node::NODES_FILE).exists());
    }

    // The contacts survive a restart through nodes.dat.
    fn when_the_node_restarts_from_its_nodes_dat(&mut self, index: usize) {
        let mut restarted = self.wire.restart(index);
        restarted.load_nodes();
        self.restarted = Some(restarted);
    }

    fn then_the_restarted_node_has_more_contacts_than(&self, count: usize) {
        assert!(self.restarted.as_ref().unwrap().contacts() > count);
    }

    fn then_the_restarted_node_keeps_the_id_of_node(&self, index: usize) {
        assert_eq!(self.restarted.as_ref().unwrap().id(), self.wire.nodes[index].id());
    }

    fn near(&self, target: UInt128) -> bool {
        self.wire.some_node_near(target, &[])
    }
}

#[test]
fn should_let_nodes_find_each_other_publish_and_search() {
    let mut simulation = Simulation::new();
    simulation.given_nodes_that_bootstrapped_from_each_other(NODES);
    // All nodes start together, so their first self lookups (after 3 minutes) see a young network; the second one,
    // after 4 hours, finds the nearest nodes as joining a mature network does.
    simulation.given_the_network_ran_for_seconds(4 * 60 * 60 + 5 * 60);
    simulation.given_every_node_is_connected_to_enough_contacts();
    simulation.given_a_keyword_published_by_node(17);
    simulation.given_the_keyword_found_by_node(150);
    simulation.given_a_source_published_by_node(42);
    simulation.given_the_source_found_by_node(99, 42);
    simulation.given_most_nodes_know_they_are_reachable_and_where_others_see_them();
    simulation.given_a_node_saved_its_contacts(5);

    simulation.when_the_node_restarts_from_its_nodes_dat(5);

    simulation.then_the_restarted_node_has_more_contacts_than(10);
    simulation.then_the_restarted_node_keeps_the_id_of_node(5);
}
