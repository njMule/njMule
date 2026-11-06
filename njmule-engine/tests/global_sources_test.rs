// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's GlobalSourcesTest, under their names, with the clock in the test's hands.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::net::{Ipv4Addr, SocketAddr};
use std::time::{Duration, Instant};

use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::persist::format::server_met::Server;
use njmule_engine::protocol::ed2k::server_datagrams::{self, SourceQuery};
use njmule_engine::protocol::ed2k::server_messages::{FoundSources, Source};
use njmule_engine::protocol::packet::Packet;
use njmule_engine::protocol::tag::server_tag;
use njmule_engine::server::global_sources::{self, GlobalSources, PASS_PAUSE};
use njmule_engine::server::server_list::{self, Address, ServerList};
use njmule_engine::server::server_route::ServerRoute;

const DEAD_RETRIES: u32 = 3;
const HIGH_ID: u32 = 0x5511_7400;
const LOW_ID: u32 = 0x1234;

fn ip(name: &str) -> u32 {
    let last = name.as_bytes()[0];
    u32::from(Ipv4Addr::new(81, 2, 3, last))
}

// A server that takes several files a request, with their sizes.
fn server(name: &str) -> Server {
    Server { ip: ip(name), port: 4661, tags: vec![server_list::int(server_tag::UDPFLAGS, 0x21)] }
}

fn udp(name: &str) -> SocketAddr {
    SocketAddr::from((Ipv4Addr::from(ip(name)), 4665))
}

fn file(n: u8) -> [u8; 16] {
    [n; 16]
}

fn wanted(files: &[[u8; 16]]) -> Vec<SourceQuery> {
    files.iter().map(|file| SourceQuery { file: *file, size: 1000 }).collect()
}

fn sources(file: [u8; 16], ids: &[u32]) -> FoundSources {
    FoundSources {
        file,
        sources: ids
            .iter()
            .map(|&client_id| Source { client_id, port: 4662, crypt_options: 0, user_hash: None })
            .collect(),
    }
}

// The files a request asks for, in order.
fn files_asked(packet: &Packet) -> Vec<[u8; 16]> {
    let size = if packet.opcode() == 0x94 { 20 } else { 16 };
    packet.payload().chunks(size).map(|entry| entry[..16].try_into().unwrap()).collect()
}

struct Asking {
    servers: ServerList,
    connected: Option<Address>,
    wanting: Vec<SourceQuery>,
    global: GlobalSources,
    now: Instant,
}

impl Asking {
    fn new(servers: &[Server]) -> Self {
        let mut list = ServerList::default();
        for server in servers {
            list.add(server.clone());
        }
        let mut global = GlobalSources::default();
        global.start();
        Self { servers: list, connected: None, wanting: Vec::new(), global, now: Instant::now() }
    }

    fn tick(&mut self, allowed: bool) -> Option<(SocketAddr, Packet)> {
        let plain = |server: &Server| ServerRoute::of(server, 0, ObfuscationMode::Disabled);
        let asking = global_sources::Asking {
            connected: self.connected.as_ref(),
            dead_retries: DEAD_RETRIES,
            allowed,
            route: &plain,
        };
        self.global
            .tick(self.now, &self.servers, &self.wanting, &asking)
            .map(|(route, packet)| (route.target, packet))
    }

    fn requests(&mut self, count: usize) -> Vec<(SocketAddr, Packet)> {
        (0..count).filter_map(|_| self.tick(true)).collect()
    }
}

#[test]
fn should_ask_another_server_for_the_wanted_downloads_but_not_the_one_we_are_on() {
    let mut asking = Asking::new(&[server("A"), server("B")]);
    asking.connected = Some(server_list::address_of(&server("A")));
    asking.wanting = wanted(&[file(1)]);

    let sent = asking.requests(2);

    assert_eq!(sent.len(), 1);
    assert_eq!(sent[0].0, udp("B"));
    assert_eq!(files_asked(&sent[0].1), [file(1)]);
}

#[test]
fn should_leave_out_a_server_that_failed_too_often() {
    let dead = server("C").with(server_list::int(server_tag::FAIL, u64::from(DEAD_RETRIES)));
    let mut asking = Asking::new(&[dead, server("B")]);
    asking.wanting = wanted(&[file(1)]);

    let sent = asking.requests(2);

    assert_eq!(sent.iter().map(|(to, _)| *to).collect::<Vec<_>>(), [udp("B")]);
}

#[test]
fn should_ask_nothing_when_every_connection_must_be_obfuscated() {
    let mut asking = Asking::new(&[server("B")]);
    asking.wanting = wanted(&[file(1)]);

    let sent = asking.tick(false);

    assert!(sent.is_none());
}

#[test]
fn should_ask_the_next_server_for_the_files_the_previous_one_was_not_asked_for() {
    let mut asking = Asking::new(&[server("B"), server("D")]);
    asking.wanting = wanted(&(0..40).map(file).collect::<Vec<_>>());

    let sent = asking.requests(3);

    let to_d = sent.iter().find(|(to, _)| *to == udp("D")).unwrap();
    assert_eq!(files_asked(&to_d.1)[0], file(35));
}

#[test]
fn should_take_only_the_high_id_sources_of_the_files_the_server_was_asked_for() {
    let mut asking = Asking::new(&[server("B")]);
    asking.wanting = wanted(&[file(1)]);
    asking.requests(1);
    let answer =
        server_datagrams::found_sources(&[sources(file(1), &[HIGH_ID, LOW_ID]), sources(file(2), &[HIGH_ID])]).unwrap();

    let found = asking.global.received(udp("B"), &answer);

    assert_eq!(found, [sources(file(1), &[HIGH_ID])]);
}

#[test]
fn should_ignore_sources_from_an_address_that_was_not_asked() {
    let mut asking = Asking::new(&[server("B"), server("D")]);
    asking.wanting = wanted(&[file(1)]);
    asking.requests(1);
    let answer = server_datagrams::found_sources(&[sources(file(1), &[HIGH_ID])]).unwrap();

    let found = asking.global.received(udp("D"), &answer);

    assert!(found.is_empty());
}

#[test]
fn should_not_ask_again_before_half_an_hour_after_a_pass() {
    let mut asking = Asking::new(&[server("B")]);
    asking.wanting = wanted(&[file(1)]);
    asking.requests(2);
    asking.now += PASS_PAUSE - Duration::from_secs(60);

    let sent = asking.tick(true);

    assert!(sent.is_none());
}

#[test]
fn should_ask_again_half_an_hour_after_a_pass() {
    let mut asking = Asking::new(&[server("B")]);
    asking.wanting = wanted(&[file(1)]);
    asking.requests(2);
    asking.now += PASS_PAUSE;

    let sent = asking.tick(true);

    assert_eq!(sent.map(|(to, packet)| (to, files_asked(&packet))), Some((udp("B"), vec![file(1)])));
}
