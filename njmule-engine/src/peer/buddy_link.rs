// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The TCP link between Kad buddies. Firewalled, we connect to the nodes that offered to be our buddy, one at a time,
//! keep the first link that stands and ping it now and then. Open, we serve one firewalled node that asked us: we take
//! its connection and relay to it the callback requests and the UDP re-asks of other clients. The network makes the
//! connections the link asks for and hands it their packets.

use std::collections::{HashMap, VecDeque};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4};
use std::time::{Duration, Instant};

use crate::kad::kad_node::{Buddies, BuddyState};
use crate::kad::uint128::UInt128;
use crate::peer::peer_link::{LinkHandle, LinkId};
use crate::protocol::ed2k::client_reasks::{Relayed, ThroughBuddy};
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::ed2k::low_id_messages::{self, Callback};
use crate::protocol::ed2k::nat_traversal_messages::{Forward, Rendezvous};
use crate::protocol::packet::opcodes::{client_udp, extended};
use crate::protocol::packet::{Packet, protocol};
use crate::util::instants;

/// The firewalled side pings this often.
pub const PING_EVERY: Duration = Duration::from_secs(10 * 60);

/// The buddy answers a ping this long after its last answer at most.
pub const PONG_GAP: Duration = Duration::from_secs(13 * 60);

/// A buddy link waits this long for a packet: pings come every ten minutes, and a buddy that answers every other one
/// sends a pong every twenty.
pub const LINK_TIMEOUT: Duration = Duration::from_secs(25 * 60);

/// A firewalled node that asked us waits this long for its connection to be taken.
pub const EXPECTED_FOR: Duration = Duration::from_secs(10 * 60);

/// The re-asks of one client, by address and UDP port, are relayed to the node we serve this rarely at most.
pub const RELAY_GAP: Duration = Duration::from_secs(30);

// Clients whose re-asks were relayed within the gap, kept at once: beyond, new ones wait.
const MAX_RELAYED: usize = 512;

// Offers and requests kept at once: the first link to stand wins, the rest are dropped.
const MAX_OFFERS: usize = 10;
const MAX_EXPECTED: usize = 4;

pub type Clock = Box<dyn Fn() -> Instant + Send>;

/// Whether we deal with a client at this IPv4 address already (in the order the network gives it).
pub type Known = Box<dyn Fn(u32) -> bool + Send>;

/// What the link asks of the network, or passes on to it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BuddyAction {
    /// Connect to the node that offered at `address` and send our hello, obfuscated for `user_hash` when its
    /// `connect_options` and ours allow, then hand the link over with [`BuddyLink::opened`] or tell
    /// [`BuddyLink::failed`].
    Connect { attempt: u64, address: SocketAddrV4, user_hash: [u8; 16], connect_options: u8 },
    /// Our buddy relays the callback request of another client.
    CallBack(Callback),
    /// Our buddy relays the UDP re-ask of a client in our queue.
    AnswerReask(Relayed),
    /// Our buddy passes on a rendezvous, with the address it saw the requester at.
    Rendezvous { from: SocketAddr, rendezvous: Rendezvous },
}

/// A node that offered to be our buddy.
#[derive(Debug, Clone, Copy)]
struct Offer {
    ip: u32,
    tcp_port: u16,
    udp_port: u16,
    user_hash: [u8; 16],
    connect_options: u8,
}

/// A firewalled node that asked us to be its buddy and will connect.
#[derive(Debug, Clone, Copy)]
struct Expected {
    ip: u32,
    user_hash: [u8; 16],
    buddy_id: UInt128,
    since: Instant,
}

/// The connection to the node that offered, until its hello answer.
struct Connecting {
    attempt: u64,
    offer: Offer,
    link: Option<LinkHandle>,
}

pub struct BuddyLink {
    clock: Clock,
    known: Known,
    state: BuddyState,
    offers: VecDeque<Offer>,
    expected: Vec<Expected>,
    // When the re-asks of each client (address and UDP port) were last relayed to the node we serve.
    relayed: HashMap<(u32, u16), Instant>,
    connecting: Option<Connecting>,
    next_attempt: u64,
    link: Option<LinkHandle>,
    // Our buddy's address and UDP port while we have one.
    buddy: Option<(u32, u16)>,
    // The id the node we serve was found by; callback requests must name it.
    served_id: Option<UInt128>,
    // When our buddy is pinged next, and when the node we serve may get its next pong.
    next_ping: Instant,
    next_pong: Instant,
    actions: Vec<BuddyAction>,
}

impl BuddyLink {
    pub fn new(clock: Clock, known: Known) -> Self {
        let now = clock();
        Self {
            clock,
            known,
            state: BuddyState::None,
            offers: VecDeque::new(),
            expected: Vec::new(),
            relayed: HashMap::new(),
            connecting: None,
            next_attempt: 1,
            link: None,
            buddy: None,
            served_id: None,
            next_ping: now,
            next_pong: now,
            actions: Vec::new(),
        }
    }

    /// The address of the firewalled node we serve, while we serve one.
    pub fn served(&self) -> Option<SocketAddr> {
        self.link.as_ref().filter(|_| self.state == BuddyState::Serving).map(LinkHandle::remote)
    }

    /// Whether `link` is the buddy link, or the connection to a node that offered.
    pub fn has_link(&self, link: LinkId) -> bool {
        self.is_buddy_link(link) || self.connecting_link() == Some(link)
    }

    /// What the link asked of the network or passed on since the last call.
    pub fn take_actions(&mut self) -> Vec<BuddyAction> {
        std::mem::take(&mut self.actions)
    }

    /// Takes a connection a client opened when it is the firewalled node we agreed to serve.
    pub fn adopt(&mut self, link: &LinkHandle, hello: &Hello) -> bool {
        let IpAddr::V4(from) = link.remote().ip().to_canonical() else {
            return false;
        };
        if self.state != BuddyState::None {
            return false;
        }
        let at = u32::from(from);
        let Some(expected) = self.expected.iter().find(|e| e.ip == at && e.user_hash == hello.user_hash).copied()
        else {
            return false;
        };
        self.expected.clear();
        self.offers.clear();
        self.attach(link.clone());
        self.state = BuddyState::Serving;
        self.served_id = Some(expected.buddy_id);
        // The first ping is answered once the node has waited as long as between two pongs.
        self.next_pong = instants::later(expected.since, PONG_GAP);
        tracing::info!("Kad: we are now the buddy of {}", link.remote());
        true
    }

    /// Relays to the node we serve the re-ask a client at `ip` and UDP `port` sent us, when it names that node; false
    /// otherwise.
    pub fn relay_reask(&mut self, ip: u32, port: u16, through_buddy: &Packet) -> bool {
        if self.state != BuddyState::Serving {
            return false;
        }
        let reask = match ThroughBuddy::decode(through_buddy) {
            Ok(reask) => reask,
            Err(e) => {
                tracing::debug!("Bad re-ask through us from {}: {e}", Ipv4Addr::from(ip));
                return false;
            }
        };
        let now = (self.clock)();
        let last = self.relayed.get(&(ip, port)).copied();
        if self.served_id != Some(UInt128::from_big_endian(reask.buddy_id))
            || last.is_some_and(|at| now.saturating_duration_since(at) < RELAY_GAP)
            || (last.is_none() && self.relayed.len() >= MAX_RELAYED)
        {
            return false;
        }
        self.relayed.insert((ip, port), now);
        self.send(Relayed { requester_ip: ip, requester_port: port, ping: reask.ping }.encode())
    }

    /// Runs every second: pings our buddy, forgets stale requests, and connects to the next offer.
    pub fn tick(&mut self) {
        let now = (self.clock)();
        self.forget_expected(now);
        self.relayed.retain(|_, at| now.saturating_duration_since(*at) < RELAY_GAP);
        if self.state == BuddyState::Connected && now >= self.next_ping {
            self.next_ping = instants::later(now, PING_EVERY);
            self.send(low_id_messages::buddy_ping());
        }
        self.connect_next();
    }

    /// The connection of `attempt` is open, with our hello sent over `link`; false when it is no longer wanted, and the
    /// network closes it.
    pub fn opened(&mut self, attempt: u64, link: &LinkHandle) -> bool {
        match &mut self.connecting {
            Some(connecting) if connecting.attempt == attempt && connecting.link.is_none() => {
                connecting.link = Some(link.clone());
                true
            }
            _ => false,
        }
    }

    /// The connection of `attempt` could not be made.
    pub fn failed(&mut self, attempt: u64, reason: &str) {
        if self.connecting.as_ref().is_some_and(|connecting| connecting.attempt == attempt) {
            self.give_up(reason);
        }
    }

    /// The client on `link` answered our hello: only the node that offered, by its user hash, becomes our buddy.
    pub fn greeted(&mut self, link: LinkId, hello: &Hello) {
        if self.connecting_link() != Some(link) {
            return;
        }
        let Some(connecting) = self.connecting.take() else {
            return;
        };
        if hello.user_hash != connecting.offer.user_hash {
            self.abandon(connecting, "another client answered");
            return;
        }
        let (offer, Some(handle)) = (connecting.offer, connecting.link) else {
            return;
        };
        self.offers.clear();
        tracing::info!("Kad: {} is our buddy", handle.remote());
        self.attach(handle);
        let udp_port = hello.udp_port();
        self.buddy = Some((offer.ip, if udp_port != 0 { udp_port } else { offer.udp_port }));
        self.state = BuddyState::Connected;
        self.next_ping = instants::later((self.clock)(), PING_EVERY);
    }

    /// A packet for the buddy side from the client on `link`.
    pub fn received(&mut self, link: LinkId, packet: &Packet) {
        if !self.is_buddy_link(link) || packet.protocol() != protocol::EMULE {
            return;
        }
        let now = (self.clock)();
        match packet.opcode() {
            extended::BUDDYPING if self.state == BuddyState::Serving && now >= self.next_pong => {
                self.next_pong = instants::later(now, PONG_GAP);
                self.send(low_id_messages::buddy_pong());
            }
            extended::BUDDYPONG if self.state == BuddyState::Connected => {
                self.next_ping = instants::later(now, PING_EVERY);
            }
            extended::CALLBACK if self.state == BuddyState::Connected => match Callback::decode(packet) {
                Ok(callback) => self.actions.push(BuddyAction::CallBack(callback)),
                Err(e) => tracing::debug!("Bad callback from our buddy: {e}"),
            },
            extended::REASKCALLBACKTCP if self.state == BuddyState::Connected => self.reask(packet),
            // Nothing else travels on a buddy link.
            _ => {}
        }
    }

    /// `link` is closed.
    pub fn closed(&mut self, link: LinkId, reason: Option<&str>) {
        if self.is_buddy_link(link) {
            tracing::info!("Kad: buddy link closed{}", reason.map(|reason| format!(": {reason}")).unwrap_or_default());
            self.drop_link();
        } else if self.connecting_link() == Some(link) {
            self.give_up(reason.unwrap_or("closed before the hello"));
        }
    }

    // A re-ask names its file; a zero file hash carries another packet, such as a rendezvous.
    fn reask(&mut self, packet: &Packet) {
        let action = match Forward::decode(packet) {
            Ok(None) => Relayed::decode(packet).map(BuddyAction::AnswerReask),
            Ok(Some(forward)) if forward.opcode == client_udp::RENDEZVOUS => Rendezvous::decode(&forward.payload)
                .map(|rendezvous| BuddyAction::Rendezvous { from: forward.requester, rendezvous }),
            Ok(Some(_)) => return,
            Err(e) => Err(e),
        };
        match action {
            Ok(action) => self.actions.push(action),
            Err(e) => tracing::debug!("Bad re-ask from our buddy: {e}"),
        }
    }

    fn connect_next(&mut self) {
        if self.state != BuddyState::None || self.connecting.is_some() {
            return;
        }
        let Some(offer) = self.offers.pop_front() else {
            return;
        };
        let attempt = self.next_attempt;
        self.next_attempt = self.next_attempt.wrapping_add(1);
        self.connecting = Some(Connecting { attempt, offer, link: None });
        self.state = BuddyState::Connecting;
        self.actions.push(BuddyAction::Connect {
            attempt,
            address: SocketAddrV4::new(Ipv4Addr::from(offer.ip), offer.tcp_port),
            user_hash: offer.user_hash,
            connect_options: offer.connect_options,
        });
    }

    // The node that offered is no buddy of ours: the next offer is tried.
    fn give_up(&mut self, reason: &str) {
        if let Some(connecting) = self.connecting.take() {
            self.abandon(connecting, reason);
        }
    }

    fn abandon(&mut self, connecting: Connecting, reason: &str) {
        tracing::debug!("Kad: no buddy link to {}: {reason}", Ipv4Addr::from(connecting.offer.ip));
        if let Some(link) = connecting.link {
            link.close();
        }
        self.state = BuddyState::None;
        self.connect_next();
    }

    fn forget_expected(&mut self, now: Instant) {
        self.expected.retain(|e| now.saturating_duration_since(e.since) < EXPECTED_FOR);
    }

    fn attach(&mut self, link: LinkHandle) {
        link.set_timeout(LINK_TIMEOUT);
        self.link = Some(link);
    }

    fn is_buddy_link(&self, link: LinkId) -> bool {
        self.link.as_ref().is_some_and(|handle| handle.id() == link)
    }

    fn connecting_link(&self) -> Option<LinkId> {
        self.connecting.as_ref().and_then(|connecting| connecting.link.as_ref()).map(LinkHandle::id)
    }

    fn send(&self, packet: Packet) -> bool {
        self.link.as_ref().is_some_and(|link| link.send(packet))
    }

    fn drop_link(&mut self) {
        self.link = None;
        self.state = BuddyState::None;
        self.buddy = None;
        self.served_id = None;
        self.relayed.clear();
    }
}

impl Buddies for BuddyLink {
    fn state(&self) -> BuddyState {
        self.state
    }

    fn buddy_ip(&self) -> u32 {
        self.buddy.map_or(0, |(ip, _)| ip)
    }

    fn buddy_udp_port(&self) -> u16 {
        self.buddy.map_or(0, |(_, port)| port)
    }

    fn offered(&mut self, ip: u32, tcp_port: u16, udp_port: u16, user_hash: [u8; 16], connect_options: u8) {
        if matches!(self.state, BuddyState::Connected | BuddyState::Serving)
            || self.offers.len() >= MAX_OFFERS
            || self.offers.iter().any(|offer| offer.ip == ip)
        {
            return;
        }
        self.offers.push_back(Offer { ip, tcp_port, udp_port, user_hash, connect_options });
        self.connect_next();
    }

    fn asked(&mut self, ip: u32, _tcp_port: u16, _udp_port: u16, user_hash: [u8; 16], buddy_id: UInt128) -> bool {
        let now = (self.clock)();
        self.forget_expected(now);
        // A client we deal with already would mix up both uses of the link.
        if self.state != BuddyState::None
            || self.expected.len() >= MAX_EXPECTED
            || (self.known)(ip)
            || self.expected.iter().any(|e| e.ip == ip)
        {
            return false;
        }
        self.expected.push(Expected { ip, user_hash, buddy_id, since: now });
        true
    }

    fn relay(&mut self, buddy_id: UInt128, file: UInt128, requester_ip: u32, requester_port: u16) -> bool {
        if self.state != BuddyState::Serving || self.served_id != Some(buddy_id) {
            return false;
        }
        self.send(Callback { buddy_id, file, requester_ip, requester_port }.encode())
    }

    fn release(&mut self) {
        self.offers.clear();
        self.expected.clear();
        if let Some(link) = self.connecting.take().and_then(|connecting| connecting.link) {
            link.close();
        }
        let open = self.link.take();
        self.drop_link();
        if let Some(open) = open {
            tracing::info!("Kad: buddy link to {} released", open.remote());
            open.close();
        }
    }
}
