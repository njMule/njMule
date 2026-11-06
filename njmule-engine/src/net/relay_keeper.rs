// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Keeps a UDP socket on the route its proxy asks for: straight without a proxy, or with one that relays no UDP (which
//! the log says), through a SOCKS5 association otherwise, made again when it ends. Nothing goes while an association
//! is being made, so no datagram leaves outside the proxy.

use std::fmt;
use std::net::Ipv4Addr;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use tokio::task::JoinHandle;

use crate::config::proxy_settings::{ProxySettings, ProxyType};
use crate::net::socks5::Socks5Error;
use crate::net::socks5_udp::Socks5Udp;
use crate::net::udp_route::UdpRoute;

/// How long to wait for each answer of the proxy.
pub const TIMEOUT: Duration = Duration::from_secs(15);
/// The first wait before asking the proxy again; it doubles up to [`LONGEST_WAIT`].
pub const FIRST_WAIT: Duration = Duration::from_secs(30);
pub const LONGEST_WAIT: Duration = Duration::from_secs(5 * 60);

/// The level of a line for the front ends' log.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Level {
    Info,
    Warning,
}

impl fmt::Display for Level {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Info => "INFO",
            Self::Warning => "WARNING",
        })
    }
}

type Route = Box<dyn Fn(UdpRoute) + Send + Sync>;
type Log = Box<dyn Fn(Level, &str) + Send + Sync>;

pub struct RelayKeeper {
    inner: Arc<Inner>,
}

struct Inner {
    what: String,
    target: Route,
    local: Option<Ipv4Addr>,
    log: Log,
    first_wait: Duration,
    state: Mutex<State>,
}

// The settings followed, and which of the workers started for them is the current one.
#[derive(Default)]
struct State {
    settings: Option<ProxySettings>,
    generation: u64,
    worker: Option<JoinHandle<()>>,
    association: Option<Arc<Socks5Udp>>,
    closed: bool,
}

impl RelayKeeper {
    /// Puts the socket that `target` routes on the route of `settings` at once, then keeps it there; `what` names the
    /// socket in the log ("Kad UDP"), `local` is the address to reach the proxy from, none for any.
    pub fn start(
        what: &str,
        target: impl Fn(UdpRoute) + Send + Sync + 'static,
        settings: ProxySettings,
        local: Option<Ipv4Addr>,
        log: impl Fn(Level, &str) + Send + Sync + 'static,
    ) -> Self {
        Self::start_waiting(what, target, settings, local, log, FIRST_WAIT)
    }

    /// As [`RelayKeeper::start`], asking the proxy again after `first_wait` first.
    pub fn start_waiting(
        what: &str,
        target: impl Fn(UdpRoute) + Send + Sync + 'static,
        settings: ProxySettings,
        local: Option<Ipv4Addr>,
        log: impl Fn(Level, &str) + Send + Sync + 'static,
        first_wait: Duration,
    ) -> Self {
        let inner = Arc::new(Inner {
            what: what.to_owned(),
            target: Box::new(target),
            local,
            log: Box::new(log),
            first_wait,
            state: Mutex::default(),
        });
        let keeper = Self { inner };
        keeper.follow(settings);
        keeper
    }

    /// Follows `now`, the proxy settings that changed; the same settings change nothing.
    pub fn follow(&self, now: ProxySettings) {
        let inner = &self.inner;
        let mut state = inner.lock();
        if state.closed || state.settings.as_ref() == Some(&now) {
            return;
        }
        state.settings = Some(now.clone());
        stop_worker(&mut state);
        state.generation = state.generation.saturating_add(1);
        match now.kind {
            ProxyType::None => (inner.target)(UdpRoute::Direct),
            ProxyType::Http => {
                (inner.target)(UdpRoute::Direct);
                (inner.log)(
                    Level::Warning,
                    &format!("{} goes straight to the network: an HTTP proxy carries TCP only", inner.what),
                );
            }
            ProxyType::Socks => {
                (inner.target)(UdpRoute::Held);
                state.worker = Some(tokio::spawn(keep(Arc::clone(inner), now, state.generation)));
            }
        }
    }

    /// Stops keeping the route; the socket keeps the last one, for its own close.
    pub fn close(&self) {
        let mut state = self.inner.lock();
        state.closed = true;
        stop_worker(&mut state);
    }
}

impl Drop for RelayKeeper {
    fn drop(&mut self) {
        self.close();
    }
}

impl Inner {
    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }

    // Routes as asked, unless other settings came meanwhile.
    fn route(&self, route: UdpRoute, current: u64) -> bool {
        let mut state = self.lock();
        if state.closed || current != state.generation {
            return false;
        }
        state.association = match &route {
            UdpRoute::Through(association) => Some(Arc::clone(association)),
            _ => None,
        };
        (self.target)(route);
        true
    }
}

fn stop_worker(state: &mut State) {
    if let Some(worker) = state.worker.take() {
        worker.abort();
    }
    if let Some(association) = state.association.take() {
        association.close();
    }
}

// Asks for an association, routes through it while it lasts, and asks again after it ends or fails.
async fn keep(inner: Arc<Inner>, proxy: ProxySettings, current: u64) {
    let what = &inner.what;
    let mut wait = inner.first_wait;
    let mut failed_before = false;
    loop {
        match Socks5Udp::associate(&proxy, inner.local, TIMEOUT).await {
            Ok(made) => {
                let made = Arc::new(made);
                if !inner.route(UdpRoute::Through(Arc::clone(&made)), current) {
                    made.close();
                    return;
                }
                let relay = made.relay();
                (inner.log)(Level::Info, &format!("{what} goes through the UDP relay of the SOCKS5 proxy, {relay}"));
                wait = inner.first_wait;
                failed_before = false;
                made.ended().await;
                if !inner.route(UdpRoute::Held, current) {
                    return;
                }
                (inner.log)(
                    Level::Warning,
                    &format!("{what} is held: the SOCKS5 proxy ended its UDP relay, asking it again"),
                );
            }
            Err(e @ (Socks5Error::NotVersion5 | Socks5Error::Unsupported(_))) => {
                if inner.route(UdpRoute::Direct, current) {
                    (inner.log)(Level::Warning, &format!("{what} goes straight to the network: {e}"));
                }
                return;
            }
            Err(Socks5Error::Io(e)) => {
                if !failed_before {
                    (inner.log)(Level::Warning, &format!("{what} is held until the SOCKS5 proxy answers: {e}"));
                }
                failed_before = true;
            }
        }
        tokio::time::sleep(wait).await;
        wait = wait.saturating_mul(2).min(LONGEST_WAIT);
    }
}
