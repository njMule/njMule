// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The AICH master hashes sources announce for a file, trusted when at least [`MIN_SIGNERS`] networks (/20 for IPv4,
//! /48 for IPv6, which one site may hold whole) announce the same one and it is [`MIN_PERCENT`]% of the announcements.
//! A network counts for the first hash it announces only, so that one host cannot outvote the others.

use std::collections::{BTreeSet, HashMap};
use std::net::IpAddr;

pub const MIN_SIGNERS: usize = 10;
pub const MIN_PERCENT: usize = 92;

// Bit 62 sets IPv6 networks apart from IPv4 ones.
const IPV6: u64 = 1 << 62;

#[derive(Debug, Clone, Default)]
pub struct AichVotes {
    // In the order the hashes were first announced: the first of equal counts wins.
    signers: Vec<([u8; 20], BTreeSet<u64>)>,
    by_network: HashMap<u64, [u8; 20]>,
}

impl AichVotes {
    pub fn new() -> Self {
        Self::default()
    }

    /// `address` announced `hash`; returns the hash trusted now, if any.
    pub fn announced(&mut self, address: IpAddr, hash: [u8; 20]) -> Option<[u8; 20]> {
        let network = match address.to_canonical() {
            IpAddr::V4(v4) => u64::from(u32::from(v4) >> 12),
            IpAddr::V6(v6) => {
                let [a, b, c, ..] = v6.segments();
                IPV6 | u64::from(a) << 32 | u64::from(b) << 16 | u64::from(c)
            }
        };
        let before = *self.by_network.entry(network).or_insert(hash);
        if before == hash {
            match self.signers.iter_mut().find(|(signed, _)| *signed == hash) {
                Some((_, networks)) => {
                    networks.insert(network);
                }
                None => self.signers.push((hash, BTreeSet::from([network]))),
            }
        }
        self.trusted()
    }

    pub fn trusted(&self) -> Option<[u8; 20]> {
        let total: usize = self.signers.iter().map(|(_, networks)| networks.len()).sum();
        let mut most: Option<([u8; 20], usize)> = None;
        for (hash, networks) in &self.signers {
            if networks.len() > most.map_or(0, |(_, count)| count) {
                most = Some((*hash, networks.len()));
            }
        }
        let (hash, count) = most?;
        let percent = count.saturating_mul(100).checked_div(total)?;
        (count >= MIN_SIGNERS && percent >= MIN_PERCENT).then_some(hash)
    }
}
