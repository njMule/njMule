// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's NatTraversalTest, under its names: two firewalled clients that meet through the target's buddy.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::{HashMap, HashSet};
use std::io;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use njmule_engine::peer::nat_traversal::{NatTraversal, Opened, Request, RoutedConnection};
use njmule_engine::protocol::ed2k::nat_traversal_messages::{
    Caps, Forward, OPTION_QUIC, OPTION_UTP, Rendezvous, TRANSPORT_QUIC, TRANSPORT_UTP,
};
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::opcodes::{client_udp, extended};
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::util::ipv4;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

const REQUESTER_HASH: [u8; 16] = [0xAA; 16];
const TARGET_HASH: [u8; 16] = [0xBB; 16];
const BUDDY_ID: [u8; 16] = [0xCC; 16];
const FILE: [u8; 16] = [0xDD; 16];

#[tokio::test]
async fn should_open_a_utp_connection_through_both_routers_by_a_rendezvous_through_the_targets_buddy() {
    let mut test = Routers::new();
    test.given_two_firewalled_clients();

    test.when_the_requester_asks_for_a_rendezvous(false);

    test.then_the_requester_gets_a_connection(false).await;
    test.then_the_rendezvous_asked_for(TRANSPORT_UTP);
    test.then_the_target_says_hello_first_and_the_requester_waits().await;
    test.then_bytes_cross_the_connection().await;
}

#[tokio::test]
async fn should_open_a_quic_connection_through_both_routers_when_the_target_has_quic() {
    let mut test = Routers::new();
    test.given_two_firewalled_clients();

    test.when_the_requester_asks_for_a_rendezvous(true);

    test.then_the_requester_gets_a_connection(true).await;
    test.then_the_rendezvous_asked_for(TRANSPORT_QUIC);
    test.then_the_target_says_hello_first_and_the_requester_waits().await;
    test.then_bytes_cross_the_connection().await;
}

#[tokio::test]
async fn should_open_no_connection_when_the_target_does_not_punch_its_holes() {
    let mut test = Routers::new();
    test.given_two_firewalled_clients();
    test.given_the_target_ignores_rendezvous();

    test.when_the_requester_asks_for_a_rendezvous(false);

    test.then_nothing_opens_within_seconds(3).await;
}

// The capability frames and rendezvous NatTraversalTest leaves to the engine's flows.
#[tokio::test]
async fn should_answer_the_capabilities_of_a_host_it_waits_for() {
    let test = Routers::new();
    test.given_two_firewalled_clients();
    test.given_the_target_heard_a_rendezvous_with(OPTION_UTP);

    test.when_the_requester_tells_its_capabilities([0; 16]).await;

    test.then_the_target_answered_with_its_capabilities();
}

#[tokio::test]
async fn should_ignore_the_capabilities_of_a_host_it_does_not_wait_for() {
    let test = Routers::new();
    test.given_two_firewalled_clients();

    test.when_the_requester_tells_its_capabilities([0; 16]).await;

    test.then_the_target_sent_nothing();
}

#[tokio::test]
async fn should_wait_for_nobody_after_a_rendezvous_without_utp_or_quic() {
    let test = Routers::new();
    test.given_two_firewalled_clients();

    test.when_the_target_hears_a_rendezvous_with(0);

    test.then_the_target_sent_nothing();
}

/// Both clients behind routers that let in only what comes from where they sent to; the buddy is reachable and passes
/// a rendezvous on.
struct Routers {
    wire: Arc<Wire>,
    opened: mpsc::UnboundedReceiver<(SocketAddr, RoutedConnection, bool)>,
    connections: mpsc::UnboundedSender<(SocketAddr, RoutedConnection, bool)>,
    asked: Option<JoinHandle<io::Result<Opened>>>,
    requester_side: Option<RoutedConnection>,
    target_side: Option<RoutedConnection>,
}

#[derive(Default)]
struct Wire {
    sides: Mutex<HashMap<SocketAddr, Arc<NatTraversal>>>,
    // What each side's router has sent to, by the side's address.
    sent_to: Mutex<HashMap<SocketAddr, HashSet<SocketAddr>>>,
    target_ignores: Mutex<bool>,
    relayed: Mutex<Option<Rendezvous>>,
    // Every datagram, by sender.
    sent: Mutex<Vec<(SocketAddr, Packet)>>,
}

impl Routers {
    fn new() -> Self {
        let (connections, opened) = mpsc::unbounded_channel();
        Self { wire: Arc::default(), opened, connections, asked: None, requester_side: None, target_side: None }
    }

    fn given_two_firewalled_clients(&self) {
        self.side(requester(), REQUESTER_HASH);
        self.side(target(), TARGET_HASH);
    }

    fn given_the_target_ignores_rendezvous(&self) {
        *self.wire.target_ignores.lock().unwrap() = true;
    }

    fn given_the_target_heard_a_rendezvous_with(&self, options: u8) {
        self.when_the_target_hears_a_rendezvous_with(options);
    }

    fn when_the_target_hears_a_rendezvous_with(&self, options: u8) {
        let rendezvous = Rendezvous {
            requester: REQUESTER_HASH,
            options,
            file: FILE,
            requester_ip: 0,
            requester_port: 0,
            transport: TRANSPORT_UTP,
        };
        self.wire.side(target()).unwrap().rendezvous_forwarded(requester(), &rendezvous);
    }

    async fn when_the_requester_tells_its_capabilities(&self, expected: [u8; 16]) {
        let caps = Caps { answer: false, options: OPTION_UTP, sender: REQUESTER_HASH, expected, file: FILE };
        self.wire.sent.lock().unwrap().clear();
        self.wire.side(target()).unwrap().frame(requester(), &caps.encode());
        // What the target sends goes out at once; the wire carries it a little later.
        tokio::time::sleep(Duration::from_millis(50)).await;
    }

    fn when_the_requester_asks_for_a_rendezvous(&mut self, target_has_quic: bool) {
        let requester_side = self.wire.side(requester()).unwrap();
        let asking = requester_side.rendezvous(Request {
            target: target(),
            target_hash: TARGET_HASH,
            buddy: buddy(),
            buddy_id: BUDDY_ID,
            file: FILE,
            our_ip: u32::from(ipv4_of(requester())),
            our_port: requester().port(),
            has_quic: target_has_quic,
        });
        self.asked = Some(tokio::spawn(asking));
    }

    async fn then_the_requester_gets_a_connection(&mut self, over_quic: bool) {
        let asked = self.asked.take().unwrap();
        let opened = tokio::time::timeout(Duration::from_secs(30), asked).await.unwrap().unwrap().unwrap();
        assert_eq!(opened.over_quic, over_quic);
        assert_eq!(opened.remote.ip(), target().ip());
    }

    fn then_the_rendezvous_asked_for(&self, transport: u8) {
        let relayed = self.wire.relayed.lock().unwrap().unwrap();
        assert_eq!(relayed.transport, transport);
        assert_ne!(relayed.options & OPTION_UTP, 0);
        assert_ne!(relayed.options & OPTION_QUIC, 0);
    }

    async fn then_the_target_says_hello_first_and_the_requester_waits(&mut self) {
        let mut told = HashSet::new();
        for _ in 0..2 {
            let (side, connection, hello_first) =
                tokio::time::timeout(Duration::from_secs(10), self.opened.recv()).await.unwrap().unwrap();
            told.insert(format!("{side} {}", if hello_first { "speaks first" } else { "waits" }));
            if side == requester() {
                self.requester_side = Some(connection);
            } else {
                self.target_side = Some(connection);
            }
        }
        let expected: HashSet<String> =
            [format!("{} waits", requester()), format!("{} speaks first", target())].into_iter().collect();
        assert_eq!(told, expected);
    }

    async fn then_bytes_cross_the_connection(&mut self) {
        let requester_side = self.requester_side.as_mut().unwrap();
        requester_side.write_all(b"file request").await.unwrap();
        requester_side.flush().await.unwrap();
        let mut read = [0; 12];
        let target_side = self.target_side.as_mut().unwrap();
        tokio::time::timeout(Duration::from_secs(10), target_side.read_exact(&mut read)).await.unwrap().unwrap();
        assert_eq!(&read, b"file request");
    }

    fn then_the_target_answered_with_its_capabilities(&self) {
        let sent = self.wire.sent.lock().unwrap();
        let answers: Vec<Caps> = sent
            .iter()
            .filter(|(from, packet)| *from == target() && packet.protocol() == protocol::UDP_RESERVED_2)
            .map(|(_, packet)| Caps::decode(packet).unwrap())
            .collect();
        let expected = Caps {
            answer: true,
            options: OPTION_UTP | OPTION_QUIC,
            sender: TARGET_HASH,
            expected: REQUESTER_HASH,
            file: FILE,
        };
        assert_eq!(answers, vec![expected]);
    }

    fn then_the_target_sent_nothing(&self) {
        assert!(self.wire.sent.lock().unwrap().iter().all(|(from, _)| *from != target()));
    }

    async fn then_nothing_opens_within_seconds(&mut self, seconds: u64) {
        let opened = tokio::time::timeout(Duration::from_secs(seconds), self.opened.recv()).await;
        assert!(opened.is_err(), "a connection opened");
        assert!(!self.asked.as_ref().unwrap().is_finished());
    }

    fn side(&self, at: SocketAddr, hash: [u8; 16]) {
        self.wire.sent_to.lock().unwrap().insert(at, HashSet::new());
        let wire = Arc::downgrade(&self.wire);
        let connections = self.connections.clone();
        let side = NatTraversal::new(
            Arc::new(move |to, packet: &Packet| {
                if let Some(wire) = wire.upgrade() {
                    wire.send(at, to, packet.clone());
                }
            }),
            Arc::new(move |connection, hello_first| {
                let _ = connections.send((at, connection, hello_first));
            }),
            Arc::new(move || hash),
            Arc::new(|| 0),
            Arc::new(|| false),
        )
        .unwrap();
        self.wire.sides.lock().unwrap().insert(at, Arc::new(side));
    }
}

impl Drop for Routers {
    fn drop(&mut self) {
        let sides: Vec<Arc<NatTraversal>> = self.wire.sides.lock().unwrap().drain().map(|(_, side)| side).collect();
        drop(sides);
    }
}

impl Wire {
    fn side(&self, at: SocketAddr) -> Option<Arc<NatTraversal>> {
        self.sides.lock().unwrap().get(&at).cloned()
    }

    // The datagram leaves the sender's router, which lets answers from there in from now on.
    fn send(self: Arc<Self>, from: SocketAddr, to: SocketAddr, packet: Packet) {
        self.sent_to.lock().unwrap().entry(from).or_default().insert(to);
        self.sent.lock().unwrap().push((from, packet.clone()));
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(3)).await;
            self.deliver(from, to, &packet);
        });
    }

    // It reaches the other side if that side's router let it in.
    fn deliver(&self, from: SocketAddr, to: SocketAddr, packet: &Packet) {
        if to == buddy() {
            self.relay(from, packet);
            return;
        }
        let Some(side) = self.side(to) else {
            return;
        };
        if !self.sent_to.lock().unwrap().get(&to).is_some_and(|sent| sent.contains(&from)) {
            return;
        }
        if packet.protocol() == protocol::UDP_RESERVED_2 {
            side.frame(from, packet);
        } else if packet.protocol() == protocol::EMULE && packet.opcode() == client_udp::HOLEPUNCH {
            side.hole_punched(from);
        }
    }

    // As a buddy does: the re-ask through it goes on over TCP with the address it came from, the buddy id left out.
    fn relay(&self, from: SocketAddr, packet: &Packet) {
        if packet.opcode() != client_udp::REASKCALLBACKUDP || *self.target_ignores.lock().unwrap() {
            return;
        }
        let mut input = packet.reader();
        assert_eq!(input.hash16().unwrap(), BUDDY_ID);
        let mut out = PacketWriter::new();
        out.u32(ipv4::to_ed2k(u32::from(ipv4_of(from)))).u16(from.port()).bytes(input.rest());
        let forward = Packet::written(protocol::EMULE, extended::REASKCALLBACKTCP, out);
        let passed = Forward::decode(&forward).unwrap().unwrap();
        assert_eq!(passed.opcode, client_udp::RENDEZVOUS);
        let relayed = Rendezvous::decode(&passed.payload).unwrap();
        *self.relayed.lock().unwrap() = Some(relayed);
        self.side(target()).unwrap().rendezvous_forwarded(passed.requester, &relayed);
    }
}

fn ipv4_of(at: SocketAddr) -> std::net::Ipv4Addr {
    match at.ip() {
        std::net::IpAddr::V4(ip) => ip,
        std::net::IpAddr::V6(_) => unreachable!("the test's addresses are IPv4"),
    }
}

fn requester() -> SocketAddr {
    "198.51.100.10:4672".parse().unwrap()
}

fn target() -> SocketAddr {
    "203.0.113.20:4682".parse().unwrap()
}

fn buddy() -> SocketAddr {
    "192.0.2.30:4692".parse().unwrap()
}
