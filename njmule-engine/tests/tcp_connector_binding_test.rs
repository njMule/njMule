// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's TcpConnectorBindingTest, under its names: outgoing connections leave from the network interface of
//! the preferences, and none while it is gone.

#![allow(clippy::unwrap_used)]

use std::net::{Ipv4Addr, SocketAddr, TcpListener};
use std::time::Duration;

use njmule_engine::config::bind_interface::Binding;
use njmule_engine::config::proxy_settings::ProxySettings;
use njmule_engine::net::routes::Routes;
use njmule_engine::net::tcp_connector;
use rand::SeedableRng;
use rand::rngs::StdRng;

const TIMEOUT: Duration = Duration::from_secs(5);

fn peer() -> (TcpListener, SocketAddr) {
    let peer = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let address = peer.local_addr().unwrap();
    (peer, address)
}

#[tokio::test]
async fn should_leave_from_the_address_of_the_chosen_interface() {
    let (peer, address) = peer();
    let routes = Routes::new(ProxySettings::default, || Binding::Bound(Ipv4Addr::LOCALHOST));

    let connection =
        tcp_connector::connect_to_client(address, None, TIMEOUT, &routes, &mut StdRng::seed_from_u64(1)).await;

    let (_accepted, from) = peer.accept().unwrap();
    assert!(connection.is_ok());
    assert_eq!(from.ip(), Ipv4Addr::LOCALHOST);
}

#[tokio::test]
async fn should_not_connect_at_all_while_the_chosen_interface_is_gone() {
    let (peer, address) = peer();
    peer.set_nonblocking(true).unwrap();
    let routes = Routes::new(ProxySettings::default, || Binding::Missing("wg0".to_owned()));

    let failure = tcp_connector::connect_to_client(address, None, TIMEOUT, &routes, &mut StdRng::seed_from_u64(1))
        .await
        .err()
        .unwrap();

    assert!(failure.to_string().contains("wg0"), "{failure}");
    assert!(peer.accept().is_err(), "nothing reached the peer");
}
