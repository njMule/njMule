// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Logged-in sessions of the web interface: a random 128-bit token in a cookie, a separate token for forms against
//! cross-site requests, and an end after half an hour without use. Login attempts slow an address down.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Mutex, PoisonError};
use std::time::{Duration, Instant};

use indexmap::IndexMap;
use rand::Rng;
use subtle::ConstantTimeEq;

/// A session ends after this long without a request.
pub const IDLE: Duration = Duration::from_secs(30 * 60);
/// Logins without success from one address before it must wait.
pub const MAX_FAILURES: u32 = 5;
/// How long an address that failed too often waits, and how long its attempts are remembered.
pub const LOCKOUT: Duration = Duration::from_secs(60);
/// Addresses whose attempts are remembered at most; the oldest are forgotten first.
pub const MAX_ADDRESSES: usize = 4096;

const TOKEN_BYTES: usize = 16;

/// One session; `csrf` goes into every form, and a guest's may only look.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Session {
    pub id: String,
    pub csrf: String,
    pub guest: bool,
}

pub struct Sessions {
    clock: Box<dyn Fn() -> Duration + Send + Sync>,
    state: Mutex<State>,
}

#[derive(Default)]
struct State {
    // With their last use.
    sessions: HashMap<String, (Session, Duration)>,
    // The failed attempts of each address and the time of the last, by last attempt, oldest first.
    failures: IndexMap<String, (u32, Duration)>,
}

impl Default for Sessions {
    fn default() -> Self {
        let start = Instant::now();
        Self::new(move || start.elapsed())
    }
}

impl Sessions {
    /// Sessions on the monotonic clock `clock`.
    pub fn new(clock: impl Fn() -> Duration + Send + Sync + 'static) -> Self {
        Self { clock: Box::new(clock), state: Mutex::default() }
    }

    pub fn open(&self, guest: bool) -> Session {
        let session = Session { id: token(), csrf: token(), guest };
        let now = (self.clock)();
        self.state().sessions.insert(session.id.clone(), (session.clone(), now));
        session
    }

    /// The live session of `id`, whose use is noted.
    pub fn find(&self, id: &str) -> Option<Session> {
        let now = (self.clock)();
        let mut state = self.state();
        state.sessions.retain(|_, (_, last_use)| now.saturating_sub(*last_use) < IDLE);
        let (session, last_use) = state.sessions.get_mut(id)?;
        *last_use = now;
        Some(session.clone())
    }

    pub fn close(&self, id: &str) {
        self.state().sessions.remove(id);
    }

    /// Whether `address` may try to log in now; the attempt counts as failed until [`Sessions::succeeded`], so that
    /// parallel attempts cannot all pass while their passwords are checked.
    pub fn may_try(&self, address: &str) -> bool {
        let now = (self.clock)();
        let mut state = self.state();
        while state.failures.first().is_some_and(|(_, (_, since))| now.saturating_sub(*since) >= LOCKOUT) {
            state.failures.shift_remove_index(0);
        }
        let count = match state.failures.get(address) {
            Some((count, _)) if *count >= MAX_FAILURES => return false,
            Some((count, _)) => count.saturating_add(1),
            None => 1,
        };
        state.failures.shift_remove(address);
        state.failures.insert(address.to_owned(), (count, now));
        if state.failures.len() > MAX_ADDRESSES {
            state.failures.shift_remove_index(0);
        }
        true
    }

    pub fn succeeded(&self, address: &str) {
        self.state().failures.shift_remove(address);
    }

    /// The addresses whose attempts are remembered.
    pub fn addresses(&self) -> usize {
        self.state().failures.len()
    }

    /// The sessions open, the idle ones included until a search ends them.
    pub fn count(&self) -> usize {
        self.state().sessions.len()
    }

    fn state(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

/// Whether `given` is the form token of `session`, compared in constant time.
pub fn valid_form(session: &Session, given: Option<&str>) -> bool {
    given.is_some_and(|given| bool::from(session.csrf.as_bytes().ct_eq(given.as_bytes())))
}

/// The key a client's login attempts are counted under: its IPv4 address, or the /64 network of an IPv6 one, whose host
/// part a client chooses at will.
pub fn client_key(address: IpAddr) -> String {
    match address.to_canonical() {
        IpAddr::V4(address) => address.to_string(),
        IpAddr::V6(address) => {
            let network: Vec<String> = address.segments().iter().take(4).map(|part| format!("{part:x}")).collect();
            format!("{}::/64", network.join(":"))
        }
    }
}

fn token() -> String {
    let mut bytes = [0u8; TOKEN_BYTES];
    rand::rng().fill_bytes(&mut bytes);
    data_encoding::BASE64URL_NOPAD.encode(&bytes)
}
