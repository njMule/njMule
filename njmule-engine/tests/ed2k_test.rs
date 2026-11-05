// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's Ed2kTest, under their names: MD4, parts and hash sets.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use njmule_engine::files::hash::aich_tree;
use njmule_engine::files::hash::ed2k::{self, EMBLOCKSIZE, EMPTY_PART_HASH, MAX_FILE_SIZE, PARTSIZE};
use njmule_engine::files::hash::ed2k_hash_set::Ed2kHashSet;

fn hash16(hex: &str) -> [u8; 16] {
    hex::decode(hex).unwrap().try_into().unwrap()
}

// RFC 1320, appendix A.5.
#[test]
fn should_match_the_rfc_vectors_of_md4() {
    for (message, digest) in [
        ("", "31D6CFE0D16AE931B73C59D7E0C089C0"),
        ("abc", "A448017AAF21D8525FC10AE87AA6729D"),
        ("message digest", "D9130A8164549FE818874806E1C7014B"),
        (
            "12345678901234567890123456789012345678901234567890123456789012345678901234567890",
            "E33B4DDC9C38F2199C3E7B164FCC0536",
        ),
    ] {
        assert_eq!(hex::encode_upper(ed2k::md4(message.as_bytes())), digest, "{message:?}");
    }
}

// Reference part counts around the PARTSIZE boundaries.
#[test]
fn should_count_the_parts_around_the_partsize_boundaries() {
    for (size, data_parts, ed2k_parts, hashes) in [
        (1, 1, 1, 0),
        (9_727_999, 1, 1, 0),
        (9_728_000, 1, 2, 2),
        (9_728_001, 2, 2, 2),
        (19_456_000, 2, 3, 3),
        (19_456_001, 3, 3, 3),
    ] {
        assert_eq!(
            (ed2k::part_count(size), ed2k::ed2k_part_count(size), ed2k::hash_set_size(size)),
            (data_parts, ed2k_parts, hashes),
            "{size} bytes"
        );
    }
}

// Reference hash sets: the file hash is the MD4 of the part hashes.
#[test]
fn should_hash_a_file_as_the_md4_of_its_part_hashes() {
    for (file, parts) in [
        ("A72CA8DF7F07154E217C236C89C17619", "4891ED2E5C9C49F442145A3A5F608299:31D6CFE0D16AE931B73C59D7E0C089C0"),
        ("2F620AE9D462CBB6A59FE8401D2B3D23", "121795F0BEDE02DDC7C5426D0995F53F:C329E527945B8FE75B3C5E8826755747"),
        (
            "A54C5E562D5E03CA7D77961EB9A745A4",
            "B3F5CE2A06BF403BFB9BFFF68BDDC4D9:509AA30C9EA8FC136B1159DF2F35B8A9:31D6CFE0D16AE931B73C59D7E0C089C0",
        ),
        (
            "797ED552F34380CAFF8C958207E40355",
            "FC7FD02CCD6987DCF1421F4C0AF94FB8:2FE466AF8A7C06DA3365317B75A5ACFE:873D3BF52629F7C1527C6E8E473C1C30:\
             BCE50BEE7877BB07BB6FDA56BFE142FB",
        ),
    ] {
        let parts: Vec<[u8; 16]> = parts.split(':').map(hash16).collect();

        assert_eq!(ed2k::file_hash(&parts), Ok(hash16(file)), "{file}");
    }
}

#[test]
fn should_cover_a_file_exactly_with_its_parts_and_blocks() {
    for size in [1, EMBLOCKSIZE, PARTSIZE - 1, PARTSIZE, PARTSIZE + 1, 3 * PARTSIZE + 7, 0xFFFF_FFFF, MAX_FILE_SIZE] {
        let parts = ed2k::part_count(size);
        let lengths: Vec<u64> = (0..parts).map(|part| ed2k::part_length(size, part)).collect();

        assert!(lengths.iter().all(|length| (1..=PARTSIZE).contains(length)), "{size} bytes");
        let mut total = 0;
        for (part, length) in lengths.iter().enumerate() {
            assert_eq!(ed2k::part_start(part), total, "{size} bytes, part {part}");
            total += length;
        }
        assert_eq!(total, size, "{size} bytes");
        assert_eq!(
            aich_tree::block_count(size),
            lengths.iter().map(|length| length.div_ceil(EMBLOCKSIZE)).sum::<u64>(),
            "{size} bytes"
        );
        assert_eq!(ed2k::ed2k_part_count(size), usize::try_from(size / PARTSIZE).unwrap() + 1, "{size} bytes");
        let expected = if size < PARTSIZE { 0 } else { ed2k::ed2k_part_count(size) };
        assert_eq!(ed2k::hash_set_size(size), expected, "{size} bytes");
    }
}

#[test]
fn should_refuse_a_hash_set_with_too_few_part_hashes() {
    assert!(Ed2kHashSet::new(PARTSIZE, EMPTY_PART_HASH, vec![EMPTY_PART_HASH]).is_err());
}

#[test]
fn should_find_a_hash_set_inconsistent_when_its_file_hash_is_not_that_of_its_parts() {
    let set = Ed2kHashSet::new(PARTSIZE, EMPTY_PART_HASH, vec![EMPTY_PART_HASH; 2]).unwrap();

    assert!(!set.is_consistent());
}

#[test]
fn should_refuse_a_file_above_the_maximum_size() {
    assert!(ed2k::check_size(MAX_FILE_SIZE + 1).is_err());
}

#[test]
fn should_hash_a_file_of_one_part_as_its_part_hash() {
    let part = hash16("31d6cfe0d16ae931b73c59d7e0c089c0");

    assert_eq!(ed2k::file_hash(&[part]).unwrap(), part);
}

#[test]
fn should_have_no_part_hash_beyond_the_last_data_part() {
    let set = Ed2kHashSet::new(PARTSIZE, EMPTY_PART_HASH, vec![EMPTY_PART_HASH; 2]).unwrap();

    assert_eq!(set.part_hash(0), Some(EMPTY_PART_HASH));
    assert_eq!(set.part_hash(1), None, "the empty part ending a file of one PARTSIZE holds no data");
}
