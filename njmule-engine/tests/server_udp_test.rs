// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ServerUdpTest, under its names: the socket for server queries, straight, through the relay of a
//! SOCKS5 proxy, or held.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

mod fake_socks5_proxy;

use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::Arc;
use std::time::Duration;

use fake_socks5_proxy::FakeSocks5Proxy;
use njmule_engine::net::socks5_udp::Socks5Udp;
use njmule_engine::net::udp_route::UdpRoute;
use njmule_engine::protocol::packet::{Packet, protocol, udp_framing};
use njmule_engine::server::server_udp::ServerUdp;
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
