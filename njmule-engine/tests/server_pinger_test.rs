// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's ServerPingerTest, under their names. The pinger here hands what to send to the
//! engine, which owns the socket and the timer.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::panic)]

use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::time::{SystemTime, UNIX_EPOCH};

use njmule_engine::persist::format::server_met::Server;
use njmule_engine::protocol::ed2k::server_datagrams::{self, Description, Status};
use njmule_engine::protocol::packet::opcodes::server_udp;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::{Tag, server_tag};
use njmule_engine::server::server_list::{self, ServerList};
use njmule_engine::server::server_pinger::{self, Effect, ServerPinger};

const DEAD_RETRIES: u32 = 5;
const OUR_IP: u32 = 0x0102_0304;

fn ip(text: &str) -> u32 {
    u32::from(text.parse::<Ipv4Addr>().unwrap())
}

fn server(at: &str, port: u16) -> Server {
    Server { ip: ip(at), port, tags: Vec::new() }
}

fn tag(id: u8, value: u64) -> Tag {
    server_list::int(id, value)
}

fn from(at: &str, port: u16) -> SocketAddr {
    SocketAddr::from((at.parse::<Ipv4Addr>().unwrap(), port))
}

fn status(challenge: u32) -> Status {
    Status {
        challenge,
        users: 1,
        files: 2,
        max_users: 3,
        soft_files: 4,
        hard_files: 5,
        udp_flags: 0,
        low_id_users: 0,
        obfuscated_udp_port: 0,
        obfuscated_tcp_port: 0,
        udp_key: 0,
    }
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs()
}

struct Pinging {
    pinger: ServerPinger,
    servers: ServerList,
    random: rand::rngs::ThreadRng,
}

impl Pinging {
    fn of(servers: impl IntoIterator<Item = Server>) -> Self {
        let mut list = ServerList::default();
        for server in servers {
            list.add(server);
        }
        Self { pinger: ServerPinger::default(), servers: list, random: rand::rng() }
    }

    fn ping(&mut self) -> Vec<Effect> {
        self.pinger.ping_next(&mut self.servers, DEAD_RETRIES, &mut self.random)
    }

    fn arrives(&mut self, from: SocketAddr, packet: &Packet) -> Vec<Effect> {
        self.pinger.received(from, packet, &mut self.servers, OUR_IP, &mut self.random)
    }

    // The challenge of the status request the last ping sent.
    fn challenge(effects: &[Effect]) -> u32 {
        match effects {
            [Effect::Send(_, packet)] => u32::from_le_bytes(packet.payload()[..4].try_into().unwrap()),
            other => panic!("no status request: {other:?}"),
        }
    }
}

fn updated(effects: &[Effect]) -> Vec<&Server> {
    effects
        .iter()
        .filter_map(|effect| match effect {
            Effect::Updated(server) => Some(server),
            _ => None,
        })
        .collect()
}

#[test]
fn should_skip_a_server_whose_udp_port_would_pass_the_last_port() {
    let mut pinging = Pinging::of([server("192.0.2.1", 65_533)]);

    assert!(pinging.ping().is_empty());
}

#[test]
fn should_not_ping_a_server_known_by_host_name_only() {
    let named = Server { ip: 0, port: 4661, tags: vec![server_list::string(server_tag::DYNIP, "server.example")] };
    let mut pinging = Pinging::of([named]);

    let effects = pinging.ping();

    assert!(effects.is_empty());
    assert_eq!(pinging.servers.all()[0].last_ping(), None);
}

#[test]
fn should_ignore_answers_from_ipv6_addresses() {
    let mut pinging = Pinging::of([server("192.0.2.1", 4661)]);
    assert!(pinging.arrives(SocketAddr::from((Ipv6Addr::LOCALHOST, 4665)), &status(1).encode()).is_empty());

    let effects = pinging.arrives(from("127.0.0.1", 4665), &status(1).encode());

    assert!(updated(&effects).is_empty());
}

#[test]
fn should_take_the_default_obfuscation_ports_and_bind_the_key_to_our_address() {
    let given = Status {
        users: 10,
        udp_flags: server_datagrams::UDP_OBFUSCATION | server_datagrams::UDP_TCP_OBFUSCATION,
        low_id_users: 5,
        udp_key: 0x1234_5678,
        ..status(1)
    };

    let applied = server_pinger::apply(&server("192.0.2.1", 4661), &given, OUR_IP);

    assert_eq!(applied.obfuscated_tcp_port(), Some(4661));
    assert_eq!(applied.obfuscated_udp_port(), Some(4661 + 12));
    assert_eq!(applied.users(), Some(10));
    assert!(server_list::supports_obfuscation(&applied));
    assert_eq!(applied.udp_key_ip(), Some(u64::from(njmule_engine::util::ipv4::to_ed2k(OUR_IP))));
}

#[test]
fn should_keep_the_announced_obfuscation_ports_and_take_no_key_as_no_key() {
    let given = Status {
        users: 10,
        udp_flags: server_datagrams::UDP_OBFUSCATION | server_datagrams::UDP_TCP_OBFUSCATION,
        low_id_users: 5,
        obfuscated_udp_port: 7000,
        obfuscated_tcp_port: 7001,
        ..status(1)
    };

    let applied = server_pinger::apply(&server("192.0.2.1", 4661), &given, OUR_IP);

    assert_eq!(applied.obfuscated_tcp_port(), Some(7001));
    assert_eq!(applied.obfuscated_udp_port(), Some(7000));
    assert_eq!(applied.udp_key(), None);
}

#[test]
fn should_report_a_server_that_failed_too_often_dead_unless_it_is_static() {
    let mut pinging = Pinging::of([
        server("192.0.2.1", 4661).with(tag(server_tag::FAIL, 5)),
        server("192.0.2.2", 4661).with(tag(server_tag::FAIL, 5)),
    ]);
    pinging.servers.set_static(&server_list::Address::new(ip("192.0.2.2"), 4661), true);
    let first = pinging.ping();

    let second = pinging.ping();

    assert!(matches!(first.as_slice(), [Effect::Dead(dead)] if dead.ip == ip("192.0.2.1")));
    // A static server is asked instead.
    assert!(matches!(second.as_slice(), [Effect::Send(to, _)] if *to == from("192.0.2.2", 4665)));
}

fn failed_often_and_last_pinged(at: u64) -> Pinging {
    Pinging::of([server("192.0.2.1", 4661).with(tag(server_tag::LASTPING, at)).with(tag(server_tag::FAIL, 9))])
}

#[test]
fn should_leave_a_server_pinged_recently_alone() {
    let mut pinging = failed_often_and_last_pinged(now());

    // Not even checked.
    assert!(pinging.ping().is_empty());
}

#[test]
fn should_check_again_a_server_last_pinged_in_the_future() {
    let mut pinging = failed_often_and_last_pinged(now() + 30 * 24 * 3600);

    assert!(matches!(pinging.ping().as_slice(), [Effect::Dead(_)]));
}

#[test]
fn should_change_nothing_on_a_malformed_status() {
    let mut pinging = Pinging::of([server("192.0.2.1", 4661)]);

    let effects =
        pinging.arrives(from("192.0.2.1", 4665), &Packet::of(protocol::EDONKEY, server_udp::GLOBSERVSTATRES, [0; 2]));

    assert!(effects.is_empty());
}

#[test]
fn should_change_nothing_on_a_status_that_was_not_asked() {
    let mut pinging = Pinging::of([server("192.0.2.1", 4661)]);

    let effects = pinging.arrives(from("192.0.2.1", 4665), &status(0x55AA_0001).encode());

    assert!(effects.is_empty());
}

#[test]
fn should_take_the_status_the_pinged_server_sends_from_its_udp_port_then_ask_for_its_description() {
    let mut pinging = Pinging::of([server("192.0.2.1", 4661)]);
    let challenge = Pinging::challenge(&pinging.ping());

    let effects = pinging.arrives(from("192.0.2.1", 4665), &status(challenge).encode());

    assert_eq!(updated(&effects)[0].users(), Some(1));
    assert_eq!(updated(&effects)[0].failed_count(), Some(0));
    assert!(matches!(effects.last(), Some(Effect::Send(_, packet)) if packet.opcode() == server_udp::SERVER_DESC_REQ));
}

#[test]
fn should_change_nothing_on_the_challenge_of_a_ping_coming_from_another_address() {
    let mut pinging = Pinging::of([server("192.0.2.1", 4661)]);
    let challenge = Pinging::challenge(&pinging.ping());

    let effects = pinging.arrives(from("192.0.2.9", 4665), &status(challenge).encode());

    assert!(effects.is_empty());
}

#[test]
fn should_change_nothing_on_the_challenge_of_a_ping_coming_from_another_port() {
    let mut pinging = Pinging::of([server("192.0.2.1", 4661)]);
    let challenge = Pinging::challenge(&pinging.ping());

    let effects = pinging.arrives(from("192.0.2.1", 4666), &status(challenge).encode());

    assert!(effects.is_empty());
}

#[test]
fn should_change_nothing_on_a_description_that_was_not_asked() {
    let mut pinging = Pinging::of([server("192.0.2.1", 4661)]);
    let spoofed = Description {
        challenge: server_datagrams::description_challenge(1),
        tags: vec![server_list::string(server_tag::SERVERNAME, "Spoofed")],
    };

    let effects = pinging.arrives(from("192.0.2.1", 4665), &spoofed.encode().unwrap());

    assert!(effects.is_empty());
}

#[test]
fn should_take_the_name_description_and_version_of_a_description_without_control_characters() {
    let tags = [
        server_list::string(server_tag::SERVERNAME, "Evil\r\nServer"),
        server_list::string(server_tag::DESCRIPTION, "\u{1b}[31mred"),
        server_list::string(server_tag::VERSION, "17.15\u{7}"),
    ];

    let described = server_pinger::describe(&server("192.0.2.1", 4661), &tags);

    assert_eq!(described.name(), Some("EvilServer"));
    assert_eq!(described.description(), Some("[31mred"));
    assert_eq!(described.version().as_deref(), Some("17.15"));
}

#[test]
fn should_write_a_numeric_version_as_major_dot_minor() {
    let described = server_pinger::describe(&server("192.0.2.1", 4661), &[tag(server_tag::VERSION, 17 << 16 | 9)]);

    assert_eq!(described.version().as_deref(), Some("17.09"));
}

#[test]
fn should_keep_the_known_name_when_a_description_gives_a_blank_one() {
    let known = server("192.0.2.1", 4661).with(server_list::string(server_tag::SERVERNAME, "Known"));

    let described = server_pinger::describe(&known, &[server_list::string(server_tag::SERVERNAME, " \t ")]);

    assert_eq!(described.name(), Some("Known"));
}

#[test]
fn should_take_the_description_of_a_server_that_answered_its_status() {
    let mut pinging = Pinging::of([server("192.0.2.1", 4661)]);
    let challenge = Pinging::challenge(&pinging.ping());
    let effects = pinging.arrives(from("192.0.2.1", 4665), &status(challenge).encode());
    let Some(Effect::Send(_, request)) = effects.last() else {
        panic!("no description request: {effects:?}");
    };
    let asked = u32::from_le_bytes(request.payload()[..4].try_into().unwrap());
    let answer = Description { challenge: asked, tags: vec![server_list::string(server_tag::SERVERNAME, "Other")] };

    let described = pinging.arrives(from("192.0.2.1", 4665), &answer.encode().unwrap());

    assert_eq!(updated(&described)[0].name(), Some("Other"));
}
