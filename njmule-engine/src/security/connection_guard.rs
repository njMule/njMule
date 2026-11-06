// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Admission control for incoming TCP connections: the IP filter first, then caps on the total and per-address
//! connections and on the accept rate, in all and per address. Our own connections count in the total too. An IPv6
//! address counts with the rest of its /64, which one home holds whole; the IP filter knows IPv4 ranges only.

use std::collections::{HashMap, VecDeque};
use std::net::{IpAddr, Ipv4Addr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use crate::security::ip_filter::IpFilter;
use crate::util::ipv6;

/// Why a connection was refused.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Refusal {
    Filtered,
    Banned,
    TooManyConnections,
    TooManyFromAddress,
    AcceptRate,
    AcceptRateFromAddress,
}

/// Admission outcome: a permit, released when the connection closes, or a refusal.
#[derive(Debug)]
pub enum Admission {
    Admitted(Permit),
    Refused(Refusal),
}

/// Limits; the accept rate counts connections admitted within `rate_window`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limits {
    max_total: u32,
    max_per_address: u32,
    max_accepts: u32,
    rate_window: Duration,
}

impl Limits {
    /// The longest rate window; a longer one would no longer limit a rate.
    pub const MAX_RATE_WINDOW: Duration = Duration::from_secs(3600);
    pub const DEFAULT: Self =
        Self { max_total: 500, max_per_address: 5, max_accepts: 20, rate_window: Duration::from_secs(5) };

    pub fn new(max_total: u32, max_per_address: u32, max_accepts: u32, rate_window: Duration) -> Result<Self, String> {
        if max_total < 1 || max_per_address < 1 || max_accepts < 1 {
            return Err("limits must be positive".to_owned());
        }
        // A zero window would turn the rate limit off.
        if rate_window.is_zero() || rate_window > Self::MAX_RATE_WINDOW {
            return Err(format!("the rate window must be within 0-{:?}: {rate_window:?}", Self::MAX_RATE_WINDOW));
        }
        Ok(Self { max_total, max_per_address, max_accepts, rate_window })
    }

    pub fn rate_window(&self) -> Duration {
        self.rate_window
    }

    // An address that opens and closes connections in a row could take the whole accept rate and lock everyone else
    // out: it gets a quarter of it, or as many accepts as connections it may hold when that is more.
    fn accepts_per_address(&self) -> u32 {
        self.max_per_address.max(self.max_accepts / 4)
    }
}

// By address: the IPv4 address, or the /64 network of an IPv6 one; ours have none.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum Key {
    Ours,
    Address(IpAddr),
}

#[derive(Debug, Default)]
struct State {
    per_address: HashMap<Key, u32>,
    recent_accepts: VecDeque<(Duration, Key)>,
    recent_from: HashMap<Key, u32>,
    total: u32,
}

impl State {
    fn forget_old_accepts(&mut self, window: Duration, now: Duration) {
        while let Some(&(at, key)) = self.recent_accepts.front()
            && now.saturating_sub(at) >= window
        {
            self.recent_accepts.pop_front();
            decrement(&mut self.recent_from, key);
        }
    }
}

fn decrement(counts: &mut HashMap<Key, u32>, key: Key) {
    if let Some(count) = counts.get_mut(&key) {
        if *count > 1 {
            *count = count.saturating_sub(1);
        } else {
            counts.remove(&key);
        }
    }
}

type Shared = Arc<Mutex<State>>;

pub struct ConnectionGuard {
    state: Shared,
    filter: Box<dyn Fn() -> Arc<IpFilter> + Send + Sync>,
    exempt: Box<dyn Fn(u32) -> bool + Send + Sync>,
    banned: Box<dyn Fn(IpAddr) -> bool + Send + Sync>,
    limits: Box<dyn Fn() -> Limits + Send + Sync>,
    ticker: Box<dyn Fn() -> Duration + Send + Sync>,
}

impl ConnectionGuard {
    /// `filter` and `limits` are read on every admission, so a reloaded filter and new settings apply at once.
    /// `exempt` addresses are never held back by the accept rate or the total, such as the server testing whether we
    /// are reachable: refusing it would leave us with a LowID. `banned` addresses are refused for a while because they
    /// misbehaved. `ticker` tells the time elapsed, monotonically.
    pub fn new(
        filter: impl Fn() -> Arc<IpFilter> + Send + Sync + 'static,
        exempt: impl Fn(u32) -> bool + Send + Sync + 'static,
        banned: impl Fn(IpAddr) -> bool + Send + Sync + 'static,
        limits: impl Fn() -> Limits + Send + Sync + 'static,
        ticker: impl Fn() -> Duration + Send + Sync + 'static,
    ) -> Self {
        Self {
            state: Shared::default(),
            filter: Box::new(filter),
            exempt: Box::new(exempt),
            banned: Box::new(banned),
            limits: Box::new(limits),
            ticker: Box::new(ticker),
        }
    }

    /// Decides on a connection from `address`, IPv4 or IPv6.
    pub fn admit(&self, address: IpAddr) -> Admission {
        match address {
            IpAddr::V4(v4) => self.admit_v4(u32::from(v4)),
            IpAddr::V6(_) => {
                // Never one of the IPv4 servers or Kad nodes that may be exempt.
                if (self.banned)(address) {
                    return Admission::Refused(Refusal::Banned);
                }
                self.admit_from(Key::Address(ipv6::network(address)), false)
            }
        }
    }

    /// Decides on a connection from `ip` ([`crate::util::ipv4`] form).
    pub fn admit_v4(&self, ip: u32) -> Admission {
        if (self.filter)().blocks(ip) {
            return Admission::Refused(Refusal::Filtered);
        }
        if (self.banned)(IpAddr::V4(Ipv4Addr::from(ip))) {
            return Admission::Refused(Refusal::Banned);
        }
        self.admit_from(Key::Address(IpAddr::V4(Ipv4Addr::from(ip))), (self.exempt)(ip))
    }

    fn admit_from(&self, key: Key, exempted: bool) -> Admission {
        let limits = (self.limits)();
        let now = (self.ticker)();
        let mut state = self.lock();
        state.forget_old_accepts(limits.rate_window, now);
        if !exempted && state.recent_accepts.len() >= limits.max_accepts as usize {
            return Admission::Refused(Refusal::AcceptRate);
        }
        if !exempted && state.total >= limits.max_total {
            return Admission::Refused(Refusal::TooManyConnections);
        }
        let from_address = state.per_address.get(&key).copied().unwrap_or(0);
        if from_address >= limits.max_per_address {
            return Admission::Refused(Refusal::TooManyFromAddress);
        }
        if !exempted && state.recent_from.get(&key).copied().unwrap_or(0) >= limits.accepts_per_address() {
            return Admission::Refused(Refusal::AcceptRateFromAddress);
        }
        // Exempt connections leave the accept rate to the others.
        if !exempted {
            state.recent_accepts.push_back((now, key));
            let recent = state.recent_from.entry(key).or_insert(0);
            *recent = recent.saturating_add(1);
        }
        state.per_address.insert(key, from_address.saturating_add(1));
        state.total = state.total.saturating_add(1);
        Admission::Admitted(self.permit(key, true))
    }

    /// Whether a connection of ours fits: in the total and in the rate of new connections, which incoming ones share.
    pub fn has_room(&self) -> bool {
        let limits = (self.limits)();
        let now = (self.ticker)();
        let mut state = self.lock();
        state.forget_old_accepts(limits.rate_window, now);
        state.total < limits.max_total && state.recent_accepts.len() < limits.max_accepts as usize
    }

    /// Counts a connection of ours in the total until the permit is closed, and in the rate of new connections.
    pub fn outgoing(&self) -> Permit {
        let now = (self.ticker)();
        let mut state = self.lock();
        state.recent_accepts.push_back((now, Key::Ours));
        state.total = state.total.saturating_add(1);
        drop(state);
        self.permit(Key::Ours, false)
    }

    /// Connections currently holding a permit, ours included.
    pub fn open_connections(&self) -> u32 {
        self.lock().total
    }

    fn permit(&self, key: Key, incoming: bool) -> Permit {
        Permit { state: Arc::clone(&self.state), key, incoming, released: AtomicBool::new(false) }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

/// Held while a connection is open; closing it more than once, or dropping it after, has no further effect.
#[derive(Debug)]
pub struct Permit {
    state: Shared,
    key: Key,
    incoming: bool,
    released: AtomicBool,
}

impl Permit {
    pub fn close(&self) {
        if !self.released.swap(true, Ordering::AcqRel) {
            let mut state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
            state.total = state.total.saturating_sub(1);
            if self.incoming {
                decrement(&mut state.per_address, self.key);
            }
        }
    }
}

impl Drop for Permit {
    fn drop(&mut self) {
        self.close();
    }
}
