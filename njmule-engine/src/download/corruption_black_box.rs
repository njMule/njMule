// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Which source sent each range of a download's data still unproven, to find the sources of corrupt data. Parts that
//! pass their check and AICH recovery data prove data good; recovery data proves blocks corrupt, and a part that fails
//! its check after a single source sent all of it counts against that source. A source is guilty once more than
//! [`GUILTY_PERCENT`] of what it sent and was proven is corrupt, and, on failed parts alone, once it sent at least
//! [`FAILED_PARTS`] of them: a stray bad block among much good data, or one bad part, is forgiven.

use std::collections::{HashMap, HashSet};
use std::net::IpAddr;
use std::ops::Range;

use rangemap::RangeMap;

use crate::util::ipv6;

pub const GUILTY_PERCENT: u64 = 32;
pub const FAILED_PARTS: u64 = 3;

// What a source sent and was proven: good, corrupt by recovery data, in parts it alone sent that failed, and how many
// such parts.
#[derive(Debug, Default, Clone, Copy)]
struct Proven {
    good: u64,
    corrupt: u64,
    failed: u64,
    failed_parts: u64,
}

#[derive(Debug, Default)]
pub struct CorruptionBlackBox {
    // The addresses of the sources, IPv6 ones by their /64, by the range of the data each sent; touching ranges of one
    // source are one.
    senders: RangeMap<u64, IpAddr>,
    proven: HashMap<IpAddr, Proven>,
}

impl CorruptionBlackBox {
    /// `[start, end)` from `from` reached the disk, replacing what was there.
    pub fn received(&mut self, start: u64, end: u64, from: IpAddr) {
        if start < end {
            self.senders.insert(start..end, ipv6::network(from));
        }
    }

    /// `[start, end)` passed its check.
    pub fn passed(&mut self, start: u64, end: u64) {
        for (range, ip) in self.sent(start..end) {
            let proven = self.proven.entry(ip).or_default();
            proven.good = proven.good.saturating_add(length(&range));
        }
        self.remove(start..end);
    }

    /// The part `[start, end)` failed its check: it counts against the source that sent all of it, if one did. The
    /// ranges are kept, for recovery data to tell which blocks were bad. Returns the sources now guilty.
    pub fn failed(&mut self, start: u64, end: u64) -> HashSet<IpAddr> {
        let sent = self.sent(start..end);
        let ips: HashSet<IpAddr> = sent.iter().map(|(_, ip)| *ip).collect();
        let covered = sent.iter().map(|(range, _)| length(range)).fold(0_u64, u64::saturating_add);
        let mut only = ips.iter();
        let (Some(ip), None) = (only.next(), only.next()) else {
            return HashSet::new();
        };
        if covered != end.saturating_sub(start) {
            return HashSet::new();
        }
        let proven = self.proven.entry(*ip).or_default();
        proven.failed = proven.failed.saturating_add(end.saturating_sub(start));
        proven.failed_parts = proven.failed_parts.saturating_add(1);
        self.guilty(&ips)
    }

    /// Recovery data proved the blocks `good` and `bad`: each source of some of a bad block counts the whole block as
    /// corrupt. Returns the sources now guilty.
    pub fn recovered(&mut self, good: &[Range<u64>], bad: &[Range<u64>]) -> HashSet<IpAddr> {
        for block in good {
            self.passed(block.start, block.end);
        }
        let mut suspects = HashSet::new();
        for block in bad {
            let ips: HashSet<IpAddr> = self.sent(block.clone()).into_iter().map(|(_, ip)| ip).collect();
            for ip in ips {
                let proven = self.proven.entry(ip).or_default();
                proven.corrupt = proven.corrupt.saturating_add(length(block));
                suspects.insert(ip);
            }
            self.remove(block.clone());
        }
        self.guilty(&suspects)
    }

    fn guilty(&self, suspects: &HashSet<IpAddr>) -> HashSet<IpAddr> {
        suspects
            .iter()
            .filter(|ip| {
                let proven = self.proven.get(ip).copied().unwrap_or_default();
                too_much(proven.corrupt, proven.good)
                    || (proven.failed_parts >= FAILED_PARTS && too_much(proven.failed, proven.good))
            })
            .copied()
            .collect()
    }

    // The ranges sent within `range`, cut to it.
    fn sent(&self, range: Range<u64>) -> Vec<(Range<u64>, IpAddr)> {
        if range.start >= range.end {
            return Vec::new();
        }
        self.senders
            .overlapping(&range)
            .map(|(sent, ip)| (sent.start.max(range.start)..sent.end.min(range.end), *ip))
            .collect()
    }

    fn remove(&mut self, range: Range<u64>) {
        if range.start < range.end {
            self.senders.remove(range);
        }
    }
}

fn too_much(corrupt: u64, good: u64) -> bool {
    corrupt.saturating_mul(100) > GUILTY_PERCENT.saturating_mul(good.saturating_add(corrupt))
}

fn length(range: &Range<u64>) -> u64 {
    range.end.saturating_sub(range.start)
}
