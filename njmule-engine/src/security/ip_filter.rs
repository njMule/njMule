// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Immutable set of blocked IPv4 ranges: a range blocks when its level is below the filter level. Where ranges overlap,
//! the one listed later wins. Addresses the user always allows are never blocked.
//!
//! Held in sorted vectors, as the Java engine holds them after measuring a range map at 5 times the memory: the
//! rangemap crate would also join the adjacent spans of two ranges with one description, which the count of ranges
//! keeps apart.

use std::collections::BinaryHeap;
use std::sync::Arc;

use crate::persist::format::ip_filter_dat::Range;

/// Filter level used when the user did not choose one.
pub const DEFAULT_LEVEL: u32 = 127;

#[derive(Debug, Clone, Default)]
pub struct IpFilter {
    blocked: Arc<Spans>,
    allowed: Arc<Spans>,
}

impl IpFilter {
    pub fn empty() -> Self {
        Self::default()
    }

    /// Keeps the ranges that block at `level`.
    pub fn of(ranges: &[Range], level: u32) -> Self {
        let blocking: Vec<&Range> = ranges.iter().filter(|range| range.level < level).collect();
        Self { blocked: Arc::new(Spans::later_wins(&blocking)), allowed: Arc::default() }
    }

    /// The same blocked ranges, with `ranges` (whatever their levels) as the only addresses never blocked.
    pub fn allowing(&self, ranges: &[Range]) -> Self {
        let allowed: Vec<&Range> = ranges.iter().collect();
        Self { blocked: Arc::clone(&self.blocked), allowed: Arc::new(Spans::later_wins(&allowed)) }
    }

    /// Whether `ip` ([`crate::util::ipv4`] form) is blocked; 0 never is.
    pub fn blocks(&self, ip: u32) -> bool {
        self.blocked_span(ip).is_some()
    }

    /// The description of the range blocking `ip`, if any.
    pub fn matched(&self, ip: u32) -> Option<&str> {
        self.blocked_span(ip).and_then(|span| self.blocked.descriptions.get(span)).map(|description| &**description)
    }

    /// Number of disjoint blocked ranges.
    pub fn range_count(&self) -> usize {
        self.blocked.starts.len()
    }

    fn blocked_span(&self, ip: u32) -> Option<usize> {
        if ip == 0 || self.allowed.index_of(ip).is_some() { None } else { self.blocked.index_of(ip) }
    }
}

/// Sorted, disjoint inclusive spans of addresses, each with the description of its range.
#[derive(Debug, Default)]
struct Spans {
    starts: Vec<u32>,
    ends: Vec<u32>,
    descriptions: Vec<Arc<str>>,
}

impl Spans {
    fn index_of(&self, address: u32) -> Option<usize> {
        let index = self.starts.partition_point(|&start| start <= address).checked_sub(1)?;
        (address <= *self.ends.get(index)?).then_some(index)
    }

    /// Sweeps the ranges by address, keeping at each point the last range listed that covers it; adjacent spans of
    /// different ranges stay apart.
    fn later_wins(ranges: &[&Range]) -> Self {
        let descriptions: Vec<Arc<str>> = ranges.iter().map(|range| Arc::from(range.description.as_str())).collect();
        let end = |index: usize| ranges.get(index).map_or(0, |range| u64::from(range.end));
        let mut order: Vec<(u32, usize)> =
            ranges.iter().enumerate().map(|(index, range)| (range.start, index)).collect();
        order.sort_unstable();
        let mut spans = Self::default();
        let mut previous = None;
        // The range listed last on top.
        let mut covering = BinaryHeap::new();
        let mut next = order.iter().peekable();
        let mut at = 0_u64;
        while next.peek().is_some() || !covering.is_empty() {
            if covering.is_empty()
                && let Some((start, _)) = next.peek()
            {
                at = u64::from(*start);
            }
            while let Some((_, index)) = next.next_if(|(start, _)| u64::from(*start) <= at) {
                covering.push(*index);
            }
            while covering.peek().is_some_and(|&top| end(top) < at) {
                covering.pop();
            }
            let Some(&winner) = covering.peek() else { continue };
            let mut until = end(winner);
            if let Some((start, _)) = next.peek() {
                until = until.min(u64::from(*start).saturating_sub(1));
            }
            let span_end = u32::try_from(until).unwrap_or(u32::MAX);
            if previous == Some(winner) {
                if let Some(last) = spans.ends.last_mut() {
                    *last = span_end;
                }
            } else {
                spans.starts.push(u32::try_from(at).unwrap_or(u32::MAX));
                spans.ends.push(span_end);
                spans.descriptions.push(descriptions.get(winner).cloned().unwrap_or_else(|| Arc::from("")));
                previous = Some(winner);
            }
            at = until.saturating_add(1);
        }
        spans
    }
}
