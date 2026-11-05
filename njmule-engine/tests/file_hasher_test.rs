// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's FileHasherTest and GoldenFileHashTest, under their names: the eD2K hash set and
//! the AICH tree of one pass over the data, and the vectors of the independent implementation in `tools/golden/`.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::io::{self, ErrorKind, Read};
use std::ops::ControlFlow;

use data_encoding::BASE32_NOPAD;
use njmule_engine::files::hash::aich_tree::{self, AichTree};
use njmule_engine::files::hash::ed2k::{self, EMPTY_PART_HASH, PARTSIZE};
use njmule_engine::files::hash::file_hasher::{self, FileHashes};
use sha1::{Digest, Sha1};

const GOLDEN: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../njmule-core/src/test/resources/golden/file-hashes.txt");

fn hashed(input: &mut impl Read, size: u64) -> io::Result<FileHashes> {
    file_hasher::hash(input, size, |_| ControlFlow::Continue(()))
}

fn zeros(size: u64) -> FileHashes {
    hashed(&mut io::repeat(0).take(size), size).unwrap()
}

fn md4_of_zeros(length: usize) -> [u8; 16] {
    ed2k::md4(&vec![0; length])
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
fn should_give_small_files_no_hash_set() {
    let hashes = zeros(3);

    assert_eq!(hashes.ed2k.file_hash(), md4_of_zeros(3));
    assert!(hashes.ed2k.part_hashes().is_empty());
    assert_eq!(hashes.ed2k.part_hash(0), Some(hashes.ed2k.file_hash()));
}

#[test]
fn should_end_a_file_of_one_partsize_with_the_empty_part_hash() {
    let hashes = zeros(PARTSIZE);

    assert_eq!(hashes.ed2k.part_hashes(), [md4_of_zeros(9_728_000), EMPTY_PART_HASH]);
    assert_eq!(ed2k::file_hash(hashes.ed2k.part_hashes()), Ok(hashes.ed2k.file_hash()));
    assert_eq!(hex::encode_upper(EMPTY_PART_HASH), "31D6CFE0D16AE931B73C59D7E0C089C0");
    assert_eq!(ed2k::md4(&[]), EMPTY_PART_HASH);
}

#[test]
fn should_end_a_file_of_two_partsizes_with_the_empty_part_hash() {
    let zero_part = md4_of_zeros(9_728_000);

    assert_eq!(zeros(2 * PARTSIZE).ed2k.part_hashes(), [zero_part, zero_part, EMPTY_PART_HASH]);
}

#[test]
fn should_hash_a_partial_last_part_as_it_is() {
    let hashes = zeros(PARTSIZE + 1);

    assert_eq!(hashes.ed2k.part_hashes(), [md4_of_zeros(9_728_000), md4_of_zeros(1)]);
    assert!(hashes.ed2k.is_consistent());
    assert_eq!(hashes.ed2k.part_hash(1), Some(md4_of_zeros(1)));
}

#[test]
fn should_hash_an_empty_file_to_the_empty_md4_without_aich() {
    let hashes = zeros(0);

    assert_eq!(hashes.ed2k.file_hash(), EMPTY_PART_HASH);
    assert!(hashes.aich.is_none());
}

#[test]
fn should_make_the_aich_of_a_single_block_the_sha1_of_the_data() {
    let hashes = zeros(1000);

    let expected: [u8; 20] = Sha1::digest([0u8; 1000]).into();
    assert_eq!(aich(&hashes).master_hash(), expected);
    assert!(aich(&hashes).part_hashes().is_empty());
    assert_eq!(aich(&hashes).block_hashes().len(), 1);
}

#[test]
fn should_restart_the_aich_leaves_at_every_part() {
    let hashes = zeros(2 * PARTSIZE + 5);
    let tree = aich(&hashes);

    assert_eq!(tree.block_hashes().len(), 53 + 53 + 1);
    assert_eq!(aich_tree::block_count(2 * PARTSIZE + 5), 107);
    assert_eq!(tree.part_hashes().len(), 3);
    // A left and a right branch split their blocks differently, so equal parts may differ.
    let rebuilt = AichTree::from_block_hashes(tree.size(), tree.block_hashes().to_vec()).unwrap();
    assert_eq!(rebuilt.master_hash(), tree.master_hash());
}

#[test]
fn should_refuse_data_shorter_than_announced() {
    let error = hashed(&mut io::repeat(0).take(10), 11).unwrap_err();

    assert_eq!(error.kind(), ErrorKind::UnexpectedEof);
}

#[test]
fn should_refuse_a_wrong_number_of_aich_leaves() {
    assert!(AichTree::from_block_hashes(PARTSIZE + 1, Vec::new()).is_err());
}

#[test]
fn should_cancel_when_the_progress_breaks() {
    let error = file_hasher::hash(&mut io::repeat(0).take(PARTSIZE), PARTSIZE, |done| {
        if done > 1_000_000 { ControlFlow::Break(()) } else { ControlFlow::Continue(()) }
    })
    .unwrap_err();

    assert_eq!(error.kind(), ErrorKind::Interrupted);
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
