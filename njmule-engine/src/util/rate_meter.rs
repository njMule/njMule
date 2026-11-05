// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Bytes per second averaged over the last few whole seconds, for the transfer rates.

const NANOS_PER_SECOND: i64 = 1_000_000_000;

#[derive(Debug, Clone)]
pub struct RateMeter {
    // The seconds of the average, then the one under way: counted once it is over, else the average would dip each
    // time a second starts.
    per_second: Vec<u64>,
    seconds: u64,
    second: Option<i64>,
    total: u64,
}

impl RateMeter {
    /// A meter averaging over `seconds`; none for a window of no seconds.
    pub fn new(seconds: usize) -> Option<Self> {
        let slots = seconds.checked_add(1).filter(|_| seconds > 0)?;
        Some(Self { per_second: vec![0; slots], seconds: u64::try_from(seconds).ok()?, second: None, total: 0 })
    }

    /// Counts `bytes` at `now` (monotonic nanoseconds).
    pub fn add(&mut self, bytes: u64, now: i64) {
        self.advance(now);
        let slot = self.slot(self.second.unwrap_or(0));
        if let Some(count) = self.per_second.get_mut(slot) {
            *count = count.saturating_add(bytes);
        }
        self.total = self.total.saturating_add(bytes);
    }

    pub fn rate(&mut self, now: i64) -> u64 {
        self.advance(now);
        let current = self.slot(self.second.unwrap_or(0));
        let sum = self
            .per_second
            .iter()
            .enumerate()
            .filter(|(slot, _)| *slot != current)
            .fold(0u64, |sum, (_, bytes)| sum.saturating_add(*bytes));
        sum.checked_div(self.seconds).unwrap_or(0)
    }

    pub fn total(&self) -> u64 {
        self.total
    }

    // A clock going backwards counts in the latest second.
    fn advance(&mut self, now: i64) {
        let current = now.div_euclid(NANOS_PER_SECOND);
        let slots = i64::try_from(self.per_second.len()).unwrap_or(i64::MAX);
        match self.second {
            Some(second) if current.saturating_sub(second) < slots => {
                let mut cleared = second;
                while cleared < current {
                    cleared = cleared.saturating_add(1);
                    let slot = self.slot(cleared);
                    if let Some(count) = self.per_second.get_mut(slot) {
                        *count = 0;
                    }
                }
            }
            _ => self.per_second.fill(0),
        }
        if self.second.is_none_or(|second| current > second) {
            self.second = Some(current);
        }
    }

    fn slot(&self, second: i64) -> usize {
        let slots = i64::try_from(self.per_second.len()).unwrap_or(1);
        usize::try_from(second.rem_euclid(slots.max(1))).unwrap_or(0)
    }
}
