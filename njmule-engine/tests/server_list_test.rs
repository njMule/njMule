// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's ServerListTest, under their names; those of the IP filter wait for its port.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use std::fs;
use std::path::PathBuf;

use njmule_engine::persist::format::server_met::Server;
use njmule_engine::persist::format::text_files::{self, PRIORITY_NORMAL, StaticServer};
use njmule_engine::protocol::ed2k::server_datagrams;
use njmule_engine::protocol::tag::{Tag, TagName, server_tag};
use njmule_engine::server::server_list::{self, Address, Priority, ServerList};
use njmule_engine::server::server_session;

fn ip(text: &str) -> u32 {
    u32::from(text.parse::<std::net::Ipv4Addr>().unwrap())
}

fn server(at: &str, port: u16) -> Server {
    Server { ip: ip(at), port, tags: Vec::new() }
}

fn dynamic(host: &str, port: u16) -> Server {
    Server { ip: 0, port, tags: vec![server_list::string(server_tag::DYNIP, host)] }
}

fn tag(id: u8, value: u64) -> Tag {
    server_list::int(id, value)
}

fn address(at: &str, port: u16) -> Address {
    Address::new(ip(at), port)
}

fn addresses(servers: &[Server]) -> Vec<String> {
    servers.iter().map(|server| server_list::address_of(server).to_string()).collect()
}

struct Files {
    _directory: tempfile::TempDir,
    servers: PathBuf,
    statics: PathBuf,
}

fn files() -> Files {
    let directory = tempfile::tempdir().unwrap();
    let servers = directory.path().join(server_list::FILE_NAME);
    let statics = directory.path().join(server_list::STATIC_FILE_NAME);
    Files { _directory: directory, servers, statics }
}

const STATIC_LINES: [&str; 4] =
    ["# kept servers", "5.6.7.8:4242,2,Five", "8.7.6.5:4661,Eight", "host.example:4661,0,Named"];

fn statics_loaded(files: &Files) -> ServerList {
    let lines: Vec<String> = STATIC_LINES.iter().map(|line| (*line).to_owned()).collect();
    fs::write(&files.statics, text_files::write_lines(&lines)).unwrap();
    let mut list = ServerList::default();
    list.add(server("5.6.7.8", 4242).with(tag(server_tag::PREFERENCE, server_list::PRIORITY_HIGH)));
    list.load_statics(&files.statics).unwrap();
    list
}

#[test]
fn should_drop_the_control_characters_of_the_names_a_server_list_gives() {
    let mut list = ServerList::default();

    list.add_all([server("1.2.3.4", 4661)
        .with(server_list::string(server_tag::SERVERNAME, "\u{1b}[31mBig\u{7} server"))
        .with(server_list::string(server_tag::DESCRIPTION, "line\r\nbreak"))]);

    let kept = list.get(&address("1.2.3.4", 4661)).unwrap();
    assert_eq!(kept.name(), Some("[31mBig server"));
    assert_eq!(kept.description(), Some("linebreak"));
}

#[test]
fn should_keep_one_entry_per_address_and_reject_unusable_ones() {
    for (at, port) in [("1.2.3.4", 4661), ("1.2.3.4", 0), ("127.0.0.1", 4661), ("0.0.0.0", 4661), ("239.1.1.1", 4661)] {
        let mut list = ServerList::default();
        list.add(server("1.2.3.4", 4661));

        let added = list.add(server(at, port));

        assert!(!added, "{at}:{port}");
        assert_eq!(list.len(), 1);
    }
}

#[test]
fn should_offer_high_priority_and_reliable_servers_first() {
    let mut list = ServerList::default();
    for server in [
        server("1.1.1.1", 1).with(tag(server_tag::PREFERENCE, server_list::PRIORITY_LOW)),
        server("2.2.2.2", 2).with(tag(server_tag::FAIL, 3)),
        server("3.3.3.3", 3),
        server("4.4.4.4", 4).with(tag(server_tag::PREFERENCE, server_list::PRIORITY_HIGH)),
    ] {
        list.add(server);
    }

    let candidates = list.candidates();

    assert_eq!(addresses(&candidates), ["4.4.4.4:4", "3.3.3.3:3", "2.2.2.2:2", "1.1.1.1:1"]);
}

#[test]
fn should_save_updates_and_load_them_again() {
    let files = files();
    let mut list = ServerList::default();
    list.add_all([server("5.6.7.8", 4242), server("8.7.6.5", 4661)]);
    list.update(&address("5.6.7.8", 4242), |s| s.with(server_list::string(server_tag::SERVERNAME, "renamed")));

    list.save(&files.servers).unwrap();
    let loaded = ServerList::load(&files.servers).unwrap();

    assert_eq!(loaded.len(), 2);
    assert_eq!(loaded.get(&address("5.6.7.8", 4242)).unwrap().name(), Some("renamed"));
}

#[test]
fn should_remove_a_loaded_server() {
    let files = files();
    let mut list = ServerList::default();
    list.add_all([server("5.6.7.8", 4242), server("8.7.6.5", 4661)]);
    list.save(&files.servers).unwrap();
    let mut loaded = ServerList::load(&files.servers).unwrap();

    let removed = loaded.remove(&address("5.6.7.8", 4242));

    assert!(removed);
    assert!(loaded.get(&address("5.6.7.8", 4242)).is_none());
}

#[test]
fn should_take_the_static_servers_from_their_file_with_their_priority() {
    let files = files();

    let list = statics_loaded(&files);

    assert_eq!(list.len(), 3);
    assert!(list.is_static(&address("5.6.7.8", 4242)));
    assert!(list.is_static(&address("8.7.6.5", 4661)));
    assert_eq!(server_list::priority(list.get(&address("5.6.7.8", 4242)).unwrap()), Priority::Low);
    // No priority written: high.
    assert_eq!(server_list::priority(list.get(&address("8.7.6.5", 4661)).unwrap()), Priority::High);
    assert_eq!(list.get(&address("8.7.6.5", 4661)).unwrap().name(), Some("Eight"));
}

#[test]
fn should_write_the_static_servers_back_to_their_file() {
    let files = files();
    let mut list = statics_loaded(&files);
    list.set_static(&address("8.7.6.5", 4661), false);
    list.set_priority(&address("5.6.7.8", 4242), Priority::Normal);
    assert!(!list.set_priority(&address("9.9.9.9", 1), Priority::Low));

    list.save_statics(&files.statics).unwrap();

    let written = text_files::static_servers(&fs::read(&files.statics).unwrap());
    let entry = |host: &str, port, name: &str| StaticServer {
        host: host.to_owned(),
        port,
        priority: PRIORITY_NORMAL,
        name: name.to_owned(),
    };
    assert_eq!(written, [entry("5.6.7.8", 4242, "Five"), entry("host.example", 4661, "Named")]);
}

#[test]
fn should_keep_a_server_removed_from_the_static_servers_out_of_them() {
    let files = files();
    let mut list = statics_loaded(&files);
    list.save_statics(&files.statics).unwrap();
    list.remove(&address("5.6.7.8", 4242));

    let made = list.set_static(&address("5.6.7.8", 4242), true);

    // Unknown servers stay out.
    assert!(!made);
}

#[test]
fn should_keep_servers_known_by_host_name_by_it() {
    let files = files();
    let mut list = ServerList::default();
    assert!(list.add(dynamic("a.example", 4661)));
    assert!(list.add(dynamic("b.example", 4661)));
    // The same host.
    assert!(!list.add(dynamic("A.Example", 4661)));
    assert!(list.add(server("1.2.3.4", 4661)));

    list.save(&files.servers).unwrap();
    let loaded = ServerList::load(&files.servers).unwrap();

    assert_eq!(addresses(&loaded.all()), ["a.example:4661", "b.example:4661", "1.2.3.4:4661"]);
    assert_eq!(addresses(&loaded.candidates()), ["1.2.3.4:4661"]);
    assert_eq!(addresses(&loaded.connectable()), ["a.example:4661", "b.example:4661", "1.2.3.4:4661"]);
}

#[test]
fn should_write_the_static_servers_back_even_when_not_taken() {
    let files = files();
    let lines: Vec<String> =
        ["a.example:4661,1,A", "b.example:4661,1,B", "127.0.0.1:4661,0,Loopback", "1.2.3.4:4661,1,Kept"]
            .iter()
            .map(|line| (*line).to_owned())
            .collect();
    fs::write(&files.statics, text_files::write_lines(&lines)).unwrap();
    let mut list = ServerList::default();
    list.load_statics(&files.statics).unwrap();

    list.save_statics(&files.statics).unwrap();

    assert_eq!(list.len(), 3);
    let mut written: Vec<String> = text_files::lines(&fs::read(&files.statics).unwrap());
    let mut expected = lines;
    written.sort();
    expected.sort();
    assert_eq!(written, expected);
}

#[test]
fn should_not_let_a_server_name_add_lines_to_the_static_servers() {
    let files = files();
    let mut list = ServerList::default();
    list.add(server("1.2.3.4", 4661).with(Tag::String {
        name: TagName::id(server_tag::SERVERNAME),
        value: "Evil\r\n6.6.6.6:4661,0,Injected\u{7}".to_owned(),
    }));
    list.set_static(&address("1.2.3.4", 4661), true);

    list.save_statics(&files.statics).unwrap();

    let written = text_files::static_servers(&fs::read(&files.statics).unwrap());
    assert_eq!(
        written,
        [StaticServer {
            host: "1.2.3.4".to_owned(),
            port: 4661,
            priority: PRIORITY_NORMAL,
            name: "Evil6.6.6.6:4661,0,Injected".to_owned()
        }]
    );
}

#[test]
fn should_take_an_obfuscated_port_beyond_the_range_as_none() {
    let checked = server("1.2.3.4", 4661)
        .with(tag(server_tag::UDPFLAGS, u64::from(server_datagrams::UDP_TCP_OBFUSCATION)))
        .with(tag(server_tag::TCPPORTOBFUSCATION, 70_000));

    assert!(!server_list::supports_obfuscation(&checked));
}

fn failed_servers_and_a_static_one_that_failed_most() -> ServerList {
    let mut list = ServerList::default();
    for server in [
        server("1.1.1.1", 1).with(tag(server_tag::FAIL, 2)),
        server("2.2.2.2", 2).with(tag(server_tag::FAIL, 1)),
        server("3.3.3.3", 3),
        server("4.4.4.4", 4).with(tag(server_tag::FAIL, 5)),
    ] {
        list.add(server);
    }
    list.set_static(&address("4.4.4.4", 4), true);
    list
}

#[test]
fn should_find_the_servers_that_failed_often_enough_but_never_the_static_ones() {
    let dead = failed_servers_and_a_static_one_that_failed_most().dead(1);

    assert_eq!(dead.iter().map(ToString::to_string).collect::<Vec<_>>(), ["1.1.1.1:1", "2.2.2.2:2"]);
}

#[test]
fn should_not_find_the_servers_that_failed_too_few_times() {
    let dead = failed_servers_and_a_static_one_that_failed_most().dead(2);

    assert_eq!(dead.iter().map(ToString::to_string).collect::<Vec<_>>(), ["1.1.1.1:1"]);
}

#[test]
fn should_not_obfuscate_without_the_flag() {
    assert!(!server_list::supports_obfuscation(&server("1.2.3.4", 4661)));
}

#[test]
fn should_not_obfuscate_with_the_flag_but_no_port() {
    let checked =
        server("1.2.3.4", 4661).with(tag(server_tag::UDPFLAGS, u64::from(server_datagrams::UDP_TCP_OBFUSCATION)));

    assert!(!server_list::supports_obfuscation(&checked));
}

#[test]
fn should_obfuscate_with_the_flag_and_a_port() {
    let checked = server("1.2.3.4", 4661)
        .with(tag(server_tag::UDPFLAGS, u64::from(server_datagrams::UDP_TCP_OBFUSCATION)))
        .with(tag(server_tag::TCPPORTOBFUSCATION, 4662));

    assert!(server_list::supports_obfuscation(&checked));
}

#[test]
fn should_have_no_version_without_the_tag() {
    assert_eq!(server("1.2.3.4", 4661).version(), None);
}

#[test]
fn should_read_a_version_packed_in_a_number() {
    let checked = server("1.2.3.4", 4661).with(tag(server_tag::VERSION, 17 << 16 | 5));

    assert_eq!(checked.version().as_deref(), Some("17.05"));
}

#[test]
fn should_read_a_version_written_as_text() {
    let checked = server("1.2.3.4", 4661).with(server_list::string(server_tag::VERSION, "17.15 (lugdunum)"));

    assert_eq!(checked.version().as_deref(), Some("17.15 (lugdunum)"));
}

#[test]
fn should_read_the_version_a_server_tells_in_a_message_line() {
    for (line, expected) in [("server version 17.15 (lugdunum)", "17.15 (lugdunum)"), ("Server Version 17.5", "17.05")]
    {
        assert_eq!(server_session::version(line).as_deref(), Some(expected), "{line}");
    }
}

#[test]
fn should_find_no_version_in_other_message_lines() {
    for line in ["server version ", "Welcome to the server"] {
        assert_eq!(server_session::version(line), None, "{line}");
    }
}

#[test]
fn should_read_a_server_address_as_the_api_gives_it() {
    assert_eq!(Address::parse("192.168.1.20:31234"), Some(address("192.168.1.20", 31234)));
    assert_eq!(Address::parse("Server.Example:4661").map(|a| a.to_string()).as_deref(), Some("server.example:4661"));
    assert_eq!(Address::parse("1.2.3.4:0"), None);
    assert_eq!(Address::parse("no port"), None);
}

#[test]
fn should_neither_add_nor_offer_servers_the_filter_blocks() {
    let mut list = ServerList::default();
    list.add(server("1.2.3.4", 4661));
    list.add(server("5.6.7.8", 4661));
    let blocked = ip("5.6.7.8");
    list.filter(move |at| at == blocked);
    list.add(server("5.6.7.9", 4661));

    let added = list.add(server("5.6.7.8", 4662));

    assert!(!added);
    assert_eq!(addresses(&list.candidates()), ["1.2.3.4:4661", "5.6.7.9:4661"]);
    assert_eq!(list.len(), 3, "kept in the file");
}

// Beyond the Java engine's examples: the branches its other tests reach.

#[test]
fn should_name_the_priorities_and_read_no_address_from_other_text() {
    let names = [Priority::High, Priority::Normal, Priority::Low].map(Priority::name);
    let empty = ServerList::default();

    assert_eq!(names, ["high", "normal", "low"]);
    assert_eq!(Address::parse("not an address:4661"), None);
    assert!(empty.is_empty());
    assert!(server_list::is_lan(ip("192.168.1.2")) && !server_list::is_lan(ip("1.2.3.4")));
    assert!(format!("{empty:?}").contains("Blocked"));
}
