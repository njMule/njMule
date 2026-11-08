// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's TrustedProxiesTest, under its names: which client a request comes from, as the trusted proxies
//! tell.

#![allow(clippy::unwrap_used)]

use std::net::IpAddr;

use njmule_engine::remote::trusted_proxies::TrustedProxies;

#[test]
fn should_take_the_client_the_trusted_proxy_names() {
    let mut test = Proxies::new();
    test.given_the_trusted_proxies(&["172.17.0.0/16", "::1"]);

    test.when_a_request_comes_from("172.17.0.1", "198.51.100.7");

    test.then_the_client_is("198.51.100.7");
}

#[test]
fn should_read_the_header_from_the_right_past_the_trusted_proxies() {
    let mut test = Proxies::new();
    test.given_the_trusted_proxies(&["10.0.0.1-10.0.0.9"]);

    test.when_a_request_comes_from("10.0.0.2", "203.0.113.66, 198.51.100.7, 10.0.0.5");

    test.then_the_client_is("198.51.100.7");
}

#[test]
fn should_believe_no_header_from_an_address_not_trusted() {
    let mut test = Proxies::new();
    test.given_the_trusted_proxies(&["172.17.0.1"]);

    test.when_a_request_comes_from("192.0.2.10", "198.51.100.7");

    test.then_the_client_is("192.0.2.10");
}

#[test]
fn should_believe_no_header_without_trusted_proxies() {
    let mut test = Proxies::new();

    test.when_a_request_comes_from("127.0.0.1", "198.51.100.7");

    test.then_the_client_is("127.0.0.1");
}

#[test]
fn should_stop_at_what_is_no_address() {
    let mut test = Proxies::new();
    test.given_the_trusted_proxies(&["172.17.0.1", "172.17.0.2"]);

    test.when_a_request_comes_from("172.17.0.1", "198.51.100.7, evil.example, 172.17.0.2");

    test.then_the_client_is("172.17.0.2");
}

#[test]
fn should_refuse_what_is_no_address_block_or_range() {
    for entry in ["localhost", "proxy.example", "10.0.0.0/", "10.0.0.0/99", "10.0.0.1-", "", "1.2.3.4 5"] {
        let mut test = Proxies::new();

        test.when_the_trusted_proxies_are(entry);

        test.then_they_are_refused();
    }
}

struct Proxies {
    proxies: TrustedProxies,
    client: Option<IpAddr>,
    made: Option<Result<TrustedProxies, String>>,
}

impl Proxies {
    fn new() -> Self {
        Self { proxies: TrustedProxies::default(), client: None, made: None }
    }

    fn given_the_trusted_proxies(&mut self, entries: &[&str]) {
        self.proxies = TrustedProxies::new(&texts(entries)).unwrap();
    }

    fn when_a_request_comes_from(&mut self, remote: &str, forwarded_for: &str) {
        self.client = Some(self.proxies.client(remote.parse().unwrap(), &[forwarded_for.to_owned()]));
    }

    fn when_the_trusted_proxies_are(&mut self, entry: &str) {
        self.made = Some(TrustedProxies::new(&texts(&[entry])));
    }

    fn then_the_client_is(&self, address: &str) {
        assert_eq!(self.client, Some(address.parse().unwrap()));
    }

    fn then_they_are_refused(&self) {
        assert!(matches!(self.made, Some(Err(_))), "{:?}", self.made);
    }
}

fn texts(entries: &[&str]) -> Vec<String> {
    entries.iter().map(|entry| (*entry).to_owned()).collect()
}
