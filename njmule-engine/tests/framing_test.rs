// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's FramingTest, under their names: TCP and UDP frames and zlib packing.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::io::{self, Cursor, ErrorKind, Read};

use njmule_engine::protocol::io::DecodeError;
use njmule_engine::protocol::packet::frame_limit::{FrameLimit, fixed};
use njmule_engine::protocol::packet::packing::{self, MAX_CLIENT_UNPACKED};
use njmule_engine::protocol::packet::{Packet, protocol, tcp_framing, udp_framing};

fn stream(hex: &str) -> Cursor<Vec<u8>> {
    Cursor::new(hex::decode(hex).unwrap())
}

fn read(input: &mut Cursor<Vec<u8>>, limit: &impl FrameLimit) -> Result<Option<Packet>, String> {
    tcp_framing::read(input, limit).map_err(|e| {
        assert_eq!(e.kind(), ErrorKind::InvalidData, "{e}");
        e.to_string()
    })
}

fn inflated(data: &[u8], max_size: usize) -> Result<Vec<u8>, DecodeError> {
    packing::inflate(data, max_size)
}

#[test]
fn should_count_the_opcode_in_the_tcp_length() {
    let frame = tcp_framing::encode(&Packet::of(protocol::EDONKEY, 0x01, [0x10])).unwrap();

    assert_eq!(hex::encode(frame), "e3020000000110");
}

#[test]
fn should_read_consecutive_frames_and_stop_cleanly_at_the_end() {
    let mut input = stream("e3020000000110c5010000004c");
    let limit = fixed(10);

    assert_eq!(read(&mut input, &limit), Ok(Some(Packet::of(protocol::EDONKEY, 0x01, [0x10]))));
    assert_eq!(read(&mut input, &limit), Ok(Some(Packet::empty(protocol::EMULE, 0x4C))));
    assert_eq!(read(&mut input, &limit), Ok(None));
}

#[test]
fn should_reject_an_oversized_frame_before_reading_it() {
    // Announces 2^32-1 bytes: rejected from the header alone.
    let error = read(&mut stream("e3ffffffff01"), &fixed(4096)).unwrap_err();

    assert!(error.contains("exceeds 4096"), "{error}");
}

#[test]
fn should_reject_an_unknown_protocol() {
    assert!(read(&mut stream("e4020000000110"), &fixed(10)).is_err());
}

#[test]
fn should_reject_a_zero_length() {
    assert!(read(&mut stream("e30000000001"), &fixed(10)).is_err());
}

#[test]
fn should_reject_a_disallowed_opcode() {
    let only_hello = |_: u8, opcode: u8| (opcode == 0x01).then_some(10);

    let error = read(&mut stream("e3020000004c10"), &only_hello).unwrap_err();

    assert!(error.contains("not allowed"), "{error}");
}

#[test]
fn should_reject_a_stream_ending_inside_a_frame() {
    let error = read(&mut stream("e305000000011011"), &fixed(10)).unwrap_err();

    assert!(error.contains("inside a frame"), "{error}");
}

#[test]
fn should_reject_a_udp_datagram_shorter_than_a_header() {
    assert!(udp_framing::decode(&[0xE3]).is_err());
}

#[test]
fn should_decode_a_udp_datagram_of_a_header_alone() {
    assert_eq!(udp_framing::decode(&[0xE3, 0x96]), Ok(Packet::empty(protocol::EDONKEY, 0x96)));
}

#[test]
fn should_decode_a_udp_datagram_inside_the_buffer() {
    let buffer = [0x00, 0xE3, 0x96, 0x01];

    assert_eq!(udp_framing::decode(&buffer[1..]), Ok(Packet::of(protocol::EDONKEY, 0x96, [1])));
}

#[test]
fn should_pack_a_large_packet_into_a_smaller_one() {
    let packed = packing::pack(Packet::of(protocol::EMULE, 0x40, vec![0; 4000])).unwrap();

    assert_eq!(packed.protocol(), protocol::PACKED);
    assert!(packed.length() < 100, "{packed:?}");
}

#[test]
fn should_unpack_to_the_emule_protocol() {
    let packet = Packet::of(protocol::EMULE, 0x40, vec![0; 4000]);
    let packed = packing::pack(packet.clone()).unwrap();

    assert_eq!(packing::unpack(packed, 4000), Ok(packet));
}

#[test]
fn should_not_pack_a_small_packet() {
    let packet = Packet::of(protocol::EMULE, 0x40, [1, 2, 3]);

    assert_eq!(packing::pack(packet.clone()), Ok(packet));
}

#[test]
fn should_unpack_a_kad_packet_to_the_kad_protocol() {
    let packed = packing::pack(Packet::of(protocol::KAD, 0x3B, vec![0; 500])).unwrap();

    assert_eq!(packing::unpack(packed, 500).map(|p| p.protocol()), Ok(protocol::KAD));
}

#[test]
fn should_pack_a_server_frame() {
    let packed = packing::pack(Packet::of(protocol::EDONKEY, 0x33, vec![0; 2000])).unwrap();

    assert_eq!(packed.protocol(), protocol::PACKED);
}

#[test]
fn should_unpack_a_packed_server_frame_to_its_edonkey_message() {
    let packet = Packet::of(protocol::EDONKEY, 0x33, vec![0; 2000]);
    let packed = packing::pack(packet.clone()).unwrap();

    assert_eq!(packing::unpack_from_server(packed), Ok(packet));
}

#[test]
fn should_leave_a_server_frame_that_is_not_packed_as_it_is() {
    let packet = Packet::of(protocol::EDONKEY, 0x33, vec![0; 2000]);

    assert_eq!(packing::unpack_from_server(packet.clone()), Ok(packet));
}

#[test]
fn should_stop_unpacking_a_decompression_bomb_at_the_cap() {
    // 1 MiB of zeros packs to about 1 KiB: a decompression bomb for a 50 kB cap.
    let packed = packing::pack(Packet::of(protocol::EMULE, 0x40, vec![0; 1 << 20])).unwrap();

    let error = packing::unpack(packed, MAX_CLIENT_UNPACKED).unwrap_err();

    assert!(error.to_string().contains("exceeds"), "{error}");
}

#[test]
fn should_refuse_to_inflate_corrupt_data() {
    assert!(inflated(&hex::decode("789c0102").unwrap(), 100).is_err());
}

#[test]
fn should_inflate_exactly_the_cap() {
    assert_eq!(inflated(&packing::deflate(&[0; 1000], 9), 1000).map(|data| data.len()), Ok(1000));
}

#[test]
fn should_refuse_to_inflate_past_the_cap() {
    assert!(inflated(&packing::deflate(&[0; 1000], 9), 999).is_err());
}

#[test]
fn should_inflate_nothing_within_a_null_cap() {
    assert_eq!(inflated(&packing::deflate(&[], 9), 0), Ok(Vec::new()));
}

#[test]
fn should_refuse_to_inflate_no_data() {
    assert!(inflated(&[], 100).is_err());
}

#[test]
fn should_refuse_to_inflate_data_cut_before_its_checksum() {
    let deflated = packing::deflate(&[0; 1000], 9);

    assert!(inflated(&deflated[..deflated.len() - 4], 1000).is_err(), "cut before its checksum");
}

#[test]
fn should_refuse_a_preset_dictionary() {
    // A preset dictionary (FDICT) cannot be supplied.
    assert!(inflated(&hex::decode("78bb00000001").unwrap(), 100).is_err());
}

#[test]
fn should_round_trip_tcp_frames() {
    for (protocol, opcode, length) in
        [(protocol::EDONKEY, 0, 0), (protocol::EMULE, 0x4C, 1), (protocol::PACKED, 0xFF, 300)]
    {
        let packet = Packet::of(protocol, opcode, (0..length).map(|i: u32| i.to_le_bytes()[0]).collect::<Vec<_>>());
        let mut input = Cursor::new(tcp_framing::encode(&packet).unwrap());

        assert_eq!(read(&mut input, &fixed(300)), Ok(Some(packet)));
    }
}

proptest::proptest! {
    #[test]
    fn should_only_fail_with_io_exceptions_on_garbage_streams(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=40)) {
        let mut input = Cursor::new(data);

        // Up to ten frames: each read gives a frame, the end, or an error, never a panic.
        for _ in 0..10 {
            if !matches!(tcp_framing::read(&mut input, &fixed(16)), Ok(Some(_))) {
                break;
            }
        }
    }

    #[test]
    fn should_never_inflate_garbage_past_the_cap(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=64)) {
        let inflated = packing::inflate(&data, 1000);

        proptest::prop_assert!(inflated.as_ref().is_ok_and(|bytes| bytes.len() <= 1000) || inflated.is_err());
    }
}

#[test]
fn should_refuse_to_pack_a_protocol_without_a_packed_form() {
    assert!(packing::pack(Packet::of(protocol::PACKED, 0x40, vec![0; 4000])).is_err());
}

#[test]
fn should_pass_on_read_errors_other_than_a_frame_cut_short() {
    struct Reset;
    impl Read for Reset {
        fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
            Err(io::Error::new(ErrorKind::ConnectionReset, "reset"))
        }
    }
    let mut input = stream("e30500000001").chain(Reset);

    let error = tcp_framing::read(&mut input, &fixed(10)).unwrap_err();

    assert_eq!(error.kind(), ErrorKind::ConnectionReset);
}

#[test]
fn should_show_a_packet_with_at_most_32_payload_bytes_in_logs() {
    let short = Packet::of(protocol::EMULE, 0x40, vec![0xAB; 2]);
    let long = Packet::of(protocol::EMULE, 0x40, vec![0; 40]);

    assert_eq!(format!("{short:?}"), "Packet[0xC5/0x40, 2 bytes: abab]");
    assert_eq!(format!("{long:?}"), format!("Packet[0xC5/0x40, 40 bytes: {}…]", "00".repeat(32)));
}
