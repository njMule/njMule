// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Which sources of a download are kept for the next run: those that served it best now, then those kept before and not
//! seen again, until they are a week old.

use std::collections::{HashMap, HashSet};
use std::time::Duration;

use crate::files::part_file::{KnownSource, MAX_KNOWN_SOURCES};

/// A source kept this long without being found good again is let go.
pub const LIFETIME: Duration = Duration::from_secs(7 * 24 * 3600);
/// A source kept less than this ago keeps the date it had: the same sources leave the download as saved.
pub const FRESH: Duration = Duration::from_secs(24 * 3600);

/// How a source stands now, best first.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Standing {
    Downloading,
    Queued,
    Answered,
}

/// A source as it stands now: its place in the queue of the client when queued, and how many parts it has of the file.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Candidate {
    pub source: KnownSource,
    pub standing: Standing,
    pub rank: u32,
    pub parts: u32,
}

/// The sources to keep: the best of those `now`, then those kept `before` that are not among them, while still young at
/// `now_seconds`, up to [`MAX_KNOWN_SOURCES`].
pub fn choose(now: &[Candidate], before: &[KnownSource], now_seconds: u64) -> Vec<KnownSource> {
    let mut kept_before: HashMap<(u32, u16), &KnownSource> = HashMap::new();
    for source in before {
        kept_before.entry(place(source)).or_insert(source);
    }
    let mut best: Vec<&Candidate> = now.iter().collect();
    // A stable sort: of sources that stand alike, the first stays first.
    best.sort_by_key(|candidate| {
        let rank = if candidate.standing == Standing::Queued { candidate.rank } else { 0 };
        (candidate.standing, rank, std::cmp::Reverse(candidate.parts))
    });
    let fresh_since = now_seconds.saturating_sub(FRESH.as_secs());
    let mut kept = Vec::new();
    let mut places = HashSet::new();
    for candidate in best {
        let source = candidate.source;
        if kept.len() < MAX_KNOWN_SOURCES && places.insert(place(&source)) {
            let seen = match kept_before.get(&place(&source)) {
                Some(earlier) if earlier.seen >= fresh_since => earlier.seen,
                _ => source.seen,
            };
            kept.push(KnownSource { seen, ..source });
        }
    }
    for source in young(before, now_seconds) {
        if kept.len() < MAX_KNOWN_SOURCES && places.insert(place(&source)) {
            kept.push(source);
        }
    }
    kept
}

/// The sources of `kept` found good within [`LIFETIME`] of `now_seconds`.
pub fn young(kept: &[KnownSource], now_seconds: u64) -> Vec<KnownSource> {
    let oldest = now_seconds.saturating_sub(LIFETIME.as_secs());
    kept.iter().filter(|source| source.seen >= oldest).copied().collect()
}

fn place(source: &KnownSource) -> (u32, u16) {
    (source.ip, source.port)
}
