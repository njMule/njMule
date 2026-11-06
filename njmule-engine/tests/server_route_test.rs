// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ServerRouteTest, under its names: how a server is asked over UDP, obfuscated or plain.

#![allow(clippy::unwrap_used)]

use std::net::{Ipv4Addr, SocketAddr};

use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::persist::format::server_met::Server;
use njmule_engine::protocol::ed2k::server_datagrams;
use njmule_engine::protocol::tag::{Tag, TagName, server_tag};
use njmule_engine::server::server_pinger::PORT_OFFSET;
use njmule_engine::server::server_route::ServerRoute;
use njmule_engine::util::ipv4;

const SERVER_IP: u32 = ipv4::of(81, 2, 3, 4);
const OUR_IP: u32 = ipv4::of(93, 40, 1, 2);
const KEY: u32 = 0x5EC2_E701;

fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

// A server taking obfuscated datagrams on `port`, which gave its key for our address `for_ip`.
fn obfuscating_server(port: u64, for_ip: u32) -> Server {
    Server {
        ip: SERVER_IP,
        port: 4661,
        tags: vec![
            int(server_tag::UDPFLAGS, u64::from(server_datagrams::UDP_OBFUSCATION)),
            int(server_tag::UDPPORTOBFUSCATION, port),
            int(server_tag::UDPKEY, u64::from(KEY)),
            int(server_tag::UDPKEYIP, u64::from(ipv4::to_ed2k(for_ip))),
        ],
    }
}

fn plain(route: Option<ServerRoute>) {
    let route = route.unwrap();
    assert!(!route.is_obfuscated());
    assert_eq!(route.target.port(), 4661 + PORT_OFFSET);
}

#[test]
fn should_ask_a_server_obfuscated_on_its_own_port_when_it_gave_us_a_key() {
    let route = ServerRoute::of(&obfuscating_server(4665, OUR_IP), OUR_IP, ObfuscationMode::Requested);

    assert_eq!(route, Some(ServerRoute { target: SocketAddr::from((Ipv4Addr::new(81, 2, 3, 4), 4665)), key: KEY }));
}

#[test]
fn should_not_use_a_key_given_for_another_address_of_ours() {
    let route = ServerRoute::of(&obfuscating_server(4665, ipv4::of(93, 40, 9, 9)), OUR_IP, ObfuscationMode::Requested);

    plain(route);
}

#[test]
fn should_ask_a_server_plain_with_obfuscation_off() {
    let route = ServerRoute::of(&obfuscating_server(4665, OUR_IP), OUR_IP, ObfuscationMode::Disabled);

    plain(route);
}

#[test]
fn should_not_ask_a_server_without_obfuscation_when_it_is_required() {
    let route =
        ServerRoute::of(&Server { ip: SERVER_IP, port: 4661, tags: Vec::new() }, OUR_IP, ObfuscationMode::Required);

    assert_eq!(route, None);
}
