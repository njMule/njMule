// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's Rc4Test, under their names. A skip cannot go backwards (its length is unsigned),
//! and there is no rekeying: a new key stream costs what rekeying did, with no cipher to look up.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::crypto::rc4::{Rc4, TCP_DISCARD};
use rand::Rng;

fn key_stream<const N: usize>(key: &[u8; N], length: usize) -> Vec<u8> {
    let mut out = vec![0; length];
    Rc4::new(key).apply(&mut out);
    out
}

#[test]
fn should_match_the_rfc6229_key_stream() {
    // RFC 6229, 40-bit key 0x0102030405, offsets 0 and 16.
    let stream = key_stream(&[1, 2, 3, 4, 5], 32);

    assert_eq!(hex::encode(stream), "b2396305f03dc027ccc3524a0a1118a8".to_owned() + "6982944f18fc82d589c403a47a0d0919");
}

#[test]
fn should_match_the_rfc6229_key_stream_of_a_128_bit_key() {
    // RFC 6229, 128-bit key 0x0102…10, the length of every obfuscation key, offsets 0 and 16.
    let key: [u8; 16] = std::array::from_fn(|i| u8::try_from(i + 1).unwrap());

    let stream = key_stream(&key, 32);

    assert_eq!(hex::encode(stream), "9ac7cc9a609d1ef7b2932899cde41b97".to_owned() + "5248c4959014126a6e8a84f11d1a9e1c");
}

#[test]
fn should_skip_as_if_the_output_were_discarded() {
    let mut random = rand::rng();
    for skip in [0, 1, 255, TCP_DISCARD, 4095, 4096, 4097, 10_000] {
        let mut key = [0; 16];
        random.fill_bytes(&mut key);
        let mut rc4 = Rc4::new(&key);

        rc4.skip(skip);

        let mut tail = [0; 16];
        rc4.apply(&mut tail);
        assert_eq!(tail.as_slice(), &key_stream(&key, skip + 16)[skip..], "skip {skip}");
    }
}

#[test]
fn should_continue_the_key_stream_across_calls() {
    let key = [7; 16];
    let mut rc4 = Rc4::new(&key);

    let mut parts = [0; 40];
    for part in parts.chunks_mut(7) {
        rc4.apply(part);
    }

    assert_eq!(parts.as_slice(), key_stream(&key, 40));
}

#[test]
fn should_show_nothing_of_the_key_stream_in_logs() {
    assert_eq!(format!("{:?}", Rc4::new(&[1, 2, 3, 4, 5])), "Rc4 { .. }");
}
