// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's CountryTableTest, under its names.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::net::country_table::CountryTable;

// As DB-IP writes it: IPv4 and IPv6 ranges, and ZZ for the unknown.
const CSV: &str = "1.0.0.0,1.0.0.255,AU\n2.16.0.0,2.16.0.255,IT\n2.16.1.0,2.16.1.255,IT\n5.5.5.0,5.5.5.255,ZZ\n\
                   203.0.113.0,203.0.113.255,FR\n255.255.255.0,255.255.255.255,US\n2001:db8::,2001:db8::ffff,DE\n";

#[test]
fn should_tell_the_country_of_a_client_address() {
    let table = CountryTable::parse(CSV.as_bytes()).unwrap();
    for (address, country) in [
        ("1.0.0.7:4662", "au"),
        ("2.16.1.9:4662", "it"),
        ("203.0.113.200:51000", "fr"),
        ("255.255.255.255:1", "us"),
        ("5.5.5.5:4662", ""),
        ("9.9.9.9:4662", ""),
        ("[2001:db8::1]:4662", ""),
    ] {
        let found = table.country_of(address);

        assert_eq!(found.as_deref(), Some(country).filter(|c| !c.is_empty()), "{address}");
    }
}

#[test]
fn should_join_the_ranges_next_to_each_other_of_one_country() {
    let table = CountryTable::parse(CSV.as_bytes()).unwrap();

    let read = CountryTable::read(&table.write()).unwrap();

    assert_eq!(read.size(), 4);
    assert_eq!(read, table);
}

#[test]
fn should_refuse_a_database_without_an_ipv4_range() {
    assert!(CountryTable::parse(b"2001:db8::,2001:db8::ffff,DE\n").is_err());
}

#[test]
fn should_refuse_a_table_of_another_version_or_cut_short() {
    let written = CountryTable::parse(CSV.as_bytes()).unwrap().write();
    let mut other_version = written.clone();
    other_version[3] = 2;

    assert!(CountryTable::read(&other_version).is_err());
    assert!(CountryTable::read(&written[..written.len() - 1]).is_err());
    assert!(CountryTable::read(&[0, 0]).is_err());
    assert_eq!(CountryTable::empty().country_of("1.0.0.7:4662"), None);
    assert_eq!(
        CountryTable::parse(b"1.0.0.0,1.0.0.9,\"de\"\nx,y,FR\n").unwrap().country(0x0100_0005).as_deref(),
        Some("de")
    );
}
