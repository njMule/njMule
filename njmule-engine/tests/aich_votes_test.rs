// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's AichVotesTest, under their names: a master hash trusted by enough networks.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::net::{IpAddr, Ipv4Addr};

use njmule_engine::files::hash::aich_votes::AichVotes;

const GOOD: [u8; 20] = hash(1);
const BAD: [u8; 20] = hash(2);

const fn hash(seed: u8) -> [u8; 20] {
    let mut b = [0u8; 20];
    b[0] = seed;
    b
}

fn v4(a: u8, b: u8, c: u8, d: u8) -> IpAddr {
    IpAddr::V4(Ipv4Addr::new(a, b, c, d))
}

fn v6(text: &str) -> IpAddr {
    text.parse().unwrap()
}

#[test]
fn should_trust_a_hash_once_ten_networks_agree() {
    let mut votes = AichVotes::new();
    for i in 0..9 {
        assert_eq!(votes.announced(v4(10 + i, 1, 1, 1), GOOD), None);
    }

    assert_eq!(votes.announced(v4(30, 1, 1, 1), GOOD), Some(GOOD));
}

#[test]
fn should_count_one_network_once_and_for_its_first_hash() {
    let mut votes = AichVotes::new();
    for i in 0..20 {
        votes.announced(v4(10, 1, 1, i), GOOD);
    }

    votes.announced(v4(10, 1, 2, 1), BAD);

    assert_eq!(votes.trusted(), None, "all from one /20");
}

#[test]
fn should_count_the_ipv6_hosts_of_one_slash48_once() {
    let mut votes = AichVotes::new();
    for i in 0..20 {
        votes.announced(v6(&format!("2001:db8:ffff:{i:x}::1")), GOOD);
    }

    votes.announced(v6("2001:db8:ffff:99::1"), GOOD);

    assert_eq!(votes.trusted(), None, "all from one /48");
}

#[test]
fn should_trust_a_hash_once_ten_ipv6_networks_agree() {
    let mut votes = AichVotes::new();
    for i in 0..9 {
        votes.announced(v6(&format!("2001:db8:{i:x}::1")), GOOD);
    }

    assert_eq!(votes.announced(v6("2001:db8:9:ff::1"), GOOD), Some(GOOD));
}

#[test]
fn should_keep_the_trust_while_other_hashes_are_a_minority() {
    let mut votes = AichVotes::new();
    for i in 0..12 {
        votes.announced(v4(40 + i, 1, 1, 1), GOOD);
    }

    votes.announced(v4(90, 1, 1, 1), BAD);

    assert_eq!(votes.trusted(), Some(GOOD), "12 of 13 is 92%");
}

#[test]
fn should_drop_the_trust_when_other_hashes_are_too_many() {
    let mut votes = AichVotes::new();
    for i in 0..12 {
        votes.announced(v4(40 + i, 1, 1, 1), GOOD);
    }
    votes.announced(v4(90, 1, 1, 1), BAD);

    votes.announced(v4(91, 1, 1, 1), BAD);

    assert_eq!(votes.trusted(), None, "12 of 14 is too few");
}
