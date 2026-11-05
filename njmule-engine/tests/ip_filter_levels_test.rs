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
