// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's RelayKeeperTest, under its names.

#![allow(clippy::unwrap_used, clippy::panic, clippy::arithmetic_side_effects)]

use crate::fake_socks5_proxy;

use std::net::{Ipv4Addr, TcpListener};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use fake_socks5_proxy::FakeSocks5Proxy;
use njmule_engine::config::proxy_settings::{ProxySettings, ProxyType};
use njmule_engine::net::relay_keeper::RelayKeeper;
use njmule_engine::net::udp_route::UdpRoute;

const SHORT_WAIT: Duration = Duration::from_millis(100);

#[derive(Clone, Default)]
struct Seen {
    routes: Arc<Mutex<Vec<UdpRoute>>>,
    log: Arc<Mutex<Vec<String>>>,
}

impl Seen {
    fn keep(&self, settings: ProxySettings) -> RelayKeeper {
        let (routes, log) = (Arc::clone(&self.routes), Arc::clone(&self.log));
        RelayKeeper::start_waiting(
            "Test UDP",
            move |route| routes.lock().unwrap().push(route),
            settings,
            None,
            move |level, text| log.lock().unwrap().push(format!("{level} {text}")),
            SHORT_WAIT,
        )
    }

    fn last_route(&self) -> String {
        self.routes.lock().unwrap().last().map(ToString::to_string).unwrap_or_default()
    }

    fn first_route(&self) -> String {
        self.routes.lock().unwrap().first().map(ToString::to_string).unwrap_or_default()
    }

    fn relayed(&self) -> bool {
        self.routes.lock().unwrap().last().is_some_and(UdpRoute::is_relayed)
    }

    fn logged(&self, start: &str) -> bool {
        self.log.lock().unwrap().iter().any(|line| line.starts_with(start))
    }
}

async fn eventually(condition: impl Fn() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(10);
    while !condition() {
        assert!(Instant::now() < deadline, "waited 10 s");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

fn socks(port: u16) -> ProxySettings {
    ProxySettings { kind: ProxyType::Socks, host: "127.0.0.1".to_owned(), port, ..ProxySettings::default() }
}

#[tokio::test]
async fn should_send_straight_without_a_proxy() {
    let seen = Seen::default();

    let _keeper = seen.keep(ProxySettings::default());

    assert_eq!(seen.last_route(), "direct");
    assert!(seen.log.lock().unwrap().is_empty());
}

#[tokio::test]
async fn should_send_straight_and_say_so_with_an_http_proxy() {
    let seen = Seen::default();

    let _keeper = seen.keep(ProxySettings { kind: ProxyType::Http, ..socks(3128) });

    assert_eq!(seen.last_route(), "direct");
    assert_eq!(
        *seen.log.lock().unwrap(),
        ["WARNING Test UDP goes straight to the network: an HTTP proxy carries TCP only"]
    );
}

#[tokio::test]
async fn should_go_through_the_relay_of_a_socks_proxy() {
    let proxy = FakeSocks5Proxy::start();
    let seen = Seen::default();

    let _keeper = seen.keep(proxy.settings());

    assert_eq!(seen.first_route(), "held");
    eventually(|| seen.relayed()).await;
    eventually(|| seen.logged("INFO Test UDP goes through the UDP relay of the SOCKS5 proxy")).await;
}

#[tokio::test]
async fn should_send_straight_and_say_so_with_a_socks_proxy_that_relays_no_udp() {
    let proxy = FakeSocks5Proxy::refusing_udp();
    let seen = Seen::default();

    let _keeper = seen.keep(proxy.settings());

    eventually(|| seen.last_route() == "direct").await;
    eventually(|| seen.logged("WARNING Test UDP goes straight to the network: the proxy relays no UDP")).await;
}

#[tokio::test]
async fn should_hold_while_the_proxy_cannot_be_reached() {
    let closed_port = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap().local_addr().unwrap().port();
    let seen = Seen::default();

    let _keeper = seen.keep(socks(closed_port));

    eventually(|| seen.logged("WARNING Test UDP is held until the SOCKS5 proxy answers")).await;
    assert_eq!(seen.last_route(), "held");
}

#[tokio::test]
async fn should_ask_for_another_relay_when_the_proxy_ends_its_own() {
    let proxy = FakeSocks5Proxy::start();
    let seen = Seen::default();
    let _keeper = seen.keep(proxy.settings());
    eventually(|| seen.relayed()).await;

    proxy.end_associations();

    eventually(|| seen.logged("WARNING Test UDP is held: the SOCKS5 proxy ended its UDP relay")).await;
    eventually(|| proxy.seen().iter().filter(|seen| *seen == "associate").count() == 2).await;
    eventually(|| seen.relayed()).await;
}

#[tokio::test]
async fn should_send_straight_and_end_the_association_when_the_proxy_is_turned_off() {
    let proxy = FakeSocks5Proxy::start();
    let seen = Seen::default();
    let keeper = seen.keep(proxy.settings());
    eventually(|| seen.relayed()).await;

    keeper.follow(ProxySettings::default());

    assert_eq!(seen.last_route(), "direct");
    eventually(|| proxy.seen().contains(&"ended".to_owned())).await;
}
