// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's StreamTransportTest, under its names: a connection over a stream that is no TCP socket.

#![allow(clippy::unwrap_used)]

use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Mutex;
use std::time::Duration;

use njmule_engine::net::tcp_connection::{Handler, TcpConnection};
use njmule_engine::protocol::packet::{Packet, frame_limits, protocol};
use tokio::sync::mpsc;

/// Hands on what it hears, and nothing else.
struct Hearing(Mutex<Option<mpsc::UnboundedSender<Packet>>>);

impl Handler for Hearing {
    fn on_packet(&self, _: &TcpConnection, packet: Packet) -> Result<(), String> {
        if let Some(heard) = self.0.lock().unwrap().as_ref() {
            let _ = heard.send(packet);
        }
        Ok(())
    }

    fn on_closed(&self, _: &TcpConnection, _: Option<io::Error>) {}
}

// One end of a pipe, at a made-up loopback port.
fn end(stream: tokio::io::DuplexStream, port: u16) -> TcpConnection {
    let (input, output) = tokio::io::split(stream);
    TcpConnection::over(
        input,
        output,
        SocketAddr::from((Ipv4Addr::LOCALHOST, port)),
        Some(IpAddr::V4(Ipv4Addr::LOCALHOST)),
        frame_limits::client,
    )
}

#[tokio::test]
async fn should_carry_packets_over_a_stream_other_than_a_socket() {
    let (near_end, far_end) = tokio::io::duplex(1 << 16);
    let near = end(near_end, 1);
    let far = end(far_end, 2);
    let (heard_in, mut heard) = mpsc::unbounded_channel();
    near.start(Hearing(Mutex::new(None)), Box::new(Ok));
    far.start(Hearing(Mutex::new(Some(heard_in))), Box::new(Ok));

    near.send(Packet::of(protocol::EMULE, 0x60, vec![1, 2, 3]));

    let packet = tokio::time::timeout(Duration::from_secs(10), heard.recv()).await.unwrap().unwrap();
    assert_eq!(packet, Packet::of(protocol::EMULE, 0x60, vec![1, 2, 3]));
    near.close();
    far.close();
}
