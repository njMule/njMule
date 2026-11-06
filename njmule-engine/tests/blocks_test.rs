// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's BlocksTest, under its names: a block of a file cut into the packets that carry it, compressed when
//! that is worth it.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects, clippy::indexing_slicing)]

use njmule_engine::protocol::packet::Packet;
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::upload::blocks;
use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};

const FILE: [u8; 16] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef];
const BLOCK: usize = 184_320;

fn text() -> Vec<u8> {
    let mut text = String::new();
    let mut line = 0;
    while text.len() < BLOCK {
        text.push_str(&format!("Line {line} of a text file that compresses well.\n"));
        line += 1;
    }
    text.into_bytes()[..BLOCK].to_vec()
}

// `random` bytes that do not compress, then zeros up to a block.
fn random_then_zeros(random: usize) -> Vec<u8> {
    let mut data = vec![0; BLOCK];
    StdRng::seed_from_u64(7).fill_bytes(&mut data[..random]);
    data
}

fn every_packet_is(packets: &[Packet], opcode: u8) {
    assert!(!packets.is_empty());
    assert!(packets.iter().all(|packet| packet.opcode() == opcode));
}

#[test]
fn should_send_a_block_that_shrinks_compressed() {
    let packets = blocks::packets(FILE, 0, &text(), true).unwrap();

    every_packet_is(&packets, extended::COMPRESSEDPART);
}

#[test]
fn should_send_a_block_of_compressed_data_as_it_is() {
    let packets = blocks::packets(FILE, 0, &random_then_zeros(BLOCK), true).unwrap();

    every_packet_is(&packets, client::SENDINGPART);
}

// The first 16 KiB tell: the zeros after them are not compressed either.
#[test]
fn should_send_as_it_is_a_block_whose_start_does_not_shrink() {
    let packets = blocks::packets(FILE, 0, &random_then_zeros(16 * 1024), true).unwrap();

    every_packet_is(&packets, client::SENDINGPART);
}

#[test]
fn should_send_a_block_as_it_is_when_the_client_takes_no_compression() {
    let packets = blocks::packets(FILE, 0, &text(), false).unwrap();

    every_packet_is(&packets, client::SENDINGPART);
}

// Beyond the Java engine's examples: the branches its other tests reach.

#[test]
fn should_read_a_block_from_disk_and_refuse_one_beyond_the_file() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("shared.txt");
    std::fs::write(&file, text()).unwrap();

    // 16 chunks, and a rest of 12 480 bytes too short for a packet of its own.
    let packets = blocks::read_packets(&file, FILE, 0, 16 * blocks::CHUNK + 12_480, false).unwrap();
    let beyond = blocks::read(&file, BLOCK as u64 - 10, 20);

    assert_eq!(packets.len(), 17, "the short rest goes with the last chunk");
    assert_eq!(beyond.unwrap_err().kind(), std::io::ErrorKind::UnexpectedEof);
}

#[test]
fn should_cut_a_block_above_four_gibibytes_with_wide_offsets() {
    let narrow = blocks::packets(FILE, 0, &text(), false).unwrap();

    let wide = blocks::packets(FILE, 0xFFFF_0000, &text(), false).unwrap();

    assert_eq!(wide.len(), narrow.len());
    assert!(wide[0].payload().len() > narrow[0].payload().len(), "64-bit offsets");
}
