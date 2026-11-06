// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's TcpListenerTest, under its names: the port clients connect to.

#![allow(clippy::unwrap_used)]

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use njmule_engine::net::tcp_listener::TcpListener;
use njmule_engine::security::connection_guard::{ConnectionGuard, Limits};
use njmule_engine::security::ip_filter::IpFilter;
use tokio::sync::mpsc;

// On Linux a closed socket kept its port while its accepting task still held it, most times in 200.
const ROUNDS: usize = 50;

fn guard() -> Arc<ConnectionGuard> {
    let started = Instant::now();
    Arc::new(ConnectionGuard::new(
        || Arc::new(IpFilter::empty()),
        |_| false,
        |_| false,
        || Limits::DEFAULT,
        move || started.elapsed(),
    ))
}

#[tokio::test]
async fn should_free_its_port_before_close_returns() {
    let mut still_taken = 0;
    for _ in 0..ROUNDS {
        let opened = TcpListener::open(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)), guard(), |_, _| {}).await.unwrap();
        let port = opened.port();
        // Its task waits in accept.
        tokio::time::sleep(Duration::from_millis(5)).await;

        opened.close().await;

        if std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, port)).is_err() {
            still_taken += 1;
        }
    }

    assert_eq!(still_taken, 0);
}

#[tokio::test]
async fn should_accept_a_client_over_ipv6() {
    if std::net::TcpListener::bind((Ipv6Addr::LOCALHOST, 0)).is_err() {
        eprintln!("no IPv6 loopback here: the test passes over");
        return;
    }
    let (accepted_in, mut accepted) = mpsc::unbounded_channel();
    let listener = TcpListener::open(SocketAddr::from((Ipv6Addr::LOCALHOST, 0)), guard(), move |stream, _permit| {
        let _ = accepted_in.send(stream.peer_addr().unwrap().ip());
    })
    .await
    .unwrap();

    let _client = tokio::net::TcpStream::connect((Ipv6Addr::LOCALHOST, listener.port())).await.unwrap();

    let from = tokio::time::timeout(Duration::from_secs(10), accepted.recv()).await.unwrap().unwrap();
    assert_eq!(from, IpAddr::V6(Ipv6Addr::LOCALHOST));
    listener.close().await;
}
