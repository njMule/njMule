// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ProxyRouteTest, under its names, with proxies of SOCKS 5, SOCKS 4 and HTTP made up for it.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use njmule_engine::config::bind_interface::Binding;
use njmule_engine::config::proxy_settings::{ProxySettings, ProxyType};
use njmule_engine::net::routes::Routes;
use njmule_engine::net::tcp_connector;
use rand::SeedableRng;
use rand::rngs::StdRng;

const TIMEOUT: Duration = Duration::from_secs(5);

type Seen = Arc<Mutex<Vec<String>>>;

#[derive(Clone, Copy)]
enum Login {
    Never,
    Always,
    WhenOffered,
}

fn listen() -> TcpListener {
    TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap()
}

fn byte(stream: &mut TcpStream) -> u8 {
    let mut one = [0];
    stream.read_exact(&mut one).unwrap();
    one[0]
}

fn bytes(stream: &mut TcpStream, count: usize) -> Vec<u8> {
    let mut read = vec![0; count];
    stream.read_exact(&mut read).unwrap();
    read
}

fn relay(mut from: TcpStream, upstream: SocketAddr) {
    let mut to = TcpStream::connect(upstream).unwrap();
    let _ = std::io::copy(&mut from, &mut to);
}

// A SOCKS 5 proxy (RFC 1928, user name and password per RFC 1929) that relays one connection.
fn socks5(login: Login, seen: &Seen) -> SocketAddr {
    let server = listen();
    let address = server.local_addr().unwrap();
    let seen = Arc::clone(seen);
    thread::spawn(move || {
        let (mut client, _) = server.accept().unwrap();
        byte(&mut client);
        let count = usize::from(byte(&mut client));
        let offered = bytes(&mut client, count).contains(&2);
        let wants_password = matches!(login, Login::Always) || (matches!(login, Login::WhenOffered) && offered);
        client.write_all(&[5, if wants_password { 2 } else { 0 }]).unwrap();
        if wants_password {
            byte(&mut client);
            let length = usize::from(byte(&mut client));
            let user = String::from_utf8(bytes(&mut client, length)).unwrap();
            let length = usize::from(byte(&mut client));
            let password = String::from_utf8(bytes(&mut client, length)).unwrap();
            seen.lock().unwrap().push(format!("auth {user}:{password}"));
            client.write_all(&[1, 0]).unwrap();
        }
        bytes(&mut client, 3);
        let kind = byte(&mut client);
        let ip: [u8; 4] = bytes(&mut client, if kind == 1 { 4 } else { 16 }).try_into().unwrap();
        let port = u16::from_be_bytes(bytes(&mut client, 2).try_into().unwrap());
        let target = SocketAddr::from((ip, port));
        seen.lock().unwrap().push(format!("connect {target}"));
        client.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0]).unwrap();
        relay(client, target);
    });
    address
}

// A SOCKS 4 proxy, which answers a SOCKS 5 greeting in version 0: a client may then go on in version 4, or leave and
// ask again on a new connection, as ours does.
fn socks4(seen: &Seen) -> SocketAddr {
    let server = listen();
    let address = server.local_addr().unwrap();
    let seen = Arc::clone(seen);
    thread::spawn(move || {
        for client in server.incoming() {
            let mut client = client.unwrap();
            let seen = Arc::clone(&seen);
            thread::spawn(move || {
                let mut first = [0];
                if client.read_exact(&mut first).is_err() {
                    return;
                }
                if first[0] == 5 {
                    let count = usize::from(byte(&mut client));
                    bytes(&mut client, count);
                    let _ = client.write_all(&[0, 91]);
                    let mut next = [0];
                    if client.read_exact(&mut next).is_err() || next[0] != 4 {
                        return;
                    }
                }
                byte(&mut client);
                let port = u16::from_be_bytes(bytes(&mut client, 2).try_into().unwrap());
                let ip: [u8; 4] = bytes(&mut client, 4).try_into().unwrap();
                // The user id, ended by a zero byte.
                while byte(&mut client) != 0 {}
                let target = SocketAddr::from((ip, port));
                seen.lock().unwrap().push(format!("connect4 {target}"));
                client.write_all(&[0, 90, 0, 0, 0, 0, 0, 0]).unwrap();
                relay(client, target);
            });
        }
    });
    address
}

// An HTTP proxy that accepts one CONNECT.
fn http_connect(seen: &Seen) -> SocketAddr {
    let server = listen();
    let address = server.local_addr().unwrap();
    let seen = Arc::clone(seen);
    thread::spawn(move || {
        let (mut client, _) = server.accept().unwrap();
        let mut head = Vec::new();
        while !head.ends_with(b"\r\n\r\n") {
            head.push(byte(&mut client));
        }
        let head = String::from_utf8(head).unwrap();
        let request: Vec<&str> = head.split(' ').collect();
        seen.lock().unwrap().push(format!("{} {}", request[0], request[1]));
        client.write_all(b"HTTP/1.1 200 Connection established\r\n\r\n").unwrap();
        relay(client, request[1].parse().unwrap());
    });
    address
}

fn routes(kind: ProxyType, proxy: SocketAddr, user: &str, password: &str) -> Routes {
    let settings = ProxySettings {
        kind,
        host: proxy.ip().to_string(),
        port: proxy.port(),
        user: user.to_owned(),
        password: password.to_owned(),
    }
    .validated()
    .unwrap();
    Routes::new(move || settings.clone(), || Binding::Any)
}

/// Connects to a target through `routes`; returns the target's address once it accepted the connection.
async fn connection_to_a_target_through(routes: &Routes) -> SocketAddr {
    let target = listen();
    let address = target.local_addr().unwrap();
    let connection = tcp_connector::connect_to_client(address, None, TIMEOUT, routes, &mut StdRng::seed_from_u64(1))
        .await
        .unwrap();
    target.accept().unwrap();
    drop(connection);
    address
}

#[tokio::test]
async fn should_connect_through_a_socks_proxy_with_its_credentials() {
    let seen = Seen::default();
    let routes = routes(ProxyType::Socks, socks5(Login::Always, &seen), "me", "secret");

    let target = connection_to_a_target_through(&routes).await;

    assert_eq!(*seen.lock().unwrap(), ["auth me:secret".to_owned(), format!("connect {target}")]);
    let shown = format!("{:?}", routes.proxy());
    assert!(!shown.contains("secret") && shown.contains("***"), "{shown}");
}

#[tokio::test]
async fn should_connect_through_a_socks_proxy_that_needs_no_credentials() {
    let seen = Seen::default();
    let routes = routes(ProxyType::Socks, socks5(Login::Never, &seen), "", "");

    let target = connection_to_a_target_through(&routes).await;

    assert_eq!(*seen.lock().unwrap(), [format!("connect {target}")]);
}

// As Dante does by default: a login is chosen whenever offered, so none is offered without a user name.
#[tokio::test]
async fn should_connect_through_a_socks_proxy_that_prefers_a_login_when_the_settings_have_none() {
    let seen = Seen::default();
    let routes = routes(ProxyType::Socks, socks5(Login::WhenOffered, &seen), "", "");

    let target = connection_to_a_target_through(&routes).await;

    assert_eq!(*seen.lock().unwrap(), [format!("connect {target}")]);
}

#[tokio::test]
async fn should_connect_through_a_socks4_proxy() {
    let seen = Seen::default();
    let routes = routes(ProxyType::Socks, socks4(&seen), "", "");

    let target = connection_to_a_target_through(&routes).await;

    assert_eq!(*seen.lock().unwrap(), [format!("connect4 {target}")]);
}

#[tokio::test]
async fn should_connect_through_an_http_proxy() {
    let seen = Seen::default();
    let routes = routes(ProxyType::Http, http_connect(&seen), "", "");

    let target = connection_to_a_target_through(&routes).await;

    assert_eq!(*seen.lock().unwrap(), [format!("CONNECT {target}")]);
}

#[tokio::test]
async fn should_go_direct_without_proxy_settings() {
    let routes = Routes::direct();

    let target = connection_to_a_target_through(&routes).await;

    assert_eq!(routes.proxy().kind, ProxyType::None);
    assert!(target.ip().is_loopback());
}

#[test]
fn should_tell_that_no_proxy_settings_are_disabled() {
    assert!(!ProxySettings::default().is_enabled());
}

#[test]
fn should_refuse_a_blank_host() {
    let blank = ProxySettings { kind: ProxyType::Socks, host: " ".to_owned(), port: 1080, ..ProxySettings::default() };

    assert!(blank.validated().is_err());
}

#[test]
fn should_refuse_port_zero() {
    let zero = ProxySettings { kind: ProxyType::Http, host: "h".to_owned(), port: 0, ..ProxySettings::default() };

    assert!(zero.validated().is_err());
}
