// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's Ed2kLinkTest, under their names: file, server, list, search and magnet links,
//! their optional parts and their bounds.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use njmule_engine::api::ed2k_link::{
    Ed2kLink, FileLink, MAX_FILE_SIZE, MAX_SOURCES, NodesListLink, SearchLink, ServerLink, ServerListLink, Source,
};

const HASH: &str = "31D6CFE0D16AE931B73C59D7E0C089C0";
const AICH: &str = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

fn hash() -> [u8; 16] {
    data_hash(HASH)
}

fn data_hash(hex: &str) -> [u8; 16] {
    hex::decode(hex).unwrap().try_into().unwrap()
}

fn file(link: &str) -> FileLink {
    match Ed2kLink::parse(link) {
        Ok(Ed2kLink::File(file)) => file,
        other => panic!("{link}: {other:?}"),
    }
}

fn source(host: &str, port: u16) -> Source {
    Source { host: host.to_owned(), port }
}

fn round_trip(link: &Ed2kLink) -> Ed2kLink {
    Ed2kLink::parse(&link.to_uri()).unwrap()
}

#[test]
fn should_parse_a_plain_file_link() {
    let file = file(&format!(" ed2k://|file|Ubuntu%2024.04.iso|5000000000|{HASH}|/ "));

    assert_eq!(file.name, "Ubuntu 24.04.iso");
    assert_eq!(file.size, 5_000_000_000);
    assert_eq!(file.hash, hash());
    assert_eq!(file.aich_hash, None);
    assert!(file.sources.is_empty());
}

#[test]
fn should_read_a_link_with_the_separators_a_browser_escaped() {
    let file = file(&format!("ed2k://%7Cfile%7CUbuntu%2024.04.iso%7C5000%7C{HASH}%7c/"));

    assert_eq!((file.name.as_str(), file.size), ("Ubuntu 24.04.iso", 5000));
}

#[test]
fn should_read_optional_parameters() {
    let file = file(&format!(
        "ed2k://|file|a.bin|19456001|{HASH}|h={AICH}|p={HASH}:{HASH}|s=http://example.org/a.bin|x=ignored|/"
    ));

    assert_eq!(file.aich_hash.map(|h| data_encoding::BASE32_NOPAD.encode(&h)).as_deref(), Some(AICH));
    assert_eq!(file.part_hashes.len(), 2);
    assert_eq!(file.http_sources, ["http://example.org/a.bin"]);
}

#[test]
fn should_drop_invalid_optional_parameters() {
    let file = file(&format!("ed2k://|file|a.bin|1|{HASH}|h=short|p={HASH}:zz|/"));

    assert_eq!(file.aich_hash, None);
    assert!(file.part_hashes.is_empty());
}

#[test]
fn should_drop_a_padded_aich_hash() {
    let base = format!("ed2k://|file|a.bin|1|{HASH}|h=");
    for link in [
        format!("{base}{}===|/", "A".repeat(29)),
        format!("{base}{}|/", "=".repeat(32)),
        format!("magnet:?xt=urn:ed2k:{HASH}&xl=1&dn=a&xt=urn:aich:{}====", "A".repeat(28)),
    ] {
        assert_eq!(file(&link).aich_hash, None, "{link}");
    }
}

#[test]
fn should_keep_only_web_sources_over_http_or_https() {
    let file = file(&format!(
        "ed2k://|file|a.bin|1|{HASH}|s=file:///etc/passwd|s=ftp://example.org/a|s=HTTPS://example.org/a|s=javascript:x|/"
    ));

    assert_eq!(file.http_sources, ["HTTPS://example.org/a"]);
}

#[test]
fn should_read_the_valid_sources() {
    let file =
        file(&format!("ed2k://|file|a.bin|100|{HASH}|/|sources,1.2.3.4:4662,host.example.org:4663,bad,5.6.7.8:0|/"));

    assert_eq!(file.sources, [source("1.2.3.4", 4662), source("host.example.org", 4663)]);
}

#[test]
fn should_read_an_ipv6_source_in_brackets() {
    let file = file(&format!("ed2k://|file|a.bin|100|{HASH}|/|sources,[2001:db8::1]:4662,[1.2.3.4]:4662,[x]:1|/"));

    assert_eq!(file.sources, [source("2001:db8::1", 4662)]);
}

#[test]
fn should_write_an_ipv6_source_in_brackets_and_read_it_back() {
    let written = Ed2kLink::File(FileLink {
        sources: vec![source("2001:db8::1", 4662)],
        ..FileLink::of("a.bin", 100, hash()).unwrap()
    });

    assert_eq!(round_trip(&written), written);
    assert!(written.to_uri().contains("|sources,[2001:db8::1]:4662|"));
}

#[test]
fn should_drop_sources_past_their_expiry() {
    assert!(file(&format!("ed2k://|file|a.bin|100|{HASH}|/|sources@000101,1.2.3.4:4662|/")).sources.is_empty());
}

#[test]
fn should_keep_sources_before_their_expiry() {
    assert_eq!(file(&format!("ed2k://|file|a.bin|100|{HASH}|/|sources@991231,1.2.3.4:4662|/")).sources.len(), 1);
}

#[test]
fn should_decode_utf8_names_and_keep_raw_unicode() {
    for (name, expected) in [("citt%C3%A0.txt", "città.txt"), ("città.txt", "città.txt")] {
        assert_eq!(file(&format!("ed2k://|file|{name}|1|{HASH}|/")).name, expected, "{name}");
    }
}

#[test]
fn should_drop_control_characters_from_the_name() {
    assert_eq!(file(&format!("ed2k://|file|a%0Ab|1|{HASH}|/")).name, "ab");
}

#[test]
fn should_reject_incomplete_or_invalid_links() {
    for link in [
        format!("ed2k://|file|a|1|{HASH}|"),
        format!("ed2k://|file|a|0|{HASH}|/"),
        format!("ed2k://|file|a|{}|{HASH}|/", MAX_FILE_SIZE + 1),
        "ed2k://|file|a|1|1234|/".to_owned(),
        "ed2k://|server|1.2.3.4|70000|/".to_owned(),
        "ed2k://|serverlist|file:///etc/passwd|/".to_owned(),
    ] {
        assert!(Ed2kLink::parse(&link).is_err(), "{link}");
    }
}

#[test]
fn should_find_no_link_in_an_address_of_another_kind() {
    assert_eq!(Ed2kLink::try_parse("http://example.org"), None);
}

#[test]
fn should_parse_a_server_link() {
    assert_eq!(
        Ed2kLink::parse("ed2k://|server|176.123.5.89|4725|/"),
        Ok(Ed2kLink::Server(ServerLink::new("176.123.5.89", 4725).unwrap()))
    );
}

#[test]
fn should_parse_a_server_list_link() {
    assert_eq!(
        Ed2kLink::parse("ed2k://|serverlist|http://example.org/server.met|/"),
        Ok(Ed2kLink::ServerList(ServerListLink::new("http://example.org/server.met").unwrap()))
    );
}

#[test]
fn should_parse_a_nodes_list_link() {
    assert_eq!(
        Ed2kLink::parse("ed2k://|nodeslist|https://example.org/nodes.dat|/"),
        Ok(Ed2kLink::NodesList(NodesListLink::new("https://example.org/nodes.dat").unwrap()))
    );
}

#[test]
fn should_parse_a_search_link() {
    assert_eq!(
        Ed2kLink::parse("ed2k://|search|free%20music|/"),
        Ok(Ed2kLink::Search(SearchLink::new("free music").unwrap()))
    );
}

#[test]
fn should_parse_an_ed2k_magnet_link() {
    let file = file(&format!(
        "magnet:?xt=urn:ed2k:{HASH}&xl=123&dn=a%20b.txt&xt=urn:aich:{AICH}&xs=ed2kftp://1.2.3.4:4662/{HASH}/123/\
         &as=http://example.org/x&tr=ignored"
    ));

    assert_eq!((file.name.as_str(), file.size), ("a b.txt", 123));
    assert_eq!(file.aich_hash.map(|h| data_encoding::BASE32_NOPAD.encode(&h)).as_deref(), Some(AICH));
    assert_eq!(file.sources, [source("1.2.3.4", 4662)]);
    assert_eq!(file.http_sources, ["http://example.org/x"]);
}

#[test]
fn should_reject_a_magnet_link_without_an_ed2k_hash() {
    assert!(Ed2kLink::parse("magnet:?xt=urn:btih:abc&dn=x").is_err());
}

#[test]
fn should_write_links_that_parse_back() {
    let aich: [u8; 20] = data_encoding::BASE32_NOPAD.decode(AICH.as_bytes()).unwrap().try_into().unwrap();
    let written = Ed2kLink::File(
        FileLink::new(
            "100% città | test.mkv".to_owned(),
            19_456_001,
            hash(),
            Some(aich),
            vec![hash(); 3],
            vec![source("1.2.3.4", 4662)],
            Vec::new(),
        )
        .unwrap(),
    );

    assert!(written.to_uri().starts_with("ed2k://|file|100%25%20citt%C3%A0%20%7C%20test.mkv|19456001|"));
    assert_eq!(round_trip(&written), written);
}

#[test]
fn should_round_trip_file_links() {
    for (name, size) in [("a", 1), ("film (2024) [1080p].mkv", 734_003_200), ("x y", MAX_FILE_SIZE)] {
        let written = Ed2kLink::File(FileLink::of(name, size, hash()).unwrap());

        assert_eq!(round_trip(&written), written, "{name}");
    }
}

#[test]
fn should_accept_the_largest_file_size() {
    assert_eq!(file(&format!("ed2k://|file|a|{}|{HASH}|/", 1u64 << 38)).size, 1 << 38);
}

#[test]
fn should_accept_a_lower_case_hash() {
    assert_eq!(file(&format!("ed2k://|file|a|1|{}|/", HASH.to_lowercase())).hash, hash());
}

#[test]
fn should_reject_sizes_hashes_and_ports_beyond_their_bounds() {
    for link in [
        format!("ed2k://|file|a|-1|{HASH}|/"),
        format!("ed2k://|file|a|+1|{HASH}|/"),
        format!("ed2k://|file|a| 1|{HASH}|/"),
        format!("ed2k://|file|a|1234567890123456|{HASH}|/"),
        format!("ed2k://|file|a|1|{}|/", &HASH[1..]),
        format!("ed2k://|file|a|1|{HASH}0|/"),
        format!("ed2k://|file|a|1|{}|/", "G".repeat(32)),
        format!("ed2k://|file||1|{HASH}|/"),
        format!("ed2k://|file| |1|{HASH}|/"),
        "ed2k://|server|1.2.3.4|0|/".to_owned(),
        "ed2k://|server|1.2.3.4|65536|/".to_owned(),
        "ed2k://|server|1.2.3.4|123456|/".to_owned(),
        "ed2k://|server|1.2.3.4|4661".to_owned(),
        "ed2k://|search| |/".to_owned(),
        format!("magnet:?xt=urn:ed2k:{HASH}&dn=a"),
        format!("magnet:?xt=urn:ed2k:{HASH}&xl=1&dn=%20"),
    ] {
        assert!(Ed2kLink::parse(&link).is_err(), "{link}");
    }
}

#[test]
fn should_accept_the_highest_port() {
    assert_eq!(
        Ed2kLink::parse("ed2k://|server|1.2.3.4|65535|/"),
        Ok(Ed2kLink::Server(ServerLink::new("1.2.3.4", 65535).unwrap()))
    );
}

#[test]
fn should_drop_bad_part_hashes_but_keep_the_link() {
    assert!(file(&format!("ed2k://|file|a.bin|100|{HASH}|p={HASH}::{HASH}|/")).part_hashes.is_empty());
}

#[test]
fn should_drop_bad_sources_but_keep_the_link() {
    let file = file(&format!(
        "ed2k://|file|a.bin|100|{HASH}|/|sources,1.2.3.4:0,1.2.3.4:65536,:4662,1.2.3.4:x,1.2.3.4:4662|/"
    ));

    assert_eq!(file.sources, [source("1.2.3.4", 4662)]);
}

#[test]
fn should_drop_sources_with_a_bad_expiry_but_keep_the_link() {
    for (expiry, reason) in [("991399", "an impossible date"), ("99", "a short date")] {
        let file = file(&format!("ed2k://|file|a.bin|100|{HASH}|/|sources@{expiry},1.2.3.4:4662|/"));

        assert!(file.sources.is_empty(), "{reason}");
    }
}

#[test]
fn should_keep_at_most_the_maximum_number_of_sources() {
    let many: String = (0..MAX_SOURCES + 10).map(|i| format!(",1.2.{}.{}:4662", i / 250, i % 250 + 1)).collect();

    assert_eq!(file(&format!("ed2k://|file|a.bin|100|{HASH}|/|sources{many}|/")).sources.len(), MAX_SOURCES);
}

#[test]
fn should_round_trip_names_of_any_script() {
    for name in ["città", "Ünïcödé ÿ", "日本語の名前", "Ελληνικά", "emoji 😀 name", "100%|50%"] {
        let written = Ed2kLink::File(FileLink::of(name, 1, hash()).unwrap());

        assert_eq!(round_trip(&written), written, "{name}");
    }
}

#[test]
fn should_round_trip_server_links() {
    for (host, port) in [("1.2.3.4", 1), ("host.example", 4661), ("a-b.c", 65535)] {
        let written = Ed2kLink::Server(ServerLink::new(host, port).unwrap());

        assert_eq!(round_trip(&written), written, "{host}:{port}");
    }
}
