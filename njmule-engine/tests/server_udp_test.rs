// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ServerUdpTest, under its names: the socket for server queries, straight, through the relay of a
//! SOCKS5 proxy, or held.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use crate::fake_socks5_proxy;

use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::Arc;
use std::time::Duration;

use fake_socks5_proxy::FakeSocks5Proxy;
use njmule_engine::net::relay_keeper::RelayKeeper;
use njmule_engine::net::socks5_udp::Socks5Udp;
use njmule_engine::net::udp_route::UdpRoute;
use njmule_engine::protocol::crypt::datagram_obfuscation;
use njmule_engine::protocol::packet::{Packet, protocol, udp_framing};
use njmule_engine::server::server_route::ServerRoute;
use njmule_engine::server::server_udp::ServerUdp;
use rand::SeedableRng;
use tokio::sync::mpsc;

// Enough rounds to meet the close race, which most rounds lose on Linux when the port is not waited for.
const ROUNDS: usize = 20;
const OPCODE: u8 = 0x96;
const WAIT: Duration = Duration::from_secs(5);

/// A server on the loopback that echoes what it gets to whoever sent it.
struct EchoServer {
    socket: UdpSocket,
}

impl EchoServer {
    fn start() -> Self {
        let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        Self { socket }
    }

    fn address(&self) -> SocketAddr {
        self.socket.local_addr().unwrap()
    }

    // Who asked, once the query came; none when nothing came in time.
    async fn asked(self) -> Option<SocketAddr> {
        tokio::task::spawn_blocking(move || {
            let mut buffer = [0; 1024];
            let (_, asked_by) = self.socket.recv_from(&mut buffer).ok()?;
            let answer = udp_framing::encode(&Packet::of(protocol::EDONKEY, OPCODE + 1, vec![0; 4]));
            self.socket.send_to(&answer, asked_by).unwrap();
            Some(asked_by)
        })
        .await
        .unwrap()
    }
}

fn query() -> Packet {
    Packet::of(protocol::EDONKEY, OPCODE, vec![0; 4])
}

#[tokio::test]
async fn should_leave_the_port_of_a_closed_socket_free_at_once() {
    let mut busy = 0;
    for _ in 0..ROUNDS {
        let (receiver, _received) = mpsc::unbounded_channel();
        let udp = ServerUdp::open(0, receiver).await.unwrap();
        let port = udp.port();
        tokio::time::sleep(Duration::from_millis(5)).await;

        udp.close().await;

        if UdpSocket::bind((Ipv4Addr::UNSPECIFIED, port)).is_err() {
            busy += 1;
        }
    }

    assert_eq!(busy, 0);
}

#[tokio::test]
async fn should_ask_a_server_through_the_udp_relay_of_a_socks_proxy() {
    let server = EchoServer::start();
    let to = server.address();
    let (receiver, mut received) = mpsc::unbounded_channel();
    let udp = ServerUdp::open(0, receiver).await.unwrap();
    let proxy = FakeSocks5Proxy::start();
    let association = Arc::new(Socks5Udp::associate(&proxy.settings(), None, WAIT).await.unwrap());
    udp.route(UdpRoute::Through(Arc::clone(&association)));

    udp.send(to, &query()).unwrap();
    let asked_by = server.asked().await;

    assert_eq!(asked_by, Some(association.relay()), "the server was asked by the relay");
    let (from, answer) = tokio::time::timeout(WAIT, received.recv()).await.unwrap().unwrap();
    assert_eq!(from, to, "the answer came from the server");
    assert_eq!(answer.opcode(), OPCODE + 1);
}

#[tokio::test]
async fn should_send_nothing_while_the_route_is_held() {
    let server = EchoServer::start();
    let (receiver, _received) = mpsc::unbounded_channel();
    let udp = ServerUdp::open(0, receiver).await.unwrap();
    udp.route(UdpRoute::Held);

    udp.send(server.address(), &query()).unwrap();

    assert_eq!(server.asked().await, None, "the server heard nothing");
}

// Beyond the Java engine's examples: the branches its other tests reach.

#[tokio::test]
async fn should_ask_a_server_obfuscated_with_its_key_and_read_its_obfuscated_answer() {
    const KEY: u32 = 0x5EC2_E701;
    let server = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    server.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let to = server.local_addr().unwrap();
    let (receiver, mut received) = mpsc::unbounded_channel();
    let udp = ServerUdp::open(0, receiver).await.unwrap();
    udp.decode_with(move |from| if from == to { KEY } else { 0 });
    let mut random = rand::rngs::StdRng::from_rng(&mut rand::rng());

    udp.send_routed(&ServerRoute { target: to, key: KEY }, &query(), &mut random).unwrap();

    let (asked, from) = tokio::task::spawn_blocking(move || {
        let mut buffer = [0; 1024];
        let (length, from) = server.recv_from(&mut buffer).unwrap();
        let asked = datagram_obfuscation::decode_at_server(&buffer[..length], KEY).unwrap().packet;
        let mut random = rand::rngs::StdRng::from_rng(&mut rand::rng());
        let answer = udp_framing::encode(&Packet::of(protocol::EDONKEY, OPCODE + 1, vec![0; 4]));
        server.send_to(&datagram_obfuscation::encrypt_from_server(&answer, KEY, &mut random), from).unwrap();
        (asked, from)
    })
    .await
    .unwrap();
    assert_eq!(asked.opcode(), OPCODE);
    assert_eq!(from.port(), udp.port());
    let (_, answer) = tokio::time::timeout(WAIT, received.recv()).await.unwrap().unwrap();
    assert_eq!(answer.opcode(), OPCODE + 1);
}

#[tokio::test]
async fn should_drop_datagrams_that_are_no_edonkey_packets() {
    let sender = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let (receiver, mut received) = mpsc::unbounded_channel();
    let udp = ServerUdp::open(0, receiver).await.unwrap();
    let to = SocketAddr::from((Ipv4Addr::LOCALHOST, udp.port()));

    sender.send_to(&[0xE3], to).unwrap();
    sender.send_to(&udp_framing::encode(&Packet::of(protocol::KAD, 0x01, vec![0; 4])), to).unwrap();
    sender.send_to(&udp_framing::encode(&query()), to).unwrap();

    let (_, packet) = tokio::time::timeout(WAIT, received.recv()).await.unwrap().unwrap();
    assert_eq!((packet.protocol(), packet.opcode()), (protocol::EDONKEY, OPCODE), "only the eDonkey packet");
}

#[tokio::test]
async fn should_go_through_the_relay_a_keeper_keeps_it_on() {
    let proxy = FakeSocks5Proxy::start();
    let (receiver, _received) = mpsc::unbounded_channel();
    let udp = ServerUdp::open(0, receiver).await.unwrap();
    let _keeper = RelayKeeper::start("Server UDP", udp.router(), proxy.settings(), None, |_, _| {});
    let server = EchoServer::start();
    let to = server.address();
    // Held until the proxy grants the association.
    let deadline = tokio::time::Instant::now() + WAIT;
    while !proxy.seen().contains(&"associate".to_owned()) {
        assert!(tokio::time::Instant::now() < deadline, "the proxy was asked");
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    tokio::time::sleep(Duration::from_millis(100)).await;

    udp.send(to, &query()).unwrap();

    let asked_by = server.asked().await.unwrap();
    assert!(asked_by.ip().is_loopback());
    assert_ne!(asked_by.port(), udp.port(), "asked by the relay, not by the socket");
}
