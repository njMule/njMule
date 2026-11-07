// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Kad nodes on a simulated wire, for the Java engine's simulations: what one node sends reaches another at the next
//! delivery, a helper asked to check a node's TCP port connects back at once, and the clock moves only when told.

#![allow(dead_code, clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::{HashMap, VecDeque};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use njmule_engine::kad::kad_identity::KadIdentity;
use njmule_engine::kad::kad_node::{Comments, Host, KadNode, Settings, Transport};
use njmule_engine::kad::lookup;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::packet::Packet;
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};

pub const PORT: u16 = 4672;

/// What travels on the wire: a datagram, or a helper connecting back to a node that asked it to.
enum Delivery {
    Datagram { from: u32, to: u32, port: u16, packet: Packet, receiver_key: u32, sender_key: u32 },
    Reached { helper: u32, requester: u32 },
}

type Line = Arc<Mutex<VecDeque<Delivery>>>;

struct Sends {
    from: u32,
    line: Line,
}

impl Transport for Sends {
    fn send(&mut self, to: u32, port: u16, packet: &Packet, _: Option<UInt128>, receiver_key: u32, sender_key: u32) {
        let datagram =
            Delivery::Datagram { from: self.from, to, port, packet: packet.clone(), receiver_key, sender_key };
        self.line.lock().unwrap().push_back(datagram);
    }
}

struct ConnectsBack {
    from: u32,
    line: Line,
    checks: Arc<Mutex<usize>>,
}

impl Host for ConnectsBack {
    fn check_reachable(&mut self, to: u32, _: u16, _: [u8; 16], _: u8) {
        *self.checks.lock().unwrap() += 1;
        self.line.lock().unwrap().push_back(Delivery::Reached { helper: self.from, requester: to });
    }
}

/// Neither sends nor connects.
pub struct Nowhere;

impl Transport for Nowhere {
    fn send(&mut self, _: u32, _: u16, _: &Packet, _: Option<UInt128>, _: u32, _: u32) {}
}

impl Host for Nowhere {
    fn check_reachable(&mut self, _: u32, _: u16, _: [u8; 16], _: u8) {}
}

pub struct Wire {
    pub dir: tempfile::TempDir,
    // A fixed seed: the same identities and addresses at every run.
    pub random: StdRng,
    pub time: Arc<Mutex<Instant>>,
    pub nodes: Vec<KadNode>,
    pub ips: Vec<u32>,
    /// How many times a node asked another to check its TCP port.
    pub reachability_checks: Arc<Mutex<usize>>,
    by_ip: HashMap<u32, usize>,
    line: Line,
}

impl Wire {
    pub fn new(seed: u64) -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            random: StdRng::seed_from_u64(seed),
            time: Arc::new(Mutex::new(Instant::now())),
            nodes: Vec::new(),
            ips: Vec::new(),
            reachability_checks: Arc::default(),
            by_ip: HashMap::new(),
            line: Arc::default(),
        }
    }

    /// Adds a node at `ip` that tells `comments` as its notes.
    pub fn add_node(&mut self, ip: u32, comments: Comments) {
        let config = self.config(self.nodes.len());
        std::fs::create_dir_all(&config).unwrap();
        let user_hash: [u8; 16] = self.random.random();
        let identity = KadIdentity::load_or_create(&config, &mut self.random).unwrap();
        let transport = Sends { from: ip, line: Arc::clone(&self.line) };
        let host =
            ConnectsBack { from: ip, line: Arc::clone(&self.line), checks: Arc::clone(&self.reachability_checks) };
        let mut settings = Settings::new(4662, user_hash, 1, Arc::new(|_| false), Arc::new(|_| false));
        settings.comments = comments;
        let node = KadNode::new(identity, Box::new(transport), Box::new(host), settings, &config, self.clock());
        self.by_ip.insert(ip, self.nodes.len());
        self.nodes.push(node);
        self.ips.push(ip);
    }

    /// Each node but the first bootstraps from the first and from one before it; the first, which only seeded the
    /// others, from the second.
    pub fn bootstrap_from_each_other(&mut self) {
        for at in 1..self.nodes.len() {
            let first = self.ips[0];
            self.nodes[at].bootstrap(first, PORT);
            if at > 1 {
                let other = self.ips[self.random.random_range(0..at)];
                self.nodes[at].bootstrap(other, PORT);
            }
            self.deliver();
        }
        let second = self.ips[1];
        self.nodes[0].bootstrap(second, PORT);
    }

    /// The node that saved its files in the folder of node `at`, again, on no wire.
    pub fn restart(&mut self, at: usize) -> KadNode {
        let config = self.config(at);
        let identity = KadIdentity::load_or_create(&config, &mut self.random).unwrap();
        let settings = Settings::new(4662, [0; 16], 0, Arc::new(|_| false), Arc::new(|_| false));
        KadNode::new(identity, Box::new(Nowhere), Box::new(Nowhere), settings, &config, self.clock())
    }

    /// Whether some node is near enough to `target` to store what is published there.
    pub fn some_node_near(&self, target: UInt128, but: &[usize]) -> bool {
        self.nodes
            .iter()
            .enumerate()
            .any(|(at, node)| !but.contains(&at) && (node.id() ^ target).top32() <= lookup::TOLERANCE)
    }

    pub fn config(&self, at: usize) -> PathBuf {
        self.dir.path().join(format!("node{at}"))
    }

    /// Lets `seconds` pass, a second at a time: every node ticks, then what they sent arrives.
    pub fn run(&mut self, seconds: u64) {
        for _ in 0..seconds {
            *self.time.lock().unwrap() += Duration::from_secs(1);
            for node in &mut self.nodes {
                node.tick();
            }
            self.deliver();
        }
    }

    pub fn deliver(&mut self) {
        for _ in 0..2_000_000 {
            let Some(delivery) = self.line.lock().unwrap().pop_front() else {
                return;
            };
            match delivery {
                Delivery::Datagram { from, to, port, packet, receiver_key, sender_key } => {
                    if let Some(&at) = self.by_ip.get(&to)
                        && port == PORT
                    {
                        self.nodes[at].received(from, PORT, &packet, receiver_key, sender_key);
                    }
                }
                Delivery::Reached { helper, requester } => {
                    if let Some(&at) = self.by_ip.get(&requester) {
                        self.nodes[at].reachable_confirmed(helper);
                    }
                }
            }
        }
    }

    fn clock(&self) -> Box<dyn Fn() -> Instant + Send> {
        let clock = Arc::clone(&self.time);
        Box::new(move || *clock.lock().unwrap())
    }
}
