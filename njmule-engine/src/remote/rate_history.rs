// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The rates of each second of the last hour, and the mean of each minute of the last day, for the front ends that
//! draw them.

use std::collections::VecDeque;

pub const SECONDS_KEPT: usize = 3600;
pub const MINUTES_KEPT: usize = 1440;

/// The bytes a second downloaded and uploaded.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rate {
    pub down: u64,
    pub up: u64,
}

#[derive(Debug, Default)]
pub struct RateHistory {
    seconds: VecDeque<Rate>,
    minutes: VecDeque<Rate>,
    minute_down: u64,
    minute_up: u64,
    minute_samples: u64,
}

const SECONDS_IN_A_MINUTE: u64 = 60;

impl RateHistory {
    /// Adds the rates of a second; every sixtieth closes a minute.
    pub fn add(&mut self, down: u64, up: u64) {
        keep(&mut self.seconds, Rate { down, up }, SECONDS_KEPT);
        self.minute_down = self.minute_down.saturating_add(down);
        self.minute_up = self.minute_up.saturating_add(up);
        self.minute_samples = self.minute_samples.saturating_add(1);
        if self.minute_samples == SECONDS_IN_A_MINUTE {
            let mean = Rate {
                down: self.minute_down.checked_div(SECONDS_IN_A_MINUTE).unwrap_or(0),
                up: self.minute_up.checked_div(SECONDS_IN_A_MINUTE).unwrap_or(0),
            };
            keep(&mut self.minutes, mean, MINUTES_KEPT);
            self.minute_down = 0;
            self.minute_up = 0;
            self.minute_samples = 0;
        }
    }

    /// The last `count` seconds, oldest first.
    pub fn seconds(&self, count: usize) -> Vec<Rate> {
        last(&self.seconds, count)
    }

    /// The last `count` minutes, oldest first.
    pub fn minutes(&self, count: usize) -> Vec<Rate> {
        last(&self.minutes, count)
    }
}

fn keep(samples: &mut VecDeque<Rate>, rate: Rate, most: usize) {
    samples.push_back(rate);
    if samples.len() > most {
        samples.pop_front();
    }
}

fn last(samples: &VecDeque<Rate>, count: usize) -> Vec<Rate> {
    samples.iter().skip(samples.len().saturating_sub(count)).copied().collect()
}
