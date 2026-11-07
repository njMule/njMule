// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's BuddyLinkTest, under its names: the TCP link between Kad buddies, on a loop of its own that makes
//! the connections it asks for as the network does, with peers on the loopback address and a clock the test moves.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::{HashMap, HashSet};
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use njmule_engine::kad::kad_node::{Buddies as _, BuddyState};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::net::routes::Routes;
use njmule_engine::net::tcp_connection::{TcpConnection, read_packet, write_packet};
use njmule_engine::net::tcp_connector::{self, Connection};
use njmule_engine::peer::buddy_link::{BuddyAction, BuddyLink};
use njmule_engine::peer::peer_greetings;
use njmule_engine::peer::peer_link::{self, LinkEvent, LinkHandle, LinkId, Side};
use njmule_engine::protocol::ed2k::client_messages::PartStatus;
use njmule_engine::protocol::ed2k::client_reasks::{self, Relayed, ThroughBuddy};
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::ed2k::low_id_messages::Callback;
use njmule_engine::protocol::ed2k::nat_traversal_messages::Rendezvous;
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, frame_limits, packing, protocol};
use njmule_engine::util::ipv4;
use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot};

const OURS: [u8; 16] = [0xab; 16];
const BUDDY: [u8; 16] = [0xbb; 16];
const FIREWALLED: [u8; 16] = [0xcc; 16];
const LOOPBACK: u32 = u32::from_be_bytes([127, 0, 0, 1]);
const WAIT: Duration = Duration::from_secs(20);

#[tokio::test]
async fn should_take_a_node_that_offered_as_our_buddy() {
    let mut buddies = Buddies::new();
    buddies.given_a_buddy_listening().await;
    buddies.given_it_offered_to_be_our_buddy().await;

    buddies.when_it_answers_our_hello_as(BUDDY, 4675).await;

    buddies.then_the_state_becomes(BuddyState::Connected).await;
    buddies.then_our_buddy_is_at(LOOPBACK, 4675).await;
}

#[tokio::test]
async fn should_ping_our_buddy_every_ten_minutes() {
    let mut buddies = Buddies::new();
    buddies.given_a_buddy_listening().await;
    buddies.given_it_is_our_buddy().await;

    buddies.when_minutes_pass(10).await;

    buddies.then_the_buddy_gets(extended::BUDDYPING).await;
}

#[tokio::test]
async fn should_let_our_buddy_relay_the_callbacks_of_other_clients() {
    let mut buddies = Buddies::new();
    buddies.given_a_buddy_listening().await;
    buddies.given_it_is_our_buddy().await;

    buddies.when_the_buddy_relays_a_callback_from(ip(1, 2, 3, 4), 4711).await;

    buddies.then_a_callback_arrives_from(ip(1, 2, 3, 4), 4711).await;
}

#[tokio::test]
async fn should_not_take_a_client_that_answers_with_another_hash_as_buddy() {
    let mut buddies = Buddies::new();
    buddies.given_a_buddy_listening().await;
    buddies.given_it_offered_to_be_our_buddy().await;

    buddies.when_it_answers_our_hello_as(FIREWALLED, 4675).await;

    buddies.then_the_state_becomes(BuddyState::None).await;
    buddies.then_our_buddy_is_at(0, 0).await;
}

#[tokio::test]
async fn should_take_a_firewalled_node_we_agreed_to_serve() {
    let mut buddies = Buddies::new();
    buddies.given_we_agreed_to_serve(FIREWALLED).await;

    buddies.when_it_connects_as(FIREWALLED).await;

    buddies.then_the_state_becomes(BuddyState::Serving).await;
}

#[tokio::test]
async fn should_relay_to_the_node_we_serve_the_callbacks_for_its_id() {
    let mut buddies = Buddies::new();
    buddies.given_we_agreed_to_serve(FIREWALLED).await;
    buddies.given_it_connected_as(FIREWALLED).await;

    buddies.when_a_callback_is_relayed(buddies.buddy_id).await;

    buddies.then_the_relay_went(true);
    buddies.then_the_firewalled_node_gets_the_callback().await;
}

#[tokio::test]
async fn should_not_relay_a_callback_for_another_id() {
    let mut buddies = Buddies::new();
    buddies.given_we_agreed_to_serve(FIREWALLED).await;
    buddies.given_it_connected_as(FIREWALLED).await;

    buddies.when_a_callback_is_relayed(UInt128::random(&mut rand::rng())).await;

    buddies.then_the_relay_went(false);
}

#[tokio::test]
async fn should_relay_to_the_node_we_serve_the_reasks_for_its_id() {
    let mut buddies = Buddies::new();
    buddies.given_we_agreed_to_serve(FIREWALLED).await;
    buddies.given_it_connected_as(FIREWALLED).await;

    buddies.when_a_reask_through_us_arrives_from(ip(5, 6, 7, 8), 4672, buddies.buddy_id).await;

    buddies.then_the_relay_went(true);
    buddies.then_the_firewalled_node_gets_the_reask_of(ip(5, 6, 7, 8), 4672).await;
}

#[tokio::test]
async fn should_not_relay_a_reask_for_another_id() {
    let mut buddies = Buddies::new();
    buddies.given_we_agreed_to_serve(FIREWALLED).await;
    buddies.given_it_connected_as(FIREWALLED).await;

    buddies.when_a_reask_through_us_arrives_from(ip(5, 6, 7, 8), 4672, UInt128::random(&mut rand::rng())).await;

    buddies.then_the_relay_went(false);
}

#[tokio::test]
async fn should_relay_the_reasks_of_a_client_once_in_thirty_seconds() {
    let mut buddies = Buddies::new();
    buddies.given_we_agreed_to_serve(FIREWALLED).await;
    buddies.given_it_connected_as(FIREWALLED).await;
    buddies.given_a_reask_through_us_relayed_from(ip(5, 6, 7, 8), 4672).await;
    buddies.given_seconds_passed(29).await;

    buddies.when_a_reask_through_us_arrives_from(ip(5, 6, 7, 8), 4672, buddies.buddy_id).await;

    buddies.then_the_relay_went(false);
}

#[tokio::test]
async fn should_let_our_buddy_relay_the_reasks_of_clients_in_our_queue() {
    let mut buddies = Buddies::new();
    buddies.given_a_buddy_listening().await;
    buddies.given_it_is_our_buddy().await;

    buddies.when_the_buddy_relays_a_reask_from(ip(1, 2, 3, 4), 4672).await;

    buddies.then_a_reask_arrives_from(ip(1, 2, 3, 4), 4672).await;
}

#[tokio::test]
async fn should_pass_on_a_rendezvous_our_buddy_relays_with_the_requesters_address() {
    let mut buddies = Buddies::new();
    buddies.given_a_buddy_listening().await;
    buddies.given_it_is_our_buddy().await;

    buddies.when_the_buddy_relays_a_rendezvous_from(ip(1, 2, 3, 4), 4672).await;

    buddies.then_a_rendezvous_arrives_from("1.2.3.4:4672", [0xab; 16]).await;
    buddies.then_no_reask_arrives().await;
}

#[tokio::test]
async fn should_not_take_a_client_we_did_not_agree_to_serve() {
    let mut buddies = Buddies::new();
    buddies.given_we_agreed_to_serve(FIREWALLED).await;

    buddies.when_it_connects_as(BUDDY).await;

    buddies.then_the_connection_closes().await;
    buddies.then_the_state_stays(BuddyState::None).await;
}

#[tokio::test]
async fn should_answer_the_node_we_serve_at_most_once_in_thirteen_minutes() {
    let mut buddies = Buddies::new();
    buddies.given_we_agreed_to_serve(FIREWALLED).await;
    buddies.given_it_connected_as(FIREWALLED).await;
    buddies.given_minutes_passed(13).await;
    buddies.given_it_pinged().await;

    buddies.when_it_pings_again().await;

    buddies.then_it_gets(extended::BUDDYPONG).await;
    buddies.then_it_gets_nothing_more().await;
}

#[tokio::test]
async fn should_close_a_released_link() {
    let mut buddies = Buddies::new();
    buddies.given_a_buddy_listening().await;
    buddies.given_it_is_our_buddy().await;

    buddies.when_the_link_is_released().await;

    buddies.then_the_connection_closes().await;
    buddies.then_the_state_stays(BuddyState::None).await;
}

/// The scenario: the link on its loop, the other end of the link, and what the steps saw.
struct Buddies {
    jobs: mpsc::UnboundedSender<Job>,
    offset: Arc<AtomicU64>,
    // Where the loop takes connections of clients.
    port: u16,
    buddy_id: UInt128,
    buddy_listener: Option<TcpListener>,
    peer: Option<Peer>,
    relayed: bool,
}

impl Buddies {
    fn new() -> Self {
        let offset = Arc::new(AtomicU64::new(0));
        let base = Instant::now();
        let ticks = Arc::clone(&offset);
        let clock = Box::new(move || base + Duration::from_millis(ticks.load(Ordering::Relaxed)));
        let (link_events, links) = mpsc::unbounded_channel();
        let (dialed_in, dialed) = mpsc::unbounded_channel();
        let (jobs, jobs_out) = mpsc::unbounded_channel();
        let lab = Lab {
            buddies: BuddyLink::new(clock, Box::new(|_| false)),
            ours: hello(OURS, 4662, "test", 4672),
            links: HashMap::new(),
            incoming: HashSet::new(),
            answered: HashSet::new(),
            next_link: 1,
            link_events,
            dialed: dialed_in,
            callbacks: Vec::new(),
            reasks: Vec::new(),
            rendezvous: Vec::new(),
        };
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        listener.set_nonblocking(true).unwrap();
        let port = listener.local_addr().unwrap().port();
        let listener = TcpListener::from_std(listener).unwrap();
        tokio::spawn(serve(lab, jobs_out, links, dialed, listener));
        Self {
            jobs,
            offset,
            port,
            buddy_id: UInt128::parse_hex("00112233445566778899AABBCCDDEEFF").unwrap(),
            buddy_listener: None,
            peer: None,
            relayed: false,
        }
    }

    async fn given_a_buddy_listening(&mut self) {
        self.buddy_listener = Some(TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap());
    }

    async fn given_it_is_our_buddy(&mut self) {
        self.given_it_offered_to_be_our_buddy().await;
        self.when_it_answers_our_hello_as(BUDDY, 4675).await;
        self.then_the_state_becomes(BuddyState::Connected).await;
    }

    async fn given_we_agreed_to_serve(&self, user_hash: [u8; 16]) {
        let buddy_id = self.buddy_id;
        assert!(self.run(move |lab| lab.buddies.asked(LOOPBACK, 4663, 4676, user_hash, buddy_id)).await);
    }

    async fn given_it_connected_as(&mut self, user_hash: [u8; 16]) {
        self.when_it_connects_as(user_hash).await;
        self.then_the_state_becomes(BuddyState::Serving).await;
    }

    async fn given_minutes_passed(&self, minutes: u64) {
        self.pass(Duration::from_secs(minutes * 60)).await;
    }

    async fn given_a_reask_through_us_relayed_from(&mut self, ip: u32, port: u16) {
        self.when_a_reask_through_us_arrives_from(ip, port, self.buddy_id).await;
        self.then_the_relay_went(true);
    }

    async fn given_seconds_passed(&self, seconds: u64) {
        self.pass(Duration::from_secs(seconds)).await;
    }

    async fn given_it_pinged(&mut self) {
        self.ping().await;
    }

    async fn given_it_offered_to_be_our_buddy(&self) {
        let port = self.buddy_listener.as_ref().unwrap().local_addr().unwrap().port();
        self.run(move |lab| lab.buddies.offered(LOOPBACK, port, 4673, BUDDY, 0)).await;
    }

    async fn when_it_answers_our_hello_as(&mut self, user_hash: [u8; 16], udp_port: u16) {
        let listener = self.buddy_listener.as_ref().unwrap();
        let (stream, _) = tokio::time::timeout(WAIT, listener.accept()).await.expect("our connection").unwrap();
        let mut peer = Peer::over(stream);
        assert_eq!(peer.next().await.opcode(), client::HELLO);
        peer.send(&hello(user_hash, 4664, "buddy", udp_port).encode(true).unwrap()).await;
        self.peer = Some(peer);
    }

    async fn when_it_connects_as(&mut self, user_hash: [u8; 16]) {
        let stream = TcpStream::connect((Ipv4Addr::LOCALHOST, self.port)).await.unwrap();
        let mut peer = Peer::over(stream);
        peer.send(&hello(user_hash, 4663, "firewalled", 4676).encode(false).unwrap()).await;
        assert_eq!(peer.next().await.opcode(), client::HELLOANSWER);
        self.peer = Some(peer);
    }

    async fn when_the_buddy_relays_a_callback_from(&mut self, ip: u32, port: u16) {
        let callback =
            Callback { buddy_id: self.buddy_id, file: UInt128::ZERO, requester_ip: ip, requester_port: port };
        self.peer().send(&callback.encode()).await;
    }

    async fn when_a_reask_through_us_arrives_from(&mut self, ip: u32, port: u16, id: UInt128) {
        let through_us = ThroughBuddy { buddy_id: id.to_big_endian(), ping: ping() }.encode();
        self.relayed = self.run(move |lab| lab.buddies.relay_reask(ip, port, &through_us)).await;
    }

    async fn when_the_buddy_relays_a_rendezvous_from(&mut self, ip: u32, port: u16) {
        let rendezvous = Rendezvous {
            requester: [0xab; 16],
            options: 0x80,
            file: [0; 16],
            requester_ip: 0,
            requester_port: 0,
            transport: 0,
        };
        let through_buddy = rendezvous.encode_through_buddy(&[0xcd; 16]);
        let mut relayed = PacketWriter::new();
        relayed.u32(ipv4::to_ed2k(ip)).u16(port).bytes(&through_buddy.payload()[16..]);
        self.peer().send(&Packet::written(protocol::EMULE, extended::REASKCALLBACKTCP, relayed)).await;
    }

    async fn when_the_buddy_relays_a_reask_from(&mut self, ip: u32, port: u16) {
        self.peer().send(&Relayed { requester_ip: ip, requester_port: port, ping: ping() }.encode()).await;
    }

    async fn when_a_callback_is_relayed(&mut self, id: UInt128) {
        self.relayed = self.run(move |lab| lab.buddies.relay(id, UInt128::ZERO, ip(5, 6, 7, 8), 4711)).await;
    }

    async fn when_it_pings_again(&mut self) {
        self.ping().await;
    }

    async fn when_minutes_pass(&self, minutes: u64) {
        self.pass(Duration::from_secs(minutes * 60)).await;
    }

    async fn when_the_link_is_released(&self) {
        self.run(|lab| lab.buddies.release()).await;
    }

    async fn then_the_state_becomes(&self, state: BuddyState) {
        let deadline = Instant::now() + WAIT;
        while self.run(|lab| lab.buddies.state()).await != state {
            assert!(Instant::now() < deadline, "waited in vain for {state:?}");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    async fn then_the_state_stays(&self, state: BuddyState) {
        assert_eq!(self.run(|lab| lab.buddies.state()).await, state);
    }

    async fn then_our_buddy_is_at(&self, ip: u32, port: u16) {
        let buddy = self.run(|lab| (lab.buddies.buddy_ip(), lab.buddies.buddy_udp_port())).await;
        assert_eq!(buddy, (ip, port));
    }

    async fn then_the_buddy_gets(&mut self, opcode: u8) {
        self.then_it_gets(opcode).await;
    }

    async fn then_it_gets(&mut self, opcode: u8) {
        let packet = self.peer().next().await;
        assert_eq!((packet.protocol(), packet.opcode()), (protocol::EMULE, opcode));
    }

    async fn then_it_gets_nothing_more(&mut self) {
        assert!(self.peer().silent_for(Duration::from_millis(500)).await);
    }

    async fn then_a_callback_arrives_from(&self, ip: u32, port: u16) {
        self.wait_for(|lab| !lab.callbacks.is_empty()).await;
        let expected =
            Callback { buddy_id: self.buddy_id, file: UInt128::ZERO, requester_ip: ip, requester_port: port };
        assert_eq!(self.run(|lab| lab.callbacks.clone()).await, vec![expected]);
    }

    async fn then_the_firewalled_node_gets_the_reask_of(&mut self, ip: u32, port: u16) {
        let packet = self.peer().next().await;
        assert_eq!(packet.opcode(), extended::REASKCALLBACKTCP);
        assert_eq!(Relayed::decode(&packet).unwrap(), Relayed { requester_ip: ip, requester_port: port, ping: ping() });
    }

    async fn then_a_reask_arrives_from(&self, ip: u32, port: u16) {
        self.wait_for(|lab| !lab.reasks.is_empty()).await;
        let expected = Relayed { requester_ip: ip, requester_port: port, ping: ping() };
        assert_eq!(self.run(|lab| lab.reasks.clone()).await, vec![expected]);
    }

    async fn then_a_rendezvous_arrives_from(&self, address: &str, requester: [u8; 16]) {
        self.wait_for(|lab| !lab.rendezvous.is_empty()).await;
        let from: SocketAddr = address.parse().unwrap();
        assert_eq!(self.run(|lab| lab.rendezvous.clone()).await, vec![(from, requester)]);
    }

    async fn then_no_reask_arrives(&self) {
        assert!(self.run(|lab| lab.reasks.is_empty()).await);
    }

    fn then_the_relay_went(&self, went: bool) {
        assert_eq!(self.relayed, went);
    }

    async fn then_the_firewalled_node_gets_the_callback(&mut self) {
        let packet = self.peer().next().await;
        assert_eq!(packet.opcode(), extended::CALLBACK);
        let expected = Callback {
            buddy_id: self.buddy_id,
            file: UInt128::ZERO,
            requester_ip: ip(5, 6, 7, 8),
            requester_port: 4711,
        };
        assert_eq!(Callback::decode(&packet).unwrap(), expected);
    }

    async fn then_the_connection_closes(&mut self) {
        assert!(self.peer().closes_within(WAIT).await);
    }

    async fn ping(&mut self) {
        self.peer().send(&Packet::empty(protocol::EMULE, extended::BUDDYPING)).await;
    }

    async fn pass(&self, by: Duration) {
        self.offset.fetch_add(u64::try_from(by.as_millis()).unwrap(), Ordering::Relaxed);
        self.run(|lab| lab.buddies.tick()).await;
    }

    fn peer(&mut self) -> &mut Peer {
        self.peer.as_mut().expect("a peer at the other end")
    }

    async fn run<T: Send + 'static>(&self, work: impl FnOnce(&mut Lab) -> T + Send + 'static) -> T {
        let (answer, answered) = oneshot::channel();
        let job: Job = Box::new(move |lab| {
            let _ = answer.send(work(lab));
        });
        self.jobs.send(job).ok().unwrap();
        answered.await.unwrap()
    }

    async fn wait_for(&self, condition: impl Fn(&mut Lab) -> bool + Send + Copy + 'static) {
        let deadline = Instant::now() + WAIT;
        while !self.run(condition).await {
            assert!(Instant::now() < deadline, "waited in vain");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }
}

type Job = Box<dyn FnOnce(&mut Lab) + Send>;

/// What the loop owns: the link, the connections it works with, and what it passed on.
struct Lab {
    buddies: BuddyLink,
    ours: Hello,
    links: HashMap<LinkId, LinkHandle>,
    // Clients that connected to us and have not greeted yet.
    incoming: HashSet<LinkId>,
    // Our connections whose hello was answered.
    answered: HashSet<LinkId>,
    next_link: LinkId,
    link_events: mpsc::UnboundedSender<LinkEvent>,
    dialed: mpsc::UnboundedSender<(u64, io::Result<Connection>)>,
    callbacks: Vec<Callback>,
    reasks: Vec<Relayed>,
    rendezvous: Vec<(SocketAddr, [u8; 16])>,
}

impl Lab {
    // Acts on what the link asked and keeps what it passed on, as the network does.
    fn after(&mut self) {
        for action in self.buddies.take_actions() {
            match action {
                BuddyAction::Connect { attempt, address, .. } => {
                    let dialed = self.dialed.clone();
                    tokio::spawn(async move {
                        let mut random = StdRng::from_rng(&mut rand::rng());
                        // Plain, as for a node whose connect options do not ask for obfuscation.
                        let timeout = peer_greetings::HELLO_TIMEOUT;
                        let result = tcp_connector::connect_to_client(
                            address.into(),
                            None,
                            timeout,
                            &Routes::direct(),
                            &mut random,
                        )
                        .await;
                        let _ = dialed.send((attempt, result));
                    });
                }
                BuddyAction::CallBack(callback) => self.callbacks.push(callback),
                BuddyAction::AnswerReask(reask) => self.reasks.push(reask),
                BuddyAction::Rendezvous { from, rendezvous } => self.rendezvous.push((from, rendezvous.requester)),
            }
        }
    }

    // Our connection to a node that offered: our hello goes first.
    fn dialed(&mut self, attempt: u64, result: io::Result<Connection>) {
        match result {
            Ok(connection) => {
                let handle = self.start(connection.into_tcp_connection(frame_limits::client_handshake));
                handle.send(self.ours.to_peer_at(&handle.remote().ip()).encode(false).unwrap());
                if !self.buddies.opened(attempt, &handle) {
                    handle.close();
                }
            }
            Err(e) => self.buddies.failed(attempt, &e.to_string()),
        }
    }

    // A client connected to us: the link takes it once it greets us as the node we agreed to serve.
    fn accepted(&mut self, stream: TcpStream) {
        let remote = stream.peer_addr().unwrap();
        let local = stream.local_addr().ok().map(|address| address.ip());
        let (input, output) = stream.into_split();
        let handle = self.start(TcpConnection::over(input, output, remote, local, frame_limits::client_handshake));
        self.incoming.insert(handle.id());
    }

    fn start(&mut self, connection: TcpConnection) -> LinkHandle {
        let id = self.next_link;
        self.next_link += 1;
        let handle = peer_link::start(id, connection, self.link_events.clone());
        self.links.insert(id, handle.clone());
        handle
    }

    fn event(&mut self, event: LinkEvent) {
        match event {
            LinkEvent::Packet(link, packet) => self.packet(link, &packet),
            LinkEvent::Closed(link, reason) => {
                self.links.remove(&link);
                self.incoming.remove(&link);
                self.answered.remove(&link);
                self.buddies.closed(link, reason.as_deref());
            }
        }
    }

    fn packet(&mut self, link: LinkId, packet: &Packet) {
        let Some(handle) = self.links.get(&link).cloned() else {
            return;
        };
        let greeting = |opcode| (packet.protocol(), packet.opcode()) == (protocol::EDONKEY, opcode);
        if self.incoming.remove(&link) {
            match Hello::decode(packet) {
                Ok(hello) if greeting(client::HELLO) => {
                    handle.send(self.ours.to_peer_at(&handle.remote().ip()).encode(true).unwrap());
                    if !self.buddies.adopt(&handle, &hello) {
                        handle.close();
                    }
                }
                _ => handle.close(),
            }
        } else if greeting(client::HELLOANSWER) && self.answered.insert(link) {
            if let Ok(hello) = Hello::decode(packet) {
                self.buddies.greeted(link, &hello);
            }
        } else if peer_link::side_of(packet) == Side::Buddy && self.buddies.has_link(link) {
            self.buddies.received(link, packet);
        }
    }
}

async fn serve(
    mut lab: Lab,
    mut jobs: mpsc::UnboundedReceiver<Job>,
    mut links: mpsc::UnboundedReceiver<LinkEvent>,
    mut dialed: mpsc::UnboundedReceiver<(u64, io::Result<Connection>)>,
    listener: TcpListener,
) {
    loop {
        tokio::select! {
            Ok((stream, _)) = listener.accept() => lab.accepted(stream),
            Some(event) = links.recv() => lab.event(event),
            Some((attempt, result)) = dialed.recv() => lab.dialed(attempt, result),
            job = jobs.recv() => match job {
                Some(job) => job(&mut lab),
                None => {
                    lab.buddies.release();
                    return;
                }
            },
        }
        lab.after();
    }
}

/// The other end of a buddy link, written and read packet by packet.
struct Peer {
    input: OwnedReadHalf,
    output: OwnedWriteHalf,
}

impl Peer {
    fn over(stream: TcpStream) -> Self {
        let (input, output) = stream.into_split();
        Self { input, output }
    }

    async fn send(&mut self, packet: &Packet) {
        write_packet(&mut self.output, packet).await.unwrap();
    }

    async fn next(&mut self) -> Packet {
        let packet = tokio::time::timeout(WAIT, read_packet(&mut self.input, &frame_limits::client))
            .await
            .expect("a packet in time")
            .unwrap()
            .expect("the connection open");
        packing::unpack(packet, 1 << 22).unwrap()
    }

    async fn silent_for(&mut self, wait: Duration) -> bool {
        tokio::time::timeout(wait, read_packet(&mut self.input, &frame_limits::client)).await.is_err()
    }

    async fn closes_within(&mut self, wait: Duration) -> bool {
        let deadline = tokio::time::Instant::now() + wait;
        loop {
            match tokio::time::timeout_at(deadline, read_packet(&mut self.input, &frame_limits::client)).await {
                Ok(Ok(Some(_))) => {}
                Ok(Ok(None) | Err(_)) => return true,
                Err(_) => return false,
            }
        }
    }
}

fn hello(user_hash: [u8; 16], tcp_port: u16, nick: &str, udp_port: u16) -> Hello {
    let ours = Ours { nick, udp_port, kad_port: udp_port, misc_options1: 0, misc_options2: 0 };
    Hello::of(user_hash, 1, tcp_port, &ours, 0, 0)
}

fn ping() -> Packet {
    client_reasks::Ping { file: [0xdd; 16], parts: PartStatus::COMPLETE, complete_sources: 3 }
        .encode(4)
        .unwrap()
}

fn ip(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from(Ipv4Addr::new(a, b, c, d))
}
