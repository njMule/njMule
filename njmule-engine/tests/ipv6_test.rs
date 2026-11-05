// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's Ipv6Test, under their names.

#![allow(clippy::unwrap_used)]

use std::net::IpAddr;

use njmule_engine::util::ipv6;

fn address(text: &str) -> IpAddr {
    text.parse().unwrap()
}

#[test]
fn should_take_the_slash64_of_an_ipv6_address_and_an_ipv4_address_as_it_is() {
    let cases = [
        ("2001:db8:1:2:ffff:eeee:dddd:cccc", "2001:db8:1:2::"),
        ("2001:db8:1:2::", "2001:db8:1:2::"),
        ("fe80::1", "fe80::"),
        ("93.184.216.34", "93.184.216.34"),
    ];

    for (given, expected) in cases {
        assert_eq!(ipv6::network(address(given)), address(expected), "{given}");
    }
}

#[test]
fn should_tell_whether_peers_on_the_internet_may_reach_an_address() {
    let cases = [
        ("2a01:4f8:1:2::3", true),
        ("2001:db8::1", true),
        ("3fff:ffff::1", true),
        ("fe80::1", false),
        ("fd12:3456::1", false),
        ("::1", false),
        ("ff02::1", false),
        ("2002:102:304::1", false),
        ("2001:0:4136:e378::1", false),
        ("93.184.216.34", false),
    ];

    for (given, expected) in cases {
        let reachable = matches!(address(given), IpAddr::V6(v6) if ipv6::is_public(&v6));

        assert_eq!(reachable, expected, "{given}");
    }
}
