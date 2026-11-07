// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java SearchCollectorTest, under its names: how long a search started through the API counts as running.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use njmule_engine::remote::search_collector::{Asked, LONGEST_SEARCH, SearchCollector};

const SEARCH: u64 = 7;

#[test]
fn should_stop_counting_a_search_as_running_after_the_longest_search() {
    let mut searches = Searches::new();
    searches.given_a_search_started();
    searches.given_time_passed(LONGEST_SEARCH + Duration::from_secs(1));

    searches.when_it_is_asked_whether_the_search_runs();

    searches.then_the_search_runs(false);
}

#[test]
fn should_count_a_search_as_running_again_once_asked_for_more_results() {
    let mut searches = Searches::new();
    searches.given_a_search_started();
    searches.given_time_passed(LONGEST_SEARCH + Duration::from_secs(1));
    searches.given_more_results_were_asked_for();

    searches.when_it_is_asked_whether_the_search_runs();

    searches.then_the_search_runs(true);
}

#[test]
fn should_not_start_a_search_it_does_not_know_when_asked_for_more_results() {
    let mut searches = Searches::new();
    searches.given_more_results_were_asked_for();

    searches.when_it_is_asked_whether_the_search_runs();

    searches.then_the_search_runs(false);
}

struct Searches {
    now: Arc<Mutex<Instant>>,
    collector: SearchCollector,
    running: bool,
}

impl Searches {
    fn new() -> Self {
        let now = Arc::new(Mutex::new(Instant::now()));
        let clock = Arc::clone(&now);
        let collector = SearchCollector::new(Box::new(move || *clock.lock().unwrap()));
        Self { now, collector, running: false }
    }

    fn given_a_search_started(&mut self) {
        self.collector.started(SEARCH, Asked::default());
    }

    fn given_time_passed(&self, time: Duration) {
        *self.now.lock().unwrap() += time;
    }

    fn given_more_results_were_asked_for(&mut self) {
        self.collector.resumed(SEARCH);
    }

    fn when_it_is_asked_whether_the_search_runs(&mut self) {
        self.running = self.collector.running(SEARCH);
    }

    fn then_the_search_runs(&self, expected: bool) {
        assert_eq!(self.running, expected);
    }
}
