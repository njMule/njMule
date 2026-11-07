// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The downloads and their sources: the clients known to have each file, one session at a time with each client, the
//! sources the servers find and the firewalled ones asked through the server to connect to us. Kad, the exchange of
//! sources between clients, asking another file of a client (A4AF) and the download limit wait for their ports.

use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use indexmap::IndexMap;
use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::sync::mpsc::UnboundedSender;

use crate::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus, SourceInfo, SourceState};
use crate::api::ed2k_link::{self, FileLink};
use crate::config::obfuscation_mode::ObfuscationMode;
use crate::download::download_queue::{self, DownloadQueue, QueueEvent};
use crate::download::download_session::{Context, DownloadSession, Note, Outcome, State};
use crate::files::hash::aich_recovery;
use crate::files::hash::aich_votes::AichVotes;
use crate::files::part_file::{self, PartFile, Status};
use crate::files::part_file_storage::DiskDone;
use crate::peer::peer_link::{LinkHandle, LinkId};
use crate::peer::peer_software;
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::ed2k::server_datagrams::SourceQuery;
use crate::protocol::ed2k::server_messages::{FoundSources, LOW_ID_LIMIT};
use crate::protocol::packet::Packet;
use crate::server::server_list;
use crate::util::{instants, ipv4};

/// A source that keeps us in its queue is asked again this often.
pub const REASK_INTERVAL: Duration = Duration::from_secs(29 * 60);
/// Sources ban clients that ask more often than this.
pub const MIN_REASK: Duration = Duration::from_secs(10 * 60);
/// The server is asked for the sources of each download this often.
pub const SERVER_SOURCES_INTERVAL: Duration = Duration::from_secs(20 * 60);
/// Source requests a server takes in a row; then it takes one more each [`SERVER_REQUEST_CREDIT`].
pub const SERVER_REQUEST_BURST: u32 = 15;
pub const SERVER_REQUEST_CREDIT: Duration = Duration::from_secs(20);
/// How long a corrupt part waits for its AICH recovery data before it is downloaded again whole.
pub const RECOVERY_WAIT: Duration = Duration::from_secs(30);
/// Firewalled sources asked for at once, and how long a callback is awaited.
pub const MAX_CALLBACKS: usize = 32;
pub const CALLBACK_WAIT: Duration = Duration::from_secs(60);
/// Sessions starting at once at most.
pub const MAX_STARTING: usize = 8;

const MAX_FAILURES: u32 = 3;
const RATE_WINDOW: usize = 5;
const CRYPT_SUPPORTED: u8 = 0x01;
const CRYPT_REQUIRED: u8 = 0x07;
const MAX_LINK_HOSTS: usize = 16;

/// The server connection, as far as the downloads use it.
pub trait ServerLink {
    /// Our client id on the server, zero when not connected.
    fn client_id(&self) -> u32;
    /// The server's address, zero when not connected.
    fn server_ip(&self) -> u32;
    /// Asks the connected server for the sources of a file; false without one.
    fn ask_sources(&mut self, file: &[u8; 16], size: u64) -> bool;
    /// Asks the server to have the LowID client `low_id` connect to us.
    fn request_callback(&mut self, low_id: u32) -> bool;
}

/// What the downloads need from the preferences.
#[derive(Debug, Clone)]
pub struct Settings {
    pub obfuscation: ObfuscationMode,
    pub max_sources_per_file: usize,
    pub listen_port: u16,
    /// Whether sources at LAN addresses the network gives are left out.
    pub filter_lan: bool,
}

/// What the downloads ask of the network side.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    /// Connect to a source, obfuscated for its user hash when given, and hand the link to
    /// [`DownloadManager::connected`].
    Connect { address: SocketAddr, obfuscate_for: Option<[u8; 16]> },
    /// Resolve the host names of link sources; each address goes to [`DownloadManager::resolved`].
    Resolve { file: [u8; 16], hosts: Vec<ed2k_link::Source> },
}

/// What the downloads tell the front ends.
#[derive(Debug)]
pub enum DownloadEvent {
    Added(DownloadInfo),
    /// At most once a second per download.
    Progress(DownloadInfo),
    /// The download is complete, its file now at `path`; `file` has its hashes.
    Completed {
        info: DownloadInfo,
        path: PathBuf,
        file: Box<PartFile>,
    },
    Failed {
        info: DownloadInfo,
        reason: String,
    },
    Removed([u8; 16]),
}

// A firewalled source asked through the server to connect to us.
struct Callback {
    file: [u8; 16],
    user_hash: Option<[u8; 16]>,
    since: Instant,
    requested: bool,
}

// A client known as a source of one or more downloads: one session at a time, for the download it is asked for.
struct Client {
    // Its downloads, in the order it was found for them.
    sources: Vec<[u8; 16]>,
    // The download it is asked for.
    current: [u8; 16],
    // The download the last file request went to: a slot the client offers later is for that file.
    requested: Option<[u8; 16]>,
    session: Option<DownloadSession>,
    user_hash: Option<[u8; 16]>,
    crypt_options: u8,
    // The id of a firewalled source on our server, reached only by callbacks; 0 otherwise.
    low_id: u32,
    next_ask: Instant,
    last_ask: Instant,
    asked: bool,
    failures: u32,
}

// A client as a source of one download.
#[derive(Default)]
struct Source {
    parts: Option<Vec<bool>>,
    // Our place in the client's queue for the file, as it last told; 0 for none.
    rank: u32,
    received: u64,
    reported_name: Option<String>,
}

struct Entry {
    sources: IndexMap<SocketAddr, Source>,
    window: [u64; RATE_WINDOW],
    slot: usize,
    received: u64,
    changed: bool,
    next_server_ask: Instant,
    // Corrupt parts waiting for a source able to send their AICH recovery data, and when each stops waiting.
    recovery_wanted: Vec<bool>,
    recovery_tried: Vec<bool>,
    holds: Vec<(usize, Instant)>,
    aich_votes: AichVotes,
}

impl Entry {
    fn new(parts: usize, now: Instant) -> Self {
        Self {
            sources: IndexMap::new(),
            window: [0; RATE_WINDOW],
            slot: 0,
            received: 0,
            changed: false,
            next_server_ask: now,
            recovery_wanted: vec![false; parts],
            recovery_tried: vec![false; parts],
            holds: Vec::new(),
            aich_votes: AichVotes::new(),
        }
    }

    fn rate(&self) -> u64 {
        self.window
            .iter()
            .fold(0u64, |sum, bytes| sum.saturating_add(*bytes))
            .checked_div(RATE_WINDOW as u64)
            .unwrap_or(0)
    }
}

pub struct DownloadManager {
    queue: DownloadQueue,
    entries: IndexMap<[u8; 16], Entry>,
    // The clients that are sources of any download, by the address they are reached at.
    clients: IndexMap<SocketAddr, Client>,
    // The link of each session, to its client.
    links: HashMap<LinkId, SocketAddr>,
    // Firewalled sources asked, through the server, to connect to us: by their LowID.
    callbacks: IndexMap<u32, Callback>,
    random: StdRng,
    // When the server will have credited back every source request sent.
    server_requests_due: Instant,
    actions: Vec<Action>,
    events: Vec<DownloadEvent>,
}

impl DownloadManager {
    /// Loads the downloads of `temp_dir`; finished files go to `incoming_dir`, downloads wait while the temp disk has
    /// less free space than `min_free`, disk work reports to `disk`.
    pub fn open(
        temp_dir: &Path,
        incoming_dir: &Path,
        min_free: download_queue::MinFree,
        disk: UnboundedSender<DiskDone>,
    ) -> io::Result<Self> {
        let incoming = incoming_dir.to_path_buf();
        let queue = DownloadQueue::load(temp_dir, Box::new(move |_| incoming.clone()), min_free, disk)?;
        let now = Instant::now();
        let entries = queue.all().map(|d| (d.hash(), Entry::new(d.file.part_count(), now))).collect();
        Ok(Self {
            queue,
            entries,
            clients: IndexMap::new(),
            links: HashMap::new(),
            callbacks: IndexMap::new(),
            random: StdRng::from_rng(&mut rand::rng()),
            server_requests_due: now,
            actions: Vec::new(),
            events: Vec::new(),
        })
    }

    pub fn take_actions(&mut self) -> Vec<Action> {
        std::mem::take(&mut self.actions)
    }

    pub fn take_events(&mut self) -> Vec<DownloadEvent> {
        std::mem::take(&mut self.events)
    }

    pub fn temp_dir(&self) -> &Path {
        self.queue.temp_dir()
    }

    /// Adds a download, or the link's sources to the existing one of the same file.
    pub fn add(&mut self, link: &FileLink, settings: &Settings, ours: &Hello) -> io::Result<DownloadInfo> {
        let known = self.queue.get(&link.hash).map(|d| download_queue::same_file(&d.file, link));
        let hash = self.queue.add(link, 0, DownloadPriority::Auto)?;
        self.settle(ours);
        if known != Some(false) {
            self.add_link_sources(&hash, &link.sources, settings);
        } else if let Some(download) = self.queue.get(&hash) {
            tracing::warn!(
                "A link of {} gives another size or AICH hash than the download: its sources are left out",
                download.file.name()
            );
        }
        self.info(&hash).ok_or_else(|| io::Error::other("the download is gone"))
    }

    pub fn downloads(&self) -> Vec<DownloadInfo> {
        self.entries.keys().filter_map(|hash| self.info(hash)).collect()
    }

    pub fn download(&self, hash: &[u8; 16]) -> Option<DownloadInfo> {
        self.info(hash)
    }

    pub fn pause(&mut self, hash: &[u8; 16], ours: &Hello) -> bool {
        if !self.entries.contains_key(hash) {
            return false;
        }
        self.queue.pause(hash);
        self.close_sessions(hash, "the download is paused", ours);
        self.progress(hash);
        true
    }

    /// Resumes a paused, stopped or failed download; false if unknown or running already.
    pub fn resume(&mut self, hash: &[u8; 16], ours: &Hello) -> bool {
        if !self.entries.contains_key(hash) || !self.queue.resume(hash) {
            return false;
        }
        let now = Instant::now();
        let addresses: Vec<SocketAddr> =
            self.entries.get(hash).map(|e| e.sources.keys().copied().collect()).unwrap_or_default();
        for address in addresses {
            if let Some(client) = self.clients.get_mut(&address)
                && client.current == *hash
            {
                client.next_ask = earliest_ask(client, now);
            }
        }
        if let Some(entry) = self.entries.get_mut(hash) {
            entry.next_server_ask = now;
        }
        self.progress(hash);
        self.settle(ours);
        true
    }

    /// Stops the download and deletes its data; false if unknown or while its finished file is moved.
    pub fn cancel(&mut self, hash: &[u8; 16], ours: &Hello) -> bool {
        if self.queue.get(hash).is_none_or(|d| d.is_finishing()) || !self.entries.contains_key(hash) {
            return false;
        }
        self.close_sessions(hash, "the download is cancelled", ours);
        self.forget(hash, ours);
        self.entries.shift_remove(hash);
        self.callbacks.retain(|_, callback| callback.file != *hash);
        self.queue.cancel(hash);
        self.events.push(DownloadEvent::Removed(*hash));
        true
    }

    /// The sources of every download, as the list of downloading clients shows them.
    pub fn sources(&self) -> Vec<SourceInfo> {
        let mut all = Vec::new();
        for (hash, entry) in &self.entries {
            let Some(download) = self.queue.get(hash) else {
                continue;
            };
            for (address, source) in &entry.sources {
                let Some(client) = self.clients.get(address) else {
                    continue;
                };
                let session = client.session.as_ref().filter(|_| client.current == *hash);
                let hello = client.session.as_ref().and_then(DownloadSession::peer);
                let parts: Option<Vec<bool>> =
                    session.and_then(|s| s.source_parts().map(<[bool]>::to_vec)).or_else(|| source.parts.clone());
                let a4af = client.current != *hash;
                let state = if a4af {
                    SourceState::A4af
                } else if let Some(session) = session.filter(|s| s.state() != State::Ended) {
                    state_of(session.state())
                } else if parts.as_deref().is_some_and(|parts| !needed(&download.file, parts)) {
                    SourceState::NoNeededParts
                } else {
                    SourceState::Waiting
                };
                all.push(SourceInfo {
                    file: *hash,
                    file_name: download.file.name().to_owned(),
                    address: address.to_string(),
                    nick: hello.and_then(|h| h.nick().map(str::to_owned)),
                    software: hello.map(peer_software::describe).unwrap_or_default(),
                    state,
                    queue_rank: if a4af { 0 } else { session.map_or(source.rank, DownloadSession::queue_rank) },
                    received: source.received,
                    parts: parts.as_deref().map_or(0, |parts| parts.iter().filter(|has| **has).count()),
                    part_count: download.file.part_count(),
                    reported_name: source.reported_name.clone(),
                });
            }
        }
        all
    }

    /// The downloads that want more sources, for the other servers to be asked.
    pub fn wanting_sources(&self, settings: &Settings) -> Vec<SourceQuery> {
        self.entries
            .iter()
            .filter(|(hash, entry)| {
                entry.sources.len() < settings.max_sources_per_file
                    && self.queue.get(hash).is_some_and(|d| d.is_active())
            })
            .filter_map(|(hash, _)| self.queue.get(hash).map(|d| SourceQuery { file: *hash, size: d.file.size() }))
            .collect()
    }

    /// A server connection was made: it is asked for the sources of every download.
    pub fn server_connected(&mut self) {
        let now = Instant::now();
        self.server_requests_due = now;
        for entry in self.entries.values_mut() {
            entry.next_server_ask = now;
        }
    }

    /// Sources a server found. A LowID source is asked through the server to connect to us, which needs a HighID of
    /// ours.
    pub fn found_sources(&mut self, found: &FoundSources, settings: &Settings, our_id: u32, ours: &Hello) {
        if self.queue.get(&found.file).is_none_or(|d| !d.is_active()) {
            return;
        }
        let mut added = 0usize;
        for source in &found.sources {
            if source.user_hash == Some(ours.user_hash) {
                continue;
            }
            if source.is_low_id() {
                if our_id >= LOW_ID_LIMIT && source.client_id != 0 {
                    self.ask_callback(&found.file, source.client_id, source.user_hash, source.crypt_options, settings);
                }
                continue;
            }
            let ip = ipv4::from_ed2k(source.client_id);
            if !usable(ip, source.port, settings, our_id) {
                continue;
            }
            let address = SocketAddr::from((Ipv4Addr::from(ip), source.port));
            if self.add_source(&found.file, address, settings) {
                if let Some(client) = self.clients.get_mut(&address)
                    && client.user_hash.is_none()
                    && source.user_hash.is_some()
                {
                    client.user_hash = source.user_hash;
                    client.crypt_options = source.crypt_options;
                }
                added = added.saturating_add(1);
            }
        }
        tracing::debug!("{added} of {} sources from the server are new", found.sources.len());
    }

    /// The host names of a link's sources resolved: each address becomes a source.
    pub fn resolved(&mut self, file: &[u8; 16], addresses: &[SocketAddr], settings: &Settings) {
        for address in addresses {
            self.add_link_source(file, *address, settings);
        }
    }

    /// Once a second: sessions that stalled, callbacks, the server asked for sources, sessions started.
    pub fn tick(&mut self, server: &mut dyn ServerLink, settings: &Settings, ours: &Hello) {
        let now = Instant::now();
        self.queue.tick(now);
        let addresses: Vec<SocketAddr> = self.clients.keys().copied().collect();
        for address in &addresses {
            self.with_session(address, ours, |session, ctx| session.check(ctx));
        }
        let held: Vec<([u8; 16], usize)> = self
            .entries
            .iter_mut()
            .flat_map(|(hash, entry)| {
                let over: Vec<([u8; 16], usize)> =
                    entry.holds.iter().filter(|(_, until)| now >= *until).map(|(part, _)| (*hash, *part)).collect();
                entry.holds.retain(|(_, until)| now < *until);
                over
            })
            .collect();
        for (hash, part) in held {
            self.unhold(&hash, part, ours);
        }
        self.send_callbacks(server, now);
        let mut starting = self.clients.values().filter(|c| c.session.as_ref().is_some_and(is_starting)).count();
        let hashes: Vec<[u8; 16]> = self.entries.keys().copied().collect();
        for hash in hashes {
            let Some(entry) = self.entries.get_mut(&hash) else {
                continue;
            };
            if let Some(window) = entry.window.get_mut(entry.slot) {
                *window = entry.received;
            }
            entry.slot = entry.slot.saturating_add(1).checked_rem(RATE_WINDOW).unwrap_or(0);
            entry.changed |= entry.received > 0;
            entry.received = 0;
            let Some((active, size)) = self.queue.get(&hash).map(|d| (d.is_active(), d.file.size())) else {
                continue;
            };
            if active {
                let burst = SERVER_REQUEST_CREDIT.saturating_mul(SERVER_REQUEST_BURST.saturating_sub(1));
                let may_ask = self.server_requests_due.saturating_duration_since(now) <= burst;
                if now >= entry.next_server_ask && may_ask && server.ask_sources(&hash, size) {
                    self.server_requests_due =
                        instants::later(now.max(self.server_requests_due), SERVER_REQUEST_CREDIT);
                    entry.next_server_ask = instants::later(now, SERVER_SOURCES_INTERVAL);
                }
                starting = starting.saturating_add(self.start_sessions(
                    &hash,
                    now,
                    MAX_STARTING.saturating_sub(starting),
                    server,
                    settings,
                ));
            }
            let changed = self.entries.get_mut(&hash).is_some_and(|entry| std::mem::take(&mut entry.changed));
            if changed {
                self.progress(&hash);
            }
        }
        self.settle(ours);
    }

    /// The connection asked for by [`Action::Connect`] is open, or could not be.
    pub fn connected(&mut self, address: SocketAddr, link: Result<LinkHandle, String>, ours: &Hello) {
        let connecting =
            self.clients.get(&address).and_then(|c| c.session.as_ref()).is_some_and(|s| s.state() == State::Connecting);
        match link {
            Ok(link) if connecting => {
                self.links.insert(link.id(), address);
                self.with_session(&address, ours, |session, _| session.opened(link, ours));
            }
            Ok(link) => link.close(),
            Err(reason) => self.with_session(&address, ours, |session, ctx| session.unreachable(&reason, ctx)),
        }
        self.settle(ours);
    }

    /// The client of an outgoing link answered our hello.
    pub fn greeted(&mut self, link: LinkId, hello: Hello, ours: &Hello) {
        if let Some(address) = self.links.get(&link).copied() {
            self.with_session(&address, ours, |session, ctx| session.greeted(hello, ctx));
            self.settle(ours);
        }
    }

    /// Takes the download side of a link a client opened when it is one of our sources, as a source does when it has
    /// a slot for us or when we asked it through the server to connect; false if it is not.
    pub fn adopt(
        &mut self,
        link: &LinkHandle,
        hello: &Hello,
        server_ip: u32,
        settings: &Settings,
        ours: &Hello,
    ) -> bool {
        if hello.user_hash == ours.user_hash {
            return false;
        }
        let adopted = self.adopt_callback(link, hello, server_ip, settings, ours) || self.adopt_slot(link, hello, ours);
        self.settle(ours);
        adopted
    }

    /// A packet of a link's download side.
    pub fn received(&mut self, link: LinkId, packet: &Packet, ours: &Hello) -> bool {
        let Some(address) = self.links.get(&link).copied() else {
            return false;
        };
        self.with_session(&address, ours, |session, ctx| session.received(packet, ctx));
        self.settle(ours);
        true
    }

    /// Whether a link carries a download.
    pub fn has_link(&self, link: LinkId) -> bool {
        self.links.contains_key(&link)
    }

    pub fn closed(&mut self, link: LinkId, reason: Option<&str>, ours: &Hello) {
        if let Some(address) = self.links.remove(&link) {
            let current = self
                .clients
                .get(&address)
                .and_then(|c| c.session.as_ref())
                .and_then(DownloadSession::link)
                .is_some_and(|l| l.id() == link);
            if current {
                self.with_session(&address, ours, |session, ctx| session.closed(reason, ctx));
                self.settle(ours);
            }
        }
    }

    /// What a disk thread did.
    pub fn disk_done(&mut self, done: DiskDone, ours: &Hello) {
        self.queue.disk_done(done);
        self.settle(ours);
    }

    /// Ends every session and saves the downloads.
    pub fn close(&mut self, ours: &Hello) {
        let hashes: Vec<[u8; 16]> = self.entries.keys().copied().collect();
        for hash in hashes {
            self.close_sessions(&hash, "shutting down", ours);
        }
        self.queue.close();
    }

    fn info(&self, hash: &[u8; 16]) -> Option<DownloadInfo> {
        let entry = self.entries.get(hash)?;
        let file = &self.queue.get(hash)?.file;
        let transferring = entry
            .sources
            .keys()
            .filter_map(|address| self.clients.get(address))
            .filter(|c| c.current == *hash && c.session.as_ref().is_some_and(|s| s.state() == State::Downloading))
            .count();
        Some(DownloadInfo {
            hash: *hash,
            name: file.name().to_owned(),
            size: file.size(),
            completed: file.completed_bytes(),
            status: status(file.status()),
            priority: file.priority(),
            category: file.category(),
            sources: entry.sources.len(),
            transferring,
            bytes_per_second: entry.rate(),
            parts: file.map(),
            preview_first: file.preview_first(),
            auto_swap: false,
        })
    }

    fn progress(&mut self, hash: &[u8; 16]) {
        if let Some(info) = self.info(hash) {
            self.events.push(DownloadEvent::Progress(info));
        }
    }

    // Runs `work` on the client's session with what it works with.
    fn with_session(
        &mut self,
        address: &SocketAddr,
        ours: &Hello,
        work: impl FnOnce(&mut DownloadSession, &mut Context<'_, StdRng>),
    ) {
        let Self { queue, clients, random, .. } = self;
        if let Some(session) = clients.get_mut(address).and_then(|c| c.session.as_mut()) {
            let mut ctx = Context { queue, ours, random, now: Instant::now() };
            work(session, &mut ctx);
        }
    }

    // Handles what the queue and the sessions told, and what that brings, until nothing is left.
    fn settle(&mut self, ours: &Hello) {
        for _ in 0..64 {
            let events = self.queue.take_events();
            let notes: Vec<(SocketAddr, Vec<Note>)> = self
                .clients
                .iter_mut()
                .filter_map(|(address, c)| c.session.as_mut().map(|s| (*address, s.take_notes())))
                .filter(|(_, notes)| !notes.is_empty())
                .collect();
            if events.is_empty() && notes.is_empty() {
                return;
            }
            for event in events {
                self.queue_event(event, ours);
            }
            for (address, notes) in notes {
                for note in notes {
                    self.note(address, note, ours);
                }
            }
        }
    }

    fn queue_event(&mut self, event: QueueEvent, ours: &Hello) {
        match event {
            QueueEvent::Added(hash) => {
                let parts = self.queue.get(&hash).map_or(0, |d| d.file.part_count());
                self.entries.insert(hash, Entry::new(parts, Instant::now()));
                if let Some(info) = self.info(&hash) {
                    self.events.push(DownloadEvent::Added(info));
                }
            }
            QueueEvent::Progress(hash) => {
                if let Some(entry) = self.entries.get_mut(&hash) {
                    entry.changed = true;
                    self.wake_sessions(&hash, ours);
                }
            }
            QueueEvent::Completed { file, path } => {
                let hash = file.hash();
                if !self.entries.contains_key(&hash) {
                    return;
                }
                self.close_sessions(&hash, "the download is complete", ours);
                let entry = self.entries.get(&hash);
                let info = DownloadInfo {
                    hash,
                    name: file.name().to_owned(),
                    size: file.size(),
                    completed: file.size(),
                    status: DownloadStatus::Complete,
                    priority: file.priority(),
                    category: file.category(),
                    sources: entry.map_or(0, |e| e.sources.len()),
                    transferring: 0,
                    bytes_per_second: entry.map_or(0, Entry::rate),
                    parts: file.map(),
                    preview_first: file.preview_first(),
                    auto_swap: false,
                };
                self.forget(&hash, ours);
                self.entries.shift_remove(&hash);
                self.events.push(DownloadEvent::Completed { info, path, file });
            }
            QueueEvent::Failed { hash, reason } => {
                self.close_sessions(&hash, &reason, ours);
                if let Some(info) = self.info(&hash) {
                    self.events.push(DownloadEvent::Failed { info, reason });
                }
            }
            QueueEvent::Corrupt { hash, part } => self.recover(&hash, part),
            QueueEvent::CorruptSenders { ips, .. } => {
                for ip in ips {
                    tracing::info!("{ip} sent corrupt data");
                }
            }
            QueueEvent::DiskSpace(enough) => {
                let hashes: Vec<[u8; 16]> = self.entries.keys().copied().collect();
                for hash in hashes {
                    if let Some(entry) = self.entries.get_mut(&hash) {
                        entry.changed = true;
                    }
                    if !enough {
                        self.close_sessions(&hash, "not enough free disk space", ours);
                    }
                }
            }
        }
    }

    fn note(&mut self, address: SocketAddr, note: Note, ours: &Hello) {
        let Some(hash) = self.clients.get(&address).map(|c| c.current) else {
            return;
        };
        match note {
            Note::Received { bytes, .. } => {
                if let Some(entry) = self.entries.get_mut(&hash) {
                    entry.received = entry.received.saturating_add(bytes);
                    if let Some(source) = entry.sources.get_mut(&address) {
                        source.received = source.received.saturating_add(bytes);
                    }
                }
            }
            Note::PartsKnown => {
                let parts = self
                    .clients
                    .get(&address)
                    .and_then(|c| c.session.as_ref())
                    .and_then(|s| s.source_parts().map(<[bool]>::to_vec));
                if let Some(parts) = parts {
                    self.set_parts(&hash, &address, Some(parts));
                }
                self.may_recover_from(&address, &hash);
            }
            Note::AichAnswered { part, master, data } => self.aich_answered(&hash, part, &master, &data, ours),
            Note::AichUnavailable { part } => self.unhold(&hash, part, ours),
            Note::Released => self.wake_sessions(&hash, ours),
            Note::AichAnnounced(master) => {
                if let (Some(entry), Some(download)) = (self.entries.get_mut(&hash), self.queue.get_mut(&hash))
                    && download.file.aich_hash().is_none()
                    && let Some(trusted) = entry.aich_votes.announced(address.ip(), master)
                {
                    entry.aich_votes = AichVotes::new();
                    download.file.trust_aich(trusted);
                    tracing::info!("The sources agree on the AICH hash of {}", download.file.name());
                }
            }
            Note::Named(name) => {
                if let Some(source) = self.entries.get_mut(&hash).and_then(|e| e.sources.get_mut(&address)) {
                    source.reported_name = Some(name);
                }
            }
            Note::Ended { outcome, reason } => self.ended(address, outcome, &reason, ours),
        }
    }

    fn ended(&mut self, address: SocketAddr, outcome: Outcome, reason: &str, ours: &Hello) {
        let Some(client) = self.clients.get_mut(&address) else {
            return;
        };
        let Some(session) = client.session.take() else {
            return;
        };
        if let Some(link) = session.link() {
            self.links.remove(&link.id());
        }
        self.links.retain(|_, a| *a != address);
        let hash = client.current;
        if session.peer_hash() == Some(ours.user_hash) {
            // We reached ourselves: the address is no source of any download.
            tracing::debug!("{address} is ourselves");
            self.drop_client(&address);
            return;
        }
        if let Some(hash) = session.peer_hash() {
            client.user_hash = Some(hash);
            client.crypt_options = session.peer_crypt_options();
        }
        tracing::debug!("{address} ({outcome:?}): {reason}");
        let now = Instant::now();
        match outcome {
            Outcome::Queued | Outcome::Later => {
                client.failures = 0;
                client.next_ask = instants::later(client.last_ask, REASK_INTERVAL);
            }
            Outcome::Unreachable => {
                client.failures = client.failures.saturating_add(1);
                client.next_ask = instants::later(now, MIN_REASK);
            }
            Outcome::Gone => client.next_ask = instants::later(client.last_ask, REASK_INTERVAL),
        }
        let failures = client.failures;
        let parts = session.source_parts().map(<[bool]>::to_vec);
        let rank = session.upload_asked().then(|| session.queue_rank());
        if let Some(parts) = parts {
            self.set_parts(&hash, &address, Some(parts));
        }
        if let (Some(rank), Some(source)) =
            (rank, self.entries.get_mut(&hash).and_then(|e| e.sources.get_mut(&address)))
        {
            source.rank = rank;
        }
        if outcome == Outcome::Gone {
            self.detach(&hash, &address, ours);
        } else if outcome == Outcome::Unreachable && failures >= MAX_FAILURES {
            self.drop_client(&address);
        }
        if let Some(entry) = self.entries.get_mut(&hash) {
            entry.changed = true;
        }
        // The blocks it held may come from another source with a slot.
        if self.queue.get(&hash).is_some_and(|d| d.is_active()) {
            self.wake_sessions(&hash, ours);
        }
    }

    // A firewalled source we asked to call us, recognised by the LowID our server gave it.
    fn adopt_callback(
        &mut self,
        link: &LinkHandle,
        hello: &Hello,
        server_ip: u32,
        settings: &Settings,
        ours: &Hello,
    ) -> bool {
        let wanted = self.callbacks.get(&hello.client_id).is_some_and(|callback| {
            callback.requested
                && hello.server_ip != 0
                && hello.server_ip == server_ip
                && callback.user_hash.is_none_or(|user| user == hello.user_hash)
        });
        if !wanted {
            return false;
        }
        let Some(callback) = self.callbacks.shift_remove(&hello.client_id) else {
            return false;
        };
        if self.queue.get(&callback.file).is_none_or(|d| !d.is_active()) {
            return false;
        }
        let address = SocketAddr::new(link.remote().ip(), hello.listen_port());
        let known = self.entries.get(&callback.file).is_some_and(|e| e.sources.contains_key(&address));
        if !known && !self.add_source(&callback.file, address, settings) {
            return false;
        }
        let now = Instant::now();
        let Some(client) = self.clients.get_mut(&address) else {
            return false;
        };
        if client.session.is_some() {
            return false;
        }
        client.low_id = hello.client_id;
        client.user_hash = Some(hello.user_hash);
        client.asked = true;
        client.last_ask = now;
        client.next_ask = instants::later(now, REASK_INTERVAL);
        client.current = callback.file;
        client.requested = Some(callback.file);
        client.session = Some(DownloadSession::new(callback.file, address, now));
        self.links.insert(link.id(), address);
        tracing::debug!("{address} called us back");
        let (link, hello) = (link.clone(), hello.clone());
        self.with_session(&address, ours, |session, ctx| session.adopt(link, hello, None, false, ctx));
        true
    }

    // A source that connects to give us a slot, whatever download it was asked for.
    fn adopt_slot(&mut self, link: &LinkHandle, hello: &Hello, ours: &Hello) -> bool {
        let from = link.remote().ip();
        let found = self.clients.iter().find_map(|(address, client)| {
            let same = client.user_hash == Some(hello.user_hash)
                || (address.ip() == from && address.port() == hello.listen_port());
            (same && client.asked).then_some(*address)
        });
        let Some(address) = found else {
            return false;
        };
        let active = |queue: &DownloadQueue, hash: &[u8; 16]| queue.get(hash).is_some_and(|d| d.is_active());
        let Some(client) = self.clients.get(&address) else {
            return false;
        };
        let source = if active(&self.queue, &client.current) {
            Some(client.current)
        } else {
            client.sources.iter().copied().find(|hash| active(&self.queue, hash))
        };
        let Some(hash) = source else {
            return false;
        };
        let old = client.session.as_ref().map(DownloadSession::state);
        if old.is_some_and(|state| state != State::Queued) {
            return false;
        }
        if old.is_some()
            && let Some(mut session) = self.clients.get_mut(&address).and_then(|c| c.session.take())
        {
            if let Some(old_link) = session.link() {
                self.links.remove(&old_link.id());
            }
            let Self { queue, random, .. } = self;
            session.close("the source called back", &mut Context { queue, ours, random, now: Instant::now() });
        }
        // The file it was asked for last needs no request again, another does.
        let known = self
            .clients
            .get(&address)
            .filter(|c| c.requested == Some(hash))
            .and_then(|_| self.entries.get(&hash))
            .and_then(|e| e.sources.get(&address))
            .and_then(|s| s.parts.clone());
        let now = Instant::now();
        let Some(client) = self.clients.get_mut(&address) else {
            return false;
        };
        client.current = hash;
        client.requested = Some(hash);
        client.user_hash = Some(hello.user_hash);
        client.session = Some(DownloadSession::new(hash, address, now));
        self.links.insert(link.id(), address);
        tracing::debug!("{address} called back");
        let (link, hello) = (link.clone(), hello.clone());
        self.with_session(&address, ours, |session, ctx| session.adopt(link, hello, known, true, ctx));
        true
    }

    // Queues a callback request; one goes out each tick, and those not answered soon are dropped.
    fn ask_callback(
        &mut self,
        file: &[u8; 16],
        low_id: u32,
        user_hash: Option<[u8; 16]>,
        crypt: u8,
        settings: &Settings,
    ) {
        let full = self.entries.get(file).is_none_or(|e| e.sources.len() >= settings.max_sources_per_file);
        if !suits_obfuscation(settings.obfuscation, crypt, user_hash)
            || self.callbacks.len() >= MAX_CALLBACKS
            || self.callbacks.contains_key(&low_id)
            || full
        {
            return;
        }
        self.callbacks.insert(low_id, Callback { file: *file, user_hash, since: Instant::now(), requested: false });
    }

    fn send_callbacks(&mut self, server: &mut dyn ServerLink, now: Instant) {
        self.callbacks.retain(|_, callback| now.saturating_duration_since(callback.since) < CALLBACK_WAIT);
        let next = self.callbacks.iter().find(|(_, callback)| !callback.requested).map(|(low_id, _)| *low_id);
        if let Some(low_id) = next {
            if server.request_callback(low_id) {
                if let Some(callback) = self.callbacks.get_mut(&low_id) {
                    callback.requested = true;
                    callback.since = now;
                }
            } else {
                self.callbacks.shift_remove(&low_id);
            }
        }
    }

    // The clients asked for the download whose time has come are asked again.
    fn start_sessions(
        &mut self,
        hash: &[u8; 16],
        now: Instant,
        budget: usize,
        server: &mut dyn ServerLink,
        settings: &Settings,
    ) -> usize {
        let mut started = 0usize;
        let addresses: Vec<SocketAddr> =
            self.entries.get(hash).map(|e| e.sources.keys().copied().collect()).unwrap_or_default();
        for address in addresses {
            if started >= budget {
                break;
            }
            let Some(client) = self.clients.get_mut(&address) else {
                continue;
            };
            if client.current != *hash || client.session.is_some() || now < client.next_ask {
                continue;
            }
            if !suits_obfuscation(settings.obfuscation, client.crypt_options, client.user_hash) {
                continue;
            }
            if client.low_id != 0 {
                // Firewalled: asked again through the server, while it keeps the same LowID.
                client.next_ask = instants::later(now, REASK_INTERVAL);
                let (low_id, user_hash, crypt) = (client.low_id, client.user_hash, client.crypt_options);
                if server.client_id() >= LOW_ID_LIMIT {
                    self.ask_callback(hash, low_id, user_hash, crypt, settings);
                }
                continue;
            }
            client.asked = true;
            client.last_ask = now;
            client.next_ask = instants::later(now, REASK_INTERVAL);
            client.requested = Some(*hash);
            client.session = Some(DownloadSession::new(*hash, address, now));
            // Obfuscated only towards sources known to take it, which needs their user hash.
            let crypt =
                settings.obfuscation != ObfuscationMode::Disabled && client.crypt_options & CRYPT_SUPPORTED != 0;
            let obfuscate_for = if crypt { client.user_hash } else { None };
            self.actions.push(Action::Connect { address, obfuscate_for });
            started = started.saturating_add(1);
        }
        started
    }

    // The user chose these sources, so a LAN or loopback address is fine.
    fn add_link_sources(&mut self, hash: &[u8; 16], sources: &[ed2k_link::Source], settings: &Settings) {
        let mut named = Vec::new();
        for source in sources {
            match source.host.parse::<IpAddr>() {
                Ok(ip) => self.add_link_source(hash, SocketAddr::new(ip, source.port), settings),
                Err(_) if named.len() < MAX_LINK_HOSTS => named.push(source.clone()),
                Err(_) => {}
            }
        }
        if !named.is_empty() {
            self.actions.push(Action::Resolve { file: *hash, hosts: named });
        }
    }

    // No client is at 0.x.x.x, at a multicast address or above, nor at an IPv6 address no host has.
    fn add_link_source(&mut self, hash: &[u8; 16], address: SocketAddr, settings: &Settings) {
        let fits = match address.ip() {
            IpAddr::V4(ip) => {
                let first = ip.octets()[0];
                first != 0 && first < 224
            }
            IpAddr::V6(ip) => !ip.is_multicast() && !ip.is_unspecified(),
        };
        if address.port() != 0 && fits {
            self.add_source(hash, address, settings);
        }
    }

    // Whether the source is new; a known client keeps its clock.
    fn add_source(&mut self, hash: &[u8; 16], address: SocketAddr, settings: &Settings) -> bool {
        let Some(entry) = self.entries.get_mut(hash) else {
            return false;
        };
        if entry.sources.contains_key(&address) || entry.sources.len() >= settings.max_sources_per_file {
            return false;
        }
        entry.sources.insert(address, Source::default());
        entry.changed = true;
        let now = Instant::now();
        let client = self.clients.entry(address).or_insert_with(|| Client {
            sources: Vec::new(),
            current: *hash,
            requested: None,
            session: None,
            user_hash: None,
            crypt_options: 0,
            low_id: 0,
            next_ask: now,
            last_ask: now,
            asked: false,
            failures: 0,
        });
        client.sources.push(*hash);
        let current_active = self.queue.get(&client.current).is_some_and(|d| d.is_active());
        if client.session.is_none() && !current_active {
            client.current = *hash;
        }
        true
    }

    // Ends the sessions of the download; the clients stay its sources.
    fn close_sessions(&mut self, hash: &[u8; 16], reason: &str, ours: &Hello) {
        let addresses: Vec<SocketAddr> =
            self.entries.get(hash).map(|e| e.sources.keys().copied().collect()).unwrap_or_default();
        for address in addresses {
            if self.clients.get(&address).is_some_and(|c| c.current == *hash) {
                self.with_session(&address, ours, |session, ctx| session.close(reason, ctx));
            }
        }
        self.settle(ours);
    }

    // The download leaves for good, with its sources.
    fn forget(&mut self, hash: &[u8; 16], ours: &Hello) {
        let addresses: Vec<SocketAddr> =
            self.entries.get(hash).map(|e| e.sources.keys().copied().collect()).unwrap_or_default();
        for address in addresses {
            self.detach(hash, &address, ours);
        }
    }

    // Takes the source off its download and its client; a client asked for that download is asked for another.
    fn detach(&mut self, hash: &[u8; 16], address: &SocketAddr, ours: &Hello) {
        self.set_parts(hash, address, None);
        if let Some(entry) = self.entries.get_mut(hash) {
            entry.sources.shift_remove(address);
            entry.changed = true;
        }
        let Some(client) = self.clients.get_mut(address) else {
            return;
        };
        client.sources.retain(|source| source != hash);
        if client.requested == Some(*hash) {
            client.requested = None;
        }
        if client.current == *hash
            && let Some(mut session) = client.session.take()
        {
            if let Some(link) = session.link() {
                self.links.remove(&link.id());
            }
            let Self { queue, random, .. } = self;
            session.close("the source is dropped", &mut Context { queue, ours, random, now: Instant::now() });
        }
        let Some(client) = self.clients.get_mut(address) else {
            return;
        };
        match client.sources.first() {
            None => {
                self.clients.shift_remove(address);
            }
            Some(first) if client.current == *hash => client.current = *first,
            Some(_) => {}
        }
    }

    // A client of no use any more, dropped from every download.
    fn drop_client(&mut self, address: &SocketAddr) {
        let Some(client) = self.clients.shift_remove(address) else {
            return;
        };
        for hash in client.sources {
            self.set_parts_of(&hash, address, None);
            if let Some(entry) = self.entries.get_mut(&hash) {
                entry.sources.shift_remove(address);
                entry.changed = true;
            }
        }
        self.links.retain(|_, a| a != address);
    }

    // What a source has of a file counts in the rarity of its parts for as long as the source is known.
    fn set_parts(&mut self, hash: &[u8; 16], address: &SocketAddr, parts: Option<Vec<bool>>) {
        self.set_parts_of(hash, address, parts);
    }

    fn set_parts_of(&mut self, hash: &[u8; 16], address: &SocketAddr, parts: Option<Vec<bool>>) {
        let (Some(source), Some(download)) =
            (self.entries.get_mut(hash).and_then(|e| e.sources.get_mut(address)), self.queue.get_mut(hash))
        else {
            return;
        };
        if let Some(old) = &source.parts {
            download.file.add_availability(old, false);
        }
        if let Some(new) = &parts {
            download.file.add_availability(new, true);
        }
        source.parts = parts;
    }

    // Blocks were let go or a part may be downloaded again: a session with nothing to ask for asks now.
    fn wake_sessions(&mut self, hash: &[u8; 16], ours: &Hello) {
        let addresses: Vec<SocketAddr> =
            self.entries.get(hash).map(|e| e.sources.keys().copied().collect()).unwrap_or_default();
        for address in addresses {
            if self.clients.get(&address).is_some_and(|c| c.current == *hash) {
                self.with_session(&address, ours, |session, ctx| session.wake(ctx));
            }
        }
    }

    // A corrupt part with a trusted AICH master hash: one source able to is asked for the recovery data.
    fn recover(&mut self, hash: &[u8; 16], part: usize) {
        let (Some(entry), Some(download)) = (self.entries.get_mut(hash), self.queue.get_mut(hash)) else {
            return;
        };
        let Some(master) = download.file.aich_hash() else {
            return;
        };
        // Once only: a part that fails again after its recovery is downloaded again whole.
        let tried = part_file::has(&entry.recovery_tried, part);
        if tried || !u32::try_from(part).is_ok_and(|p| aich_recovery::has_recovery(download.file.size(), p)) {
            return;
        }
        if let Some(bit) = entry.recovery_tried.get_mut(part) {
            *bit = true;
        }
        // The part waits a little for the recovery data rather than come again whole.
        download.file.hold(part);
        entry.holds.push((part, instants::later(Instant::now(), RECOVERY_WAIT)));
        let addresses: Vec<SocketAddr> = entry.sources.keys().copied().collect();
        for address in addresses {
            if let Some(session) = self
                .clients
                .get_mut(&address)
                .filter(|c| c.current == *hash)
                .and_then(|c| c.session.as_mut())
                .filter(|s| s.may_recover(part))
            {
                tracing::info!("Asking {address} for AICH recovery data of part {part}");
                session.request_aich(part, &master);
                return;
            }
        }
        // The next source able to is asked when it says which parts it has.
        if let Some(bit) = self.entries.get_mut(hash).and_then(|e| e.recovery_wanted.get_mut(part)) {
            *bit = true;
        }
    }

    fn may_recover_from(&mut self, address: &SocketAddr, hash: &[u8; 16]) {
        let (Some(entry), Some(download)) = (self.entries.get_mut(hash), self.queue.get(hash)) else {
            return;
        };
        let Some(master) = download.file.aich_hash() else {
            return;
        };
        let Some(session) = self.clients.get_mut(address).and_then(|c| c.session.as_mut()) else {
            return;
        };
        for part in 0..entry.recovery_wanted.len() {
            if !part_file::has(&entry.recovery_wanted, part) {
                continue;
            }
            if download.file.is_part_complete(part) || part_file::has(download.verified_parts(), part) {
                if let Some(bit) = entry.recovery_wanted.get_mut(part) {
                    *bit = false;
                }
            } else if session.may_recover(part) {
                if let Some(bit) = entry.recovery_wanted.get_mut(part) {
                    *bit = false;
                }
                tracing::info!("Asking {address} for AICH recovery data of part {part}");
                session.request_aich(part, &master);
                return;
            }
        }
    }

    fn aich_answered(&mut self, hash: &[u8; 16], part: usize, master: &[u8; 20], data: &[u8], ours: &Hello) {
        let Some(download) = self.queue.get(hash) else {
            return;
        };
        if download.file.aich_hash().as_ref() != Some(master) {
            return;
        }
        let size = download.file.size();
        match u32::try_from(part).ok().and_then(|p| aich_recovery::verify(size, p, master, data)) {
            Some(blocks) => self.queue.recovered(hash, part, blocks),
            None => {
                tracing::info!("AICH recovery data of part {part} proves nothing");
                self.unhold(hash, part, ours);
            }
        }
    }

    // No recovery data will come: the part is downloaded again whole, by the sessions idle now too.
    fn unhold(&mut self, hash: &[u8; 16], part: usize, ours: &Hello) {
        if let Some(download) = self.queue.get_mut(hash) {
            download.file.unhold(part);
            self.wake_sessions(hash, ours);
        }
    }
}

// A connection with the source, ours or its callback, needs obfuscation when either side requires it.
fn suits_obfuscation(mode: ObfuscationMode, crypt_options: u8, user_hash: Option<[u8; 16]>) -> bool {
    match mode {
        ObfuscationMode::Required => user_hash.is_some() && crypt_options & CRYPT_SUPPORTED != 0,
        ObfuscationMode::Disabled => crypt_options & CRYPT_REQUIRED != CRYPT_REQUIRED,
        ObfuscationMode::Supported | ObfuscationMode::Requested => true,
    }
}

// A source the network told us about: a public-looking address, not ourselves.
fn usable(ip: u32, port: u16, settings: &Settings, our_id: u32) -> bool {
    port != 0
        && server_list::is_usable(ip)
        && !(settings.filter_lan && server_list::is_lan(ip))
        && !(port == settings.listen_port && ipv4::to_ed2k(ip) == our_id)
}

fn earliest_ask(client: &Client, now: Instant) -> Instant {
    if client.asked { now.max(instants::later(client.last_ask, MIN_REASK)) } else { now }
}

fn is_starting(session: &DownloadSession) -> bool {
    matches!(session.state(), State::Connecting | State::Greeting | State::Requesting)
}

fn needed(file: &PartFile, parts: &[bool]) -> bool {
    (0..file.part_count()).any(|part| part_file::has(parts, part) && !file.is_part_complete(part))
}

fn state_of(state: State) -> SourceState {
    match state {
        State::Connecting => SourceState::Connecting,
        State::Greeting | State::Requesting => SourceState::Asking,
        State::Queued => SourceState::Queued,
        State::Downloading => SourceState::Downloading,
        State::Ended => SourceState::Waiting,
    }
}

fn status(status: Status) -> DownloadStatus {
    match status {
        Status::Downloading => DownloadStatus::Downloading,
        Status::Paused => DownloadStatus::Paused,
        Status::Stopped => DownloadStatus::Stopped,
        Status::Error => DownloadStatus::Error,
        Status::Complete => DownloadStatus::Complete,
    }
}
