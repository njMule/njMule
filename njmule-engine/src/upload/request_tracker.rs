// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! How each client program, by address and listening port, used our queue lately: how soon it asked again for each file
//! and how many slots it left idle. Kept while the client is out of the queue too, and whatever user hash it shows, so
//! neither leaving and coming back nor showing another hash clears anything.

use std::collections::HashMap;
use std::net::IpAddr;
use std::time::{Duration, Instant};

use indexmap::IndexMap;

use crate::util::ipv6;

/// A client program: its address and the port it listens on.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Key {
    pub ip: IpAddr,
    pub port: u16,
}

// Clients re-asking on a timer of exactly the shortest time come a little early now and then.
const TOLERANCE: Duration = Duration::from_secs(10);
const MAX_FILES: usize = 32;
const UNKNOWN_FILES_KEPT: Duration = Duration::from_secs(2 * 3600);

pub struct RequestTracker {
    fast_ask: Duration,
    window: Duration,
    histories: HashMap<Key, History>,
    // By address: connections dropped for asking for files we do not have.
    unknown_files: HashMap<IpAddr, Drops>,
}

struct History {
    asks: IndexMap<[u8; 16], Ask>,
    seen: Instant,
    idle_slots: u32,
    first_idle: Instant,
}

struct Drops {
    count: u32,
    last: Instant,
}

// The last ask for a file and how many came too soon.
struct Ask {
    last: Instant,
    bad: u32,
}

impl RequestTracker {
    /// `min_ask` is the shortest time between two asks for a file that does not count against a client, `window` how
    /// long idle slots count, and a client is remembered.
    pub fn new(min_ask: Duration, window: Duration) -> Self {
        Self {
            fast_ask: min_ask.saturating_sub(TOLERANCE),
            window,
            histories: HashMap::new(),
            unknown_files: HashMap::new(),
        }
    }

    /// Counts an ask for `file`; returns how many asks for it came too soon with no slower one between.
    pub fn asked(&mut self, client: Key, file: [u8; 16], now: Instant) -> u32 {
        let fast_ask = self.fast_ask;
        let history = self.history(client, now);
        let Some(ask) = history.asks.get_mut(&file) else {
            history.asks.insert(file, Ask { last: now, bad: 0 });
            if history.asks.len() > MAX_FILES {
                history.asks.shift_remove_index(0);
            }
            return 0;
        };
        ask.bad = if now.saturating_duration_since(ask.last) < fast_ask {
            ask.bad.saturating_add(1)
        } else {
            ask.bad.saturating_sub(1)
        };
        ask.last = now;
        ask.bad
    }

    /// A slot ended because its client asked for nothing; returns how many did within the window.
    pub fn idle_slot(&mut self, client: Key, now: Instant) -> u32 {
        let window = self.window;
        let history = self.history(client, now);
        if history.idle_slots == 0 || now.saturating_duration_since(history.first_idle) >= window {
            history.idle_slots = 0;
            history.first_idle = now;
        }
        history.idle_slots = history.idle_slots.saturating_add(1);
        history.idle_slots
    }

    /// A slot sent file data: the idle slots of its client are forgiven.
    pub fn productive(&mut self, client: Key) {
        if let Some(history) = self.histories.get_mut(&client) {
            history.idle_slots = 0;
        }
    }

    /// A connection from `ip` was dropped for asking for files we do not have; returns how many were within two hours,
    /// whatever the user hash and wherever in its /64 for IPv6.
    pub fn unknown_files(&mut self, ip: IpAddr, now: Instant) -> u32 {
        let drops = self.unknown_files.entry(ipv6::network(ip)).or_insert(Drops { count: 0, last: now });
        if now.saturating_duration_since(drops.last) >= UNKNOWN_FILES_KEPT {
            drops.count = 0;
        }
        drops.last = now;
        drops.count = drops.count.saturating_add(1);
        drops.count
    }

    /// Forgets the clients not heard of within the window.
    pub fn purge(&mut self, now: Instant) {
        let window = self.window;
        self.histories.retain(|_, history| now.saturating_duration_since(history.seen) < window);
        self.unknown_files.retain(|_, drops| now.saturating_duration_since(drops.last) < UNKNOWN_FILES_KEPT);
    }

    pub fn len(&self) -> usize {
        self.histories.len()
    }

    pub fn is_empty(&self) -> bool {
        self.histories.is_empty()
    }

    fn history(&mut self, client: Key, now: Instant) -> &mut History {
        let history = self.histories.entry(client).or_insert_with(|| History {
            asks: IndexMap::new(),
            seen: now,
            idle_slots: 0,
            first_idle: now,
        });
        history.seen = now;
        history
    }
}
