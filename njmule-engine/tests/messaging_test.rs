// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's MessagingTest, under its names: the conversations with other clients. The test plays the
//! network: it connects where a client listens, fails where none does, and keeps what is sent on each connection.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::{HashMap, HashSet};
use std::net::{Ipv4Addr, SocketAddr};

use njmule_engine::peer::peer_link::LinkId;
use njmule_engine::protocol::ed2k::chat_messages;
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::packet::Packet;
use njmule_engine::social::chat_filter::ChatFilter;
use njmule_engine::social::messaging::{Action, ChatEvent, Judge, Messaging};

const SPAMMER: [u8; 16] = [0x5A; 16];
const PAL: [u8; 16] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF];
// The connection clients write on, and the one a correspondent opens to us.
const THEIRS: LinkId = 1;
const INCOMING: LinkId = 20;

#[test]
fn should_still_filter_a_spammer_coming_back_from_another_port() {
    let mut test = Chats::new();
    test.given_the_chat_filter(ChatFilter::new(false, &[], true));
    test.given_a_message_from(SPAMMER, 4001, "cheap stuff at http://spam.example");

    test.when_a_message_comes_from(SPAMMER, 4002, "hello there");

    test.then_the_messages_received_are(&[]);
}

#[test]
fn should_let_a_link_from_a_friend_through_while_the_spammer_is_filtered() {
    let mut test = Chats::new();
    test.given_the_chat_filter(ChatFilter::new(false, &[], true));
    test.given_a_message_from(SPAMMER, 4001, "cheap stuff at http://spam.example");
    test.given_a_message_from(SPAMMER, 4002, "hello there");

    test.when_a_message_comes_from(PAL, 4003, "a link from a friend: http://pal.example");

    test.then_the_messages_received_are(&["a link from a friend: http://pal.example"]);
}

#[test]
fn should_start_no_conversation_for_a_blank_text() {
    let mut test = Chats::new();
    test.given_a_correspondent_at(from());

    test.when_we_send(" \t ");

    test.then_there_are_conversations(0);
}

#[test]
fn should_report_and_forget_a_text_to_a_client_that_cannot_be_reached() {
    let mut test = Chats::new();
    test.given_a_correspondent_nobody_listens_for();

    test.when_we_send("anyone?");

    test.then_the_failure_to_reach_it_is_reported();
    test.then_there_are_conversations(0);
}

#[test]
fn should_end_a_conversation_once_its_connection_closes_with_nothing_pending() {
    let mut test = Chats::new();
    test.given_a_message_from(PAL, 4001, "hi");
    test.given_there_are_conversations(1);

    test.when_the_connection_closes();

    test.then_there_are_conversations(0);
    test.then_the_messages_received_are(&["hi"]);
}

#[test]
fn should_keep_a_conversation_that_an_incoming_connection_serves_when_ours_closes() {
    let mut test = Chats::new();
    test.given_a_correspondent_listening();
    test.given_we_sent("first");
    test.given_it_accepted_our_connection_and_read_our_hello();
    test.given_it_connected_to_us_with("hey");
    test.given_our_connection_closed();
    test.given_the_conversation_kept();

    test.when_we_send("second");

    test.then_the_packets_sent_over_its_connection_are(2);
    test.then_the_second_of_them_carries("second");
    test.then_no_failure_is_reported();
}

struct Chats {
    messaging: Messaging,
    filter: ChatFilter,
    to: SocketAddr,
    // Where clients listen, the connections we opened and what went over each.
    listening: HashSet<SocketAddr>,
    ours: HashMap<SocketAddr, LinkId>,
    next_link: LinkId,
    sent: HashMap<LinkId, Vec<Packet>>,
    received: Vec<String>,
    failed: Vec<SocketAddr>,
}

impl Chats {
    fn new() -> Self {
        Self {
            messaging: Messaging::default(),
            filter: ChatFilter::NONE,
            to: from(),
            listening: HashSet::new(),
            ours: HashMap::new(),
            next_link: 10,
            sent: HashMap::new(),
            received: Vec::new(),
            failed: Vec::new(),
        }
    }

    fn given_the_chat_filter(&mut self, filter: ChatFilter) {
        self.filter = filter;
    }

    fn given_a_message_from(&mut self, user_hash: [u8; 16], port: u16, text: &str) {
        self.when_a_message_comes_from(user_hash, port, text);
    }

    fn given_a_correspondent_at(&mut self, address: SocketAddr) {
        self.to = address;
    }

    fn given_a_correspondent_nobody_listens_for(&mut self) {
        self.to = SocketAddr::from((Ipv4Addr::LOCALHOST, 4711));
    }

    fn given_there_are_conversations(&mut self, count: usize) {
        self.then_there_are_conversations(count);
    }

    fn given_a_correspondent_listening(&mut self) {
        self.to = SocketAddr::from((Ipv4Addr::LOCALHOST, 4712));
        self.listening.insert(self.to);
    }

    fn given_we_sent(&mut self, text: &str) {
        self.when_we_send(text);
    }

    // Our connection is up and our hello went over it; the client does not answer it.
    fn given_it_accepted_our_connection_and_read_our_hello(&self) {
        assert!(self.ours.contains_key(&self.to), "our connection");
    }

    // The client connects to us before answering our hello: the conversation goes over its connection.
    fn given_it_connected_to_us_with(&mut self, text: &str) {
        let remote = SocketAddr::from((Ipv4Addr::LOCALHOST, 50_001));
        let peer = hello(PAL, self.to.port());
        let message = chat_messages::message(text, true).unwrap();
        let judge = Judge { filter: &self.filter, friends: &|user_hash| *user_hash == PAL };
        assert!(self.messaging.incoming(remote, &peer, true, INCOMING, &message, &judge));
        self.play();
    }

    fn given_our_connection_closed(&mut self) {
        let link = self.ours[&self.to];
        self.messaging.closed(link);
        self.play();
    }

    fn given_the_conversation_kept(&mut self) {
        self.then_there_are_conversations(1);
    }

    fn when_a_message_comes_from(&mut self, user_hash: [u8; 16], port: u16, text: &str) {
        let peer = hello(user_hash, port);
        let message = chat_messages::message(text, true).unwrap();
        let judge = Judge { filter: &self.filter, friends: &|user_hash| *user_hash == PAL };
        assert!(self.messaging.incoming(from(), &peer, true, THEIRS, &message, &judge));
        self.play();
    }

    fn when_we_send(&mut self, text: &str) {
        self.messaging.send(self.to, text);
        self.play();
    }

    fn when_the_connection_closes(&mut self) {
        self.messaging.closed(THEIRS);
        self.play();
    }

    fn then_the_messages_received_are(&self, texts: &[&str]) {
        assert_eq!(self.received, texts);
    }

    fn then_there_are_conversations(&mut self, count: usize) {
        assert_eq!(self.messaging.conversations(), count);
    }

    fn then_the_failure_to_reach_it_is_reported(&self) {
        assert_eq!(self.failed, [self.to]);
    }

    fn then_the_packets_sent_over_its_connection_are(&self, count: usize) {
        assert_eq!(self.sent.get(&INCOMING).map_or(0, Vec::len), count);
    }

    fn then_the_second_of_them_carries(&self, text: &str) {
        assert_eq!(chat_messages::text(&self.sent[&INCOMING][1]).unwrap(), text);
    }

    fn then_no_failure_is_reported(&self) {
        assert_eq!(self.failed, Vec::<SocketAddr>::new());
    }

    // What the network does: it connects where a client listens and fails elsewhere, and keeps what it sends.
    fn play(&mut self) {
        loop {
            let actions = self.messaging.take_actions();
            if actions.is_empty() {
                break;
            }
            for action in actions {
                match action {
                    Action::Connect(to) if self.listening.contains(&to) => {
                        let link = self.next_link;
                        self.next_link += 1;
                        assert!(self.messaging.connected(to, link));
                        self.ours.insert(to, link);
                    }
                    Action::Connect(to) => self.messaging.unreachable(to, "connection refused"),
                    Action::Send(link, packet) => self.sent.entry(link).or_default().push(packet),
                }
            }
        }
        for event in self.messaging.take_events() {
            match event {
                ChatEvent::Received(_, text) => self.received.push(text),
                ChatEvent::Failed(to, _) => self.failed.push(to),
                ChatEvent::Captcha(..) | ChatEvent::CaptchaResult(..) => {}
            }
        }
    }
}

fn from() -> SocketAddr {
    SocketAddr::from((Ipv4Addr::new(10, 0, 0, 9), 50_000))
}

fn hello(user_hash: [u8; 16], port: u16) -> Hello {
    let ours = Ours { nick: "them", udp_port: 0, kad_port: 0, misc_options1: 0, misc_options2: 0 };
    Hello::of(user_hash, 0, port, &ours, 0, 0)
}
