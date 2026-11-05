// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's OtherFilesTest, under their names: the Kad indexes, the search files, the text
//! files and the IP filter lists. The ini files, read only when importing a profile, are not ported yet.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::ip_filter_dat::{self, Range};
use njmule_engine::persist::format::kad_index_files::{
    self, Entry, IndexKey, Keyword, KeywordEntry, KeywordIndex, KeywordSource, Load, LoadIndex, NO_AICH, PublishedName,
    Publisher, Source, SourceIndex, Tracking,
};
use njmule_engine::persist::format::search_files::{self, SearchResult, StoredSearch};
use njmule_engine::persist::format::text_files::{self, PRIORITY_HIGH, PRIORITY_NORMAL, SharedFile, StaticServer};
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};
use njmule_engine::util::ipv4;
use rand::{Rng, RngExt};

const K1: UInt128 = UInt128(0x0102_0304_0506_0708_090A_0B0C_0D0E_0F10);
const K2: UInt128 = UInt128(0xF0E0_D0C0_B0A0_9080_7060_5040_3020_1000);
const IP_FILTER_DAT: &str = "# comment
001.002.003.000 - 001.002.003.255 , 000 , Bad people
10.0.0.0 - 10.0.0.255
10.0.1.0 - 10.0.1.255 , 100 , adjacent, same level
10.0.2.0 - 10.0.2.255 , 050 , adjacent, other level
1.2.3.0 - 1.2.3.255 , 020 , identical
";

fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

fn string(id: u8, value: &str) -> Tag {
    Tag::String { name: TagName::id(id), value: value.to_owned() }
}

fn ip(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from_be_bytes([a, b, c, d])
}

fn lines(lines: &[&str]) -> Vec<String> {
    lines.iter().map(|line| (*line).to_owned()).collect()
}

fn range(start: u32, end: u32, level: u32, description: &str) -> Range {
    Range { start, end, level, description: description.to_owned() }
}

#[test]
fn should_read_back_the_load_index_it_writes() {
    let index = LoadIndex { save_time: 1_700_000_000, loads: vec![Load { key: K1, time: 1_700_000_100 }] };
    let file = kad_index_files::write_load(&index).unwrap();

    assert_eq!(kad_index_files::read_load(&file).unwrap(), index);
}

#[test]
fn should_read_back_the_source_index_it_writes() {
    let index = SourceIndex {
        version: 2,
        expiry: 1_700_018_000,
        keys: vec![IndexKey {
            key: K1,
            sources: vec![Source {
                id: K2,
                entries: vec![Entry { lifetime: 1_700_001_000, tags: vec![int(file_tag::SOURCEPORT, 4662)] }],
            }],
        }],
    };
    let file = kad_index_files::write_sources(&index).unwrap();

    assert_eq!(kad_index_files::read_sources(&file).unwrap(), index);
}

#[test]
fn should_read_back_the_keyword_index_it_writes() {
    let tracking = Tracking {
        aich_hashes: vec![[0; 20]],
        names: vec![PublishedName { name: "Città.mkv".to_owned(), popularity: 3 }],
        publishers: vec![
            Publisher { ip: ip(1, 2, 3, 4), time: 1_700_000_500, aich_index: 0 },
            Publisher { ip: ip(5, 6, 7, 8), time: 1_700_000_600, aich_index: NO_AICH },
        ],
    };
    let index = KeywordIndex {
        version: 4,
        expiry: 1_700_086_400,
        own_id: K2,
        keywords: vec![Keyword {
            key: K1,
            sources: vec![KeywordSource {
                id: K2,
                entries: vec![KeywordEntry {
                    lifetime: 1_700_002_000,
                    tracking,
                    tags: vec![string(file_tag::FILENAME, "a.mkv")],
                }],
            }],
        }],
    };
    let file = kad_index_files::write_keywords(&index).unwrap();

    assert_eq!(kad_index_files::read_keywords(&file).unwrap(), index);
}

#[test]
fn should_read_a_keyword_index_of_version3_without_aich_hashes() {
    let mut id = PacketWriter::new();
    K1.write(&mut id);
    let id = hex::encode(id.into_byte_array());
    let file = hex::decode(
        "03000000".to_owned()
            + "10000000"
            + &id
            + "01000000"
            + &id
            + "01000000"
            + &id
            + "01000000"
            + "20000000" // lifetime
            + "00000000" // names
            + "01000000"
            + "04030201"
            + "30000000" // one publisher, no AICH index
            + "00", // no tags
    )
    .unwrap();

    let index = kad_index_files::read_keywords(&file).unwrap();

    assert_eq!(index.version, 3);
    let tracking = &index.keywords[0].sources[0].entries[0].tracking;
    assert!(tracking.aich_hashes.is_empty());
    assert_eq!(tracking.publishers, [Publisher { ip: ip(1, 2, 3, 4), time: 0x30, aich_index: NO_AICH }]);
}

#[test]
fn should_read_back_the_stored_searches_it_writes() {
    let result = SearchResult {
        file_hash: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
        client_id: ipv4::to_ed2k(ip(1, 2, 3, 4)),
        port: 4662,
        tags: vec![string(file_tag::FILENAME, "x.iso"), int(file_tag::FILESIZE, 123)],
    };
    let searches = vec![StoredSearch {
        id: 7,
        search_type: 3,
        client_shared_files: false,
        title: String::new(),
        expression: "linux iso".to_owned(),
        file_type: "Pro".to_owned(),
        results: vec![result],
    }];
    let file = search_files::write_stored(&searches).unwrap();

    assert_eq!(search_files::read_stored(&file).unwrap(), searches);
}

#[test]
fn should_read_back_the_spam_it_writes() {
    let spam = vec![string(search_files::SPAM_FULL_NAME, "fake.exe"), int(search_files::SPAM_SIZE, 1 << 33)];
    let file = search_files::write_spam(&spam).unwrap();

    assert_eq!(search_files::read_spam(&file).unwrap(), spam);
}

#[test]
fn should_write_text_files_in_utf16() {
    let file = text_files::write_lines(&lines(&["C:\\Città", "D:\\x"]));

    assert!(hex::encode(file).starts_with("fffe"));
}

#[test]
fn should_read_back_the_utf16_lines_it_writes() {
    let file = text_files::write_lines(&lines(&["C:\\Città", "D:\\x"]));

    assert_eq!(text_files::lines(&file), ["C:\\Città", "D:\\x"]);
}

#[test]
fn should_read_ansi_lines() {
    assert_eq!(text_files::lines(&[b'a', 0xE9, b'\r', b'\n', b'b']), ["aé", "b"]);
}

#[test]
fn should_read_static_servers_in_their_line_format() {
    let file = text_files::write_lines(&lines(&[
        "# comment",
        "server.example.org:4661,0,Normal one",
        "1.2.3.4:4242,5,Bad priority",
        "5.6.7.8:4243,Name, with comma",
        "nohostport",
    ]));

    let servers = text_files::static_servers(&file);

    let server = |host: &str, port, priority, name: &str| StaticServer {
        host: host.to_owned(),
        port,
        priority,
        name: name.to_owned(),
    };
    assert_eq!(
        servers,
        [
            server("server.example.org", 4661, PRIORITY_NORMAL, "Normal one"),
            server("1.2.3.4", 4242, PRIORITY_HIGH, "Bad priority"),
            server("5.6.7.8", 4243, PRIORITY_HIGH, "Name, with comma"),
        ]
    );
}

#[test]
fn should_read_back_the_static_servers_it_writes() {
    let servers = vec![StaticServer { host: "a.org".to_owned(), port: 1, priority: 2, name: "x".to_owned() }];
    let file = text_files::write_static_servers(&servers);

    assert_eq!(text_files::static_servers(&file), servers);
}

#[test]
fn should_mark_the_exclusions_of_the_shared_files() {
    let file = text_files::write_lines(&lines(&["C:\\a.txt", "-C:\\b.txt", ""]));

    assert_eq!(
        text_files::shared_files(&file),
        [
            SharedFile { path: "C:\\a.txt".to_owned(), excluded: false },
            SharedFile { path: "C:\\b.txt".to_owned(), excluded: true },
        ]
    );
}

#[test]
fn should_read_ip_filter_lines_with_levels_and_descriptions() {
    let ranges = ip_filter_dat::read(IP_FILTER_DAT.as_bytes(), "ipfilter.dat");

    assert_eq!(
        ranges,
        [
            range(ip(1, 2, 3, 0), ip(1, 2, 3, 255), 0, "Bad people"),
            range(ip(10, 0, 0, 0), ip(10, 0, 1, 255), 100, ""),
            range(ip(10, 0, 2, 0), ip(10, 0, 2, 255), 50, "adjacent, other level"),
        ]
    );
    assert!(ranges[0].contains(ip(1, 2, 3, 77)));
}

#[test]
fn should_read_back_the_ip_filter_it_writes() {
    let ranges = ip_filter_dat::read(IP_FILTER_DAT.as_bytes(), "ipfilter.dat");
    let file = ip_filter_dat::write(&ranges);

    assert_eq!(ip_filter_dat::read(&file, "ipfilter.dat"), ranges);
}

#[test]
fn should_read_a_peer_guardian_text_list() {
    let file = b"<b>Some list</b>Evil Corp PGIPDB:3.0.0.0-3.255.255.255\n";

    assert_eq!(ip_filter_dat::read(file, "list.txt"), [range(ip(3, 0, 0, 0), ip(3, 255, 255, 255), 100, "Evil Corp")]);
}

#[test]
fn should_read_a_peer_guardian_binary_list() {
    let file = hex::decode("ffffffff503242".to_owned() + "02" + "4e616d6500" + "05000000" + "050000ff").unwrap();

    assert_eq!(ip_filter_dat::read(&file, "list.p2b"), [range(ip(5, 0, 0, 0), ip(5, 0, 0, 255), 100, "Name")]);
}

#[test]
fn should_never_throw_from_text_parsers_and_only_fail_cleanly_from_binary_ones() {
    let mut random = rand::rng();
    for _ in 0..2_000 {
        let mut data = vec![0; random.random_range(0..=200)];
        random.fill_bytes(&mut data);

        let _ = text_files::lines(&data);
        let _ = text_files::static_servers(&data);
        let _ = text_files::shared_files(&data);
        let _ = text_files::entries(&data);
        let _ = ip_filter_dat::read(&data, "ipfilter.dat");
        let _ = ip_filter_dat::read(&data, "list.p2p");
        let _ = kad_index_files::read_load(&data);
        let _ = kad_index_files::read_sources(&data);
        let _ = kad_index_files::read_keywords(&data);
        let _ = search_files::read_stored(&data);
        let _ = search_files::read_spam(&data);
    }
}
