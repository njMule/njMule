// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! One iterative lookup towards a target: the closest contacts we know are asked for closer ones, three at a time, and
//! the nodes that answered and are close enough get the actual request (a search or a publish).

use std::collections::{BTreeMap, HashMap, HashSet};
use std::fmt;
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::kad::kad_contact;
use crate::kad::response_times::{self, ResponseTimes};
use crate::kad::routing_table::RoutingTable;
use crate::kad::uint128::UInt128;
use crate::protocol::kad::kad_messages::Contact;

/// Ids within this distance of the target, in their top 32 bits, get the request.
pub const TOLERANCE: u32 = 0x0100_0000;

const ALPHA: usize = 3;
const START_CONTACTS: usize = 50;
const STALL: Duration = response_times::MAX_WAIT;
// Once no node is left to ask, or it has all it wants, a search or publish waits this long for its last answers.
const LATE_ANSWER: Duration = Duration::from_secs(15);
// A search or publish sends nothing new in the last seconds of its life, which its last answers need.
const LAST_SENDING: Duration = Duration::from_secs(20);
const RE_ASK_COUNT: u8 = 11;
// Contacts a search asks each node for; publishes ask for more.
const FIND_VALUE: u8 = 2;
const PER_SUBNET: u32 = 2;
// Nodes that must place a contact at the same address before it gets the request ahead of the others.
const VOUCHERS: usize = 2;
const SUBNET: u32 = 0xFFFF_FF00;

/// The purpose of a lookup, with the contacts it asks for and how long it lives.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Kind {
    Node,
    NodeComplete,
    Sources,
    Keyword,
    Notes,
    StoreSource,
    StoreKeyword,
    StoreNotes,
    /// Finds helpers for the UDP firewall test: its answers are never asked or added.
    UdpHelpers,
    /// The value counted is each buddy request sent.
    FindBuddy,
}

impl Kind {
    /// The contacts each node is asked for.
    pub fn count(self) -> u8 {
        match self {
            Self::Node | Self::NodeComplete | Self::UdpHelpers => 11,
            Self::Sources | Self::Keyword | Self::Notes => FIND_VALUE,
            Self::StoreSource | Self::StoreKeyword | Self::StoreNotes | Self::FindBuddy => 4,
        }
    }

    pub fn lifetime(self) -> Duration {
        Duration::from_secs(match self {
            Self::StoreSource | Self::StoreKeyword => 140,
            Self::StoreNotes | Self::FindBuddy => 100,
            _ => 45,
        })
    }

    // The values after which a search or publish stops sending; none for a node lookup.
    fn max_answers(self) -> u32 {
        match self {
            Self::Sources | Self::Keyword => 300,
            Self::Notes => 50,
            Self::StoreSource | Self::StoreKeyword | Self::StoreNotes | Self::FindBuddy => 10,
            Self::Node | Self::NodeComplete | Self::UdpHelpers => 0,
        }
    }

    // Whether the lookup sends a request to the nodes it finds.
    fn sends_value(self) -> bool {
        self.max_answers() > 0
    }

    /// Publishes run in the background: they wait the full time for silent nodes.
    pub fn stores(self) -> bool {
        matches!(self, Self::StoreSource | Self::StoreKeyword | Self::StoreNotes)
    }
}

/// Whether an address (host order) is on a LAN, or otherwise trusted, and so not held to the subnet rules.
pub type AddressTest = Arc<dyn Fn(u32) -> bool + Send + Sync>;

/// What the sender of a request tells the lookup.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Requested {
    Sent,
    /// The request counts as values received, as a buddy request does.
    Counted(u32),
    /// What was to be sent is gone: the lookup ends.
    Stop,
}

/// What a lookup sends.
pub trait Requests {
    /// Asks `contact` for `count` contacts closer to the target.
    fn ask(&mut self, target: UInt128, kind: Kind, contact: &Contact, count: u8);

    /// Sends the search or publish to a node close to the target.
    fn request(&mut self, target: UInt128, kind: Kind, contact: &Contact) -> Requested;
}

pub struct Lookup {
    target: UInt128,
    kind: Kind,
    lan: AddressTest,
    started: Instant,
    // Both by distance to the target.
    possible: BTreeMap<UInt128, Contact>,
    tried: BTreeMap<UInt128, Contact>,
    responded: HashSet<UInt128>,
    // When each node was asked, by distance, until it answers.
    asked_at: HashMap<UInt128, Instant>,
    requested: HashSet<UInt128>,
    // Addresses sent the search or publish: only they may answer it.
    requested_ips: HashSet<u32>,
    // By distance: the addresses of the nodes that told of each contact, and the contacts of our own table.
    referrers: HashMap<UInt128, HashSet<u32>>,
    from_table: HashSet<UInt128>,
    // Contacts two nodes placed at different addresses: one of them lies, and neither is believed.
    disputed: HashSet<UInt128>,
    last_answer: Instant,
    answers: u32,
    load_sum: u64,
    loads: u64,
    re_asked: Option<Contact>,
    stopped: bool,
    stopping: bool,
    stop_at: Option<Instant>,
}

impl Lookup {
    /// A lookup towards `target`; `lan` tells the addresses not held to the subnet rule.
    pub fn new(target: UInt128, kind: Kind, lan: AddressTest, now: Instant) -> Self {
        Self {
            target,
            kind,
            lan,
            started: now,
            possible: BTreeMap::new(),
            tried: BTreeMap::new(),
            responded: HashSet::new(),
            asked_at: HashMap::new(),
            requested: HashSet::new(),
            requested_ips: HashSet::new(),
            referrers: HashMap::new(),
            from_table: HashSet::new(),
            disputed: HashSet::new(),
            last_answer: now,
            answers: 0,
            load_sum: 0,
            loads: 0,
            re_asked: None,
            stopped: false,
            stopping: false,
            stop_at: None,
        }
    }

    pub fn target(&self) -> UInt128 {
        self.target
    }

    pub fn kind(&self) -> Kind {
        self.kind
    }

    pub fn is_stopped(&self) -> bool {
        self.stopped
    }

    /// Ends the lookup at once.
    pub fn stop(&mut self) {
        self.stopped = true;
    }

    /// Whether the node at `ip` was sent the search or publish.
    pub fn requested_from(&self, ip: u32) -> bool {
        self.requested_ips.contains(&ip)
    }

    /// Values (search results or publish answers) received so far.
    pub fn answers(&self) -> u32 {
        self.answers
    }

    /// A node that stores what this lookup publishes reported its load, 0 to 100.
    pub fn load_reported(&mut self, load: u8) {
        self.load_sum = self.load_sum.saturating_add(u64::from(load));
        self.loads = self.loads.saturating_add(1);
    }

    /// The mean of the loads reported, 0 when none was.
    pub fn load(&self) -> u8 {
        self.load_sum.checked_div(self.loads).and_then(|mean| u8::try_from(mean).ok()).unwrap_or(0)
    }

    /// Starts with the contacts of the routing table closest to the target.
    pub fn start(&mut self, table: &RoutingTable, requests: &mut impl Requests) {
        for contact in table.closest(self.target, kad_contact::NEW, START_CONTACTS) {
            let distance = contact.id() ^ self.target;
            self.possible.insert(distance, contact.message());
            self.from_table.insert(distance);
        }
        let first = if self.kind == Kind::Node { 1 } else { ALPHA };
        let possible: Vec<Contact> = self.possible.values().copied().collect();
        for contact in possible {
            if self.tried.len() >= first {
                break;
            }
            self.ask(&contact, self.kind.count(), self.started, requests);
        }
        if self.tried.is_empty() {
            self.stopped = true;
        }
    }

    /// Whether the node at `ip:port` is one we asked and whose answer we wait for.
    pub fn asked(&self, ip: u32, port: u16) -> bool {
        self.asked_id(ip, port).is_some()
    }

    /// The id of the node we asked at `ip:port`, as the contact that told us of it gave it.
    pub fn asked_id(&self, ip: u32, port: u16) -> Option<UInt128> {
        self.tried.values().find(|contact| contact.ip == ip && contact.udp_port == port).map(|contact| contact.id)
    }

    /// How many contacts the node we asked at `ip:port` may send.
    pub fn expected_contacts(&self, ip: u32, port: u16) -> u8 {
        if self.re_asked.is_some_and(|again| again.ip == ip && again.udp_port == port) {
            RE_ASK_COUNT
        } else {
            self.kind.count()
        }
    }

    /// Contacts sent by a node we asked.
    pub fn answered(
        &mut self,
        ip: u32,
        port: u16,
        contacts: &[Contact],
        now: Instant,
        times: &mut ResponseTimes,
        requests: &mut impl Requests,
    ) {
        let Some(responder) = self.tried.values().find(|c| c.ip == ip && c.udp_port == port).copied() else {
            return;
        };
        if self.stopped || contacts.len() > usize::from(self.expected_contacts(ip, port)) {
            return;
        }
        self.last_answer = now;
        self.responded.insert(responder.id);
        if let Some(asked) = self.asked_at.remove(&(responder.id ^ self.target)) {
            times.answered(ip, now.saturating_duration_since(asked));
        }
        if self.kind == Kind::Node {
            self.stopped = true;
            return;
        }
        if self.stopping {
            return;
        }
        let responder_distance = responder.id ^ self.target;
        // The responder may not send itself, and its own /24 counts against the limit.
        let mut ips = HashSet::from([responder.ip]);
        let mut subnets: HashMap<u32, u32> = HashMap::from([(responder.ip & SUBNET, 1)]);
        for contact in contacts {
            let distance = contact.id ^ self.target;
            let known = self.tried.get(&distance).or_else(|| self.possible.get(&distance)).copied();
            if known.is_some() || self.disputed.contains(&distance) || distance == responder_distance {
                if let Some(known) = known
                    && distance != responder_distance
                {
                    self.referred(distance, known, contact, responder.ip);
                }
                continue;
            }
            if !ips.insert(contact.ip) {
                continue;
            }
            if !(self.lan)(contact.ip) {
                let count = subnets.entry(contact.ip & SUBNET).or_insert(0);
                *count = count.saturating_add(1);
                if *count > PER_SUBNET {
                    continue;
                }
            }
            self.possible.insert(distance, *contact);
            self.referrers.entry(distance).or_default().insert(responder.ip);
            if distance < responder_distance && self.is_among_best(distance) {
                self.ask(contact, self.kind.count(), now, requests);
            }
        }
    }

    // Another node told of a contact we know: at the same address it vouches for it; elsewhere, unless our own table
    // has it, the contact is believed no more.
    fn referred(&mut self, distance: UInt128, known: Contact, told: &Contact, referrer: u32) {
        if known.ip == told.ip && known.udp_port == told.udp_port {
            self.referrers.entry(distance).or_default().insert(referrer);
        } else if !self.from_table.contains(&distance) {
            self.disputed.insert(distance);
            self.possible.remove(&distance);
        }
    }

    /// Values (results or publish answers) for this lookup arrived; with enough of them it stops sending.
    pub fn value_received(&mut self, count: u32, now: Instant) {
        self.answers = self.answers.saturating_add(count);
        let most = self.kind.max_answers();
        if most > 0 && self.answers >= most {
            self.prepare_to_stop(now);
        }
    }

    fn prepare_to_stop(&mut self, now: Instant) {
        if !self.stopping {
            self.stopping = true;
            self.stop_at = now.checked_add(LATE_ANSWER);
        }
    }

    /// Runs every second: moves on when the lookup stalls, and ends it when its time is up.
    pub fn tick(&mut self, now: Instant, times: &ResponseTimes, requests: &mut impl Requests) {
        if self.stopped {
            return;
        }
        let age = now.saturating_duration_since(self.started);
        let lifetime = self.kind.lifetime();
        if age > lifetime {
            self.stopped = true;
            return;
        }
        if self.kind.sends_value() && !self.stopping && age >= lifetime.saturating_sub(LAST_SENDING) {
            self.request_waiting(now, requests);
            self.prepare_to_stop(now);
        }
        if self.stopping {
            self.stopped = self.stop_at.is_none_or(|stop_at| now >= stop_at);
            return;
        }
        let stall = if self.kind.stores() { STALL } else { times.wait() };
        if now.saturating_duration_since(self.last_answer) < stall {
            return;
        }
        if self.nothing_left_to_ask() {
            if self.kind.sends_value() {
                self.request_waiting(now, requests);
                self.prepare_to_stop(now);
            } else {
                self.stopped = true;
            }
            return;
        }
        if self.kind.count() == FIND_VALUE && self.re_asked.is_none() && self.tried.len() >= 3 * usize::from(FIND_VALUE)
        {
            self.re_ask_if_closest_silent(requests);
        }
        let possible: Vec<(UInt128, Contact)> = self.possible.iter().map(|(d, c)| (*d, *c)).collect();
        for (distance, contact) in possible {
            if !self.tried.contains_key(&distance) {
                self.ask(&contact, self.kind.count(), now, requests);
                return;
            }
            if self.responded.contains(&contact.id) {
                if self.is_waiting(distance, &contact) {
                    continue;
                }
                if self.kind.sends_value() && distance.top32() <= TOLERANCE && !self.requested.contains(&contact.id) {
                    self.request(&contact, now, requests);
                    if self.stopped {
                        return;
                    }
                }
                self.possible.remove(&distance);
            }
        }
        if !self.kind.sends_value() {
            self.stopped = self.nothing_left_to_ask();
        }
    }

    // Asked nodes that stay silent are kept for a late answer, and nodes only one other knew of for the end; neither
    // brings the lookup further.
    fn nothing_left_to_ask(&self) -> bool {
        self.possible.iter().all(|(distance, contact)| {
            self.tried.contains_key(distance)
                && !(self.responded.contains(&contact.id) && !self.is_waiting(*distance, contact))
        })
    }

    // A node close enough for the request that only one other knew of waits for a second one, or the end.
    fn is_waiting(&self, distance: UInt128, contact: &Contact) -> bool {
        self.kind.sends_value()
            && distance.top32() <= TOLERANCE
            && !self.requested.contains(&contact.id)
            && !self.from_table.contains(&distance)
            && !(self.lan)(contact.ip)
            && self.referrers.get(&distance).map_or(0, HashSet::len) < VOUCHERS
    }

    // Before the lookup stops sending, the nodes still waiting get the request, closest first.
    fn request_waiting(&mut self, now: Instant, requests: &mut impl Requests) {
        let possible: Vec<(UInt128, Contact)> = self.possible.iter().map(|(d, c)| (*d, *c)).collect();
        for (distance, contact) in possible {
            if self.responded.contains(&contact.id) && self.is_waiting(distance, &contact) {
                self.request(&contact, now, requests);
                if self.stopped {
                    return;
                }
            }
        }
    }

    fn request(&mut self, contact: &Contact, now: Instant, requests: &mut impl Requests) {
        self.requested.insert(contact.id);
        self.requested_ips.insert(contact.ip);
        match requests.request(self.target, self.kind, contact) {
            Requested::Sent => {}
            Requested::Counted(count) => self.value_received(count, now),
            Requested::Stop => self.stopped = true,
        }
    }

    // A search whose two closest asked nodes are silent asks the closest one that answered for more contacts.
    fn re_ask_if_closest_silent(&mut self, requests: &mut impl Requests) {
        let Some((seen, again)) =
            self.tried.values().enumerate().find(|(_, contact)| self.responded.contains(&contact.id))
        else {
            return;
        };
        if seen < usize::from(FIND_VALUE) {
            return;
        }
        let again = *again;
        self.re_asked = Some(again);
        requests.ask(self.target, self.kind, &again, RE_ASK_COUNT);
    }

    fn is_among_best(&self, distance: UInt128) -> bool {
        self.possible.range(..distance).take(ALPHA).count() < ALPHA
    }

    fn ask(&mut self, contact: &Contact, count: u8, now: Instant, requests: &mut impl Requests) {
        let distance = contact.id ^ self.target;
        self.tried.insert(distance, *contact);
        self.asked_at.insert(distance, now);
        requests.ask(self.target, self.kind, contact, count);
    }
}

impl fmt::Debug for Lookup {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Lookup")
            .field("target", &self.target)
            .field("kind", &self.kind)
            .field("tried", &self.tried.len())
            .field("answers", &self.answers)
            .field("stopped", &self.stopped)
            .finish_non_exhaustive()
    }
}
