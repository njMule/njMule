// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's IpWhitelistTest, under its names.

#![allow(clippy::unwrap_used)]

use std::net::Ipv4Addr;

use njmule_engine::security::ip_whitelist;

fn ranges_of(text: &str) -> Vec<String> {
    ip_whitelist::ranges(text)
        .unwrap()
        .iter()
        .map(|range| format!("{}-{}", Ipv4Addr::from(range.start), Ipv4Addr::from(range.end)))
        .collect()
}

#[test]
fn should_read_an_address_a_block_or_a_range_of_ip_filter_dat() {
    for (entry, start, end) in [
        ("1.2.3.4", "1.2.3.4", "1.2.3.4"),
        ("001.002.003.004", "1.2.3.4", "1.2.3.4"),
        ("10.20.30.40/24", "10.20.30.0", "10.20.30.255"),
        ("10.20.30.40 / 32", "10.20.30.40", "10.20.30.40"),
        ("200.1.2.3/1", "128.0.0.0", "255.255.255.255"),
        ("8.8.8.8/0", "0.0.0.0", "255.255.255.255"),
        ("1.2.3.0 - 1.2.3.255 , 000 , a/b network", "1.2.3.0", "1.2.3.255"),
        ("5.6.7.8-5.6.7.9", "5.6.7.8", "5.6.7.9"),
        ("Some Org:9.9.9.0-9.9.9.127", "9.9.9.0", "9.9.9.127"),
    ] {
        assert_eq!(ranges_of(entry), [format!("{start}-{end}")], "{entry}");
    }
}

#[test]
fn should_leave_out_empty_lines_and_comments() {
    let ranges = ranges_of("# my friends\n\n  1.2.3.4  \r\n// office\n5.6.7.0/31\n");

    assert_eq!(ranges, ["1.2.3.4-1.2.3.4", "5.6.7.0-5.6.7.1"]);
}

#[test]
fn should_name_an_entry_that_is_no_address_block_or_range() {
    for entry in [
        "1.2.3",
        "256.1.1.1",
        "1.2.3.4/33",
        "1.2.3.4/x",
        "1.2.3.4/",
        "/24",
        "1.2.3.4/024",
        "1.2.3.4 friend",
        "1.2.3.9 - 1.2.3.0",
        "::1",
        "friend.example.org",
    ] {
        let invalid = ip_whitelist::first_invalid_entry(&format!("1.2.3.4\n{entry}\n5.6.7.8"));

        assert_eq!(invalid.as_deref(), Some(entry), "{entry}");
        assert!(ip_whitelist::ranges(entry).unwrap_err().contains(entry));
    }
}

#[test]
fn should_find_nothing_wrong_in_a_good_whitelist() {
    let invalid = ip_whitelist::first_invalid_entry("1.2.3.4\n10.0.0.0/8");

    assert_eq!(invalid, None);
}

fn blocking(start: &str, end: &str) -> njmule_engine::security::ip_filter::IpFilter {
    let range = njmule_engine::persist::format::ip_filter_dat::Range {
        start: ip(start),
        end: ip(end),
        level: 0,
        description: String::new(),
    };
    njmule_engine::security::ip_filter::IpFilter::of(&[range], njmule_engine::security::ip_filter::DEFAULT_LEVEL)
}

fn ip(text: &str) -> u32 {
    u32::from(text.parse::<Ipv4Addr>().unwrap())
}

#[test]
fn should_let_through_the_allowed_addresses_of_a_blocked_range() {
    let filter = blocking("1.2.3.0", "1.2.3.255");

    let allowing = filter.allowing(&ip_whitelist::ranges("1.2.3.4\n1.2.3.128/25").unwrap());

    for through in ["1.2.3.4", "1.2.3.128", "1.2.3.255", "9.9.9.9"] {
        assert!(!allowing.blocks(ip(through)), "{through}");
    }
    for blocked in ["1.2.3.3", "1.2.3.5", "1.2.3.127"] {
        assert!(allowing.blocks(ip(blocked)), "{blocked}");
    }
}

#[test]
fn should_forget_the_addresses_of_an_earlier_whitelist() {
    let filter = blocking("1.2.3.0", "1.2.3.255").allowing(&ip_whitelist::ranges("1.2.3.4").unwrap());

    let allowing = filter.allowing(&ip_whitelist::ranges("1.2.3.5").unwrap());

    assert!(allowing.blocks(ip("1.2.3.4")));
    assert!(!allowing.blocks(ip("1.2.3.5")));
}
