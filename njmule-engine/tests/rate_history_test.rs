// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java RateHistoryTest, under its names: the seconds of the last hour and the mean of each minute.

#![allow(clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::remote::rate_history::{Rate, RateHistory, SECONDS_KEPT};

#[test]
fn should_keep_the_seconds_of_the_last_hour_only() {
    let mut rates = Rates::new();
    rates.given_seconds_with_rising_rates(SECONDS_KEPT + 1);

    rates.when_the_seconds_are_read(SECONDS_KEPT);

    rates.then_there_are(SECONDS_KEPT);
    rates.then_the_first_is(Rate { down: 1, up: 2 });
}

#[test]
fn should_give_the_mean_of_each_minute() {
    let mut rates = Rates::new();
    rates.given_seconds_with_rising_rates(60);

    rates.when_the_minutes_are_read(1);

    rates.then_the_rates_are(&[Rate { down: 29, up: 59 }]);
}

#[test]
fn should_give_no_minute_before_one_ends() {
    let mut rates = Rates::new();
    rates.given_seconds_with_rising_rates(59);

    rates.when_the_minutes_are_read(1);

    rates.then_there_are(0);
}

struct Rates {
    history: RateHistory,
    rates: Vec<Rate>,
}

impl Rates {
    fn new() -> Self {
        Self { history: RateHistory::default(), rates: Vec::new() }
    }

    // Second i downloads i bytes a second and uploads twice as many.
    fn given_seconds_with_rising_rates(&mut self, count: usize) {
        for i in 0..count {
            let i = u64::try_from(i).unwrap_or(u64::MAX);
            self.history.add(i, 2 * i);
        }
    }

    fn when_the_seconds_are_read(&mut self, count: usize) {
        self.rates = self.history.seconds(count);
    }

    fn when_the_minutes_are_read(&mut self, count: usize) {
        self.rates = self.history.minutes(count);
    }

    fn then_there_are(&self, count: usize) {
        assert_eq!(self.rates.len(), count);
    }

    fn then_the_first_is(&self, rate: Rate) {
        assert_eq!(self.rates[0], rate);
    }

    fn then_the_rates_are(&self, expected: &[Rate]) {
        assert_eq!(self.rates, expected);
    }
}
