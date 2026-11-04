// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's ServerSourceDatagramsTest, under their names.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use njmule_engine::protocol::ed2k::server_datagrams::{
    self, SourceQuery, UDP_EXT_GETSOURCES, UDP_EXT_GETSOURCES2, UDP_LARGEFILES,
};
use njmule_engine::protocol::ed2k::server_messages::{FoundSources, Source};
use njmule_engine::protocol::packet::opcodes::server_udp;
use njmule_engine::protocol::packet::{Packet, protocol};

const FIRST: [u8; 16] =
    [0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF];
const SECOND: [u8; 16] =
    [0xFF, 0xEE, 0xDD, 0xCC, 0xBB, 0xAA, 0x99, 0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0x00];
const SIZED: u32 = UDP_EXT_GETSOURCES | UDP_EXT_GETSOURCES2;
const FIVE_GB: u64 = 5 << 30;

fn query(file: [u8; 16], size: u64) -> SourceQuery {
    SourceQuery { file, size }
}

fn sources(file: [u8; 16], client_id: u32, port: u16) -> FoundSources {
    FoundSources { file, sources: vec![Source { client_id, port, crypt_options: 0, user_hash: None }] }
}

fn payloads(requests: &[Packet], opcode: u8) -> Vec<String> {
    assert!(requests.iter().all(|r| r.protocol() == protocol::EDONKEY && r.opcode() == opcode), "{requests:?}");
    requests.iter().map(|r| hex::encode(r.payload())).collect()
}

#[test]
fn should_ask_for_several_files_with_their_sizes_in_one_request_where_the_server_takes_it() {
    let requests = server_datagrams::source_requests(&[query(FIRST, 1000), query(SECOND, 0x0102_0304)], SIZED);

    assert_eq!(
        payloads(&requests, server_udp::GLOBGETSOURCES2),
        ["00112233445566778899aabbccddeeffe8030000ffeeddccbbaa9988776655443322110004030201"]
    );
}

#[test]
fn should_ask_for_one_file_and_no_size_per_request_where_the_server_takes_no_more() {
    let requests = server_datagrams::source_requests(&[query(FIRST, 1000), query(SECOND, 2000)], 0);

    assert_eq!(
        payloads(&requests, server_udp::GLOBGETSOURCES),
        ["00112233445566778899aabbccddeeff", "ffeeddccbbaa99887766554433221100"]
    );
}

#[test]
fn should_give_the_size_of_a_file_above_4_gb_in_64_bits_to_a_server_taking_large_files() {
    let requests = server_datagrams::source_requests(&[query(FIRST, FIVE_GB)], SIZED | UDP_LARGEFILES);

    assert_eq!(
        payloads(&requests, server_udp::GLOBGETSOURCES2),
        ["00112233445566778899aabbccddeeff000000000000004001000000"]
    );
}

#[test]
fn should_leave_a_file_above_4_gb_out_for_a_server_not_taking_large_files() {
    let requests = server_datagrams::source_requests(&[query(FIRST, FIVE_GB), query(SECOND, 1000)], SIZED);

    assert_eq!(payloads(&requests, server_udp::GLOBGETSOURCES2), ["ffeeddccbbaa99887766554433221100e8030000"]);
}

#[test]
fn should_start_another_request_beyond_510_bytes_of_file_ids() {
    let files: Vec<SourceQuery> = (0u128..30).map(|i| query(i.to_be_bytes(), 1000)).collect();

    let requests = server_datagrams::source_requests(&files, SIZED);

    assert_eq!(requests.iter().map(|r| r.length() / 20).collect::<Vec<_>>(), [25, 5]);
}

#[test]
fn should_read_the_sources_of_every_file_in_one_answer() {
    let answer = server_datagrams::found_sources(&[sources(FIRST, 0x0403_0201, 4662), sources(SECOND, 0x1234, 4663)]);

    assert_eq!(
        server_datagrams::decode_found_sources(&answer.unwrap()),
        Ok(vec![sources(FIRST, 0x0403_0201, 4662), sources(SECOND, 0x1234, 4663)])
    );
}

#[test]
fn should_drop_a_file_cut_short_at_the_end_of_an_answer() {
    let whole =
        server_datagrams::found_sources(&[sources(FIRST, 0x0403_0201, 4662), sources(SECOND, 0x0807_0605, 4663)])
            .unwrap()
            .into_payload();
    let answer = Packet::of(protocol::EDONKEY, server_udp::GLOBFOUNDSOURCES, &whole[..whole.len() - 1]);

    assert_eq!(server_datagrams::decode_found_sources(&answer), Ok(vec![sources(FIRST, 0x0403_0201, 4662)]));
}
