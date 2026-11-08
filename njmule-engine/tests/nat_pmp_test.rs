// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's NatPmpTest, under its names: a router found with NAT-PMP or PCP, its mappings and the IPv6 pinholes
//! of a PCP router, against a router on the loopback.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::time::Duration;

use njmule_engine::upnp::gateway::{GatewayError, Protocol};
use njmule_engine::upnp::nat_pmp::NatPmp;

use crate::fake_nat_pmp::{FakeNatPmp, Speaks};

const WAIT: Duration = Duration::from_secs(2);

#[tokio::test]
async fn should_find_a_router_that_answers_nat_pmp() {
    let mut test = Routers::new();
    test.given_a_router_that_speaks(Speaks::NatPmp).await;

    test.when_the_router_is_looked_for().await;

    test.then_the_router_speaks("NAT-PMP");
}

#[tokio::test]
async fn should_find_a_router_that_answers_pcp_only() {
    let mut test = Routers::new();
    test.given_a_router_that_speaks(Speaks::Pcp).await;

    test.when_the_router_is_looked_for().await;

    test.then_the_router_speaks("PCP");
}

#[tokio::test]
async fn should_find_no_router_where_nothing_answers() {
    let mut test = Routers::new();
    test.given_a_silent_address().await;

    test.when_the_silent_address_is_looked_at().await;

    test.then_no_router_is_found();
}

#[tokio::test]
async fn should_map_a_port_for_the_lease_the_router_grants() {
    let mut test = Routers::new();
    test.given_a_router_that_speaks(Speaks::NatPmp).await;
    test.given_the_router_grants_at_most(120);
    test.given_the_router_found().await;

    test.when_a_port_is_mapped(Protocol::Tcp, 4662, 3600).await;

    test.then_the_lease_granted_is(120);
    test.then_the_router_was_asked(&["map TCP 4662 3600"]);
}

#[tokio::test]
async fn should_map_a_port_over_pcp() {
    let mut test = Routers::new();
    test.given_a_router_that_speaks(Speaks::Pcp).await;
    test.given_the_router_found().await;

    test.when_a_port_is_mapped(Protocol::Udp, 4672, 3600).await;

    test.then_the_lease_granted_is(3600);
    test.then_the_router_was_asked_something_starting_with("map UDP 4672 3600 ");
}

#[tokio::test]
async fn should_remove_a_pcp_mapping_with_the_nonce_it_was_made_with() {
    let mut test = Routers::new();
    test.given_a_router_that_speaks(Speaks::Pcp).await;
    test.given_the_router_found().await;
    test.given_a_port_mapped(Protocol::Tcp, 4662).await;

    test.when_the_port_is_unmapped(Protocol::Tcp, 4662).await;

    test.then_the_removal_carried_the_nonce_of_the_mapping();
}

#[tokio::test]
async fn should_open_renew_and_close_an_ipv6_pinhole_from_our_address_over_pcp() {
    let mut test = Routers::new();
    if !test.given_an_ipv6_loopback().await {
        return;
    }
    test.given_a_router_on_the_ipv6_loopback_that_speaks(Speaks::Pcp).await;
    test.given_the_router_found().await;
    test.given_a_pinhole_opened_for(Protocol::Tcp, 4662).await;
    test.given_the_pinhole_renewed().await;

    test.when_the_pinhole_is_closed().await;

    test.then_the_router_was_asked_in_turn_for(&["map TCP 4662 3600 ", "map TCP 4662 3600 ", "delete TCP 4662 "]);
    test.then_each_request_came_from("::1");
    test.then_every_request_carried_the_same_nonce();
}

#[tokio::test]
async fn should_open_no_pinhole_at_a_router_on_an_ipv4_address() {
    let mut test = Routers::new();
    test.given_a_router_that_speaks(Speaks::Pcp).await;
    test.given_the_router_found().await;

    test.when_a_pinhole_is_opened_for(Protocol::Tcp, 4662).await;

    test.then_no_pinhole_is_opened();
}

#[tokio::test]
async fn should_refuse_and_remove_a_mapping_to_another_outside_port() {
    let mut test = Routers::new();
    test.given_a_router_that_speaks(Speaks::NatPmp).await;
    test.given_the_router_maps_outside_to_another_port();
    test.given_the_router_found().await;

    test.when_a_port_is_mapped(Protocol::Tcp, 4662, 3600).await;

    test.then_the_mapping_is_refused("the router gave outside port 4663 for 4662");
    test.then_the_router_was_asked(&["map TCP 4662 3600", "delete TCP 4662"]);
}

#[tokio::test]
async fn should_tell_the_refusal_of_the_router() {
    let mut test = Routers::new();
    test.given_a_router_that_speaks(Speaks::NatPmp).await;
    test.given_the_router_answers(2);
    test.given_the_router_found().await;

    test.when_a_port_is_mapped(Protocol::Tcp, 4662, 3600).await;

    test.then_the_mapping_is_refused("not authorized");
}

#[tokio::test]
async fn should_tell_the_refusal_of_a_pcp_router() {
    let mut test = Routers::new();
    test.given_a_router_that_speaks(Speaks::Pcp).await;
    test.given_the_router_answers(8);
    test.given_the_router_found().await;

    test.when_a_port_is_mapped(Protocol::Tcp, 4662, 3600).await;

    test.then_the_mapping_is_refused("no resources");
}

/// A router on the loopback, or a silent address, what was found there and what it answered.
struct Routers {
    router: Option<FakeNatPmp>,
    silent: Option<tokio::net::UdpSocket>,
    found: Option<NatPmp>,
    granted: Option<u32>,
    failure: Option<GatewayError>,
    pinhole: Option<u32>,
}

impl Routers {
    fn new() -> Self {
        Self { router: None, silent: None, found: None, granted: None, failure: None, pinhole: None }
    }

    fn router(&self) -> &FakeNatPmp {
        self.router.as_ref().unwrap()
    }

    fn found(&self) -> &NatPmp {
        self.found.as_ref().unwrap()
    }

    async fn given_a_router_that_speaks(&mut self, speaks: Speaks) {
        self.router = Some(FakeNatPmp::new(speaks).await);
    }

    // Without an IPv6 loopback here, the example cannot run.
    async fn given_an_ipv6_loopback(&self) -> bool {
        tokio::net::UdpSocket::bind((Ipv6Addr::LOCALHOST, 0)).await.is_ok()
    }

    async fn given_a_router_on_the_ipv6_loopback_that_speaks(&mut self, speaks: Speaks) {
        self.router = Some(FakeNatPmp::at(speaks, IpAddr::V6(Ipv6Addr::LOCALHOST)).await);
    }

    async fn given_a_pinhole_opened_for(&mut self, protocol: Protocol, port: u16) {
        self.pinhole = self.found().open_pinhole(protocol, Ipv6Addr::LOCALHOST, port, 3600).await.unwrap();
    }

    async fn given_the_pinhole_renewed(&self) {
        self.found().renew_pinhole(self.pinhole.unwrap(), 3600).await.unwrap();
    }

    async fn given_a_silent_address(&mut self) {
        self.silent = Some(tokio::net::UdpSocket::bind(("127.0.0.1", 0)).await.unwrap());
    }

    fn given_the_router_grants_at_most(&self, seconds: u32) {
        self.router().grants_at_most(seconds);
    }

    fn given_the_router_maps_outside_to_another_port(&self) {
        self.router().maps_outside_to(1);
    }

    fn given_the_router_answers(&self, result: u8) {
        self.router().answers(result);
    }

    async fn given_the_router_found(&mut self) {
        self.when_the_router_is_looked_for().await;
    }

    async fn given_a_port_mapped(&self, protocol: Protocol, port: u16) {
        self.found().add_mapping(protocol, port, 3600).await.unwrap();
    }

    async fn when_the_router_is_looked_for(&mut self) {
        self.found = NatPmp::discover(self.router().address(), WAIT).await.unwrap();
    }

    async fn when_the_silent_address_is_looked_at(&mut self) {
        let port = self.silent.as_ref().unwrap().local_addr().unwrap().port();
        self.found = NatPmp::discover(SocketAddr::from(([127, 0, 0, 1], port)), WAIT).await.unwrap();
    }

    async fn when_a_port_is_mapped(&mut self, protocol: Protocol, port: u16, lease: u32) {
        match self.found().add_mapping(protocol, port, lease).await {
            Ok(granted) => self.granted = Some(granted),
            Err(e) => self.failure = Some(e),
        }
    }

    async fn when_the_port_is_unmapped(&self, protocol: Protocol, port: u16) {
        self.found().delete_mapping(protocol, port).await.unwrap();
    }

    async fn when_the_pinhole_is_closed(&self) {
        self.found().close_pinhole(self.pinhole.unwrap()).await.unwrap();
    }

    async fn when_a_pinhole_is_opened_for(&mut self, protocol: Protocol, port: u16) {
        self.pinhole = self.found().open_pinhole(protocol, Ipv6Addr::LOCALHOST, port, 3600).await.unwrap();
    }

    fn then_the_router_speaks(&self, protocol: &str) {
        assert_eq!(self.found().protocol(), protocol);
        assert_eq!(self.found().host(), "127.0.0.1");
    }

    fn then_no_router_is_found(&self) {
        assert!(self.found.is_none());
    }

    fn then_the_lease_granted_is(&self, seconds: u32) {
        assert!(self.failure.is_none(), "{:?}", self.failure);
        assert_eq!(self.granted, Some(seconds));
    }

    fn then_the_router_was_asked(&self, requests: &[&str]) {
        assert_eq!(self.router().requests(), requests);
    }

    fn then_the_router_was_asked_something_starting_with(&self, start: &str) {
        let requests = self.router().requests();
        assert_eq!(requests.len(), 1, "{requests:?}");
        assert!(requests[0].starts_with(start), "{requests:?}");
    }

    fn then_the_removal_carried_the_nonce_of_the_mapping(&self) {
        let requests = self.router().requests();
        let (map, delete) = (&requests[0], &requests[1]);
        assert!(delete.starts_with("delete TCP 4662 "), "{delete}");
        assert_eq!(delete.rsplit(' ').next(), map.rsplit(' ').next());
    }

    fn then_the_router_was_asked_in_turn_for(&self, starts: &[&str]) {
        let asked = self.router().requests();
        assert_eq!(asked.len(), starts.len(), "{asked:?}");
        for (request, start) in asked.iter().zip(starts) {
            assert!(request.starts_with(start), "{request} starts with {start}");
        }
    }

    fn then_each_request_came_from(&self, client: &str) {
        let clients = self.router().clients();
        assert!(!clients.is_empty() && clients.iter().all(|c| c == client), "{clients:?}");
    }

    fn then_every_request_carried_the_same_nonce(&self) {
        let mut nonces: Vec<String> =
            self.router().requests().iter().map(|r| r.rsplit(' ').next().unwrap().to_owned()).collect();
        nonces.dedup();
        assert_eq!(nonces.len(), 1);
    }

    fn then_no_pinhole_is_opened(&self) {
        assert_eq!(self.pinhole, None);
        assert!(self.router().requests().is_empty());
    }

    fn then_the_mapping_is_refused(&self, reason: &str) {
        let refused = matches!(&self.failure, Some(GatewayError::Refused(told)) if told.contains(reason));
        assert!(refused, "{reason} in {:?}", self.failure);
    }
}
