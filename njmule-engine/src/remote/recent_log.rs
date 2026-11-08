// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The latest lines of the user's log, numbered, so that a front end reads those it has not seen yet.

use std::collections::VecDeque;

use crate::api::log_line::LogLine;

pub const KEPT: usize = 500;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub sequence: u64,
    pub line: LogLine,
}

#[derive(Debug)]
pub struct RecentLog {
    lines: VecDeque<Entry>,
    next: u64,
}

impl Default for RecentLog {
    fn default() -> Self {
        Self { lines: VecDeque::new(), next: 1 }
    }
}

impl RecentLog {
    pub fn add(&mut self, line: LogLine) {
        self.lines.push_back(Entry { sequence: self.next, line });
        self.next = self.next.saturating_add(1);
        if self.lines.len() > KEPT {
            self.lines.pop_front();
        }
    }

    /// The lines numbered above `sequence`, oldest first.
    pub fn after(&self, sequence: u64) -> Vec<Entry> {
        self.lines.iter().filter(|entry| entry.sequence > sequence).cloned().collect()
    }
}
