// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's ClientMessagesTest, under their names: file status, part requests, data and
//! compressed chunks, file names, hash sets and queue ranks.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::net::Ipv6Addr;

use njmule_engine::files::hash::ed2k::{EMBLOCKSIZE, PARTSIZE};
use njmule_engine::protocol::ed2k::client_messages::{
    self, CompressedPart, FileName, FileStatus, HashSet, PartStatus, Range, RequestParts, SendingPart,
};
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, protocol};

const FILE: [u8; 16] = [0xAB; 16];
const FOUR_GIB: u64 = 1 << 32;

fn status_of(part_count: usize, parts: &[usize]) -> FileStatus {
    FileStatus { file: FILE, part_count, parts: (0..part_count).map(|part| parts.contains(&part)).collect() }
}

fn range(start: u64, end: u64) -> Range {
    Range { start, end }
}

fn request(ranges: [Range; 3]) -> RequestParts {
    RequestParts { file: FILE, ranges }
}

fn part(start: u64, end: u64, data: &[u8]) -> SendingPart {
    SendingPart { file: FILE, start, end, data: data.to_vec() }
}

fn chunk(block_start: u64, compressed_size: u32, data: &[u8]) -> CompressedPart {
    CompressedPart { file: FILE, block_start, compressed_size, chunk: data.to_vec() }
}

fn narrow_request_of(start: u32, end: u32) -> Packet {
    let mut out = PacketWriter::new();
    out.hash16(&FILE).u32(start).u32(0).u32(0).u32(end).u32(0).u32(0);
    Packet::written(protocol::EDONKEY, client::REQUESTPARTS, out)
}

fn requested(packet: &Packet) -> Vec<Range> {
    RequestParts::decode(packet).unwrap().ranges.into_iter().filter(|r| !r.is_empty()).collect()
}

#[test]
fn should_tell_a_new_ipv6_address_in_sixteen_bytes() {
    let address: Ipv6Addr = "2001:db8::42".parse().unwrap();
    let packet = client_messages::ipv6_changed(&address);

    assert_eq!((packet.protocol(), packet.opcode(), packet.length()), (protocol::EDONKEY, 0xAC, 16));
    assert_eq!(client_messages::changed_ipv6(&packet), Ok(Some(address)));
}

#[test]
fn should_ignore_a_new_ipv6_address_no_peer_can_reach() {
    let packet = Packet::of(protocol::EDONKEY, 0xAC, "fe80::1".parse::<Ipv6Addr>().unwrap().octets());

    assert_eq!(client_messages::changed_ipv6(&packet), Ok(None));
}

#[test]
fn should_refuse_a_new_ipv6_address_cut_short() {
    assert!(client_messages::changed_ipv6(&Packet::of(protocol::EDONKEY, 0xAC, [0; 15])).is_err());
}

#[test]
fn should_encode_the_file_status_bits_from_the_lowest_bit() {
    let packet = status_of(10, &[0, 2, 9]).encode().unwrap();

    assert_eq!(hex::encode(packet.payload()), format!("{}0a000502", "ab".repeat(16)));
}

#[test]
fn should_decode_the_file_status_bits_from_the_lowest_bit() {
    let decoded = FileStatus::decode(&status_of(10, &[0, 2, 9]).encode().unwrap()).unwrap();

    assert!(decoded.has(2));
    assert!(!decoded.has(1));
    assert!(decoded.has(9));
}

#[test]
fn should_decode_the_file_status_it_encodes() {
    for part_count in [1, 7, 8, 9, 63, 400] {
        let parts: Vec<usize> = (0..part_count).filter(|part| part % 3 == 0 || part % 7 == 1).collect();
        let sent = status_of(part_count, &parts);

        assert_eq!(FileStatus::decode(&sent.encode().unwrap()), Ok(sent), "{part_count} parts");
    }
}

#[test]
fn should_ignore_the_padding_bits_of_the_last_byte() {
    let sent = status_of(10, &[0, 2, 9]);
    let mut payload = sent.encode().unwrap().into_payload();
    *payload.last_mut().unwrap() |= 0xFC;

    assert_eq!(FileStatus::decode(&Packet::of(protocol::EDONKEY, client::FILESTATUS, payload)), Ok(sent));
}

#[test]
fn should_decode_a_file_status_without_parts_as_complete() {
    let decoded = FileStatus::decode(&status_of(0, &[]).encode().unwrap()).unwrap();

    assert!(decoded.has(123));
    assert_eq!(decoded.data_parts(3 * 9_728_000).iter().filter(|has| **has).count(), 3);
}

#[test]
fn should_fit_any_size_with_a_file_status_without_parts() {
    assert!(status_of(0, &[]).fits(123));
}

#[test]
fn should_fit_a_size_of_its_part_count() {
    assert!(status_of(3, &[]).fits(2 * PARTSIZE + 1));
}

#[test]
fn should_fit_a_size_whose_empty_last_part_counts() {
    assert!(status_of(3, &[]).fits(2 * PARTSIZE), "the empty last part counts");
}

#[test]
fn should_not_fit_a_size_of_fewer_parts() {
    assert!(!status_of(3, &[]).fits(PARTSIZE));
}

#[test]
fn should_not_fit_a_size_of_more_parts() {
    assert!(!status_of(3, &[]).fits(3 * PARTSIZE));
}

#[test]
fn should_encode_a_narrow_part_request() {
    let packet = request([range(0, 184_320), range(184_320, 368_640), range(0, 0)]).encode(false).unwrap();

    assert_eq!(packet.opcode(), client::REQUESTPARTS);
    assert_eq!(packet.length(), 16 + 6 * 4);
}

#[test]
fn should_encode_a_wide_part_request() {
    let packet = request([range(0, 184_320), range(184_320, 368_640), range(0, 0)]).encode(true).unwrap();

    assert_eq!(packet.protocol(), protocol::EMULE);
    assert_eq!(packet.length(), 16 + 6 * 8);
}

#[test]
fn should_round_trip_a_part_request_in_both_widths() {
    for wide in [false, true] {
        let sent = request([range(0, 184_320), range(184_320, 368_640), range(0, 0)]);

        assert_eq!(RequestParts::decode(&sent.encode(wide).unwrap()), Ok(sent), "wide {wide}");
    }
}

#[test]
fn should_round_trip_a_part_of_data_in_both_widths() {
    for wide in [false, true] {
        let decoded = SendingPart::decode(&part(10, 13, &[1, 2, 3]).encode(wide).unwrap()).unwrap();

        assert_eq!((decoded.start, decoded.end), (10, 13), "wide {wide}");
        assert_eq!(decoded.data, [1, 2, 3], "wide {wide}");
    }
}

#[test]
fn should_refuse_a_part_of_data_shorter_than_its_range() {
    let packet =
        Packet::of(protocol::EDONKEY, 0x46, hex::decode(format!("{}0a0000000d00000001", "ab".repeat(16))).unwrap());

    assert!(SendingPart::decode(&packet).is_err());
}

#[test]
fn should_encode_a_narrow_compressed_chunk() {
    let packet = chunk(184_320, 5, &[9, 8, 7]).encode(false).unwrap();

    assert_eq!(packet.opcode(), extended::COMPRESSEDPART);
    assert_eq!(hex::encode(packet.payload()), format!("{}00d0020005000000090807", "ab".repeat(16)));
}

#[test]
fn should_encode_a_wide_compressed_chunk() {
    let packet = chunk(184_320, 5, &[9, 8, 7]).encode(true).unwrap();

    assert_eq!(packet.opcode(), extended::COMPRESSEDPART_I64);
    assert_eq!(packet.length(), 16 + 8 + 4 + 3);
}

#[test]
fn should_round_trip_a_compressed_chunk_in_both_widths() {
    for wide in [false, true] {
        let decoded = CompressedPart::decode(&chunk(184_320, 5, &[9, 8, 7]).encode(wide).unwrap()).unwrap();

        assert_eq!((decoded.block_start, decoded.compressed_size), (184_320, 5), "wide {wide}");
        assert_eq!(decoded.chunk, [9, 8, 7], "wide {wide}");
    }
}

#[test]
fn should_refuse_a_compressed_chunk_larger_than_the_compressed_size() {
    assert!(CompressedPart::decode(&chunk(0, 2, &[1, 2, 3]).encode(false).unwrap()).is_err());
}

#[test]
fn should_refuse_a_narrow_part_request_beyond_four_gigabytes() {
    assert!(request([range(FOUR_GIB - 10, FOUR_GIB + 10), range(0, 0), range(0, 0)]).encode(false).is_err());
}

#[test]
fn should_refuse_a_narrow_part_of_data_beyond_four_gigabytes() {
    assert!(part(FOUR_GIB, FOUR_GIB + 1, &[1]).encode(false).is_err());
}

#[test]
fn should_refuse_a_narrow_compressed_chunk_beyond_four_gigabytes() {
    assert!(chunk(FOUR_GIB, 5, &[1]).encode(false).is_err());
}

#[test]
fn should_carry_a_part_request_beyond_four_gigabytes_in_the_wide_form() {
    let sent = request([range(FOUR_GIB - 10, FOUR_GIB + 10), range(0, 0), range(0, 0)]);

    assert_eq!(RequestParts::decode(&sent.encode(true).unwrap()), Ok(sent));
}

#[test]
fn should_carry_a_part_of_data_beyond_four_gigabytes_in_the_wide_form() {
    let sent = part(FOUR_GIB, FOUR_GIB + 1, &[1]);

    assert_eq!(SendingPart::decode(&sent.encode(true).unwrap()), Ok(sent));
}

#[test]
fn should_carry_a_compressed_chunk_beyond_four_gigabytes_in_the_wide_form() {
    let sent = chunk(FOUR_GIB, 5, &[1]);

    assert_eq!(CompressedPart::decode(&sent.encode(true).unwrap()), Ok(sent));
}

#[test]
fn should_carry_a_part_of_data_ending_at_the_last_narrow_offset_in_the_narrow_form() {
    let sent = part(0xFFFF_FFFE, 0xFFFF_FFFF, &[1]);

    assert_eq!(SendingPart::decode(&sent.encode(false).unwrap()), Ok(sent));
}

#[test]
fn should_read_a_narrow_range_whose_end_wrapped_to_zero_as_ending_at_four_gigabytes() {
    let start = u32::try_from(FOUR_GIB - EMBLOCKSIZE).unwrap();

    assert_eq!(requested(&narrow_request_of(start, 0)), [range(FOUR_GIB - EMBLOCKSIZE, FOUR_GIB)]);
}

#[test]
fn should_read_a_narrow_range_ending_before_its_start_as_nothing() {
    assert_eq!(requested(&narrow_request_of(5000, 4000)), []);
}

#[test]
fn should_refuse_a_file_status_whose_part_count_does_not_fit_sixteen_bits() {
    assert!(status_of(0x10000, &[]).encode().is_err());
}

#[test]
fn should_refuse_a_hash_set_whose_part_count_does_not_fit_sixteen_bits() {
    assert!(HashSet { file: FILE, parts: vec![FILE; 0x10000] }.encode().is_err());
}

#[test]
fn should_round_trip_a_file_name() {
    let sent = FileName { file: FILE, name: "ubuntu.iso".into() };

    assert_eq!(FileName::decode(&sent.encode().unwrap()), Ok(sent));
}

#[test]
fn should_round_trip_a_hash_set() {
    let sent = HashSet { file: FILE, parts: vec![FILE, [0; 16]] };

    assert_eq!(HashSet::decode(&sent.encode().unwrap()), Ok(sent));
}

#[test]
fn should_decode_a_queue_rank() {
    assert_eq!(client_messages::decode_queue_rank(&Packet::of(protocol::EDONKEY, 0x5C, [0x2A, 0, 0, 0])), Ok(42));
}

#[test]
fn should_decode_a_queue_ranking_of_the_extended_protocol() {
    let mut payload = vec![0x07, 0x00];
    payload.extend([0; 10]);

    assert_eq!(client_messages::decode_queue_rank(&Packet::of(protocol::EMULE, 0x60, payload)), Ok(7));
}

#[test]
fn should_read_the_file_of_a_file_request() {
    assert_eq!(client_messages::file_of(&client_messages::for_file(client::SETREQFILEID, &FILE)), Ok(FILE));
}

// Every decoder reads garbage, in both protocols, or refuses it as malformed: never a panic.
proptest::proptest! {
    #[test]
    fn should_only_fail_with_decode_exception_on_garbage(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=120)) {
        use njmule_engine::protocol::ed2k::client_messages as messages;
        for kind in [protocol::EDONKEY, protocol::EMULE] {
            let received = Packet::of(kind, 0x50, data.clone());
            let _ = messages::FileStatus::decode(&received);
            let _ = messages::HashSet::decode(&received);
            let _ = messages::RequestParts::decode(&received);
            let _ = messages::SendingPart::decode(&received);
            let _ = messages::CompressedPart::decode(&received);
            let _ = messages::FileName::decode(&received);
            let _ = messages::decode_queue_rank(&received);
        }
    }
}

#[test]
fn should_give_the_status_of_a_download_from_its_complete_parts() {
    let status = PartStatus::of(2 * PARTSIZE + 1, &[true, false]);

    assert_eq!(status.part_count, 3);
    assert_eq!([0, 1, 2, 3].map(|part| status.has(part)), [true, false, false, false]);
}

#[test]
fn should_count_the_empty_last_part_of_an_exact_multiple_of_the_part_size_as_complete() {
    let status = PartStatus::of(2 * PARTSIZE, &[false, true]);

    assert_eq!(status.parts, [false, true, true]);
}

#[test]
fn should_refuse_to_send_data_that_does_not_fill_its_range() {
    assert!(part(0, 10, &[0; 9]).encode(false).is_err());
}
