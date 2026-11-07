// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The ids nodes proved at their address. Kad ids are kept for good, so a node that proves another one soon after the
//! last change moves around the network to sit where it likes; an id merely claimed does not replace a proven one.
//! Each address is forgotten an hour after its last proof.

use std::time::{Duration, Instant};

use indexmap::IndexMap;

use crate::kad::uint128::UInt128;

/// A node changing its id more often than this is not an ordinary client.
pub const MIN_CHANGE_GAP: Duration = Duration::from_secs(3600);

const FORGET_AFTER: Duration = Duration::from_secs(3600);
const MAX_NODES: usize = 10_000;

#[derive(Debug, Clone, Copy)]
struct Proven {
    id: UInt128,
    changed_at: Instant,
    seen_at: Instant,
}

#[derive(Debug, Default)]
pub struct ProvenIds {
    // Proven longest ago first.
    nodes: IndexMap<(u32, u16), Proven>,
}

impl ProvenIds {
    pub fn new() -> Self {
        Self::default()
    }

    /// The node at `ip:port` proved it holds `id`; true when it changed its id too soon.
    pub fn proved(&mut self, ip: u32, port: u16, id: UInt128, now: Instant) -> bool {
        let mut known = self.nodes.shift_remove(&(ip, port));
        if let Some(proven) = known
            && proven.id != id
        {
            if now.saturating_duration_since(proven.changed_at) < MIN_CHANGE_GAP {
                return true;
            }
            known = None;
        }
        let changed_at = known.map_or(now, |proven| proven.changed_at);
        self.nodes.insert((ip, port), Proven { id, changed_at, seen_at: now });
        if self.nodes.len() > MAX_NODES {
            self.nodes.shift_remove_index(0);
        }
        false
    }

    /// Whether the node at `ip:port` proved another id than `id`.
    pub fn contradicts(&self, ip: u32, port: u16, id: UInt128) -> bool {
        self.nodes.get(&(ip, port)).is_some_and(|proven| proven.id != id)
    }

    /// Forgets the addresses that proved nothing for an hour.
    pub fn clean(&mut self, now: Instant) {
        while let Some((_, proven)) = self.nodes.first()
            && now.saturating_duration_since(proven.seen_at) >= FORGET_AFTER
        {
            self.nodes.shift_remove_index(0);
        }
    }
}
