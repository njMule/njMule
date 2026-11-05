// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's Ipv4Test, under their names.

#![allow(clippy::unwrap_used)]

use njmule_engine::util::ipv4;

#[test]
fn should_tell_a_lan_address() {
    let lan = [
        ipv4::of(10, 0, 0, 1),
        ipv4::of(172, 16, 0, 1),
        ipv4::of(172, 31, 255, 255),
        ipv4::of(192, 168, 1, 1),
        ipv4::of(0, 1, 2, 3),
    ];

    assert!(lan.into_iter().all(ipv4::is_lan));
}

#[test]
fn should_tell_an_address_outside_the_lan() {
    let outside = [ipv4::of(172, 32, 0, 1), ipv4::of(192, 169, 0, 1), ipv4::of(11, 0, 0, 1)];

    assert!(!outside.into_iter().any(ipv4::is_lan));
}

#[test]
fn should_keep_loopback_apart_from_the_lan() {
    let lan = ipv4::is_lan(ipv4::of(127, 0, 0, 1));

    assert!(!lan, "loopback is apart");
}

#[test]
fn should_take_a_public_unicast_address_for_a_peer() {
    let usable = ipv4::is_unicast_public_candidate(ipv4::of(93, 184, 216, 34));

    assert!(usable);
}

#[test]
fn should_refuse_an_address_that_no_peer_can_have() {
    let none = [ipv4::of(127, 0, 0, 1), ipv4::of(224, 0, 0, 1), ipv4::of(0, 0, 0, 0)];

    assert!(!none.into_iter().any(ipv4::is_unicast_public_candidate));
}

#[test]
fn should_make_the_same_socket_address_as_its_text_without_looking_it_up() {
    let cases = [((81, 2, 133, 47), 4672), ((255, 255, 255, 255), 1), ((128, 0, 0, 1), 65535)];

    for ((a, b, c, d), port) in cases {
        let address = ipv4::socket_address(ipv4::of(a, b, c, d), port);

        assert_eq!(address, format!("{a}.{b}.{c}.{d}:{port}").parse().unwrap());
    }
}
