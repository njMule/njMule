// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's PortForwardingTest, under its names: our ports forwarded by a UPnP gateway on the loopback, for a
//! lease renewed at half its time, with the pinholes of its IPv6 firewall, the gateway looked for again while it is
//! silent, and everything removed at the end.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::net::Ipv6Addr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use njmule_engine::upnp::igd;
use njmule_engine::upnp::port_forwarding::{self, Pause, PortForwarding, Routers};
use tokio::sync::{Semaphore, mpsc};
use url::Url;

use crate::fake_gateway::{self, Body, FakeGateway};
use crate::free_port;

const IP: &str = "urn:schemas-upnp-org:service:WANIPConnection:1";
const PPP: &str = "urn:schemas-upnp-org:service:WANPPPConnection:1";
const ADD: &str = "AddPortMapping";
const DELETE: &str = "DeletePortMapping";
const FIREWALL: &str = "urn:schemas-upnp-org:service:WANIPv6FirewallControl:1";
const OURS: Ipv6Addr = Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 0x99);

#[tokio::test]
async fn should_forward_our_ports_for_a_lease() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;

    test.when_a_forwarding_starts(Some(4672)).await;

    test.then_the_mappings_are(&["TCP 4662", "UDP 4672"]);
    test.then_the_only_line_logged_says("TCP 4662 and UDP 4672 forwarded");
    test.then_the_gateway_was_asked_times(2);
    test.then_the_first_mapping_asked_for(&[
        "<NewExternalPort>4662</NewExternalPort>",
        "<NewProtocol>TCP</NewProtocol>",
        "<NewInternalClient>127.0.0.1</NewInternalClient>",
        "<NewPortMappingDescription>njMule</NewPortMappingDescription>",
        &format!("<NewLeaseDuration>{}</NewLeaseDuration>", port_forwarding::LEASE.as_secs()),
    ]);
}

#[tokio::test]
async fn should_open_the_ipv6_firewall_of_the_router_to_our_ports() {
    let mut test = Forwardings::new();
    test.given_a_gateway_with_an_ipv6_firewall_of(IP, "/ctl", "/fw").await;
    test.given_our_ipv6_address(OURS);

    test.when_a_forwarding_starts(Some(4672)).await;

    test.then_the_pinholes_opened_are(2);
    test.then_the_pinhole_opened_is(
        0,
        &[
            "/fw ",
            "<InternalClient>2001:db8::99</InternalClient>",
            "<InternalPort>4662</InternalPort>",
            "<Protocol>6</Protocol>",
            "<LeaseTime>3600</LeaseTime>",
        ],
    )
    .await;
    test.then_the_pinhole_opened_is(1, &["<InternalPort>4672</InternalPort>", "<Protocol>17</Protocol>"]).await;
}

#[tokio::test]
async fn should_keep_the_pinholes_open_with_the_mappings() {
    let mut test = Forwardings::new();
    test.given_a_gateway_with_an_ipv6_firewall_of(IP, "/ctl", "/fw").await;
    test.given_our_ipv6_address(OURS);
    test.given_a_forwarding_started(Some(4672)).await;
    test.given_a_wait_for_the_renewal_of(port_forwarding::LEASE / 2).await;

    test.when_the_wait_for_the_renewal_ends();

    test.then_the_gateway_is_asked("UpdatePinhole", &["<UniqueID>100</UniqueID>", "<UniqueID>101</UniqueID>"])
        .await;
    test.then_the_pinholes_opened_are(2);
}

#[tokio::test]
async fn should_move_the_pinholes_to_our_new_ipv6_address() {
    let mut test = Forwardings::new();
    test.given_a_gateway_with_an_ipv6_firewall_of(IP, "/ctl", "/fw").await;
    test.given_our_ipv6_address(OURS);
    test.given_a_forwarding_started(None).await;
    test.given_a_wait_for_the_renewal_of(port_forwarding::LEASE / 2).await;
    test.given_our_ipv6_address("2001:db8::77".parse().unwrap());

    test.when_the_wait_for_the_renewal_ends();

    test.then_the_gateway_is_asked("DeletePinhole", &["<UniqueID>100</UniqueID>"]).await;
    test.then_the_pinhole_opened_is(1, &["<InternalClient>2001:db8::77</InternalClient>"]).await;
}

#[tokio::test]
async fn should_close_the_pinholes_at_the_end() {
    let mut test = Forwardings::new();
    test.given_a_gateway_with_an_ipv6_firewall_of(IP, "/ctl", "/fw").await;
    test.given_our_ipv6_address(OURS);
    test.given_a_forwarding_started(Some(4672)).await;

    test.when_the_forwarding_is_closed().await;

    test.then_the_gateway_is_asked("DeletePinhole", &["<UniqueID>100</UniqueID>", "<UniqueID>101</UniqueID>"])
        .await;
}

#[tokio::test]
async fn should_open_no_pinhole_when_the_ipv6_firewall_is_off() {
    let mut test = Forwardings::new();
    test.given_a_gateway_with_an_ipv6_firewall_of(IP, "/ctl", "/fw").await;
    test.given_the_ipv6_firewall_is_off();
    test.given_our_ipv6_address(OURS);

    test.when_a_forwarding_starts(Some(4672)).await;

    test.then_the_mappings_are(&["TCP 4662", "UDP 4672"]);
    test.then_the_pinholes_opened_are(0);
}

#[tokio::test]
async fn should_open_no_pinhole_without_an_ipv6_address() {
    let mut test = Forwardings::new();
    test.given_a_gateway_with_an_ipv6_firewall_of(IP, "/ctl", "/fw").await;

    test.when_a_forwarding_starts(Some(4672)).await;

    test.then_the_mappings_are(&["TCP 4662", "UDP 4672"]);
    test.then_the_pinholes_opened_are(0);
}

#[tokio::test]
async fn should_remove_the_mappings_at_the_end() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_a_forwarding_started(Some(4672)).await;

    test.when_the_forwarding_is_closed().await;

    test.then_the_removals_asked_for_are(2);
    test.then_the_removal_asked_for_is(0, "<NewProtocol>TCP</NewProtocol>");
    test.then_the_removal_asked_for_is(1, "<NewProtocol>UDP</NewProtocol>");
}

#[tokio::test]
async fn should_renew_the_mappings_at_half_the_lease() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_a_forwarding_started(Some(4672)).await;
    test.given_a_wait_for_the_renewal_of(port_forwarding::LEASE / 2).await;

    test.when_the_wait_for_the_renewal_ends();

    test.then_the_mappings_asked_for_are(4, "renewed").await;
    test.then_the_mapping_asked_for_is(2, "<NewProtocol>TCP</NewProtocol>");
    test.then_the_next_renewal_is_waited_for().await;
}

#[tokio::test]
async fn should_ask_for_a_permanent_mapping_when_the_router_keeps_no_other() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_gateway_keeps_only_permanent_mappings();

    test.when_a_forwarding_starts(None).await;

    test.then_the_mappings_are(&["TCP 4662"]);
    test.then_the_only_line_logged_says("TCP 4662 forwarded");
    test.then_the_mappings_asked_for_are(2, "asked again without a lease").await;
    test.then_the_mapping_asked_for_is(1, "<NewLeaseDuration>0</NewLeaseDuration>");
}

#[tokio::test]
async fn should_find_the_router_again_when_it_stops_answering() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_a_forwarding_started(None).await;
    test.given_a_wait_for_the_renewal().await;
    test.given_the_router_moved_to_another_address().await;

    test.when_the_wait_for_the_renewal_ends();

    test.then_the_mappings_asked_for_are(2, "mapped again at the router's new address").await;
    test.then_the_mappings_are(&["TCP 4662"]);
}

#[tokio::test]
async fn should_look_for_the_router_again_a_minute_after_none_answered() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_gateway_answers_no_search();

    test.when_a_forwarding_starts(None).await;

    test.then_the_only_line_logged_says("no router answered");
    test.then_the_next_wait_is(port_forwarding::FIRST_RETRY).await;
}

#[tokio::test]
async fn should_forward_the_ports_once_the_router_answers_a_later_search() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_gateway_answers_no_search();
    test.given_a_forwarding_started(None).await;
    test.given_a_wait_for_the_renewal_of(port_forwarding::FIRST_RETRY).await;
    test.given_the_gateway_answers_again();

    test.when_the_wait_for_the_renewal_ends();

    test.then_the_mappings_asked_for_are(1, "once the router answers").await;
    test.then_a_line_is_logged_saying("TCP 4662 forwarded").await;
}

#[tokio::test]
async fn should_forward_the_ports_once_a_router_that_failed_answers_again() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    // Longer than the router is waited for.
    test.given_the_gateway_takes_for_each_mapping(Duration::from_secs(6));
    test.given_a_forwarding_started(None).await;
    test.given_a_wait_for_the_renewal_of(port_forwarding::FIRST_RETRY).await;
    test.given_the_gateway_takes_for_each_mapping(Duration::ZERO);

    test.when_the_wait_for_the_renewal_ends();

    test.then_the_mappings_asked_for_are(2, "asked again once the router answers").await;
    test.then_a_line_is_logged_saying("UPnP failed").await;
    test.then_a_line_is_logged_saying("TCP 4662 forwarded").await;
}

#[tokio::test]
async fn should_wait_twice_as_long_each_time_the_router_stays_silent() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_gateway_answers_no_search();
    test.given_a_forwarding_started(None).await;
    test.given_a_wait_for_the_renewal_of(port_forwarding::FIRST_RETRY).await;

    test.when_the_wait_for_the_renewal_ends();

    test.then_the_next_wait_is(port_forwarding::FIRST_RETRY * 2).await;
    test.then_the_only_line_logged_says("no router answered");
}

#[tokio::test]
async fn should_stop_looking_for_the_router_at_once_when_closed() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_gateway_answers_no_search();
    test.given_a_forwarding_started(None).await;
    test.given_a_wait_for_the_renewal_of(port_forwarding::FIRST_RETRY).await;

    test.when_the_forwarding_is_closed().await;

    test.then_it_closed_within(Duration::from_secs(1));
    test.then_the_gateway_was_asked_nothing();
}

#[tokio::test]
async fn should_remove_the_mapping_at_the_address_the_router_moved_to() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_a_forwarding_started(None).await;
    test.given_a_wait_for_the_renewal().await;
    test.given_the_router_moved_to_another_address().await;
    test.given_the_wait_for_the_renewal_ended();
    test.given_the_mappings_asked_for(2, "mapped again at the router's new address").await;

    test.when_the_forwarding_is_closed().await;

    test.then_the_removals_asked_for_are(1);
}

#[tokio::test]
async fn should_leave_nothing_mapped_after_the_end() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    // Each mapping takes the router longer than the end waits for.
    test.given_the_gateway_takes_for_each_mapping(Duration::from_secs(3));
    test.given_a_forwarding_started_without_waiting(Some(4672));
    test.given_a_mapping_asked_for().await;

    test.when_the_forwarding_is_closed().await;

    test.then_every_mapping_asked_for_is_removed_after(Duration::from_secs(4)).await;
}

#[tokio::test]
async fn should_work_with_a_ppp_gateway_and_report_its_refusals() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(PPP, "ctl").await;
    test.given_the_gateway_refuses_with(606);
    test.given_a_forwarding_started(None).await;

    test.when_the_forwarding_is_closed().await;

    test.then_the_only_line_logged_says("refused TCP 4662 (606 Action not authorized)");
    test.then_the_gateway_was_asked_only_for(ADD);
}

#[tokio::test]
async fn should_ignore_routers_without_the_service_or_elsewhere() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of("urn:schemas-upnp-org:service:Layer3Forwarding:1", "/l3").await;
    test.given_a_forwarding_that_found_no_router().await;
    // A description announced on another host is not fetched.
    test.given_the_description_announced_on("192.168.200.1");

    test.when_a_forwarding_starts(None).await;

    test.then_the_last_line_logged_says("no router answered");
    test.then_the_gateway_was_asked_nothing();
}

#[tokio::test]
async fn should_not_let_a_device_that_cannot_be_read_hide_the_router() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_a_device_that_cannot_be_read_answering_first();

    test.when_a_forwarding_starts(None).await;

    test.then_the_mappings_are(&["TCP 4662"]);
}

#[tokio::test]
async fn should_give_up_a_description_that_stalls() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_description_served(Body::Stalled);

    test.when_a_forwarding_starts_within(None, Duration::from_secs(20)).await;

    test.then_the_only_line_logged_says("no router answered");
    test.then_the_gateway_was_asked_nothing();
}

#[tokio::test]
async fn should_give_up_a_description_that_never_ends() {
    let mut test = Forwardings::new();
    test.given_a_gateway_of(IP, "/ctl").await;
    test.given_the_description_served(Body::Oversized);

    test.when_a_forwarding_starts(None).await;

    test.then_the_last_line_logged_says("no router answered");
    test.then_the_gateway_was_asked_nothing();
}

#[tokio::test]
async fn should_choose_the_wan_connection_in_use() {
    let mut test = Forwardings::new();
    test.given_a_gateway_described_as(&fake_gateway::description(&[(IP, "/ip"), (PPP, "/ppp")])).await;
    test.given_the_status_of("/ip", "Disconnected");

    test.when_a_forwarding_starts(None).await;

    test.then_the_only_mapping_asked_for_goes_to("/ppp ", "WANPPPConnection");
}

#[tokio::test]
async fn should_read_the_location_of_a_search_answer() {
    let mut test = Forwardings::new();
    test.given_the_search_answer(
        "HTTP/1.1 200 OK\r\nCache-Control: max-age=120\r\nLocation: http://10.0.0.1:5000/d.xml\r\n",
    );

    test.when_its_location_is_read();

    test.then_the_location_is("http://10.0.0.1:5000/d.xml");
}

#[tokio::test]
async fn should_find_no_location_in_a_search_answer_without_one() {
    let mut test = Forwardings::new();
    test.given_the_search_answer("HTTP/1.1 200 OK\r\n");

    test.when_its_location_is_read();

    test.then_there_is_no_location();
}

#[tokio::test]
async fn should_find_no_location_in_a_search_answer_with_a_bad_one() {
    let mut test = Forwardings::new();
    test.given_the_search_answer("LOCATION: http://bad host/\r\n");

    test.when_its_location_is_read();

    test.then_there_is_no_location();
}

/// A gateway on the loopback, a forwarding through it whose waits the test lets go one at a time, and what it logged.
struct Forwardings {
    gateway: Option<FakeGateway>,
    forwarding: Option<PortForwarding>,
    log: Arc<Mutex<Vec<String>>>,
    asked: mpsc::UnboundedReceiver<Duration>,
    pause: Pause,
    go: Arc<Semaphore>,
    ours: Arc<Mutex<Option<Ipv6Addr>>>,
    closing: Duration,
    answer: String,
    location: Option<Url>,
}

impl Forwardings {
    fn new() -> Self {
        let (asking, asked) = mpsc::unbounded_channel();
        let go = Arc::new(Semaphore::new(0));
        let waiting = Arc::clone(&go);
        // The waits between renewals, let go of by the test one at a time.
        let pause: Pause = Arc::new(move |wait| {
            let _ = asking.send(wait);
            let go = Arc::clone(&waiting);
            Box::pin(async move { go.acquire().await.unwrap().forget() })
        });
        Self {
            gateway: None,
            forwarding: None,
            log: Arc::default(),
            asked,
            pause,
            go,
            ours: Arc::default(),
            closing: Duration::ZERO,
            answer: String::new(),
            location: None,
        }
    }

    fn gateway(&self) -> &FakeGateway {
        self.gateway.as_ref().unwrap()
    }

    fn forwarding(&self) -> &PortForwarding {
        self.forwarding.as_ref().unwrap()
    }

    async fn given_a_gateway_of(&mut self, kind: &str, control: &str) {
        self.gateway = Some(FakeGateway::start(kind, control).await);
    }

    async fn given_a_gateway_described_as(&mut self, description: &str) {
        self.gateway = Some(FakeGateway::describing(description).await);
    }

    async fn given_a_gateway_with_an_ipv6_firewall_of(&mut self, kind: &str, control: &str, firewall: &str) {
        let description = fake_gateway::description(&[(kind, control), (FIREWALL, firewall)]);
        self.gateway = Some(FakeGateway::describing(&description).await);
    }

    fn given_our_ipv6_address(&self, address: Ipv6Addr) {
        *self.ours.lock().unwrap() = Some(address);
    }

    fn given_the_ipv6_firewall_is_off(&self) {
        self.gateway().ipv6_firewall_off();
    }

    fn given_the_status_of(&self, control: &str, status: &str) {
        self.gateway().status(control, status);
    }

    fn given_the_gateway_answers_no_search(&self) {
        self.gateway().stop_answering();
    }

    fn given_the_gateway_answers_again(&self) {
        self.gateway().answer_again();
    }

    fn given_the_gateway_keeps_only_permanent_mappings(&self) {
        self.gateway().only_permanent_leases();
    }

    fn given_the_gateway_refuses_with(&self, code: u32) {
        self.gateway().refuse_with(code);
    }

    fn given_the_gateway_takes_for_each_mapping(&self, delay: Duration) {
        self.gateway().delay_mappings(delay);
    }

    fn given_the_description_served(&self, how: Body) {
        self.gateway().serve_description(how);
    }

    fn given_the_description_announced_on(&self, host: &str) {
        self.gateway().announce_on(host);
    }

    fn given_a_device_that_cannot_be_read_answering_first(&self) {
        let closed = free_port::free_port();
        self.gateway().others_answer_first(vec![format!("http://127.0.0.1:{closed}/desc.xml")]);
    }

    async fn given_a_forwarding_started(&mut self, udp: Option<u16>) {
        self.when_a_forwarding_starts(udp).await;
    }

    fn given_a_forwarding_started_without_waiting(&mut self, udp: Option<u16>) {
        self.forwarding = Some(self.start(udp));
    }

    async fn given_a_forwarding_that_found_no_router(&mut self) {
        let first = self.start(None);
        first.wait_started(Duration::from_secs(10)).await;
        first.close().await;
        let log = self.log.lock().unwrap().clone();
        assert!(log.len() == 1 && log[0].contains("no router answered"), "{log:?}");
        while self.asked.try_recv().is_ok() {}
    }

    async fn given_a_wait_for_the_renewal_of(&mut self, expected: Duration) {
        let asked = tokio::time::timeout(Duration::from_secs(10), self.asked.recv()).await.unwrap();
        assert_eq!(asked, Some(expected));
    }

    async fn given_a_wait_for_the_renewal(&mut self) {
        let asked = tokio::time::timeout(Duration::from_secs(10), self.asked.recv()).await.unwrap();
        assert!(asked.is_some());
    }

    async fn given_the_router_moved_to_another_address(&self) {
        self.gateway().move_http().await;
    }

    fn given_the_wait_for_the_renewal_ended(&self) {
        self.when_the_wait_for_the_renewal_ends();
    }

    async fn given_the_mappings_asked_for(&self, count: usize, why: &str) {
        self.then_the_mappings_asked_for_are(count, why).await;
    }

    async fn given_a_mapping_asked_for(&self) {
        assert!(until(|| !self.gateway().actions_named(ADD).is_empty()).await);
    }

    fn given_the_search_answer(&mut self, text: &str) {
        text.clone_into(&mut self.answer);
    }

    async fn when_a_forwarding_starts(&mut self, udp: Option<u16>) {
        self.when_a_forwarding_starts_within(udp, Duration::from_secs(10)).await;
    }

    async fn when_a_forwarding_starts_within(&mut self, udp: Option<u16>, wait: Duration) {
        let forwarding = self.start(udp);
        forwarding.wait_started(wait).await;
        self.forwarding = Some(forwarding);
    }

    async fn when_the_forwarding_is_closed(&mut self) {
        let start = Instant::now();
        self.forwarding.take().unwrap().close().await;
        self.closing = start.elapsed();
    }

    fn when_the_wait_for_the_renewal_ends(&self) {
        self.go.add_permits(1);
    }

    fn when_its_location_is_read(&mut self) {
        self.location = igd::location(&self.answer);
    }

    fn then_the_mappings_are(&self, expected: &[&str]) {
        assert_eq!(self.forwarding().mappings(), expected);
    }

    fn then_the_only_line_logged_says(&self, text: &str) {
        let log = self.log.lock().unwrap().clone();
        assert!(log.len() == 1 && log[0].contains(text), "{text} alone in {log:?}");
    }

    fn then_the_last_line_logged_says(&self, text: &str) {
        let log = self.log.lock().unwrap().clone();
        assert!(log.last().is_some_and(|line| line.contains(text)), "{text} last in {log:?}");
    }

    // The line comes off the test's task, just after the mappings.
    async fn then_a_line_is_logged_saying(&self, text: &str) {
        let logged = until(|| self.log.lock().unwrap().iter().any(|line| line.contains(text))).await;
        assert!(logged, "a line saying {text} in {:?}", self.log.lock().unwrap());
    }

    async fn then_the_next_wait_is(&mut self, expected: Duration) {
        self.given_a_wait_for_the_renewal_of(expected).await;
    }

    fn then_it_closed_within(&self, most: Duration) {
        assert!(self.closing < most, "{:?}", self.closing);
    }

    fn then_the_gateway_was_asked_times(&self, count: usize) {
        assert_eq!(self.gateway().actions().len(), count);
    }

    fn then_the_gateway_was_asked_nothing(&self) {
        assert!(self.gateway().actions().is_empty(), "{:?}", self.gateway().actions());
    }

    fn then_the_gateway_was_asked_only_for(&self, action: &str) {
        let actions = self.gateway().actions();
        assert!(actions.len() == 1 && actions[0].name == action, "{actions:?}");
    }

    fn then_the_first_mapping_asked_for(&self, parts: &[&str]) {
        let body = &self.gateway().actions_named(ADD)[0].body;
        for part in parts {
            assert!(body.contains(part), "{part} in {body}");
        }
    }

    async fn then_the_mappings_asked_for_are(&self, count: usize, why: &str) {
        assert!(until(|| self.gateway().actions_named(ADD).len() == count).await, "{why}");
    }

    fn then_the_mapping_asked_for_is(&self, index: usize, part: &str) {
        let body = &self.gateway().actions_named(ADD)[index].body;
        assert!(body.contains(part), "{part} in {body}");
    }

    fn then_the_only_mapping_asked_for_goes_to(&self, control: &str, service: &str) {
        let added = self.gateway().actions_named(ADD);
        assert!(added.len() == 1 && added[0].body.starts_with(control) && added[0].body.contains(service), "{added:?}");
    }

    fn then_the_removals_asked_for_are(&self, count: usize) {
        assert_eq!(self.gateway().actions_named(DELETE).len(), count);
    }

    fn then_the_removal_asked_for_is(&self, index: usize, part: &str) {
        let body = &self.gateway().actions_named(DELETE)[index].body;
        assert!(body.contains(part), "{part} in {body}");
    }

    async fn then_the_next_renewal_is_waited_for(&mut self) {
        let asked = tokio::time::timeout(Duration::from_secs(10), self.asked.recv()).await.unwrap();
        assert!(asked.is_some(), "and again");
    }

    async fn then_every_mapping_asked_for_is_removed_after(&self, wait: Duration) {
        tokio::time::sleep(wait).await;
        let deletes = self.gateway().actions_named(DELETE);
        for add in self.gateway().actions_named(ADD) {
            let port = external_port(&add.body);
            let removed = deletes.iter().any(|d| d.at > add.at && d.body.contains(&format!(">{port}<")));
            assert!(removed, "the mapping of port {port} asked for is removed");
        }
    }

    fn then_the_pinholes_opened_are(&self, count: usize) {
        assert_eq!(self.gateway().actions_named("AddPinhole").len(), count);
    }

    // The pinholes are opened off the test's task: the one asked for may come a moment later.
    async fn then_the_pinhole_opened_is(&self, index: usize, parts: &[&str]) {
        assert!(until(|| self.gateway().actions_named("AddPinhole").len() > index).await, "pinhole {index}");
        let body = &self.gateway().actions_named("AddPinhole")[index].body;
        for part in parts {
            assert!(body.contains(part), "{part} in {body}");
        }
    }

    async fn then_the_gateway_is_asked(&self, action: &str, each: &[&str]) {
        assert!(until(|| self.gateway().actions_named(action).len() >= each.len()).await, "{action}");
        let asked = self.gateway().actions_named(action);
        for (index, part) in each.iter().enumerate() {
            assert!(asked[index].body.contains(part), "{part} in {}", asked[index].body);
        }
    }

    fn then_the_location_is(&self, expected: &str) {
        assert_eq!(self.location.as_ref().map(Url::as_str), Some(expected));
    }

    fn then_there_is_no_location(&self) {
        assert_eq!(self.location, None);
    }

    fn start(&self, udp: Option<u16>) -> PortForwarding {
        let log = Arc::clone(&self.log);
        let ours = Arc::clone(&self.ours);
        PortForwarding::start_with(
            Routers::upnp_only(self.gateway().ssdp()),
            4662,
            udp,
            Arc::new(move |_, _, line| log.lock().unwrap().push(line.to_owned())),
            Arc::clone(&self.pause),
            Arc::new(move || *ours.lock().unwrap()),
        )
    }
}

async fn until(condition: impl Fn() -> bool) -> bool {
    let deadline = Instant::now() + Duration::from_secs(15);
    while Instant::now() < deadline {
        if condition() {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    false
}

// The external port an AddPortMapping asked for.
fn external_port(body: &str) -> String {
    let start = body.find("<NewExternalPort>").expect("an external port") + "<NewExternalPort>".len();
    body[start..].split('<').next().unwrap().to_owned()
}
