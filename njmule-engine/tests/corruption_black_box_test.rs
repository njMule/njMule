// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's CorruptionBlackBoxTest, under its names: which sources sent the corrupt data of a download.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects, clippy::single_range_in_vec_init)]

use std::collections::HashSet;
use std::net::IpAddr;

use njmule_engine::download::corruption_black_box::CorruptionBlackBox;
use njmule_engine::files::hash::ed2k::{EMBLOCKSIZE, PARTSIZE};

const PART: u64 = PARTSIZE;
const BLOCK: u64 = EMBLOCKSIZE;

fn ip(text: &str) -> IpAddr {
    text.parse().unwrap()
}

fn set(ips: &[&str]) -> HashSet<IpAddr> {
    ips.iter().map(|text| ip(text)).collect()
}

// The part `part` sent whole by `from` failed, and nobody was blamed for it yet.
fn sent_a_part_that_failed(box_: &mut CorruptionBlackBox, from: &str, part: u64) {
    box_.received(part * PART, (part + 1) * PART, ip(from));
    assert!(box_.failed(part * PART, (part + 1) * PART).is_empty());
}

#[test]
fn should_take_the_addresses_of_an_ipv6_slash64_as_one_source() {
    let mut box_ = CorruptionBlackBox::default();
    sent_a_part_that_failed(&mut box_, "2001:db8:1:2::1", 0);
    sent_a_part_that_failed(&mut box_, "2001:db8:1:2::99", 0);
    box_.received(0, PART / 2, ip("2001:db8:1:2::1"));
    box_.received(PART / 2, PART, ip("2001:db8:1:2::99"));

    let guilty = box_.failed(0, PART);

    assert_eq!(guilty, set(&["2001:db8:1:2::"]));
}

#[test]
fn should_forgive_a_source_that_alone_sent_one_part_that_failed() {
    let mut box_ = CorruptionBlackBox::default();
    box_.received(0, PART, ip("192.0.2.1"));

    let guilty = box_.failed(0, PART);

    assert!(guilty.is_empty());
}

#[test]
fn should_blame_a_source_that_alone_sent_a_part_that_failed_three_times() {
    let mut box_ = CorruptionBlackBox::default();
    sent_a_part_that_failed(&mut box_, "192.0.2.1", 0);
    sent_a_part_that_failed(&mut box_, "192.0.2.1", 0);
    box_.received(0, PART, ip("192.0.2.1"));

    let guilty = box_.failed(0, PART);

    assert_eq!(guilty, set(&["192.0.2.1"]));
}

#[test]
fn should_not_blame_for_three_failures_a_source_whose_other_parts_passed() {
    let mut box_ = CorruptionBlackBox::default();
    for part in 0..7 {
        box_.received(part * PART, (part + 1) * PART, ip("192.0.2.1"));
        box_.passed(part * PART, (part + 1) * PART);
    }
    sent_a_part_that_failed(&mut box_, "192.0.2.1", 7);
    sent_a_part_that_failed(&mut box_, "192.0.2.1", 8);
    box_.received(9 * PART, 10 * PART, ip("192.0.2.1"));

    let guilty = box_.failed(9 * PART, 10 * PART);

    assert!(guilty.is_empty());
}

#[test]
fn should_blame_none_of_the_several_sources_that_sent_a_failed_part() {
    let mut box_ = CorruptionBlackBox::default();
    box_.received(0, PART / 2, ip("192.0.2.1"));
    box_.received(PART / 2, PART, ip("192.0.2.2"));

    let guilty = box_.failed(0, PART);

    assert!(guilty.is_empty());
}

#[test]
fn should_blame_no_source_for_a_part_with_data_from_no_known_source() {
    let mut box_ = CorruptionBlackBox::default();
    box_.received(BLOCK, PART, ip("192.0.2.1"));

    let guilty = box_.failed(0, PART);

    assert!(guilty.is_empty());
}

#[test]
fn should_blame_only_the_source_of_the_bad_block_through_recovery_data() {
    let mut box_ = CorruptionBlackBox::default();
    box_.received(0, 2 * BLOCK, ip("192.0.2.1"));
    box_.received(2 * BLOCK, 3 * BLOCK, ip("192.0.2.2"));
    assert!(box_.failed(0, 3 * BLOCK).is_empty());

    let guilty = box_.recovered(&[0..BLOCK, BLOCK..2 * BLOCK], &[2 * BLOCK..3 * BLOCK]);

    assert_eq!(guilty, set(&["192.0.2.2"]));
}
