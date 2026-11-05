// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's AichRecoveryTest, under their names: recovery data made and proven, refused
//! when changed or for another file, and which parts have it. A part is a `u32`, so no part is negative.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use njmule_engine::files::hash::aich_recovery::{self, LARGE_FILE, has_recovery};
use njmule_engine::files::hash::aich_tree::{self, AichTree};
use njmule_engine::files::hash::ed2k::{self, EMBLOCKSIZE, PARTSIZE};

const PER_PART: usize = 53;

// A tree of leaves made from the seed, different for every seed and leaf.
fn tree(size: u64, seed: u64) -> AichTree {
    let leaves = (0..aich_tree::block_count(size))
        .map(|i| {
            let mut leaf = [0u8; 20];
            leaf[..8].copy_from_slice(&seed.to_le_bytes());
            leaf[8..16].copy_from_slice(&i.to_le_bytes());
            leaf[16] = 0xA5;
            leaf
        })
        .collect();
    AichTree::from_block_hashes(size, leaves).unwrap()
}

fn proven(tree: &AichTree, part: u32, data: &[u8]) -> Option<Vec<[u8; 20]>> {
    aich_recovery::verify(tree.size(), part, &tree.master_hash(), data)
}

fn blocks_of(tree: &AichTree, part: usize) -> Vec<[u8; 20]> {
    let first = part * PER_PART;
    let count = usize::try_from(ed2k::part_length(tree.size(), part).div_ceil(EMBLOCKSIZE)).unwrap();
    tree.block_hashes()[first..first + count].to_vec()
}

#[test]
fn should_prove_the_blocks_of_every_part_of_a_file_of_several_parts() {
    let tree = tree(3 * PARTSIZE + 1_000_000, 1);
    for part in 0..4 {
        let data = aich_recovery::create(&tree, part).unwrap();

        assert_eq!(proven(&tree, part, &data), Some(blocks_of(&tree, part as usize)), "part {part}");
    }
}

#[test]
fn should_lay_out_one_sibling_per_level_then_the_blocks() {
    let data = aich_recovery::create(&tree(2 * PARTSIZE, 2), 0).unwrap();
    let u16_at = |offset: usize| u16::from_le_bytes([data[offset], data[offset + 1]]);

    assert_eq!(usize::from(u16_at(0)), 1 + PER_PART);
    // Root (1), its right child (2) as the sibling, then the 53 blocks below the left child (3).
    assert_eq!(u16_at(2), 2);
    let first_block = u16_at(2 + 2 + 20);
    assert_ne!(first_block & ((1 << (15 - first_block.leading_zeros())) >> 1), 0, "below node 3");
    assert_eq!(data.len(), 2 + (1 + PER_PART) * (2 + 20) + 2);
}

#[test]
fn should_prove_nothing_with_corrupted_data() {
    let tree = tree(2 * PARTSIZE + 5, 3);
    let mut data = aich_recovery::create(&tree, 1).unwrap();
    let at = data.len() - 3;
    data[at] ^= 1;

    assert_eq!(proven(&tree, 1, &data), None);
}

#[test]
fn should_prove_nothing_against_the_master_hash_of_another_file() {
    let tree3 = tree(2 * PARTSIZE + 5, 3);
    let data = aich_recovery::create(&tree3, 1).unwrap();

    assert_eq!(aich_recovery::verify(tree3.size(), 1, &tree(2 * PARTSIZE + 5, 4).master_hash(), &data), None);
}

#[test]
fn should_prove_nothing_with_data_of_one_byte() {
    assert_eq!(proven(&tree(2 * PARTSIZE + 5, 3), 1, &[1]), None);
}

#[test]
fn should_prove_all_the_blocks_of_a_file_of_one_part_above_one_block() {
    for size in [EMBLOCKSIZE + 1, 1_000_000, PARTSIZE] {
        let tree = tree(size, 6);
        let data = aich_recovery::create(&tree, 0).unwrap();

        assert_eq!(proven(&tree, 0, &data).as_deref(), Some(tree.block_hashes()), "{size} bytes");
    }
}

#[test]
fn should_have_no_recovery_data_for_a_file_of_one_block() {
    assert!(!has_recovery(EMBLOCKSIZE, 0), "one block is the master hash itself");
}

#[test]
fn should_have_no_recovery_data_for_a_last_part_of_one_block() {
    assert!(!has_recovery(2 * PARTSIZE + EMBLOCKSIZE, 2), "a last part of one block");
}

#[test]
fn should_have_recovery_data_for_a_last_part_above_one_block() {
    assert!(has_recovery(2 * PARTSIZE + EMBLOCKSIZE + 1, 2));
}

#[test]
fn should_have_no_recovery_data_for_a_part_beyond_the_file() {
    assert!(!has_recovery(2 * PARTSIZE, 2), "no such part");
}

#[test]
fn should_prove_the_blocks_of_every_part_of_more_than_one_block() {
    for (size, seed) in [
        (1, 0),
        (EMBLOCKSIZE + 1, 7),
        (PARTSIZE - 1, 11),
        (PARTSIZE + EMBLOCKSIZE, 12),
        (PARTSIZE + EMBLOCKSIZE + 1, 13),
        (3 * PARTSIZE + 2 * EMBLOCKSIZE + 9, 14),
        (4 * PARTSIZE + EMBLOCKSIZE, 15),
    ] {
        let tree = tree(size, seed);
        let parts = ed2k::part_count(size);
        for part in 0..parts {
            let index = u32::try_from(part).unwrap();
            if ed2k::part_length(size, part) > EMBLOCKSIZE {
                let data = aich_recovery::create(&tree, index).unwrap();

                assert_eq!(proven(&tree, index, &data), Some(blocks_of(&tree, part)), "part {part} of {size} bytes");
            } else {
                assert!(!has_recovery(size, index), "part {part} of {size} bytes");
            }
        }
        assert!(!has_recovery(size, u32::try_from(parts).unwrap()), "{size} bytes");
    }
}

#[test]
fn should_use_long_identifiers_for_large_files() {
    let tree = tree(LARGE_FILE + PARTSIZE, 5);
    let last = u32::try_from((tree.size() - 1) / PARTSIZE).unwrap();

    let data = aich_recovery::create(&tree, last).unwrap();

    assert_eq!(&data[..2], [0, 0]);
    assert!(proven(&tree, last, &data).is_some());
}

#[test]
fn should_make_no_recovery_data_for_a_part_without_it() {
    let error = aich_recovery::create(&tree(EMBLOCKSIZE, 3), 0).unwrap_err();

    assert_eq!(error.to_string(), format!("part 0 of {EMBLOCKSIZE} bytes has no recovery data"));
}

#[test]
fn should_prove_nothing_for_a_part_without_recovery_data() {
    let tree = tree(2 * PARTSIZE + EMBLOCKSIZE, 4);
    let data = aich_recovery::create(&tree, 0).unwrap();

    assert_eq!(proven(&tree, 2, &data), None);
}

#[test]
fn should_prove_the_blocks_of_data_without_the_count_of_long_identifiers() {
    let tree = tree(2 * PARTSIZE, 5);
    let data = aich_recovery::create(&tree, 1).unwrap();

    // Small files end their data with a zero count of 32-bit identifiers; a reader takes it without.
    assert_eq!(proven(&tree, 1, &data[..data.len() - 2]), Some(blocks_of(&tree, 1)));
}

#[test]
fn should_prove_nothing_with_an_identifier_outside_the_tree() {
    let tree = tree(2 * PARTSIZE, 6);
    let mut data = aich_recovery::create(&tree, 0).unwrap();
    data[2..4].copy_from_slice(&1u16.to_le_bytes());

    assert_eq!(proven(&tree, 0, &data), None, "1 is the root, which the data never holds");
}
