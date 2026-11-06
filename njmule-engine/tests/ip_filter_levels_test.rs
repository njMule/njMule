// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The example of the Java engine's IpFilterLevelsTest that reads ranges, under its name; the ones that ask the IP
//! filter itself wait for its port.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use njmule_engine::persist::format::ip_filter_dat::{self, Range};

fn ip(text: &str) -> u32 {
    u32::from(text.parse::<std::net::Ipv4Addr>().unwrap())
}

#[test]
fn should_merge_the_overlapping_ranges_of_one_level() {
    let file = "10.0.0.100 - 10.0.1.255 , 000 , second
10.0.0.0 - 10.0.0.255 , 000 , first
10.0.0.50 - 10.0.0.60 , 200 , apart
";

    let ranges = ip_filter_dat::read(file.as_bytes(), "ipfilter.dat");

    assert_eq!(
        ranges,
        [
            Range { start: ip("10.0.0.0"), end: ip("10.0.1.255"), level: 0, description: "first".to_owned() },
            Range { start: ip("10.0.0.50"), end: ip("10.0.0.60"), level: 200, description: "apart".to_owned() },
        ]
    );
}

fn filter_of(list: &str) -> njmule_engine::security::ip_filter::IpFilter {
    njmule_engine::security::ip_filter::IpFilter::of(
        &ip_filter_dat::read(list.as_bytes(), "ipfilter.dat"),
        njmule_engine::security::ip_filter::DEFAULT_LEVEL,
    )
}

#[test]
fn should_keep_blocked_a_range_inside_an_allowed_one() {
    let filter = filter_of("10.0.0.0 - 10.0.0.255 , 200 , allowed\n10.0.0.100 - 10.0.1.255 , 000 , blocked\n");

    assert!(filter.blocks(ip("10.0.0.150")) && filter.blocks(ip("10.0.1.10")));
    assert!(!filter.blocks(ip("10.0.0.50")));
}

#[test]
fn should_not_grow_a_blocked_range_over_an_allowed_one() {
    let filter = filter_of("10.0.0.0 - 10.0.0.255 , 000 , blocked\n10.0.0.200 - 10.0.3.255 , 200 , allowed\n");

    assert!(filter.blocks(ip("10.0.0.10")) && filter.blocks(ip("10.0.0.220")));
    assert!(!filter.blocks(ip("10.0.2.1")));
}
