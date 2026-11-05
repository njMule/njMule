// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's KadMessagesTest, under their names: ids by words, addresses in host order,
//! round trips of every message, answers split to their size and counts too large for their fields.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::net::Ipv4Addr;

use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::kad::kad_messages::{
    self, Answer, BootstrapAnswer, Contact, Entry, FirewallCheck, Hello, KeywordPublish, KeywordSearch,
    MAX_LOOKUP_CONTACTS, PublishAnswer, Request, SearchAnswer, Sender, SourcePublish, SourceSearch, VERSION,
};
use njmule_engine::protocol::packet::opcodes::kad;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::{Tag, TagName};

const ID: UInt128 = UInt128(0x0011_2233_4455_6677_8899_AABB_CCDD_EEFF);
const OTHER: UInt128 = UInt128(0xFFEE_DDCC_BBAA_9988_7766_5544_3322_1100);

fn host(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from(Ipv4Addr::new(a, b, c, d))
}

fn contact() -> Contact {
    Contact { id: OTHER, ip: host(1, 2, 3, 4), udp_port: 4672, tcp_port: 4662, version: 10 }
}

fn named(id: UInt128, name: &str) -> Entry {
    Entry { id, tags: vec![Tag::String { name: TagName::id(0x01), value: name.to_owned() }] }
}

fn entry() -> Entry {
    named(OTHER, "a file name.iso")
}

fn empty_entry() -> Entry {
    Entry { id: OTHER, tags: Vec::new() }
}

fn hello() -> Hello {
    Hello {
        sender: Sender { id: ID, tcp_port: 4662, version: VERSION },
        tags: vec![Tag::Int { name: TagName::id(0xFC), value: 4672 }],
    }
}

fn split(results: &[Entry]) -> Vec<Packet> {
    SearchAnswer::split(OTHER, ID, results, 1420).unwrap()
}

fn results_in(answers: &[Packet]) -> usize {
    answers.iter().map(|p| SearchAnswer::decode(p).unwrap().results.len()).sum()
}

#[test]
fn should_encode_ids_by_words_and_addresses_in_host_order() {
    let packet = Answer { target: ID, contacts: vec![contact()] }.encode().unwrap();

    assert_eq!(
        hex::encode(packet.payload()),
        [
            "33221100", "77665544", "bbaa9988", "ffeeddcc", "01", "ccddeeff", "8899aabb", "44556677", "00112233",
            "04030201", "4012", "3612", "0a",
        ]
        .concat()
    );
}

#[test]
fn should_round_trip_an_answer() {
    let sent = Answer { target: ID, contacts: vec![contact()] };

    assert_eq!(Answer::decode(&sent.encode().unwrap()), Ok(sent));
}

#[test]
fn should_round_trip_a_lookup_request_for_one_to_thirty_one_contacts() {
    for count in [1, 11, MAX_LOOKUP_CONTACTS] {
        let sent = Request { count, target: ID, receiver: OTHER };

        assert_eq!(Request::decode(&sent.encode().unwrap()), Ok(sent), "count {count}");
    }
}

#[test]
fn should_refuse_a_lookup_request_for_any_other_number_of_contacts() {
    for count in [0, MAX_LOOKUP_CONTACTS + 1, 0x20, 0xFF] {
        let error = Request { count, target: ID, receiver: OTHER }.encode().unwrap_err();

        assert!(error.to_string().contains(&format!("count {count}")), "{error}");
    }
}

#[test]
fn should_round_trip_a_hello() {
    assert_eq!(Hello::decode(&hello().encode(false).unwrap()), Ok(hello()));
}

#[test]
fn should_give_the_hello_answer_its_own_opcode() {
    assert_eq!(hello().encode(true).unwrap().opcode(), 0x19);
}

#[test]
fn should_round_trip_a_bootstrap_answer() {
    let sent = BootstrapAnswer {
        sender: Sender { id: ID, tcp_port: 4662, version: 10 },
        contacts: vec![Contact { id: OTHER, ip: host(5, 6, 7, 8), udp_port: 1, tcp_port: 2, version: 9 }],
    };

    assert_eq!(BootstrapAnswer::decode(&sent.encode().unwrap()), Ok(sent));
}

#[test]
fn should_round_trip_a_hello_ack() {
    assert_eq!(kad_messages::decode_hello_ack(&kad_messages::hello_ack(&ID)), Ok(ID));
}

#[test]
fn should_round_trip_a_pong() {
    assert_eq!(kad_messages::decode_pong(&kad_messages::pong(4672)), Ok(4672));
}

#[test]
fn should_round_trip_a_firewall_answer() {
    let ip = host(9, 8, 7, 6);

    assert_eq!(kad_messages::decode_firewall_answer(&kad_messages::firewall_answer(ip)), Ok(ip));
}

#[test]
fn should_round_trip_a_firewall_check() {
    let sent = FirewallCheck { tcp_port: 4662, user_hash: OTHER, connect_options: 1 };

    assert_eq!(FirewallCheck::decode(&sent.encode()), Ok(sent));
}

#[test]
fn should_round_trip_a_keyword_search() {
    let sent = KeywordSearch { target: ID, start: 0, expression: vec![1, 3, 0, b'a', b'b', b'c'] };

    assert_eq!(KeywordSearch::decode(&sent.encode()), Ok(sent));
}

#[test]
fn should_flag_the_start_of_a_keyword_search() {
    let packet = KeywordSearch { target: ID, start: 0, expression: vec![1, 3, 0, b'a', b'b', b'c'] }.encode();

    assert!(hex::encode(packet.payload()).contains("0080"));
}

#[test]
fn should_round_trip_a_source_search() {
    let sent = SourceSearch { target: ID, start: 0, size: 5_000_000_000 };

    assert_eq!(SourceSearch::decode(&sent.encode()), Ok(sent));
}

#[test]
fn should_round_trip_a_keyword_publish() {
    let sent = KeywordPublish { keyword: ID, files: vec![entry()] };

    assert_eq!(KeywordPublish::decode(&sent.encode().unwrap()), Ok(sent));
}

#[test]
fn should_round_trip_a_source_publish() {
    let sent = SourcePublish { file: ID, source: OTHER, tags: entry().tags };

    assert_eq!(SourcePublish::decode(&sent.encode().unwrap()), Ok(sent));
}

#[test]
fn should_round_trip_a_publish_answer() {
    let sent = PublishAnswer { target: ID, load: 7 };

    assert_eq!(PublishAnswer::decode(&sent.encode()), Ok(sent));
}

#[test]
fn should_split_many_results_into_answers_that_fit_their_size() {
    let answers = split(&vec![entry(); 100]);

    assert!(answers.len() > 1);
    assert!(answers.iter().all(|p| p.length() + 2 <= 1420));
    assert!(answers.iter().all(|p| SearchAnswer::decode(p).unwrap().target == ID));
    assert_eq!(results_in(&answers), 100);
}

#[test]
fn should_leave_out_an_entry_too_large_for_an_answer() {
    let small = named(OTHER, "a.iso");
    let huge = named(ID, &"x".repeat(2000));

    let answers = split(&[small.clone(), huge, small]);

    assert!(answers.iter().all(|p| p.length() + 2 <= 1420));
    assert_eq!(results_in(&answers), 2);
}

#[test]
fn should_make_no_answer_of_an_entry_too_large_alone() {
    assert!(split(&[named(ID, &"x".repeat(2000))]).is_empty());
}

#[test]
fn should_encode_an_answer_of_255_contacts() {
    assert!(Answer { target: ID, contacts: vec![contact(); 255] }.encode().is_ok());
}

#[test]
fn should_refuse_to_encode_an_answer_of_256_contacts() {
    assert!(Answer { target: ID, contacts: vec![contact(); 256] }.encode().is_err());
}

#[test]
fn should_refuse_to_encode_a_bootstrap_answer_of_65536_contacts() {
    let sent =
        BootstrapAnswer { sender: Sender { id: ID, tcp_port: 4662, version: 10 }, contacts: vec![contact(); 0x10000] };

    assert!(sent.encode().is_err());
}

#[test]
fn should_refuse_to_encode_a_search_answer_of_65536_results() {
    assert!(SearchAnswer { sender: OTHER, target: ID, results: vec![empty_entry(); 0x10000] }.encode().is_err());
}

#[test]
fn should_refuse_to_encode_a_keyword_publish_of_65536_files() {
    assert!(KeywordPublish { keyword: ID, files: vec![empty_entry(); 0x10000] }.encode().is_err());
}

#[test]
fn should_refuse_an_answer_with_more_contacts_than_bytes() {
    let packet =
        Packet::of(protocol::KAD, 0x29, hex::decode(format!("{}02{}", "00".repeat(16), "00".repeat(25))).unwrap());

    assert!(Answer::decode(&packet).is_err());
}

#[test]
fn should_refuse_a_lookup_request_for_no_contacts() {
    assert!(Request::decode(&Packet::of(protocol::KAD, 0x21, vec![0; 33])).is_err());
}

// Every decoder reads garbage or refuses it as malformed: never a panic.
proptest::proptest! {
    #[test]
    fn should_only_fail_with_decode_exception_on_garbage(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=120)) {
        use njmule_engine::protocol::kad::kad_messages as messages;
        let received = Packet::of(protocol::KAD, 0x01, data);
        let _ = messages::Answer::decode(&received);
        let _ = messages::Request::decode(&received);
        let _ = messages::Hello::decode(&received);
        let _ = messages::BootstrapAnswer::decode(&received);
        let _ = messages::SearchAnswer::decode(&received);
        let _ = messages::KeywordPublish::decode(&received);
        let _ = messages::SourcePublish::decode(&received);
        let _ = messages::KeywordSearch::decode(&received);
        let _ = messages::SourceSearch::decode(&received);
        let _ = messages::PublishAnswer::decode(&received);
        let _ = messages::FirewallCheck::decode(&received);
    }
}

#[test]
fn should_write_the_empty_kad_requests() {
    assert_eq!(kad_messages::bootstrap_request(), Packet::empty(protocol::KAD, kad::BOOTSTRAP_REQ));
    assert_eq!(kad_messages::ping(), Packet::empty(protocol::KAD, kad::PING));
}

#[test]
fn should_fit_an_entry_in_an_answer_only_with_room_for_the_answer_around_it() {
    // The answer's sender, target and count take 36 bytes.
    let size = entry().size().unwrap();

    assert!(SearchAnswer::fits(&entry(), 36 + size).unwrap());
    assert!(!SearchAnswer::fits(&entry(), 35 + size).unwrap());
}
