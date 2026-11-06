// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's CreditsTest, under its names: what each client gave and got, its place in our queue, and
//! `clients.met`, which keeps them.

#![allow(clippy::unwrap_used, clippy::float_cmp, clippy::cast_precision_loss)]

use std::path::Path;

use njmule_engine::upload::credits::{self, Credits};

const MIB: u64 = 1024 * 1024;
const PEER: [u8; 16] = [0x11; 16];
const OTHER: [u8; 16] = [0x22; 16];
const NOW: u64 = 1_000_000;

fn loaded_at(dir: &Path, epoch_second: u64) -> Credits {
    Credits::load(dir, Box::new(move || epoch_second))
}

fn received(credits: &mut Credits, client: [u8; 16], amounts: &[u64]) {
    for bytes in amounts {
        credits.downloaded(client, *bytes);
    }
}

fn sent(credits: &mut Credits, client: [u8; 16], amounts: &[u64]) {
    for bytes in amounts {
        credits.uploaded(client, *bytes);
    }
}

// What the restart scenarios saved: the first key pinned, credit gathered after it, and a client that gave nothing.
fn saved_with_a_pinned_key(dir: &Path) {
    let mut credits = loaded_at(dir, NOW);
    received(&mut credits, PEER, &[5 * MIB]);
    sent(&mut credits, PEER, &[MIB]);
    credits.identified(PEER, &[1, 2, 3]);
    credits.identified(PEER, &[9]);
    received(&mut credits, PEER, &[5 * MIB - 1]);
    sent(&mut credits, PEER, &[MIB - 1]);
    received(&mut credits, OTHER, &[0]);
    credits.save();
}

#[test]
fn should_give_a_client_that_gave_us_nothing_a_ratio_of_one() {
    let dir = tempfile::tempdir().unwrap();
    let credits = loaded_at(dir.path(), NOW);

    assert_eq!(credits.ratio(&PEER, true), 1.0);
}

#[test]
fn should_keep_the_ratio_at_one_below_one_mebibyte_received() {
    let dir = tempfile::tempdir().unwrap();
    let mut credits = loaded_at(dir.path(), NOW);
    received(&mut credits, PEER, &[MIB / 2]);

    assert_eq!(credits.ratio(&PEER, true), 1.0, "below 1 MiB");
}

// Nothing uploaded and 8 MiB received: the linear ramp is the lowest term.
#[test]
fn should_raise_the_ratio_along_the_ramp_with_what_a_client_gave_us() {
    let dir = tempfile::tempdir().unwrap();
    let mut credits = loaded_at(dir.path(), NOW);
    received(&mut credits, PEER, &[MIB / 2, 7 * MIB + MIB / 2]);

    let ratio = credits.ratio(&PEER, true);

    let expected = (8.0 * MIB as f64 - MIB as f64) / (9_646_899.0 - MIB as f64) * 2.34 + 1.0;
    assert!((ratio - expected).abs() < 1e-9, "{ratio} against {expected}");
}

#[test]
fn should_give_a_ratio_of_one_to_a_client_whose_identity_is_in_doubt() {
    let dir = tempfile::tempdir().unwrap();
    let mut credits = loaded_at(dir.path(), NOW);
    received(&mut credits, PEER, &[MIB / 2, 7 * MIB + MIB / 2]);

    assert_eq!(credits.ratio(&PEER, false), 1.0, "identity in doubt");
}

// 100 MiB received: sqrt(100 + 2) caps the ratio of 10.
#[test]
fn should_cap_the_ratio_at_ten() {
    let dir = tempfile::tempdir().unwrap();
    let mut credits = loaded_at(dir.path(), NOW);
    received(&mut credits, PEER, &[MIB / 2, 7 * MIB + MIB / 2, 92 * MIB]);

    assert_eq!(credits.ratio(&PEER, true), 10.0);
}

#[test]
fn should_lower_the_ratio_with_what_we_sent_the_client() {
    let dir = tempfile::tempdir().unwrap();
    let mut credits = loaded_at(dir.path(), NOW);
    received(&mut credits, PEER, &[MIB / 2, 7 * MIB + MIB / 2, 92 * MIB]);
    sent(&mut credits, PEER, &[50 * MIB]);

    assert_eq!(credits.ratio(&PEER, true), 4.0);
}

#[test]
fn should_bring_the_ratio_back_to_one_when_we_sent_the_client_far_more() {
    let dir = tempfile::tempdir().unwrap();
    let mut credits = loaded_at(dir.path(), NOW);
    received(&mut credits, PEER, &[MIB / 2, 7 * MIB + MIB / 2, 92 * MIB]);
    sent(&mut credits, PEER, &[50 * MIB, 1000 * MIB]);

    assert_eq!(credits.ratio(&PEER, true), 1.0);
}

// Pinning the first key wipes what the hash gathered before.
#[test]
fn should_wipe_what_a_client_gave_before_its_first_key_is_pinned() {
    let dir = tempfile::tempdir().unwrap();
    let mut credits = loaded_at(dir.path(), NOW);
    received(&mut credits, PEER, &[5 * MIB]);
    sent(&mut credits, PEER, &[MIB]);

    credits.identified(PEER, &[1, 2, 3]);

    assert_eq!(credits.get(&PEER).unwrap().downloaded, 1);
}

#[test]
fn should_keep_credits_and_the_first_key_across_a_restart() {
    let dir = tempfile::tempdir().unwrap();
    saved_with_a_pinned_key(dir.path());

    let credits = loaded_at(dir.path(), NOW + 3600);

    let peer = credits.get(&PEER).unwrap();
    assert_eq!((peer.downloaded, peer.uploaded), (5 * MIB, MIB));
    assert_eq!(peer.public_key, [1, 2, 3]);
    assert!(credits.get(&OTHER).is_none());
}

#[test]
fn should_forget_clients_not_seen_for_too_long() {
    let dir = tempfile::tempdir().unwrap();
    saved_with_a_pinned_key(dir.path());

    let credits = loaded_at(dir.path(), NOW + credits::EXPIRY.as_secs() + 1);

    assert!(credits.get(&PEER).is_none());
}
