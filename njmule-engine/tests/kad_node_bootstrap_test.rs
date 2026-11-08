// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodeBootstrapTest, under its names: how a node starts, from a bootstrap list asked one contact
//! at a time, from saved and shipped contacts, and from clients met over eD2K.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use crate::kad_lab;

use std::net::Ipv4Addr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use kad_lab::{KadLab, PORT, Sent, ip};
use njmule_engine::kad::kad_node::{self, KadNode};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::{self, UdpKey};
use njmule_engine::protocol::kad::kad_messages::{self, BootstrapAnswer, Contact, Sender};
use njmule_engine::protocol::packet::opcodes::kad;

const BLOCKED: u32 = ip(99, 1, 1, 1);
const CLIENT: u32 = ip(60, 1, 1, 1);
const OTHER_CLIENT: u32 = ip(61, 1, 1, 1);

struct Bootstrap {
    lab: KadLab,
    node: KadNode,
    good: Vec<nodes_dat::Contact>,
    bad: Vec<nodes_dat::Contact>,
    saved: Vec<nodes_dat::Contact>,
    shipped: Arc<Mutex<Vec<nodes_dat::Contact>>>,
    shipped_reads: Arc<Mutex<u32>>,
    loaded: bool,
}

impl Bootstrap {
    fn new() -> Self {
        let mut lab = KadLab::new();
        let mut settings = kad_lab::settings();
        settings.blocked = Arc::new(|ip: u32| ip == BLOCKED);
        settings.udp_port = PORT;
        let node = lab.node(settings);
        Self {
            lab,
            node,
            good: Vec::new(),
            bad: Vec::new(),
            saved: Vec::new(),
            shipped: Arc::default(),
            shipped_reads: Arc::default(),
            loaded: false,
        }
    }

    fn given_a_client_said_hello(&mut self, ip: u32, kad_port: u16, kad_version: u8) {
        self.when_a_client_says_hello(ip, kad_port, kad_version);
    }

    fn given_a_nodes_dat(&mut self) {
        let record = self.record(ip(20, 1, 1, 1), PORT, 8);
        self.good.push(record);
        std::fs::write(self.lab.dir.path().join(kad_node::NODES_FILE), nodes_dat::write(&self.good).unwrap()).unwrap();
    }

    fn given_a_bootstrap_list_of_good_and_bad_contacts(&mut self, good_ones: u8) {
        for at in 0..good_ones {
            let record = self.record(ip(20 + at, 1, 1, 1), PORT, 8);
            self.good.push(record);
        }
        self.bad = vec![
            self.record(ip(90, 1, 1, 1), 53, 5),
            self.record(ip(90, 1, 1, 2), 0, 8),
            self.record(ip(90, 1, 1, 3), PORT, 1),
            self.record(ip(192, 168, 1, 3), PORT, 8),
            self.record(BLOCKED, PORT, 8),
        ];
        let mut all = self.bad.clone();
        all.extend(self.good.iter().cloned());
        self.node.add_contacts(&nodes_dat::Contents { file_version: 3, bootstrap: true, contacts: all });
    }

    fn given_saved_contacts(&mut self, count: u8) {
        for at in 0..count {
            let record = self.record(ip(30 + at, 1, 1, 1), PORT, 8);
            self.saved.push(record);
        }
        let contents = nodes_dat::Contents { file_version: 2, bootstrap: false, contacts: self.saved.clone() };
        self.node.add_contacts(&contents);
    }

    fn given_shipped_contacts(&mut self, count: u8) {
        for at in 0..count {
            let record = self.record(ip(40 + at, 1, 1, 1), PORT, 8);
            self.shipped.lock().unwrap().push(record);
        }
        let (shipped, reads) = (Arc::clone(&self.shipped), Arc::clone(&self.shipped_reads));
        self.node.bootstrap_from(Box::new(move || {
            *reads.lock().unwrap() += 1;
            nodes_dat::Contents { file_version: 3, bootstrap: true, contacts: shipped.lock().unwrap().clone() }
        }));
    }

    fn given_a_saved_contact_pings(&mut self) {
        self.node.received(self.saved[0].ip, PORT, &kad_messages::ping(), 0, 0);
    }

    fn given_seconds_pass(&mut self, seconds: u64) {
        self.when_seconds_pass(seconds);
    }

    fn given_the_asked_node_answers_with_contacts(&mut self, count: u8) {
        let request = self.bootstrap_requests()[0].clone();
        let contacts = self.contacts(count);
        let id = self.good.iter().find(|contact| contact.ip == request.ip).unwrap().id;
        let answer = BootstrapAnswer { sender: Sender { id, tcp_port: 4662, version: 8 }, contacts };
        self.node.received(request.ip, request.port, &answer.encode().unwrap(), 0, 0);
    }

    fn when_a_client_says_hello(&mut self, ip: u32, kad_port: u16, kad_version: u8) {
        self.node.bootstrap_via(ip, kad_port, kad_version);
    }

    fn when_the_client_answers_with_contacts(&mut self, ip: u32, count: u8) {
        let contacts = self.contacts(count);
        let sender = Sender { id: UInt128::random(&mut self.lab.random), tcp_port: 4662, version: 9 };
        self.node.received(ip, PORT, &BootstrapAnswer { sender, contacts }.encode().unwrap(), 0, 0);
    }

    fn when_the_nodes_are_loaded(&mut self) {
        self.loaded = self.node.load_nodes();
    }

    fn when_seconds_pass(&mut self, seconds: u64) {
        for _ in 0..seconds {
            self.node.tick();
            self.lab.advance(Duration::from_secs(1));
        }
    }

    fn then_the_contacts_include(&self, ip: u32) {
        let address = format!("{}:{PORT}", Ipv4Addr::from(ip));
        assert!(self.node.contact_infos().iter().any(|info| info.address == address));
    }

    fn then_bootstrap_requests_went_to(&self, ips: &[u32]) {
        assert_eq!(self.bootstrap_requests().iter().map(|sent| sent.ip).collect::<Vec<_>>(), ips);
    }

    fn then_there_was_no_nodes_dat(&self) {
        assert!(!self.loaded);
    }

    fn then_there_was_a_nodes_dat(&self) {
        assert!(self.loaded);
    }

    fn then_bootstrap_requests_went(&self, count: usize) {
        assert_eq!(self.bootstrap_requests().len(), count);
    }

    fn then_bootstrap_requests_went_to_shipped_contacts(&self, count: usize) {
        let shipped: Vec<u32> = self.shipped.lock().unwrap().iter().map(|contact| contact.ip).collect();
        let requests = self.bootstrap_requests();
        assert_eq!(requests.len(), count);
        assert!(requests.iter().all(|sent| shipped.contains(&sent.ip)));
    }

    fn then_the_shipped_contacts_were_read(&self, times: u32) {
        assert_eq!(*self.shipped_reads.lock().unwrap(), times);
    }

    fn then_bootstrap_requests_went_to_the_closest_good_contacts(&self, count: usize) {
        let own = self.node.id();
        let mut closest = self.good.clone();
        closest.sort_by_key(|contact| contact.id ^ own);
        let expected: Vec<u32> = closest.iter().take(count).map(|contact| contact.ip).collect();
        assert_eq!(self.bootstrap_requests().iter().map(|sent| sent.ip).collect::<Vec<_>>(), expected);
    }

    fn then_no_bad_contact_was_asked(&self) {
        let bad: Vec<u32> = self.bad.iter().map(|contact| contact.ip).collect();
        assert!(!self.lab.sent().iter().any(|sent| bad.contains(&sent.ip)));
    }

    fn then_the_request_is_obfuscated_for(&self, id: UInt128) {
        let requests = self.bootstrap_requests();
        assert_eq!(requests.len(), 1);
        assert_eq!(requests[0].receiver_id, Some(id));
    }

    fn then_the_node_has_contacts(&self, count: usize) {
        assert_eq!(self.node.contacts(), count);
    }

    fn then_the_answering_node_is_no_contact(&self) {
        let answering = self.bootstrap_requests()[0].ip;
        let address = format!("{}:{PORT}", Ipv4Addr::from(answering));
        assert!(!self.node.contact_infos().iter().any(|info| info.address == address));
    }

    fn bootstrap_requests(&self) -> Vec<Sent> {
        self.lab.sent().into_iter().filter(|sent| sent.packet.opcode() == kad::BOOTSTRAP_REQ).collect()
    }

    fn contacts(&mut self, count: u8) -> Vec<Contact> {
        (0..count)
            .map(|at| Contact {
                id: UInt128::random(&mut self.lab.random),
                ip: ip(70 + at, 1, 1, 1),
                udp_port: PORT,
                tcp_port: 4662,
                version: 8,
            })
            .collect()
    }

    fn record(&mut self, ip: u32, udp_port: u16, version: u8) -> nodes_dat::Contact {
        nodes_dat::Contact {
            id: UInt128::random(&mut self.lab.random),
            ip,
            udp_port,
            tcp_port: 4662,
            version,
            contact_type: 0,
            udp_key: UdpKey::default(),
            verified: false,
        }
    }
}

const REFILL_SECONDS: u64 = kad_node::BOOTSTRAP_REFILL_AFTER.as_secs();
const CLIENT_GAP_SECONDS: u64 = kad_node::CLIENT_BOOTSTRAP_EVERY.as_secs();

#[test]
fn should_ask_the_bootstrap_contacts_one_by_one() {
    let mut bootstrap = Bootstrap::new();
    bootstrap.given_a_bootstrap_list_of_good_and_bad_contacts(60);

    bootstrap.when_seconds_pass(10);

    bootstrap.then_bootstrap_requests_went(5);
}

#[test]
fn should_ask_only_the_fifty_closest_usable_contacts() {
    let mut bootstrap = Bootstrap::new();
    bootstrap.given_a_bootstrap_list_of_good_and_bad_contacts(60);
    bootstrap.given_seconds_pass(10);

    bootstrap.when_seconds_pass(200);

    bootstrap.then_bootstrap_requests_went_to_the_closest_good_contacts(kad_node::BOOTSTRAP_CONTACTS);
    bootstrap.then_no_bad_contact_was_asked();
    bootstrap.then_the_node_has_contacts(0);
}

#[test]
fn should_obfuscate_the_request_for_a_node_that_takes_it() {
    let mut bootstrap = Bootstrap::new();
    bootstrap.given_a_bootstrap_list_of_good_and_bad_contacts(1);

    bootstrap.when_seconds_pass(1);

    bootstrap.then_the_request_is_obfuscated_for(bootstrap.good[0].id);
}

#[test]
fn should_stop_asking_once_connected_and_never_take_the_node_that_answered() {
    let mut bootstrap = Bootstrap::new();
    bootstrap.given_a_bootstrap_list_of_good_and_bad_contacts(10);
    bootstrap.given_seconds_pass(1);
    bootstrap.given_the_asked_node_answers_with_contacts(3);

    bootstrap.when_seconds_pass(60);

    bootstrap.then_bootstrap_requests_went(1);
    bootstrap.then_the_node_has_contacts(3);
    bootstrap.then_the_answering_node_is_no_contact();
}

#[test]
fn should_tell_that_there_was_no_nodes_dat_to_load() {
    let mut bootstrap = Bootstrap::new();

    bootstrap.when_the_nodes_are_loaded();

    bootstrap.then_there_was_no_nodes_dat();
}

#[test]
fn should_tell_that_there_was_a_nodes_dat_to_load() {
    let mut bootstrap = Bootstrap::new();
    bootstrap.given_a_nodes_dat();

    bootstrap.when_the_nodes_are_loaded();

    bootstrap.then_there_was_a_nodes_dat();
}

#[test]
fn should_ask_the_shipped_contacts_at_once_next_to_the_saved_ones() {
    let mut bootstrap = Bootstrap::new();
    bootstrap.given_saved_contacts(5);
    bootstrap.given_shipped_contacts(10);

    bootstrap.when_seconds_pass(1);

    bootstrap.then_bootstrap_requests_went_to_shipped_contacts(1);
}

#[test]
fn should_stop_asking_the_shipped_contacts_once_a_saved_contact_answers() {
    let mut bootstrap = Bootstrap::new();
    bootstrap.given_saved_contacts(5);
    bootstrap.given_shipped_contacts(10);
    bootstrap.given_seconds_pass(1);
    bootstrap.given_a_saved_contact_pings();

    bootstrap.when_seconds_pass(120);

    bootstrap.then_bootstrap_requests_went_to_shipped_contacts(1);
    bootstrap.then_the_shipped_contacts_were_read(1);
}

#[test]
fn should_not_ask_the_shipped_contacts_again_before_the_pause() {
    let mut bootstrap = Bootstrap::new();
    bootstrap.given_shipped_contacts(10);

    bootstrap.when_seconds_pass(REFILL_SECONDS);

    bootstrap.then_bootstrap_requests_went_to_shipped_contacts(10);
    bootstrap.then_the_shipped_contacts_were_read(1);
}

#[test]
fn should_ask_the_shipped_contacts_again_when_they_ran_out_without_a_connection() {
    let mut bootstrap = Bootstrap::new();
    bootstrap.given_shipped_contacts(10);

    bootstrap.when_seconds_pass(REFILL_SECONDS + 1);

    bootstrap.then_bootstrap_requests_went_to_shipped_contacts(11);
    bootstrap.then_the_shipped_contacts_were_read(2);
}

#[test]
fn should_ask_a_client_running_kad_for_contacts_while_not_connected() {
    let mut bootstrap = Bootstrap::new();

    bootstrap.when_a_client_says_hello(CLIENT, PORT, 9);

    bootstrap.then_bootstrap_requests_went_to(&[CLIENT]);
}

#[test]
fn should_take_an_answering_client_as_a_contact_while_shipped_contacts_wait() {
    let mut bootstrap = Bootstrap::new();
    bootstrap.given_shipped_contacts(10);
    bootstrap.given_a_client_said_hello(CLIENT, PORT, 9);

    bootstrap.when_the_client_answers_with_contacts(CLIENT, 3);

    bootstrap.then_the_node_has_contacts(4);
    bootstrap.then_the_contacts_include(CLIENT);
}

#[test]
fn should_not_ask_another_client_before_the_gap() {
    let mut bootstrap = Bootstrap::new();
    bootstrap.given_a_client_said_hello(CLIENT, PORT, 9);
    bootstrap.given_seconds_pass(CLIENT_GAP_SECONDS - 1);

    bootstrap.when_a_client_says_hello(OTHER_CLIENT, PORT, 9);

    bootstrap.then_bootstrap_requests_went_to(&[CLIENT]);
}

#[test]
fn should_ask_another_client_once_the_gap_passed() {
    let mut bootstrap = Bootstrap::new();
    bootstrap.given_a_client_said_hello(CLIENT, PORT, 9);
    bootstrap.given_seconds_pass(CLIENT_GAP_SECONDS);

    bootstrap.when_a_client_says_hello(OTHER_CLIENT, PORT, 9);

    bootstrap.then_bootstrap_requests_went_to(&[CLIENT, OTHER_CLIENT]);
}

#[test]
fn should_not_ask_a_client_once_connected() {
    let mut bootstrap = Bootstrap::new();
    bootstrap.given_saved_contacts(5);
    bootstrap.given_a_saved_contact_pings();

    bootstrap.when_a_client_says_hello(CLIENT, PORT, 9);

    bootstrap.then_bootstrap_requests_went(0);
}

#[test]
fn should_not_ask_a_client_without_a_kad_port() {
    let mut bootstrap = Bootstrap::new();

    bootstrap.when_a_client_says_hello(CLIENT, 0, 9);

    bootstrap.then_bootstrap_requests_went(0);
}

#[test]
fn should_not_ask_a_client_running_the_first_kad() {
    let mut bootstrap = Bootstrap::new();

    bootstrap.when_a_client_says_hello(CLIENT, PORT, 1);

    bootstrap.then_bootstrap_requests_went(0);
}
