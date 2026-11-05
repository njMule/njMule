// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's EmuleProfileTest, under its names: a real eMule profile, read and never written, from
//! `NJMULE_EMULE_CONFIG`, and its downloads in progress from `NJMULE_EMULE_TEMP`. Without them each test passes at
//! once. `cargo test --test emule_profile_test -- --nocapture` shows the counts, never names or hashes.

#![allow(clippy::unwrap_used, clippy::panic, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fmt::Display;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

use njmule_engine::crypto::rsa::{self, NETWORK_KEY_BITS};
use njmule_engine::crypto::rsa_keys::{self, MAX_PUBLIC_KEY_SIZE};
use njmule_engine::files::hash::ed2k;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::ini_file::IniFile;
use njmule_engine::persist::format::{
    cancelled_met, clients_met, friends_met, kad_index_files, known_met, known2_met, nodes_dat, part_met,
    preferences_dat, preferences_kad_dat, search_files, server_met, text_files,
};

fn directory(variable: &str) -> Option<PathBuf> {
    std::env::var_os(variable).filter(|value| !value.is_empty()).map(PathBuf::from)
}

fn read(config: &Path, name: &str) -> Vec<u8> {
    fs::read(config.join(name)).unwrap_or_else(|e| panic!("{name}: {e}"))
}

fn report(file: &str, what: impl Display) {
    println!("{file}: {what}");
}

// The MD4 of `length` bytes of `file` from `start`.
fn md4(file: &Path, start: u64, length: u64) -> [u8; 16] {
    let mut input = File::open(file).unwrap();
    input.seek(SeekFrom::Start(start)).unwrap();
    let mut data = Vec::new();
    input.take(length).read_to_end(&mut data).unwrap();
    assert_eq!(data.len() as u64, length, "the data of a complete part ends early");
    ed2k::md4(&data)
}

#[test]
fn should_read_every_data_file() {
    let Some(config) = directory("NJMULE_EMULE_CONFIG") else { return };
    let file = |name: &str| read(&config, name);

    report("server.met", server_met::read(&file("server.met")).unwrap().len());
    let known = known_met::read(&file("known.met")).unwrap();
    let consistent = known.iter().filter(|known| known.is_consistent()).count();
    report("known.met", format!("{} ({consistent} consistent)", known.len()));
    report("known2_64.met", known2_met::read(&file("known2_64.met")).unwrap().len());
    report("cancelled.met", cancelled_met::read(&file("cancelled.met")).unwrap().keys.len());
    report("clients.met", clients_met::read(&file("clients.met")).unwrap().len());
    report("emfriends.met", friends_met::read(&file("emfriends.met")).unwrap().len());
    report("nodes.dat", nodes_dat::read(&file("nodes.dat")).unwrap().contacts.len());
    let kad_id = preferences_kad_dat::read(&file("preferencesKad.dat")).unwrap().kad_id;
    report("preferencesKad.dat", if kad_id == UInt128::ZERO { "zero id" } else { "ok" });
    let user_hash = preferences_dat::read(&file("preferences.dat")).unwrap().user_hash;
    let marked = preferences_dat::is_marked_user_hash(&user_hash);
    report("preferences.dat", if marked { "marked hash" } else { "unmarked hash" });
    report("load_index.dat", kad_index_files::read_load(&file("load_index.dat")).unwrap().loads.len());
    report("src_index.dat", kad_index_files::read_sources(&file("src_index.dat")).unwrap().keys.len());
    report("key_index.dat", kad_index_files::read_keywords(&file("key_index.dat")).unwrap().keywords.len());
    report("StoredSearches.met", search_files::read_stored(&file("StoredSearches.met")).unwrap().len());
    report("SearchSpam.met", search_files::read_spam(&file("SearchSpam.met")).unwrap().len());
    report("staticservers.dat", text_files::static_servers(&file("staticservers.dat")).len());
    report("shareddir.dat", text_files::entries(&file("shareddir.dat")).len());
    report("sharedfiles.dat", text_files::shared_files(&file("sharedfiles.dat")).len());
    report("addresses.dat", text_files::entries(&file("addresses.dat")).len());
    for ini in ["preferences.ini", "Category.ini", "statistics.ini"] {
        report(ini, format!("{} sections", IniFile::parse(&file(ini)).sections().len()));
    }

    assert_eq!(consistent, known.len());
}

#[test]
fn should_sign_and_verify_with_the_secure_ident_key() {
    let Some(config) = directory("NJMULE_EMULE_CONFIG") else { return };
    let key_file = config.join("cryptkey.dat");
    if !key_file.exists() {
        report("cryptkey.dat", "none in the profile");
        return;
    }
    let key = rsa_keys::read_key_file(&key_file).unwrap();
    let message = [1, 2, 3, 4];

    let signature = rsa::sign(&key, &message).unwrap();

    assert_eq!(key.public_key().modulus_bits(), NETWORK_KEY_BITS);
    assert!(rsa_keys::encode_public(&key.public_key()).len() <= MAX_PUBLIC_KEY_SIZE);
    assert!(rsa::verify(&key.public_key(), &message, &signature));
    report("cryptkey.dat", "signs and verifies");
}

#[test]
fn should_match_the_complete_parts_with_their_hashes() {
    let (Some(_), Some(temp)) = (directory("NJMULE_EMULE_CONFIG"), directory("NJMULE_EMULE_TEMP")) else { return };
    let parts_to_check = std::env::var("NJMULE_PARTS_TO_CHECK").ok().and_then(|n| n.parse().ok()).unwrap_or(3);
    let mut checked_parts = Vec::new();

    for entry in fs::read_dir(&temp).unwrap() {
        let met = entry.unwrap().path();
        let Some(name) = met.file_name().and_then(|name| name.to_str()).filter(|name| name.ends_with(".part.met"))
        else {
            continue;
        };
        let contents = part_met::read(&fs::read(&met).unwrap()).unwrap();
        let data = temp.join(name.trim_end_matches(".met"));
        let size = contents.size().unwrap();
        let gaps = contents.gaps();
        let mut checked = 0;
        for part in 0..ed2k::part_count(size) {
            if checked == parts_to_check {
                break;
            }
            let start = ed2k::part_start(part);
            let end = start + ed2k::part_length(size, part);
            let complete = gaps.iter().all(|gap| gap.start() >= end || gap.end() <= start);
            if complete && !contents.part_hashes.is_empty() && data.exists() {
                checked_parts.push((contents.part_hashes[part], md4(&data, start, end - start)));
                checked += 1;
            }
        }
        let parts = ed2k::part_count(size);
        report(name, format!("{parts} parts, {} gaps, {checked} complete parts verified", gaps.len()));
    }

    for (expected, actual) in checked_parts {
        assert_eq!(actual, expected);
    }
}
