// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's Socks5UdpTest, under its names.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use crate::fake_socks5_proxy;

use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket};
use std::sync::Arc;
use std::time::Duration;

use fake_socks5_proxy::FakeSocks5Proxy;
use njmule_engine::config::proxy_settings::{ProxySettings, ProxyType};
use njmule_engine::net::socks5::Socks5Error;
use njmule_engine::net::socks5_udp::{self, Socks5Udp};
use njmule_engine::net::udp_route::UdpRoute;

const WAIT: Duration = Duration::from_secs(5);

fn peer() -> SocketAddrV4 {
    SocketAddrV4::new(Ipv4Addr::new(10, 1, 2, 3), 4672)
}

async fn associate(settings: &ProxySettings) -> Result<Socks5Udp, Socks5Error> {
    Socks5Udp::associate(settings, None, WAIT).await
}

fn assert_relays_no_udp(asked: Result<Socks5Udp, Socks5Error>) {
    assert!(
        matches!(asked, Err(Socks5Error::Unsupported(_) | Socks5Error::NotVersion5)),
        "{:?}",
        asked.map(|association| association.relay())
    );
}

#[tokio::test]
async fn should_get_a_relay_from_a_proxy_without_a_login() {
    let proxy = FakeSocks5Proxy::start();

    let association = associate(&proxy.settings()).await.unwrap();

    assert!(association.is_open());
    assert!(association.relay().ip().is_loopback());
    assert!(proxy.seen().contains(&"associate".to_owned()));
}

#[tokio::test]
async fn should_log_in_with_the_user_and_password_of_the_settings() {
    let proxy = FakeSocks5Proxy::with_login("mule", "s3cret");

    let association = associate(&proxy.settings_with("mule", "s3cret")).await.unwrap();

    assert!(association.is_open());
    let seen = proxy.seen();
    assert!(seen.contains(&"login mule:s3cret".to_owned()) && seen.contains(&"associate".to_owned()), "{seen:?}");
}

#[tokio::test]
async fn should_tell_a_proxy_that_relays_no_udp() {
    let proxy = FakeSocks5Proxy::refusing_udp();

    assert_relays_no_udp(associate(&proxy.settings()).await);
}

#[tokio::test]
async fn should_tell_a_proxy_that_wants_a_login_the_settings_lack() {
    let proxy = FakeSocks5Proxy::with_login("mule", "s3cret");

    assert_relays_no_udp(associate(&proxy.settings()).await);
}

#[tokio::test]
async fn should_tell_a_wrong_password() {
    let proxy = FakeSocks5Proxy::with_login("mule", "s3cret");

    assert_relays_no_udp(associate(&proxy.settings_with("mule", "wrong")).await);
}

#[tokio::test]
async fn should_tell_an_http_proxy() {
    let http =
        ProxySettings { kind: ProxyType::Http, host: "127.0.0.1".to_owned(), port: 3128, ..ProxySettings::default() };

    assert_relays_no_udp(associate(&http).await);
}

#[tokio::test]
async fn should_end_when_the_proxy_ends_the_association() {
    let proxy = FakeSocks5Proxy::start();
    let association = associate(&proxy.settings()).await.unwrap();

    proxy.end_associations();

    tokio::time::timeout(Duration::from_secs(10), association.ended()).await.unwrap();
    assert!(!association.is_open());
}

#[tokio::test]
async fn should_carry_a_datagram_through_the_relay_and_its_answer_back() {
    let proxy = FakeSocks5Proxy::start();
    let route = UdpRoute::Through(Arc::new(associate(&proxy.settings()).await.unwrap()));
    let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let peer = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    client.set_read_timeout(Some(WAIT)).unwrap();
    peer.set_read_timeout(Some(WAIT)).unwrap();
    let peer_at = peer.local_addr().unwrap();

    let (to, datagram) = route.outgoing(peer_at, b"ping").unwrap();
    client.send_to(&datagram, to).unwrap();
    let mut buffer = [0; 1024];
    let (length, asked_from) = peer.recv_from(&mut buffer).unwrap();
    assert_eq!(&buffer[..length], b"ping");
    peer.send_to(b"pong", asked_from).unwrap();
    let (length, relay) = client.recv_from(&mut buffer).unwrap();
    let (from, answer) = route.incoming(relay, &buffer[..length]).unwrap();

    assert!(proxy.seen().contains(&format!("relay to {peer_at}")), "{:?}", proxy.seen());
    assert_eq!(from.port(), peer_at.port());
    assert_eq!(answer, b"pong");
}

#[test]
fn should_read_the_sender_of_a_wrapped_datagram() {
    let wrapped = socks5_udp::wrap(peer(), b"hello");

    let (from, payload) = socks5_udp::unwrap(&wrapped).unwrap();

    assert_eq!(from, SocketAddr::V4(peer()));
    assert_eq!(payload, b"hello");
}

#[test]
fn should_drop_a_fragment() {
    let mut wrapped = socks5_udp::wrap(peer(), b"hello");
    wrapped[2] = 1;

    assert_eq!(socks5_udp::unwrap(&wrapped), None);
}

#[test]
fn should_drop_a_datagram_shorter_than_its_header() {
    assert_eq!(socks5_udp::unwrap(&[0, 0, 0, 1, 10, 1, 2]), None);
}

#[test]
fn should_drop_a_datagram_from_a_named_sender() {
    let mut wrapped = socks5_udp::wrap(peer(), b"hello");
    wrapped[3] = 3;

    assert_eq!(socks5_udp::unwrap(&wrapped), None);
}
