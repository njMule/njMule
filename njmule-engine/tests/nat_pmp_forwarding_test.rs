// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's NatPmpForwardingTest, under its names: a router that answers NAT-PMP or PCP forwards our ports when
//! no UPnP one answers.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use njmule_engine::upnp::port_forwarding::{PortForwarding, Routers};
use tokio::net::UdpSocket;
use tokio::sync::{Semaphore, mpsc};

use crate::fake_nat_pmp::{FakeNatPmp, Speaks};

#[tokio::test]
async fn should_forward_our_ports_by_nat_pmp_when_no_upnp_router_answers() {
    let mut test = Forwardings::new();
    test.given_no_upnp_router().await;
    test.given_a_router_that_speaks(Speaks::NatPmp).await;

    test.when_a_forwarding_starts().await;

    test.then_the_only_line_logged_is("NAT-PMP: TCP 4662 and UDP 4672 forwarded by 127.0.0.1");
    test.then_the_mappings_are(&["TCP 4662", "UDP 4672"]);
}

#[tokio::test]
async fn should_forward_our_ports_by_pcp() {
    let mut test = Forwardings::new();
    test.given_no_upnp_router().await;
    test.given_a_router_that_speaks(Speaks::Pcp).await;

    test.when_a_forwarding_starts().await;

    test.then_the_only_line_logged_is("PCP: TCP 4662 and UDP 4672 forwarded by 127.0.0.1");
}

#[tokio::test]
async fn should_open_the_ipv6_firewall_by_pcp_at_the_ipv6_gateway_when_the_router_opens_none() {
    let mut test = Forwardings::new();
    if !test.given_an_ipv6_loopback().await {
        return;
    }
    test.given_no_upnp_router().await;
    test.given_a_router_that_speaks(Speaks::NatPmp).await;
    test.given_a_pcp_server_at_the_ipv6_gateway().await;
    test.given_our_ipv6_address(Ipv6Addr::LOCALHOST);

    test.when_a_forwarding_starts().await;

    test.then_the_mappings_are(&["TCP 4662", "UDP 4672"]);
    test.then_the_ipv6_gateway_was_asked_from("::1", &["map TCP 4662 3600 ", "map UDP 4672 3600 "]);
}

#[tokio::test]
async fn should_renew_at_half_the_lease_the_router_granted() {
    let mut test = Forwardings::new();
    test.given_no_upnp_router().await;
    test.given_a_router_that_speaks(Speaks::NatPmp).await;
    test.given_the_router_grants_at_most(600);

    test.when_a_forwarding_starts().await;

    test.then_the_first_renewal_waits(Duration::from_secs(300)).await;
}

#[tokio::test]
async fn should_remove_the_mappings_at_the_end() {
    let mut test = Forwardings::new();
    test.given_no_upnp_router().await;
    test.given_a_router_that_speaks(Speaks::NatPmp).await;
    test.given_a_forwarding_started().await;

    test.when_the_forwarding_is_closed().await;

    test.then_the_router_was_asked(&["map TCP 4662 3600", "map UDP 4672 3600", "delete TCP 4662", "delete UDP 4672"]);
}

#[tokio::test]
async fn should_say_that_no_router_answered() {
    let mut test = Forwardings::new();
    test.given_no_upnp_router().await;
    test.given_no_nat_pmp_router().await;

    test.when_a_forwarding_starts().await;

    test.then_the_only_line_logged_is("UPnP, NAT-PMP and PCP: no router answered");
}

/// Silent addresses or routers on the loopback, a forwarding through them, and what it logged and waited.
struct Forwardings {
    no_upnp: Option<UdpSocket>,
    router: Option<FakeNatPmp>,
    no_router: Option<UdpSocket>,
    ipv6_gateway: Option<FakeNatPmp>,
    ours: Option<Ipv6Addr>,
    forwarding: Option<PortForwarding>,
    log: Arc<Mutex<Vec<String>>>,
    waits: Option<mpsc::UnboundedReceiver<Duration>>,
    renew: Arc<Semaphore>,
}

impl Forwardings {
    fn new() -> Self {
        Self {
            no_upnp: None,
            router: None,
            no_router: None,
            ipv6_gateway: None,
            ours: None,
            forwarding: None,
            log: Arc::default(),
            waits: None,
            renew: Arc::new(Semaphore::new(0)),
        }
    }

    // Without an IPv6 loopback here, the example cannot run.
    async fn given_an_ipv6_loopback(&self) -> bool {
        UdpSocket::bind((Ipv6Addr::LOCALHOST, 0)).await.is_ok()
    }

    async fn given_no_upnp_router(&mut self) {
        self.no_upnp = Some(UdpSocket::bind("127.0.0.1:0").await.unwrap());
    }

    async fn given_a_router_that_speaks(&mut self, speaks: Speaks) {
        self.router = Some(FakeNatPmp::new(speaks).await);
    }

    async fn given_no_nat_pmp_router(&mut self) {
        self.no_router = Some(UdpSocket::bind("127.0.0.1:0").await.unwrap());
    }

    async fn given_a_pcp_server_at_the_ipv6_gateway(&mut self) {
        self.ipv6_gateway = Some(FakeNatPmp::at(Speaks::Pcp, IpAddr::V6(Ipv6Addr::LOCALHOST)).await);
    }

    fn given_our_ipv6_address(&mut self, address: Ipv6Addr) {
        self.ours = Some(address);
    }

    fn given_the_router_grants_at_most(&self, seconds: u32) {
        self.router.as_ref().unwrap().grants_at_most(seconds);
    }

    async fn given_a_forwarding_started(&mut self) {
        self.when_a_forwarding_starts().await;
    }

    async fn when_a_forwarding_starts(&mut self) {
        let nat_pmp = match &self.router {
            Some(router) => router.address(),
            None => self.no_router.as_ref().unwrap().local_addr().unwrap(),
        };
        let pcp_ipv6: Option<SocketAddr> = self.ipv6_gateway.as_ref().map(FakeNatPmp::address);
        let ssdp = self.no_upnp.as_ref().unwrap().local_addr().unwrap();
        let routers = Routers::new(ssdp, Arc::new(move || Some(nat_pmp)), Arc::new(move || pcp_ipv6));
        let (asking, waits) = mpsc::unbounded_channel();
        let renew = Arc::clone(&self.renew);
        let log = Arc::clone(&self.log);
        let ours = self.ours;
        let forwarding = PortForwarding::start_with(
            routers,
            4662,
            Some(4672),
            Arc::new(move |_, _, line| log.lock().unwrap().push(line.to_owned())),
            Arc::new(move |wait| {
                let _ = asking.send(wait);
                let renew = Arc::clone(&renew);
                Box::pin(async move { renew.acquire().await.unwrap().forget() })
            }),
            Arc::new(move || ours),
        );
        forwarding.wait_started(Duration::from_secs(20)).await;
        let deadline = Instant::now() + Duration::from_secs(20);
        while self.log.lock().unwrap().is_empty() && Instant::now() < deadline {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        self.forwarding = Some(forwarding);
        self.waits = Some(waits);
    }

    async fn when_the_forwarding_is_closed(&mut self) {
        self.forwarding.take().unwrap().close().await;
    }

    fn then_the_only_line_logged_is(&self, line: &str) {
        assert_eq!(*self.log.lock().unwrap(), [line]);
    }

    fn then_the_mappings_are(&self, mappings: &[&str]) {
        assert_eq!(self.forwarding.as_ref().unwrap().mappings(), mappings);
    }

    fn then_the_ipv6_gateway_was_asked_from(&self, client: &str, starts: &[&str]) {
        let gateway = self.ipv6_gateway.as_ref().unwrap();
        let asked = gateway.requests();
        assert_eq!(asked.len(), starts.len(), "{asked:?}");
        for (request, start) in asked.iter().zip(starts) {
            assert!(request.starts_with(start), "{request} starts with {start}");
        }
        assert!(gateway.clients().iter().all(|c| c == client), "{:?}", gateway.clients());
    }

    async fn then_the_first_renewal_waits(&mut self, wait: Duration) {
        let waits = self.waits.as_mut().unwrap();
        let asked = tokio::time::timeout(Duration::from_secs(10), waits.recv()).await.unwrap();
        assert_eq!(asked, Some(wait));
    }

    fn then_the_router_was_asked(&self, requests: &[&str]) {
        assert_eq!(self.router.as_ref().unwrap().requests(), requests);
    }
}
