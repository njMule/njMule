// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The network's side of the forwarding of our ports: the router asked as the preferences and the ports say, its
//! answer in the user's log, the status and a port test, and the server and Kad asked to check our ports again once a
//! router answers a later search.

use std::sync::Arc;
use std::time::Duration;

use tokio::task::JoinHandle;

use super::{Network, Tick};
use crate::api::log_line::Level as LogLevel;
use crate::api::port_test::Forwarding;
use crate::engine::port_check;
use crate::engine::switches::Upnp as UpnpSwitch;
use crate::upnp::port_forwarding::{Outcome, PortForwarding, Report, Routers};

// How long the end waits for the router to remove our mappings.
const UNMAP_WAIT: Duration = Duration::from_secs(15);

/// Where the router is looked for, and how it answered the mappings asked last.
pub(super) struct Upnp {
    routers: Option<Routers>,
    wanted: bool,
    // Counts the starts: an answer to mappings asked before the last ones no longer tells how the router stands.
    run: u64,
    pub(super) answer: Forwarding,
    pub(super) router: String,
    // The changes one after the other, each giving the forwarding it leaves running: a change waits for the removals
    // of the one before, so that no mapping is left behind.
    changes: Option<JoinHandle<Option<PortForwarding>>>,
}

impl Upnp {
    pub(super) fn new(switch: UpnpSwitch) -> Self {
        let routers = match switch {
            UpnpSwitch::Off => None,
            UpnpSwitch::ThisComputer => Some(Routers::of_this_computer()),
            UpnpSwitch::At(ssdp, retry) => Some(Routers::upnp_only(ssdp).retrying_after(retry)),
        };
        Self { routers, wanted: false, run: 0, answer: Forwarding::Off, router: String::new(), changes: None }
    }

    /// Whether the router can be asked at all.
    pub(super) fn available(&self) -> bool {
        self.routers.is_some()
    }
}

impl Network {
    /// Asks the router for our ports again, as the preferences and the ports are now; the mappings follow the ports.
    pub(super) fn restart_forwarding(&mut self) {
        let wanted = self.preferences().upnp && self.accepting.is_some() && self.upnp.available();
        if !wanted && !self.upnp.wanted {
            return;
        }
        self.upnp.wanted = wanted;
        self.upnp.run = self.upnp.run.wrapping_add(1);
        self.upnp.answer = if wanted { Forwarding::Asking } else { Forwarding::Off };
        self.upnp.router.clear();
        let run = self.upnp.run;
        let tcp = self.shared.listen_port();
        let udp = Some(self.kad_udp_port()).filter(|port| *port != 0);
        let routers = self.upnp.routers.clone().filter(|_| wanted);
        let ticks = self.ticks.clone();
        let before = self.upnp.changes.take();
        self.upnp.changes = Some(tokio::spawn(async move {
            if let Some(Ok(Some(running))) = match before {
                Some(before) => Some(before.await),
                None => None,
            } {
                running.close().await;
            }
            let routers = routers?;
            let report: Report = Arc::new(move |outcome, router: &str, line: &str| {
                let _ = ticks.send(Tick::Forwarded(run, outcome, router.to_owned(), line.to_owned()));
            });
            Some(PortForwarding::start(routers, tcp, udp, report))
        }));
    }

    /// What the router made of the mappings asked in the run `run`.
    pub(super) fn forwarded(&mut self, run: u64, outcome: Outcome, router: String, line: String) {
        let level = if outcome == Outcome::Forwarded { LogLevel::Success } else { LogLevel::Warning };
        self.user_log(level, line);
        let testing = self.port_check.as_ref().is_some_and(|check| !check.is_over());
        if run == self.upnp.run {
            let before = self.upnp.answer;
            self.upnp.answer = port_check::forwarding(outcome);
            self.upnp.router.clone_from(&router);
            let late = matches!(before, Forwarding::NoRouter | Forwarding::Failed);
            let open = matches!(outcome, Outcome::Forwarded | Outcome::Partly);
            if late && open && !testing {
                self.check_reach_again();
            }
        }
        if testing && let Some(check) = &mut self.port_check {
            check.forwarded(outcome, &router);
        }
    }

    // The router answered a later search: the server and Kad check our ports again, as in a port test.
    fn check_reach_again(&mut self) {
        let low_id = self.session.id().is_some_and(|id| id.is_low_id());
        let kad_firewalled = self.kad_info().is_some_and(|kad| kad.firewalled);
        if !low_id && !kad_firewalled {
            return;
        }
        self.user_log(LogLevel::Info, "UPnP: the router answered at last, checking the ports again".to_owned());
        if low_id {
            let server = self.session.server().cloned();
            self.with_session(|session, ctx| session.connect(server, ctx));
        }
        if kad_firewalled {
            self.recheck_kad();
        }
    }

    /// The end: the router is asked to remove our mappings once the rest is saved, a slow one holding the end back
    /// no longer than a while.
    pub(super) async fn unmap(&mut self) {
        let Some(changes) = self.upnp.changes.take() else {
            return;
        };
        let removed = async {
            if let Ok(Some(running)) = changes.await {
                running.close().await;
            }
        };
        if tokio::time::timeout(UNMAP_WAIT, removed).await.is_err() {
            tracing::info!("UPnP: the router did not remove our mappings in time; they end with their lease");
        }
    }
}
