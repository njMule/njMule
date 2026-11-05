// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's BlockInflaterTest, under their names.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::download::block_inflater::BlockInflater;
use njmule_engine::protocol::packet::packing;
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};

fn random_block(size: usize, seed: u64) -> Vec<u8> {
    let mut block = vec![0u8; size];
    StdRng::seed_from_u64(seed).fill(&mut block[..]);
    block
}

fn inflate_in_chunks(inflater: &mut BlockInflater, stream: &[u8], chunk: usize) -> (Vec<u8>, Vec<u64>) {
    let mut out = Vec::new();
    let mut positions = Vec::new();
    for piece in stream.chunks(chunk) {
        positions.push(inflater.position());
        out.extend(inflater.inflate(piece).unwrap());
    }
    (out, positions)
}

#[test]
fn should_inflate_a_stream_split_into_chunks() {
    let mut block = random_block(184_320, 7);
    block[50_000..120_000].fill(b'x');
    let stream = packing::deflate(&block, 1);
    let mut inflater = BlockInflater::new(1_000_000, 184_320);

    let (out, positions) = inflate_in_chunks(&mut inflater, &stream, 10_240);

    assert_eq!(positions[0], 1_000_000);
    assert_eq!(inflater.position(), 1_000_000 + 184_320);
    assert!(inflater.finished());
    assert_eq!(out, block);
}

#[test]
fn should_give_every_byte_of_blocks_that_end_in_long_repeats() {
    for repeating in [0u8, b'a', 0xFF] {
        let mut block = random_block(184_320, 3);
        block[1000..].fill(repeating);
        let stream = packing::deflate(&block, 1);
        let mut inflater = BlockInflater::new(0, 184_320);

        let (out, _) = inflate_in_chunks(&mut inflater, &stream, 10_240);

        assert_eq!(out, block);
        assert!(inflater.finished());
    }
}

#[test]
fn should_refuse_output_beyond_the_requested_block() {
    let stream = packing::deflate(&random_block(10_000, 5), 1);
    let mut inflater = BlockInflater::new(0, 9_999);

    let inflated = inflater.inflate(&stream);

    assert!(inflated.is_err());
}

#[test]
fn should_refuse_corrupt_streams() {
    let mut stream = packing::deflate(&random_block(10_000, 6), 1);
    for byte in &mut stream[2..40] {
        *byte ^= 0x5A;
    }
    let mut inflater = BlockInflater::new(0, 10_000);

    let inflated = inflater.inflate(&stream);

    assert!(inflated.is_err());
}

#[test]
fn should_take_a_stream_one_byte_at_a_time() {
    let block = random_block(5_000, 8);
    let stream = packing::deflate(&block, 1);
    let mut inflater = BlockInflater::new(0, 5_000);

    let (out, _) = inflate_in_chunks(&mut inflater, &stream, 1);

    assert_eq!(out, block);
    assert!(inflater.finished());
}

#[test]
fn should_give_nothing_after_the_end_of_the_block() {
    let block = random_block(5_000, 9);
    let stream = packing::deflate(&block, 1);
    let mut inflater = BlockInflater::new(0, 5_000);
    inflater.inflate(&stream).unwrap();

    let more = inflater.inflate(&stream).unwrap();

    assert!(more.is_empty());
}

#[test]
fn should_take_up_to_exactly_the_block() {
    let block = random_block(5_000, 10);
    let stream = packing::deflate(&block, 1);
    let mut inflater = BlockInflater::new(0, 5_000);

    let out = inflater.inflate(&stream).unwrap();

    assert_eq!(out.len(), 5_000);
}

#[test]
fn should_refuse_streams_without_the_zlib_wrapper() {
    let stream = packing::deflate(&random_block(5_000, 11), 1);
    let raw = &stream[2..stream.len() - 4];
    let mut inflater = BlockInflater::new(0, 5_000);

    let inflated = inflater.inflate(raw);

    assert!(inflated.is_err());
}

#[test]
fn should_refuse_streams_with_a_dictionary() {
    // A zlib header with the preset dictionary flag, its check bits right, and a dictionary id.
    let header: [u8; 2] = [0x78, 0xBB];
    assert_eq!(u16::from_be_bytes(header) % 31, 0);
    let stream = [&header[..], &[0, 0, 0, 1], &[0x03, 0x00]].concat();
    let mut inflater = BlockInflater::new(0, 5_000);

    let inflated = inflater.inflate(&stream);

    assert!(inflated.is_err());
}
