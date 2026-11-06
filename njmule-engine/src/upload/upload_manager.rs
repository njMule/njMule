// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The clients that download from us: their sessions, the upload slots and the queue waiting for them, ordered by how
//! long each waited, its credits and the priority of its file. The slots share the upload budget in turn; a slot goes
//! back to the queue after about a part while others wait, or when its client leaves it idle. Bans, calling clients
//! back, UDP re-asks, source exchange and partial files wait for their ports.

use std::collections::{HashMap, VecDeque};
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::time::{Duration, Instant};

use indexmap::IndexMap;
use tokio::sync::mpsc::UnboundedSender;

use crate::api::upload_priority::UploadPriority;
use crate::files::hash::ed2k;
use crate::files::shared_files::SharedFile;
use crate::peer::peer_link::{LinkHandle, LinkId};
use crate::protocol::ed2k::client_messages::PartStatus;
use crate::protocol::ed2k::client_reasks::{self, Ack};
use crate::protocol::ed2k::hello::{self, Hello};
use crate::protocol::packet::Packet;
use crate::upload::blocks;
use crate::upload::credits::Credits;
use crate::upload::request_tracker::{Key, RequestTracker};
use crate::upload::upload_session::{Block, Request, UploadSession, Visible};
use crate::upload::uploadable::Uploadable;
use crate::util::ipv6;
use crate::util::rate_meter::RateMeter;

/// Payload a slot gets before the client waits again, while others wait.
pub const SESSION_LIMIT: u64 = ed2k::PARTSIZE + 20_480;
/// Clients asking again for a file sooner than this are counted; at [`BAD_LIMIT`] they are banned.
pub const MIN_REQUEST_TIME: Duration = Duration::from_secs(600);
pub const BAD_LIMIT: u32 = 4;
pub const BAN_TIME: Duration = Duration::from_secs(2 * 3600);
/// Slots a client leaves without asking for data within [`IDLE_SLOTS_WINDOW`], at which it is banned.
pub const IDLE_SLOTS_LIMIT: u32 = 8;
pub const IDLE_SLOTS_WINDOW: Duration = Duration::from_secs(4 * 3600);
// Connections from an address dropped for asking for files we do not have, at which it is banned.
const UNKNOWN_FILES_DROPS: u32 = 2;
// A client not in the queue that asks over UDP is told the queue is full this close to its size.
const QUEUE_FULL_MARGIN: usize = 50;
// How often the request histories are purged.
const REQUESTS_PURGE: Duration = Duration::from_secs(60);
/// A waiting client that does not ask again for this long is forgotten.
pub const PURGE_AFTER: Duration = Duration::from_secs(3600);
/// Clients of one address that may wait at once.
pub const MAX_WAITING_PER_IP: usize = 3;
pub const MIN_SLOTS: usize = 2;
pub const MAX_SLOTS: usize = 100;
/// Slots open at once without an upload limit.
pub const UNLIMITED_SLOTS: usize = 10;
/// How often the queue is served.
pub const TICK: Duration = Duration::from_millis(100);
/// While others wait, a slot goes back to the queue when its client asks for nothing this long.
pub const FIRST_REQUEST_WAIT: Duration = Duration::from_secs(15);
pub const IDLE_SLOT: Duration = Duration::from_secs(30);
/// File data a slot may have waiting in its connection before it gets more: a second's worth at its rate, at least
/// this. A client that reads slowly holds back only its own data, and the budget goes to the others.
pub const MIN_QUEUED: u64 = ed2k::EMBLOCKSIZE;
// Well below what a connection holds before it drops a peer that does not read.
const MAX_QUEUED: u64 = 4 << 20;
const WAITING_TIMEOUT: Duration = Duration::from_secs(40);
// Throttled slots may send nothing for a while; the client keeps its connection.
const SLOT_TIMEOUT: Duration = Duration::from_secs(300);
const CREDITS_SAVE: Duration = Duration::from_secs(13 * 60);
const RATE_SECONDS: usize = 5;
// Protocol byte, length and opcode before each payload.
const HEADER: u64 = 6;
const MIN_COMPRESSIBLE: u64 = 32;

/// What the uploads need from the preferences: the upload limit, 0 for none, how many clients may wait, and whether
/// the clients we call are reached obfuscated when they take it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Settings {
    pub max_upload_kibps: u32,
    pub queue_size: usize,
    pub obfuscation: bool,
}

/// A client whose turn came while it was not connected: we call it, obfuscated with its user hash when it takes it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Call {
    pub user_hash: [u8; 16],
    pub address: SocketAddr,
    pub obfuscate_for: Option<[u8; 16]>,
}

/// A block read from its file at `path`, or why it could not be.
#[derive(Debug)]
pub struct BlockRead {
    pub link: LinkId,
    pub block: Block,
    pub path: PathBuf,
    pub packets: io::Result<Vec<Packet>>,
}

/// What a UDP re-ask is answered with, and the user hash that obfuscates the answer, when it is known.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReaskAnswer {
    pub packet: Packet,
    pub obfuscate_for: Option<[u8; 16]>,
}

/// Where the requests by name, the slots and the bytes sent of each file are counted.
pub trait FileCounts: Send {
    fn asked(&mut self, file: &[u8; 16]);
    fn accepted(&mut self, file: &[u8; 16]);
    fn sent(&mut self, file: &[u8; 16], bytes: u64);
}

/// A banned address, for IPv6 its /64, and the time left.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Ban {
    pub address: IpAddr,
    pub left: Duration,
}

/// A client of ours: waiting, or uploading with what it got in this slot.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UploadState {
    pub user_hash: [u8; 16],
    pub file: [u8; 16],
    /// None while it waits.
    pub sent: Option<u64>,
}

type Priorities = Box<dyn Fn(&[u8; 16]) -> UploadPriority + Send>;

/// Our complete file of a hash.
pub type Find<'a> = &'a dyn Fn(&[u8; 16]) -> Option<SharedFile>;

struct Client {
    user_hash: [u8; 16],
    ip: IpAddr,
    // The port it listens on, which tells its program apart from others at the address.
    port: u16,
    low_id: bool,
    // Whether it takes obfuscated connections.
    crypt: bool,
    // Where it takes UDP re-asks from, and their version.
    udp_port: u16,
    udp_version: u32,
    // We are calling it for its slot.
    connecting: bool,
    // A LowID we cannot call whose turn came while it was away: it gets a slot when it asks again.
    next_connect: bool,
    file: [u8; 16],
    // The connection it waits on.
    link: Option<LinkId>,
    wait_start: Instant,
    // When it was last heard of, to forget it when it stops asking.
    last_ask: Instant,
    // Whether credits applied when it was last connected.
    trusted: bool,
}

// A packet to send and the file bytes it completes.
struct Item {
    packet: Packet,
    payload: u64,
}

struct Slot {
    client: Client,
    link: LinkId,
    out: VecDeque<Item>,
    sent: u64,
    rate: Option<RateMeter>,
    reading: bool,
    // Whether the client asked for a block we have, and since when it has nothing to send.
    requested: bool,
    idle_since: Instant,
}

pub struct UploadManager {
    sessions: HashMap<LinkId, UploadSession>,
    // The clients waiting for a slot, by user hash.
    waiting: IndexMap<[u8; 16], Client>,
    slots: Vec<Slot>,
    settings: Settings,
    // Bytes the slots may still send in this tick; u64::MAX without a limit.
    budget: u64,
    // The slot served first in the next round.
    turn: usize,
    reads: UnboundedSender<BlockRead>,
    credits: Credits,
    priorities: Priorities,
    next_credits_save: Option<Instant>,
    // The start of the meters' clock.
    epoch: Instant,
    // Payload bytes sent in this session.
    uploaded: u64,
    // Banned addresses, IPv6 ones by /64, until when.
    bans: HashMap<IpAddr, Instant>,
    requests: RequestTracker,
    next_purge: Option<Instant>,
    // The clients to call, for the network.
    calls: Vec<Call>,
    counts: Option<Box<dyn FileCounts>>,
}

impl UploadManager {
    /// Blocks read go to `reads`, for [`Self::block_read`]; `credits` order the queue and count what is sent.
    pub fn new(reads: UnboundedSender<BlockRead>, credits: Credits, settings: Settings) -> Self {
        Self {
            sessions: HashMap::new(),
            waiting: IndexMap::new(),
            slots: Vec::new(),
            settings,
            budget: u64::MAX,
            turn: 0,
            reads,
            credits,
            priorities: Box::new(|_| UploadPriority::Auto),
            next_credits_save: None,
            epoch: Instant::now(),
            uploaded: 0,
            bans: HashMap::new(),
            requests: RequestTracker::new(MIN_REQUEST_TIME, IDLE_SLOTS_WINDOW),
            next_purge: None,
            calls: Vec::new(),
            counts: None,
        }
    }

    /// Where the upload priority of a shared file comes from; without it every file is [`UploadPriority::Auto`].
    pub fn set_priorities(&mut self, priorities: impl Fn(&[u8; 16]) -> UploadPriority + Send + 'static) {
        self.priorities = Box::new(priorities);
    }

    /// Counts the requests by name, the slots and the bytes sent of each file in `counts`.
    pub fn count_in(&mut self, counts: Box<dyn FileCounts>) {
        self.counts = Some(counts);
    }

    /// A client at `ip` asked again over UDP from `port`. One waiting for the file is told its place, with the parts we
    /// have to clients of UDP version 4 and up; anyone is told when we do not share the file, or when the queue is about
    /// full. A client we cannot tell apart from others at its address, waiting for another file or due for a slot it
    /// can only get over TCP is answered nothing, and so asks over TCP.
    pub fn reasked(
        &mut self,
        ip: IpAddr,
        port: u16,
        ping: &Packet,
        find: Find<'_>,
        now: Instant,
    ) -> Option<ReaskAnswer> {
        if self.is_banned(ip, now) {
            return None;
        }
        let hash = client_reasks::Ping::file(ping).ok()?;
        let waiting = self.waiting_at(ip, port);
        let key = waiting.and_then(|user_hash| self.waiting.get(&user_hash)).filter(|client| client.crypt);
        let key = key.map(|client| client.user_hash);
        if find(&hash).is_none() {
            return Some(ReaskAnswer { packet: client_reasks::file_not_found(), obfuscate_for: key });
        }
        let Some(user_hash) = waiting else {
            // Its user hash unknown, the answer goes plain.
            let full = self.waiting.len().saturating_add(QUEUE_FULL_MARGIN) > self.settings.queue_size;
            return full.then(|| ReaskAnswer { packet: client_reasks::queue_full(), obfuscate_for: None });
        };
        let client = self.waiting.get(&user_hash)?;
        if client.file != hash || client.next_connect {
            return None;
        }
        let (client_port, version) = (client.port, client.udp_version);
        if self.requests.asked(Key { ip, port: client_port }, hash, now) >= BAD_LIMIT {
            self.ban(ip, now, "asked again too often");
            return None;
        }
        if let Some(client) = self.waiting.get_mut(&user_hash) {
            client.last_ask = now;
        }
        // Our shared files are complete.
        let parts = (version > 3).then_some(PartStatus::COMPLETE);
        let rank = u32::try_from(self.rank_of(&user_hash, now).unwrap_or(1)).unwrap_or(u32::MAX);
        let packet = Ack { parts, rank }.encode(version).ok()?;
        Some(ReaskAnswer { packet, obfuscate_for: key })
    }

    // The client waiting at this address and UDP port; none when it is not the only one there.
    fn waiting_at(&self, ip: IpAddr, port: u16) -> Option<[u8; 16]> {
        let mut found = None;
        for client in self.waiting.values().filter(|client| client.ip == ip && client.udp_port == port) {
            if found.is_some() {
                return None;
            }
            found = Some(client.user_hash);
        }
        found
    }

    pub fn credits(&self) -> &Credits {
        &self.credits
    }

    pub fn credits_mut(&mut self) -> &mut Credits {
        &mut self.credits
    }

    /// Clients waiting for a slot.
    pub fn waiting_count(&self) -> usize {
        self.waiting.len()
    }

    /// Clients with a slot.
    pub fn slot_count(&self) -> usize {
        self.slots.len()
    }

    /// Payload bytes sent in this session.
    pub fn uploaded(&self) -> u64 {
        self.uploaded
    }

    /// The banned addresses with the time left.
    pub fn bans(&self, now: Instant) -> Vec<Ban> {
        self.bans
            .iter()
            .map(|(address, until)| Ban { address: *address, left: until.saturating_duration_since(now) })
            .filter(|ban| !ban.left.is_zero())
            .collect()
    }

    /// Lifts the ban on `ip`, for IPv6 on its /64; false when it was not banned.
    pub fn unban(&mut self, ip: IpAddr) -> bool {
        self.bans.remove(&ipv6::network(ip)).is_some()
    }

    /// Whether `ip` is banned, with the rest of its /64 for IPv6.
    pub fn is_banned(&self, ip: IpAddr, now: Instant) -> bool {
        self.bans.get(&ipv6::network(ip)).is_some_and(|until| now < *until)
    }

    /// Bans `ip` for [`BAN_TIME`]. Clients waiting from it leave the queue; a slot it holds runs to its end, as other
    /// clients may share the address.
    pub fn ban(&mut self, ip: IpAddr, now: Instant, reason: &str) {
        tracing::debug!("Banning {ip}: {reason}");
        let banned = ipv6::network(ip);
        self.bans.insert(banned, now.checked_add(BAN_TIME).unwrap_or(now));
        let gone: Vec<[u8; 16]> = self
            .waiting
            .values()
            .filter(|client| ipv6::network(client.ip) == banned)
            .map(|client| client.user_hash)
            .collect();
        for user_hash in gone {
            if let Some(link) = self.waiting.shift_remove(&user_hash).and_then(|client| client.link)
                && !self.slots.iter().any(|slot| slot.link == link)
                && let Some(session) = self.sessions.get(&link)
            {
                session.link().close();
            }
        }
    }

    /// Ends the uploads to and drops the waiting clients at the addresses `refused` takes; answers how many.
    pub fn drop_clients(&mut self, refused: &dyn Fn(IpAddr) -> bool) -> usize {
        let mut dropped = 0usize;
        let gone: Vec<LinkId> =
            self.slots.iter().filter(|slot| refused(slot.client.ip)).map(|slot| slot.link).collect();
        for link in gone {
            self.slots.retain(|slot| slot.link != link);
            if let Some(session) = self.sessions.get(&link) {
                session.link().close();
            }
            dropped = dropped.saturating_add(1);
        }
        let waiting: Vec<[u8; 16]> =
            self.waiting.values().filter(|client| refused(client.ip)).map(|client| client.user_hash).collect();
        for user_hash in waiting {
            if let Some(link) = self.waiting.shift_remove(&user_hash).and_then(|client| client.link)
                && let Some(session) = self.sessions.get(&link)
            {
                session.link().close();
            }
            dropped = dropped.saturating_add(1);
        }
        dropped
    }

    /// Slots and waiting clients, for diagnostics.
    pub fn describe(&self) -> String {
        let called = self.waiting.values().filter(|client| client.next_connect).count();
        let mut text = format!("waiting {}, {called} called when they ask", self.waiting.len());
        for slot in &self.slots {
            let pending = self.sessions.get(&slot.link).is_some_and(UploadSession::has_pending_blocks);
            text.push_str(&format!(
                "\nslot out {}{}{} payload {}",
                slot.out.len(),
                if slot.reading { " reading" } else { "" },
                if pending { " pending" } else { "" },
                slot.sent
            ));
        }
        text
    }

    /// The clients to call for their slots, since asked last.
    pub fn take_calls(&mut self) -> Vec<Call> {
        std::mem::take(&mut self.calls)
    }

    /// A client we called answered our hello over `link`: its slot, when it is the client we called.
    pub fn called(&mut self, user_hash: [u8; 16], link: &LinkHandle, peer: &Hello, trusted: bool, now: Instant) {
        let Some(client) = self.waiting.get_mut(&user_hash) else {
            link.close();
            return;
        };
        client.connecting = false;
        if peer.user_hash != user_hash {
            tracing::debug!("{} answered our call with another user hash", link.remote());
            link.close();
            return;
        }
        let mut session = UploadSession::new(link.clone(), peer.clone());
        session.set_trusted(trusted);
        self.sessions.insert(link.id(), session);
        if let Some(mut client) = self.waiting.shift_remove(&user_hash) {
            client.link = Some(link.id());
            client.trusted = trusted;
            self.open(client, link.id(), now);
        }
    }

    /// A client we called could not be reached, or left before its hello: it leaves the queue.
    pub fn call_failed(&mut self, user_hash: [u8; 16], reason: &str) {
        if self.waiting.get(&user_hash).is_some_and(|client| client.connecting) {
            tracing::debug!("Cannot reach a client for its slot: {reason}");
            self.waiting.shift_remove(&user_hash);
        }
    }

    /// The clients with a slot, then the waiting ones from the best score down.
    pub fn uploads(&self, now: Instant) -> Vec<UploadState> {
        let slots = self.slots.iter().map(|slot| UploadState {
            user_hash: slot.client.user_hash,
            file: slot.client.file,
            sent: Some(slot.sent),
        });
        let scoring = Scoring::new(self, now);
        let mut waiting: Vec<(&Client, f64)> =
            self.waiting.values().map(|client| (client, scoring.of(client))).collect();
        waiting.sort_by(|a, b| b.1.total_cmp(&a.1));
        let waiting = waiting.into_iter().map(|(client, _)| UploadState {
            user_hash: client.user_hash,
            file: client.file,
            sent: None,
        });
        slots.chain(waiting).collect()
    }

    /// The place in the queue of the client waiting with `user_hash`.
    pub fn rank_of(&self, user_hash: &[u8; 16], now: Instant) -> Option<usize> {
        self.waiting.get(user_hash).map(|client| self.rank(client, now))
    }

    /// A packet of a link's upload side, whose client greeted us as `peer`; `trusted` when credits apply to it.
    #[allow(clippy::too_many_arguments)]
    pub fn received(
        &mut self,
        link: &LinkHandle,
        peer: &Hello,
        trusted: bool,
        packet: &Packet,
        ours: &Hello,
        find: Find<'_>,
        visible: Visible<'_>,
        now: Instant,
    ) {
        if !self.sessions.contains_key(&link.id()) && self.is_banned(link.remote().ip(), now) {
            return;
        }
        let session = self.sessions.entry(link.id()).or_insert_with(|| UploadSession::new(link.clone(), peer.clone()));
        session.set_trusted(trusted);
        match session.received(packet, ours, find, visible) {
            Ok(Some(Request::Upload(file))) => self.requested(link.id(), file, now),
            Ok(Some(Request::Named(file))) => {
                if let Some(counts) = &mut self.counts {
                    counts.asked(&file);
                }
            }
            Ok(Some(Request::Blocks)) => self.blocks_requested(link.id(), find, now),
            Ok(Some(Request::Stopped)) => self.slot_gone(link.id()),
            Ok(Some(Request::FileGone)) => {
                tracing::debug!("{} asked for blocks of a file we no longer share", link.remote());
                self.end_slot(link.id());
            }
            Ok(Some(Request::Misbehaved)) => {
                link.close();
                self.ban(link.remote().ip(), now, "changed its user hash");
            }
            Ok(Some(Request::UnknownFiles)) => {
                let ip = link.remote().ip();
                if self.requests.unknown_files(ip, now) >= UNKNOWN_FILES_DROPS {
                    self.ban(ip, now, "asked for files we do not have again and again");
                }
            }
            Ok(None) => {}
            Err(e) => {
                tracing::debug!("Bad packet from {}: {e}", link.remote());
                link.close();
                self.credits.save();
            }
        }
    }

    /// The connection of a link closed.
    pub fn closed(&mut self, link: LinkId) {
        let trusted = self.sessions.remove(&link).map(|session| session.is_trusted());
        self.slot_gone(link);
        for client in self.waiting.values_mut().filter(|client| client.link == Some(link)) {
            client.trusted = trusted.unwrap_or(client.trusted);
            client.link = None;
        }
    }

    /// Ends the uploads, saving the credits.
    pub fn close(&mut self) {
        for slot in self.slots.drain(..) {
            if let Some(session) = self.sessions.get(&slot.link) {
                session.link().close();
            }
        }
        self.waiting.clear();
        self.credits.save();
    }

    /// One step of the queue: the budget renewed, slots fed, ended and filled.
    pub fn tick(&mut self, settings: Settings, find: Find<'_>, now: Instant) {
        self.settings = settings;
        let limit = u64::from(settings.max_upload_kibps);
        // Nothing carries over from unlimited: the sum would overflow and stop the uploads.
        let carried = if self.budget == u64::MAX { 0 } else { self.budget };
        self.budget = if limit == 0 {
            u64::MAX
        } else {
            carried.saturating_add(limit.saturating_mul(1024) / 10).min(limit.saturating_mul(1024))
        };
        self.bans.retain(|_, until| now < *until);
        self.waiting.retain(|_, client| {
            now.saturating_duration_since(client.last_ask) <= PURGE_AFTER && find(&client.file).is_some()
        });
        let waiting = !self.waiting.is_empty();
        let mut requeued = Vec::new();
        let mut idle = Vec::new();
        for at in 0..self.slots.len() {
            self.read(at, find);
            let Some(slot) = self.slots.get_mut(at) else {
                continue;
            };
            let busy = slot.reading
                || !slot.out.is_empty()
                || self
                    .sessions
                    .get(&slot.link)
                    .is_some_and(|session| session.has_pending_blocks() || session.link().queued_data() > 0);
            let wait = if slot.requested { IDLE_SLOT } else { FIRST_REQUEST_WAIT };
            if slot.sent >= SESSION_LIMIT && waiting && slot.out.is_empty() && !slot.reading {
                requeued.push(slot.link);
            } else if busy {
                slot.idle_since = now;
            } else if waiting && now.saturating_duration_since(slot.idle_since) > wait {
                // A client that holds a slot without asking for data we have keeps others waiting.
                tracing::debug!("A client left its upload slot idle");
                requeued.push(slot.link);
                if !slot.requested {
                    idle.push(Key { ip: slot.client.ip, port: slot.client.port });
                }
            }
        }
        for link in requeued {
            self.requeue(link, now);
        }
        // Taking slots and asking for nothing, over and over, keeps others from them: the client is banned.
        for key in idle {
            if self.requests.idle_slot(key, now) >= IDLE_SLOTS_LIMIT {
                self.ban(key.ip, now, "took slots and asked for nothing");
            }
        }
        let purge_at = *self.next_purge.get_or_insert(now);
        if now >= purge_at {
            self.next_purge = now.checked_add(REQUESTS_PURGE);
            self.requests.purge(now);
        }
        self.send(now);
        self.fill_slots(now);
        let save_at = *self.next_credits_save.get_or_insert(now.checked_add(CREDITS_SAVE).unwrap_or(now));
        if now >= save_at {
            self.next_credits_save = now.checked_add(CREDITS_SAVE);
            self.credits.save();
        }
    }

    /// A block read for a slot: its packets go out within what the budget of this tick has left. A file renamed, or a
    /// download completed, since the block was asked for is read where it is now.
    pub fn block_read(&mut self, read: BlockRead, find: Find<'_>, now: Instant) {
        let Some(at) = self.slots.iter().position(|slot| slot.link == read.link) else {
            return;
        };
        let packets = match read.packets {
            Ok(packets) => packets,
            Err(e) => {
                let moved = find(&read.block.file)
                    .map(|file| Uploadable::of(&file))
                    .filter(|file| file.data != read.path && file.has(read.block.start, read.block.end));
                match moved {
                    Some(file) => self.spawn_read(at, file, read.block),
                    None => {
                        tracing::warn!("Cannot read a block for an upload: {e}");
                        self.end_slot(read.link);
                    }
                }
                return;
            }
        };
        let Some(slot) = self.slots.get_mut(at) else {
            return;
        };
        slot.reading = false;
        let length = read.block.end.saturating_sub(read.block.start);
        let last = packets.len().saturating_sub(1);
        for (at, packet) in packets.into_iter().enumerate() {
            slot.out.push_back(Item { packet, payload: if at == last { length } else { 0 } });
        }
        // At once: waiting for the next tick would cap each slot at a block a tick, whatever the line.
        self.send(now);
    }

    // The client asks for an upload of `file`: a slot when one is free and nobody waits, else a place in the queue.
    fn requested(&mut self, link: LinkId, file: [u8; 16], now: Instant) {
        let Some(session) = self.sessions.get(&link) else {
            return;
        };
        let (user_hash, ip, trusted) = (session.user_hash(), session.link().remote().ip(), session.is_trusted());
        let port = session.peer().tcp_port;
        if self.is_banned(ip, now) {
            return;
        }
        let holds_slot = self.slots.iter().any(|slot| slot.client.user_hash == user_hash);
        if self.waiting.get(&user_hash).is_some_and(|client| client.ip != ip)
            || self.slots.iter().any(|slot| slot.client.user_hash == user_hash && slot.client.ip != ip)
        {
            tracing::debug!("{ip} claims the user hash of a client from elsewhere");
            return;
        }
        // A client asking for the same file again too soon, with no slower ask between, is banned, also when it left
        // the queue meanwhile; a slot it holds runs on.
        if self.requests.asked(Key { ip, port }, file, now) >= BAD_LIMIT {
            self.ban(ip, now, "asked again too often");
            if !holds_slot && let Some(session) = self.sessions.get(&link) {
                session.link().close();
            }
            return;
        }
        if let Some(slot) = self.slots.iter_mut().find(|slot| slot.client.user_hash == user_hash) {
            // Already uploading to it, maybe over a new connection or for another file.
            slot.link = link;
            slot.client.file = file;
            if let Some(session) = self.sessions.get_mut(&link) {
                session.link().set_timeout(SLOT_TIMEOUT);
                session.accept();
            }
            return;
        }
        if let Some(client) = self.waiting.get_mut(&user_hash) {
            client.last_ask = now;
            client.link = Some(link);
            client.file = file;
            client.trusted = trusted;
            if client.next_connect && self.can_open_slot(true) {
                if let Some(client) = self.waiting.shift_remove(&user_hash) {
                    self.open(client, link, now);
                }
            } else {
                self.tell_rank(link, &user_hash, now);
            }
            return;
        }
        let same_ip = self.waiting.values().filter(|client| client.ip == ip).count();
        if self.waiting.len() >= self.settings.queue_size || same_ip >= MAX_WAITING_PER_IP {
            return;
        }
        let low_id = session.peer().client_id != 0 && session.peer().client_id < 0x0100_0000;
        let crypt = session.peer().misc_options2() & hello::MISC2_SUPPORTS_CRYPT != 0;
        let (udp_port, udp_version) = (session.peer().udp_port(), session.peer().udp_version());
        let client = Client {
            user_hash,
            ip,
            port,
            low_id,
            crypt,
            udp_port,
            udp_version,
            connecting: false,
            next_connect: false,
            file,
            link: Some(link),
            wait_start: now,
            last_ask: now,
            trusted,
        };
        if self.waiting.is_empty() && self.can_open_slot(false) {
            self.open(client, link, now);
            return;
        }
        self.waiting.insert(user_hash, client);
        self.tell_rank(link, &user_hash, now);
    }

    fn tell_rank(&self, link: LinkId, user_hash: &[u8; 16], now: Instant) {
        let rank = self.rank_of(user_hash, now).unwrap_or(1);
        if let Some(session) = self.sessions.get(&link) {
            session.send_rank(u32::try_from(rank).unwrap_or(u32::MAX));
        }
    }

    fn blocks_requested(&mut self, link: LinkId, find: Find<'_>, now: Instant) {
        let Some(at) = self.slots.iter().position(|slot| slot.link == link) else {
            return;
        };
        if let Some(slot) = self.slots.get_mut(at) {
            slot.requested = true;
            slot.idle_since = now;
        }
        self.read(at, find);
    }

    // The slot of the link ends: the client stopped, or left.
    fn slot_gone(&mut self, link: LinkId) {
        if let Some(at) = self.slots.iter().position(|slot| slot.link == link) {
            let slot = self.slots.remove(at);
            self.slot_ended(&slot);
        }
    }

    // A slot that sent data forgives the idle slots of its client.
    fn slot_ended(&mut self, slot: &Slot) {
        if slot.sent > 0 {
            self.requests.productive(Key { ip: slot.client.ip, port: slot.client.port });
        }
    }

    // The next block of a slot is read, and compressed, off the engine's task.
    fn read(&mut self, at: usize, find: Find<'_>) {
        let Some(slot) = self.slots.get_mut(at) else {
            return;
        };
        let Some(session) = self.sessions.get_mut(&slot.link) else {
            return;
        };
        if slot.reading || !slot.out.is_empty() || !session.link().is_open() {
            return;
        }
        let Some(block) = session.next_block() else {
            return;
        };
        let Some(file) = find(&block.file).map(|file| Uploadable::of(&file)) else {
            // Its file is no longer shared: the slot ends.
            let link = slot.link;
            tracing::debug!("The file of an upload slot is gone");
            self.end_slot(link);
            return;
        };
        self.spawn_read(at, file, block);
    }

    // The block is read, and compressed, off the engine's task.
    fn spawn_read(&mut self, at: usize, file: Uploadable, block: Block) {
        let Some(slot) = self.slots.get_mut(at) else {
            return;
        };
        let Some(session) = self.sessions.get(&slot.link) else {
            return;
        };
        slot.client.file = file.hash;
        slot.reading = true;
        let length = block.end.saturating_sub(block.start);
        // A client tells in its hello which version of compression it takes.
        let compress =
            file.compressible() && length >= MIN_COMPRESSIBLE && session.peer().misc_options1() >> 20 & 0x0F != 0;
        let (link, reads) = (slot.link, self.reads.clone());
        tokio::task::spawn_blocking(move || {
            let packets = usize::try_from(length)
                .map_err(io::Error::other)
                .and_then(|length| blocks::read_packets(&file.data, file.hash, block.start, length, compress));
            let _ = reads.send(BlockRead { link, block, path: file.data, packets });
        });
    }

    // The slot ends and its connection closes, as when its file cannot be read.
    fn end_slot(&mut self, link: LinkId) {
        self.slot_gone(link);
        if let Some(session) = self.sessions.get(&link) {
            session.link().close();
        }
    }

    // One data packet per slot in turn, while the budget lasts: every slot whose client reads what it gets has an equal
    // share. The budget is spent on what goes out, as a slot holds little more than it sends.
    fn send(&mut self, now: Instant) {
        let clock = self.clock(now);
        let mut progress = true;
        while self.budget > 0 && progress && !self.slots.is_empty() {
            progress = false;
            let count = self.slots.len();
            for step in 0..count {
                if self.budget == 0 {
                    break;
                }
                let at = self.turn.saturating_add(step).checked_rem(count).unwrap_or(0);
                let Some(slot) = self.slots.get_mut(at) else {
                    continue;
                };
                let Some(session) = self.sessions.get(&slot.link) else {
                    continue;
                };
                let rate = slot.rate.as_mut().map_or(0, |rate| rate.rate(clock));
                if session.link().queued_data() >= rate.clamp(MIN_QUEUED, MAX_QUEUED) {
                    continue;
                }
                let Some(item) = slot.out.pop_front() else {
                    continue;
                };
                progress = true;
                let wire = u64::try_from(item.packet.length()).unwrap_or(u64::MAX).saturating_add(HEADER);
                if self.budget != u64::MAX {
                    self.budget = self.budget.saturating_sub(wire);
                }
                if session.link().send_data(item.packet) {
                    slot.sent = slot.sent.saturating_add(item.payload);
                    self.uploaded = self.uploaded.saturating_add(item.payload);
                    if let Some(counts) = &mut self.counts
                        && item.payload > 0
                    {
                        counts.sent(&slot.client.file, item.payload);
                    }
                    if let Some(rate) = &mut slot.rate {
                        rate.add(item.payload, clock);
                    }
                    if session.is_trusted() {
                        self.credits.uploaded(slot.client.user_hash, wire);
                    }
                }
            }
            self.turn = self.turn.saturating_add(1).checked_rem(self.slots.len()).unwrap_or(0);
        }
    }

    // About one part sent while others wait, or a slot left idle: the client waits again, from now.
    fn requeue(&mut self, link: LinkId, now: Instant) {
        let Some(at) = self.slots.iter().position(|slot| slot.link == link) else {
            return;
        };
        let slot = self.slots.remove(at);
        self.slot_ended(&slot);
        let mut client = slot.client;
        client.link = Some(link);
        client.wait_start = now;
        client.last_ask = now;
        let user_hash = client.user_hash;
        self.waiting.insert(user_hash, client);
        let rank = self.rank_of(&user_hash, now).unwrap_or(1);
        if let Some(session) = self.sessions.get_mut(&link) {
            session.end_slot(u32::try_from(rank).unwrap_or(u32::MAX));
            session.link().set_timeout(WAITING_TIMEOUT);
        }
    }

    // The best client we can reach gets the slot; a LowID that is not connected cannot be called, so when it would have
    // been the best it gets a slot when it asks again. A HighID that is not connected is called.
    fn fill_slots(&mut self, now: Instant) {
        while !self.waiting.is_empty() && self.can_open_slot(false) {
            let scoring = Scoring::new(self, now);
            let mut best: Option<([u8; 16], f64)> = None;
            let mut away: Option<([u8; 16], f64)> = None;
            for client in self.waiting.values().filter(|client| !client.connecting) {
                let score = scoring.of(client);
                let connected = client.link.is_some_and(|link| self.is_open(link));
                if !client.low_id || connected {
                    if best.is_none_or(|(_, top)| score > top) {
                        best = Some((client.user_hash, score));
                    }
                } else if !client.next_connect && away.is_none_or(|(_, top)| score > top) {
                    away = Some((client.user_hash, score));
                }
            }
            if let Some((user_hash, score)) = away
                && best.is_none_or(|(_, top)| score > top)
                && let Some(client) = self.waiting.get_mut(&user_hash)
            {
                client.next_connect = true;
            }
            let Some((user_hash, _)) = best else {
                return;
            };
            let connected =
                self.waiting.get(&user_hash).and_then(|client| client.link).filter(|link| self.is_open(*link));
            match connected {
                Some(link) => {
                    if let Some(client) = self.waiting.shift_remove(&user_hash) {
                        self.open(client, link, now);
                    }
                }
                None => {
                    self.call(&user_hash);
                    return;
                }
            }
        }
    }

    fn call(&mut self, user_hash: &[u8; 16]) {
        let obfuscation = self.settings.obfuscation;
        let Some(client) = self.waiting.get_mut(user_hash) else {
            return;
        };
        client.connecting = true;
        // Obfuscated only towards clients that said they take it.
        let obfuscate_for = (obfuscation && client.crypt).then_some(client.user_hash);
        self.calls.push(Call {
            user_hash: client.user_hash,
            address: SocketAddr::new(client.ip, client.port),
            obfuscate_for,
        });
    }

    fn open(&mut self, client: Client, link: LinkId, now: Instant) {
        let Some(session) = self.sessions.get_mut(&link) else {
            return;
        };
        session.link().set_timeout(SLOT_TIMEOUT);
        session.accept();
        tracing::debug!("Upload slot for {}", session.link().remote());
        if let Some(counts) = &mut self.counts {
            counts.accepted(&client.file);
        }
        self.slots.push(Slot {
            client,
            link,
            out: VecDeque::new(),
            sent: 0,
            rate: RateMeter::new(RATE_SECONDS),
            reading: false,
            requested: false,
            idle_since: now,
        });
    }

    // Clients being called count as slots; `extra` lets one more open for a firewalled client whose turn came.
    fn can_open_slot(&self, extra: bool) -> bool {
        let calling = self.waiting.values().filter(|client| client.connecting).count();
        let open = self.slots.len().saturating_add(calling);
        let extra = usize::from(extra);
        if open >= MAX_SLOTS {
            return false;
        }
        if open < MIN_SLOTS {
            return true;
        }
        let limit = u64::from(self.settings.max_upload_kibps);
        if limit == 0 {
            return open < UNLIMITED_SLOTS.saturating_add(extra);
        }
        let open_now = u64::try_from(open).unwrap_or(u64::MAX);
        let per_slot = if open <= 3 { 3_072 } else { open_now.saturating_mul(1_024).min(25_600) };
        let allowed =
            usize::try_from(limit.saturating_mul(1_024).checked_div(per_slot).unwrap_or(0)).unwrap_or(usize::MAX);
        open < allowed.max(MIN_SLOTS).saturating_add(extra)
    }

    fn is_open(&self, link: LinkId) -> bool {
        self.sessions.get(&link).is_some_and(|session| session.link().is_open())
    }

    fn rank(&self, client: &Client, now: Instant) -> usize {
        let scoring = Scoring::new(self, now);
        let score = scoring.of(client);
        let better = self
            .waiting
            .values()
            .filter(|other| other.user_hash != client.user_hash && scoring.of(other) > score)
            .count();
        better.saturating_add(1)
    }

    // Monotonic nanoseconds for the meters.
    fn clock(&self, now: Instant) -> i64 {
        i64::try_from(now.saturating_duration_since(self.epoch).as_nanos()).unwrap_or(i64::MAX)
    }
}

/// Scores at one moment: waiting time times credits times the factor of the file's priority. The factors, which count
/// the clients of each file, are worked out once, so a pass over the queue costs a score per client.
struct Scoring<'a> {
    manager: &'a UploadManager,
    now: Instant,
    requesters: HashMap<[u8; 16], usize>,
}

impl<'a> Scoring<'a> {
    fn new(manager: &'a UploadManager, now: Instant) -> Self {
        let mut requesters: HashMap<[u8; 16], usize> = HashMap::new();
        let files =
            manager.waiting.values().map(|client| client.file).chain(manager.slots.iter().map(|slot| slot.client.file));
        for file in files {
            let count = requesters.entry(file).or_default();
            *count = count.saturating_add(1);
        }
        Self { manager, now, requesters }
    }

    fn of(&self, client: &Client) -> f64 {
        let trusted = client
            .link
            .and_then(|link| self.manager.sessions.get(&link))
            .map_or(client.trusted, UploadSession::is_trusted);
        let waited = self.now.saturating_duration_since(client.wait_start).as_secs_f64();
        let ratio = self.manager.credits.ratio(&client.user_hash, trusted);
        waited * ratio * f64::from(self.factor(&client.file)) / 10.0
    }

    // The factor of a file's level, in tenths.
    fn factor(&self, file: &[u8; 16]) -> u8 {
        match (self.manager.priorities)(file) {
            UploadPriority::VeryLow => 2,
            UploadPriority::Low => 6,
            UploadPriority::Normal => 7,
            UploadPriority::High => 9,
            UploadPriority::Release => 18,
            // Files many clients want get a lower priority, so rare files spread first.
            UploadPriority::Auto => match self.requesters.get(file).copied().unwrap_or(0) {
                count if count > 20 => 6,
                count if count > 1 => 7,
                _ => 9,
            },
        }
    }
}
