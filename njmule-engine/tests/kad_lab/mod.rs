// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A Kad node for the Java engine's tests of it: a profile folder, a clock the tests move, and a transport and a host
//! that remember what the node sent and asked for.

#![allow(dead_code, clippy::unwrap_used, clippy::expect_used, clippy::arithmetic_side_effects)]

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use njmule_engine::kad::kad_identity::KadIdentity;
use njmule_engine::kad::kad_node::{Host, KadNode, Settings, Transport};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::{self, UdpKey};
use njmule_engine::protocol::kad::kad_messages::Entry;
use njmule_engine::protocol::packet::Packet;
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};
use rand::SeedableRng;
use rand::rngs::StdRng;

pub const PORT: u16 = 4672;

pub const fn ip(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from_be_bytes([a, b, c, d])
}

/// A datagram the node sent.
#[derive(Debug, Clone)]
pub struct Sent {
    pub ip: u32,
    pub port: u16,
    pub packet: Packet,
    pub receiver_id: Option<UInt128>,
    pub receiver_key: u32,
    pub sender_key: u32,
}

struct Recording(Arc<Mutex<Vec<Sent>>>);

impl Transport for Recording {
    fn send(
        &mut self,
        ip: u32,
        port: u16,
        packet: &Packet,
        receiver_id: Option<UInt128>,
        receiver_key: u32,
        sender_key: u32,
    ) {
        let sent = Sent { ip, port, packet: packet.clone(), receiver_id, receiver_key, sender_key };
        self.0.lock().unwrap().push(sent);
    }
}

/// What the node asked of its host, as text: `reach ip:port` and `udp ip:port`.
#[derive(Clone, Default)]
pub struct Checks {
    pub asked: Arc<Mutex<Vec<String>>>,
    pub known_clients: Arc<Mutex<Vec<u32>>>,
    pub high_id: Arc<Mutex<bool>>,
}

impl Host for Checks {
    fn check_reachable(&mut self, ip: u32, port: u16, _: [u8; 16], _: u8) {
        self.asked.lock().unwrap().push(format!("reach {}:{port}", std::net::Ipv4Addr::from(ip)));
    }

    fn check_udp(&mut self, ip: u32, tcp_port: u16, _: u16, _: u16, _: u32) {
        self.asked.lock().unwrap().push(format!("udp {}:{tcp_port}", std::net::Ipv4Addr::from(ip)));
    }

    fn is_known_client(&self, ip: u32) -> bool {
        self.known_clients.lock().unwrap().contains(&ip)
    }

    fn has_high_id(&self) -> bool {
        *self.high_id.lock().unwrap()
    }
}

pub struct KadLab {
    pub dir: tempfile::TempDir,
    pub random: StdRng,
    pub time: Arc<Mutex<Instant>>,
    pub sent: Arc<Mutex<Vec<Sent>>>,
    pub checks: Checks,
}

impl KadLab {
    pub fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            random: StdRng::from_rng(&mut rand::rng()),
            time: Arc::new(Mutex::new(Instant::now())),
            sent: Arc::default(),
            checks: Checks::default(),
        }
    }

    /// A node with an identity of its own in the lab's folder.
    pub fn node(&mut self, settings: Settings) -> KadNode {
        let identity = KadIdentity::load_or_create(self.dir.path(), &mut self.random).unwrap();
        self.node_as(identity, settings)
    }

    pub fn node_as(&mut self, identity: KadIdentity, settings: Settings) -> KadNode {
        let clock = Arc::clone(&self.time);
        KadNode::new(
            identity,
            Box::new(Recording(Arc::clone(&self.sent))),
            Box::new(self.checks.clone()),
            settings,
            self.dir.path(),
            Box::new(move || *clock.lock().unwrap()),
        )
    }

    pub fn advance(&self, by: Duration) {
        *self.time.lock().unwrap() += by;
    }

    pub fn now(&self) -> Instant {
        *self.time.lock().unwrap()
    }

    /// What the node sent so far, and forgets it.
    pub fn take_sent(&self) -> Vec<Sent> {
        std::mem::take(&mut *self.sent.lock().unwrap())
    }

    pub fn sent(&self) -> Vec<Sent> {
        self.sent.lock().unwrap().clone()
    }

    pub fn asked(&self) -> Vec<String> {
        self.checks.asked.lock().unwrap().clone()
    }
}

/// Settings of a node at TCP port 4662 that trusts no address and blocks none.
pub fn settings() -> Settings {
    Settings::new(4662, [0; 16], 0, Arc::new(|_| false), Arc::new(|_| false))
}

/// The contents of a nodes.dat with one verified contact of id `id` at `ip`.
pub fn one_contact(id: UInt128, ip: u32) -> nodes_dat::Contents {
    let contact = nodes_dat::Contact {
        id,
        ip,
        udp_port: PORT,
        tcp_port: 4662,
        version: 8,
        contact_type: 0,
        udp_key: UdpKey::default(),
        verified: true,
    };
    nodes_dat::Contents { file_version: 2, bootstrap: false, contacts: vec![contact] }
}

/// A file entry of a name and a size, as searches answer and keyword publishes carry.
pub fn file(id: UInt128, name: &str, size: u64) -> Entry {
    Entry {
        id,
        tags: vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: size },
        ],
    }
}
