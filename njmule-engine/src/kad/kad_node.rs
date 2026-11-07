// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Our Kad node: answers other nodes, keeps the routing table fresh, runs lookups for searches and publishes, indexes
//! what others publish to us and checks whether we can be reached. What searches find comes out as [`KadEvent`]s.

use std::collections::{HashMap, HashSet};
use std::fs;
use std::io;
use std::net::{Ipv4Addr, Ipv6Addr};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use indexmap::IndexMap;
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};

use crate::api::file_comment::FileComment;
use crate::api::kad_contact_info::KadContactInfo;
use crate::api::kad_search_info::{self, KadSearchInfo};
use crate::api::search_result::{Media, SearchResult};
use crate::kad::kad_contact::{self, KadContact};
use crate::kad::kad_identity::KadIdentity;
use crate::kad::kad_index::{self, KadIndex, Matcher};
use crate::kad::kad_notes::{self, Note};
use crate::kad::kad_publisher;
use crate::kad::keywords;
use crate::kad::lookup::{self, AddressTest, Kind, Lookup, Requested, Requests};
use crate::kad::packet_tracking::PacketTracking;
use crate::kad::proven_ids::ProvenIds;
use crate::kad::response_times::ResponseTimes;
use crate::kad::routing_table::{self, Outcome, RoutingTable};
use crate::kad::udp_firewall_test::UdpFirewallTest;
use crate::kad::{kad_lower_case, uint128::UInt128};
use crate::persist::atomic_files;
use crate::persist::format::kad_index_files;
use crate::persist::format::nodes_dat::{self, UdpKey};
use crate::protocol::io::{DecodeError, PacketReader, PacketWriter};
use crate::protocol::kad::kad_messages::{self, Contact, Entry, Sender as SelfInfo};
use crate::protocol::packet::Packet;
use crate::protocol::packet::opcodes::kad;
use crate::protocol::tag::{Tag, TagName, file_tag, media_tags, tags};
use crate::search::search_expr::SearchExpr;
use crate::search::search_tree_codec;
use crate::util::{ipv4, ipv6};

/// Sends Kad packets, obfuscated with the receiver's id or key when given.
pub trait Transport: Send {
    fn send(
        &mut self,
        ip: u32,
        port: u16,
        packet: &Packet,
        receiver_id: Option<UInt128>,
        receiver_key: u32,
        sender_key: u32,
    );
}

/// What the node needs from the client around it.
pub trait Host: Send {
    /// Connects to `ip:port` over TCP to prove to that node it is reachable.
    fn check_reachable(&mut self, ip: u32, port: u16, user_hash: [u8; 16], connect_options: u8);

    /// Connects to the helper at `ip:tcp_port` and asks it to send a datagram to our UDP ports, obfuscated with
    /// `sender_key`; then tells [`KadNode::udp_check_ended`] whether the request went out.
    fn check_udp(&mut self, _ip: u32, _tcp_port: u16, _internal_port: u16, _external_port: u16, _sender_key: u32) {}

    /// Whether the client at `ip` deals with us already (a download, an upload): it cannot be a helper.
    fn is_known_client(&self, _ip: u32) -> bool {
        false
    }

    /// Whether other clients reach us anyway, through a HighID on the server.
    fn has_high_id(&self) -> bool {
        false
    }
}

/// No buddy, ours being connected to, ours connected, or a node we serve.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BuddyState {
    None,
    Connecting,
    Connected,
    Serving,
}

/// The TCP side of buddies: the link to our buddy while we are firewalled, or to the firewalled node we serve. A buddy
/// relays the callback requests of other clients to the node it serves.
pub trait Buddies: Send {
    fn state(&self) -> BuddyState {
        BuddyState::None
    }

    /// The address of our buddy, 0 without one.
    fn buddy_ip(&self) -> u32 {
        0
    }

    /// The UDP port of our buddy, 0 without one.
    fn buddy_udp_port(&self) -> u16 {
        0
    }

    /// The node at `ip` answered our search and offers to be our buddy.
    fn offered(&mut self, _ip: u32, _tcp_port: u16, _udp_port: u16, _user_hash: [u8; 16], _connect_options: u8) {}

    /// The firewalled node at `ip` asks us to be its buddy; false when we cannot now.
    fn asked(&mut self, _ip: u32, _tcp_port: u16, _udp_port: u16, _user_hash: [u8; 16], _buddy_id: UInt128) -> bool {
        false
    }

    /// Relays the callback request of the client at `requester_ip` to the node we serve, when `buddy_id` is its; false
    /// otherwise.
    fn relay(&mut self, _buddy_id: UInt128, _file: UInt128, _requester_ip: u32, _requester_port: u16) -> bool {
        false
    }

    /// The link is no longer needed.
    fn release(&mut self) {}
}

/// No buddy links.
#[derive(Debug, Default)]
pub struct NoBuddies;

impl Buddies for NoBuddies {}

/// The buddy of a firewalled source, which relays callback requests to it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Buddy {
    pub ip: u32,
    pub udp_port: u16,
    pub id: UInt128,
}

/// A source a Kad search found. A firewalled one connects to us when asked: through its `buddy`, or directly at its
/// Kad `udp_port` when it takes `direct_callback`s.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Source {
    pub user_hash: [u8; 16],
    pub ip: u32,
    pub tcp_port: u16,
    pub connect_options: u8,
    pub udp_port: u16,
    pub buddy: Option<Buddy>,
    pub direct_callback: bool,
    pub ipv6: Option<Ipv6Addr>,
}

impl Source {
    /// Whether the source is firewalled and reached only by a callback.
    pub fn is_firewalled(&self) -> bool {
        self.buddy.is_some() || self.direct_callback
    }
}

/// A file a keyword search found.
#[derive(Debug, Clone, PartialEq)]
pub struct FileResult {
    pub hash: [u8; 16],
    pub name: String,
    pub size: u64,
    pub sources: u32,
    pub file_type: Option<String>,
    pub media: Media,
    pub aich_hash: Option<[u8; 20]>,
}

impl FileResult {
    /// The result as searches list it: Kad does not tell how many sources have the whole file.
    pub fn into_search_result(self) -> SearchResult {
        let mut result =
            SearchResult::new(self.hash, &self.name, self.size, self.sources, None, self.file_type.as_deref());
        result.media = self.media;
        result.aich_hash = self.aich_hash;
        result
    }
}

/// What a search found, or that it ended.
#[derive(Debug, Clone, PartialEq)]
pub enum KadEvent {
    /// Files a keyword search found.
    Files {
        search: SearchHandle,
        results: Vec<FileResult>,
    },
    Sources {
        file: [u8; 16],
        sources: Vec<Source>,
    },
    Notes {
        file: [u8; 16],
        notes: Vec<Note>,
    },
    /// A search for notes ended.
    NotesEnded {
        file: [u8; 16],
    },
}

/// A keyword search running: ending it leaves a later search of the same keyword alone.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct SearchHandle {
    pub keyword: UInt128,
    serial: u64,
}

/// Our comment on a file we share, if any.
pub type Comments = Arc<dyn Fn(&[u8; 16]) -> Option<FileComment> + Send + Sync>;

/// Settings that are not the protocol's.
#[derive(Clone)]
pub struct Settings {
    pub tcp_port: u16,
    pub user_hash: [u8; 16],
    /// How others reach us (obfuscation, traversal through the routers), read each time it is told.
    pub options: Arc<dyn Fn() -> u8 + Send + Sync>,
    /// Addresses taken although they are not public (tests, LANs).
    pub trusted: AddressTest,
    /// Addresses the IP filter refuses, never taken as contacts.
    pub blocked: AddressTest,
    /// The UDP port we listen on, 0 when unknown: the UDP firewall test needs it.
    pub udp_port: u16,
    /// Our public IPv6 address, read at each publish of a source.
    pub ipv6: Arc<dyn Fn() -> Option<Ipv6Addr> + Send + Sync>,
    /// Our comment on a file we share, read again for each node a publish of it goes to.
    pub comments: Comments,
}

impl Settings {
    pub fn new(
        tcp_port: u16,
        user_hash: [u8; 16],
        connect_options: u8,
        trusted: AddressTest,
        blocked: AddressTest,
    ) -> Self {
        Self {
            tcp_port,
            user_hash,
            options: Arc::new(move || connect_options),
            trusted,
            blocked,
            udp_port: 0,
            ipv6: Arc::new(|| None),
            comments: Arc::new(|_| None),
        }
    }

    /// The connect options as they are now.
    pub fn connect_options(&self) -> u8 {
        (self.options)()
    }
}

pub const NODES_FILE: &str = "nodes.dat";
/// The files of what other nodes published to us, and of our keywords whose nodes are loaded.
pub const KEYWORD_INDEX_FILE: &str = "key_index.dat";
pub const SOURCE_INDEX_FILE: &str = "src_index.dat";
pub const NOTE_INDEX_FILE: &str = "notes_index.dat";
pub const LOAD_INDEX_FILE: &str = "load_index.dat";
/// A node counts as connected while packets keep arriving.
pub const CONNECTED_FOR: Duration = Duration::from_secs(20 * 60);
/// Fewer contacts heard from than this do not replace a nodes.dat: a short outage must not lose a good one.
pub const MIN_SAVED_CONTACTS: usize = 25;
/// Contacts of a bootstrap-only nodes.dat kept to start from: those closest to our id.
pub const BOOTSTRAP_CONTACTS: usize = 50;
/// The contacts and the index are saved this often too, not only when Kad stops: a crash loses no more.
pub const SAVE_EVERY: Duration = Duration::from_secs(30 * 60);
/// Shipped contacts asked to no avail are asked again after this pause, as when the network came back late.
pub const BOOTSTRAP_REFILL_AFTER: Duration = Duration::from_secs(5 * 60);
/// Clients met over eD2K are asked for contacts this far apart at most while we are not connected.
pub const CLIENT_BOOTSTRAP_EVERY: Duration = Duration::from_secs(10);

const MINUTE: Duration = Duration::from_secs(60);
const HOUR: Duration = Duration::from_secs(3600);
const QUIET_FOR: Duration = Duration::from_secs(15 * 60);
// An index file larger than this is not read: the index holds far less within its own limits.
const MAX_INDEX_BYTES: u64 = 64 * 1024 * 1024;
const SELF_LOOKUP_FIRST: Duration = Duration::from_secs(3 * 60);
const SELF_LOOKUP_EVERY: Duration = Duration::from_secs(4 * 3600);
// Above this load the nodes of a keyword are spared: for up to a week, in proportion to the load.
const HIGH_LOAD: u8 = 20;
const LOADED_FOR: Duration = Duration::from_secs(7 * 24 * 3600);
const FIREWALL_CHECK_EVERY: Duration = Duration::from_secs(3600);
const FIREWALL_CHECKS: u32 = 4;
const FIREWALL_ACKS: usize = 2;
const HELLO_ACK_VERSION: u8 = 8;
// Nodes from this version on obfuscate with keys, which a ping proves; older ones only answer lookups.
const KEYS_VERSION: u8 = 7;
// The contacts a lookup that challenges an old node asks for.
const CHALLENGE_CONTACTS: u8 = 2;
const OBFUSCATION_VERSION: u8 = 6;
const FIREWALL2_VERSION: u8 = 7;
// From this version nodes say whether their UDP port is firewalled.
const UDP_FIREWALLED_VERSION: u8 = 8;
// Files an average node shares, when the servers tell no better.
const FILES_PER_NODE: u64 = 108;
const MISC_UDP_FIREWALLED: u64 = 0x01;
const MISC_TCP_FIREWALLED: u64 = 0x02;
const MISC_ACK_REQUESTED: u64 = 0x04;
const SOURCE_DIRECT: u64 = 1;
const SOURCE_BUDDY: u64 = 3;
const SOURCE_DIRECT_LARGE: u64 = 4;
const SOURCE_BUDDY_LARGE: u64 = 5;
const SOURCE_DIRECT_CALLBACK: u64 = 6;
// More sources than any file has: a result saying so tells nothing.
const MAX_AVAILABILITY: u64 = 65_500;
const DNS_PORT: u16 = 53;
const LARGE_SOURCE: u64 = 4_290_048_000;
// Connect option bits.
const OBFUSCATION_REQUIRED: u8 = 0x04;
const DIRECT_CALLBACK: u8 = 0x08;
const ALL_ONES: UInt128 = UInt128(u128::MAX);
// A firewalled node looks for a buddy a while after the start, when its state is known, then now and then.
const FIND_BUDDY_FIRST: Duration = Duration::from_secs(5 * 60);
const FIND_BUDDY_EVERY: Duration = Duration::from_secs(20 * 60);
const EXTERNAL_PORT_ASK_EVERY: Duration = Duration::from_secs(15);
// A bootstrap contact is asked every so often while we are not connected, more often while we know no one.
const BOOTSTRAP_EVERY: Duration = Duration::from_secs(15);
const BOOTSTRAP_EVERY_ALONE: Duration = Duration::from_secs(2);
// Lookups for UDP test helpers in one test at most, and how far apart.
const UDP_HELPER_LOOKUPS: u32 = 3;
const UDP_HELPER_LOOKUP_GAP: Duration = Duration::from_secs(60);
// A node that proves another id too soon after the last is kept out this long.
const ID_CHANGE_BAN: Duration = Duration::from_secs(4 * 3600);
// A node is helped with its UDP test once in this time at most, and so many are remembered.
const UDP_HELP_GAP: Duration = Duration::from_secs(5 * 60);
const MAX_UDP_HELPED: usize = 256;
const SMALL_TIMER: Duration = MINUTE;
const BIG_TIMER: Duration = Duration::from_secs(10);
const CONSOLIDATE_EVERY: Duration = Duration::from_secs(45 * 60);
const CLEAN_EVERY: Duration = Duration::from_secs(12 * 60);
const FIREWALL_CHECK_LIFE: Duration = Duration::from_secs(3 * 60);

/// What a lookup carries for its requests, and gathers of its results.
#[derive(Debug)]
enum LookupData {
    Nothing,
    Keyword { tree: Vec<u8>, keywords: Vec<String>, expression: Option<SearchExpr> },
    Sources { file: [u8; 16], size: u64 },
    Notes { file: [u8; 16], size: u64, publishers: HashSet<UInt128> },
    StoreKeyword { files: Vec<Entry> },
    StoreSource { size: u64 },
    StoreNotes { file: [u8; 16], name: String, size: u64 },
}

#[derive(Debug)]
struct Running {
    lookup: Lookup,
    data: LookupData,
    serial: u64,
}

/// Where a packet came from and the keys it carried.
#[derive(Debug, Clone, Copy)]
struct Peer {
    ip: u32,
    port: u16,
    valid_key: bool,
    sender_key: u32,
}

impl Peer {
    fn key(&self, our_ip: u32) -> UdpKey {
        if self.sender_key != 0 && our_ip != 0 {
            UdpKey { key: self.sender_key, ip: our_ip }
        } else {
            UdpKey::default()
        }
    }
}

/// How others reach us, as a source publish tells.
#[derive(Debug, Clone)]
struct Reach {
    firewalled_for_clients: bool,
    direct_callback: bool,
    buddy: Option<(u32, u16)>,
    use_external_port: bool,
    our_id: UInt128,
}

pub struct KadNode {
    identity: KadIdentity,
    table: RoutingTable,
    tracking: PacketTracking,
    proven_ids: ProvenIds,
    response_times: ResponseTimes,
    index: KadIndex,
    transport: Box<dyn Transport>,
    host: Box<dyn Host>,
    settings: Settings,
    config_dir: PathBuf,
    random: StdRng,
    clock: Box<dyn Fn() -> Instant + Send>,
    // The instant the index's ticker counts from.
    origin: Instant,
    lookups: IndexMap<UInt128, Running>,
    next_serial: u64,
    events: Vec<KadEvent>,
    // Nodes asked to connect to our TCP port, and when.
    firewall_checks: HashMap<u32, Instant>,
    firewall_acks: HashSet<u32>,
    udp_test: UdpFirewallTest,
    // Nodes we sent datagrams for their UDP test, and when.
    udp_helped: IndexMap<u32, Instant>,
    // The contacts of a bootstrap-only nodes.dat, closest to our id first: asked, never added.
    bootstrap_list: Vec<Contact>,
    // The addresses of the list's contacts asked since it was last filled: their answers give contacts, not them.
    asked_from_list: HashSet<u32>,
    last_bootstrap: Option<Instant>,
    shipped: Option<Box<dyn Fn() -> nodes_dat::Contents + Send>>,
    shipped_at: Instant,
    next_client_bootstrap: Instant,
    buddies: Box<dyn Buddies>,
    buddy_state: BuddyState,
    find_buddy: bool,
    next_find_buddy: Instant,
    next_external_port_ask: Instant,
    next_udp_helper_lookup: Instant,
    udp_helper_lookups: u32,
    public_ip: u32,
    pending_ip: u32,
    last_packet: Option<Instant>,
    last_quiet_refresh: Option<Instant>,
    next_small_timer: Instant,
    next_big_timer: Instant,
    next_consolidate: Instant,
    next_save: Instant,
    next_self_lookup: Instant,
    next_firewall_check: Instant,
    next_clean: Instant,
    firewall_reports: u32,
    // How many firewall checks started, so that an outcome can be told from one of an earlier check.
    firewall_rounds: u32,
    // What the nodes that greet us say of their UDP port: the share firewalled weighs the estimate of the network.
    udp_firewalled_nodes: u32,
    udp_open_nodes: u32,
    self_lookup_done: bool,
    was_open: bool,
}

impl KadNode {
    pub fn new(
        identity: KadIdentity,
        transport: Box<dyn Transport>,
        host: Box<dyn Host>,
        settings: Settings,
        config_dir: &Path,
        clock: Box<dyn Fn() -> Instant + Send>,
    ) -> Self {
        let now = clock();
        let trusted = Arc::clone(&settings.trusted);
        Self {
            table: RoutingTable::new(identity.id(), move |ip| trusted(ip)),
            identity,
            tracking: PacketTracking::new(),
            proven_ids: ProvenIds::new(),
            response_times: ResponseTimes::new(),
            index: KadIndex::new(),
            transport,
            host,
            settings,
            config_dir: config_dir.to_path_buf(),
            random: StdRng::from_rng(&mut rand::rng()),
            clock,
            origin: now,
            lookups: IndexMap::new(),
            next_serial: 0,
            events: Vec::new(),
            firewall_checks: HashMap::new(),
            firewall_acks: HashSet::new(),
            udp_test: UdpFirewallTest::new(),
            udp_helped: IndexMap::new(),
            bootstrap_list: Vec::new(),
            asked_from_list: HashSet::new(),
            last_bootstrap: None,
            shipped: None,
            shipped_at: now,
            next_client_bootstrap: now,
            buddies: Box::new(NoBuddies),
            buddy_state: BuddyState::None,
            find_buddy: false,
            next_find_buddy: later(now, FIND_BUDDY_FIRST),
            next_external_port_ask: now,
            next_udp_helper_lookup: now,
            udp_helper_lookups: 0,
            public_ip: 0,
            pending_ip: 0,
            last_packet: None,
            last_quiet_refresh: None,
            next_small_timer: later(now, SMALL_TIMER),
            next_big_timer: later(now, BIG_TIMER),
            next_consolidate: later(now, CONSOLIDATE_EVERY),
            next_save: later(now, SAVE_EVERY),
            next_self_lookup: later(now, SELF_LOOKUP_FIRST),
            next_firewall_check: now,
            next_clean: later(now, CLEAN_EVERY),
            firewall_reports: 0,
            firewall_rounds: 0,
            udp_firewalled_nodes: 0,
            udp_open_nodes: 0,
            self_lookup_done: false,
            was_open: false,
        }
    }

    /// Where the buddy links are kept; none until set.
    pub fn use_buddies(&mut self, links: Box<dyn Buddies>) {
        self.buddies = links;
    }

    pub fn id(&self) -> UInt128 {
        self.identity.id()
    }

    /// Contacts in the routing table.
    pub fn contacts(&self) -> usize {
        self.table.size()
    }

    /// Contacts that proved who they are.
    pub fn verified_contacts(&self) -> usize {
        self.table.all().filter(|contact| contact.is_verified()).count()
    }

    /// The routing table, to look into.
    pub fn table(&self) -> &RoutingTable {
        &self.table
    }

    /// What searches found since asked last.
    pub fn take_events(&mut self) -> Vec<KadEvent> {
        std::mem::take(&mut self.events)
    }

    pub fn is_connected(&self) -> bool {
        let now = (self.clock)();
        self.last_packet.is_some_and(|last| now.saturating_duration_since(last) < CONNECTED_FOR)
            && self.table.size() > 0
    }

    /// Checks again whether other nodes can reach our TCP port, the next contacts heard from being asked at once, and
    /// our UDP port.
    pub fn recheck_firewall(&mut self) {
        self.next_firewall_check = (self.clock)();
    }

    /// Whether nodes we never wrote to cannot send to our UDP port, as far as we know; while a test runs, the last
    /// outcome stands. Unknown counts as open.
    pub fn is_firewalled_udp(&self) -> bool {
        self.udp_test.is_firewalled(true)
    }

    /// Whether a UDP firewall test came to an outcome.
    pub fn is_udp_verified(&self) -> bool {
        self.udp_test.is_verified()
    }

    /// Whether we take direct UDP callbacks: TCP-firewalled, with a UDP port proven open.
    pub fn supports_direct_callback(&self) -> bool {
        self.is_firewalled() && !self.is_firewalled_udp() && self.udp_test.is_verified()
    }

    /// The Kad port other clients should use: the one a NAT shows them once a test proved it, else ours.
    pub fn announced_udp_port(&self) -> u16 {
        let external = self.udp_test.external_port();
        if external != 0 && self.udp_test.use_external_port() && self.udp_test.is_verified() {
            external
        } else {
            self.settings.udp_port
        }
    }

    /// What our buddy link is doing.
    pub fn buddy_state(&self) -> BuddyState {
        self.buddies.state()
    }

    /// Whether `buddy_id` is the one our buddy was found by: the complement of our id.
    pub fn is_our_buddy_id(&self, buddy_id: UInt128) -> bool {
        buddy_id ^ ALL_ONES == self.identity.id()
    }

    // Firewalled as other clients see us: neither Kad nor the server gives us a HighID.
    fn is_firewalled_for_clients(&self) -> bool {
        self.is_firewalled() && !self.host.has_high_id()
    }

    /// Whether other clients can reach us, directly, through our buddy or by a direct callback.
    pub fn is_reachable(&self) -> bool {
        !self.is_firewalled_for_clients()
            || self.buddies.state() == BuddyState::Connected
            || (!self.is_firewalled_udp() && self.udp_test.is_verified())
    }

    /// The address of the buddy that relays callbacks to us, 0 without one.
    pub fn buddy_ip(&self) -> u32 {
        if self.buddies.state() == BuddyState::Connected { self.buddies.buddy_ip() } else { 0 }
    }

    /// Whether other nodes cannot connect to our TCP port, as far as we know.
    pub fn is_firewalled(&self) -> bool {
        let open = self.firewall_acks.len() >= FIREWALL_ACKS;
        // While a recheck runs, the last outcome stands.
        if !open && self.firewall_reports < FIREWALL_CHECKS && self.was_open {
            return false;
        }
        !open
    }

    /// How many nodes the Kad network holds, as our routing table tells, made up for the nodes no one can take as a
    /// contact.
    pub fn estimated_nodes(&self) -> u64 {
        self.table.estimated_nodes(firewalled_modifier(
            self.is_firewalled_udp(),
            self.udp_firewalled_share(),
            self.table.share_from(UDP_FIREWALLED_VERSION),
        ))
    }

    /// The share of the nodes greeting us that are UDP-firewalled, 0 until more than ten open ones did.
    pub fn udp_firewalled_share(&self) -> f64 {
        if self.udp_firewalled_nodes == 0 || self.udp_open_nodes <= 10 {
            return 0.0;
        }
        f64::from(self.udp_firewalled_nodes) / f64::from(self.udp_firewalled_nodes.saturating_add(self.udp_open_nodes))
    }

    /// How many files the Kad network holds: the nodes estimated, each sharing what an average user of the servers
    /// does (`server_average`, 0 when they do not tell), or as many as our index holds keywords, at least 108.
    pub fn estimated_files(&self, server_average: u64) -> u64 {
        let per_node = server_average.max(self.index.keyword_count() as u64).max(FILES_PER_NODE);
        per_node.saturating_mul(self.estimated_nodes())
    }

    /// How many checks of our ports started; a recheck asked for starts at the node's next tick.
    pub fn firewall_rounds(&self) -> u32 {
        self.firewall_rounds
    }

    /// How the last check of our TCP port ended, open or not; none while it runs.
    pub fn tcp_check_open(&self) -> Option<bool> {
        if self.firewall_acks.len() >= FIREWALL_ACKS {
            return Some(true);
        }
        (self.firewall_reports >= FIREWALL_CHECKS).then_some(false)
    }

    /// How the last UDP firewall test ended, open or not; none while it runs or before one ended.
    pub fn udp_test_open(&self) -> Option<bool> {
        if self.udp_test.is_running() || !self.udp_test.is_verified() {
            return None;
        }
        Some(!self.udp_test.is_firewalled(false))
    }

    /// Our address as other nodes see it, 0 until one told us.
    pub fn public_ip(&self) -> u32 {
        self.public_ip
    }

    /// Whether the first lookup of our own id is done, which publishing waits for.
    pub fn is_ready(&self) -> bool {
        self.self_lookup_done
    }

    /// Loads the contacts of `nodes.dat`, if any; false when there is no such file.
    pub fn load_nodes(&mut self) -> bool {
        let file = self.config_dir.join(NODES_FILE);
        if !file.exists() {
            return false;
        }
        match fs::read(&file)
            .map_err(|e| e.to_string())
            .and_then(|data| nodes_dat::read(&data).map_err(|e| e.to_string()))
        {
            Ok(contents) => {
                let added = self.add_contacts(&contents);
                let what = if contents.bootstrap { "contacts to bootstrap from" } else { "contacts" };
                tracing::info!("{added} Kad {what} from {NODES_FILE}");
            }
            Err(e) => tracing::warn!("Cannot read {}: {e}", file.display()),
        }
        true
    }

    /// Adds the contacts of a `nodes.dat`, or keeps those of a bootstrap-only one to start from; returns how many were
    /// new.
    pub fn add_contacts(&mut self, contents: &nodes_dat::Contents) -> usize {
        if contents.bootstrap {
            return self.keep_for_bootstrap(&contents.contacts);
        }
        let any_verified = contents.contacts.iter().any(|contact| contact.verified);
        let now = (self.clock)();
        let mut added = 0usize;
        let good: Vec<&nodes_dat::Contact> =
            contents.contacts.iter().filter(|record| self.is_good_address(record.ip)).collect();
        for record in good {
            let mut contact =
                KadContact::new(record.id, record.ip, record.udp_port, record.tcp_port, record.version, now);
            contact.set_verified(record.verified || !any_verified);
            if self.table.add(contact, false, record.udp_key, self.public_ip, now) == Outcome::Added {
                added = added.saturating_add(1);
            }
        }
        added
    }

    // A list shipped with a client would flood the nodes on it if every user took them as contacts: they are only
    // asked for contacts, one at a time, and the closest to us give a start near our own zone.
    fn keep_for_bootstrap(&mut self, contacts: &[nodes_dat::Contact]) -> usize {
        let own = self.identity.id();
        let mut known: HashSet<UInt128> = self.bootstrap_list.iter().map(|contact| contact.id).collect();
        let before = self.bootstrap_list.len();
        for record in contacts {
            if self.is_good_address(record.ip)
                && record.udp_port != 0
                && record.version > 1
                && !(record.udp_port == DNS_PORT && record.version <= 5)
                && known.insert(record.id)
            {
                self.bootstrap_list.push(Contact {
                    id: record.id,
                    ip: record.ip,
                    udp_port: record.udp_port,
                    tcp_port: record.tcp_port,
                    version: record.version,
                });
            }
        }
        self.bootstrap_list.sort_by(|a, b| routing_table::compare_distances(a.id, b.id, own));
        self.bootstrap_list.truncate(BOOTSTRAP_CONTACTS);
        self.bootstrap_list.len().saturating_sub(before)
    }

    /// Bootstrap-only contacts asked at once next to the saved ones, which may all be gone, and asked again when they
    /// ran out with the node still not connected.
    pub fn bootstrap_from(&mut self, contacts: Box<dyn Fn() -> nodes_dat::Contents + Send>) {
        let first = contacts();
        self.shipped = Some(contacts);
        self.shipped_at = (self.clock)();
        let added = self.add_contacts(&first);
        tracing::info!("{added} Kad contacts shipped with njMule to bootstrap from");
    }

    fn refill_bootstrap_list(&mut self, now: Instant) {
        if self.shipped.is_none()
            || !self.bootstrap_list.is_empty()
            || self.is_connected()
            || now.saturating_duration_since(self.shipped_at) < BOOTSTRAP_REFILL_AFTER
        {
            return;
        }
        self.shipped_at = now;
        self.asked_from_list.clear();
        let Some(contents) = self.shipped.as_ref().map(|shipped| shipped()) else {
            return;
        };
        let added = self.add_contacts(&contents);
        tracing::info!("Kad is still not connected: asking the {added} shipped contacts again");
    }

    // The next bootstrap contact is asked while nothing tells us we are connected.
    fn bootstrap_from_list(&mut self, now: Instant) {
        let gap = if self.table.size() == 0 { BOOTSTRAP_EVERY_ALONE } else { BOOTSTRAP_EVERY };
        let recent = self.last_bootstrap.is_some_and(|last| now.saturating_duration_since(last) < gap);
        if self.bootstrap_list.is_empty() || self.is_connected() || recent {
            return;
        }
        self.last_bootstrap = Some(now);
        let contact = self.bootstrap_list.remove(0);
        self.asked_from_list.insert(contact.ip);
        let key = (contact.version >= OBFUSCATION_VERSION).then_some(contact.id);
        self.send(contact.ip, contact.udp_port, &kad_messages::bootstrap_request(), key, 0);
        self.tracking.sent(contact.ip, kad::BOOTSTRAP_REQ, now);
    }

    /// Looks for contacts at once rather than at the next rounds: when asked to connect, or after new ones came.
    pub fn look_for_contacts(&mut self) {
        let now = (self.clock)();
        self.next_small_timer = now;
        self.next_big_timer = now;
        self.next_self_lookup = now;
        for zone in self.table.leaves_mut() {
            zone.set_next_big_timer(now);
        }
    }

    /// Writes the best contacts we heard from to `nodes.dat`.
    pub fn save_nodes(&mut self) {
        let records: Vec<nodes_dat::Contact> = self
            .table
            .bootstrap_contacts(nodes_dat::MAX_WRITTEN, is_known_live, &mut self.random)
            .into_iter()
            .map(KadContact::record)
            .collect();
        let file = self.config_dir.join(NODES_FILE);
        if records.is_empty() || (records.len() < MIN_SAVED_CONTACTS && file.exists()) {
            tracing::debug!("Keeping {NODES_FILE}: only {} contacts heard from", records.len());
            return;
        }
        match nodes_dat::write(&records) {
            Ok(data) => {
                if let Err(e) = atomic_files::write(&file, &data) {
                    tracing::warn!("Cannot save the Kad contacts: {e}");
                }
            }
            Err(e) => tracing::warn!("Cannot save the Kad contacts: {e}"),
        }
    }

    /// Writes what other nodes published to us.
    pub fn save_index(&mut self) {
        self.save_index_at(wall_seconds());
    }

    /// As [`save_index`](Self::save_index), `wall` being the epoch second of now.
    pub fn save_index_at(&mut self, wall: u64) {
        let now = self.ticks((self.clock)());
        let keywords = self.index.keyword_file(self.identity.id(), wall, now);
        self.write_index(KEYWORD_INDEX_FILE, kad_index_files::write_keywords(&keywords).map_err(|e| e.to_string()));
        let sources = self.index.source_file(wall, now);
        self.write_index(SOURCE_INDEX_FILE, kad_index_files::write_sources(&sources).map_err(|e| e.to_string()));
        let notes = self.index.note_file(wall, now);
        self.write_index(NOTE_INDEX_FILE, kad_index_files::write_sources(&notes).map_err(|e| e.to_string()));
        let loads = self.index.load_file(wall, now);
        self.write_index(LOAD_INDEX_FILE, kad_index_files::write_load(&loads).map_err(|e| e.to_string()));
    }

    fn write_index(&self, name: &str, data: Result<Vec<u8>, String>) {
        let written =
            data.and_then(|data| atomic_files::write(&self.config_dir.join(name), &data).map_err(|e| e.to_string()));
        if let Err(e) = written {
            tracing::warn!("Cannot save {name}: {e}");
        }
    }

    /// Loads what other nodes published to us before, as far as it is still alive.
    pub fn load_index(&mut self) {
        self.load_index_at(wall_seconds());
    }

    /// As [`load_index`](Self::load_index), `wall` being the epoch second of now. Read with the limits of live
    /// publishes: our tolerance, the IP filter and the index's own caps.
    pub fn load_index_at(&mut self, wall: u64) {
        let now = self.ticks((self.clock)());
        let blocked = Arc::clone(&self.settings.blocked);
        let refused = move |ip: u32| blocked(ip);
        let own = self.identity.id();
        let near = move |key: &UInt128| in_tolerance(own, *key);
        if let Some(file) = self.read_index(KEYWORD_INDEX_FILE, kad_index_files::read_keywords) {
            self.index.restore_keywords(&file, own, &refused, wall, now);
        }
        if let Some(file) = self.read_index(SOURCE_INDEX_FILE, kad_index_files::read_sources) {
            self.index.restore_sources(&file, &near, &refused, wall, now);
        }
        if let Some(file) = self.read_index(NOTE_INDEX_FILE, kad_index_files::read_sources) {
            self.index.restore_notes(&file, &near, &refused, wall, now);
        }
        if let Some(file) = self.read_index(LOAD_INDEX_FILE, kad_index_files::read_load) {
            self.index.restore_loads(&file, LOADED_FOR, wall, now);
        }
        tracing::info!(
            "Kad index: {} keyword entries, {} sources, {} notes",
            self.index.keyword_entries(),
            self.index.source_entries(),
            self.index.note_entries()
        );
    }

    // A damaged or oversized file is left out: the index fills again as nodes publish.
    fn read_index<T>(&self, name: &str, reader: impl Fn(&[u8]) -> Result<T, DecodeError>) -> Option<T> {
        let file = self.config_dir.join(name);
        if !file.exists() && !atomic_files::bak_path(&file).exists() {
            return None;
        }
        let read = atomic_files::read(&file, |data| {
            if u64::try_from(data.len()).unwrap_or(u64::MAX) > MAX_INDEX_BYTES {
                return Err(io::Error::new(io::ErrorKind::InvalidData, format!("larger than {MAX_INDEX_BYTES} bytes")));
            }
            reader(data).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))
        });
        match read {
            Ok(index) => Some(index),
            Err(e) => {
                tracing::warn!("Cannot read {name}: {e}");
                None
            }
        }
    }

    /// Saves the contacts and the index, as Kad stops.
    pub fn close(&mut self) {
        self.save_nodes();
        self.save_index();
    }

    /// Asks the node at `ip:port` for contacts to start from.
    pub fn bootstrap(&mut self, ip: u32, port: u16) {
        self.send(ip, port, &kad_messages::bootstrap_request(), None, 0);
        let now = (self.clock)();
        self.tracking.sent(ip, kad::BOOTSTRAP_REQ, now);
    }

    /// A client met over eD2K whose hello names a Kad 2 node at `ip:kad_port`: while we are not connected, it is asked
    /// for contacts, one such client every few seconds at most.
    pub fn bootstrap_via(&mut self, ip: u32, kad_port: u16, kad_version: u8) {
        let now = (self.clock)();
        if kad_port == 0
            || kad_version <= 1
            || self.is_connected()
            || !self.is_good_address(ip)
            || now < self.next_client_bootstrap
        {
            return;
        }
        self.next_client_bootstrap = later(now, CLIENT_BOOTSTRAP_EVERY);
        self.bootstrap(ip, kad_port);
    }

    /// Looks for files under the first keyword of `words`, filtered by `expression` there and here; none when the
    /// search cannot start now. Its results come as [`KadEvent::Files`].
    pub fn search_keyword(&mut self, words: &str, expression: Option<SearchExpr>) -> Option<SearchHandle> {
        let keywords = keywords::of(words);
        let target = keywords::id(keywords.first()?);
        if self.lookups.contains_key(&target) {
            return None;
        }
        let mut tree = Vec::new();
        if let Some(expression) = &expression {
            let mut out = PacketWriter::new();
            search_tree_codec::write(&mut out, expression, true).ok()?;
            tree = out.into_byte_array();
        }
        let serial = self.start_lookup(target, Kind::Keyword, LookupData::Keyword { tree, keywords, expression })?;
        Some(SearchHandle { keyword: target, serial })
    }

    /// Ends a keyword search; a later search of the same keyword is left alone.
    pub fn end_search(&mut self, handle: SearchHandle) {
        if self.lookups.get(&handle.keyword).is_some_and(|running| running.serial == handle.serial) {
            self.lookup_ended(handle.keyword);
        }
    }

    /// Looks for sources of the file `hash` of `size` bytes; they come as [`KadEvent::Sources`].
    pub fn find_sources(&mut self, hash: [u8; 16], size: u64) -> bool {
        let target = UInt128::from_big_endian(hash);
        !self.lookups.contains_key(&target)
            && self.start_lookup(target, Kind::Sources, LookupData::Sources { file: hash, size }).is_some()
    }

    /// Looks for the notes on the file `hash` of `size` bytes: each publisher's note comes once, as
    /// [`KadEvent::Notes`], then [`KadEvent::NotesEnded`]. False when the search cannot start now.
    pub fn find_notes(&mut self, hash: [u8; 16], size: u64) -> bool {
        let target = UInt128::from_big_endian(hash);
        let data = LookupData::Notes { file: hash, size, publishers: HashSet::new() };
        !self.lookups.contains_key(&target) && self.start_lookup(target, Kind::Notes, data).is_some()
    }

    /// Publishes `files` under the keyword `word`; not while its nodes are loaded.
    pub fn publish_keyword(&mut self, word: &str, files: Vec<Entry>) -> bool {
        let target = keywords::id(word);
        let now = self.ticks((self.clock)());
        if files.is_empty() || self.lookups.contains_key(&target) || !self.index.may_store(&target, now) {
            return false;
        }
        self.start_lookup(target, Kind::StoreKeyword, LookupData::StoreKeyword { files }).is_some()
    }

    /// Publishes us as a source of the file `hash` of `size` bytes.
    pub fn publish_source(&mut self, hash: [u8; 16], size: u64) -> bool {
        let target = UInt128::from_big_endian(hash);
        !self.lookups.contains_key(&target)
            && self.start_lookup(target, Kind::StoreSource, LookupData::StoreSource { size }).is_some()
    }

    /// Publishes our comment on the file `hash`, under our id, to the nodes closest to it; the comment is read again
    /// for each node, and an empty one ends the publish.
    pub fn publish_note(&mut self, hash: [u8; 16], name: &str, size: u64) -> bool {
        let target = UInt128::from_big_endian(hash);
        let data = LookupData::StoreNotes { file: hash, name: name.to_owned(), size };
        !self.lookups.contains_key(&target) && self.start_lookup(target, Kind::StoreNotes, data).is_some()
    }

    /// The target of the lookup for UDP test helpers running now.
    pub fn udp_helpers_target(&self) -> Option<UInt128> {
        self.lookups
            .values()
            .find(|running| running.lookup.kind() == Kind::UdpHelpers)
            .map(|running| running.lookup.target())
    }

    /// Files indexed under keywords and sources indexed.
    pub fn indexed(&self) -> (usize, usize) {
        (self.index.keyword_entries(), self.index.source_entries())
    }

    /// Notes indexed.
    pub fn indexed_notes(&self) -> usize {
        self.index.note_entries()
    }

    fn running(&self, kind: Kind) -> usize {
        self.lookups.values().filter(|running| running.lookup.kind() == kind).count()
    }

    /// Lookups running now.
    pub fn running_lookups(&self) -> usize {
        self.lookups.len()
    }

    /// The contacts of the routing table, closest first.
    pub fn contact_infos(&self) -> Vec<KadContactInfo> {
        let us = self.identity.id();
        let mut all: Vec<&KadContact> = self.table.all().collect();
        all.sort_by(|a, b| routing_table::compare_distances(a.id(), b.id(), us));
        all.into_iter()
            .map(|contact| KadContactInfo {
                id: contact.id().to_hex(),
                address: format!("{}:{}", Ipv4Addr::from(contact.ip()), contact.udp_port()),
                contact_type: contact.contact_type(),
                distance: (contact.id() ^ us).to_hex(),
                version: contact.version(),
                verified: contact.is_verified(),
                country: None,
            })
            .collect()
    }

    /// The lookups running now.
    pub fn lookup_infos(&self) -> Vec<KadSearchInfo> {
        self.lookups
            .values()
            .filter(|running| !running.lookup.is_stopped())
            .map(|running| KadSearchInfo {
                target: running.lookup.target().to_hex(),
                kind: info_kind(running.lookup.kind()),
                answers: running.lookup.answers(),
            })
            .collect()
    }

    /// The IP or LAN filter changed: contacts it now refuses are dropped; returns how many.
    pub fn filters_changed(&mut self) -> usize {
        let refused: Vec<UInt128> =
            self.table.all().filter(|contact| !self.is_good_address(contact.ip())).map(KadContact::id).collect();
        for id in &refused {
            self.table.remove(id);
        }
        refused.len()
    }

    /// Whether the node at `ip` was asked to connect to our TCP port and has yet to say it did.
    pub fn awaits_connection_from(&self, ip: u32) -> bool {
        self.firewall_checks.contains_key(&ip)
    }

    /// A node we asked to check our TCP port connected and said so.
    pub fn reachable_confirmed(&mut self, ip: u32) {
        if self.firewall_checks.remove(&ip).is_some() {
            self.firewall_acks.insert(ip);
        }
    }

    /// The TCP connection to a UDP test helper ended; `asked` whether the request went out. A helper asked whose
    /// datagram did not come by then failed, one never asked does not count.
    pub fn udp_check_ended(&mut self, ip: u32, asked: bool) {
        let now = (self.clock)();
        if self.udp_test.outcome(ip, false, !asked, 0, self.settings.udp_port, now) {
            self.ask_udp_helpers();
        }
    }

    /// A client asked us over TCP to send a datagram to its UDP ports for its UDP firewall test. It gets one per port,
    /// marked when it is known to us already (`known_client`, a contact, a node we wrote to): that would bias its
    /// outcome.
    pub fn udp_check_requested(
        &mut self,
        ip: u32,
        internal_port: u16,
        external_port: u16,
        sender_key: u32,
        known_client: bool,
    ) {
        let now = (self.clock)();
        if internal_port == 0 || !self.is_good_address(ip) {
            return;
        }
        self.udp_helped.retain(|_, at| now.saturating_duration_since(*at) < UDP_HELP_GAP);
        if self.udp_helped.contains_key(&ip) || self.udp_helped.len() >= MAX_UDP_HELPED {
            return;
        }
        self.udp_helped.insert(ip, now);
        let known = known_client || self.table.by_address(ip).is_some() || self.tracking.wrote_to(ip, now);
        let error = u8::from(known);
        let first = kad_messages::FirewallUdp { error, port: internal_port }.encode();
        self.transport.send(ip, internal_port, &first, None, sender_key, self.identity.verify_key(ip));
        if external_port != 0 && external_port != internal_port {
            let second = kad_messages::FirewallUdp { error, port: external_port }.encode();
            self.transport.send(ip, external_port, &second, None, sender_key, self.identity.verify_key(ip));
        }
    }

    /// Asks the buddy of a firewalled source, plain as the buddy may not know us, to have the source connect to our TCP
    /// port for `file`; false when the buddy's address is not usable.
    pub fn request_callback(&mut self, buddy: &Buddy, file: [u8; 16]) -> bool {
        if !self.is_good_address(buddy.ip) || buddy.udp_port == 0 {
            return false;
        }
        let request = kad_messages::CallbackRequest {
            buddy_id: buddy.id,
            file: UInt128::from_big_endian(file),
            tcp_port: self.settings.tcp_port,
        }
        .encode();
        self.transport.send(buddy.ip, buddy.udp_port, &request, None, 0, 0);
        true
    }

    /// Runs every second.
    pub fn tick(&mut self) {
        let now = (self.clock)();
        let targets: Vec<UInt128> = self.lookups.keys().copied().collect();
        for target in targets {
            let reach = self.reach();
            let Self { lookups, transport, tracking, table, identity, settings, response_times, public_ip, .. } = self;
            let Some(Running { lookup, data, .. }) = lookups.get_mut(&target) else {
                continue;
            };
            let mut sender = LookupSender {
                transport: &mut **transport,
                tracking,
                table,
                identity,
                settings,
                public_ip: *public_ip,
                reach: &reach,
                data,
                now,
            };
            lookup.tick(now, response_times, &mut sender);
            if lookup.is_stopped() {
                self.lookup_ended(target);
            }
        }
        if now >= self.next_small_timer {
            self.next_small_timer = later(now, SMALL_TIMER);
            self.check_contacts(now);
        }
        if now >= self.next_big_timer {
            self.next_big_timer = later(now, BIG_TIMER);
            // After a quarter of an hour without a packet every zone looks for contacts again, and again every quarter
            // of an hour while it stays quiet.
            let quiet = self.last_packet.is_some_and(|last| now.saturating_duration_since(last) > QUIET_FOR);
            let refreshed =
                self.last_quiet_refresh.is_some_and(|last| now.saturating_duration_since(last) <= QUIET_FOR);
            if quiet && !refreshed {
                for zone in self.table.leaves_mut() {
                    zone.set_next_big_timer(now);
                }
                self.last_quiet_refresh = Some(now);
            }
            self.refresh_one_zone(now);
        }
        if now >= self.next_consolidate {
            self.next_consolidate = later(now, CONSOLIDATE_EVERY);
            self.table.consolidate();
        }
        if now >= self.next_save {
            self.next_save = later(now, SAVE_EVERY);
            self.save_nodes();
            self.save_index();
        }
        if now >= self.next_self_lookup && self.table.size() > 0 {
            self.next_self_lookup = later(now, SELF_LOOKUP_EVERY);
            self.start_lookup(self.identity.id(), Kind::NodeComplete, LookupData::Nothing);
        }
        if now >= self.next_firewall_check {
            self.next_firewall_check = later(now, FIREWALL_CHECK_EVERY);
            self.firewall_rounds = self.firewall_rounds.saturating_add(1);
            // Only a check that ended gives the outcome that stands while the next one runs.
            let open = self.firewall_acks.len() >= FIREWALL_ACKS;
            if open || self.firewall_reports >= FIREWALL_CHECKS {
                self.was_open = open;
            }
            self.firewall_reports = 0;
            self.firewall_acks.clear();
            let connected = self.is_connected();
            self.udp_test.recheck(connected, now);
            self.udp_helper_lookups = 0;
            self.next_udp_helper_lookup = now;
            self.stop_lookups(Kind::UdpHelpers);
            // A buddy is looked for once the outcome is likely known.
            self.find_buddy = false;
            let first = later(now, FIND_BUDDY_FIRST);
            if self.next_find_buddy < first {
                self.next_find_buddy = first;
            }
        }
        self.refill_bootstrap_list(now);
        self.bootstrap_from_list(now);
        self.tick_udp_test(now);
        self.tick_buddies(now);
        if now >= self.next_clean {
            self.next_clean = later(now, CLEAN_EVERY);
            self.tracking.clean(now);
            self.proven_ids.clean(now);
            let ticks = self.ticks(now);
            self.index.clean(ticks);
            self.firewall_checks.retain(|_, at| now.saturating_duration_since(*at) <= FIREWALL_CHECK_LIFE);
            self.udp_helped.retain(|_, at| now.saturating_duration_since(*at) < UDP_HELP_GAP);
        }
    }

    // While the UDP test runs: nodes are asked the port they see us on, and lookups find helpers.
    fn tick_udp_test(&mut self, now: Instant) {
        let connected = self.is_connected();
        let firewalled = self.is_firewalled();
        self.udp_test.tick(connected, firewalled, now);
        if !self.udp_test.is_running() {
            self.stop_lookups(Kind::UdpHelpers);
            return;
        }
        if !connected {
            return;
        }
        if self.udp_test.finds_external_port() && now >= self.next_external_port_ask {
            self.next_external_port_ask = later(now, EXTERNAL_PORT_ASK_EVERY);
            self.ask_external_port(now);
        }
        // Helpers are looked for once they can be asked: when the port a NAT shows is known.
        if !self.udp_test.finds_external_port()
            && self.udp_test.wants_candidates()
            && self.udp_helper_lookups < UDP_HELPER_LOOKUPS
            && now >= self.next_udp_helper_lookup
            && self.running(Kind::UdpHelpers) == 0
        {
            let target = UInt128::random(&mut self.random);
            if self.start_lookup(target, Kind::UdpHelpers, LookupData::Nothing).is_some() {
                self.udp_helper_lookups = self.udp_helper_lookups.saturating_add(1);
                self.next_udp_helper_lookup = later(now, UDP_HELPER_LOOKUP_GAP);
            }
        }
    }

    // A random contact recent enough says which UDP port it sees us on.
    fn ask_external_port(&mut self, now: Instant) {
        let usable: Vec<UInt128> = self
            .table
            .all()
            .filter(|contact| contact.contact_type() < kad_contact::DEAD && contact.version() >= OBFUSCATION_VERSION)
            .map(KadContact::id)
            .collect();
        if usable.is_empty() {
            return;
        }
        let Some(id) = usable.get(self.random.random_range(0..usable.len())).copied() else {
            return;
        };
        if let Some(contact) = self.table.get(&id).cloned() {
            self.request(&contact, &kad_messages::ping(), kad::PING, now);
        }
    }

    // Helpers are asked one by one until enough are; each is told our ports and the key to obfuscate with.
    fn ask_udp_helpers(&mut self) {
        let now = (self.clock)();
        loop {
            let udp_port = self.settings.udp_port;
            let (table, tracking, host, settings) = (&self.table, &mut self.tracking, &self.host, &self.settings);
            let usable = |contact: &Contact| {
                udp_port != 0
                    && contact.tcp_port != 0
                    && good_address(settings, contact.ip)
                    && table.by_address(contact.ip).is_none()
                    && !tracking.wrote_to(contact.ip, now)
                    && !host.is_known_client(contact.ip)
            };
            let helper = self.udp_test.next_helper(usable, self.public_ip, self.identity.id());
            let Some(helper) = helper else {
                return;
            };
            tracing::debug!("Asking {} to test our UDP port", Ipv4Addr::from(helper.ip));
            let key = self.identity.verify_key(helper.ip);
            let external = self.udp_test.external_port();
            self.host.check_udp(helper.ip, helper.tcp_port, udp_port, external, key);
        }
    }

    // A buddy is looked for while we are firewalled on both ports, and let go once we are not.
    fn tick_buddies(&mut self, now: Instant) {
        if now >= self.next_find_buddy {
            self.find_buddy = true;
            self.next_find_buddy = later(now, FIND_BUDDY_EVERY);
        }
        let state = self.buddies.state();
        let lost =
            state == BuddyState::None && matches!(self.buddy_state, BuddyState::Connected | BuddyState::Connecting);
        self.buddy_state = state;
        if !self.is_connected() {
            if state != BuddyState::None {
                self.buddies.release();
            }
            return;
        }
        if !self.needs_buddy() {
            if matches!(state, BuddyState::Connected | BuddyState::Connecting) {
                tracing::debug!("Kad: our buddy is no longer needed");
                self.buddies.release();
            }
            return;
        }
        // A buddy lost is replaced at once.
        if lost {
            self.find_buddy = true;
        }
        // Callback requests to us cannot be obfuscated, so a node that requires it cannot use a buddy.
        if state == BuddyState::None && self.find_buddy && self.settings.connect_options() & OBFUSCATION_REQUIRED == 0 {
            self.find_buddy = false;
            let target = self.identity.id() ^ ALL_ONES;
            if self.start_lookup(target, Kind::FindBuddy, LookupData::Nothing).is_none() {
                self.find_buddy = true;
            }
        }
    }

    // Firewalled on both ports, a node needs a buddy to be reached.
    fn needs_buddy(&self) -> bool {
        self.is_connected() && self.is_firewalled() && self.is_firewalled_udp()
    }

    fn stop_lookups(&mut self, kind: Kind) {
        let targets: Vec<UInt128> = self
            .lookups
            .values()
            .filter(|running| running.lookup.kind() == kind)
            .map(|running| running.lookup.target())
            .collect();
        for target in targets {
            self.lookup_ended(target);
        }
    }

    /// A Kad packet from `ip:port`; the keys come from its obfuscation (0 when plain).
    pub fn received(&mut self, ip: u32, port: u16, packet: &Packet, receiver_key: u32, sender_key: u32) {
        // A datagram from the DNS port without a sender key may be a DNS answer made to look like Kad.
        if port == DNS_PORT && sender_key == 0 {
            return;
        }
        let now = (self.clock)();
        if self.tracking.is_banned(ip, now) {
            return;
        }
        let opcode = packet.opcode();
        // Only the packet is dropped: the sender's address may be forged, so its contact is kept.
        if !self.tracking.allow(ip, opcode, now) {
            return;
        }
        self.last_packet = Some(now);
        let peer =
            Peer { ip, port, valid_key: receiver_key != 0 && receiver_key == self.identity.verify_key(ip), sender_key };
        let handled = match opcode {
            kad::BOOTSTRAP_REQ => {
                self.bootstrap_requested(peer);
                Ok(())
            }
            kad::BOOTSTRAP_RES => self.bootstrap_answered(peer, packet),
            kad::HELLO_REQ => self.hello_received(peer, packet, false),
            kad::HELLO_RES => self.hello_received(peer, packet, true),
            kad::HELLO_RES_ACK => self.hello_acknowledged(peer, packet),
            kad::REQ => self.lookup_requested(peer, packet),
            kad::RES => self.lookup_answered(peer, packet),
            kad::SEARCH_KEY_REQ => self.keyword_search_requested(peer, packet),
            kad::SEARCH_SOURCE_REQ => self.source_search_requested(peer, packet),
            kad::SEARCH_NOTES_REQ => self.notes_search_requested(peer, packet),
            kad::SEARCH_RES => self.search_answered(peer, packet),
            kad::PUBLISH_KEY_REQ => self.keyword_published(peer, packet),
            kad::PUBLISH_SOURCE_REQ => self.source_published(peer, packet),
            kad::PUBLISH_NOTES_REQ => self.note_published(peer, packet),
            kad::PUBLISH_RES => self.publish_answered(peer, packet),
            kad::FIREWALLED2_REQ => self.firewall_check_requested(peer, packet),
            kad::FIREWALLED_RES => self.firewall_answered(peer, packet),
            kad::FIREWALLUDP => self.udp_test_answered(peer, packet),
            kad::FINDBUDDY_REQ => self.buddy_requested(peer, packet),
            kad::FINDBUDDY_RES => self.buddy_offered(peer, packet),
            kad::CALLBACK_REQ => self.callback_requested(peer, packet),
            kad::PING => {
                self.answer(peer, &kad_messages::pong(port));
                Ok(())
            }
            kad::PONG => self.pong_received(peer, packet),
            // Kad 1 and acknowledgements we never ask for.
            _ => Ok(()),
        };
        if let Err(e) = handled {
            tracing::debug!("Bad Kad packet from {}:{port}: {e}", Ipv4Addr::from(ip));
        }
    }

    fn bootstrap_requested(&mut self, peer: Peer) {
        let contacts: Vec<Contact> = self
            .table
            .bootstrap_contacts(kad_messages::MAX_BOOTSTRAP_CONTACTS, is_known_live, &mut self.random)
            .into_iter()
            .map(KadContact::message)
            .collect();
        if let Ok(answer) = (kad_messages::BootstrapAnswer { sender: self.self_info(), contacts }).encode() {
            self.answer(peer, &answer);
        }
    }

    fn bootstrap_answered(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let now = (self.clock)();
        if !self.tracking.expected(peer.ip, kad::BOOTSTRAP_RES, now) {
            return Ok(());
        }
        let answer = kad_messages::BootstrapAnswer::decode(packet)?;
        let empty = self.table.size() == 0;
        // A node of a bootstrap list gives contacts but is not taken: everyone starting from that list would.
        if !self.asked_from_list.contains(&peer.ip) && self.is_good_address(peer.ip) {
            let sender = answer.sender;
            let mut contact = KadContact::new(sender.id, peer.ip, peer.port, sender.tcp_port, sender.version, now);
            contact.set_verified(true);
            contact.heard_from();
            self.table.add(contact, true, peer.key(self.public_ip), self.public_ip, now);
        }
        for contact in &answer.contacts {
            self.add_unverified(contact, empty, now);
        }
        Ok(())
    }

    fn hello_received(&mut self, peer: Peer, packet: &Packet, answer: bool) -> Result<(), DecodeError> {
        let now = (self.clock)();
        if answer && !self.tracking.expected(peer.ip, kad::HELLO_RES, now) {
            return Ok(());
        }
        let hello = kad_messages::Hello::decode(packet)?;
        let sender = hello.sender;
        // Our key for its address proves where the node is: the id it gives there counts as proven.
        if peer.valid_key && !self.proved(peer, sender.id, now) {
            return Ok(());
        }
        let claimed_only = !peer.valid_key && self.proven_ids.contradicts(peer.ip, peer.port, sender.id);
        let udp_port = tags::port_value(&hello.tags, file_tag::SOURCEUPORT).unwrap_or(peer.port);
        let misc = tags::int_value(&hello.tags, file_tag::KADMISCOPTIONS).unwrap_or(0);
        let version = sender.version;
        // A node greeting us has us in its table: counted, our table should hold no UDP-firewalled node.
        if !answer && version >= UDP_FIREWALLED_VERSION {
            if misc & MISC_UDP_FIREWALLED != 0 {
                self.udp_firewalled_nodes = self.udp_firewalled_nodes.saturating_add(1);
            } else {
                self.udp_open_nodes = self.udp_open_nodes.saturating_add(1);
            }
        }
        let mut outcome = Outcome::Refused;
        if !claimed_only && self.is_good_address(peer.ip) && misc & MISC_UDP_FIREWALLED == 0 {
            let mut contact = KadContact::new(sender.id, peer.ip, udp_port, sender.tcp_port, version, now);
            contact.set_verified(peer.valid_key);
            contact.heard_from();
            outcome = self.table.add(contact, true, peer.key(self.public_ip), self.public_ip, now);
            if let Some(known) = self.table.get_mut(&sender.id) {
                known.set_hello_received();
            }
        }
        let verified = self.table.get(&sender.id).map_or(peer.valid_key, KadContact::is_verified);
        if outcome != Outcome::Refused && !verified && !peer.valid_key {
            self.challenge(peer, sender.id, version, answer, now);
        }
        if !answer {
            let ask_ack = outcome != Outcome::Refused && !verified && version >= HELLO_ACK_VERSION;
            if let Ok(reply) = self.hello(version, ask_ack).encode(true) {
                let key = (version >= OBFUSCATION_VERSION && !sender.id.is_zero()).then_some(sender.id);
                self.send(peer.ip, peer.port, &reply, key, peer.sender_key);
                if ask_ack {
                    self.tracking.sent(peer.ip, kad::HELLO_RES, now);
                }
            }
        } else if misc & MISC_ACK_REQUESTED != 0 && version >= HELLO_ACK_VERSION && peer.sender_key != 0 {
            self.answer(peer, &kad_messages::hello_ack(&self.identity.id()));
        }
        self.maybe_check_firewall(peer, version, now);
        Ok(())
    }

    // Nodes older than the hello acknowledgement prove their address by answering a request only they were sent: a
    // ping for a node with keys, else a lookup for a random target. One at a time per address.
    fn challenge(&mut self, peer: Peer, id: UInt128, version: u8, answer: bool, now: Instant) {
        if version >= HELLO_ACK_VERSION
            || (answer && version >= KEYS_VERSION)
            || self.tracking.is_challenged(peer.ip, now)
        {
            return;
        }
        if version >= KEYS_VERSION {
            self.answer(peer, &kad_messages::ping());
            self.tracking.sent(peer.ip, kad::PING, now);
            self.tracking.challenged(peer.ip, kad::PING, UInt128::ZERO, id, now);
            return;
        }
        let target = UInt128::random(&mut self.random);
        // Such nodes cannot open obfuscated packets.
        if let Ok(request) = (kad_messages::Request { count: CHALLENGE_CONTACTS, target, receiver: id }).encode() {
            self.transport.send(peer.ip, peer.port, &request, None, 0, 0);
            self.tracking.sent(peer.ip, kad::REQ, now);
            self.tracking.challenged(peer.ip, kad::REQ, target, id, now);
        }
    }

    fn verified(&mut self, peer: Peer, id: UInt128, now: Instant) {
        if self.proved(peer, id, now)
            && let Some(contact) = self.table.get_mut(&id).filter(|contact| contact.ip() == peer.ip)
        {
            contact.set_verified(true);
        }
    }

    // False when the node changed its id too soon, which bans it.
    fn proved(&mut self, peer: Peer, id: UInt128, now: Instant) -> bool {
        if !self.proven_ids.proved(peer.ip, peer.port, id, now) {
            return true;
        }
        tracing::debug!("Kad: {} changed its id too soon, ignored for a while", Ipv4Addr::from(peer.ip));
        self.tracking.ban(peer.ip, ID_CHANGE_BAN, now);
        if let Some(gone) = self.table.by_address(peer.ip).map(KadContact::id) {
            self.table.remove(&gone);
        }
        false
    }

    fn hello_acknowledged(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let now = (self.clock)();
        if !peer.valid_key || !self.tracking.expected(peer.ip, kad::HELLO_RES_ACK, now) {
            return Ok(());
        }
        let id = kad_messages::decode_hello_ack(packet)?;
        self.verified(peer, id, now);
        Ok(())
    }

    fn lookup_requested(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let request = kad_messages::Request::decode(packet)?;
        if request.receiver != self.identity.id() {
            return Ok(());
        }
        let count = usize::from(request.count.min(kad_messages::MAX_LOOKUP_CONTACTS));
        let closest: Vec<Contact> =
            self.table.closest(request.target, 2, count).into_iter().map(KadContact::message).collect();
        if let Ok(answer) = (kad_messages::Answer { target: request.target, contacts: closest }).encode() {
            self.answer(peer, &answer);
        }
        Ok(())
    }

    fn lookup_answered(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let now = (self.clock)();
        if !self.tracking.expected(peer.ip, kad::RES, now) {
            return Ok(());
        }
        let answer = kad_messages::Answer::decode(packet)?;
        if let Some(id) = self.table.by_address(peer.ip).map(KadContact::id) {
            self.table.alive(&id, now);
        }
        // The answer to a challenge proves the contact; the contacts it carries are not wanted.
        if let Some(proven) = self.tracking.challenge_answered(peer.ip, kad::REQ, answer.target, now) {
            self.verified(peer, proven, now);
            return Ok(());
        }
        // Contacts are learnt only from the answer to a lookup that asked this node for that many, under the id the
        // node proved there if it did.
        let usable_lookup = self.lookups.get(&answer.target).is_some_and(|running| {
            let lookup = &running.lookup;
            lookup.asked(peer.ip, peer.port)
                && answer.contacts.len() <= usize::from(lookup.expected_contacts(peer.ip, peer.port))
                && !lookup
                    .asked_id(peer.ip, peer.port)
                    .is_some_and(|id| self.proven_ids.contradicts(peer.ip, peer.port, id))
        });
        if !usable_lookup {
            tracing::debug!("Ignoring {} contacts from {}", answer.contacts.len(), Ipv4Addr::from(peer.ip));
            return Ok(());
        }
        let helpers = self.lookups.get(&answer.target).is_some_and(|running| running.lookup.kind() == Kind::UdpHelpers);
        let mut usable = Vec::new();
        for contact in &answer.contacts {
            if contact.version < 2
                || contact.udp_port == 0
                || !self.is_good_address(contact.ip)
                || (contact.udp_port == DNS_PORT && contact.version <= 5)
            {
                continue;
            }
            // Helpers must never hear from us over UDP: they are neither added nor asked further.
            if helpers {
                self.udp_test.candidate(*contact);
                usable.push(*contact);
            } else if self.add_unverified(contact, false, now) == Outcome::Added
                || self.table.is_acceptable(&contact.id, contact.ip, contact.udp_port)
            {
                usable.push(*contact);
            }
        }
        let reach = self.reach();
        let Self { lookups, transport, tracking, table, identity, settings, response_times, public_ip, .. } = self;
        if let Some(Running { lookup, data, .. }) = lookups.get_mut(&answer.target) {
            let mut sender = LookupSender {
                transport: &mut **transport,
                tracking,
                table,
                identity,
                settings,
                public_ip: *public_ip,
                reach: &reach,
                data,
                now,
            };
            let told = if helpers { Vec::new() } else { usable };
            lookup.answered(peer.ip, peer.port, &told, now, response_times, &mut sender);
        }
        if helpers {
            self.ask_udp_helpers();
        }
        Ok(())
    }

    fn keyword_search_requested(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let search = kad_messages::KeywordSearch::decode(packet)?;
        let expression = if search.expression.is_empty() {
            None
        } else {
            Some(search_tree_codec::read(&mut PacketReader::of(&search.expression))?)
        };
        let now = self.ticks((self.clock)());
        let results = self.index.search_keyword(search.target, expression.as_ref(), usize::from(search.start), now);
        self.answer_results(peer, search.target, &results);
        Ok(())
    }

    fn source_search_requested(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let search = kad_messages::SourceSearch::decode(packet)?;
        let now = self.ticks((self.clock)());
        let results = self.index.search_sources(search.target, search.size, usize::from(search.start), now);
        self.answer_results(peer, search.target, &results);
        Ok(())
    }

    fn notes_search_requested(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let search = kad_messages::NotesSearch::decode(packet)?;
        let now = self.ticks((self.clock)());
        let results = self.index.search_notes(search.target, search.size, now);
        self.answer_results(peer, search.target, &results);
        Ok(())
    }

    fn answer_results(&mut self, peer: Peer, target: UInt128, results: &[Entry]) {
        if results.is_empty() {
            return;
        }
        let answers = kad_messages::SearchAnswer::split(self.identity.id(), target, results, kad_index::ANSWER_BYTES)
            .unwrap_or_default();
        for answer in answers {
            self.answer(peer, &answer);
        }
    }

    fn search_answered(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let now = (self.clock)();
        if !self.tracking.awaits(peer.ip, kad::SEARCH_RES, now) {
            return Ok(());
        }
        let answer = kad_messages::SearchAnswer::decode(packet)?;
        if self.proven_ids.contradicts(peer.ip, peer.port, answer.sender) {
            return Ok(());
        }
        let Some(running) = self.lookups.get_mut(&answer.target) else {
            return Ok(());
        };
        // Only a node this very search was sent to answers it.
        if running.lookup.is_stopped() || !running.lookup.requested_from(peer.ip) {
            return Ok(());
        }
        // Only usable results bring the lookup closer to its end: junk from one node must not stop it.
        let (found, event) = results_of(
            &mut running.data,
            &answer.results,
            SearchHandle { keyword: answer.target, serial: running.serial },
        );
        running.lookup.value_received(found, now);
        if let Some(event) = event {
            self.events.push(event);
        }
        Ok(())
    }

    // A node others cannot send to would store what nobody can ask for: it takes no publishes.
    fn keyword_published(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let publish = kad_messages::KeywordPublish::decode(packet)?;
        if self.is_firewalled_udp() || !in_tolerance(self.identity.id(), publish.keyword) {
            return Ok(());
        }
        let now = self.ticks((self.clock)());
        let mut load = 0;
        for file in &publish.files {
            load = self.index.publish_keyword(publish.keyword, file, peer.ip, now);
        }
        self.answer(peer, &kad_messages::PublishAnswer { target: publish.keyword, load }.encode());
        Ok(())
    }

    fn source_published(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let publish = kad_messages::SourcePublish::decode(packet)?;
        if self.is_firewalled_udp() || !in_tolerance(self.identity.id(), publish.file) {
            return Ok(());
        }
        let now = self.ticks((self.clock)());
        let load = self.index.publish_source(publish.file, publish.source, &publish.tags, peer.ip, peer.port, now);
        if let Some(load) = load {
            self.answer(peer, &kad_messages::PublishAnswer { target: publish.file, load }.encode());
        }
        Ok(())
    }

    fn note_published(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let publish = kad_messages::NotesPublish::decode(packet)?;
        // Like keywords and sources: a node others cannot ask over UDP keeps nothing for them.
        if self.is_firewalled_udp() || !in_tolerance(self.identity.id(), publish.file) {
            return Ok(());
        }
        let now = self.ticks((self.clock)());
        let load = self.index.publish_note(publish.file, publish.publisher, &publish.tags, peer.ip, now);
        if let Some(load) = load {
            self.answer(peer, &kad_messages::PublishAnswer { target: publish.file, load }.encode());
        }
        Ok(())
    }

    fn publish_answered(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let now = (self.clock)();
        if !self.tracking.expected(peer.ip, kad::PUBLISH_RES, now) {
            return Ok(());
        }
        let answer = kad_messages::PublishAnswer::decode(packet)?;
        if let Some(running) = self.lookups.get_mut(&answer.target)
            && running.lookup.requested_from(peer.ip)
        {
            if running.lookup.kind() == Kind::StoreKeyword {
                running.lookup.load_reported(answer.load);
            }
            running.lookup.value_received(1, now);
        }
        Ok(())
    }

    // We help only nodes on addresses that could be real: a loopback answer would mislead them.
    fn firewall_check_requested(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let check = kad_messages::FirewallCheck::decode(packet)?;
        if !self.is_good_address(peer.ip) || check.tcp_port == 0 {
            return Ok(());
        }
        self.answer(peer, &kad_messages::firewall_answer(peer.ip));
        self.host.check_reachable(peer.ip, check.tcp_port, check.user_hash.to_big_endian(), check.connect_options);
        Ok(())
    }

    fn firewall_answered(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let now = (self.clock)();
        if !self.firewall_checks.contains_key(&peer.ip) || !self.tracking.expected(peer.ip, kad::FIREWALLED_RES, now) {
            return Ok(());
        }
        let ip = kad_messages::decode_firewall_answer(packet)?;
        self.firewall_reports = self.firewall_reports.saturating_add(1);
        // A first report is taken; afterwards a new address needs two reports in a row.
        if self.public_ip == 0 || ip == self.public_ip || ip == self.pending_ip {
            self.public_ip = ip;
            self.pending_ip = 0;
        } else {
            self.pending_ip = ip;
        }
        Ok(())
    }

    fn pong_received(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let now = (self.clock)();
        if !self.tracking.expected(peer.ip, kad::PONG, now) {
            return Ok(());
        }
        if let Some(id) = self.tracking.challenge_answered(peer.ip, kad::PING, UInt128::ZERO, now) {
            self.verified(peer, id, now);
        }
        let port = kad_messages::decode_pong(packet)?;
        if port != 0 && self.udp_test.is_running() && self.udp_test.finds_external_port() {
            self.udp_test.port_seen(port, peer.ip);
            self.ask_udp_helpers();
        }
        Ok(())
    }

    // Only a helper we asked counts, and only with the key we gave it: a datagram from anyone else proves nothing.
    fn udp_test_answered(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        if !peer.valid_key || !self.udp_test.asked(peer.ip) {
            return Ok(());
        }
        let answer = kad_messages::FirewallUdp::decode(packet)?;
        let internal = self.settings.udp_port;
        let ours = answer.port != 0 && (answer.port == internal || answer.port == self.udp_test.external_port());
        let succeeded = ours && answer.error == 0;
        let now = (self.clock)();
        let port = if succeeded { answer.port } else { 0 };
        let more = self.udp_test.outcome(peer.ip, succeeded, !succeeded, port, internal, now);
        if !self.udp_test.is_running() {
            self.stop_lookups(Kind::UdpHelpers);
            let what = if self.udp_test.is_firewalled(false) { "firewalled" } else { "open" };
            tracing::info!("Kad: UDP {what}");
        } else if more {
            self.ask_udp_helpers();
        }
        Ok(())
    }

    // We serve one firewalled node, and only when both our ports are open: it must be able to reach us.
    fn buddy_requested(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let request = kad_messages::FindBuddy::decode(packet)?;
        if self.is_firewalled()
            || self.is_firewalled_udp()
            || !self.udp_test.is_verified()
            || self.buddies.state() != BuddyState::None
            || request.tcp_port == 0
            || !self.is_good_address(peer.ip)
            || self.firewall_checks.contains_key(&peer.ip)
        {
            return Ok(());
        }
        let user_hash = request.user_hash.to_big_endian();
        if !self.buddies.asked(peer.ip, request.tcp_port, peer.port, user_hash, request.buddy_id) {
            return Ok(());
        }
        // Only nodes recent enough to obfuscate read the connect options.
        let options = (peer.sender_key != 0).then(|| self.settings.connect_options());
        let offer = kad_messages::BuddyOffer {
            buddy_id: request.buddy_id,
            user_hash: UInt128::from_big_endian(self.settings.user_hash),
            tcp_port: self.settings.tcp_port,
            connect_options: options,
        };
        self.answer(peer, &offer.encode());
        Ok(())
    }

    fn buddy_offered(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let now = (self.clock)();
        if !self.tracking.expected(peer.ip, kad::FINDBUDDY_RES, now) {
            return Ok(());
        }
        let offer = kad_messages::BuddyOffer::decode(packet)?;
        let state = self.buddies.state();
        if !self.is_our_buddy_id(offer.buddy_id)
            || offer.tcp_port == 0
            || !self.is_good_address(peer.ip)
            || !self.needs_buddy()
            || !matches!(state, BuddyState::None | BuddyState::Connecting)
        {
            return Ok(());
        }
        let user_hash = offer.user_hash.to_big_endian();
        self.buddies.offered(peer.ip, offer.tcp_port, peer.port, user_hash, offer.connect_options.unwrap_or(0));
        Ok(())
    }

    // The node we serve is asked to connect to whoever sent this; the buddy id must be the one it registered.
    fn callback_requested(&mut self, peer: Peer, packet: &Packet) -> Result<(), DecodeError> {
        let request = kad_messages::CallbackRequest::decode(packet)?;
        if request.tcp_port == 0 || !self.is_good_address(peer.ip) || self.buddies.state() != BuddyState::Serving {
            return Ok(());
        }
        if self.buddies.relay(request.buddy_id, request.file, peer.ip, request.tcp_port) {
            tracing::debug!("Kad: callback request from {} relayed", Ipv4Addr::from(peer.ip));
        }
        Ok(())
    }

    fn maybe_check_firewall(&mut self, peer: Peer, version: u8, now: Instant) {
        if self.firewall_reports >= FIREWALL_CHECKS
            || version < FIREWALL2_VERSION
            || self.firewall_checks.contains_key(&peer.ip)
            || !self.is_good_address(peer.ip)
        {
            return;
        }
        self.firewall_checks.insert(peer.ip, now);
        let request = kad_messages::FirewallCheck {
            tcp_port: self.settings.tcp_port,
            user_hash: UInt128::from_big_endian(self.settings.user_hash),
            connect_options: self.settings.connect_options(),
        }
        .encode();
        match self.table.by_address(peer.ip).cloned() {
            Some(contact) => self.request(&contact, &request, kad::FIREWALLED2_REQ, now),
            None => {
                self.send(peer.ip, peer.port, &request, None, peer.sender_key);
                self.tracking.sent(peer.ip, kad::FIREWALLED2_REQ, now);
            }
        }
    }

    // Each minute a bin checks its oldest contact; contacts that keep failing are dropped.
    fn check_contacts(&mut self, now: Instant) {
        let bins: Vec<Vec<UInt128>> = self.table.leaves().iter().map(|zone| zone.contacts().to_vec()).collect();
        for bin in bins {
            for id in &bin {
                let dead = self.table.get(id).is_some_and(|contact| {
                    contact.contact_type() == kad_contact::DEAD && contact.is_expired(now) && !contact.in_use()
                });
                if dead {
                    self.table.remove(id);
                } else if let Some(contact) = self.table.get_mut(id) {
                    contact.touch_expires(now);
                }
            }
            let Some(oldest) = bin.iter().find(|id| self.table.get(id).is_some()).copied() else {
                continue;
            };
            let Some(contact) = self.table.get(&oldest) else {
                continue;
            };
            if !contact.is_expired(now) || contact.contact_type() == kad_contact::DEAD {
                self.table.to_back(&oldest);
                continue;
            }
            let version = contact.version();
            if let Some(contact) = self.table.get_mut(&oldest) {
                contact.checking(now);
            }
            if let (Ok(hello), Some(contact)) =
                (self.hello(version, false).encode(false), self.table.get(&oldest).cloned())
            {
                self.request(&contact, &hello, kad::HELLO_REQ, now);
            }
        }
    }

    // One zone at a time, at most every ten seconds, looks for more contacts once an hour.
    fn refresh_one_zone(&mut self, now: Instant) {
        let mut target = None;
        for zone in self.table.leaves_mut() {
            if zone.next_big_timer().is_some_and(|next| now < next) {
                continue;
            }
            zone.set_next_big_timer(later(now, HOUR));
            if zone.wants_contacts() {
                target = Some((zone.level(), zone.index()));
                break;
            }
        }
        let Some((level, index)) = target else {
            return;
        };
        let id = self.table.random_id_in_zone(level, index, &mut self.random);
        self.start_lookup(id, Kind::Node, LookupData::Nothing);
    }

    // The serial of the lookup started, none when it could not start or ended at once.
    fn start_lookup(&mut self, target: UInt128, kind: Kind, data: LookupData) -> Option<u64> {
        if self.lookups.contains_key(&target) {
            return None;
        }
        let now = (self.clock)();
        let serial = self.next_serial;
        self.next_serial = self.next_serial.saturating_add(1);
        let lookup = Lookup::new(target, kind, Arc::clone(&self.settings.trusted), now);
        self.lookups.insert(target, Running { lookup, data, serial });
        let reach = self.reach();
        let Self { lookups, transport, tracking, table, identity, settings, public_ip, .. } = self;
        if let Some(Running { lookup, data, .. }) = lookups.get_mut(&target) {
            let mut sender = LookupSender {
                transport: &mut **transport,
                tracking,
                table,
                identity,
                settings,
                public_ip: *public_ip,
                reach: &reach,
                data,
                now,
            };
            lookup.start(table, &mut sender);
        }
        if self.lookups.get(&target).is_some_and(|running| running.lookup.is_stopped()) {
            self.lookup_ended(target);
            return None;
        }
        Some(serial)
    }

    fn lookup_ended(&mut self, target: UInt128) {
        let Some(running) = self.lookups.shift_remove(&target) else {
            return;
        };
        let kind = running.lookup.kind();
        if kind == Kind::NodeComplete {
            self.self_lookup_done = true;
        }
        let load = running.lookup.load();
        if kind == Kind::StoreKeyword && load > HIGH_LOAD {
            let spared = LOADED_FOR.checked_div(100).unwrap_or_default().saturating_mul(u32::from(load));
            let until = self.ticks((self.clock)()).saturating_add(nanos(spared));
            self.index.loaded(target, until);
        }
        if let LookupData::Notes { file, .. } = running.data {
            self.events.push(KadEvent::NotesEnded { file });
        }
    }

    // How others reach us, as source publishes tell it.
    fn reach(&self) -> Reach {
        let buddy = (self.buddies.state() == BuddyState::Connected && self.buddies.buddy_ip() != 0)
            .then(|| (self.buddies.buddy_ip(), self.buddies.buddy_udp_port()));
        Reach {
            firewalled_for_clients: self.is_firewalled_for_clients(),
            direct_callback: !self.is_firewalled_udp() && self.udp_test.is_verified(),
            buddy,
            use_external_port: self.udp_test.use_external_port(),
            our_id: self.identity.id(),
        }
    }

    fn request(&mut self, contact: &KadContact, request: &Packet, kind: u8, now: Instant) {
        request_known(
            &mut *self.transport,
            &mut self.tracking,
            &self.identity,
            self.public_ip,
            contact,
            request,
            kind,
            now,
        );
    }

    fn answer(&mut self, peer: Peer, answer: &Packet) {
        self.send(peer.ip, peer.port, answer, None, peer.sender_key);
    }

    fn send(&mut self, ip: u32, port: u16, packet: &Packet, receiver_id: Option<UInt128>, receiver_key: u32) {
        self.transport.send(ip, port, packet, receiver_id, receiver_key, self.identity.verify_key(ip));
    }

    fn hello(&self, peer_version: u8, ask_ack: bool) -> kad_messages::Hello {
        let mut tags = Vec::new();
        // Our own port, once the UDP test proved it better than the one a NAT shows.
        if !self.udp_test.use_external_port() && self.settings.udp_port != 0 {
            tags.push(int_tag(file_tag::SOURCEUPORT, u64::from(self.settings.udp_port)));
        }
        let tcp = self.is_firewalled();
        let udp = self.is_firewalled_udp();
        // A UDP-firewalled node says so: others do not take it as a contact.
        if peer_version >= HELLO_ACK_VERSION && (ask_ack || tcp || udp) {
            let misc = (if udp { MISC_UDP_FIREWALLED } else { 0 })
                | (if tcp { MISC_TCP_FIREWALLED } else { 0 })
                | (if ask_ack { MISC_ACK_REQUESTED } else { 0 });
            tags.push(int_tag(file_tag::KADMISCOPTIONS, misc));
        }
        kad_messages::Hello { sender: self.self_info(), tags }
    }

    fn self_info(&self) -> SelfInfo {
        SelfInfo { id: self.identity.id(), tcp_port: self.settings.tcp_port, version: kad_messages::VERSION }
    }

    fn add_unverified(&mut self, contact: &Contact, verified: bool, now: Instant) -> Outcome {
        if !self.is_good_address(contact.ip) || contact.version < 2 {
            return Outcome::Refused;
        }
        let mut added = KadContact::of(contact, now);
        added.set_verified(verified);
        self.table.add(added, false, UdpKey::default(), self.public_ip, now)
    }

    // Public unicast addresses the IP filter allows, or what the settings trust (tests, LANs).
    fn is_good_address(&self, ip: u32) -> bool {
        good_address(&self.settings, ip)
    }

    // The node's ticker, as the index counts time.
    fn ticks(&self, now: Instant) -> i64 {
        i64::try_from(now.saturating_duration_since(self.origin).as_nanos()).unwrap_or(i64::MAX)
    }
}

impl kad_publisher::Node for KadNode {
    fn is_ready(&self) -> bool {
        KadNode::is_ready(self)
    }

    fn is_connected(&self) -> bool {
        KadNode::is_connected(self)
    }

    fn is_firewalled(&self) -> bool {
        KadNode::is_firewalled(self)
    }

    fn is_reachable(&self) -> bool {
        KadNode::is_reachable(self)
    }

    fn buddy_ip(&self) -> u32 {
        KadNode::buddy_ip(self)
    }

    fn running_keyword_publishes(&self) -> usize {
        self.running(Kind::StoreKeyword)
    }

    fn running_source_publishes(&self) -> usize {
        self.running(Kind::StoreSource)
    }

    fn running_note_publishes(&self) -> usize {
        self.running(Kind::StoreNotes)
    }

    fn publish_keyword(&mut self, word: &str, files: Vec<Entry>) -> bool {
        KadNode::publish_keyword(self, word, files)
    }

    fn publish_source(&mut self, hash: [u8; 16], size: u64) -> bool {
        KadNode::publish_source(self, hash, size)
    }

    fn publish_note(&mut self, hash: [u8; 16], name: &str, size: u64) -> bool {
        KadNode::publish_note(self, hash, name, size)
    }
}

impl kad_notes::Finder for KadNode {
    fn is_ready(&self) -> bool {
        KadNode::is_ready(self)
    }

    fn find_notes(&mut self, file: [u8; 16], size: u64) -> bool {
        KadNode::find_notes(self, file, size)
    }
}

/// The packets lookups send.
struct LookupSender<'a> {
    transport: &'a mut dyn Transport,
    tracking: &'a mut PacketTracking,
    table: &'a RoutingTable,
    identity: &'a KadIdentity,
    settings: &'a Settings,
    public_ip: u32,
    reach: &'a Reach,
    data: &'a LookupData,
    now: Instant,
}

impl LookupSender<'_> {
    fn request_to(&mut self, contact: &Contact, request: &Packet, kind: u8) {
        if let Some(known) = self.table.get(&contact.id) {
            request_known(
                &mut *self.transport,
                self.tracking,
                self.identity,
                self.public_ip,
                known,
                request,
                kind,
                self.now,
            );
            return;
        }
        let key = (contact.version >= OBFUSCATION_VERSION).then_some(contact.id);
        let sender_key = self.identity.verify_key(contact.ip);
        self.transport.send(contact.ip, contact.udp_port, request, key, 0, sender_key);
        self.tracking.sent(contact.ip, kind, self.now);
    }
}

impl Requests for LookupSender<'_> {
    fn ask(&mut self, target: UInt128, _: Kind, contact: &Contact, count: u8) {
        if let Ok(request) = (kad_messages::Request { count, target, receiver: contact.id }).encode() {
            self.request_to(contact, &request, kad::REQ);
        }
    }

    fn request(&mut self, target: UInt128, kind: Kind, contact: &Contact) -> Requested {
        match (kind, self.data) {
            (Kind::Keyword, LookupData::Keyword { tree, .. }) => {
                let search = kad_messages::KeywordSearch { target, start: 0, expression: tree.clone() }.encode();
                self.request_to(contact, &search, kad::SEARCH_KEY_REQ);
            }
            (Kind::Sources, LookupData::Sources { size, .. }) => {
                let search = kad_messages::SourceSearch { target, start: 0, size: *size }.encode();
                self.request_to(contact, &search, kad::SEARCH_SOURCE_REQ);
            }
            (Kind::Notes, LookupData::Notes { size, .. }) => {
                let search = kad_messages::NotesSearch { target, size: *size }.encode();
                self.request_to(contact, &search, kad::SEARCH_NOTES_REQ);
            }
            (Kind::StoreKeyword, LookupData::StoreKeyword { files }) => {
                for batch in files.chunks(kad_messages::MAX_PUBLISHED_FILES) {
                    let publish = kad_messages::KeywordPublish { keyword: target, files: batch.to_vec() };
                    if let Ok(publish) = publish.encode() {
                        self.request_to(contact, &publish, kad::PUBLISH_KEY_REQ);
                    }
                }
            }
            (Kind::StoreSource, LookupData::StoreSource { size }) => {
                if let Some(tags) = source_tags(self.settings, self.reach, *size) {
                    let source = UInt128::from_big_endian(self.settings.user_hash);
                    if let Ok(publish) = (kad_messages::SourcePublish { file: target, source, tags }).encode() {
                        self.request_to(contact, &publish, kad::PUBLISH_SOURCE_REQ);
                    }
                }
            }
            (Kind::FindBuddy, _) => {
                let find = kad_messages::FindBuddy {
                    buddy_id: target,
                    user_hash: UInt128::from_big_endian(self.settings.user_hash),
                    tcp_port: self.settings.tcp_port,
                    // A buddy serving one firewalled node already takes another only if it reaches it through the
                    // routers.
                    connect_options: Some(self.settings.connect_options()),
                }
                .encode();
                self.request_to(contact, &find, kad::FINDBUDDY_REQ);
                return Requested::Counted(1);
            }
            (Kind::StoreNotes, LookupData::StoreNotes { file, name, size }) => {
                let Some(comment) = (self.settings.comments)(file).filter(|comment| !comment.is_empty()) else {
                    // The comment was taken away meanwhile: the nodes left hear nothing of it.
                    return Requested::Stop;
                };
                let publish = kad_messages::NotesPublish {
                    file: target,
                    publisher: self.identity.id(),
                    tags: note_tags(name, *size, &comment),
                };
                if let Ok(publish) = publish.encode() {
                    self.request_to(contact, &publish, kad::PUBLISH_NOTES_REQ);
                }
            }
            // Node lookups only collect contacts.
            _ => {}
        }
        Requested::Sent
    }
}

#[allow(clippy::too_many_arguments)]
fn request_known(
    transport: &mut dyn Transport,
    tracking: &mut PacketTracking,
    identity: &KadIdentity,
    public_ip: u32,
    contact: &KadContact,
    request: &Packet,
    kind: u8,
    now: Instant,
) {
    let key = (contact.version() >= OBFUSCATION_VERSION).then_some(contact.id());
    let stored = contact.udp_key();
    let receiver_key = if stored.ip == public_ip { stored.key } else { 0 };
    transport.send(contact.ip(), contact.udp_port(), request, key, receiver_key, identity.verify_key(contact.ip()));
    tracking.sent(contact.ip(), kind, now);
}

// What a search answer brings: the usable results, as an event, and how many there were.
fn results_of(data: &mut LookupData, entries: &[Entry], search: SearchHandle) -> (u32, Option<KadEvent>) {
    match data {
        LookupData::Keyword { keywords, expression, .. } => {
            let matcher = expression.as_ref().map(Matcher::new);
            let results = file_results(entries, keywords, matcher.as_ref());
            (count(results.len()), Some(KadEvent::Files { search, results }))
        }
        LookupData::Sources { file, .. } => {
            let sources = source_results(entries);
            (count(sources.len()), Some(KadEvent::Sources { file: *file, sources }))
        }
        LookupData::Notes { file, size, publishers } => {
            let notes = note_results(entries, *size, publishers);
            let found = count(notes.len());
            (found, (!notes.is_empty()).then_some(KadEvent::Notes { file: *file, notes }))
        }
        _ => (0, None),
    }
}

fn count(found: usize) -> u32 {
    u32::try_from(found).unwrap_or(u32::MAX)
}

// How others reach us as a source: directly, through our buddy or by a direct callback; none when they cannot.
fn source_tags(settings: &Settings, reach: &Reach, size: u64) -> Option<Vec<Tag>> {
    let large = size > LARGE_SOURCE;
    let mut direct = false;
    let mut tags = Vec::new();
    if !reach.firewalled_for_clients {
        tags.push(int_tag(file_tag::SOURCETYPE, if large { SOURCE_DIRECT_LARGE } else { SOURCE_DIRECT }));
    } else if reach.direct_callback {
        direct = true;
        tags.push(int_tag(file_tag::SOURCETYPE, SOURCE_DIRECT_CALLBACK));
    } else if let Some((ip, port)) = reach.buddy {
        tags.push(int_tag(file_tag::SOURCETYPE, if large { SOURCE_BUDDY_LARGE } else { SOURCE_BUDDY }));
        tags.push(int_tag(file_tag::SERVERIP, u64::from(ip.swap_bytes())));
        tags.push(int_tag(file_tag::SERVERPORT, u64::from(port)));
        tags.push(Tag::String { name: TagName::id(file_tag::BUDDYHASH), value: buddy_hash(reach.our_id) });
    } else {
        return None;
    }
    tags.push(int_tag(file_tag::SOURCEPORT, u64::from(settings.tcp_port)));
    if !reach.use_external_port && settings.udp_port != 0 {
        tags.push(int_tag(file_tag::SOURCEUPORT, u64::from(settings.udp_port)));
    }
    tags.push(int_tag(file_tag::FILESIZE, size));
    let options = settings.connect_options() | if direct { DIRECT_CALLBACK } else { 0 };
    tags.push(int_tag(file_tag::ENCRYPTION, u64::from(options)));
    if let Some(ours) = (settings.ipv6)() {
        let hex: String = ours.octets().iter().map(|byte| format!("{byte:02X}")).collect();
        tags.push(Tag::String { name: ipv6_tag(), value: hex });
    }
    Some(tags)
}

/// A source's public IPv6 address, as 32 hex digits of its bytes in order.
pub fn ipv6_tag() -> TagName {
    TagName::of_bytes(b"ip6")
}

/// The buddy hash of a source publish: the complement of the Kad id, as hex of its id layout.
pub fn buddy_hash(kad_id: UInt128) -> String {
    (kad_id ^ ALL_ONES).to_wire_bytes().iter().map(|byte| format!("{byte:02x}")).collect()
}

/// The Kad id of a buddy hash, none for one that is no hash.
pub fn parse_buddy_hash(hex: &str) -> Option<UInt128> {
    let bytes = hex_bytes(hex)?;
    UInt128::from_wire_bytes(&bytes).ok()
}

fn hex_bytes(hex: &str) -> Option<Vec<u8>> {
    if !hex.len().is_multiple_of(2) || !hex.bytes().all(|digit| digit.is_ascii_hexdigit()) {
        return None;
    }
    (0..hex.len())
        .step_by(2)
        .map(|at| hex.get(at..at.saturating_add(2)).and_then(|pair| u8::from_str_radix(pair, 16).ok()))
        .collect()
}

fn note_tags(name: &str, size: u64, comment: &FileComment) -> Vec<Tag> {
    let mut tags = vec![Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() }];
    if comment.rating() > 0 {
        tags.push(int_tag(file_tag::FILERATING, u64::from(comment.rating())));
    }
    if !comment.text().is_empty() {
        tags.push(Tag::String { name: TagName::id(file_tag::DESCRIPTION), value: comment.text().to_owned() });
    }
    tags.push(int_tag(file_tag::FILESIZE, size));
    tags
}

// A node may answer what it holds under the keyword without the filter: the whole expression is checked again.
fn file_results(entries: &[Entry], keywords: &[String], matcher: Option<&Matcher<'_>>) -> Vec<FileResult> {
    let mut results = Vec::new();
    for entry in entries {
        let Some(name) = tags::string_value(&entry.tags, file_tag::FILENAME) else {
            continue;
        };
        let size = tags::int_value(&entry.tags, file_tag::FILESIZE).unwrap_or(0);
        let words = keywords::of(name);
        if size == 0
            || !keywords.iter().all(|keyword| words.contains(keyword))
            || matcher
                .is_some_and(|matcher| !matcher.matches(name, &kad_lower_case::to_lower_case(name), size, &entry.tags))
        {
            continue;
        }
        let sources = tags::int_value(&entry.tags, file_tag::SOURCES).unwrap_or(0);
        results.push(FileResult {
            hash: entry.id.to_big_endian(),
            name: name.to_owned(),
            size,
            sources: if sources > MAX_AVAILABILITY { 0 } else { u32::try_from(sources).unwrap_or(0) },
            file_type: tags::string_value(&entry.tags, file_tag::FILETYPE).map(str::to_owned),
            media: media_tags::read(&entry.tags),
            aich_hash: agreed_aich(&entry.tags),
        });
    }
    results
}

/// The AICH root a node tells for a file, with how many of its publishers gave each root: taken only when it is the one
/// root, given by a third of the publishers at least, as a wrong one keeps the download from completing.
pub fn agreed_aich(tags: &[Tag]) -> Option<[u8; 20]> {
    let told = tags.iter().rev().find_map(|tag| match tag {
        Tag::Bsob { name, value } if *name == TagName::id(file_tag::KADAICHHASHRESULT) => Some(value.as_slice()),
        _ => None,
    })?;
    let publishers = tags::int_value(tags, file_tag::PUBLISHINFO).unwrap_or(0) >> 16 & 0xFF;
    let mut roots = Vec::new();
    let mut popularity = 0u64;
    let mut input = PacketReader::of(told);
    let count = input.u8().ok()?;
    for _ in 0..count {
        let given = input.u8().ok()?;
        let root: [u8; 20] = input.bytes(20).ok()?.try_into().ok()?;
        if given > 0 {
            roots.push(root);
            popularity = u64::from(given);
        }
    }
    if roots.len() != 1 || publishers == 0 || publishers.checked_div(popularity).is_none_or(|ratio| ratio > 3) {
        return None;
    }
    roots.first().copied()
}

// A note on a file of another size, or with neither comment nor rating, says nothing of this file.
fn note_results(entries: &[Entry], size: u64, publishers: &mut HashSet<UInt128>) -> Vec<Note> {
    let mut notes = Vec::new();
    for entry in entries {
        let comment = kad_index::note_comment(&entry.tags);
        let note_size = tags::int_value(&entry.tags, file_tag::FILESIZE).unwrap_or(0);
        if comment.is_empty() || (size != 0 && note_size != 0 && note_size != size) || !publishers.insert(entry.id) {
            continue;
        }
        let name =
            kad_index::cut(tags::string_value(&entry.tags, file_tag::FILENAME).unwrap_or(""), kad_index::MAX_NOTE_NAME);
        notes.push(Note { publisher: entry.id, file_name: name, size: note_size, comment });
    }
    notes
}

fn ipv6_of(tags: &[Tag]) -> Option<Ipv6Addr> {
    let hex = tags.iter().find_map(|tag| match tag {
        Tag::String { name, value } if *name == ipv6_tag() => Some(value.as_str()),
        _ => None,
    })?;
    if hex.len() != 32 {
        return None;
    }
    ipv6::public_of(&hex_bytes(hex)?)
}

/// Sources reachable directly, through their buddy, or by a direct callback when they say they take it.
pub fn source_results(entries: &[Entry]) -> Vec<Source> {
    let mut sources = Vec::new();
    for entry in entries {
        let found = &entry.tags;
        let source_type = tags::int_value(found, file_tag::SOURCETYPE).unwrap_or(0);
        let ip = tags::int_value(found, file_tag::SOURCEIP).and_then(|ip| u32::try_from(ip).ok()).unwrap_or(0);
        let port = tags::port_value(found, file_tag::SOURCEPORT).unwrap_or(0);
        let udp = tags::port_value(found, file_tag::SOURCEUPORT).unwrap_or(0);
        let options = u8::try_from(tags::int_value(found, file_tag::ENCRYPTION).unwrap_or(0) & 0xFF).unwrap_or(0);
        if ip == 0 || port == 0 {
            continue;
        }
        let user_hash = entry.id.to_big_endian();
        let ipv6 = ipv6_of(found);
        let source = |udp_port: u16, buddy: Option<Buddy>, direct_callback: bool| Source {
            user_hash,
            ip,
            tcp_port: port,
            connect_options: options,
            udp_port,
            buddy,
            direct_callback,
            ipv6,
        };
        match source_type {
            SOURCE_DIRECT | SOURCE_DIRECT_LARGE => sources.push(source(0, None, false)),
            SOURCE_BUDDY | SOURCE_BUDDY_LARGE => {
                let buddy_ip = tags::int_value(found, file_tag::SERVERIP)
                    .and_then(|ip| u32::try_from(ip).ok())
                    .map_or(0, u32::swap_bytes);
                let buddy_port = tags::port_value(found, file_tag::SERVERPORT).unwrap_or(0);
                let buddy_id = tags::string_value(found, file_tag::BUDDYHASH).and_then(parse_buddy_hash);
                if let Some(id) = buddy_id.filter(|_| buddy_ip != 0 && buddy_port != 0) {
                    sources.push(source(udp, Some(Buddy { ip: buddy_ip, udp_port: buddy_port, id }), false));
                }
            }
            SOURCE_DIRECT_CALLBACK if options & DIRECT_CALLBACK != 0 && udp != 0 => {
                sources.push(source(udp, None, true));
            }
            // Type 2 is read wrongly by old clients; other types are unknown.
            _ => {}
        }
    }
    sources
}

/// How much to scale the nodes our table sees by, for the firewalled ones: `newer_share` of our contacts are recent
/// enough to tell whether they are UDP-firewalled.
pub fn firewalled_modifier(firewalled_udp: bool, firewalled_share: f64, newer_share: f64) -> f64 {
    let older = 1.20;
    let mut newer = 0.0;
    if firewalled_udp {
        newer = 1.40;
    } else if firewalled_share > 0.0 {
        newer = 1.0 + firewalled_share;
    }
    if newer_share > 0.0 && newer > 0.0 { newer_share * newer + (1.0 - newer_share) * older } else { older }
}

// Contacts only loaded or told of by other nodes, and dead ones, are neither saved nor handed out: nodes.dat keeps no
// type or age, and a node starting from our bootstrap answer takes them as verified.
fn is_known_live(contact: &KadContact) -> bool {
    contact.was_heard_from() && contact.contact_type() < kad_contact::DEAD
}

fn good_address(settings: &Settings, ip: u32) -> bool {
    if (settings.blocked)(ip) {
        return false;
    }
    if (settings.trusted)(ip) {
        return true;
    }
    ipv4::is_unicast_public_candidate(ip) && !ipv4::is_lan(ip)
}

fn in_tolerance(own: UInt128, target: UInt128) -> bool {
    (target ^ own).top32() <= lookup::TOLERANCE
}

fn info_kind(kind: Kind) -> kad_search_info::Kind {
    match kind {
        Kind::Node | Kind::NodeComplete | Kind::UdpHelpers | Kind::FindBuddy => kad_search_info::Kind::Node,
        Kind::Keyword => kad_search_info::Kind::Keyword,
        Kind::Sources => kad_search_info::Kind::Sources,
        Kind::Notes => kad_search_info::Kind::Notes,
        Kind::StoreKeyword => kad_search_info::Kind::StoreKeyword,
        Kind::StoreSource => kad_search_info::Kind::StoreSource,
        Kind::StoreNotes => kad_search_info::Kind::StoreNotes,
    }
}

fn int_tag(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

fn later(now: Instant, by: Duration) -> Instant {
    now.checked_add(by).unwrap_or(now)
}

fn nanos(duration: Duration) -> i64 {
    i64::try_from(duration.as_nanos()).unwrap_or(i64::MAX)
}

fn wall_seconds() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |since| since.as_secs())
}
