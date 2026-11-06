// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's HttpRouteTest, under its names: the client leaves from the interface chosen, as a server on the
//! loopback sees it.

#![allow(clippy::unwrap_used)]

use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, TcpListener};
use std::thread;

use njmule_engine::config::bind_interface::Binding;
use njmule_engine::config::proxy_settings::ProxySettings;
use njmule_engine::net::http_route;
use njmule_engine::net::routes::Routes;

#[tokio::test]
async fn should_leave_from_the_chosen_interface() {
    let server = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let port = server.local_addr().unwrap().port();
    let seen = thread::spawn(move || {
        let (mut client, from) = server.accept().unwrap();
        let mut request = [0; 1024];
        let _ = client.read(&mut request).unwrap();
        client.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").unwrap();
        from.ip()
    });
    let routes = Routes::new(ProxySettings::default, || Binding::Bound(Ipv4Addr::LOCALHOST));

    let http = http_route::client(&routes).unwrap();

    http.get(format!("http://127.0.0.1:{port}/")).send().await.unwrap();
    assert_eq!(seen.join().unwrap(), IpAddr::V4(Ipv4Addr::LOCALHOST));
}

#[test]
fn should_start_no_client_while_the_chosen_interface_is_gone() {
    let routes = Routes::new(ProxySettings::default, || Binding::Missing("wg0".to_owned()));

    let failure = http_route::client(&routes).err().unwrap();

    assert!(failure.to_string().contains("wg0"), "{failure}");
}
