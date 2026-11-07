// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The downloads and their sources: the clients known to have each file, one session at a time with each client, the
//! sources the servers find and the firewalled ones asked through the server to connect to us. Kad, the exchange of
//! sources between clients, asking another file of a client (A4AF) and the download limit wait for their ports.

use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use indexmap::IndexMap;
use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::sync::mpsc::UnboundedSender;

use crate::api::category::{self, Category};
use crate::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus, SourceInfo, SourceState};
use crate::api::ed2k_link::{self, FileLink};
use crate::api::file_comment::FileComment;
use crate::api::file_names;
use crate::api::peer_comment::PeerComment;
use crate::config::next_download::NextDownload;
use crate::config::obfuscation_mode::ObfuscationMode;
use crate::download::download_queue::{self, DownloadQueue, QueueEvent};
use crate::download::download_session::{Context, DownloadSession, Note, Outcome, State};
use crate::download::known_sources::{self, Candidate, Standing};
use crate::files::comment_filter::CommentFilter;
use crate::files::hash::aich_recovery;
use crate::files::hash::aich_votes::AichVotes;
use crate::files::part_file::{self, KnownSource, PartFile, Status};
use crate::files::part_file_storage::DiskDone;
use crate::peer::peer_link::{LinkHandle, LinkId};
use crate::peer::peer_software;
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::ed2k::server_datagrams::SourceQuery;
use crate::protocol::ed2k::server_messages::{FoundSources, LOW_ID_LIMIT};
use crate::protocol::ed2k::source_exchange as exchange;
use crate::protocol::packet::Packet;
use crate::security::path_sanitizer;
use crate::server::server_list;
use crate::util::{instants, ipv4, ipv6, white_space};

/// A source that keeps us in its queue is asked again this often.
pub const REASK_INTERVAL: Duration = Duration::from_secs(29 * 60);
/// Sources ban clients that ask more often than this.
pub const MIN_REASK: Duration = Duration::from_secs(10 * 60);
/// A client asked for another download, or again before its time, waits at least this long since its last ask: a margin
/// over [`MIN_REASK`], which the source measures from when our request reached it.
pub const EARLIEST_REASK: Duration = Duration::from_secs(11 * 60);
/// A place in a client's queue this good or better is kept rather than swapped for another download.
pub const GOOD_RANK: u32 = 50;
// A source that told it has no part we need is asked for something else first this long.
const NO_PARTS_WAIT: Duration = Duration::from_secs(58 * 60);
// A source swapped away from by hand waits this long before it is asked for that download again by itself.
const AWAY: Duration = Duration::from_secs(60 * 60);
// A source of a rare file may be asked for other sources every 40 minutes, of another every 160; a file waits for answers
// between asks, and stops asking near the source limit.
const SX_RARE_INTERVAL: Duration = Duration::from_secs(40 * 60);
const SX_INTERVAL: Duration = Duration::from_secs(160 * 60);
const SX_RARE_ANSWER_GAP: Duration = Duration::from_secs(5 * 60);
const SX_ANSWER_GAP: Duration = Duration::from_secs(20 * 60);
const SX_VERY_RARE: usize = 10;
const SX_RARE: usize = 50;
const SX_MAX_SOURCES: usize = 750;
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
/// How long the sources at an address that sent corrupt data are kept out.
pub const CORRUPT_BAN: Duration = Duration::from_secs(2 * 60 * 60);
/// The sources that served each download are kept for the next run this often, and when the downloads close.
pub const KEEP_SOURCES_INTERVAL: Duration = Duration::from_secs(10 * 60);

const MAX_FAILURES: u32 = 3;
const RATE_WINDOW: usize = 5;
const CRYPT_SUPPORTED: u8 = 0x01;
const CRYPT_REQUIRED: u8 = 0x07;
const MAX_LINK_HOSTS: usize = 16;
// The comments kept of each download at most.
const MAX_COMMENTS: usize = 100;
// The bytes from the start a player needs to show a file while it grows.
const PREVIEW_START: u64 = 16 * 1024;

// How a client's download stands against its others, the better the greater: not swapped away from, may have parts we
// need, takes A4AF sources by itself, priority, parts known, the current one.
type Standing4af = (bool, bool, bool, u8, bool, bool);

/// What the downloads take the time from: [`Instant::now`] but in tests.
pub type Clock = Box<dyn Fn() -> Instant + Send>;

/// What the engine does for the downloads.
pub struct Hooks {
    /// Whether the IP filter blocks an IPv4 address, as [`crate::util::ipv4`] holds it.
    pub blocked: Box<dyn Fn(u32) -> bool + Send>,
    /// Keeps the link of a download about to be deleted for receiving nothing; false keeps the download.
    pub keep_link: Box<dyn FnMut(&FileLink) -> bool + Send>,
}

impl Hooks {
    /// No IP filter, and no link kept, so that no idle download is deleted.
    pub fn none() -> Self {
        Self { blocked: Box::new(|_| false), keep_link: Box::new(|_| false) }
    }
}

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
    /// Whether the names of new downloads are tidied, without the words of `name_cleanups` (apart by `|`).
    pub clean_up_new_names: bool,
    pub name_cleanups: String,
    /// A running download that receives nothing for this many days is deleted, 0 for never.
    pub auto_delete_days: u32,
    /// Words, apart by `|`, that leave the sources' comments holding them out.
    pub comment_filter: String,
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
    /// The source at the address sent corrupt data: its sources were dropped, and none is taken there for a while.
    CorruptSender(IpAddr),
    /// The temp disk has dropped below (false), or come back above (true), the free space downloads need.
    DiskSpace(bool),
    /// `part` of the download failed its check and is downloaded again.
    CorruptPart {
        info: DownloadInfo,
        part: usize,
    },
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
    // Swapped by hand: asked for its current download at its time, whatever the others offer.
    pinned: bool,
    // The IPv6 address another client told it has.
    ipv6: Option<Ipv6Addr>,
    // The downloads asked for on the current connection.
    tried: Vec<[u8; 16]>,
}

// A client as a source of one download.
#[derive(Default)]
struct Source {
    parts: Option<Vec<bool>>,
    // Our place in the client's queue for the file, as it last told; 0 for none.
    rank: u32,
    received: u64,
    reported_name: Option<String>,
    // When the parts were told, and until when the source is swapped away from by hand.
    parts_at: Option<Instant>,
    away_until: Option<Instant>,
    // When it was last asked for other sources.
    sources_asked: Option<Instant>,
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
    // Stopped by a scheduler rule: no resume of the next download starts it again.
    stopped_by_schedule: bool,
    // What the sources said of the file, the latest of each, in the order they said it.
    comments: IndexMap<SocketAddr, PeerComment>,
    // When a source last told the other sources it knows.
    sources_answered: Option<Instant>,
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
            stopped_by_schedule: false,
            comments: IndexMap::new(),
            sources_answered: None,
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
    clock: Clock,
    categories: Vec<Category>,
    hooks: Hooks,
    // Until when the sources at each address that sent corrupt data, IPv6 ones by their /64, are kept out.
    corrupt_senders: HashMap<IpAddr, Instant>,
    // How far the idle time of the downloads is counted.
    idle_counted: Instant,
    // The sources kept from the last run are taken at the first tick, which knows the settings and the server.
    known_taken: bool,
    next_sources_kept: Instant,
    comment_filter: CommentFilter,
    // The settings and our client id the last tick saw, for what the sources tell between ticks.
    settings: Option<Settings>,
    our_id: u32,
}

impl DownloadManager {
    /// Loads the downloads of `temp_dir`; finished files go to `incoming_dir`, downloads wait while the temp disk has
    /// less free space than `min_free`, disk work reports to `disk`.
    pub fn open(
        temp_dir: &Path,
        incoming_dir: &Path,
        min_free: download_queue::MinFree,
        disk: UnboundedSender<DiskDone>,
        clock: Clock,
        hooks: Hooks,
    ) -> io::Result<Self> {
        let incoming = incoming_dir.to_path_buf();
        let queue = DownloadQueue::load(temp_dir, Box::new(move |_| incoming.clone()), min_free, disk)?;
        let now = clock();
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
            clock,
            categories: Vec::new(),
            hooks,
            corrupt_senders: HashMap::new(),
            idle_counted: now,
            known_taken: false,
            next_sources_kept: instants::later(now, KEEP_SOURCES_INTERVAL),
            comment_filter: CommentFilter::default(),
            settings: None,
            our_id: 0,
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

    /// Adds a download, or the link's sources to the existing one of the same file. A new download goes in the first
    /// category whose names it matches, with that category's priority.
    pub fn add(&mut self, link: &FileLink, settings: &Settings, ours: &Hello) -> io::Result<DownloadInfo> {
        self.add_in(link, None, settings, ours)
    }

    /// Adds a download in `chosen`, a known category or none; without one, in the category its name falls in.
    pub fn add_in(
        &mut self,
        added: &FileLink,
        chosen: Option<u32>,
        settings: &Settings,
        ours: &Hello,
    ) -> io::Result<DownloadInfo> {
        let is_new = self.queue.get(&added.hash).is_none();
        let cleaned;
        let link = if is_new && settings.clean_up_new_names {
            cleaned = added
                .with_name(&file_names::clean_up(&added.name, &settings.name_cleanups))
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e.to_string()))?;
            &cleaned
        } else {
            added
        };
        // A link of another file under the same hash would give sources that cannot serve this one.
        let known = self.queue.get(&link.hash).map(|d| download_queue::same_file(&d.file, link));
        let (mut category, mut priority) = (category::NONE, DownloadPriority::Auto);
        if is_new {
            // Set before the download is announced, so the news of it tells them.
            let name = path_sanitizer::file_name(&link.name);
            let given = match chosen {
                Some(id) => self.category(id),
                None => self.categories.iter().find(|c| c.matches(&name)),
            };
            if let Some(given) = given {
                (category, priority) = (given.id, given.priority);
            }
        }
        let hash = self.queue.add(link, category, priority)?;
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
        self.release(hash);
        self.progress(hash);
        true
    }

    /// Resumes a paused, stopped or failed download; false if unknown or running already.
    pub fn resume(&mut self, hash: &[u8; 16], ours: &Hello) -> bool {
        if !self.entries.contains_key(hash) || !self.queue.resume(hash) {
            return false;
        }
        let now = (self.clock)();
        let addresses: Vec<SocketAddr> =
            self.entries.get(hash).map(|e| e.sources.keys().copied().collect()).unwrap_or_default();
        for address in addresses {
            let Some(client) = self.clients.get_mut(&address) else {
                continue;
            };
            if client.current == *hash {
                client.next_ask = earliest_ask(client, now);
            } else if client.session.is_none() && !self.queue.get(&client.current).is_some_and(|d| d.is_active()) {
                // Idle while the download it was asked for waits: asked for this one now.
                self.swap(&address, *hash, now);
            }
        }
        if let Some(entry) = self.entries.get_mut(hash) {
            entry.next_server_ask = now;
            entry.stopped_by_schedule = false;
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

    /// Pauses the download and forgets its sources: resumed, it looks for them again.
    pub fn stop(&mut self, hash: &[u8; 16], ours: &Hello) -> bool {
        if !self.entries.contains_key(hash) {
            return false;
        }
        self.queue.stop(hash);
        self.close_sessions(hash, "the download is stopped", ours);
        self.forget(hash, ours);
        self.queue.set_known_sources(hash, &[]);
        self.callbacks.retain(|_, callback| callback.file != *hash);
        self.progress(hash);
        true
    }

    /// Stops the download as a scheduler rule asks, also one stopped already: no other leaving starts it again.
    pub fn stop_by_schedule(&mut self, hash: &[u8; 16], ours: &Hello) -> bool {
        let Some(entry) = self.entries.get_mut(hash) else {
            return false;
        };
        entry.stopped_by_schedule = true;
        self.queue.get(hash).is_some_and(|d| d.file.status() == Status::Stopped) || self.stop(hash, ours)
    }

    /// The categories downloads may be in; downloads in one that is gone have none.
    pub fn set_categories(&mut self, categories: Vec<Category>) {
        self.categories = categories;
        let hashes: Vec<[u8; 16]> = self.entries.keys().copied().collect();
        for hash in hashes {
            let id = self.queue.get(&hash).map_or(category::NONE, |d| d.file.category());
            if id != category::NONE && self.category(id).is_none() {
                self.queue.set_category(&hash, category::NONE);
                self.progress(&hash);
            }
        }
    }

    /// Puts the download in a category, with its priority; [`category::NONE`] takes it out of any.
    pub fn set_category(&mut self, hash: &[u8; 16], id: u32) -> bool {
        if !self.entries.contains_key(hash) || (id != category::NONE && self.category(id).is_none()) {
            return false;
        }
        let priority = self.category(id).map(|c| c.priority);
        self.queue.set_category(hash, id);
        if let Some(priority) = priority.filter(|p| *p != DownloadPriority::Auto) {
            self.queue.set_priority(hash, priority);
        }
        self.progress(hash);
        true
    }

    /// Names the download `name`, made a safe file name, which its file gets once complete; false for no such
    /// download, an error for a blank name.
    pub fn rename(&mut self, hash: &[u8; 16], name: &str) -> Result<bool, &'static str> {
        if white_space::strip(name).is_empty() {
            return Err("a download needs a name");
        }
        if !self.entries.contains_key(hash) {
            return Ok(false);
        }
        self.queue.rename(hash, path_sanitizer::file_name(name));
        self.progress(hash);
        Ok(true)
    }

    pub fn set_priority(&mut self, hash: &[u8; 16], priority: DownloadPriority) -> bool {
        if !self.entries.contains_key(hash) {
            return false;
        }
        self.queue.set_priority(hash, priority);
        self.progress(hash);
        true
    }

    pub fn set_preview_first(&mut self, hash: &[u8; 16], value: bool) -> bool {
        if !self.entries.contains_key(hash) {
            return false;
        }
        self.queue.set_preview_first(hash, value);
        self.progress(hash);
        true
    }

    /// Resumes the paused or stopped download of highest priority, the first added among equals: one of `category`
    /// first, unless `mode` takes any; returns it, if there was one. Downloads a scheduler rule stopped wait.
    pub fn resume_next(&mut self, category: u32, mode: NextDownload, ours: &Hello) -> Option<DownloadInfo> {
        let mut next = None;
        if matches!(mode, NextDownload::SameCategoryFirst | NextDownload::SameCategory) {
            next = self.first_paused(|file| file.category() == category);
        }
        if next.is_none() && matches!(mode, NextDownload::Any | NextDownload::SameCategoryFirst) {
            next = self.first_paused(|_| true);
        }
        let hash = next?;
        self.resume(&hash, ours);
        self.info(&hash)
    }

    /// The data file of the download, which a player can show while it grows once the start of it has come.
    pub fn data_file(&self, hash: &[u8; 16]) -> Option<PathBuf> {
        let file = &self.queue.get(hash).filter(|_| self.entries.contains_key(hash))?.file;
        file.is_complete_between(0, file.size().min(PREVIEW_START)).then(|| file.data_file())
    }

    /// The download of `hash`, whose checked parts other clients may download.
    pub fn find(&self, hash: &[u8; 16]) -> Option<&download_queue::Download> {
        self.queue.get(hash).filter(|_| self.entries.contains_key(hash))
    }

    /// The download of `hash` to change, as the tests do.
    pub fn find_mut(&mut self, hash: &[u8; 16]) -> Option<&mut download_queue::Download> {
        if !self.entries.contains_key(hash) {
            return None;
        }
        self.queue.get_mut(hash)
    }

    /// Adds a source the user typed, LAN addresses too, as long as the IP filter allows it; false for an unknown
    /// download or a refused source.
    pub fn add_source_by_hand(&mut self, hash: &[u8; 16], address: SocketAddr, settings: &Settings) -> bool {
        let Some(before) = self.entries.get(hash).map(|entry| entry.sources.len()) else {
            return false;
        };
        self.add_link_source(hash, address, settings);
        if self.entries.get(hash).is_some_and(|entry| entry.sources.len() > before) {
            self.progress(hash);
            return true;
        }
        false
    }

    /// Drops the sources the IP filter now refuses, ending their sessions; returns how many. LAN sources stay: some
    /// were given by the user.
    pub fn drop_blocked_sources(&mut self, ours: &Hello) -> usize {
        let blocked: Vec<SocketAddr> = self
            .clients
            .keys()
            .filter(|address| match address.ip() {
                IpAddr::V4(ip) => (self.hooks.blocked)(u32::from(ip)),
                IpAddr::V6(_) => false,
            })
            .copied()
            .collect();
        self.drop_sources(&blocked, "the IP filter blocks it", ours)
    }

    /// Drops the sources at `ip`, which sent corrupt data, and keeps them out while [`CORRUPT_BAN`] lasts.
    pub fn ban_corrupt_sender(&mut self, ip: IpAddr, ours: &Hello) {
        tracing::info!("{ip} sent corrupt data: its sources are dropped");
        let sender = ipv6::network(ip);
        self.corrupt_senders.insert(sender, instants::later((self.clock)(), CORRUPT_BAN));
        let at: Vec<SocketAddr> =
            self.clients.keys().filter(|address| ipv6::network(address.ip()) == sender).copied().collect();
        self.drop_sources(&at, "it sent corrupt data", ours);
        self.events.push(DownloadEvent::CorruptSender(ip));
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
                    asked_for: a4af.then_some(client.current),
                    asked_for_name: if a4af {
                        self.queue.get(&client.current).map(|d| d.file.name().to_owned()).unwrap_or_default()
                    } else {
                        String::new()
                    },
                });
            }
        }
        all
    }

    /// The active downloads to ask other servers about, the highest priority first: those below three quarters of the
    /// source limit, which leaves room for the sources other ways bring.
    pub fn wanting_sources(&self, settings: &Settings) -> Vec<SourceQuery> {
        let enough = settings.max_sources_per_file.saturating_mul(3) / 4;
        self.by_priority()
            .into_iter()
            .filter(|hash| self.entries.get(hash).is_some_and(|entry| entry.sources.len() < enough))
            .filter_map(|hash| {
                self.queue.get(&hash).filter(|d| d.is_active()).map(|d| SourceQuery { file: hash, size: d.file.size() })
            })
            .collect()
    }

    /// A server connection was made: it is asked for the sources of every download.
    pub fn server_connected(&mut self) {
        let now = (self.clock)();
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
            if !usable(ip, source.port, settings, our_id) || !self.allowed(IpAddr::V4(Ipv4Addr::from(ip))) {
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
        let now = (self.clock)();
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
        self.comment_filter = CommentFilter::of(&settings.comment_filter);
        self.settings = Some(settings.clone());
        self.our_id = server.client_id();
        if !std::mem::replace(&mut self.known_taken, true) {
            let hashes: Vec<[u8; 16]> = self.entries.keys().copied().collect();
            for hash in hashes {
                self.take_known_sources(&hash, server.client_id(), settings, ours);
            }
        }
        if now >= self.next_sources_kept {
            self.next_sources_kept = instants::later(now, KEEP_SOURCES_INTERVAL);
            self.keep_known_sources();
        }
        let mut starting = self.clients.values().filter(|c| c.session.as_ref().is_some_and(is_starting)).count();
        self.corrupt_senders.retain(|_, until| now < *until);
        // Only the time it could have received counts: running, with room on the disk, and with eD2K up.
        let online = server.client_id() != 0;
        let counted = now.saturating_duration_since(self.idle_counted).as_secs();
        self.idle_counted = instants::later(self.idle_counted, Duration::from_secs(counted));
        let limit = u64::from(settings.auto_delete_days).saturating_mul(24 * 60 * 60);
        let mut stale = Vec::new();
        for hash in self.by_priority() {
            if self.idle_too_long(&hash, online, counted, limit) {
                stale.push(hash);
            }
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
        for hash in stale {
            self.delete_idle(&hash, settings.auto_delete_days, ours);
        }
        self.settle(ours);
    }

    // Whether a running download received nothing for `limit` seconds, counting `seconds` more of idle time unless it
    // received data; 0 counts no limit.
    fn idle_too_long(&mut self, hash: &[u8; 16], online: bool, seconds: u64, limit: u64) -> bool {
        let received = self.entries.get(hash).is_some_and(|entry| entry.received > 0);
        let Some((active, idle)) = self.queue.get(hash).map(|d| (d.is_active(), d.file.idle_seconds())) else {
            return false;
        };
        if received {
            self.queue.set_idle_seconds(hash, 0);
        } else if online && active {
            self.queue.set_idle_seconds(hash, idle.saturating_add(seconds));
        }
        limit > 0 && active && self.queue.get(hash).is_some_and(|d| d.file.idle_seconds() >= limit)
    }

    fn delete_idle(&mut self, hash: &[u8; 16], days: u32, ours: &Hello) {
        let Some(file) = self.queue.get(hash).map(|d| &d.file) else {
            return;
        };
        let name = file.name().to_owned();
        let link =
            FileLink::new(name.clone(), file.size(), file.hash(), file.aich_hash(), Vec::new(), Vec::new(), Vec::new());
        if !link.is_ok_and(|link| (self.hooks.keep_link)(&link)) {
            // Without its link kept the download stays, and counts its days again.
            self.queue.set_idle_seconds(hash, 0);
            return;
        }
        tracing::info!("Deleting {name}: it ran {days} days without receiving data");
        self.cancel(hash, ours);
    }

    // Takes the sources at `addresses` off every download, ending their sessions; returns how many there were.
    fn drop_sources(&mut self, addresses: &[SocketAddr], reason: &str, ours: &Hello) -> usize {
        let mut dropped = 0usize;
        for address in addresses {
            let Some(hashes) = self.clients.get(address).map(|client| client.sources.clone()) else {
                continue;
            };
            tracing::debug!("Dropping the source {address}: {reason}");
            for hash in hashes {
                self.detach(&hash, address, ours);
                dropped = dropped.saturating_add(1);
            }
        }
        self.settle(ours);
        dropped
    }

    // Whether a source at `ip` may be taken: neither blocked by the IP filter nor at an address that sent corrupt data.
    fn allowed(&self, ip: IpAddr) -> bool {
        let blocked = match ip {
            IpAddr::V4(ip) => (self.hooks.blocked)(u32::from(ip)),
            IpAddr::V6(_) => false,
        };
        let now = (self.clock)();
        !blocked && !self.corrupt_senders.get(&ipv6::network(ip)).is_some_and(|until| now < *until)
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

    /// The client to connect to is connected to us already, for our files: it is asked over its own connection.
    pub fn connected_over(&mut self, address: SocketAddr, link: LinkHandle, hello: Hello, ours: &Hello) {
        let connecting =
            self.clients.get(&address).and_then(|c| c.session.as_ref()).is_some_and(|s| s.state() == State::Connecting);
        if !connecting {
            return;
        }
        self.links.insert(link.id(), address);
        self.with_session(&address, ours, |session, ctx| session.adopt(link, hello, None, false, ctx));
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
        if self.known_taken {
            self.keep_known_sources();
        }
        let hashes: Vec<[u8; 16]> = self.entries.keys().copied().collect();
        for hash in hashes {
            self.close_sessions(&hash, "shutting down", ours);
        }
        self.queue.close();
    }

    /// What the sources of the download said of the file.
    pub fn comments(&self, hash: &[u8; 16]) -> Vec<PeerComment> {
        self.entries.get(hash).map(|entry| entry.comments.values().cloned().collect()).unwrap_or_default()
    }

    // A comment holding a word of the filter is left out, as spam; a source keeps its latest one.
    fn commented(&mut self, hash: &[u8; 16], address: SocketAddr, comment: FileComment) {
        if comment.is_empty() || self.comment_filter.leaves_out(comment.text()) {
            return;
        }
        let user = self
            .clients
            .get(&address)
            .and_then(|c| c.session.as_ref())
            .and_then(DownloadSession::peer)
            .and_then(Hello::nick)
            .map_or_else(|| address.ip().to_string(), str::to_owned);
        let (Some(entry), Some(download)) = (self.entries.get_mut(hash), self.queue.get(hash)) else {
            return;
        };
        let file_name = download.file.name().to_owned();
        entry.comments.shift_remove(&address);
        entry.comments.insert(address, PeerComment { user, file_name, comment });
        while entry.comments.len() > MAX_COMMENTS {
            entry.comments.shift_remove_index(0);
        }
    }

    /// Downloads, sources and sessions, for diagnostics.
    pub fn describe(&self) -> String {
        let mut text = String::new();
        for (hash, entry) in &self.entries {
            let Some(file) = self.queue.get(hash).map(|d| &d.file) else {
                continue;
            };
            text.push_str(&format!("{} missing {}:", file.name(), file.missing_bytes()));
            for address in entry.sources.keys() {
                let Some(client) = self.clients.get(address) else {
                    continue;
                };
                let shown = match client.session.as_ref().filter(|_| client.current == *hash) {
                    Some(session) => session.describe(),
                    None if client.current == *hash => address.to_string(),
                    None => format!("{address} A4AF"),
                };
                text.push(' ');
                text.push_str(&shown);
                if client.failures > 0 {
                    text.push_str(&format!(" (failed {})", client.failures));
                }
            }
            text.push('\n');
        }
        text
    }

    /// Sources of an active download that we spoke with, for other clients asking for sources: HighIDs with a known
    /// user hash, and those at a public IPv6 address alone.
    pub fn exchangeable(&self, hash: &[u8; 16]) -> Vec<exchange::Source> {
        let (Some(entry), true) = (self.entries.get(hash), self.queue.get(hash).is_some_and(|d| d.is_active())) else {
            return Vec::new();
        };
        let mut sources = Vec::new();
        for (address, source) in &entry.sources {
            let Some(client) = self.clients.get(address) else {
                continue;
            };
            // A firewalled source passed on by its address would be tried in vain.
            let Some(user_hash) = client.user_hash.filter(|_| source.parts.is_some() && client.low_id == 0) else {
                continue;
            };
            match address.ip() {
                IpAddr::V4(ip) => sources.push(exchange::Source {
                    ip: u32::from(ip),
                    low_id: 0,
                    port: address.port(),
                    server_ip: 0,
                    server_port: 0,
                    user_hash: Some(user_hash),
                    crypt_options: client.crypt_options,
                    ipv6: None,
                }),
                IpAddr::V6(ip) if ipv6::is_public(&ip) => {
                    sources.push(exchange::Source::ipv6_only(ip, address.port(), Some(user_hash)));
                }
                IpAddr::V6(_) => {}
            }
        }
        sources
    }

    // The sources kept from the last run, unless the download is stopped or they are now refused.
    fn take_known_sources(&mut self, hash: &[u8; 16], our_id: u32, settings: &Settings, ours: &Hello) {
        let Some(file) = self.queue.get(hash).map(|d| &d.file).filter(|file| file.status() != Status::Stopped) else {
            return;
        };
        for known in known_sources::young(file.known_sources(), wall_seconds()) {
            let first = known.ip >> 24;
            let ip = IpAddr::V4(Ipv4Addr::from(known.ip));
            if known.user_hash == Some(ours.user_hash)
                || known.port == 0
                || first == 0
                || first >= 224
                || !self.allowed(ip)
                || (known.port == settings.listen_port && ipv4::to_ed2k(known.ip) == our_id)
            {
                continue;
            }
            let address = SocketAddr::new(ip, known.port);
            if self.add_source(hash, address, settings)
                && let Some(client) = self.clients.get_mut(&address)
                && client.user_hash.is_none()
            {
                client.user_hash = known.user_hash;
                client.crypt_options = known.crypt_options;
            }
        }
    }

    // The sources reached directly that served each download, best first, joined by those kept before; a LowID's id
    // changes with its server, so it is found again instead.
    fn keep_known_sources(&mut self) {
        let now = wall_seconds();
        let mut kept = Vec::new();
        for (hash, entry) in &self.entries {
            let Some(file) = self.queue.get(hash).map(|d| &d.file).filter(|file| file.status() != Status::Stopped)
            else {
                continue;
            };
            let mut candidates = Vec::new();
            for (address, source) in &entry.sources {
                let (Some(client), IpAddr::V4(ip)) = (self.clients.get(address), address.ip()) else {
                    continue;
                };
                let session = client.session.as_ref().filter(|_| client.current == *hash);
                let rank = session.map_or(source.rank, DownloadSession::queue_rank);
                let standing = if session.is_some_and(|s| s.state() == State::Downloading) {
                    Standing::Downloading
                } else if rank > 0 {
                    Standing::Queued
                } else if source.parts.is_some() || source.received > 0 {
                    Standing::Answered
                } else {
                    continue;
                };
                if client.low_id != 0 {
                    continue;
                }
                let known = KnownSource {
                    ip: u32::from(ip),
                    port: address.port(),
                    seen: now,
                    user_hash: client.user_hash,
                    crypt_options: client.crypt_options,
                };
                let parts = source.parts.as_deref().map_or(0, |parts| parts.iter().filter(|has| **has).count());
                candidates.push(Candidate {
                    source: known,
                    standing,
                    rank,
                    parts: u32::try_from(parts).unwrap_or(u32::MAX),
                });
            }
            kept.push((*hash, known_sources::choose(&candidates, file.known_sources(), now)));
        }
        for (hash, sources) in kept {
            self.queue.set_known_sources(&hash, &sources);
        }
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
            status: if file.status() == Status::Downloading && self.queue.is_space_short() {
                DownloadStatus::NoSpace
            } else {
                status(file.status())
            },
            priority: file.priority(),
            category: file.category(),
            sources: entry.sources.len(),
            transferring,
            bytes_per_second: entry.rate(),
            parts: file.map(),
            preview_first: file.preview_first(),
            auto_swap: file.auto_swap(),
        })
    }

    // The downloads, the highest effective priority first, in the order they were added among equals.
    fn by_priority(&self) -> Vec<[u8; 16]> {
        let mut sorted: Vec<([u8; 16], DownloadPriority)> = self
            .entries
            .iter()
            .filter_map(|(hash, entry)| {
                self.queue.get(hash).map(|d| (*hash, d.file.priority().effective(entry.sources.len())))
            })
            .collect();
        sorted.sort_by_key(|(_, priority)| std::cmp::Reverse(priority.rank()));
        sorted.into_iter().map(|(hash, _)| hash).collect()
    }

    // The paused or stopped download `wanted` takes of highest priority, the first among equals; none a scheduler rule
    // stopped.
    fn first_paused(&self, wanted: impl Fn(&PartFile) -> bool) -> Option<[u8; 16]> {
        let mut best: Option<([u8; 16], DownloadPriority)> = None;
        for (hash, entry) in &self.entries {
            let Some(file) = self.queue.get(hash).map(|d| &d.file) else {
                continue;
            };
            let priority = file.priority().effective(entry.sources.len());
            if matches!(file.status(), Status::Paused | Status::Stopped)
                && !entry.stopped_by_schedule
                && wanted(file)
                && best.is_none_or(|(_, top)| priority.rank() > top.rank())
            {
                best = Some((*hash, priority));
            }
        }
        best.map(|(hash, _)| hash)
    }

    fn category(&self, id: u32) -> Option<&Category> {
        self.categories.iter().find(|c| c.id == id)
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
        let Self { queue, clients, random, clock, .. } = self;
        if let Some(session) = clients.get_mut(address).and_then(|c| c.session.as_mut()) {
            let mut ctx = Context { queue, ours, random, now: clock() };
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
                self.entries.insert(hash, Entry::new(parts, (self.clock)()));
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
            QueueEvent::Corrupt { hash, part } => {
                if let Some(info) = self.info(&hash) {
                    self.events.push(DownloadEvent::CorruptPart { info, part });
                }
                self.recover(&hash, part);
            }
            QueueEvent::CorruptSenders { ips, .. } => {
                for ip in ips {
                    self.ban_corrupt_sender(ip, ours);
                }
            }
            QueueEvent::DiskSpace(enough) => {
                let now = (self.clock)();
                let hashes: Vec<[u8; 16]> = self.entries.keys().copied().collect();
                for hash in hashes {
                    if let Some(entry) = self.entries.get_mut(&hash) {
                        entry.changed = true;
                        if enough {
                            entry.next_server_ask = now;
                        }
                    }
                    if !enough {
                        self.close_sessions(&hash, "not enough free disk space", ours);
                    }
                }
                if enough {
                    for client in self.clients.values_mut() {
                        client.next_ask = earliest_ask(client, now);
                    }
                }
                self.events.push(DownloadEvent::DiskSpace(enough));
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
            Note::Commented(comment) => self.commented(&hash, address, comment),
            Note::SourcesWanted { extended } => {
                if self.may_sources_be_asked(&hash, &address) {
                    self.with_session(&address, ours, |session, _| session.ask_sources(extended));
                }
            }
            Note::SourcesFound(answer) => self.sources_found(&address, &answer, ours),
            Note::NothingHere { has_file } => match self.another(&address, has_file, ours) {
                Some(next) => self.with_session(&address, ours, |session, ctx| session.ask_for(next, ctx)),
                None => self.with_session(&address, ours, |session, ctx| session.give_up(has_file, ctx)),
            },
        }
    }

    // Rare files may ask a source every 40 minutes, others every 160; a file waits for answers.
    fn may_sources_be_asked(&mut self, hash: &[u8; 16], address: &SocketAddr) -> bool {
        let now = (self.clock)();
        let max_sources = self.settings.as_ref().map_or(0, |settings| settings.max_sources_per_file);
        let Some(entry) = self.entries.get_mut(hash) else {
            return false;
        };
        let known = entry.sources.len();
        if known >= (max_sources.saturating_mul(9) / 10).min(SX_MAX_SOURCES) {
            return false;
        }
        let since_answer = entry.sources_answered.map_or(Duration::MAX, |at| now.saturating_duration_since(at));
        let Some(source) = entry.sources.get_mut(address) else {
            return false;
        };
        let asked = source.sources_asked.map(|at| now.saturating_duration_since(at));
        let rare_due = asked.is_none_or(|since| since >= SX_RARE_INTERVAL);
        let common_due = asked.is_none_or(|since| since >= SX_INTERVAL);
        let due = (known <= SX_VERY_RARE && rare_due)
            || (known <= SX_RARE && rare_due && since_answer >= SX_RARE_ANSWER_GAP)
            || (common_due && since_answer >= SX_ANSWER_GAP);
        if due {
            source.sources_asked = Some(now);
        }
        due
    }

    // The sources another client told of: those it may pass on and we can reach, IPv6 ones when we have IPv6 too.
    fn sources_found(&mut self, from: &SocketAddr, answer: &exchange::Answer, ours: &Hello) {
        let Some(settings) = self.settings.clone() else {
            return;
        };
        if !self.queue.get(&answer.file).is_some_and(|d| d.is_active()) || !self.entries.contains_key(&answer.file) {
            return;
        }
        if let Some(entry) = self.entries.get_mut(&answer.file) {
            entry.sources_answered = Some((self.clock)());
        }
        let our_ipv6 = ours.ipv6();
        let mut added = 0usize;
        for told in &answer.sources {
            if told.user_hash == Some(ours.user_hash) || (told.ipv6.is_some() && told.ipv6 == our_ipv6) {
                continue;
            }
            let address = if told.is_low_id() || told.is_ipv6_only() {
                // Reached at its IPv6 address, when we have IPv6 too; a LowID would otherwise need a callback through
                // its server, and ours may differ.
                let Some(ipv6) = told.ipv6.filter(|_| our_ipv6.is_some() && told.port != 0) else {
                    continue;
                };
                if !self.allowed(IpAddr::V6(ipv6)) {
                    continue;
                }
                SocketAddr::new(IpAddr::V6(ipv6), told.port)
            } else {
                if !usable(told.ip, told.port, &settings, self.our_id)
                    || !self.allowed(IpAddr::V4(Ipv4Addr::from(told.ip)))
                {
                    continue;
                }
                SocketAddr::new(IpAddr::V4(Ipv4Addr::from(told.ip)), told.port)
            };
            if self.add_source(&answer.file, address, &settings) {
                if let Some(client) = self.clients.get_mut(&address) {
                    if client.user_hash.is_none() && told.user_hash.is_some_and(|hash| hash != [0; 16]) {
                        client.user_hash = told.user_hash;
                        client.crypt_options = told.crypt_options;
                    }
                    if told.ipv6.is_some() && address.is_ipv4() {
                        client.ipv6 = told.ipv6;
                    }
                }
                added = added.saturating_add(1);
            }
        }
        tracing::debug!("{added} of {} sources from {from} are new", answer.sources.len());
    }

    // The client has nothing for the download its session asks for: the next of its downloads that may have something,
    // asked for on the same connection; a download it lacks loses it as a source.
    fn another(&mut self, address: &SocketAddr, has_file: bool, ours: &Hello) -> Option<[u8; 16]> {
        let now = (self.clock)();
        let client = self.clients.get_mut(address)?;
        let from = client.current;
        if !client.tried.contains(&from) {
            client.tried.push(from);
        }
        let tried = client.tried.clone();
        let parts = client.session.as_ref().and_then(|s| s.source_parts().map(<[bool]>::to_vec));
        if let Some(parts) = parts {
            self.set_parts(&from, address, Some(parts));
        }
        let next = self.best(address, now, &tried)?;
        if self.has_nothing(&next, address, now) || self.is_away(&next, address, now) {
            return None;
        }
        self.make_current(address, next);
        if let Some(client) = self.clients.get_mut(address) {
            client.requested = Some(next);
            client.tried.push(next);
        }
        if !has_file {
            self.detach(&from, address, ours);
        }
        Some(next)
    }

    // The download of the client's sources it is best asked for, of those not in `skipped`: one not swapped away from
    // by hand, one that may have parts we need, of a download that takes A4AF sources by itself, of a higher priority,
    // whose parts we know, the current one, the first found; none when no download of it runs.
    fn best(&self, address: &SocketAddr, now: Instant, skipped: &[[u8; 16]]) -> Option<[u8; 16]> {
        let client = self.clients.get(address)?;
        let mut best: Option<([u8; 16], Standing4af)> = None;
        for hash in client.sources.iter().filter(|hash| !skipped.contains(hash)) {
            let Some(file) = self.queue.get(hash).filter(|d| d.is_active()).map(|d| &d.file) else {
                continue;
            };
            let Some(entry) = self.entries.get(hash) else {
                continue;
            };
            let Some(source) = entry.sources.get(address) else {
                continue;
            };
            let key = (
                !self.is_away(hash, address, now),
                !self.has_nothing(hash, address, now),
                file.auto_swap(),
                file.priority().effective(entry.sources.len()).rank(),
                source.parts.is_some(),
                *hash == client.current,
            );
            if best.is_none_or(|(_, top)| key > top) {
                best = Some((*hash, key));
            }
        }
        best.map(|(hash, _)| hash)
    }

    fn is_away(&self, hash: &[u8; 16], address: &SocketAddr, now: Instant) -> bool {
        self.source(hash, address).and_then(|source| source.away_until).is_some_and(|until| now < until)
    }

    // The client told lately that it has no part of the file we need.
    fn has_nothing(&self, hash: &[u8; 16], address: &SocketAddr, now: Instant) -> bool {
        let (Some(source), Some(download)) = (self.source(hash, address), self.queue.get(hash)) else {
            return false;
        };
        let (Some(parts), Some(at)) = (&source.parts, source.parts_at) else {
            return false;
        };
        now.saturating_duration_since(at) < NO_PARTS_WAIT && !needed(&download.file, parts)
    }

    fn source(&self, hash: &[u8; 16], address: &SocketAddr) -> Option<&Source> {
        self.entries.get(hash).and_then(|entry| entry.sources.get(address))
    }

    // A place in the client's queue that a swap could lose: a slot, or a good rank.
    fn holds_place(&self, address: &SocketAddr) -> bool {
        let Some(client) = self.clients.get(address) else {
            return false;
        };
        let session = client.session.as_ref();
        if session.is_some_and(|s| s.state() == State::Downloading) {
            return true;
        }
        let told = session.map_or(0, DownloadSession::queue_rank);
        let rank = if told > 0 { told } else { self.source(&client.current, address).map_or(0, |s| s.rank) };
        rank > 0 && rank <= GOOD_RANK
    }

    // At its time the client is asked for the download it is best asked for: the one swapped to by hand, or the one
    // asked for while we keep a good place in its queue for it, otherwise the best of its sources.
    fn choose_to_ask(&self, address: &SocketAddr, now: Instant) -> Option<[u8; 16]> {
        let client = self.clients.get(address)?;
        if client.pinned || self.holds_place(address) {
            return Some(client.current);
        }
        Some(self.best(address, now, &[]).unwrap_or(client.current))
    }

    fn make_current(&mut self, address: &SocketAddr, hash: [u8; 16]) {
        let Some(client) = self.clients.get_mut(address) else {
            return;
        };
        if client.current != hash {
            let old = std::mem::replace(&mut client.current, hash);
            for changed in [old, hash] {
                if let Some(entry) = self.entries.get_mut(&changed) {
                    entry.changed = true;
                }
            }
        }
    }

    // Asked for another download, at the client's time but no later than the shortest wait sources allow.
    fn swap(&mut self, address: &SocketAddr, to: [u8; 16], now: Instant) {
        self.make_current(address, to);
        if let Some(client) = self.clients.get_mut(address) {
            client.pinned = false;
            let allowed = earliest_ask(client, now);
            if client.next_ask > allowed {
                client.next_ask = allowed;
            }
        }
    }

    // The download does not run any more: the clients asked for it are asked for another they are sources of.
    fn release(&mut self, hash: &[u8; 16]) {
        let now = (self.clock)();
        let addresses: Vec<SocketAddr> =
            self.entries.get(hash).map(|e| e.sources.keys().copied().collect()).unwrap_or_default();
        for address in addresses {
            let idle = self.clients.get(&address).is_some_and(|c| c.current == *hash && c.session.is_none());
            if idle && let Some(next) = self.best(&address, now, &[]) {
                self.swap(&address, next, now);
            }
        }
    }

    // By hand a client waiting in a queue moves too; one being asked or sending data does not.
    fn swap_by_hand(&mut self, to: [u8; 16], address: &SocketAddr, ours: &Hello) -> bool {
        let Some(state) = self.clients.get(address).map(|c| c.session.as_ref().map(DownloadSession::state)) else {
            return false;
        };
        match state {
            Some(State::Queued | State::Ended) => {
                self.with_session(address, ours, |session, ctx| session.close("swapped to another download", ctx));
                self.settle(ours);
            }
            Some(_) => return false,
            None => {}
        }
        let now = (self.clock)();
        if let Some(source) = self.entries.get_mut(&to).and_then(|e| e.sources.get_mut(address)) {
            source.away_until = None;
        }
        self.swap(address, to, now);
        if let Some(client) = self.clients.get_mut(address) {
            client.pinned = true;
        }
        tracing::debug!("{address} swapped by hand");
        true
    }

    /// Swaps the A4AF sources of the download to it: each client is asked for it next, unless it sends us data or is
    /// being asked now; returns how many were swapped.
    pub fn swap_sources_here(&mut self, hash: &[u8; 16], ours: &Hello) -> usize {
        if !self.entries.contains_key(hash) || !self.queue.get(hash).is_some_and(|d| d.is_active()) {
            return 0;
        }
        let addresses: Vec<SocketAddr> =
            self.entries.get(hash).map(|e| e.sources.keys().copied().collect()).unwrap_or_default();
        let mut swapped = 0usize;
        for address in addresses {
            let a4af = self.clients.get(&address).is_some_and(|c| c.current != *hash);
            if a4af && self.swap_by_hand(*hash, &address, ours) {
                swapped = swapped.saturating_add(1);
            }
        }
        swapped
    }

    /// Swaps the clients asked for the download to another download they are sources of, which goes first for a
    /// while; returns how many were swapped.
    pub fn swap_sources_away(&mut self, hash: &[u8; 16], ours: &Hello) -> usize {
        if !self.entries.contains_key(hash) {
            return 0;
        }
        let now = (self.clock)();
        let addresses: Vec<SocketAddr> =
            self.entries.get(hash).map(|e| e.sources.keys().copied().collect()).unwrap_or_default();
        let mut swapped = 0usize;
        for address in addresses {
            if !self.clients.get(&address).is_some_and(|c| c.current == *hash) {
                continue;
            }
            if let Some(next) = self.best(&address, now, &[*hash])
                && self.swap_by_hand(next, &address, ours)
            {
                if let Some(source) = self.entries.get_mut(hash).and_then(|e| e.sources.get_mut(&address)) {
                    source.away_until = Some(instants::later(now, AWAY));
                }
                swapped = swapped.saturating_add(1);
            }
        }
        swapped
    }

    /// Swaps the client at `address`, an A4AF source of the download, to it; false if it is none, the download does
    /// not run or the client cannot move now.
    pub fn swap_source(&mut self, hash: &[u8; 16], address: SocketAddr, ours: &Hello) -> bool {
        let a4af = self.entries.get(hash).is_some_and(|e| e.sources.contains_key(&address))
            && self.queue.get(hash).is_some_and(|d| d.is_active())
            && self.clients.get(&address).is_some_and(|c| c.current != *hash);
        a4af && self.swap_by_hand(*hash, &address, ours)
    }

    /// Has the A4AF sources of the download swapped to it whenever they are free to move, or stops; kept with the
    /// download. False if unknown.
    pub fn set_auto_swap(&mut self, hash: &[u8; 16], value: bool) -> bool {
        if !self.entries.contains_key(hash) {
            return false;
        }
        self.queue.set_auto_swap(hash, value);
        if value && self.queue.get(hash).is_some_and(|d| d.is_active()) {
            // Those free now move at once, keeping their clock.
            let now = (self.clock)();
            let addresses: Vec<SocketAddr> =
                self.entries.get(hash).map(|e| e.sources.keys().copied().collect()).unwrap_or_default();
            for address in addresses {
                let free =
                    self.clients.get(&address).is_some_and(|c| c.current != *hash && c.session.is_none() && !c.pinned);
                if free && !self.holds_place(&address) && self.best(&address, now, &[]) == Some(*hash) {
                    self.make_current(&address, *hash);
                }
            }
        }
        self.progress(hash);
        true
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
        let now = (self.clock)();
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
        let now = (self.clock)();
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
            let Self { queue, random, clock, .. } = self;
            session.close("the source called back", &mut Context { queue, ours, random, now: clock() });
        }
        // The file it was asked for last needs no request again, another does.
        let known = self
            .clients
            .get(&address)
            .filter(|c| c.requested == Some(hash))
            .and_then(|_| self.entries.get(&hash))
            .and_then(|e| e.sources.get(&address))
            .and_then(|s| s.parts.clone());
        let now = (self.clock)();
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
        self.callbacks.insert(low_id, Callback { file: *file, user_hash, since: (self.clock)(), requested: false });
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
            let chosen = self.choose_to_ask(&address, now).unwrap_or(*hash);
            self.make_current(&address, chosen);
            let Some(client) = self.clients.get_mut(&address) else {
                continue;
            };
            client.pinned = false;
            if client.low_id != 0 {
                // Firewalled: asked again through the server, while it keeps the same LowID.
                client.next_ask = instants::later(now, REASK_INTERVAL);
                let (low_id, user_hash, crypt) = (client.low_id, client.user_hash, client.crypt_options);
                if server.client_id() >= LOW_ID_LIMIT {
                    self.ask_callback(&chosen, low_id, user_hash, crypt, settings);
                }
                continue;
            }
            client.asked = true;
            client.last_ask = now;
            client.next_ask = instants::later(now, REASK_INTERVAL);
            client.requested = Some(chosen);
            client.tried = vec![chosen];
            client.session = Some(DownloadSession::new(chosen, address, now));
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

    // No client is at 0.x.x.x, at a multicast address or above, nor at an IPv6 address no host has; the IP filter
    // applies.
    fn add_link_source(&mut self, hash: &[u8; 16], address: SocketAddr, settings: &Settings) {
        let fits = match address.ip() {
            IpAddr::V4(ip) => {
                let first = ip.octets()[0];
                first != 0 && first < 224 && !(self.hooks.blocked)(u32::from(ip))
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
        let now = (self.clock)();
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
            pinned: false,
            ipv6: None,
            tried: Vec::new(),
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
        client.tried.retain(|tried| tried != hash);
        if client.requested == Some(*hash) {
            client.requested = None;
        }
        if client.current == *hash
            && let Some(mut session) = client.session.take()
        {
            if let Some(link) = session.link() {
                self.links.remove(&link.id());
            }
            let Self { queue, random, clock, .. } = self;
            session.close("the source is dropped", &mut Context { queue, ours, random, now: clock() });
        }
        let Some(client) = self.clients.get_mut(address) else {
            return;
        };
        match client.sources.first().copied() {
            None => {
                self.clients.shift_remove(address);
            }
            Some(first) if client.current == *hash => {
                client.current = first;
                let now = (self.clock)();
                let next = self.best(address, now, &[]).unwrap_or(first);
                self.swap(address, next, now);
            }
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
        source.parts_at = Some((self.clock)());
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
        entry.holds.push((part, instants::later((self.clock)(), RECOVERY_WAIT)));
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

// The soonest a client may be asked again, without being taken for one that asks too often.
fn earliest_ask(client: &Client, now: Instant) -> Instant {
    if client.asked { now.max(instants::later(client.last_ask, EARLIEST_REASK)) } else { now }
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

/// Whether a source at `ip` (as [`crate::util::ipv4`] holds it) may be passed on to the client at `asker`: a public
/// address to anyone, a LAN address within the LAN or this machine, a loopback address within this machine.
pub fn passes_on(ip: u32, asker: IpAddr) -> bool {
    let first = ip >> 24;
    if first == 0 || first >= 224 {
        return false;
    }
    let loopback = first == 127;
    if !loopback && !ipv4::is_lan(ip) {
        return true;
    }
    let IpAddr::V4(asker) = asker else {
        return false;
    };
    let at = u32::from(asker);
    let asker_loopback = at >> 24 == 127;
    if loopback { asker_loopback } else { asker_loopback || ipv4::is_lan(at) }
}

fn wall_seconds() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |since| since.as_secs())
}

/// The address to reach a client at over IPv6: when both of us have it and IPv4 does not do (`wanted`), as when the
/// client is firewalled there or did not answer on it.
pub fn ipv6_route(we_have_ipv6: bool, theirs: Option<Ipv6Addr>, port: u16, wanted: bool) -> Option<SocketAddr> {
    theirs.filter(|_| we_have_ipv6 && wanted).map(|address| SocketAddr::new(IpAddr::V6(address), port))
}
