// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's Ed2kMessagesTest about servers, under their names: login, id, status,
//! identity, search results, server lists, sources and the version tag. Its peer messages come with their port.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::net::Ipv4Addr;

use njmule_engine::protocol::ed2k::client_version::{self, NJMULE_COMPAT};
use njmule_engine::protocol::ed2k::file_entry::FileEntry;
use njmule_engine::protocol::ed2k::server_datagrams::{self, Status, UDP_OBFUSCATION};
use njmule_engine::protocol::ed2k::server_messages::{
    self, CallbackRequested, Endpoint, FoundSources, IdChange, Login, SearchResults, ServerIdent, ServerStatus, Source,
    TCP_COMPRESSION, TCP_LARGEFILES,
};
use njmule_engine::protocol::io::{PacketWriter, StringEncoding};
use njmule_engine::protocol::packet::opcodes::server_udp;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::tag_codec::write_ed2k_list;
use njmule_engine::protocol::tag::{Ed2kTagFormat, Tag, TagName, file_tag, server_tag};
use njmule_engine::util::ipv4;

const HASH: [u8; 16] = [0x11; 16];

fn ip(text: &str) -> u32 {
    u32::from(text.parse::<Ipv4Addr>().unwrap())
}

fn packet(opcode: u8, hex: &str) -> Packet {
    Packet::of(protocol::EDONKEY, opcode, hex::decode(hex).unwrap())
}

fn named_file() -> FileEntry {
    FileEntry { hash: HASH, client_id: 0, port: 0, tags: vec![string(file_tag::FILENAME, "a")] }
}

fn string(id: u8, value: &str) -> Tag {
    Tag::String { name: TagName::id(id), value: value.to_owned() }
}

fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

fn entry_with(tags: Vec<Tag>) -> FileEntry {
    FileEntry { hash: HASH, client_id: 0, port: 0, tags }
}

fn write_file(out: &mut PacketWriter, hash: [u8; 16], name: &str, size: u64, sources: u64) {
    out.hash16(&hash).u32(0).u16(0);
    let tags = if size > u64::from(u32::MAX) {
        vec![
            string(file_tag::FILENAME, name),
            int(file_tag::FILESIZE, size & u64::from(u32::MAX)),
            int(file_tag::FILESIZE_HI, size >> 32),
            int(file_tag::SOURCES, sources),
        ]
    } else {
        vec![string(file_tag::FILENAME, name), int(file_tag::FILESIZE, size), int(file_tag::SOURCES, sources)]
    };
    out.count32(tags.len()).unwrap();
    write_ed2k_list(out, &tags, Ed2kTagFormat::New, StringEncoding::Utf8).unwrap();
}

fn write_file_cut_short(out: &mut PacketWriter, hash: [u8; 16], name: &str, size: u64, sources: u64) {
    let mut whole = PacketWriter::new();
    write_file(&mut whole, hash, name, size, sources);
    let bytes = whole.into_byte_array();
    out.bytes(&bytes[..bytes.len() - 3]);
}

fn names(files: &[FileEntry]) -> Vec<&str> {
    files.iter().map(|f| f.name().unwrap()).collect()
}

fn global_answer(out: PacketWriter) -> Packet {
    Packet::written(protocol::EDONKEY, server_udp::GLOBSEARCHRES, out)
}

#[test]
fn should_carry_the_four_login_tags_in_the_old_encoding() {
    let packet = Login { user_hash: HASH, client_id: 0, tcp_port: 4662, nick: "x".into(), capabilities: 0x0719 }
        .encode()
        .unwrap();

    assert_eq!(packet.opcode(), 0x01);
    assert_eq!(
        hex::encode(packet.payload()),
        [
            "11".repeat(16).as_str(),
            "00000000",
            "3612",
            "04000000",
            "0201000101",
            "0078",
            "030100113c000000",
            "0301002019070000",
            "030100fb",
            &hex::encode(client_version::nj_mule().to_le_bytes()),
        ]
        .concat()
    );
}

#[test]
fn should_read_a_low_id_change_without_the_optional_fields() {
    let id = IdChange::decode(&packet(0x40, "39300000")).unwrap();

    assert!(id.is_low_id());
    assert_eq!(id.server_flags, 0);
    assert_eq!(id.public_ip(), 0);
}

#[test]
fn should_read_the_optional_fields_of_a_high_id_change() {
    // HighID 1.2.3.4 (network order on the wire), flags, unused port, reported IP.
    let id = IdChange::decode(&packet(0x40, &["01020304", "19050000", "00000000", "05060708"].concat())).unwrap();

    assert!(!id.is_low_id());
    assert_eq!(id.public_ip(), ip("1.2.3.4"));
    assert!(id.has(TCP_COMPRESSION));
    assert!(id.has(TCP_LARGEFILES));
    assert_eq!(id.reported_ip, ip("5.6.7.8"));
}

#[test]
fn should_refuse_a_truncated_id_change() {
    assert!(IdChange::decode(&packet(0x40, "0102")).is_err());
}

#[test]
fn should_decode_a_server_status() {
    assert_eq!(
        ServerStatus::decode(&packet(0x34, "0a00000014000000")),
        Ok(Some(ServerStatus { users: 10, files: 20 }))
    );
}

#[test]
fn should_ignore_a_server_status_without_the_file_count() {
    assert_eq!(ServerStatus::decode(&packet(0x34, "0a000000")), Ok(None));
}

#[test]
fn should_decode_the_server_identity() {
    let mut out = PacketWriter::new();
    out.hash16(&HASH).u32(ipv4::to_ed2k(ip("9.8.7.6"))).u16(4661).u32(2);
    let tags = [string(server_tag::SERVERNAME, "Test server"), string(server_tag::DESCRIPTION, "for tests")];
    write_ed2k_list(&mut out, &tags, Ed2kTagFormat::New, StringEncoding::Utf8).unwrap();

    let ident = ServerIdent::decode(&Packet::written(protocol::EDONKEY, 0x41, out)).unwrap();

    assert_eq!(ident.ip, ip("9.8.7.6"));
    assert_eq!(ident.name.as_deref(), Some("Test server"));
    assert_eq!(ident.description.as_deref(), Some("for tests"));
}

#[test]
fn should_read_the_files_and_the_more_flag_of_search_results() {
    let mut out = PacketWriter::new();
    out.u32(2);
    write_file(&mut out, HASH, "ubuntu.iso", 5_000_000_000, 42);
    write_file(&mut out, [0x22; 16], "small.txt", 3, 1);
    out.u8(1);

    let results = SearchResults::decode(&Packet::written(protocol::EDONKEY, 0x33, out)).unwrap();

    assert!(results.more);
    assert_eq!(results.files.len(), 2);
    assert_eq!(results.files[0].name(), Some("ubuntu.iso"));
    assert_eq!(results.files[0].size(), Some(5_000_000_000));
    assert_eq!(results.files[0].sources(), 42);
    assert_eq!(results.files[1].size(), Some(3));
}

#[test]
fn should_refuse_search_results_announcing_more_files_than_they_carry() {
    let mut out = PacketWriter::new();
    out.u32(1000);

    assert!(SearchResults::decode(&Packet::written(protocol::EDONKEY, 0x33, out)).is_err());
}

#[test]
fn should_keep_the_results_before_the_cut_of_a_search_answer() {
    let mut out = PacketWriter::new();
    out.u32(3);
    write_file(&mut out, HASH, "a", 1, 1);
    write_file(&mut out, [0x22; 16], "b", 2, 2);
    write_file_cut_short(&mut out, [0x33; 16], "c", 3, 3);

    let results = SearchResults::decode(&Packet::written(protocol::EDONKEY, 0x33, out)).unwrap();

    assert_eq!(names(&results.files), ["a", "b"]);
    assert!(!results.more);
}

#[test]
fn should_keep_the_results_before_the_cut_of_a_global_search_answer() {
    let mut out = PacketWriter::new();
    write_file(&mut out, HASH, "a", 1, 1);
    out.u8(protocol::EDONKEY).u8(server_udp::GLOBSEARCHRES);
    write_file(&mut out, [0x22; 16], "b", 2, 2);
    out.u8(protocol::EDONKEY).u8(server_udp::GLOBSEARCHRES);
    write_file_cut_short(&mut out, [0x33; 16], "c", 3, 3);

    let files = server_datagrams::decode_search_results(&global_answer(out)).unwrap();

    assert_eq!(names(&files), ["a", "b"]);
}

#[test]
fn should_encode_a_server_list_of_255_servers() {
    let servers = vec![Endpoint { ip: ip("1.1.1.1"), port: 4242 }; 255];

    assert!(server_messages::server_list(&servers).is_ok());
}

#[test]
fn should_refuse_to_encode_a_server_list_of_256_servers() {
    let servers = vec![Endpoint { ip: ip("1.1.1.1"), port: 4242 }; 256];

    assert!(server_messages::server_list(&servers).is_err());
}

#[test]
fn should_refuse_to_encode_found_sources_of_256_sources() {
    let source = Source { client_id: 7, port: 1, crypt_options: 0, user_hash: None };
    let found = FoundSources { file: HASH, sources: vec![source; 256] };

    assert!(found.encode().is_err());
}

#[test]
fn should_keep_negative_version_parts_out_of_the_compatible_client_bits() {
    assert_eq!(client_version::encode(NJMULE_COMPAT, -1, -1, -1), u32::from(NJMULE_COMPAT) << 24);
}

#[test]
fn should_keep_oversized_version_parts_out_of_the_compatible_client_bits() {
    assert_eq!(client_version::encode(NJMULE_COMPAT, 1000, 1000, 1000) >> 24, u32::from(NJMULE_COMPAT));
}

#[test]
fn should_split_server_messages_into_lines() {
    let packet = server_messages::message("server version 17.15\r\n\r\nWelcome!\nERROR: x").unwrap();

    assert_eq!(server_messages::decode_message(&packet).unwrap(), ["server version 17.15", "Welcome!", "ERROR: x"]);
}

#[test]
fn should_decode_a_server_list() {
    assert_eq!(
        server_messages::decode_server_list(&packet(0x32, "01010203043512")),
        Ok(vec![Endpoint { ip: ip("1.2.3.4"), port: 4661 }])
    );
}

#[test]
fn should_refuse_a_server_list_announcing_more_servers_than_it_carries() {
    assert!(server_messages::decode_server_list(&packet(0x32, "0501020304")).is_err());
}

#[test]
fn should_decode_a_callback_request_without_a_user_hash() {
    let callback = CallbackRequested::decode(&packet(0x35, "0a0000013612")).unwrap();

    assert_eq!(callback.endpoint, Endpoint { ip: ip("10.0.0.1"), port: 4662 });
    assert_eq!(callback.user_hash, None);
}

#[test]
fn should_decode_a_callback_request_with_the_user_hash() {
    let callback = CallbackRequested::decode(&packet(0x35, &format!("0a000001361281{}", "11".repeat(16)))).unwrap();

    assert_eq!(callback.crypt_options, 0x81);
    assert_eq!(callback.user_hash, Some(HASH));
}

#[test]
fn should_put_the_challenge_in_a_status_request() {
    assert_eq!(hex::encode(server_datagrams::status_request(0x55AA_1234).payload()), "3412aa55");
}

#[test]
fn should_decode_a_minimal_status_answer() {
    let status = Status::decode(&packet(0x97, "3412aa5564000000c8000000")).unwrap();

    assert_eq!((status.users, status.files), (100, 200));
    assert_eq!(status.udp_flags, 0);
}

#[test]
fn should_decode_a_full_status_answer() {
    let status = Status::decode(&packet(
        0x97,
        &[
            "3412aa55", "64000000", "c8000000", "e8030000", "10270000", "20270000", "0b030000", "05000000", "4112",
            "4012", "efbeadde",
        ]
        .concat(),
    ))
    .unwrap();

    assert_eq!(status.max_users, 1000);
    assert!(status.has(UDP_OBFUSCATION));
    assert_eq!((status.obfuscated_udp_port, status.obfuscated_tcp_port), (0x1241, 0x1240));
    assert_eq!(status.udp_key, 0xDEAD_BEEF);
}

#[test]
fn should_refuse_a_truncated_status_answer() {
    assert!(Status::decode(&packet(0x97, "3412aa55")).is_err());
}

#[test]
fn should_read_several_results_from_one_global_answer() {
    let mut out = PacketWriter::new();
    write_file(&mut out, HASH, "a", 1, 1);
    out.u8(protocol::EDONKEY).u8(server_udp::GLOBSEARCHRES);
    write_file(&mut out, [0x33; 16], "b", 2, 2);
    out.u8(0x00);

    let files = server_datagrams::decode_search_results(&global_answer(out)).unwrap();

    assert_eq!(names(&files), ["a", "b"]);
}

#[test]
fn should_saturate_source_counts_beyond_the_signed_range() {
    let entry = entry_with(vec![int(file_tag::SOURCES, u64::MAX), int(file_tag::COMPLETE_SOURCES, 1 << 63)]);

    assert_eq!(entry.sources(), i32::MAX.unsigned_abs());
    assert_eq!(entry.complete_sources(), i32::MAX.unsigned_abs());
}

#[test]
fn should_saturate_a_source_count_beyond_thirty_two_bits() {
    assert_eq!(entry_with(vec![int(file_tag::SOURCES, 1 << 32)]).sources(), i32::MAX.unsigned_abs());
}

#[test]
fn should_count_no_sources_without_the_source_tags() {
    let entry = entry_with(Vec::new());

    assert_eq!((entry.sources(), entry.complete_sources()), (0, 0));
}

#[test]
fn should_round_trip_a_login() {
    let sent =
        Login { user_hash: HASH, client_id: 0x0100_0001, tcp_port: 4662, nick: "nick".into(), capabilities: 0x719 };

    assert_eq!(Login::decode(&sent.encode().unwrap()), Ok(sent));
}

#[test]
fn should_round_trip_a_high_id_change() {
    let sent = IdChange { client_id: ipv4::to_ed2k(ip("1.2.3.4")), server_flags: 0x519, reported_ip: ip("1.2.3.4") };

    assert_eq!(IdChange::decode(&sent.encode()), Ok(sent));
}

#[test]
fn should_round_trip_a_low_id_change() {
    let sent = IdChange { client_id: 12_345, server_flags: 0x19, reported_ip: ip("5.6.7.8") };

    assert_eq!(IdChange::decode(&sent.encode()), Ok(sent));
}

#[test]
fn should_round_trip_a_server_status() {
    let sent = ServerStatus { users: 1, files: 2 };

    assert_eq!(ServerStatus::decode(&sent.encode()), Ok(Some(sent)));
}

#[test]
fn should_round_trip_a_server_identity() {
    let sent = ServerIdent { hash: HASH, ip: ip("9.8.7.6"), port: 4661, name: Some("name".into()), description: None };

    assert_eq!(ServerIdent::decode(&sent.encode().unwrap()), Ok(sent));
}

#[test]
fn should_round_trip_search_results() {
    for more in [true, false] {
        let sent = SearchResults { files: vec![named_file(), named_file()], more };

        assert_eq!(SearchResults::decode(&sent.encode().unwrap()), Ok(sent));
    }
}

#[test]
fn should_round_trip_a_server_message() {
    let packet = server_messages::message("one\ntwo").unwrap();

    assert_eq!(server_messages::decode_message(&packet).unwrap(), ["one", "two"]);
}

#[test]
fn should_round_trip_a_server_list() {
    let servers = [Endpoint { ip: ip("1.1.1.1"), port: 4242 }];

    assert_eq!(
        server_messages::decode_server_list(&server_messages::server_list(&servers).unwrap()),
        Ok(servers.to_vec())
    );
}

#[test]
fn should_round_trip_a_full_status() {
    let sent = Status {
        challenge: 7,
        users: 1,
        files: 2,
        max_users: 3,
        soft_files: 4,
        hard_files: 5,
        udp_flags: 0x30B,
        low_id_users: 6,
        obfuscated_udp_port: 4665,
        obfuscated_tcp_port: 4667,
        udp_key: 0xCAFE,
    };

    assert_eq!(Status::decode(&sent.encode()), Ok(sent));
}

#[test]
fn should_round_trip_global_search_results() {
    let sent = vec![named_file(), named_file(), named_file()];

    assert_eq!(server_datagrams::decode_search_results(&server_datagrams::search_results(&sent).unwrap()), Ok(sent));
}

#[test]
fn should_ask_for_the_sources_of_a_file() {
    let packet = server_messages::get_sources(&HASH, 1000, false);

    assert_eq!(hex::encode(packet.payload()), format!("{}e8030000", "11".repeat(16)));
}

#[test]
fn should_ask_for_the_obfuscated_sources_of_a_large_file() {
    let packet = server_messages::get_sources(&HASH, 5_000_000_000, true);

    assert_eq!(packet.opcode(), 0x23);
    assert_eq!(hex::encode(packet.payload()), format!("{}0000000000f2052a01000000", "11".repeat(16)));
}

#[test]
fn should_decode_found_sources() {
    let found = FoundSources::decode(&packet(0x42, &format!("{}02010203043612393000003712", "11".repeat(16)))).unwrap();

    assert_eq!(
        found.sources,
        [
            Source { client_id: 0x0403_0201, port: 4662, crypt_options: 0, user_hash: None },
            Source { client_id: 12_345, port: 4663, crypt_options: 0, user_hash: None },
        ]
    );
    assert!(found.sources[1].is_low_id());
}

#[test]
fn should_round_trip_obfuscated_found_sources() {
    let sent = FoundSources {
        file: HASH,
        sources: vec![
            Source { client_id: 0x0403_0201, port: 4662, crypt_options: 0x01, user_hash: None },
            Source { client_id: 7, port: 1, crypt_options: 0x83, user_hash: Some(HASH) },
        ],
    };

    assert_eq!(FoundSources::decode(&sent.encode().unwrap()), Ok(sent));
}

#[test]
fn should_refuse_found_sources_announcing_more_sources_than_they_carry() {
    assert!(FoundSources::decode(&packet(0x42, &format!("{}050102", "11".repeat(16)))).is_err());
}

#[test]
fn should_carry_the_njmule_compatible_client_id_in_a_pre_release_version() {
    assert_eq!(client_version::from_sem_ver("0.0.1-alpha.1"), 0x6E00_0080);
}

#[test]
fn should_carry_the_njmule_compatible_client_id_in_a_release_version() {
    assert_eq!(client_version::from_sem_ver("1.2.3"), 0x6E00_0000 | 1 << 17 | 2 << 10 | 3 << 7);
}

#[test]
fn should_carry_the_njmule_compatible_client_id_alone_for_an_unreadable_version() {
    assert_eq!(client_version::from_sem_ver("garbage"), 0x6E00_0000);
}
