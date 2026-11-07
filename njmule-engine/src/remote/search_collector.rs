// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The results of the searches started through the API, gathered from the engine's events, so that a front end reads
//! them when it wants: the latest searches only, each with a bounded number of results.

use std::time::{Duration, Instant};

use indexmap::IndexMap;

use crate::api::search_result::SearchResult;

pub const SEARCHES_KEPT: usize = 10;
pub const RESULTS_KEPT: usize = 1000;
/// How long a search counts as running at most, should its end never be told.
pub const LONGEST_SEARCH: Duration = Duration::from_secs(3 * 60);

/// What a search started here asked for.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Asked {
    pub expression: String,
    pub scope: String,
}

/// A search started here, with the results it has and whether more may come.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Listed {
    pub id: u64,
    pub asked: Asked,
    pub results: usize,
    pub running: bool,
}

pub type Clock = Box<dyn Fn() -> Instant + Send + Sync>;

pub struct SearchCollector {
    // By search id, oldest first.
    ours: IndexMap<u64, Found>,
    others: IndexMap<u64, Found>,
    clock: Clock,
}

struct Found {
    results: IndexMap<[u8; 16], SearchResult>,
    asked: Asked,
    started_at: Instant,
    ended: bool,
}

impl Found {
    fn new(asked: Asked, started_at: Instant) -> Self {
        Self { results: IndexMap::new(), asked, started_at, ended: false }
    }
}

impl Default for SearchCollector {
    fn default() -> Self {
        Self::new(Box::new(Instant::now))
    }
}

impl SearchCollector {
    pub fn new(clock: Clock) -> Self {
        Self { ours: IndexMap::new(), others: IndexMap::new(), clock }
    }

    /// Notes a search started here and what it asked for, with what came before its id was known.
    pub fn started(&mut self, search: u64, asked: Asked) {
        let mut found = Found::new(asked, (self.clock)());
        if let Some(early) = self.others.shift_remove(&search) {
            found.results = early.results;
            found.ended = early.ended;
        }
        self.ours.insert(search, found);
        trim(&mut self.ours);
    }

    /// Forgets a search started here.
    pub fn forget(&mut self, search: u64) -> bool {
        self.ours.shift_remove(&search).is_some()
    }

    /// The results of a search started here, in the order they came; none for an unknown one.
    pub fn results(&self, search: u64) -> Option<Vec<SearchResult>> {
        self.ours.get(&search).map(|found| found.results.values().cloned().collect())
    }

    pub fn result(&self, search: u64, hash: &[u8; 16]) -> Option<SearchResult> {
        self.ours.get(&search).and_then(|found| found.results.get(hash)).cloned()
    }

    /// The searches started here, oldest first.
    pub fn searches(&self) -> Vec<Listed> {
        self.ours
            .iter()
            .map(|(id, found)| Listed {
                id: *id,
                asked: found.asked.clone(),
                results: found.results.len(),
                running: self.running(*id),
            })
            .collect()
    }

    /// Whether a search started here may still bring results.
    pub fn running(&self, search: u64) -> bool {
        self.ours.get(&search).is_some_and(|found| {
            !found.ended && (self.clock)().saturating_duration_since(found.started_at) < LONGEST_SEARCH
        })
    }

    /// Notes that a search started here was asked for more results: it runs again from now.
    pub fn resumed(&mut self, search: u64) {
        let now = (self.clock)();
        if let Some(found) = self.ours.get_mut(&search) {
            found.started_at = now;
        }
    }

    /// Keeps results of a search; they may come before the call that started it notes its id.
    pub fn add(&mut self, search: u64, results: Vec<SearchResult>) {
        let found = self.find(search);
        for result in results {
            if found.results.len() < RESULTS_KEPT || found.results.contains_key(&result.hash) {
                found.results.insert(result.hash, result);
            }
        }
    }

    pub fn ended(&mut self, search: u64) {
        self.find(search).ended = true;
    }

    fn find(&mut self, search: u64) -> &mut Found {
        let now = (self.clock)();
        if self.ours.contains_key(&search) {
            return self.ours.entry(search).or_insert_with(|| Found::new(Asked::default(), now));
        }
        if !self.others.contains_key(&search) {
            // The newest is last: the trim leaves it.
            self.others.insert(search, Found::new(Asked::default(), now));
            trim(&mut self.others);
        }
        self.others.entry(search).or_insert_with(|| Found::new(Asked::default(), now))
    }
}

fn trim(searches: &mut IndexMap<u64, Found>) {
    while searches.len() > SEARCHES_KEPT {
        searches.shift_remove_index(0);
    }
}
