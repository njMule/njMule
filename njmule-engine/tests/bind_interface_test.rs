// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's BindInterfaceTest, under its names, and how a choice reads as an address.

#![allow(clippy::unwrap_used)]

use std::net::Ipv4Addr;

use njmule_engine::config::bind_interface::{self, Binding, Candidate};

fn candidate(name: &str, display_name: &str, loopback: bool, addresses: &[&str]) -> Candidate {
    Candidate {
        name: name.to_owned(),
        display_name: display_name.to_owned(),
        loopback,
        addresses: addresses.iter().map(|address| address.parse().unwrap()).collect(),
    }
}

fn a_computer_with_a_lan_a_vpn_and_an_interface_waiting_for_its_address() -> Vec<Candidate> {
    vec![
        candidate("lo", "Loopback", true, &["127.0.0.1"]),
        candidate("eth0", "Ethernet", false, &["192.168.1.10"]),
        candidate("wg0", "WireGuard Tunnel", false, &["169.254.3.3", "10.8.0.2"]),
        // Only the address an interface takes while it waits for its own.
        candidate("eth1", "USB Ethernet", false, &["169.254.7.7"]),
    ]
}

#[test]
fn should_bind_a_choice_to_an_address_of_an_interface_that_is_there() {
    let computer = a_computer_with_a_lan_a_vpn_and_an_interface_waiting_for_its_address();
    for (choice, expected) in [
        ("", "any"),
        (" ", "any"),
        ("wg0", "10.8.0.2"),
        ("WG0", "10.8.0.2"),
        ("WireGuard Tunnel", "10.8.0.2"),
        ("10.8.0.2", "10.8.0.2"),
        ("192.168.1.10", "192.168.1.10"),
        ("eth1", "missing"),
        ("tun0", "missing"),
        ("203.0.113.7", "missing"),
        ("169.254.7.7", "169.254.7.7"),
    ] {
        let binding = bind_interface::resolve(choice, &computer);

        let wanted = match expected {
            "any" => Binding::Any,
            "missing" => Binding::Missing(choice.trim().to_owned()),
            address => Binding::Bound(address.parse().unwrap()),
        };
        assert_eq!(binding, wanted, "{choice} binds to {expected}");
    }
}

#[test]
fn should_read_four_decimal_parts_as_an_address() {
    for (choice, address) in [
        ("010.8.0.2", true),
        (" 10.8.0.02 ", true),
        ("10.8.0", false),
        ("256.1.1.1", false),
        ("1.2.3.4.5", false),
        ("1.2.3.+4", false),
        ("1.2..4", false),
        ("1234.1.1.1", false),
        ("eth0", false),
    ] {
        assert_eq!(bind_interface::is_address(choice), address, "{choice}");
    }
    let computer = vec![candidate("tun0", "Tunnel", false, &["10.8.0.2"])];
    assert_eq!(bind_interface::resolve("010.8.0.2", &computer), Binding::Bound(Ipv4Addr::new(10, 8, 0, 2)));
}

#[test]
fn should_offer_the_interfaces_with_an_address_but_the_loopback() {
    let mut computer = a_computer_with_a_lan_a_vpn_and_an_interface_waiting_for_its_address();
    computer.push(candidate("down0", "Nothing yet", false, &[]));

    let choices: Vec<&str> = bind_interface::choices(&computer).iter().map(|choice| choice.name.as_str()).collect();

    assert_eq!(choices, ["eth0", "wg0", "eth1"]);
}
