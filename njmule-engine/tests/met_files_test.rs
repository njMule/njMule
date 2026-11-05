// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's MetFilesTest, under their names: the binary files of a profile, written and read
//! back.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use njmule_engine::files::hash::ed2k::{self, EMPTY_PART_HASH, PARTSIZE};
use njmule_engine::persist::format::clients_met::{self, Credit};
use njmule_engine::persist::format::friends_met::{self, Friend};
use njmule_engine::persist::format::known_met::{self, KnownFile};
use njmule_engine::persist::format::known2_met::{self, Entry};
use njmule_engine::persist::format::part_met::{self, Gap};
use njmule_engine::persist::format::server_met::{self, Server};
use njmule_engine::persist::format::{cancelled_met, preferences_dat};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag, server_tag};
use rand::{Rng, RngExt};

const H: [u8; 16] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];
// One server 1.2.3.4:4661 with name "abc" (old tag encoding).
const SERVER_MET: &str = concat!("e0", "01000000", "01020304", "3512", "01000000", "020100010300616263");
const ZERO_AICH: [u8; 20] = [0; 20];

fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

fn string(id: u8, value: &str) -> Tag {
    Tag::String { name: TagName::id(id), value: value.to_owned() }
}

fn named(name: &[u8], value: u64) -> Tag {
    Tag::Int { name: TagName::of_bytes(name), value }
}

fn credit() -> Credit {
    Credit {
        user_hash: H,
        uploaded: 5_000_000_000,
        downloaded: 42,
        last_seen: 1_700_000_000,
        public_key: vec![1, 2, 3],
    }
}

fn friend() -> Friend {
    Friend {
        user_hash: H,
        last_ip: 0x0102_0304,
        last_port: 4662,
        last_seen: 100,
        last_chatted: 200,
        tags: vec![string(friends_met::NAME, "pal"), Tag::Hash { name: TagName::id(friends_met::KAD_ID), value: H }],
    }
}

fn aich_entry() -> Entry {
    Entry { master_hash: ZERO_AICH, block_hashes: vec![ZERO_AICH, ZERO_AICH] }
}

fn part_met(tags: Vec<Tag>) -> part_met::Contents {
    part_met::Contents { version: part_met::VERSION, mtime: 0, file_hash: H, part_hashes: Vec::new(), tags }
}

fn gap(start: u64, end: u64) -> Gap {
    Gap::new(start, end).unwrap()
}

#[test]
fn should_read_the_ip_of_a_server_in_network_order() {
    let file = hex::decode(SERVER_MET).unwrap();

    let servers = server_met::read(&file).unwrap();

    assert_eq!(std::net::Ipv4Addr::from(servers[0].ip).to_string(), "1.2.3.4");
    assert_eq!(servers[0].port, 4661);
    assert_eq!(servers[0].name(), Some("abc"));
}

#[test]
fn should_write_back_the_server_file_it_reads() {
    let servers = server_met::read(&hex::decode(SERVER_MET).unwrap()).unwrap();

    let file = server_met::write(&servers).unwrap();

    assert_eq!(hex::encode(file), SERVER_MET);
}

#[test]
fn should_keep_the_unknown_tags_and_named_counters_of_a_server() {
    let servers = vec![Server {
        ip: 0x0A00_0001,
        port: 4242,
        tags: vec![string(server_tag::SERVERNAME, "Città"), named(server_tag::USERS, 1234), int(0x77, 9)],
    }];
    let file = server_met::write(&servers).unwrap();

    let read = server_met::read(&file).unwrap();

    assert_eq!(read, servers);
    assert_eq!(read[0].users(), Some(1234));
}

#[test]
fn should_read_back_the_known_files_it_writes_and_check_their_consistency() {
    let parts = vec![H, EMPTY_PART_HASH];
    let files = vec![
        KnownFile {
            mtime: 1_700_000_000,
            file_hash: ed2k::file_hash(&parts).unwrap(),
            part_hashes: parts,
            tags: vec![string(file_tag::FILENAME, "a.bin"), int(file_tag::FILESIZE, PARTSIZE)],
        },
        KnownFile { mtime: 1, file_hash: H, part_hashes: Vec::new(), tags: vec![int(file_tag::FILESIZE, PARTSIZE)] },
    ];
    let file = known_met::write(&files).unwrap();

    let read = known_met::read(&file).unwrap();

    assert_eq!(read, files);
    assert!(read[0].is_consistent());
    assert!(!read[1].is_consistent());
    assert_eq!(read[0].size(), Some(PARTSIZE));
}

fn known_file_of_the_size(size: u64) -> Vec<KnownFile> {
    vec![KnownFile { mtime: 0, file_hash: H, part_hashes: Vec::new(), tags: vec![int(file_tag::FILESIZE, size)] }]
}

#[test]
fn should_switch_known_met_to_sixty_four_bit_tags_for_large_files() {
    let file = known_met::write(&known_file_of_the_size(5_000_000_000)).unwrap();

    assert_eq!(file[0], known_met::HEADER_64BIT);
}

#[test]
fn should_read_back_the_size_of_a_large_known_file() {
    let file = known_met::write(&known_file_of_the_size(5_000_000_000)).unwrap();

    let read = known_met::read(&file).unwrap();

    assert_eq!(read[0].size(), Some(5_000_000_000));
}

#[test]
fn should_write_the_part_met_gaps_with_exclusive_ends() {
    let written = part_met(vec![string(file_tag::FILENAME, "x"), int(file_tag::FILESIZE, 1000)])
        .with_gaps(&[gap(0, 100), gap(900, 1000)]);
    let file = part_met::write(&written).unwrap();

    let read = part_met::read(&file).unwrap();

    assert_eq!(read.gaps(), [gap(0, 100), gap(900, 1000)]);
    assert!(read.tags.contains(&named(b"\n1", 1000)));
    assert!(read.tags.contains(&named(b"\t1", 900)));
}

#[test]
fn should_clamp_a_gap_past_the_end_and_drop_an_incomplete_pair() {
    let contents =
        part_met(vec![int(file_tag::FILESIZE, 1000), named(b"\t0", 500), named(b"\n0", 5000), named(b"\t7", 10)]);

    let gaps = contents.gaps();

    assert_eq!(gaps, [gap(500, 1000)]);
}

#[test]
fn should_choose_the_large_version_of_part_met_above_four_gigabytes() {
    let file = part_met::write(&part_met(vec![int(file_tag::FILESIZE, 5_000_000_000)])).unwrap();

    assert_eq!(file[0], part_met::VERSION_LARGE);
}

#[test]
fn should_refuse_an_unknown_version_of_part_met() {
    assert!(part_met::read(&[0xE1]).is_err());
}

#[test]
fn should_write_clients_met_records_of_119_bytes() {
    let file = clients_met::write(&[credit()]).unwrap();

    assert_eq!(file.len(), 1 + 4 + 119);
}

#[test]
fn should_read_back_the_credits_it_writes() {
    let file = clients_met::write(&[credit()]).unwrap();

    assert_eq!(clients_met::read(&file).unwrap(), [credit()]);
}

#[test]
fn should_refuse_a_public_key_of_more_than_eighty_bytes_in_the_credits() {
    let mut file = clients_met::write(&[credit()]).unwrap();
    file[1 + 4 + 16 + 4 * 5 + 2] = 81;

    assert!(clients_met::read(&file).is_err());
}

#[test]
fn should_read_old_credits_without_keys() {
    let file = hex::decode(
        "11".to_owned()
            + "01000000"
            + &hex::encode(H)
            + "0a000000"
            + "14000000"
            + "00000000"
            + "01000000"
            + "00000000"
            + "0000",
    )
    .unwrap();

    let credits = clients_met::read(&file).unwrap();

    assert_eq!((credits[0].uploaded, credits[0].downloaded), ((1 << 32) + 10, 20));
    assert!(credits[0].public_key.is_empty());
}

#[test]
fn should_write_the_kad_id_of_a_friend_in_the_new_encoding() {
    let file = friends_met::write(&[friend()]).unwrap();

    assert!(hex::encode(file).contains(&("8102".to_owned() + &hex::encode(H))));
}

#[test]
fn should_read_back_the_friends_it_writes() {
    let file = friends_met::write(&[friend()]).unwrap();

    let read = friends_met::read(&file).unwrap();

    assert_eq!(read, [friend()]);
    assert_eq!(read[0].name(), Some("pal"));
    assert_eq!(read[0].kad_id(), Some(H));
}

#[test]
fn should_read_back_the_cancelled_files_it_writes() {
    let key = cancelled_met::key(0x0102_0304, &H);
    let file = cancelled_met::write(0x0102_0304, &[key]).unwrap();

    let read = cancelled_met::read(&file).unwrap();

    assert_eq!(read.seed, 0x0102_0304);
    assert!(!read.legacy);
    assert_eq!(read.keys, [key]);
}

#[test]
fn should_hide_the_hash_of_a_cancelled_file() {
    assert_ne!(cancelled_met::key(0x0102_0304, &H), H);
}

#[test]
fn should_recognise_legacy_cancelled_files() {
    let file = hex::decode("0e".to_owned() + "01000000" + &hex::encode(H) + "00").unwrap();

    assert!(cancelled_met::read(&file).unwrap().legacy);
}

#[test]
fn should_read_back_the_aich_trees_it_writes() {
    let file = known2_met::write(&[aich_entry(), aich_entry()]).unwrap();

    assert_eq!(known2_met::read(&file).unwrap(), [aich_entry(), aich_entry()]);
}

#[test]
fn should_read_the_old_version_of_the_aich_trees() {
    let zero = "00".repeat(20);
    let file = hex::decode(format!("{zero}0200{zero}{zero}")).unwrap();

    assert_eq!(known2_met::read_old(&file).unwrap(), [aich_entry()]);
}

#[test]
fn should_refuse_an_impossible_count_of_aich_trees() {
    let file = hex::decode(format!("02{}ffffff7f", "00".repeat(20))).unwrap();

    assert!(known2_met::read(&file).is_err());
}

#[test]
fn should_hold_the_user_hash_in_preferences_dat() {
    let mut user_hash = H;
    user_hash[5] = 14;
    user_hash[14] = 111;
    let mut file = vec![0; 61];
    file[0] = preferences_dat::VERSION;
    file[1..17].copy_from_slice(&user_hash);

    let read = preferences_dat::read(&file).unwrap();

    assert_eq!(read.user_hash, user_hash);
    assert!(preferences_dat::is_marked_user_hash(&read.user_hash));
    assert_eq!(read.window_placement.len(), 44);
}

#[test]
fn should_fail_on_garbage_only_with_a_decode_exception() {
    let mut random = rand::rng();
    for _ in 0..2_000 {
        let mut data = vec![0; random.random_range(0..=200)];
        random.fill_bytes(&mut data);

        // Garbage is refused with a DecodeError or read as something, never a panic.
        let _ = server_met::read(&data);
        let _ = known_met::read(&data);
        let _ = part_met::read(&data);
        let _ = clients_met::read(&data);
        let _ = friends_met::read(&data);
        let _ = cancelled_met::read(&data);
        let _ = known2_met::read(&data);
        let _ = known2_met::read_old(&data);
        let _ = preferences_dat::read(&data);
    }
}
