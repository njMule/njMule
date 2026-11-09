// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Our ports forwarded by the router while njMule runs: found with UPnP, or else with NAT-PMP or PCP, and mapped by a
//! task of its own at the start (looked for again later while none answers), for a lease renewed at half its time
//! (which also recreates mappings the router lost), unmapped at the end. Only the mappings made here are ever removed.
//! With an IPv6 address of ours, which no NAT hides, the router's IPv6 firewall is opened to the same ports in the same
//! way, when it can be.

use std::fmt;
use std::net::{Ipv6Addr, SocketAddr, SocketAddrV6};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use futures_util::future::BoxFuture;
use tokio::sync::watch;
use tokio::task::JoinHandle;

use crate::upnp::default_gateway;
use crate::upnp::gateway::{GatewayError, Protocol};
use crate::upnp::igd::{self, Igd};
use crate::upnp::nat_pmp::{self, NatPmp};

/// How long discovery waits for a router to answer.
pub const DISCOVERY: Duration = Duration::from_secs(3);
/// How long the router keeps a mapping we no longer renew.
pub const LEASE: Duration = Duration::from_secs(3600);
/// A router that did not answer is looked for again after this, then twice as long each time up to the longest.
pub const FIRST_RETRY: Duration = Duration::from_secs(60);
pub const LONGEST_RETRY: Duration = Duration::from_secs(30 * 60);

// Within the day a pinhole may last at most.
const PINHOLE_LEASE: u32 = 3600;
const DESCRIPTION: &str = "njMule";
// The UPnP error of a router that keeps mappings only until they are removed.
const ONLY_PERMANENT_LEASES: &str = "725";
// The shortest wait between renewals, whatever lease a router grants.
const SHORTEST_RENEWAL: Duration = Duration::from_secs(10);

/// What the router made of the mappings asked.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    /// Every port is forwarded.
    Forwarded,
    /// Some ports are forwarded, the router refused the others.
    Partly,
    /// The router refused every port.
    Refused,
    /// No router answered.
    NoRouter,
    /// The router stopped answering, or answered what cannot be read.
    Failed,
}

/// Where the outcome goes: its kind, the router's host (empty when none answered) and one line for the log.
pub type Report = Arc<dyn Fn(Outcome, &str, &str) + Send + Sync>;
/// Waits between renewals and searches; tests pass one they control.
pub type Pause = Arc<dyn Fn(Duration) -> BoxFuture<'static, ()> + Send + Sync>;
/// Our IPv6 address now, read at each renewal.
pub type OurIpv6 = Arc<dyn Fn() -> Option<Ipv6Addr> + Send + Sync>;
/// A router's address, read again at each search.
pub type Finder = Arc<dyn Fn() -> Option<SocketAddr> + Send + Sync>;

/// Where the router that forwards our ports is looked for: UPnP's search address, then the NAT-PMP and PCP port of the
/// default gateway; and the PCP port of the IPv6 default gateway, which may open its IPv6 firewall when UPnP does not.
#[derive(Clone)]
pub struct Routers {
    pub ssdp: SocketAddr,
    pub nat_pmp: Finder,
    pub pcp_ipv6: Finder,
    pub first_retry: Duration,
}

impl Routers {
    /// This computer's: SSDP's multicast address, and port 5351 of the default gateways.
    pub fn of_this_computer() -> Self {
        Self::new(
            igd::SSDP,
            Arc::new(|| default_gateway::find().map(|gateway| SocketAddr::from((gateway, nat_pmp::PORT)))),
            Arc::new(|| {
                default_gateway::find_ipv6()
                    .map(|(gateway, scope)| SocketAddr::V6(SocketAddrV6::new(gateway, nat_pmp::PORT, 0, scope)))
            }),
        )
    }

    /// Looked for again a minute after none answered.
    pub fn new(ssdp: SocketAddr, nat_pmp: Finder, pcp_ipv6: Finder) -> Self {
        Self { ssdp, nat_pmp, pcp_ipv6, first_retry: FIRST_RETRY }
    }

    /// UPnP's search at `ssdp` only.
    pub fn upnp_only(ssdp: SocketAddr) -> Self {
        Self::new(ssdp, Arc::new(|| None), Arc::new(|| None))
    }

    /// The same routers, looked for again `wait` after none answered.
    pub fn retrying_after(self, wait: Duration) -> Self {
        Self { first_retry: wait, ..self }
    }
}

// A router found: with UPnP, or one that answers NAT-PMP or PCP.
enum Router {
    Upnp(Igd),
    NatPmp(NatPmp),
}

impl Router {
    fn protocol(&self) -> &'static str {
        match self {
            Self::Upnp(igd) => igd.protocol(),
            Self::NatPmp(nat_pmp) => nat_pmp.protocol(),
        }
    }

    fn host(&self) -> String {
        match self {
            Self::Upnp(igd) => igd.host(),
            Self::NatPmp(nat_pmp) => nat_pmp.host(),
        }
    }

    async fn add_mapping(&self, mapping: Mapping, lease: u32) -> Result<u32, GatewayError> {
        match self {
            Self::Upnp(igd) => igd.add_mapping(mapping.protocol, mapping.port, DESCRIPTION, lease).await,
            Self::NatPmp(nat_pmp) => nat_pmp.add_mapping(mapping.protocol, mapping.port, lease).await,
        }
    }

    async fn delete_mapping(&self, mapping: Mapping) -> Result<(), GatewayError> {
        match self {
            Self::Upnp(igd) => igd.delete_mapping(mapping.protocol, mapping.port).await,
            Self::NatPmp(nat_pmp) => nat_pmp.delete_mapping(mapping.protocol, mapping.port).await,
        }
    }

    async fn open_pinhole(&self, mapping: Mapping, address: Ipv6Addr) -> Result<Option<u32>, GatewayError> {
        match self {
            Self::Upnp(igd) => igd.open_pinhole(mapping.protocol, address, mapping.port, PINHOLE_LEASE).await,
            Self::NatPmp(nat_pmp) => nat_pmp.open_pinhole(mapping.protocol, address, mapping.port, PINHOLE_LEASE).await,
        }
    }

    async fn renew_pinhole(&self, id: u32) -> Result<(), GatewayError> {
        match self {
            Self::Upnp(igd) => igd.renew_pinhole(id, PINHOLE_LEASE).await,
            Self::NatPmp(nat_pmp) => nat_pmp.renew_pinhole(id, PINHOLE_LEASE).await,
        }
    }

    async fn close_pinhole(&self, id: u32) -> Result<(), GatewayError> {
        match self {
            Self::Upnp(igd) => igd.close_pinhole(id).await,
            Self::NatPmp(nat_pmp) => nat_pmp.close_pinhole(id).await,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Mapping {
    protocol: Protocol,
    port: u16,
}

impl fmt::Display for Mapping {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} {}", self.protocol.name(), self.port)
    }
}

#[derive(Clone, Copy)]
struct Pinhole {
    mapping: Mapping,
    address: Ipv6Addr,
    id: u32,
}

// What the task that maps and the end share.
#[derive(Default)]
struct State {
    router: Option<Arc<Router>>,
    // The PCP server at the IPv6 gateway, when the router opens no pinholes itself.
    ipv6_firewall: Option<Arc<Router>>,
    // Mappings the router may hold for us: those made, and those asked for whose answer did not come.
    mapped: Vec<Mapping>,
    // The pinholes the router opened for us.
    pinholes: Vec<Pinhole>,
}

pub struct PortForwarding {
    state: Arc<Mutex<State>>,
    started: watch::Receiver<bool>,
    worker: JoinHandle<()>,
}

impl PortForwarding {
    /// Starts forwarding `tcp_port` and, when given, `udp_port` by a router of `routers`; `report` gets the outcome.
    pub fn start(routers: Routers, tcp_port: u16, udp_port: Option<u16>, report: Report) -> Self {
        let pause: Pause = Arc::new(|wait| Box::pin(tokio::time::sleep(wait)));
        Self::start_with(routers, tcp_port, udp_port, report, pause, Arc::new(|| None))
    }

    /// As [`PortForwarding::start`], waiting with `pause` and opening the router's IPv6 firewall to the same ports at
    /// `ipv6`, read at each renewal.
    pub fn start_with(
        routers: Routers,
        tcp_port: u16,
        udp_port: Option<u16>,
        report: Report,
        pause: Pause,
        ipv6: OurIpv6,
    ) -> Self {
        let mut wanted = vec![Mapping { protocol: Protocol::Tcp, port: tcp_port }];
        wanted.extend(udp_port.map(|port| Mapping { protocol: Protocol::Udp, port }));
        let state = Arc::new(Mutex::new(State::default()));
        let (started, started_now) = watch::channel(false);
        let worker = Worker {
            routers,
            wanted,
            report,
            pause,
            ipv6,
            state: Arc::clone(&state),
            started,
            ipv6_firewall_sought: false,
            lease: u32::try_from(LEASE.as_secs()).unwrap_or(u32::MAX),
            granted: 0,
        };
        Self { state, started: started_now, worker: tokio::spawn(worker.run()) }
    }

    /// The forwarded ports, as "TCP 4662".
    pub fn mappings(&self) -> Vec<String> {
        lock(&self.state).mapped.iter().map(ToString::to_string).collect()
    }

    /// Waits up to `wait` for the router to be found and asked for the ports, or for none to answer.
    pub async fn wait_started(&self, wait: Duration) {
        let mut started = self.started.clone();
        let _ = tokio::time::timeout(wait, started.wait_for(|started| *started)).await;
    }

    /// Removes the mappings made here, one asked for while closing included, and closes the pinholes.
    pub async fn close(self) {
        self.worker.abort();
        let _ = self.worker.await;
        let (router, ipv6_firewall, mapped, pinholes) = {
            let mut state = lock(&self.state);
            let mapped = std::mem::take(&mut state.mapped);
            let pinholes = std::mem::take(&mut state.pinholes);
            (state.router.take(), state.ipv6_firewall.take(), mapped, pinholes)
        };
        let Some(router) = router else {
            return;
        };
        let mut removed = Vec::new();
        for mapping in mapped {
            match router.delete_mapping(mapping).await {
                Ok(()) => removed.push(mapping.to_string()),
                Err(e) => tracing::info!(
                    "{}: the router at {} did not remove {mapping}, which ends with its lease: {e}",
                    router.protocol(),
                    router.host()
                ),
            }
        }
        if !removed.is_empty() {
            tracing::info!("{}: {} removed from {}", router.protocol(), removed.join(" and "), router.host());
        }
        let opener = ipv6_firewall.unwrap_or(router);
        let mut closed = Vec::new();
        for pinhole in pinholes {
            if close_pinhole(&opener, pinhole).await {
                closed.push(pinhole.mapping.to_string());
            }
        }
        if !closed.is_empty() {
            tracing::info!(
                "{}: the IPv6 firewall of {} closed to {}",
                opener.protocol(),
                opener.host(),
                closed.join(" and ")
            );
        }
    }
}

struct Worker {
    routers: Routers,
    wanted: Vec<Mapping>,
    report: Report,
    pause: Pause,
    ipv6: OurIpv6,
    state: Arc<Mutex<State>>,
    started: watch::Sender<bool>,
    ipv6_firewall_sought: bool,
    // The lease asked for, and the shortest the router granted at the last round (0: none).
    lease: u32,
    granted: u32,
}

impl Worker {
    async fn run(mut self) {
        let mut retry = self.routers.first_retry;
        let mut failure = self.find_and_map(None).await;
        while let Some(told) = failure {
            self.started.send_replace(true);
            (self.pause)(retry).await;
            retry = retry.saturating_mul(2).min(LONGEST_RETRY);
            failure = self.find_and_map(Some(told)).await;
        }
        self.started.send_replace(true);
        loop {
            (self.pause)(self.renewal()).await;
            self.renew().await;
        }
    }

    // None once the router is found and asked for the ports, else what went wrong; a failure is reported when it
    // differs from the one told last, so that a router that stays silent fills no log.
    async fn find_and_map(&mut self, told: Option<Outcome>) -> Option<Outcome> {
        let failed = match self.discover().await {
            Ok(None) => {
                self.tell(told, Outcome::NoRouter, "", "UPnP, NAT-PMP and PCP: no router answered");
                return Some(Outcome::NoRouter);
            }
            Ok(Some(found)) => {
                // A router that failed before: each discovery gives a gateway of its own.
                lock(&self.state).router = Some(Arc::new(found));
                match self.map_all(true).await {
                    Ok(()) => {
                        self.pin_all().await;
                        return None;
                    }
                    Err(e) => e,
                }
            }
            Err(e) => GatewayError::Failed(e),
        };
        let router = lock(&self.state).router.clone();
        let (host, protocol) =
            router.map_or_else(|| (String::new(), "UPnP"), |router| (router.host(), router.protocol()));
        self.tell(told, Outcome::Failed, &host, &format!("{protocol} failed: {failed}"));
        Some(Outcome::Failed)
    }

    fn tell(&self, told: Option<Outcome>, outcome: Outcome, router: &str, text: &str) {
        if told == Some(outcome) {
            tracing::debug!("{text}, still: looking again later");
        } else {
            (self.report)(outcome, router, text);
        }
    }

    // UPnP first, the router of most homes; else NAT-PMP or PCP at the default gateway.
    async fn discover(&self) -> std::io::Result<Option<Router>> {
        if let Some(found) = Igd::discover(self.routers.ssdp, DISCOVERY).await? {
            return Ok(Some(Router::Upnp(found)));
        }
        let Some(at) = (self.routers.nat_pmp)() else {
            return Ok(None);
        };
        Ok(NatPmp::discover(at, DISCOVERY).await?.map(Router::NatPmp))
    }

    // Half the shortest lease granted, as for the one asked for; a router may grant less.
    fn renewal(&self) -> Duration {
        let lease = if self.granted > 0 { Duration::from_secs(u64::from(self.granted)) } else { LEASE };
        lease.checked_div(2).unwrap_or_default().max(SHORTEST_RENEWAL)
    }

    // Asks for every port; refusals are reported the first time and tried again at each renewal. An error of the
    // router ends the round.
    async fn map_all(&mut self, tell: bool) -> Result<(), GatewayError> {
        let mut refused = Vec::new();
        self.granted = 0;
        for mapping in self.wanted.clone() {
            match self.add(mapping).await {
                Ok(granted) => {
                    if granted > 0 && (self.granted == 0 || granted < self.granted) {
                        self.granted = granted;
                    }
                }
                Err(GatewayError::Refused(reason)) => refused.push(format!("{mapping} ({reason})")),
                Err(e) => return Err(e),
            }
        }
        if !tell {
            return Ok(());
        }
        let Some(router) = lock(&self.state).router.clone() else {
            return Ok(());
        };
        let (host, protocol) = (router.host(), router.protocol());
        let mapped: Vec<String> = lock(&self.state).mapped.iter().map(ToString::to_string).collect();
        let forwarded = format!("{} forwarded by {host}", mapped.join(" and "));
        let denied = refused.join(" and ");
        if refused.is_empty() {
            (self.report)(Outcome::Forwarded, &host, &format!("{protocol}: {forwarded}"));
        } else if mapped.is_empty() {
            (self.report)(Outcome::Refused, &host, &format!("{protocol}: the router at {host} refused {denied}"));
        } else {
            (self.report)(Outcome::Partly, &host, &format!("{protocol}: {forwarded}, which refused {denied}"));
        }
        Ok(())
    }

    // The lease granted, 0 for until removed.
    async fn add(&mut self, mapping: Mapping) -> Result<u32, GatewayError> {
        let (router, known) = {
            let mut state = lock(&self.state);
            let known = state.mapped.contains(&mapping);
            // Ours from the request on: an answer lost to the end may still have made it.
            if !known {
                state.mapped.push(mapping);
            }
            (state.router.clone(), known)
        };
        let router = router.ok_or_else(|| GatewayError::Failed(std::io::Error::other("no router")))?;
        let mut asked = router.add_mapping(mapping, self.lease).await;
        if let Err(GatewayError::Refused(reason)) = &asked
            && matches!(*router, Router::Upnp(_))
            && self.lease != 0
            && reason.starts_with(ONLY_PERMANENT_LEASES)
        {
            self.lease = 0;
            asked = router.add_mapping(mapping, 0).await;
        }
        if matches!(asked, Err(GatewayError::Refused(_))) && !known {
            lock(&self.state).mapped.retain(|held| *held != mapping);
        }
        asked
    }

    // A renewal asks again, from our address now; a router that no longer answers is looked for again.
    async fn renew(&mut self) {
        match self.map_all(false).await {
            Ok(()) => {
                self.pin_all().await;
                return;
            }
            Err(e) => tracing::debug!("Port forwarding: renewal failed, looking for the router again: {e}"),
        }
        match self.discover().await {
            Ok(Some(found)) => {
                {
                    let mut state = lock(&self.state);
                    state.router = Some(Arc::new(found));
                    // The pinholes of the router that went away close at the end of their lease.
                    state.pinholes.clear();
                }
                match self.map_all(true).await {
                    Ok(()) => self.pin_all().await,
                    Err(e) => tracing::debug!("Port forwarding: the router is still away: {e}"),
                }
            }
            Ok(None) => {}
            // Tried again at the next renewal.
            Err(e) => tracing::debug!("Port forwarding: the router is still away: {e}"),
        }
    }

    // Each port gets a pinhole at our IPv6 address, renewed with the mappings; one at a former address is closed. A
    // router that opens none is asked through PCP at the IPv6 gateway, once.
    async fn pin_all(&mut self) {
        let router = {
            let state = lock(&self.state);
            state.ipv6_firewall.clone().or_else(|| state.router.clone())
        };
        let Some(router) = router else {
            return;
        };
        let ours = (self.ipv6)();
        let opened = self.pin_all_at(&router, ours).await;
        let nothing_held = lock(&self.state).pinholes.is_empty();
        if opened == 0 && nothing_held && ours.is_some() && !self.ipv6_firewall_sought {
            self.ipv6_firewall_sought = true;
            if let Some(pcp) = self.ipv6_gateway().await {
                let pcp = Arc::new(pcp);
                lock(&self.state).ipv6_firewall = Some(Arc::clone(&pcp));
                self.pin_all_at(&pcp, ours).await;
            }
        }
    }

    async fn ipv6_gateway(&self) -> Option<Router> {
        let at = (self.routers.pcp_ipv6)()?;
        match NatPmp::discover(at, DISCOVERY).await {
            Ok(found) => found.map(Router::NatPmp),
            Err(e) => {
                tracing::debug!("PCP: no answer at the IPv6 gateway {at}: {e}");
                None
            }
        }
    }

    // Returns how many pinholes were opened now.
    async fn pin_all_at(&self, router: &Router, ours: Option<Ipv6Addr>) -> usize {
        let moved: Vec<Pinhole> = {
            let mut state = lock(&self.state);
            let (kept, moved) = state.pinholes.iter().partition(|pinhole| Some(pinhole.address) == ours);
            state.pinholes = kept;
            moved
        };
        for pinhole in moved {
            close_pinhole(router, pinhole).await;
        }
        let Some(ours) = ours else {
            return 0;
        };
        let mut opened = Vec::new();
        for mapping in &self.wanted {
            let held = lock(&self.state).pinholes.iter().find(|pinhole| pinhole.mapping == *mapping).copied();
            let done = match held {
                Some(held) => router.renew_pinhole(held.id).await,
                None => match router.open_pinhole(*mapping, ours).await {
                    Ok(Some(id)) => {
                        lock(&self.state).pinholes.push(Pinhole { mapping: *mapping, address: ours, id });
                        opened.push(mapping.to_string());
                        Ok(())
                    }
                    Ok(None) => Ok(()),
                    Err(e) => Err(e),
                },
            };
            if let Err(e) = done {
                // Opened again at the next renewal.
                if let Some(held) = held {
                    lock(&self.state).pinholes.retain(|pinhole| pinhole.id != held.id);
                }
                tracing::debug!("{}: no pinhole for {mapping}: {e}", router.protocol());
            }
        }
        if !opened.is_empty() {
            tracing::info!(
                "{}: the IPv6 firewall of {} lets clients in to {} at {ours}",
                router.protocol(),
                router.host(),
                opened.join(" and ")
            );
        }
        opened.len()
    }
}

// Whether the router closed it; one that did not closes it at the end of its lease.
async fn close_pinhole(router: &Router, pinhole: Pinhole) -> bool {
    match router.close_pinhole(pinhole.id).await {
        Ok(()) => true,
        Err(e) => {
            tracing::debug!("{}: cannot close the pinhole for {}: {e}", router.protocol(), pinhole.mapping);
            false
        }
    }
}

fn lock(state: &Mutex<State>) -> MutexGuard<'_, State> {
    state.lock().unwrap_or_else(PoisonError::into_inner)
}
