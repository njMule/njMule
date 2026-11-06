// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's IpFilterTest, under its names.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::net::Ipv4Addr;

use njmule_engine::persist::format::ip_filter_dat::{self, Range};
use njmule_engine::security::ip_filter::{self, IpFilter};
use proptest::prelude::{Strategy, prop};

// Addresses 0.0.0.1 to 0.0.0.200, few enough to check every one.
const LAST_SMALL_ADDRESS: u32 = 200;

fn ip(text: &str) -> u32 {
    u32::from(text.parse::<Ipv4Addr>().unwrap())
}

fn range(start: &str, end: &str, level: u32, description: &str) -> Range {
    Range { start: ip(start), end: ip(end), level, description: description.to_owned() }
}

fn last_blocking_range_covering(ranges: &[Range], address: u32) -> Option<usize> {
    ranges.iter().rposition(|r| r.level < ip_filter::DEFAULT_LEVEL && r.contains(address))
}

#[test]
fn should_block_only_ranges_below_the_level() {
    let filter = IpFilter::of(
        &[
            range("10.0.0.0", "10.255.255.255", 0, "private"),
            range("20.0.0.0", "20.0.0.255", 127, "allowed"),
            range("200.1.2.0", "200.1.2.255", 100, "high"),
        ],
        ip_filter::DEFAULT_LEVEL,
    );

    assert!(filter.blocks(ip("10.1.2.3")));
    assert_eq!(filter.matched(ip("10.255.255.255")), Some("private"));
    assert!(!filter.blocks(ip("11.0.0.0")));
    assert!(!filter.blocks(ip("20.0.0.1")));
    assert!(filter.blocks(ip("200.1.2.0")));
    assert!(!filter.blocks(ip("200.1.3.0")));
    assert_eq!(filter.range_count(), 2);
}

#[test]
fn should_let_later_ranges_win_where_they_overlap() {
    let filter = IpFilter::of(
        &[
            range("1.0.0.10", "1.0.0.20", 0, "b"),
            range("1.0.0.0", "1.0.0.15", 0, "a"),
            range("1.0.0.21", "1.0.0.30", 50, "c"),
            range("1.0.0.40", "1.0.0.50", 0, "d"),
        ],
        ip_filter::DEFAULT_LEVEL,
    );

    assert_eq!(filter.range_count(), 4);
    assert_eq!(filter.matched(ip("1.0.0.12")), Some("a"));
    assert_eq!(filter.matched(ip("1.0.0.17")), Some("b"));
    assert_eq!(filter.matched(ip("1.0.0.30")), Some("c"));
    assert!(!filter.blocks(ip("1.0.0.35")));
    assert_eq!(filter.matched(ip("1.0.0.45")), Some("d"));
}

fn small_ranges() -> impl Strategy<Value = Vec<Range>> {
    prop::collection::vec(
        (1..=LAST_SMALL_ADDRESS, 0..=40_u32, prop::sample::select(vec![0, ip_filter::DEFAULT_LEVEL])).prop_map(
            |(start, length, level)| Range {
                start,
                end: LAST_SMALL_ADDRESS.min(start + length),
                level,
                description: format!("{start}+{length}@{level}"),
            },
        ),
        0..=12,
    )
}

proptest::proptest! {
    #[test]
    fn should_give_every_address_the_last_listed_blocking_range_that_covers_it(ranges in small_ranges()) {
        let filter = IpFilter::of(&ranges, ip_filter::DEFAULT_LEVEL);

        let mut runs = 0;
        let mut previous = None;
        for address in 1..=LAST_SMALL_ADDRESS {
            let winner = last_blocking_range_covering(&ranges, address);
            let expected = winner.map(|index| ranges[index].description.as_str());
            proptest::prop_assert_eq!(filter.matched(address), expected, "address {}", address);
            if winner.is_some() && winner != previous {
                runs += 1;
            }
            previous = winner;
        }
        proptest::prop_assert_eq!(filter.range_count(), runs);
    }
}

#[test]
fn should_never_block_the_zero_address() {
    let filter = IpFilter::of(&[range("0.0.0.0", "255.255.255.255", 0, "all")], 127);

    assert!(!filter.blocks(0));
    assert!(filter.blocks(ip("255.255.255.255")));
}

#[test]
fn should_never_block_with_an_empty_list() {
    assert!(!IpFilter::empty().blocks(ip("1.2.3.4")));
}

#[test]
fn should_hold_the_ends_of_ranges_across_the_sign_bit_and_up_to_the_last_address() {
    let filter = IpFilter::of(
        &[
            range("9.9.9.9", "9.9.9.9", 0, "one"),
            range("127.255.255.250", "128.0.0.5", 0, "across"),
            range("255.255.255.0", "255.255.255.255", 0, "top"),
            range("50.0.0.0", "50.0.0.9", 127, "at the level"),
            range("60.0.0.0", "60.0.0.9", 0, "left"),
            range("60.0.0.10", "60.0.0.19", 0, "right"),
        ],
        127,
    );

    assert_eq!(filter.matched(ip("9.9.9.9")), Some("one"));
    assert!(!filter.blocks(ip("9.9.9.8")));
    assert!(!filter.blocks(ip("9.9.9.10")));
    assert_eq!(filter.matched(ip("127.255.255.255")), Some("across"));
    assert_eq!(filter.matched(ip("128.0.0.0")), Some("across"));
    assert!(!filter.blocks(ip("128.0.0.6")));
    assert_eq!(filter.matched(ip("255.255.255.255")), Some("top"));
    assert!(!filter.blocks(ip("50.0.0.5")), "a level equal to the filter's lets through");
    assert_eq!(filter.matched(ip("60.0.0.9")), Some("left"));
    assert_eq!(filter.matched(ip("60.0.0.10")), Some("right"));
    assert_eq!(filter.range_count(), 5);
}

#[test]
fn should_read_an_ip_filter_dat_file() {
    let dat = b"# comment\n001.002.003.000 - 001.002.003.255 , 000 , test range\n\
                009.009.009.009 - 009.009.009.009 , 200 , harmless\n";

    let filter = IpFilter::of(&ip_filter_dat::read(dat, "ipfilter.dat"), 127);

    assert_eq!(filter.matched(ip("1.2.3.4")), Some("test range"));
    assert!(!filter.blocks(ip("9.9.9.9")));
}
