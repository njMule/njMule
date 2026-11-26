// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Host:port parsing and search expression validation.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use njmule_engine::api::search_request;
use njmule_engine::server::server_list::Address;

#[test]
fn should_parse_a_host_with_its_port() {
    let mut lab = Lab::default();

    lab.when_the_address_is_parsed(" 192.168.1.10:4661 ");

    lab.then_the_address_is(Address::new(u32::from(Ipv4Addr::new(192, 168, 1, 10)), 4661));
}

#[test]
fn should_parse_an_ipv6_host_in_brackets() {
    let parsed: SocketAddr = "[2001:db8::1]:4661".parse().unwrap();

    assert_eq!(parsed.ip(), IpAddr::V6("2001:db8::1".parse::<Ipv6Addr>().unwrap()));
    assert_eq!(parsed.port(), 4661);
}

#[test]
fn should_refuse_a_host_without_a_valid_port() {
    for input in ["server.example.org", "host:70000"] {
        let mut lab = Lab::default();

        lab.when_the_address_is_parsed(input);

        lab.then_no_address_was_parsed();
    }
}

#[test]
fn should_write_addresses_that_parse_back() {
    for (host, port) in [("192.168.1.10", 65535u16), ("server.example.org", 4242)] {
        let parsed = Address::parse(&format!("{host}:{port}")).unwrap();
        let written = parsed.to_string();
        assert_eq!(Address::parse(&written), Some(parsed));
    }
    for (host, port) in [("2001:db8::1", 4661u16), ("::1", 1)] {
        let addr = SocketAddr::new(IpAddr::V6(host.parse().unwrap()), port);
        let written = addr.to_string();
        assert_eq!(written.parse::<SocketAddr>().unwrap(), addr);
    }
}

#[test]
fn should_write_an_ipv6_host_in_brackets() {
    let addr = SocketAddr::new(IpAddr::V6("2001:db8::1".parse().unwrap()), 4661);

    assert_eq!(addr.to_string(), "[2001:db8::1]:4661");
}

#[test]
fn should_tell_well_formed_hosts() {
    let cases = [
        ("server.example.org", true),
        ("localhost", true),
        ("10.0.0.1", true),
        ("2001:db8::1", true),
        ("bad..host", false),
        ("no spaces", false),
        ("", false),
    ];
    for (host, expected) in cases {
        let valid = Address::parse(&format!("{host}:4661")).is_some() || host.parse::<Ipv6Addr>().is_ok();
        assert_eq!(valid, expected, "host={host:?}");
    }
}

#[test]
fn should_accept_a_plain_search_expression() {
    assert!(search_request::check_expression("ubuntu iso").is_ok());
}

#[test]
fn should_refuse_an_unbalanced_search_expression() {
    assert!(search_request::check_expression("(ubuntu").is_err());
}

#[test]
fn should_refuse_a_search_expression_that_is_too_long() {
    let expression = "x".repeat(search_request::MAX_EXPRESSION_LENGTH + 1);

    assert!(search_request::check_expression(&expression).is_err());
}

#[derive(Default)]
struct Lab {
    parsed: Option<Address>,
}

impl Lab {
    fn when_the_address_is_parsed(&mut self, input: &str) {
        self.parsed = Address::parse(input);
    }

    fn then_the_address_is(&self, expected: Address) {
        assert_eq!(self.parsed, Some(expected));
    }

    fn then_no_address_was_parsed(&self) {
        assert!(self.parsed.is_none());
    }
}
