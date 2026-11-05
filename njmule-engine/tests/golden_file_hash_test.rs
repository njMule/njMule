// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The example of the Java engine's GoldenFileHashTest, under its name: the vectors of the independent implementation
//! in `tools/golden/`.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::io::{self, Read};
use std::ops::ControlFlow;

use data_encoding::BASE32_NOPAD;
use njmule_engine::files::hash::aich_tree::AichTree;
use njmule_engine::files::hash::file_hasher::{self, FileHashes};

const GOLDEN: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../njmule-core/src/test/resources/golden/file-hashes.txt");

fn hashed(input: &mut impl Read, size: u64) -> io::Result<FileHashes> {
    file_hasher::hash(input, size, |_| ControlFlow::Continue(()))
}

fn aich(hashes: &FileHashes) -> &AichTree {
    hashes.aich.as_ref().unwrap()
}

/// The generator's data: byte i is (7 i + i / 251) mod 256.
struct Pattern {
    position: u64,
}

impl Read for Pattern {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        for byte in buffer.iter_mut() {
            let i = self.position;
            *byte = (7 * i + i / 251).to_le_bytes()[0];
            self.position += 1;
        }
        Ok(buffer.len())
    }
}

#[test]
fn should_match_the_golden_vectors() {
    let text = std::fs::read_to_string(GOLDEN).expect("the golden vectors");
    let mut vectors = 0;
    for line in text.lines().filter(|line| !line.trim().is_empty() && !line.starts_with('#')) {
        let field = |name: &str| {
            line.split(' ').find_map(|f| f.strip_prefix(name).and_then(|f| f.strip_prefix('='))).unwrap().to_owned()
        };
        let list = |value: String| -> Vec<String> {
            if value == "-" { Vec::new() } else { value.split(':').map(str::to_owned).collect() }
        };
        let size: u64 = field("size").parse().unwrap();

        let hashes = hashed(&mut Pattern { position: 0 }, size).unwrap();

        assert_eq!(hex::encode_upper(hashes.ed2k.file_hash()), field("md4"), "{size} bytes");
        let parts: Vec<String> = hashes.ed2k.part_hashes().iter().map(hex::encode_upper).collect();
        assert_eq!(parts, list(field("parts")), "{size} bytes");
        assert_eq!(BASE32_NOPAD.encode(&aich(&hashes).master_hash()), field("aich"), "{size} bytes");
        let aich_parts: Vec<String> = aich(&hashes).part_hashes().iter().map(|h| BASE32_NOPAD.encode(h)).collect();
        assert_eq!(aich_parts, list(field("aichparts")), "{size} bytes");
        vectors += 1;
    }
    assert!(vectors > 0, "no vector in {GOLDEN}");
}
