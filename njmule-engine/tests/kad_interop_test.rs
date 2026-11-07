// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadInteropTest, under its names: talks Kad with an eMule on this computer, bootstrap, hello,
//! lookup and ping, which change nothing in it, and a keyword stored in its index and searched there. Run on demand
//! with `cargo test --test kad_interop_test -- --ignored`; the eMule's UDP port comes from `NJMULE_INTEROP_KAD_PORT`
//! (default 14897), and the tests stop early when no eMule answers there.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::time::{Duration, Instant};

use njmule_engine::kad::keywords;
use njmule_engine::kad::lookup;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::crypt::datagram_obfuscation;
use njmule_engine::protocol::kad::kad_messages::{
    self, Answer, BootstrapAnswer, Entry, Hello, KeywordPublish, KeywordSearch, PublishAnswer, Request, SearchAnswer,
    Sender,
};
use njmule_engine::protocol::packet::opcodes::kad;
use njmule_engine::protocol::packet::{Packet, packing, protocol, udp_framing};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag, tags};
use rand::RngExt;

const WAIT: Duration = Duration::from_secs(5);
const SIZE: u64 = 23_456_789;

struct Interop {
    own: UInt128,
    socket: UdpSocket,
    port: u16,
    emule: UInt128,
    pong_port: u16,
    keyword: String,
    file: UInt128,
    found: Option<SearchAnswer>,
}

impl Interop {
    fn new() -> Self {
        let port = std::env::var("NJMULE_INTEROP_KAD_PORT").ok().and_then(|port| port.parse().ok()).unwrap_or(14897);
        Self {
            own: UInt128::random(&mut rand::rng()),
            socket: UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).unwrap(),
            port,
            emule: UInt128(0),
            pong_port: 0,
            keyword: String::new(),
            file: UInt128(0),
            found: None,
        }
    }

    fn given_an_emule_that_answers_the_bootstrap(&mut self) -> bool {
        let Some(bootstrap) = self.ask(&kad_messages::bootstrap_request(), kad::BOOTSTRAP_RES) else {
            eprintln!("No eMule Kad answering on UDP port {}: skipped", self.port);
            return false;
        };
        let answer = BootstrapAnswer::decode(&bootstrap).unwrap();
        assert!(answer.sender.version >= 8);
        assert!(answer.contacts.len() <= kad_messages::MAX_BOOTSTRAP_CONTACTS);
        for contact in &answer.contacts {
            assert_ne!(contact.ip >> 24, 127, "no loopback contacts");
        }
        self.emule = answer.sender.id;
        true
    }

    fn given_the_emule_answers_our_hello(&self) {
        let hello =
            Hello { sender: Sender { id: self.own, tcp_port: 4662, version: kad_messages::VERSION }, tags: Vec::new() };
        let reply = Hello::decode(&self.ask(&hello.encode(false).unwrap(), kad::HELLO_RES).unwrap()).unwrap();
        assert_eq!(reply.sender.id, self.emule);
        assert_eq!(reply.sender.version, kad_messages::VERSION);
    }

    fn given_the_emule_answers_a_lookup_for_our_id(&self) {
        let request = Request { count: 11, target: self.own, receiver: self.emule };
        let contacts = Answer::decode(&self.ask(&request.encode().unwrap(), kad::RES).unwrap()).unwrap();
        assert_eq!(contacts.target, self.own);
        assert!(contacts.contacts.len() <= 11);
    }

    // A node stores only keywords close to its own id.
    fn given_a_keyword_within_the_tolerance_of_the_emule(&mut self) {
        let prefix = format!("njmuleinterop{:x}", rand::rng().random::<u64>());
        self.keyword = (0..)
            .map(|n| format!("{prefix}{n}"))
            .find(|word| (keywords::id(word) ^ self.emule).top32() <= lookup::TOLERANCE)
            .unwrap();
    }

    fn given_the_emule_stores_a_file_under_the_keyword(&mut self) {
        self.file = UInt128::random(&mut rand::rng());
        let entry = Entry {
            id: self.file,
            tags: vec![
                Tag::String { name: TagName::id(file_tag::FILENAME), value: format!("{}.bin", self.keyword) },
                Tag::Int { name: TagName::id(file_tag::FILESIZE), value: SIZE },
            ],
        };
        let publish = KeywordPublish { keyword: keywords::id(&self.keyword), files: vec![entry] };
        let stored = self.ask(&publish.encode().unwrap(), kad::PUBLISH_RES);
        let stored = stored.expect("publish answer (none while the eMule's Kad UDP is firewalled)");
        assert_eq!(PublishAnswer::decode(&stored).unwrap().target, keywords::id(&self.keyword));
    }

    fn when_the_emule_is_pinged(&mut self) {
        self.pong_port = kad_messages::decode_pong(&self.ask(&kad_messages::ping(), kad::PONG).unwrap()).unwrap();
    }

    fn when_the_keyword_is_searched_in_the_emule(&mut self) {
        let search = KeywordSearch { target: keywords::id(&self.keyword), start: 0, expression: Vec::new() };
        self.found = Some(SearchAnswer::decode(&self.ask(&search.encode(), kad::SEARCH_RES).unwrap()).unwrap());
    }

    fn then_the_pong_gives_our_port(&self) {
        assert_eq!(self.pong_port, self.socket.local_addr().unwrap().port());
    }

    fn then_the_file_is_found(&self) {
        let found = self.found.as_ref().unwrap();
        assert_eq!(found.sender, self.emule);
        assert_eq!(found.target, keywords::id(&self.keyword));
        assert_eq!(found.results.len(), 1);
        assert_eq!(found.results[0].id, self.file);
        let name = format!("{}.bin", self.keyword);
        assert_eq!(tags::string_value(&found.results[0].tags, file_tag::FILENAME), Some(name.as_str()));
        assert_eq!(tags::int_value(&found.results[0].tags, file_tag::FILESIZE), Some(SIZE));
    }

    // Sends a plain datagram and waits for the answer with the given opcode.
    fn ask(&self, request: &Packet, answer: u8) -> Option<Packet> {
        let emule = SocketAddr::from((Ipv4Addr::LOCALHOST, self.port));
        self.socket.send_to(&udp_framing::encode(request), emule).unwrap();
        let deadline = Instant::now() + WAIT;
        let mut buffer = [0u8; 8192];
        while let Some(left) = deadline.checked_duration_since(Instant::now()).filter(|left| !left.is_zero()) {
            self.socket.set_read_timeout(Some(left)).unwrap();
            let Ok((length, from)) = self.socket.recv_from(&mut buffer) else {
                return None;
            };
            let SocketAddr::V4(from) = from else {
                continue;
            };
            // Answers to a hello come obfuscated with our id.
            let own = self.own.to_wire_bytes();
            let opened = datagram_obfuscation::decode_from_client(
                &buffer[..length],
                &[0; 16],
                from.ip().octets(),
                Some(&own),
                0,
            )
            .unwrap();
            let mut packet = opened.packet;
            if packet.protocol() == protocol::KAD_PACKED {
                packet = packing::unpack(packet, 250_000).unwrap();
            }
            if packet.protocol() == protocol::KAD && packet.opcode() == answer {
                return Some(packet);
            }
        }
        None
    }
}

#[test]
#[ignore = "interop: needs an eMule with Kad on this computer"]
fn should_be_answered_by_an_emule_to_bootstrap_hello_lookup_and_ping() {
    let mut interop = Interop::new();
    if !interop.given_an_emule_that_answers_the_bootstrap() {
        return;
    }
    interop.given_the_emule_answers_our_hello();
    interop.given_the_emule_answers_a_lookup_for_our_id();

    interop.when_the_emule_is_pinged();

    interop.then_the_pong_gives_our_port();
}

// Leaves the keyword in the eMule's index until it expires (a day at most).
#[test]
#[ignore = "interop: needs an eMule with Kad on this computer"]
fn should_find_in_an_emule_a_keyword_published_to_it() {
    let mut interop = Interop::new();
    if !interop.given_an_emule_that_answers_the_bootstrap() {
        return;
    }
    interop.given_the_emule_answers_our_hello();
    interop.given_a_keyword_within_the_tolerance_of_the_emule();
    interop.given_the_emule_stores_a_file_under_the_keyword();

    interop.when_the_keyword_is_searched_in_the_emule();

    interop.then_the_file_is_found();
}
