// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! How long nodes take to answer a lookup, and so how long a lookup waits before it moves on: the time within which
//! about 95% of the last answers came (mean plus twice the deviation, plus a margin), never more than three seconds.
//! Each of the last nodes counts once, and those not yet timed count as three seconds, so the wait shortens only as
//! answers prove it may.

use std::time::Duration;

use indexmap::IndexMap;

/// The longest a lookup waits for an answer before it asks the next node.
pub const MAX_WAIT: Duration = Duration::from_secs(3);

const NODES: usize = 100;
const MARGIN: Duration = Duration::from_millis(100);

#[derive(Debug)]
pub struct ResponseTimes {
    // By address, the oldest first.
    times: IndexMap<u32, Duration>,
    wait: Duration,
}

impl Default for ResponseTimes {
    fn default() -> Self {
        Self { times: IndexMap::new(), wait: MAX_WAIT }
    }
}

impl ResponseTimes {
    pub fn new() -> Self {
        Self::default()
    }

    /// A node at `ip` answered after `took`.
    pub fn answered(&mut self, ip: u32, took: Duration) {
        self.times.shift_remove(&ip);
        self.times.insert(ip, took.min(MAX_WAIT));
        if self.times.len() > NODES {
            self.times.shift_remove_index(0);
        }
        self.wait = self.estimate();
    }

    /// Answers timed.
    pub fn samples(&self) -> usize {
        self.times.len()
    }

    /// How long a lookup waits for an answer before it asks the next node.
    pub fn wait(&self) -> Duration {
        self.wait
    }

    fn estimate(&self) -> Duration {
        let max = MAX_WAIT.as_secs_f64();
        let untimed = NODES.saturating_sub(self.times.len()) as f64;
        let nodes = NODES as f64;
        let sum = untimed * max + self.times.values().map(Duration::as_secs_f64).sum::<f64>();
        let mean = sum / nodes;
        let squares = untimed * (max - mean) * (max - mean)
            + self.times.values().map(|t| (t.as_secs_f64() - mean) * (t.as_secs_f64() - mean)).sum::<f64>();
        let deviation = (squares / (nodes - 1.0)).sqrt();
        let estimate = Duration::try_from_secs_f64(mean + 2.0 * deviation).unwrap_or(MAX_WAIT);
        MAX_WAIT.min(estimate.saturating_add(MARGIN))
    }
}
