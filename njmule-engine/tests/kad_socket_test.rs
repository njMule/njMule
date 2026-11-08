// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadSocketTest, under its names: two Kad nodes on sockets of their own talk over the loopback,
//! directly or through the UDP relay of a SOCKS proxy, and a closed socket frees its port at once.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::arithmetic_side_effects)]

use crate::fake_socks5_proxy;

use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use fake_socks5_proxy::FakeSocks5Proxy;
use njmule_engine::kad::kad_identity::KadIdentity;
use njmule_engine::kad::kad_node::{Host, KadNode, Settings, Transport};
use njmule_engine::kad::kad_socket::{Incoming, KadSocket, Keys};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::net::socks5_udp::Socks5Udp;
use njmule_engine::net::udp_route::UdpRoute;
use njmule_engine::protocol::crypt::datagram_obfuscation;
use njmule_engine::protocol::kad::kad_messages;
use njmule_engine::protocol::packet::opcodes::client_udp;
use njmule_engine::protocol::packet::{Packet, packing, protocol, udp_framing};
use tokio::sync::mpsc;

// Enough rounds to meet the close race, which most rounds lose on Linux when the port is not waited for.
const ROUNDS: u32 = 20;
const LOOPBACK: u32 = u32::from_be_bytes([127, 0, 0, 1]);

type Heard = Arc<Mutex<Option<mpsc::UnboundedSender<String>>>>;

/// A host that is asked nothing here.
struct Quiet;

impl Host for Quiet {
    fn check_reachable(&mut self, _: u32, _: u16, _: [u8; 16], _: u8) {}
}

struct Peer {
    socket: KadSocket,
    node: Arc<Mutex<KadNode>>,
    heard: Heard,
}

impl Peer {
    fn port(&self) -> u16 {
        self.socket.port()
    }

    fn contacts(&self) -> usize {
        self.node.lock().unwrap().contacts()
    }
}

struct Sockets {
    dir: tempfile::TempDir,
    a: Option<Peer>,
    b: Option<Peer>,
    proxy: Option<FakeSocks5Proxy>,
    heard: Option<mpsc::UnboundedReceiver<String>>,
    busy: u32,
}

impl Sockets {
    fn new() -> Self {
        Self { dir: tempfile::tempdir().unwrap(), a: None, b: None, proxy: None, heard: None, busy: 0 }
    }

    fn given_an_ipv6_loopback(&self) -> bool {
        let found = std::net::UdpSocket::bind((Ipv6Addr::LOCALHOST, 0)).is_ok();
        if !found {
            eprintln!("No IPv6 loopback here: skipped");
        }
        found
    }

    fn given_two_nodes_on_their_own_sockets(&mut self) {
        self.a = Some(peer(&self.dir.path().join("a")));
        self.b = Some(peer(&self.dir.path().join("b")));
    }

    fn given_the_second_socket_hears_client_packets(&mut self) {
        let (sink, heard) = mpsc::unbounded_channel();
        *self.b().heard.lock().unwrap() = Some(sink);
        self.heard = Some(heard);
    }

    async fn given_the_first_socket_goes_through_the_relay_of_a_proxy(&mut self) {
        let proxy = FakeSocks5Proxy::start();
        let association = Socks5Udp::associate(&proxy.settings(), None, Duration::from_secs(5)).await.unwrap();
        self.a().socket.route(UdpRoute::Through(Arc::new(association)));
        self.proxy = Some(proxy);
    }

    fn when_the_first_node_bootstraps_from_the_second(&self) {
        let port = self.b().port();
        self.a().node.lock().unwrap().bootstrap(LOOPBACK, port);
    }

    fn when_the_second_node_bootstraps_from_the_first_socket_directly(&self) {
        let port = self.a().port();
        self.b().node.lock().unwrap().bootstrap(LOOPBACK, port);
    }

    fn when_the_first_socket_sends_a_client_packet_to_the_second_over_ipv6(&self) {
        let to = SocketAddr::from((Ipv6Addr::LOCALHOST, self.b().port()));
        self.a().socket.send_client_to(to, &Packet::of(protocol::EMULE, client_udp::REASKFILEPING, vec![0; 16]));
    }

    // Each socket receives (its reader waits on it) when it closes, as a running Kad does.
    async fn when_sockets_are_closed_and_their_ports_bound_again(&mut self, rounds: u32) {
        for _ in 0..rounds {
            let (socket, _incoming, _) = socket(&self.dir.path().join("reuse"));
            let port = socket.port();
            tokio::time::sleep(Duration::from_millis(5)).await;
            socket.close().await;
            if std::net::UdpSocket::bind((Ipv4Addr::UNSPECIFIED, port)).is_err() {
                self.busy += 1;
            }
        }
    }

    async fn then_the_first_node_learns_a_contact_within_seconds(&self, seconds: u64) {
        let deadline = Instant::now() + Duration::from_secs(seconds);
        while self.a().contacts() == 0 {
            assert!(Instant::now() < deadline, "no contact within {seconds} s");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    // The answer came back obfuscated with the key A gave B: B is a verified contact.
    fn then_the_second_node_is_a_verified_contact_of_the_first(&self) {
        assert_eq!(self.a().node.lock().unwrap().verified_contacts(), 1);
    }

    fn then_the_first_node_is_connected(&self) {
        assert!(self.a().node.lock().unwrap().is_connected());
    }

    fn then_the_second_node_adds_nobody(&self) {
        assert_eq!(self.b().contacts(), 0, "a bootstrap request adds nobody");
    }

    fn then_the_proxy_relayed_to_the_second_socket(&self) {
        let relayed = format!("relay to 127.0.0.1:{}", self.b().port());
        assert!(self.proxy.as_ref().unwrap().seen().contains(&relayed));
    }

    // Had the first socket answered, the second node would have learnt it as a contact.
    async fn then_the_first_node_heard_nothing_within_seconds(&self, seconds: u64) {
        tokio::time::sleep(Duration::from_secs(seconds)).await;
        assert_eq!(self.b().contacts(), 0);
        assert!(!self.proxy.as_ref().unwrap().seen().iter().any(|seen| seen.starts_with("relay to")));
    }

    fn then_the_port_was_busy_times(&self, times: u32) {
        assert_eq!(self.busy, times);
    }

    async fn then_the_second_socket_hears_within_seconds(&mut self, seconds: u64, expected: String) {
        let heard = self.heard.as_mut().unwrap();
        let first = tokio::time::timeout(Duration::from_secs(seconds), heard.recv()).await.ok().flatten();
        assert_eq!(first, Some(expected));
    }

    fn a(&self) -> &Peer {
        self.a.as_ref().unwrap()
    }

    fn b(&self) -> &Peer {
        self.b.as_ref().unwrap()
    }
}

fn socket(config: &Path) -> (KadSocket, mpsc::UnboundedReceiver<Incoming>, KadIdentity) {
    std::fs::create_dir_all(config).unwrap();
    let identity = KadIdentity::load_or_create(config, &mut rand::rng()).unwrap();
    let keys = Keys { user_hash: [0; 16], identity, blocked: Arc::new(|_| false) };
    let (receiver, incoming) = mpsc::unbounded_channel();
    (KadSocket::open(None, 0, keys, receiver).unwrap(), incoming, identity)
}

// The node gets what its socket receives, as the engine's Kad task does.
fn peer(config: &Path) -> Peer {
    let (socket, mut incoming, identity) = socket(config);
    let settings = Settings::new(4662, [0; 16], 1, Arc::new(|_| true), Arc::new(|_| false));
    let node =
        KadNode::new(identity, Box::new(socket.transport()), Box::new(Quiet), settings, config, Box::new(Instant::now));
    let node = Arc::new(Mutex::new(node));
    let heard: Heard = Arc::default();
    let (fed, told) = (Arc::clone(&node), Arc::clone(&heard));
    tokio::spawn(async move {
        while let Some(packet) = incoming.recv().await {
            let line = match packet {
                Incoming::Kad { ip, port, packet, receiver_key, sender_key } => {
                    fed.lock().unwrap().received(ip, port, &packet, receiver_key, sender_key);
                    continue;
                }
                Incoming::Client { port, .. } => format!("IPv4 {port}"),
                Incoming::ClientOverIpv6 { from, packet } => format!("{from} 0x{:02X}", packet.opcode()),
            };
            if let Some(sink) = told.lock().unwrap().as_ref() {
                let _ = sink.send(line);
            }
        }
    });
    Peer { socket, node, heard }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn should_let_two_nodes_talk_over_obfuscated_datagrams() {
    let mut sockets = Sockets::new();
    sockets.given_two_nodes_on_their_own_sockets();

    sockets.when_the_first_node_bootstraps_from_the_second();

    sockets.then_the_first_node_learns_a_contact_within_seconds(20).await;
    sockets.then_the_second_node_is_a_verified_contact_of_the_first();
    sockets.then_the_first_node_is_connected();
    sockets.then_the_second_node_adds_nobody();
}

// The other node sees the relay, answers it, and the first node learns the other's own address.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn should_let_a_node_talk_through_the_udp_relay_of_a_socks_proxy() {
    let mut sockets = Sockets::new();
    sockets.given_two_nodes_on_their_own_sockets();
    sockets.given_the_first_socket_goes_through_the_relay_of_a_proxy().await;

    sockets.when_the_first_node_bootstraps_from_the_second();

    sockets.then_the_first_node_learns_a_contact_within_seconds(20).await;
    sockets.then_the_second_node_is_a_verified_contact_of_the_first();
    sockets.then_the_proxy_relayed_to_the_second_socket();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn should_drop_a_datagram_that_came_around_the_relay() {
    let mut sockets = Sockets::new();
    sockets.given_two_nodes_on_their_own_sockets();
    sockets.given_the_first_socket_goes_through_the_relay_of_a_proxy().await;

    sockets.when_the_second_node_bootstraps_from_the_first_socket_directly();

    sockets.then_the_first_node_heard_nothing_within_seconds(2).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn should_pass_on_a_client_packet_that_came_plain_from_an_ipv6_address() {
    let mut sockets = Sockets::new();
    if !sockets.given_an_ipv6_loopback() {
        return;
    }
    sockets.given_two_nodes_on_their_own_sockets();
    sockets.given_the_second_socket_hears_client_packets();

    sockets.when_the_first_socket_sends_a_client_packet_to_the_second_over_ipv6();

    let expected = format!("[::1]:{} 0x90", sockets.a().port());
    sockets.then_the_second_socket_hears_within_seconds(5, expected).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn should_leave_the_port_of_a_closed_socket_free_at_once() {
    let mut sockets = Sockets::new();

    sockets.when_sockets_are_closed_and_their_ports_bound_again(ROUNDS).await;

    sockets.then_the_port_was_busy_times(0);
}

// Rust's own: what the Java engine's tests reach through the engine.

const THEIR_USER_HASH: [u8; 16] = [0x5A; 16];
const QUIET: Duration = Duration::from_millis(300);

/// A socket alone, the plain socket it talks to, and what each received.
struct Alone {
    socket: KadSocket,
    incoming: mpsc::UnboundedReceiver<Incoming>,
    peer: tokio::net::UdpSocket,
    peer_id: UInt128,
    received: Option<Vec<u8>>,
}

impl Alone {
    async fn new(local: Option<Ipv4Addr>, blocked: bool) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let identity = KadIdentity::load_or_create(dir.path(), &mut rand::rng()).unwrap();
        let keys = Keys { user_hash: [0; 16], identity, blocked: Arc::new(move |_| blocked) };
        let (receiver, incoming) = mpsc::unbounded_channel();
        let socket = KadSocket::open(local, 0, keys, receiver).unwrap();
        let peer = tokio::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        Self { socket, incoming, peer, peer_id: UInt128::random(&mut rand::rng()), received: None }
    }

    fn peer_port(&self) -> u16 {
        self.peer.local_addr().unwrap().port()
    }

    fn given_the_socket_is_held(&self) {
        (self.socket.router())(UdpRoute::Held);
    }

    fn when_a_client_packet_is_sent(&self, user_hash: Option<[u8; 16]>) {
        let packet = Packet::of(protocol::EMULE, client_udp::REASKFILEPING, vec![1; 16]);
        self.socket.send_client(LOOPBACK, self.peer_port(), &packet, user_hash, LOOPBACK);
    }

    fn when_a_large_kad_packet_is_sent_for_the_peer(&self) {
        let mut transport = self.socket.transport();
        transport.send(LOOPBACK, self.peer_port(), &large_kad_packet(), Some(self.peer_id), 0, 0);
    }

    async fn when_the_peer_sends(&self, packets: &[Packet]) {
        let to = SocketAddr::from((Ipv4Addr::LOCALHOST, self.socket.port()));
        for packet in packets {
            self.peer.send_to(&udp_framing::encode(packet), to).await.unwrap();
        }
    }

    async fn then_the_peer_receives(&mut self) {
        let mut buffer = vec![0; 8 * 1024];
        let received = tokio::time::timeout(Duration::from_secs(5), self.peer.recv_from(&mut buffer)).await;
        let (length, _) = received.expect("a datagram").unwrap();
        buffer.truncate(length);
        self.received = Some(buffer);
    }

    async fn then_the_peer_receives_nothing(&self) {
        let mut buffer = vec![0; 8 * 1024];
        assert!(tokio::time::timeout(QUIET, self.peer.recv_from(&mut buffer)).await.is_err());
    }

    fn then_it_opens_with_the_peers_user_hash_as(&self, opcode: u8, kind: datagram_obfuscation::Kind) {
        let data = self.received.as_ref().unwrap();
        let opened = datagram_obfuscation::decode_from_client(data, &THEIR_USER_HASH, [127, 0, 0, 1], None, 0).unwrap();
        assert_eq!((opened.packet.protocol(), opened.packet.opcode(), opened.kind), (protocol::EMULE, opcode, kind));
    }

    fn then_it_opens_with_the_peers_kad_id_as_the_large_packet(&self) {
        let data = self.received.as_ref().unwrap();
        let id = self.peer_id.to_wire_bytes();
        let opened = datagram_obfuscation::decode_from_client(data, &[0; 16], [127, 0, 0, 1], Some(&id), 0).unwrap();
        assert_eq!(opened.kind, datagram_obfuscation::Kind::KadIdKey);
        assert_eq!(opened.packet.protocol(), protocol::KAD_PACKED);
        assert_eq!(packing::unpack(opened.packet, 250_000).unwrap(), large_kad_packet());
    }

    async fn then_the_socket_hears(&mut self, expected: &[(u8, u8)]) {
        let mut heard = Vec::new();
        while heard.len() < expected.len() {
            let incoming = tokio::time::timeout(Duration::from_secs(5), self.incoming.recv()).await;
            heard.push(match incoming.expect("a packet").unwrap() {
                Incoming::Kad { packet, .. } | Incoming::Client { packet, .. } => (packet.protocol(), packet.opcode()),
                Incoming::ClientOverIpv6 { packet, .. } => (0, packet.opcode()),
            });
        }
        assert_eq!(heard, expected);
    }

    async fn then_the_socket_hears_nothing(&mut self) {
        assert!(tokio::time::timeout(QUIET, self.incoming.recv()).await.is_err());
    }
}

fn large_kad_packet() -> Packet {
    Packet::of(protocol::KAD, 0x33, vec![7; 400])
}

#[tokio::test]
async fn should_send_a_client_packet_obfuscated_for_its_receiver() {
    let mut alone = Alone::new(None, false).await;

    alone.when_a_client_packet_is_sent(Some(THEIR_USER_HASH));

    alone.then_the_peer_receives().await;
    alone.then_it_opens_with_the_peers_user_hash_as(client_udp::REASKFILEPING, datagram_obfuscation::Kind::Ed2k);
}

#[tokio::test]
async fn should_send_a_client_packet_plain_without_the_receivers_user_hash() {
    let mut alone = Alone::new(None, false).await;

    alone.when_a_client_packet_is_sent(None);

    alone.then_the_peer_receives().await;
    alone.then_it_opens_with_the_peers_user_hash_as(client_udp::REASKFILEPING, datagram_obfuscation::Kind::Plain);
}

#[tokio::test]
async fn should_send_a_large_kad_packet_packed_and_obfuscated_with_the_receivers_id() {
    let mut alone = Alone::new(None, false).await;

    alone.when_a_large_kad_packet_is_sent_for_the_peer();

    alone.then_the_peer_receives().await;
    alone.then_it_opens_with_the_peers_kad_id_as_the_large_packet();
}

#[tokio::test]
async fn should_send_nothing_on_a_held_route() {
    let alone = Alone::new(None, false).await;
    alone.given_the_socket_is_held();

    alone.when_a_client_packet_is_sent(None);

    alone.then_the_peer_receives_nothing().await;
}

#[tokio::test]
async fn should_pass_on_packed_kad_packets_and_client_packets_and_drop_others() {
    let mut alone = Alone::new(Some(Ipv4Addr::LOCALHOST), false).await;

    alone
        .when_the_peer_sends(&[
            packing::pack(large_kad_packet()).unwrap(),
            Packet::of(protocol::EDONKEY, 0x96, vec![0; 4]),
            Packet::of(protocol::EMULE, client_udp::REASKFILEPING, vec![1; 16]),
            kad_messages::ping(),
        ])
        .await;

    alone
        .then_the_socket_hears(&[
            (protocol::KAD, 0x33),
            (protocol::EMULE, client_udp::REASKFILEPING),
            (protocol::KAD, 0x60),
        ])
        .await;
}

#[tokio::test]
async fn should_drop_the_datagrams_of_a_blocked_address_unread() {
    let mut alone = Alone::new(None, true).await;

    alone.when_the_peer_sends(&[kad_messages::ping()]).await;

    alone.then_the_socket_hears_nothing().await;
}

fn when_a_socket_opens_on_the_port_of(other_program: &std::net::UdpSocket) -> std::io::Result<KadSocket> {
    let dir = tempfile::tempdir().unwrap();
    let identity = KadIdentity::load_or_create(dir.path(), &mut rand::rng()).unwrap();
    let keys = Keys { user_hash: [0; 16], identity, blocked: Arc::new(|_| false) };
    let port = other_program.local_addr().unwrap().port();
    KadSocket::open(None, port, keys, mpsc::unbounded_channel().0)
}

fn then_the_port_is_taken(opened: std::io::Result<KadSocket>) {
    assert_eq!(opened.err().map(|e| e.kind()), Some(std::io::ErrorKind::AddrInUse));
}

// Windows lets an IPv6 socket take a port another program holds for IPv4 alone.
#[tokio::test]
async fn should_not_take_a_port_another_program_holds_for_ipv4() {
    let other_program = std::net::UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).unwrap();

    let opened = when_a_socket_opens_on_the_port_of(&other_program);

    then_the_port_is_taken(opened);
}
