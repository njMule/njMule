// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's MutatedMessagesTest, under their names: messages of the network made right, then
//! changed as a broken or hostile peer or server could change them, are only refused, never a panic. The messages made
//! right are those of `golden/packets.jsonl`, which the Java engine's encoders wrote, each read by its decoder.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod canonical;

use canonical::packet_decoders::{Decoder, decoder};
use njmule_engine::protocol::packet::Packet;
use proptest::prelude::*;
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};
use serde_json::Value;

const CORPUS: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../njmule-core/src/test/resources/golden/packets.jsonl");
const SPECIAL_BYTES: [u8; 5] = [0x00, 0x01, 0x7F, 0x80, 0xFF];
const SPECIAL_NUMBERS: [u64; 7] = [0, 1, 0xFF, 0xFFFF, 0x7FFF_FFFF, 0x8000_0000, 0xFFFF_FFFF];

struct Sample {
    kind: String,
    packet: Packet,
    decode: Decoder,
}

// The messages the corpus has made right: those its decoder reads.
fn samples() -> Vec<Sample> {
    let text = std::fs::read_to_string(CORPUS).expect("the corpus of the Java engine");
    text.lines()
        .filter(|line| !line.trim().is_empty())
        .filter_map(|line| {
            let recorded: Value = serde_json::from_str(line).unwrap();
            if recorded.get("error").is_some_and(|error| !error.is_null()) {
                return None;
            }
            let kind = recorded["kind"].as_str().unwrap().to_owned();
            let decode = decoder(&kind)?;
            let byte = |field: &str| u8::try_from(recorded[field].as_u64().unwrap()).unwrap();
            let payload = hex::decode(recorded["in"].as_str().unwrap()).unwrap();
            Some(Sample { kind, packet: Packet::of(byte("protocol"), byte("opcode"), payload), decode })
        })
        .collect()
}

// `valid` changed one to three times, the same way for the same seed.
fn changed(valid: &[u8], seed: u64) -> Vec<u8> {
    let mut random = StdRng::seed_from_u64(seed);
    let mut data = valid.to_vec();
    for _ in 0..random.random_range(1..=3) {
        data = changed_once(data, &mut random);
    }
    data
}

fn changed_once(mut data: Vec<u8>, random: &mut StdRng) -> Vec<u8> {
    let at = if data.is_empty() { 0 } else { random.random_range(0..data.len()) };
    match random.random_range(0..7) {
        0 if !data.is_empty() => data[at] ^= 1 << random.random_range(0..8),
        1 if !data.is_empty() => data[at] = SPECIAL_BYTES[random.random_range(0..SPECIAL_BYTES.len())],
        2 => {
            // A length or a count: two or four bytes, little endian.
            let value = SPECIAL_NUMBERS[random.random_range(0..SPECIAL_NUMBERS.len())];
            let width = if random.random_bool(0.5) { 2 } else { 4 };
            for (b, byte) in data.iter_mut().skip(at).take(width).enumerate() {
                *byte = (value >> (8 * b)) as u8;
            }
        }
        3 => {
            let keep = if data.is_empty() { 0 } else { random.random_range(0..data.len()) };
            data.truncate(keep);
        }
        4 => {
            let junk: Vec<u8> = (0..random.random_range(1..=16)).map(|_| random.random()).collect();
            data.splice(at..at, junk);
        }
        5 => {
            let removed = (data.len() - at).min(random.random_range(1..=8));
            data.drain(at..at + removed);
        }
        _ => {
            let length = (data.len() - at).min(random.random_range(1..=32));
            let copy = data[at..at + length].to_vec();
            data.splice(at..at, copy);
        }
    }
    data
}

#[test]
fn should_decode_every_sample_as_it_is_made() {
    let samples = samples();

    let refused: Vec<String> = samples
        .iter()
        .filter_map(|sample| (sample.decode)(sample.packet.clone()).err().map(|e| format!("{}: {e}", sample.kind)))
        .collect();

    assert!(samples.len() > 100, "{} samples", samples.len());
    assert!(refused.is_empty(), "{refused:#?}");
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(5000))]

    #[test]
    fn should_only_refuse_a_changed_message(index: usize, seed: u64) {
        let samples = SAMPLES.with(|samples| samples.len());
        let sample = index % samples;

        let packet = SAMPLES.with(|samples| {
            let sample = &samples[sample];
            (Packet::of(sample.packet.protocol(), sample.packet.opcode(), changed(sample.packet.payload(), seed)), sample.decode)
        });

        // Read or refused as malformed: a panic fails the property.
        let _ = (packet.1)(packet.0);
    }
}

thread_local! {
    static SAMPLES: Vec<Sample> = samples();
}
