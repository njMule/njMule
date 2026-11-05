// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's Ed2kMessagesTest, under their names: login, id, status, identity, search
//! results, server lists, sources, the version tag and the hello. Its global search requests come with `search`.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

use njmule_engine::protocol::ed2k::client_version::{self, NJMULE_COMPAT};
use njmule_engine::protocol::ed2k::file_entry::FileEntry;
use njmule_engine::protocol::ed2k::hello::{
    Hello, MISC1_AICH_SHIFT, MISC1_NO_VIEW_SHARED_FILES, MISC1_UDP_VERSION_SHIFT, MISC1_UNICODE, MISC2_SUPPORTS_CRYPT,
    MOD_MISC_EXTENDED_SX, MOD_MISC_IPV6, MOD_MISC_QUIC_TRAVERSAL, MOD_MISC_UTP_TRAVERSAL, Ours,
};
use njmule_engine::protocol::ed2k::server_datagrams::{self, Status, UDP_OBFUSCATION};
use njmule_engine::protocol::ed2k::server_messages::{
    self, CallbackRequested, Endpoint, FoundSources, IdChange, Login, SearchResults, ServerIdent, ServerStatus, Source,
    TCP_COMPRESSION, TCP_LARGEFILES,
};
use njmule_engine::protocol::io::{PacketWriter, StringEncoding};
use njmule_engine::protocol::packet::opcodes::{server, server_udp};
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::tag_codec::write_ed2k_list;
use njmule_engine::protocol::tag::{Ed2kTagFormat, Tag, TagName, client_tag, file_tag, server_tag};
use njmule_engine::util::ipv4;

const HASH: [u8; 16] = [0x11; 16];

// Default preferences, nick "x", no server, Kad off.
const EMULE_HELLO: &str = concat!(
    "10",
    "11111111111111111111111111111111",
    "00000000",
    "3612",
    "06000000",
    "020100010100",
    "78",
    "030100113c000000",
    "030100f940120000",
    "030100fa16421334",
    "030100feba2d0000",
    "030100fb00200100",
    "00000000",
    "0000",
);

fn our_hello() -> Hello {
    let ours = Ours {
        nick: "njMule user",
        udp_port: 4672,
        kad_port: 0,
        misc_options1: MISC1_UNICODE | MISC1_NO_VIEW_SHARED_FILES,
        misc_options2: MISC2_SUPPORTS_CRYPT,
    };
    Hello::of(HASH, 0x0100_0001, 4662, &ours, ip("9.8.7.6"), 4661)
}

fn hello_on_the_port_with(tcp_port: u16, tags: Vec<Tag>) -> Hello {
    Hello { user_hash: HASH, client_id: 0, tcp_port, tags, server_ip: 0, server_port: 0 }
}

fn ipv6_tag(address: &str) -> Tag {
    Tag::Hash { name: TagName::id(client_tag::MOD_IP_V6), value: address.parse::<Ipv6Addr>().unwrap().octets() }
}

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

#[test]
fn should_read_the_hello_of_emule() {
    let hello = Hello::decode(&packet(0x01, EMULE_HELLO)).unwrap();

    assert_eq!(hello.user_hash, HASH);
    assert_eq!(hello.nick(), Some("x"));
    assert_eq!((hello.listen_port(), hello.udp_port(), hello.kad_port()), (4662, 4672, 0));
    assert_eq!((hello.misc_options1(), hello.misc_options2()), (0x3413_4216, 0x2DBA));
    assert_eq!(hello.kad_version(), 10);
    assert_eq!(hello.emule_version(), Some(0x0001_2000));
    assert_eq!(hello.server_ip, 0);
}

#[test]
fn should_keep_sixteen_bits_of_a_wider_listen_port_tag() {
    assert_eq!(hello_on_the_port_with(4662, vec![int(client_tag::PORT, 0x1_1234)]).listen_port(), 0x1234);
}

#[test]
fn should_keep_sixteen_bits_of_a_listen_port_tag_with_all_bits_set() {
    assert_eq!(hello_on_the_port_with(4662, vec![int(client_tag::PORT, u64::MAX)]).listen_port(), 0xFFFF);
}

#[test]
fn should_listen_on_the_tcp_port_without_a_listen_port_tag() {
    assert_eq!(hello_on_the_port_with(4662, Vec::new()).listen_port(), 4662);
}

#[test]
fn should_give_the_hello_answer_no_size_byte() {
    let hello = our_hello();

    assert_eq!(hello.encode(false).unwrap().length(), hello.encode(true).unwrap().length() + 1);
}

#[test]
fn should_round_trip_our_hello() {
    for answer in [false, true] {
        let decoded = Hello::decode(&our_hello().encode(answer).unwrap()).unwrap();

        assert_eq!(decoded, our_hello(), "answer {answer}");
        assert_eq!(decoded.server_ip, ip("9.8.7.6"), "answer {answer}");
    }
}

#[test]
fn should_tell_in_our_hello_that_we_take_ipv6() {
    let decoded = Hello::decode(&our_hello().encode(false).unwrap()).unwrap();

    assert_eq!(decoded.mod_misc_options(), MOD_MISC_IPV6);
    assert!(decoded.has_ipv4());
    assert!(!decoded.supports_extended_source_exchange());
}

#[test]
fn should_tell_router_traversal_in_the_hello_alongside_ipv6() {
    let hello = our_hello().with_router_traversal().with_ipv6(&"2001:db8:1:2::1".parse().unwrap());

    let decoded = Hello::decode(&hello.encode(false).unwrap()).unwrap();

    assert_eq!(
        decoded.mod_misc_options(),
        MOD_MISC_UTP_TRAVERSAL | MOD_MISC_QUIC_TRAVERSAL | MOD_MISC_IPV6 | MOD_MISC_EXTENDED_SX
    );
}

#[test]
fn should_carry_our_ipv6_address_in_the_hello() {
    let address: Ipv6Addr = "2001:db8:1:2::1".parse().unwrap();

    let decoded = Hello::decode(&our_hello().with_ipv6(&address).encode(true).unwrap()).unwrap();

    assert_eq!(decoded.ipv6(), Some(address));
    assert_eq!(decoded.mod_misc_options(), MOD_MISC_IPV6 | MOD_MISC_EXTENDED_SX);
    assert!(decoded.supports_extended_source_exchange());
}

#[test]
fn should_ignore_an_ipv6_address_in_the_hello_that_peers_cannot_reach() {
    for address in ["fe80::1", "fd00::1", "2002:102:304::1", "2001:0:4136:e378::1", "::1"] {
        assert_eq!(hello_on_the_port_with(4662, vec![ipv6_tag(address)]).ipv6(), None, "{address}");
    }
}

#[test]
fn should_tell_the_peer_the_ipv4_address_we_see_it_at() {
    let hello = our_hello();

    let addressed = hello.to_peer_at(&IpAddr::V4("1.2.3.4".parse().unwrap()));

    assert_eq!(addressed.tags.len(), hello.tags.len() + 1);
    assert_eq!(addressed.tags.last(), Some(&int(client_tag::MOD_YOUR_IP, u64::from(ipv4::to_ed2k(ip("1.2.3.4"))))));
}

#[test]
fn should_tell_the_peer_the_ipv6_address_we_see_it_at() {
    let hello = our_hello();
    let address: Ipv6Addr = "2001:db8::7".parse().unwrap();

    let addressed = hello.to_peer_at(&IpAddr::V6(address));

    assert_eq!(addressed.tags.len(), hello.tags.len() + 1);
    assert_eq!(
        addressed.tags.last(),
        Some(&Tag::Hash { name: TagName::id(client_tag::MOD_YOUR_IP), value: address.octets() })
    );
}

#[test]
fn should_tell_whether_a_client_has_an_ipv4_address() {
    for (client_id, expected) in [(4_294_967_295, false), (16_777_217, true), (5, true)] {
        let hello =
            Hello { user_hash: HASH, client_id, tcp_port: 4662, tags: Vec::new(), server_ip: 0, server_port: 0 };

        assert_eq!(hello.has_ipv4(), expected, "client id {client_id}");
    }
}

fn global_search(expression: &str, udp_flags: u32) -> Option<Packet> {
    let parsed = njmule_engine::search::search_parser::parse(expression).unwrap();
    server_datagrams::search_request(&parsed, udp_flags).unwrap()
}

#[test]
fn should_ask_a_server_with_large_files_and_extended_searches_the_third_global_search() {
    let request =
        global_search("ubuntu iso", server_datagrams::UDP_LARGEFILES | server_datagrams::UDP_EXT_GETFILES).unwrap();

    assert_eq!(request.opcode(), server_udp::GLOBSEARCHREQ3);
    assert!(hex::encode(request.payload()).starts_with("01000000890e01"));
}

#[test]
fn should_ask_a_server_with_extended_searches_the_second_global_search() {
    let request = global_search("ubuntu iso", server_datagrams::UDP_EXT_GETFILES).unwrap();

    assert_eq!(request.opcode(), server_udp::GLOBSEARCHREQ2);
}

#[test]
fn should_ask_a_server_without_flags_the_first_global_search() {
    let request = global_search("ubuntu iso", 0).unwrap();

    assert_eq!(request.opcode(), server_udp::GLOBSEARCHREQ);
}

#[test]
fn should_not_ask_a_server_without_large_files_for_large_files() {
    let request = global_search("movie @size>5g", server_datagrams::UDP_EXT_GETFILES);

    assert!(request.is_none());
}

// Every decoder reads garbage or refuses it as malformed: never a panic.
proptest::proptest! {
    #[test]
    fn should_only_fail_with_decode_exception_on_garbage(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=200)) {
        use njmule_engine::protocol::ed2k::server_messages as messages;
        let received = Packet::of(protocol::EDONKEY, 0x01, data);
        let _ = njmule_engine::protocol::ed2k::hello::Hello::decode(&received);
        let _ = messages::IdChange::decode(&received);
        let _ = messages::SearchResults::decode(&received);
        let _ = messages::ServerIdent::decode(&received);
        let _ = messages::decode_message(&received);
        let _ = messages::decode_server_list(&received);
        let _ = messages::CallbackRequested::decode(&received);
        let _ = Status::decode(&received);
        let _ = server_datagrams::decode_search_results(&received);
    }
}

#[test]
fn should_read_the_udp_version_and_aich_support_from_the_misc_options() {
    let options = 4u32 << MISC1_UDP_VERSION_SHIFT | 1 << MISC1_AICH_SHIFT;
    let hello = hello_on_the_port_with(4662, vec![int(client_tag::EMULE_MISCOPTIONS1, u64::from(options))]);
    let plain = hello_on_the_port_with(4662, Vec::new());

    assert_eq!((hello.udp_version(), hello.supports_aich()), (4, true));
    assert_eq!((plain.udp_version(), plain.supports_aich()), (0, false));
}

#[test]
fn should_read_a_64_bit_size_tag_without_its_high_half() {
    let entry = entry_with(vec![int(file_tag::FILESIZE, 5 << 32 | 7)]);

    assert_eq!(entry.size(), Some(5 << 32 | 7));
}

#[test]
fn should_have_no_aich_hash_of_the_wrong_length() {
    let entry = entry_with(vec![string(file_tag::AICH_HASH, "ABCDEFGH")]);

    assert_eq!(entry.aich_hash(), None);
}

#[test]
fn should_round_trip_an_id_change_with_the_reported_address() {
    let change = IdChange { client_id: 1234, server_flags: TCP_COMPRESSION, reported_ip: ip("198.51.100.20") };

    assert_eq!(IdChange::decode(&change.encode()), Ok(change));
}

#[test]
fn should_write_the_requests_of_a_server_session() {
    assert_eq!(server_messages::get_server_list(), packet(server::GETSERVERLIST, ""));
    assert_eq!(server_messages::query_more_results(), packet(server::QUERY_MORE_RESULT, ""));
    assert_eq!(server_messages::callback_request(0x0001_0203), packet(server::CALLBACKREQUEST, "03020100"));
    assert_eq!(server_messages::keep_alive(), packet(server::OFFERFILES, "00000000"));
}

#[test]
fn should_offer_files_with_the_tag_format_the_server_takes() {
    let entry = format!("01000000{}000000000000", hex::encode(HASH));

    let new = server_messages::offer_files(&[named_file()], true).unwrap();
    let old = server_messages::offer_files(&[named_file()], false).unwrap();

    assert_eq!(new, packet(server::OFFERFILES, &format!("{entry}01000000910161")));
    assert_eq!(old, packet(server::OFFERFILES, &format!("{entry}0100000002010001010061")));
}
