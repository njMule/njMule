// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's TextFormatsEdgeCaseTest, under their names: corner cases of the text files of a
//! profile. The ini files, read only when importing a profile, are not ported yet.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use njmule_engine::persist::format::ip_filter_dat::{self, Range};
use njmule_engine::persist::format::text_files::{self, PRIORITY_HIGH, PRIORITY_LOW, StaticServer};
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};

fn ip(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from_be_bytes([a, b, c, d])
}

fn range(start: u32, end: u32, level: u32, description: &str) -> Range {
    Range { start, end, level, description: description.to_owned() }
}

fn descriptions(ranges: &[Range]) -> Vec<&str> {
    ranges.iter().map(|range| range.description.as_str()).collect()
}

// Text of one line: no line ends, and no lone surrogate, which no encoding keeps.
fn text(random: &mut StdRng) -> String {
    (0..random.random_range(0..=30))
        .map(|_| {
            loop {
                let c = random.random_range('\u{0}'..='\u{FFFD}');
                if c != '\r' && c != '\n' {
                    break c;
                }
            }
        })
        .collect()
}

#[test]
fn should_end_the_lines_in_every_style() {
    assert_eq!(text_files::lines(b"a\r\nb\rc\nd"), ["a", "b", "c", "d"]);
}

#[test]
fn should_keep_the_empty_lines_before_the_last_line_end() {
    assert_eq!(text_files::lines(b"a\n\nb\n"), ["a", "", "b"]);
}

#[test]
fn should_read_no_lines_from_an_empty_file() {
    assert!(text_files::lines(&[]).is_empty());
}

#[test]
fn should_skip_the_utf8_byte_order_mark() {
    assert_eq!(text_files::lines(&hex::decode("efbbbf78").unwrap()), ["x"]);
}

#[test]
fn should_read_no_lines_from_a_utf16_byte_order_mark_alone() {
    assert!(text_files::lines(&hex::decode("fffe").unwrap()).is_empty());
}

#[test]
fn should_read_a_utf16_file_of_an_odd_length() {
    let lines = text_files::lines(&hex::decode("fffe610062").unwrap());

    assert_eq!(lines.len(), 1);
    assert!(lines[0].starts_with('a'));
}

#[test]
fn should_read_back_the_lines_it_writes() {
    let mut random = StdRng::seed_from_u64(1);
    for _ in 0..300 {
        let written: Vec<String> = (0..random.random_range(0..=10)).map(|_| text(&mut random)).collect();

        assert_eq!(text_files::lines(&text_files::write_lines(&written)), written);
    }
}

#[test]
fn should_leave_out_the_static_servers_without_a_usable_port() {
    let file = text_files::write_lines(&[
        "a.org:0,1,zero".to_owned(),
        "b.org:65536,1,big".to_owned(),
        "c.org:65535,7,odd priority".to_owned(),
    ]);

    assert_eq!(
        text_files::static_servers(&file),
        [StaticServer {
            host: "c.org".to_owned(),
            port: 65535,
            priority: PRIORITY_HIGH,
            name: "odd priority".to_owned()
        }]
    );
}

#[test]
fn should_read_back_the_static_servers_it_writes() {
    let mut random = StdRng::seed_from_u64(2);
    let host_chars: Vec<char> = ('a'..='z').chain('0'..='9').chain(['.', '-']).collect();
    for _ in 0..300 {
        let servers: Vec<StaticServer> = (0..random.random_range(0..=5))
            .map(|_| StaticServer {
                host: (0..random.random_range(1..=20))
                    .map(|_| host_chars[random.random_range(0..host_chars.len())])
                    .collect(),
                port: random.random_range(1..=0xFFFF),
                priority: random.random_range(0..=PRIORITY_LOW),
                name: text(&mut random),
            })
            .collect();

        assert_eq!(text_files::static_servers(&text_files::write_static_servers(&servers)), servers);
    }
}

#[test]
fn should_read_ip_filter_lines_in_their_many_spellings() {
    let file = "1.2.3.0-1.2.3.255,050,tight
4.0.0.0\t-\t4.0.0.255\t,\t60\t,\ttabs
010.000.000.008 - 010.000.000.009 , 0 , not octal
256.0.0.0 - 256.0.0.1 , 0 , bad octet
1000.0.0.0 - 1000.0.0.1 , 0 , four digits
// comment 9.9.9.9 - 9.9.9.9
255.255.255.0 - 255.255.255.255 , 7 , last
";

    assert_eq!(
        ip_filter_dat::read(file.as_bytes(), "ipfilter.dat"),
        [
            range(ip(1, 2, 3, 0), ip(1, 2, 3, 255), 50, "tight"),
            range(ip(4, 0, 0, 0), ip(4, 0, 0, 255), 60, "tabs"),
            range(ip(10, 0, 0, 8), ip(10, 0, 0, 9), 0, "not octal"),
            range(ip(255, 255, 255, 0), ip(255, 255, 255, 255), 7, "last"),
        ]
    );
}

#[test]
fn should_read_a_peer_guardian_text_list_with_colons_in_the_description() {
    let file = "Some: evil: corp:1.2.3.0-1.2.3.255\n";

    assert_eq!(
        ip_filter_dat::read(file.as_bytes(), "guarding.p2p"),
        [range(ip(1, 2, 3, 0), ip(1, 2, 3, 255), 100, "Some: evil: corp")]
    );
}

#[test]
fn should_read_a_latin1_peer_guardian_binary_list() {
    let file = hex::decode("ffffffff503242".to_owned() + "01" + "e900" + "05000000" + "050000ff").unwrap();

    assert_eq!(descriptions(&ip_filter_dat::read(&file, "x.p2b")), ["é"]);
}

#[test]
fn should_read_a_truncated_peer_guardian_binary_list_up_to_its_last_whole_range() {
    let file =
        hex::decode("ffffffff503242".to_owned() + "02" + "4100" + "05000000" + "050000ff" + "4200" + "0600").unwrap();

    assert_eq!(descriptions(&ip_filter_dat::read(&file, "x.p2b")), ["A"]);
}

#[test]
fn should_ignore_an_unknown_version_of_peer_guardian_binary_list() {
    let file = hex::decode("ffffffff503242".to_owned() + "03" + "4100" + "05000000" + "050000ff").unwrap();

    assert!(ip_filter_dat::read(&file, "x.p2b").is_empty());
}

#[test]
fn should_read_back_the_ip_filter_ranges_it_writes() {
    let mut random = StdRng::seed_from_u64(3);
    let description_chars: Vec<char> =
        ('a'..='z').chain('A'..='Z').chain('0'..='9').chain([' ', ',', '.', ':', '-']).collect();
    for _ in 0..300 {
        // Sorted, apart by at least one address, so that no two of them merge.
        let mut points: Vec<u32> = (0..random.random_range(0..=20)).map(|_| random.random()).collect();
        points.sort_unstable();
        points.dedup();
        let mut written: Vec<Range> = Vec::new();
        for pair in points.as_chunks::<2>().0 {
            if written.last().is_some_and(|last| u64::from(pair[0]) - u64::from(last.end) < 2) {
                continue;
            }
            let description: String = (0..random.random_range(0..=40))
                .map(|_| description_chars[random.random_range(0..description_chars.len())])
                .collect();
            written.push(range(pair[0], pair[1], random.random_range(0..=255), description.trim()));
        }

        assert_eq!(ip_filter_dat::read(&ip_filter_dat::write(&written), "ipfilter.dat"), written);
    }
}

#[test]
fn should_use_the_first_three_digits_of_a_longer_level() {
    let file = "001.002.003.000 - 001.002.003.255 , 0127 , Long level\n";

    assert_eq!(
        ip_filter_dat::read(file.as_bytes(), "ipfilter.dat"),
        [range(ip(1, 2, 3, 0), ip(1, 2, 3, 255), 12, "Long level")]
    );
}
