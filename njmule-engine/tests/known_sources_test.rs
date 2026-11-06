// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KnownSourcesTest, under its names: which sources of a download are kept for the next run.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]

use njmule_engine::download::known_sources::{self, Candidate, Standing};
use njmule_engine::files::part_file::{KnownSource, MAX_KNOWN_SOURCES};

const NOW: u64 = 1_760_000_000;
const DAY: u64 = 86_400;

fn source(port: u16, seen: u64) -> KnownSource {
    KnownSource { ip: 0x5511_7400, port, seen, user_hash: None, crypt_options: 0 }
}

fn now(port: u16, standing: Standing, rank: u32, parts: u32) -> Candidate {
    Candidate { source: source(port, NOW), standing, rank, parts }
}

fn ports(kept: &[KnownSource]) -> Vec<u16> {
    kept.iter().map(|source| source.port).collect()
}

#[test]
fn should_keep_first_the_sources_that_served_best() {
    let candidates = [
        now(1, Standing::Answered, 0, 3),
        now(2, Standing::Queued, 5, 0),
        now(3, Standing::Downloading, 0, 1),
        now(4, Standing::Answered, 0, 7),
        now(5, Standing::Queued, 2, 0),
    ];

    let kept = known_sources::choose(&candidates, &[], NOW);

    assert_eq!(ports(&kept), [3, 5, 2, 4, 1]);
}

#[test]
fn should_keep_after_them_the_sources_kept_before_until_a_week_old() {
    let before = [source(2, NOW - 6 * DAY), source(3, NOW - 8 * DAY), source(1, NOW - 2 * DAY)];

    let kept = known_sources::choose(&[now(1, Standing::Answered, 0, 1)], &before, NOW);

    assert_eq!(ports(&kept), [1, 2]);
}

#[test]
fn should_keep_the_date_of_a_source_kept_less_than_a_day_ago() {
    let before = [source(1, NOW - 3_600), source(2, NOW - 2 * DAY)];
    let candidates = [now(1, Standing::Answered, 0, 1), now(2, Standing::Answered, 0, 1)];

    let kept = known_sources::choose(&candidates, &before, NOW);

    assert_eq!(kept.iter().map(|source| source.seen).collect::<Vec<_>>(), [NOW - 3_600, NOW]);
}

#[test]
fn should_keep_no_more_sources_than_the_most() {
    let candidates: Vec<Candidate> = (1..=MAX_KNOWN_SOURCES + 20)
        .map(|port| now(u16::try_from(port).unwrap(), Standing::Answered, 0, 1))
        .collect();

    let kept = known_sources::choose(&candidates, &[source(60_000, NOW)], NOW);

    assert_eq!(kept.len(), MAX_KNOWN_SOURCES);
}
