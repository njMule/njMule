// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodeSaveTest, under its names: nodes.dat holds the contacts the node heard from, and a short
//! outage does not replace a good one.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod kad_lab;

use std::fs;
use std::path::PathBuf;
use std::time::Duration;

use kad_lab::{KadLab, PORT, ip};
use njmule_engine::kad::kad_contact;
use njmule_engine::kad::kad_node::{self, KadNode};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::{self, UdpKey};
use njmule_engine::protocol::kad::kad_messages::{BootstrapAnswer, Contact, Hello, Sender};

const LOADED: u8 = 30;
const NODE: u32 = ip(80, 1, 1, 1);

struct Save {
    lab: KadLab,
    node: KadNode,
    loaded: Vec<nodes_dat::Contact>,
    heard_from: Vec<UInt128>,
    nodes_dat_before: Vec<u8>,
}

impl Save {
    fn new() -> Self {
        let mut lab = KadLab::new();
        let node = lab.node(kad_lab::settings());
        Self { lab, node, loaded: Vec::new(), heard_from: Vec::new(), nodes_dat_before: Vec::new() }
    }

    fn given_a_nodes_dat_was_loaded(&mut self) {
        for at in 0..LOADED {
            self.loaded.push(nodes_dat::Contact {
                id: UInt128::random(&mut self.lab.random),
                ip: ip(20 + at, 1, 1, 1),
                udp_port: PORT,
                tcp_port: 4662,
                version: 8,
                contact_type: 0,
                udp_key: UdpKey::default(),
                verified: true,
            });
        }
        self.nodes_dat_before = nodes_dat::write(&self.loaded).unwrap();
        fs::write(self.nodes_file(), &self.nodes_dat_before).unwrap();
        self.node.load_nodes();
        assert_eq!(self.node.contacts(), usize::from(LOADED));
    }

    fn given_loaded_contacts_said_hello(&mut self, count: usize) {
        for contact in self.loaded.clone().iter().take(count) {
            self.hello(contact.id, contact.ip);
        }
    }

    fn given_a_node_said_hello(&mut self, ip: u32) {
        let id = UInt128::random(&mut self.lab.random);
        self.hello(id, ip);
        assert_eq!(self.node.contacts(), 1);
    }

    fn given_a_bootstrap_answer_from(&mut self, from: u32, contacts: u8) {
        let told: Vec<Contact> = (0..contacts)
            .map(|at| Contact {
                id: UInt128::random(&mut self.lab.random),
                ip: ip(20 + at, 1, 1, 1),
                udp_port: PORT,
                tcp_port: 4662,
                version: 8,
            })
            .collect();
        let id = UInt128::random(&mut self.lab.random);
        self.node.bootstrap(from, PORT);
        let answer = BootstrapAnswer { sender: Sender { id, tcp_port: 4662, version: 8 }, contacts: told };
        self.node.received(from, PORT, &answer.encode().unwrap(), 0, 0);
        self.heard_from.push(id);
        assert_eq!(self.node.contacts(), usize::from(contacts) + 1);
        assert_eq!(self.node.verified_contacts(), usize::from(contacts) + 1);
    }

    // Its first check finds it new: it is asked once and counts as dead until it answers.
    fn given_the_node_failed_its_check(&mut self) {
        self.given_minutes_pass(1);
        let infos = self.node.contact_infos();
        assert_eq!(infos.len(), 1);
        assert_eq!(infos[0].contact_type, kad_contact::DEAD);
    }

    fn given_minutes_pass(&mut self, minutes: u32) {
        for _ in 0..minutes * 60 {
            self.lab.advance(Duration::from_secs(1));
            self.node.tick();
        }
    }

    fn when_the_contacts_are_saved(&mut self) {
        self.node.save_nodes();
    }

    fn then_nodes_dat_holds_the_contacts_heard_from(&self) {
        let saved = nodes_dat::read(&fs::read(self.nodes_file()).unwrap()).unwrap();
        let mut ids: Vec<UInt128> = saved.contacts.iter().map(|contact| contact.id).collect();
        let mut expected = self.heard_from.clone();
        ids.sort();
        expected.sort();
        assert_eq!(ids, expected);
    }

    fn then_nodes_dat_is_left_as_it_was(&self) {
        assert_eq!(fs::read(self.nodes_file()).unwrap(), self.nodes_dat_before);
    }

    fn then_there_is_no_nodes_dat(&self) {
        assert!(!self.nodes_file().exists());
    }

    fn hello(&mut self, id: UInt128, from: u32) {
        let hello = Hello { sender: Sender { id, tcp_port: 4662, version: 8 }, tags: Vec::new() };
        self.node.received(from, PORT, &hello.encode(false).unwrap(), 0, 0);
        self.heard_from.push(id);
    }

    fn nodes_file(&self) -> PathBuf {
        self.lab.dir.path().join(kad_node::NODES_FILE)
    }
}

#[test]
fn should_save_the_loaded_contacts_that_said_hello() {
    let mut save = Save::new();
    save.given_a_nodes_dat_was_loaded();
    save.given_loaded_contacts_said_hello(kad_node::MIN_SAVED_CONTACTS);

    save.when_the_contacts_are_saved();

    save.then_nodes_dat_holds_the_contacts_heard_from();
}

#[test]
fn should_leave_nodes_dat_as_it_was_when_no_loaded_contact_answers() {
    let mut save = Save::new();
    save.given_a_nodes_dat_was_loaded();
    save.given_minutes_pass(2);

    save.when_the_contacts_are_saved();

    save.then_nodes_dat_is_left_as_it_was();
}

#[test]
fn should_leave_nodes_dat_as_it_was_when_too_few_loaded_contacts_answer() {
    let mut save = Save::new();
    save.given_a_nodes_dat_was_loaded();
    save.given_loaded_contacts_said_hello(kad_node::MIN_SAVED_CONTACTS - 1);

    save.when_the_contacts_are_saved();

    save.then_nodes_dat_is_left_as_it_was();
}

#[test]
fn should_save_a_new_node_that_said_hello() {
    let mut save = Save::new();
    save.given_a_node_said_hello(NODE);

    save.when_the_contacts_are_saved();

    save.then_nodes_dat_holds_the_contacts_heard_from();
}

// With no contact yet, those of a bootstrap answer are taken as verified: only the node that answered is proven.
#[test]
fn should_save_only_the_node_that_answered_a_bootstrap() {
    let mut save = Save::new();
    save.given_a_bootstrap_answer_from(NODE, 20);

    save.when_the_contacts_are_saved();

    save.then_nodes_dat_holds_the_contacts_heard_from();
}

#[test]
fn should_not_save_a_node_that_stopped_answering() {
    let mut save = Save::new();
    save.given_a_node_said_hello(NODE);
    save.given_the_node_failed_its_check();

    save.when_the_contacts_are_saved();

    save.then_there_is_no_nodes_dat();
}
