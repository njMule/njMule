// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's IgdTest, under its names: a UPnP gateway found over SSDP and described over HTTP, its SOAP
//! actions, and the reasons of its refusals, against a gateway on the loopback.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::Duration;

use njmule_engine::upnp::gateway::{GatewayError, Protocol};
use njmule_engine::upnp::igd::{self, Igd};

use crate::fake_gateway::{self, Body, FakeGateway};
use crate::free_port;

const IP: &str = "urn:schemas-upnp-org:service:WANIPConnection:1";
const PPP: &str = "urn:schemas-upnp-org:service:WANPPPConnection:1";
// A gateway found ends the wait at once.
const WAIT: Duration = Duration::from_secs(5);
// Plenty for an answer on loopback: the search for a gateway that cannot be used lasts this long.
const NONE_WAIT: Duration = Duration::from_secs(1);

#[tokio::test]
async fn should_find_the_control_address_of_a_gateway() {
    let mut test = Gateways::new();
    test.given_a_gateway_of(IP, "/ctl").await;

    test.when_a_gateway_is_sought(WAIT).await;

    test.then_the_control_path_is("/ctl");
}

#[tokio::test]
async fn should_ask_for_a_mapping_in_upper_case_with_its_description_escaped() {
    let mut test = Gateways::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_gateway_found().await;

    test.when_a_port_is_mapped(Protocol::Udp, 4672, "njMule <test> & co", 0).await;

    test.then_no_exception_occurred();
    test.then_the_mapping_asked_for(&[
        "<NewProtocol>UDP</NewProtocol>",
        "<NewPortMappingDescription>njMule &lt;test&gt; &amp; co</NewPortMappingDescription>",
        "<NewLeaseDuration>0</NewLeaseDuration>",
    ]);
}

#[tokio::test]
async fn should_ask_for_the_removal_of_a_mapping_in_upper_case() {
    let mut test = Gateways::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_gateway_found().await;

    test.when_a_port_is_unmapped(Protocol::Tcp, 4662).await;

    test.then_the_removal_asked_for(&["<NewProtocol>TCP</NewProtocol>"]);
}

#[tokio::test]
async fn should_carry_the_reason_of_the_gateway_in_a_refusal() {
    let mut test = Gateways::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_gateway_found().await;
    test.given_the_gateway_refuses_with(718);

    test.when_a_port_is_mapped(Protocol::Tcp, 4662, "njMule", 3600).await;

    test.then_the_mapping_is_refused("718 Action not authorized");
}

// None is up: the first.
#[tokio::test]
async fn should_not_take_a_connection_whose_status_cannot_be_read_for_up() {
    let mut test = Gateways::new();
    test.given_a_gateway_described_as(&fake_gateway::description(&[(IP, "/ip"), (PPP, "/ppp")])).await;
    // The status breaks the answer's XML.
    test.given_the_status_of("/ip", "<broken");
    test.given_the_status_of("/ppp", "Disconnected");

    test.when_a_gateway_is_sought(WAIT).await;

    test.then_the_control_path_is("/ip");
}

#[tokio::test]
async fn should_take_an_empty_base_address_for_the_descriptions_own() {
    let mut test = Gateways::new();
    let description =
        fake_gateway::description(&[(IP, "/ctl")]).replacen("<device>", "<URLBase> </URLBase><device>", 1);
    test.given_a_gateway_described_as(&description).await;

    test.when_a_gateway_is_sought(WAIT).await;

    test.then_the_control_host_is("127.0.0.1");
}

#[tokio::test]
async fn should_find_no_gateway_in_a_description_that_cannot_be_trusted_or_read() {
    for description in [
        "not xml at all".to_owned(),
        fake_gateway::description(&[(IP, "http://192.168.1.1/ctl")]),
        fake_gateway::description(&[(IP, "https://127.0.0.1/ctl")]),
        fake_gateway::description(&[(IP, "/a b")]),
        fake_gateway::description(&[(IP, " ")]),
    ] {
        let mut test = Gateways::new();
        test.given_a_gateway_described_as(&description).await;

        test.when_a_gateway_is_sought(NONE_WAIT).await;

        test.then_no_gateway_is_found(&description);
        test.then_the_gateway_was_asked_nothing();
    }
}

#[tokio::test]
async fn should_find_no_gateway_whose_description_is_missing() {
    let mut test = Gateways::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_description_served(Body::Missing);

    test.when_a_gateway_is_sought(NONE_WAIT).await;

    test.then_no_gateway_is_found("a description not found");
}

// Only an address is believed.
#[tokio::test]
async fn should_find_no_gateway_whose_description_is_announced_by_name() {
    let mut test = Gateways::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_description_served(Body::Whole);
    test.given_the_description_announced_on("localhost");

    test.when_a_gateway_is_sought(NONE_WAIT).await;

    test.then_no_gateway_is_found("only an address is believed");
}

#[tokio::test]
async fn should_find_a_gateway_that_missed_the_first_searches() {
    let mut test = Gateways::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_gateway_misses_the_first_searches();

    test.when_a_gateway_is_sought(WAIT).await;

    test.then_a_gateway_is_found();
}

// Two kinds of gateway are searched for in each round.
#[tokio::test]
async fn should_search_three_times_within_the_wait() {
    let mut test = Gateways::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_gateway_answers_no_search();

    test.when_a_gateway_is_sought(WAIT).await;

    test.then_no_gateway_is_found("a gateway that answers nothing");
    test.then_the_searches_received_are(6);
}

#[tokio::test]
async fn should_search_a_multicast_address_on_every_network_without_failing() {
    let mut test = Gateways::new();
    test.given_a_multicast_address_no_router_listens_at();

    test.when_a_gateway_is_sought_there(NONE_WAIT).await;

    test.then_no_gateway_is_found("no router listens at that group and port");
}

#[tokio::test]
async fn should_ask_a_device_announced_twice_once() {
    let mut test = Gateways::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_a_device_that_cannot_be_read_answering_first(2);

    test.when_a_gateway_is_sought(WAIT).await;

    test.then_a_gateway_is_found();
}

#[tokio::test]
async fn should_read_a_fault_for_its_code_and_description() {
    let mut test = Gateways::new();
    test.given_a_fault("606", &format!("{}\t{}", "x".repeat(100), "x".repeat(100)));

    test.when_the_reason_is_read_with_the_status(500);

    test.then_the_reason_starts_with("606 xxx");
    test.then_the_reason_has_length(120);
}

// A blank code.
#[tokio::test]
async fn should_tell_the_status_for_a_fault_with_a_blank_code() {
    let mut test = Gateways::new();
    test.given_a_fault(" ", "ignored");

    test.when_the_reason_is_read_with_the_status(500);

    test.then_the_reason_is("HTTP 500");
}

#[tokio::test]
async fn should_tell_the_status_for_an_answer_without_a_fault() {
    let mut test = Gateways::new();
    test.given_the_answer("<s:Envelope/>");

    test.when_the_reason_is_read_with_the_status(500);

    test.then_the_reason_is("HTTP 500");
}

#[tokio::test]
async fn should_tell_the_status_for_an_answer_that_is_not_xml() {
    let mut test = Gateways::new();
    test.given_the_answer("not xml");

    test.when_the_reason_is_read_with_the_status(503);

    test.then_the_reason_is("HTTP 503");
}

// Too large to read.
#[tokio::test]
async fn should_tell_the_status_for_an_answer_too_large_to_read() {
    let mut test = Gateways::new();
    test.given_an_answer_of_bytes(256 * 1024 + 1);

    test.when_the_reason_is_read_with_the_status(500);

    test.then_the_reason_is("HTTP 500");
}

/// A gateway on the loopback, or a multicast group nobody listens at, what was found and what it answered.
struct Gateways {
    gateway: Option<FakeGateway>,
    multicast: Option<SocketAddr>,
    found: Option<Igd>,
    failure: Option<GatewayError>,
    answer: Vec<u8>,
    reason: String,
}

impl Gateways {
    fn new() -> Self {
        Self { gateway: None, multicast: None, found: None, failure: None, answer: Vec::new(), reason: String::new() }
    }

    fn gateway(&self) -> &FakeGateway {
        self.gateway.as_ref().unwrap()
    }

    fn found(&self) -> &Igd {
        self.found.as_ref().expect("a gateway found")
    }

    async fn given_a_gateway_of(&mut self, kind: &str, control: &str) {
        self.gateway = Some(FakeGateway::start(kind, control).await);
    }

    async fn given_a_gateway_described_as(&mut self, description: &str) {
        self.gateway = Some(FakeGateway::describing(description).await);
    }

    fn given_the_status_of(&self, control: &str, status: &str) {
        self.gateway().status(control, status);
    }

    async fn given_the_gateway_found(&mut self) {
        self.when_a_gateway_is_sought(WAIT).await;
        assert!(self.found.is_some(), "a gateway found");
    }

    fn given_the_gateway_refuses_with(&self, code: u32) {
        self.gateway().refuse_with(code);
    }

    fn given_the_description_served(&self, how: Body) {
        self.gateway().serve_description(how);
    }

    fn given_the_description_announced_on(&self, host: &str) {
        self.gateway().announce_on(host);
    }

    // Never SSDP's own group and port: the real router must not answer a test.
    fn given_a_multicast_address_no_router_listens_at(&mut self) {
        self.multicast = Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(239, 255, 255, 251)), free_port::free_port()));
    }

    fn given_the_gateway_misses_the_first_searches(&self) {
        self.gateway().miss_the_first_searches();
    }

    fn given_the_gateway_answers_no_search(&self) {
        self.gateway().stop_answering();
    }

    fn given_a_device_that_cannot_be_read_answering_first(&self, times: usize) {
        let closed = free_port::free_port();
        self.gateway().others_answer_first(vec![format!("http://127.0.0.1:{closed}/desc.xml"); times]);
    }

    fn given_a_fault(&mut self, code: &str, description: &str) {
        self.answer = fault(code, description).into_bytes();
    }

    fn given_the_answer(&mut self, text: &str) {
        self.answer = text.as_bytes().to_vec();
    }

    fn given_an_answer_of_bytes(&mut self, size: usize) {
        self.answer = vec![0u8; size];
    }

    async fn when_a_gateway_is_sought(&mut self, wait: Duration) {
        self.found = Igd::discover(self.gateway().ssdp(), wait).await.unwrap();
    }

    async fn when_a_gateway_is_sought_there(&mut self, wait: Duration) {
        self.found = Igd::discover(self.multicast.unwrap(), wait).await.unwrap();
    }

    async fn when_a_port_is_mapped(&mut self, protocol: Protocol, port: u16, description: &str, lease: u32) {
        if let Err(e) = self.found().add_mapping(protocol, port, description, lease).await {
            self.failure = Some(e);
        }
    }

    async fn when_a_port_is_unmapped(&self, protocol: Protocol, port: u16) {
        self.found().delete_mapping(protocol, port).await.unwrap();
    }

    fn when_the_reason_is_read_with_the_status(&mut self, status: u16) {
        self.reason = igd::reason(status, &self.answer);
    }

    fn then_the_control_path_is(&self, expected: &str) {
        assert_eq!(self.found().control_url().path(), expected);
    }

    fn then_the_control_host_is(&self, expected: &str) {
        assert_eq!(self.found().control_url().host_str(), Some(expected));
    }

    fn then_the_mapping_asked_for(&self, parts: &[&str]) {
        let body = &self.gateway().actions_named("AddPortMapping")[0].body;
        for part in parts {
            assert!(body.contains(part), "{part} in {body}");
        }
    }

    fn then_the_removal_asked_for(&self, parts: &[&str]) {
        let body = &self.gateway().actions_named("DeletePortMapping")[0].body;
        for part in parts {
            assert!(body.contains(part), "{part} in {body}");
        }
    }

    fn then_the_gateway_was_asked_nothing(&self) {
        assert!(self.gateway().actions().is_empty());
    }

    fn then_a_gateway_is_found(&self) {
        assert!(self.found.is_some());
    }

    fn then_no_gateway_is_found(&self, why: &str) {
        assert!(self.found.is_none(), "{why}");
    }

    fn then_the_searches_received_are(&self, count: usize) {
        assert_eq!(self.gateway().searches(), count);
    }

    fn then_no_exception_occurred(&self) {
        assert!(self.failure.is_none(), "{:?}", self.failure);
    }

    fn then_the_mapping_is_refused(&self, reason: &str) {
        let refused = matches!(&self.failure, Some(GatewayError::Refused(told)) if told == reason);
        assert!(refused, "{reason} in {:?}", self.failure);
    }

    fn then_the_reason_is(&self, expected: &str) {
        assert_eq!(self.reason, expected);
    }

    fn then_the_reason_starts_with(&self, prefix: &str) {
        assert!(self.reason.starts_with(prefix), "{}", self.reason);
    }

    fn then_the_reason_has_length(&self, length: usize) {
        assert_eq!(self.reason.chars().count(), length);
    }
}

fn fault(code: &str, description: &str) -> String {
    format!(
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><detail>\
         <UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>{code}</errorCode>\
         <errorDescription>{description}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
    )
}
