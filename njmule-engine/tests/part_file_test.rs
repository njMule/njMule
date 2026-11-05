// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's PartFileTest, under their names; those of the sources kept for the next run, of
//! files above 4 GiB on the disk and its properties over any file wait for their ports.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects, clippy::panic)]

use std::fs;
use std::path::Path;

use njmule_engine::api::download_info::DownloadPriority;
use njmule_engine::files::hash::ed2k::{self, EMBLOCKSIZE, PARTSIZE};
use njmule_engine::files::part_file::{Block, PartFile, Status};
use njmule_engine::files::part_file_storage::{DiskDone, Done, PartFileStorage};
use njmule_engine::persist::format::part_met;
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};
use tokio::sync::mpsc;

const B: u64 = EMBLOCKSIZE;
const P: u64 = PARTSIZE;

fn hash(n: u8) -> [u8; 16] {
    [n; 16]
}

fn random() -> StdRng {
    StdRng::seed_from_u64(42)
}

fn small(dir: &Path, size: u64) -> PartFile {
    PartFile::create(1, dir, "small.bin", size, hash(9), None, Vec::new()).unwrap()
}

fn with_hash_set(dir: &Path, number: u32, name: &str, size: u64, parts: &[[u8; 16]]) -> PartFile {
    PartFile::create(number, dir, name, size, ed2k::file_hash(parts).unwrap(), None, parts.to_vec()).unwrap()
}

fn all(file: &PartFile) -> Vec<bool> {
    vec![true; file.part_count()]
}

fn block(start: u64, end: u64) -> Block {
    Block { start, end }
}

fn drain(file: &mut PartFile, per_call: usize) -> Vec<Block> {
    let parts = all(file);
    let mut random = random();
    let mut blocks = Vec::new();
    loop {
        let next = file.next_blocks(&parts, per_call, &mut random);
        if next.is_empty() {
            return blocks;
        }
        blocks.extend(next);
    }
}

fn hand_out(file: &mut PartFile, count: usize) -> Vec<Block> {
    let parts = all(file);
    file.next_blocks(&parts, count, &mut random())
}

#[test]
fn should_hand_out_each_missing_block_once_until_it_is_written_or_released() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = small(dir.path(), 500_000);
    assert_eq!(hand_out(&mut file, 3), [block(0, B), block(B, 2 * B), block(2 * B, 500_000)]);
    assert!(hand_out(&mut file, 3).is_empty());
    file.written(0, B);
    file.release(2 * B, 500_000);

    let blocks = hand_out(&mut file, 3);

    assert_eq!(blocks, [block(2 * B, 500_000)]);
    assert_eq!(file.completed_bytes(), B);
    assert!(file.has_hash_set());
    assert_eq!(file.part_hash(0), Some(hash(9)));
}

#[test]
fn should_ask_the_blocks_of_another_source_again_near_the_end() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = small(dir.path(), 500_000);
    hand_out(&mut file, 3);

    let blocks = file.endgame_blocks(&all(&file), 3, &[]);

    assert_eq!(blocks, [block(0, B), block(B, 2 * B), block(2 * B, 500_000)]);
}

#[test]
fn should_ask_a_block_of_the_endgame_of_two_sources_at_most() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = small(dir.path(), 500_000);
    hand_out(&mut file, 3);
    file.endgame_blocks(&all(&file), 3, &[]);

    let blocks = file.endgame_blocks(&all(&file), 3, &[]);

    assert!(blocks.is_empty());
}

#[test]
fn should_never_ask_a_source_its_own_blocks_again() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = small(dir.path(), 500_000);
    let own = hand_out(&mut file, 3);

    let blocks = file.endgame_blocks(&all(&file), 3, &own);

    assert!(blocks.is_empty());
}

#[test]
fn should_ask_a_block_again_only_while_it_is_missing() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = small(dir.path(), 500_000);
    hand_out(&mut file, 3);
    file.written(0, B);

    let blocks = file.endgame_blocks(&all(&file), 3, &[]);

    assert_eq!(blocks, [block(B, 2 * B), block(2 * B, 500_000)]);
}

#[test]
fn should_not_ask_again_a_block_on_its_way_to_the_disk() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = small(dir.path(), 500_000);
    hand_out(&mut file, 3);
    file.arriving(0, B);

    let blocks = file.endgame_blocks(&all(&file), 3, &[]);

    assert_eq!(blocks, [block(B, 2 * B), block(2 * B, 500_000)]);
}

#[test]
fn should_keep_the_endgame_for_the_last_part() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = small(dir.path(), 2 * P);
    drain(&mut file, 3);

    let blocks = file.endgame_blocks(&all(&file), 3, &[]);

    assert!(blocks.is_empty());
}

#[test]
fn should_cover_files_of_every_size_around_the_part_boundaries_with_blocks() {
    for size in [1, B - 1, B, B + 1, P - 1, P, P + 1, 2 * P - 1, 2 * P, 2 * P + 1] {
        let dir = tempfile::tempdir().unwrap();
        let mut file = PartFile::create(1, dir.path(), "edge.bin", size, hash(1), None, Vec::new()).unwrap();

        let blocks = drain(&mut file, 3);

        assert_eq!(file.part_count(), usize::try_from(size.div_ceil(P)).unwrap(), "{size}");
        for block in &blocks {
            let part_start = block.start / P * P;
            assert_eq!((block.start - part_start) / B, (block.end - 1 - part_start) / B, "{block:?} of {size}");
        }
        assert_eq!(blocks.iter().map(|b| b.end).max(), Some(size));
    }
}

#[test]
fn should_cut_a_whole_part_into_its_aich_blocks() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = PartFile::create(1, dir.path(), "one.bin", P, hash(1), None, Vec::new()).unwrap();

    let blocks = drain(&mut file, 3);

    assert_eq!(blocks.len(), 53);
    assert_eq!(blocks.last().unwrap().length(), P - 52 * B);
}

#[test]
fn should_refuse_an_empty_file() {
    let dir = tempfile::tempdir().unwrap();

    let created = PartFile::create(1, dir.path(), "empty.bin", 0, hash(1), None, Vec::new());

    assert!(created.is_err());
}

#[test]
fn should_hand_out_a_released_range_again() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = small(dir.path(), 500_000);
    hand_out(&mut file, 3);
    file.release(B + 1000, B + 2000);

    let blocks = drain(&mut file, 3);

    assert_eq!(blocks, [block(B + 1000, B + 2000)]);
}

#[test]
fn should_pick_the_rarest_part_the_source_has() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = with_hash_set(dir.path(), 1, "big.bin", 2 * P + 100, &[hash(1), hash(2), hash(3)]);
    file.add_availability(&[true, true, true], true);
    file.add_availability(&[true, false, true], true);

    let blocks = hand_out(&mut file, 1);

    assert_eq!(blocks, [block(P, P + B)]);
}

#[test]
fn should_start_with_any_of_the_equally_rare_parts() {
    let dir = tempfile::tempdir().unwrap();
    let file = with_hash_set(dir.path(), 1, "big.bin", 2 * P + 100, &[hash(1), hash(2), hash(3)]);
    let mut starts = std::collections::HashSet::new();

    for seed in 0..40 {
        let mut fresh = file.clone();
        let parts = all(&fresh);
        let blocks = fresh.next_blocks(&parts, 1, &mut StdRng::seed_from_u64(seed));
        starts.insert(blocks[0].start);
    }

    assert_eq!(starts, [0, P, 2 * P].into_iter().collect());
}

#[test]
fn should_take_back_a_corrupt_part() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = with_hash_set(dir.path(), 1, "big.bin", 2 * P + 100, &[hash(1), hash(2), hash(3)]);
    file.written(0, P);
    assert!(file.is_part_complete(0));

    file.corrupt(0);

    assert!(!file.is_part_complete(0));
    assert_eq!(file.complete_parts(), [false, false, false]);
}

#[test]
fn should_finish_a_begun_part_first_and_cut_blocks_on_the_aich_boundaries() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = with_hash_set(dir.path(), 2, "big.bin", 2 * P + 100, &[hash(1), hash(2), hash(3)]);
    file.add_availability(&[true, true, true], true);
    file.written(P, P + 1000);

    let blocks = hand_out(&mut file, 2);

    assert_eq!(blocks, [block(P + 1000, P + B), block(P + B, P + 2 * B)]);
}

#[test]
fn should_map_what_is_missing_requested_and_available() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = small(dir.path(), 500_000);
    file.written(0, 100_000);
    let handed = hand_out(&mut file, 1);
    file.add_availability(&[true], true);
    file.add_availability(&[true], true);

    let map = file.map();

    assert_eq!(map.size, 500_000);
    assert_eq!(map.missing, [(100_000, 500_000)]);
    assert_eq!(map.requested, [(handed[0].start, handed[0].end)]);
    assert_eq!(map.sources, [2]);
}

#[test]
fn should_have_no_hash_set_from_the_file_hash_alone() {
    let dir = tempfile::tempdir().unwrap();
    let file_hash = ed2k::file_hash(&[hash(1), hash(2)]).unwrap();

    let file = PartFile::create(1, dir.path(), "f", P + 1, file_hash, None, Vec::new()).unwrap();

    assert!(!file.has_hash_set());
    assert_eq!(file.part_hash(1), None);
}

#[test]
fn should_refuse_a_hash_set_that_does_not_match_the_file_hash() {
    let dir = tempfile::tempdir().unwrap();
    let file_hash = ed2k::file_hash(&[hash(1), hash(2)]).unwrap();
    let mut file = PartFile::create(1, dir.path(), "f", P + 1, file_hash, None, Vec::new()).unwrap();

    let taken = file.set_hash_set(vec![hash(2), hash(1)]);

    assert!(!taken);
    assert!(!file.has_hash_set());
}

#[test]
fn should_accept_a_hash_set_that_matches_the_file_hash() {
    let dir = tempfile::tempdir().unwrap();
    let file_hash = ed2k::file_hash(&[hash(1), hash(2)]).unwrap();
    let mut file = PartFile::create(1, dir.path(), "f", P + 1, file_hash, None, Vec::new()).unwrap();

    let taken = file.set_hash_set(vec![hash(1), hash(2)]);

    assert!(taken);
    assert_eq!(file.part_hash(1), Some(hash(2)));
}

#[test]
fn should_ask_the_sources_for_the_hash_set_when_a_link_leaves_out_the_empty_part_of_an_exact_multiple() {
    let dir = tempfile::tempdir().unwrap();
    let exact = ed2k::file_hash(&[hash(1), ed2k::EMPTY_PART_HASH]).unwrap();

    let file = PartFile::create(1, dir.path(), "a", P, exact, None, vec![hash(1)]).unwrap();

    assert!(!file.has_hash_set(), "an exact multiple also hashes the empty part after it");
}

#[test]
fn should_need_only_the_file_hash_for_one_part() {
    let dir = tempfile::tempdir().unwrap();

    let file = PartFile::create(3, dir.path(), "c", 1000, hash(3), None, vec![hash(3)]).unwrap();

    assert!(file.has_hash_set());
    assert!(file.part_hashes().is_empty(), "one part needs only the file hash");
}

#[test]
fn should_take_the_hash_set_of_a_link_that_proves_the_file_hash() {
    let dir = tempfile::tempdir().unwrap();
    let exact = [hash(1), ed2k::EMPTY_PART_HASH];

    let file = PartFile::create(4, dir.path(), "d", P, ed2k::file_hash(&exact).unwrap(), None, exact.to_vec()).unwrap();

    assert_eq!(file.part_hashes(), exact);
    assert_eq!(file.part_count(), 1);
    assert_eq!(file.part_hash(0), Some(hash(1)));
}

#[test]
fn should_save_and_load_its_progress() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = with_hash_set(dir.path(), 7, "film.mkv", 2 * P + 5, &[hash(1), hash(2), hash(3)]);
    file.written(0, P);
    file.written(P + 10, P + 20);
    file.set_status(Status::Paused);
    file.save().unwrap();

    let loaded = PartFile::load(dir.path(), 7).unwrap();

    assert_eq!(loaded.name(), "film.mkv");
    assert_eq!(loaded.size(), 2 * P + 5);
    assert_eq!(loaded.completed_bytes(), P + 10);
    assert!(loaded.is_part_complete(0));
    assert_eq!(loaded.status(), Status::Paused);
    assert_eq!(loaded.part_hash(2), Some(hash(3)));
    assert_eq!(loaded.met_file().file_name().unwrap(), "007.part.met");
}

#[test]
fn should_follow_the_rarity_when_new() {
    let dir = tempfile::tempdir().unwrap();

    let file = PartFile::create(3, dir.path(), "a.bin", 5000, hash(1), None, Vec::new()).unwrap();

    assert_eq!(file.priority(), DownloadPriority::Auto, "new downloads follow their rarity");
}

#[test]
fn should_keep_its_priority() {
    for priority in [DownloadPriority::Low, DownloadPriority::Normal, DownloadPriority::High, DownloadPriority::Auto] {
        let dir = tempfile::tempdir().unwrap();
        let mut file = PartFile::create(3, dir.path(), "a.bin", 5000, hash(1), None, Vec::new()).unwrap();
        file.set_priority(priority);
        file.save().unwrap();

        let loaded = PartFile::load(dir.path(), 3).unwrap();

        assert_eq!(loaded.priority(), priority);
    }
}

#[test]
fn should_keep_whether_it_is_stopped_and_its_category() {
    let dir = tempfile::tempdir().unwrap();
    let mut file = PartFile::create(3, dir.path(), "a.bin", 5000, hash(1), None, Vec::new()).unwrap();
    file.set_priority(DownloadPriority::High);
    file.set_status(Status::Stopped);
    file.set_category(4);
    file.save().unwrap();

    let loaded = PartFile::load(dir.path(), 3).unwrap();

    assert_eq!(loaded.category(), 4);
    assert_eq!(loaded.status(), Status::Stopped);
    let saved = part_met::read(&fs::read(loaded.met_file()).unwrap()).unwrap();
    assert!(saved.paused(), "other clients see a paused file");
    assert_eq!(saved.download_priority(), Some(2));
}

// The disk thread's next report.
fn next(done: &mut mpsc::UnboundedReceiver<DiskDone>) -> Done {
    done.blocking_recv().unwrap().work
}

fn bytes(size: usize, seed: u64) -> Vec<u8> {
    let mut data = vec![0u8; size];
    StdRng::seed_from_u64(seed).fill(&mut data[..]);
    data
}

// Written from the middle, then from the start.
fn written_out_of_order(
    dir: &Path,
    data: &[u8],
    middle: usize,
) -> (PartFileStorage, mpsc::UnboundedReceiver<DiskDone>) {
    let (sender, mut done) = mpsc::unbounded_channel();
    let storage = PartFileStorage::open(&dir.join("001.part"), 1, sender).unwrap();
    storage.write(middle as u64, data[middle..].to_vec());
    storage.write(0, data[..middle].to_vec());
    for _ in 0..2 {
        assert!(matches!(next(&mut done), Done::Written { result: Ok(()), .. }));
    }
    (storage, done)
}

#[test]
fn should_hash_a_part_written_out_of_order_from_disk() {
    let dir = tempfile::tempdir().unwrap();
    let data = bytes(300_000, 1);
    let (storage, mut done) = written_out_of_order(dir.path(), &data, 100_000);

    storage.hash_part(300_000, 0, false);

    assert!(matches!(next(&mut done), Done::PartHashed { part: 0, result: Ok(hash), .. } if hash == ed2k::md4(&data)));
}

#[test]
fn should_move_the_finished_file() {
    let dir = tempfile::tempdir().unwrap();
    let data = bytes(300_000, 2);
    let (mut storage, mut done) = written_out_of_order(dir.path(), &data, 100_000);

    storage.finish(dir.path().join("incoming"), "done.bin".to_owned());

    let Done::Finished(Ok(moved)) = next(&mut done) else {
        panic!("not moved");
    };
    assert_eq!(moved, dir.path().join("incoming").join("done.bin"));
    assert_eq!(fs::read(&moved).unwrap(), data);
    assert!(!dir.path().join("001.part").exists());
}

#[test]
fn should_never_replace_a_file_with_the_name_of_a_finished_one() {
    let dir = tempfile::tempdir().unwrap();
    let incoming = dir.path().join("incoming");
    fs::create_dir_all(&incoming).unwrap();
    fs::write(incoming.join("same.bin"), "already here").unwrap();
    let data = bytes(3, 3);
    let (mut storage, mut done) = written_out_of_order(dir.path(), &data, 1);

    storage.finish(incoming.clone(), "same.bin".to_owned());

    let Done::Finished(Ok(moved)) = next(&mut done) else {
        panic!("not moved");
    };
    assert_eq!(moved, incoming.join("same (2).bin"));
    assert_eq!(fs::read_to_string(incoming.join("same.bin")).unwrap(), "already here");
    assert_eq!(fs::read(&moved).unwrap(), data);
}
