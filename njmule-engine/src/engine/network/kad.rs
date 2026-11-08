// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Kad in the network: the client UDP socket, which Kad shares, and the node on it, started with the preferences or by
//! hand and ticked each second, and the host the node asks to connect to other clients over TCP.

use crate::api::log_line::Level as LogLevel;
use std::collections::{HashMap, HashSet};
use std::io::{self, ErrorKind};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::sync::{mpsc, oneshot};

use super::{Network, NotesOutcome, PeerEvent, Rater, Tick};
use crate::api::download_info::DownloadStatus;
use crate::api::kad_contact_info::KadContactInfo;
use crate::api::kad_info::{Buddy, KadInfo};
use crate::api::kad_note::KadNote;
use crate::api::kad_search_info::KadSearchInfo;
use crate::config::bind_interface::Binding;
use crate::config::obfuscation_mode::ObfuscationMode;
use crate::download::download_manager::{KadCallback, KadLink, KadSource};
use crate::engine::{Shared, crypt_options};
use crate::files::comment_filter::CommentFilter;
use crate::kad::kad_identity::KadIdentity;
use crate::kad::kad_node::{self, Buddies, BuddyState, Host, KadEvent, KadNode, SearchHandle, Settings};
use crate::kad::kad_notes::{self, KadNotes};
use crate::kad::kad_publisher::{self, KadPublisher};
use crate::kad::kad_socket::{Incoming, KadSocket, Keys};
use crate::kad::uint128::UInt128;
use crate::net::relay_keeper::{Level, RelayKeeper};
use crate::net::routes::Routes;
use crate::net::tcp_connector;
use crate::peer::buddy_link::{BuddyAction, BuddyLink};
use crate::peer::peer_greetings;
use crate::peer::peer_link::{LinkHandle, LinkId};
use crate::persist::format::nodes_dat;
use crate::protocol::ed2k::client_reasks::Relayed;
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::ed2k::low_id_messages::{Callback, DirectCallbackRequest, UdpCheckRequest};
use crate::protocol::ed2k::server_messages::LOW_ID_LIMIT;
use crate::protocol::packet::opcodes::{client, client_udp, extended};
use crate::protocol::packet::{Packet, frame_limits, protocol};
use crate::search::search_expr::SearchExpr;
use crate::search::search_manager::KadSearches;
use crate::server::server_list;
use crate::upload::upload_manager::KadRequest;
use crate::util::ipv4;

/// How often a Kad port another program holds is tried again.
pub(super) const PORT_RETRY_SECONDS: u32 = 10;
// The datagram of a UDP test helper comes within this time, or the test counts it as not come.
const UDP_CHECK_WAIT: Duration = peer_greetings::IDLE_TIMEOUT;
// Kad nodes before this version do not know the UDP test.
const UDP_CHECK_VERSION: u32 = 6;
// Connect options: obfuscation supported, and requested.
const CONNECT_SUPPORTED: u8 = 0x01;
const CONNECT_REQUESTED: u8 = 0x02;

/// The node on its socket, and what keeps them going.
struct Running {
    socket: KadSocket,
    node: KadNode,
    publisher: KadPublisher,
    relay: RelayKeeper,
}

/// What Kad tells of its checks of our ports, while it runs.
pub(super) struct KadChecks {
    /// Which start of Kad the node is.
    pub(super) node: u64,
    /// How many checks it started.
    pub(super) rounds: u32,
    /// How the last ones ended, open or not; none while they run.
    pub(super) tcp: Option<bool>,
    pub(super) udp: Option<bool>,
}

/// Kad as the network holds it: running or not, and why not.
pub(super) struct KadSide {
    running: Option<Running>,
    // How many times Kad started.
    starts: u64,
    // Disconnected by the user: new ports and interfaces do not start it again.
    stopped_by_hand: bool,
    // The UDP port another program held when Kad started, tried again until it is free; 0 when none.
    pub(super) port_taken: u16,
    since_port_retry: u32,
    incoming: mpsc::UnboundedSender<Incoming>,
    // The clients we deal with, which cannot be UDP test helpers.
    known: Arc<Mutex<HashSet<u32>>>,
    // The keyword searches Kad runs, by the number the search manager gave them.
    searches: HashMap<SearchHandle, u64>,
    // The TCP link to our buddy, or to the firewalled node we serve, which the node shares.
    buddies: Arc<Mutex<BuddyLink>>,
    // What Kad nodes said of our files, kept while Kad stops and starts again.
    notes: KadNotes,
}

impl KadSide {
    pub(super) fn new(incoming: mpsc::UnboundedSender<Incoming>) -> Self {
        let known: Arc<Mutex<HashSet<u32>>> = Arc::default();
        let dealt_with = Arc::clone(&known);
        let buddies = BuddyLink::new(
            Box::new(Instant::now),
            Box::new(move |ip| dealt_with.lock().unwrap_or_else(PoisonError::into_inner).contains(&ip)),
        );
        Self {
            running: None,
            starts: 0,
            stopped_by_hand: false,
            port_taken: 0,
            since_port_retry: 0,
            incoming,
            known,
            searches: HashMap::new(),
            buddies: Arc::new(Mutex::new(buddies)),
            notes: KadNotes::new(Instant::now()),
        }
    }

    /// What finds the sources of downloads on Kad, asks the firewalled ones to call us and re-asks sources over its
    /// port, while it runs; `our_ip` is part of the key of obfuscated re-asks.
    pub(super) fn finding<'a>(&'a mut self, our_ip: u32, calling: Calling<'a>) -> Option<KadFinding<'a>> {
        let running = self.running.as_mut()?;
        Some(KadFinding { node: &mut running.node, socket: &running.socket, our_ip, calling })
    }

    /// What starts keyword searches on Kad, while it runs.
    pub(super) fn searching(&mut self) -> Option<KadSearching<'_>> {
        let running = self.running.as_mut()?;
        Some(KadSearching { node: &mut running.node, searches: &mut self.searches })
    }
}

/// Finds the sources of downloads on the node, asks the firewalled ones to call us, and re-asks sources over its socket.
pub(super) struct KadFinding<'a> {
    node: &'a mut KadNode,
    socket: &'a KadSocket,
    our_ip: u32,
    calling: Calling<'a>,
}

/// What a firewalled source asked to call us needs to know, and whom we do not ask.
pub(super) struct Calling<'a> {
    pub(super) obfuscation: ObfuscationMode,
    pub(super) filter_lan: bool,
    pub(super) tcp_port: u16,
    pub(super) user_hash: [u8; 16],
    pub(super) banned: &'a dyn Fn(u32) -> bool,
}

impl KadLink for KadFinding<'_> {
    fn find_sources(&mut self, file: &[u8; 16], size: u64) -> bool {
        self.node.find_sources(*file, size)
    }

    // Firewalled, our address and port may be those of another client behind the same router.
    fn public_ip(&self) -> u32 {
        if self.node.is_firewalled() { 0 } else { self.node.public_ip() }
    }

    // Clients do not obfuscate their datagrams over IPv6.
    fn send_datagram(&mut self, to: SocketAddr, packet: &Packet, obfuscate_for: Option<[u8; 16]>) {
        match to {
            SocketAddr::V4(v4) => {
                self.socket.send_client(u32::from(*v4.ip()), v4.port(), packet, obfuscate_for, self.our_ip);
            }
            SocketAddr::V6(_) => self.socket.send_client_to(to, packet),
        }
    }

    // Through the buddy of the source, plain; or straight to its Kad port, obfuscated when either side asks for it.
    fn request_callback(&mut self, source: &KadSource, file: &[u8; 16]) -> bool {
        let Some(route) = source.callback else {
            return false;
        };
        if let Some(buddy_id) = route.buddy_id {
            let buddy = kad_node::Buddy {
                ip: route.buddy_ip,
                udp_port: route.buddy_port,
                id: UInt128::from_big_endian(buddy_id),
            };
            return !(self.calling.banned)(route.buddy_ip) && self.node.request_callback(&buddy, *file);
        }
        let ip = u32::from(*source.address.ip());
        let Some(user_hash) = source.user_hash else {
            return false;
        };
        if route.udp_port == 0
            || !server_list::is_usable(ip)
            || (self.calling.filter_lan && server_list::is_lan(ip))
            || (self.calling.banned)(ip)
        {
            return false;
        }
        let mode = self.calling.obfuscation;
        let obfuscate = source.crypt_options & CONNECT_SUPPORTED != 0
            && mode != ObfuscationMode::Disabled
            && (source.crypt_options & CONNECT_REQUESTED != 0 || mode != ObfuscationMode::Supported);
        let request = DirectCallbackRequest {
            tcp_port: self.calling.tcp_port,
            user_hash: self.calling.user_hash,
            connect_options: kad_options(mode),
        }
        .encode();
        let key = obfuscate.then_some(user_hash);
        self.socket.send_client(ip, route.udp_port, &request, key, self.node.public_ip());
        true
    }
}

/// Starts keyword searches on the node, remembering whose they are.
pub(super) struct KadSearching<'a> {
    node: &'a mut KadNode,
    searches: &'a mut HashMap<SearchHandle, u64>,
}

impl KadSearches for KadSearching<'_> {
    fn search(&mut self, search: u64, keyword: &str, expression: &SearchExpr) -> bool {
        let Some(handle) = self.node.search_keyword(keyword, Some(expression.clone())) else {
            return false;
        };
        self.searches.insert(handle, search);
        true
    }
}

impl Network {
    /// Starts Kad unless it runs, on the UDP port of the preferences; a port another program holds is tried again
    /// later.
    pub(super) fn start_kad(&mut self) {
        if self.kad.running.is_some() {
            return;
        }
        let local = match &self.binding {
            Binding::Missing(_) => {
                self.user_log(LogLevel::Warning, "Kad waits for the network interface to come back".to_owned());
                return;
            }
            Binding::Bound(address) => Some(*address),
            Binding::Any => None,
        };
        let preferences = self.preferences();
        let config = &self.shared.dirs.config;
        let identity = match KadIdentity::load_or_create(config, &mut self.random) {
            Ok(identity) => identity,
            Err(e) => {
                tracing::warn!("Cannot start Kad: {e}");
                return;
            }
        };
        let keys = Keys { user_hash: self.shared.user_hash, identity, blocked: Arc::new(self.ip_blocks()) };
        let socket = match KadSocket::open(local, preferences.udp_port, keys, self.kad.incoming.clone()) {
            Ok(socket) => socket,
            Err(e) if e.kind() == ErrorKind::AddrInUse => {
                // Told once; the port is tried again until it is free.
                if self.kad.port_taken != preferences.udp_port {
                    self.kad.port_taken = preferences.udp_port;
                    self.user_log(
                        LogLevel::Warning,
                        format!(
                            "Cannot start Kad on UDP port {}: another program uses it. njMule tries again every \
                             {PORT_RETRY_SECONDS} seconds",
                            preferences.udp_port
                        ),
                    );
                }
                return;
            }
            Err(e) => {
                self.kad.port_taken = 0;
                self.user_log(LogLevel::Warning, format!("Cannot start Kad on UDP port {}: {e}", preferences.udp_port));
                return;
            }
        };
        let relay =
            RelayKeeper::start(
                "Kad UDP",
                socket.router(),
                self.shared.routes.proxy(),
                local,
                |level, text| match level {
                    Level::Info => tracing::info!("{text}"),
                    Level::Warning => tracing::warn!("{text}"),
                },
            );
        let watched = self.shared.preferences.subscribe();
        let lan = self.shared.preferences.subscribe();
        let routes = self.shared.routes.clone();
        let settings = Settings {
            tcp_port: self.shared.listen_port(),
            user_hash: self.shared.user_hash,
            options: Arc::new(move || kad_options(watched.borrow().obfuscation)),
            trusted: Arc::new(move |ip| ipv4::is_lan(ip) && !lan.borrow().filter_lan_addresses),
            blocked: Arc::new(self.ip_blocks()),
            udp_port: socket.port(),
            ipv6: Arc::new(move || crate::net::public_ipv6::find(&routes)),
            comments: Arc::new(|_| None),
        };
        let host = NetworkHost {
            shared: Arc::clone(&self.shared),
            ticks: self.ticks.clone(),
            known: Arc::clone(&self.kad.known),
        };
        let mut node = KadNode::new(identity, Box::new(socket.transport()), Box::new(host), settings, config, {
            Box::new(Instant::now)
        });
        node.use_buddies(Box::new(SharedBuddies(Arc::clone(&self.kad.buddies))));
        node.load_nodes();
        node.load_index();
        tracing::info!("Kad id {} on UDP port {}", identity.id().to_hex(), socket.port());
        if self.kad.port_taken != 0 {
            self.user_log(LogLevel::Success, format!("Kad started: UDP port {} is free again", preferences.udp_port));
            self.kad.port_taken = 0;
        }
        self.kad.running = Some(Running { socket, node, publisher: KadPublisher::new(), relay });
        self.kad.starts = self.kad.starts.wrapping_add(1);
    }

    /// What Kad tells of its checks of our ports; none while it does not run.
    pub(super) fn kad_checks(&self) -> Option<KadChecks> {
        let running = self.kad.running.as_ref()?;
        Some(KadChecks {
            node: self.kad.starts,
            rounds: running.node.firewall_rounds(),
            tcp: running.node.tcp_check_open(),
            udp: running.node.udp_test_open(),
        })
    }

    /// Drops the contacts the IP filter now refuses; how many.
    pub(super) fn kad_filters_changed(&mut self) -> usize {
        self.kad.running.as_mut().map_or(0, |running| running.node.filters_changed())
    }

    fn ip_blocks(&self) -> impl Fn(u32) -> bool + Send + Sync + 'static {
        super::filter::ip_test(&self.ip_filter)
    }

    /// The UDP port of Kad; 0 while it does not run.
    pub(super) fn kad_udp_port(&self) -> u16 {
        self.kad.running.as_ref().map_or(0, |running| running.socket.port())
    }

    /// Stops Kad, saving its contacts and index; the port is free again once the socket's task has ended.
    pub(super) fn stop_kad(&mut self) {
        self.kad.searches.clear();
        let Some(mut running) = self.kad.running.take() else {
            return;
        };
        self.buddies().release();
        self.kad.notes.kad_stopped(&self.comment_filter());
        running.node.close();
        running.relay.close();
        tokio::spawn(running.socket.close());
    }

    /// Stops Kad for a new network interface and hands its socket over, for the rebinding to wait until it left its
    /// port; Kad starts again on the new interface.
    pub(super) fn take_kad_socket(&mut self) -> Option<KadSocket> {
        self.kad.searches.clear();
        let mut running = self.kad.running.take()?;
        self.buddies().release();
        self.kad.notes.kad_stopped(&self.comment_filter());
        running.node.close();
        running.relay.close();
        self.kad.port_taken = 0;
        Some(running.socket)
    }

    /// Joins Kad again after a stop by hand, or looks for contacts at once when it runs.
    pub(super) fn connect_kad(&mut self) {
        self.kad.stopped_by_hand = false;
        match &mut self.kad.running {
            Some(running) => running.node.look_for_contacts(),
            None => self.start_kad(),
        }
    }

    /// Stops Kad until it is connected again or njMule starts again.
    pub(super) fn disconnect_kad(&mut self) {
        self.kad.stopped_by_hand = true;
        self.kad.port_taken = 0;
        if self.kad.running.is_some() {
            self.stop_kad();
            self.user_log(LogLevel::Info, "Kad stopped".to_owned());
        }
    }

    /// Whether Kad runs and checks again whether other nodes can reach us.
    pub(super) fn recheck_kad(&mut self) -> bool {
        let Some(running) = &mut self.kad.running else {
            return false;
        };
        running.node.recheck_firewall();
        true
    }

    /// Joins Kad through `node`; false while Kad does not run.
    pub(super) fn bootstrap_kad(&mut self, node: SocketAddrV4) -> bool {
        let Some(running) = &mut self.kad.running else {
            return false;
        };
        running.node.bootstrap(u32::from(*node.ip()), node.port());
        true
    }

    /// Adds Kad contacts to the running node and to `nodes.dat`; how many were new to the node, or to the file while Kad
    /// is off. The file gets them either way: the node saves only contacts it heard from.
    pub(super) fn add_kad_contacts(&mut self, contents: &nodes_dat::Contents) -> io::Result<usize> {
        let file = self.shared.dirs.config.join(kad_node::NODES_FILE);
        let saved = match std::fs::read(&file) {
            Ok(data) => nodes_dat::read(&data).map(|read| read.contacts).unwrap_or_default(),
            Err(e) if e.kind() == ErrorKind::NotFound => Vec::new(),
            Err(e) => return Err(e),
        };
        let known: HashSet<_> = saved.iter().map(|contact| contact.id).collect();
        let fresh: Vec<_> = contents.contacts.iter().filter(|contact| !known.contains(&contact.id)).cloned().collect();
        let added_to_file = fresh.len();
        if added_to_file > 0 {
            let mut all = saved;
            all.extend(fresh);
            let data = nodes_dat::write(&all).map_err(io::Error::other)?;
            crate::persist::atomic_files::write(&file, &data)?;
        }
        Ok(match &mut self.kad.running {
            Some(running) => {
                let added = running.node.add_contacts(contents);
                running.node.look_for_contacts();
                added
            }
            None => added_to_file,
        })
    }

    pub(super) fn kad_info(&self) -> Option<KadInfo> {
        let running = self.kad.running.as_ref()?;
        let node = &running.node;
        let public_ip = node.public_ip();
        Some(KadInfo {
            connected: node.is_connected(),
            contacts: node.contacts(),
            verified_contacts: node.verified_contacts(),
            firewalled: node.is_firewalled(),
            id: node.id().to_hex(),
            public_ip: if public_ip == 0 { String::new() } else { Ipv4Addr::from(public_ip).to_string() },
            udp_port: running.socket.port(),
            udp_firewalled: node.is_firewalled_udp(),
            udp_verified: node.is_udp_verified(),
            buddy: match node.buddy_state() {
                BuddyState::None => Buddy::None,
                BuddyState::Connecting => Buddy::Connecting,
                BuddyState::Connected => Buddy::Connected,
                BuddyState::Serving => Buddy::Serving,
            },
            users: node.estimated_nodes(),
            files: node.estimated_files(self.server_files_per_user()),
        })
    }

    // Files an average user of the servers shares, from the servers that tell users and files and take many files; 0
    // while they hold too few users, and made up a little for the servers that limit files.
    fn server_files_per_user(&self) -> u64 {
        let (users, files) = self
            .servers
            .all()
            .iter()
            .filter_map(|server| match (server.users(), server.files(), server.soft_files()) {
                (Some(users), Some(files), Some(soft)) if users > 0 && files > 0 && soft > 1000 => Some((users, files)),
                _ => None,
            })
            .fold((0u64, 0u64), |(users, files), (more_users, more_files)| {
                (users.saturating_add(more_users), files.saturating_add(more_files))
            });
        if users > 500_000 { files.checked_div(users).unwrap_or(0).saturating_add(50) } else { 0 }
    }

    pub(super) fn kad_contacts(&self) -> Vec<KadContactInfo> {
        self.kad.running.as_ref().map(|running| running.node.contact_infos()).unwrap_or_default()
    }

    pub(super) fn kad_searches(&self) -> Vec<KadSearchInfo> {
        self.kad.running.as_ref().map(|running| running.node.lookup_infos()).unwrap_or_default()
    }

    /// A packet the client UDP socket received.
    pub(super) fn kad_incoming(&mut self, incoming: Incoming) {
        match incoming {
            Incoming::Kad { ip, port, packet, receiver_key, sender_key } => {
                if let Some(running) = &mut self.kad.running {
                    running.node.received(ip, port, &packet, receiver_key, sender_key);
                }
                // A node may have offered to be our buddy.
                self.buddy_actions();
            }
            Incoming::Client { ip, port, packet } => {
                self.client_datagram(SocketAddr::from((Ipv4Addr::from(ip), port)), &packet);
            }
            Incoming::ClientOverIpv6 { from, packet } => self.client_datagram(from, &packet),
        }
    }

    // A client datagram on Kad's port: a source answers our re-ask, a client waiting in our queue asks again for its
    // place, or one asks again for its place in the queue of the firewalled node we serve as its buddy.
    fn client_datagram(&mut self, from: SocketAddr, packet: &Packet) {
        match packet.opcode() {
            client_udp::REASKFILEPING => self.answer_reask(from, packet),
            client_udp::REASKACK | client_udp::QUEUEFULL | client_udp::FILENOTFOUND => {
                let ours = self.shared.our_hello();
                self.downloads.reask_answered(from, packet, &ours);
                self.after_downloads();
            }
            client_udp::REASKCALLBACKUDP => {
                if let SocketAddr::V4(v4) = from {
                    self.buddies().relay_reask(u32::from(*v4.ip()), v4.port(), packet);
                }
            }
            _ => tracing::trace!("Client datagram {:#04x} from {from}", packet.opcode()),
        }
    }

    // The re-ask of a client waiting in our queue, answered over Kad's port, obfuscated for it when we know its user
    // hash, plain over IPv6.
    fn answer_reask(&mut self, from: SocketAddr, packet: &Packet) {
        let (files, downloads) = (&self.shared_files, &self.downloads);
        let find = |hash: &[u8; 16]| super::uploadable(files, downloads, hash);
        let Some(answer) = self.uploads.reasked(from.ip(), from.port(), packet, &find, Instant::now()) else {
            return;
        };
        let our_ip = self.our_public_ip();
        let Some(running) = &self.kad.running else {
            return;
        };
        match from {
            SocketAddr::V4(v4) => {
                let ip = u32::from(*v4.ip());
                running.socket.send_client(ip, v4.port(), &answer.packet, answer.obfuscate_for, our_ip);
            }
            SocketAddr::V6(_) => running.socket.send_client_to(from, &answer.packet),
        }
    }

    // Our public IPv4 address, as the server or Kad sees it; 0 while unknown.
    pub(super) fn our_public_ip(&self) -> u32 {
        let server_seen = self.address_seen_by_server();
        if server_seen != 0 {
            return server_seen;
        }
        self.kad.running.as_ref().map_or(0, |running| running.node.public_ip())
    }

    /// Each second: the node and its publishes go on, and a port another program held is tried again now and then.
    pub(super) fn kad_second(&mut self) {
        let known: HashSet<u32> = self
            .peers
            .values()
            .filter_map(|peer| match peer.handle.remote().ip() {
                IpAddr::V4(ip) => Some(u32::from(ip)),
                IpAddr::V6(_) => None,
            })
            .collect();
        *self.kad.known.lock().unwrap_or_else(PoisonError::into_inner) = known;
        let announced = self
            .kad
            .running
            .as_ref()
            .filter(|running| running.node.is_connected())
            .map(|running| (running.node.announced_udp_port(), running.node.supports_direct_callback()));
        let (udp_port, direct_callback) = announced.unwrap_or((0, false));
        self.shared.announce_kad(udp_port, direct_callback);
        if let Some(running) = &mut self.kad.running {
            running.node.tick();
            let shared: Vec<kad_publisher::Shared> = self
                .shared_files
                .files()
                .map(|file| kad_publisher::Shared {
                    hash: file.hash,
                    name: file.name.clone(),
                    size: file.size,
                    complete: true,
                })
                .collect();
            running.publisher.tick(&mut running.node, &shared, &|_| None, Instant::now());
            let events = running.node.take_events();
            self.kad_events(events);
        } else if self.kad.port_taken != 0 {
            self.kad.since_port_retry = self.kad.since_port_retry.saturating_add(1);
            if self.kad.since_port_retry >= PORT_RETRY_SECONDS {
                self.kad.since_port_retry = 0;
                self.start_kad();
            }
        }
        if self.kad.running.is_some() {
            self.buddies().tick();
            self.buddy_actions();
            self.look_for_notes();
        }
        // Firewalled with a buddy: clients that know us reach us through it.
        let firewalled = self.kad.running.as_ref().is_some_and(|running| {
            running.node.is_connected() && running.node.is_firewalled() && running.node.buddy_ip() != 0
        });
        let (ip, port) = if firewalled {
            let buddy = self.buddies();
            (buddy.buddy_ip(), buddy.buddy_udp_port())
        } else {
            (0, 0)
        };
        self.shared.announce_buddy(ip, port);
    }

    /// The notes Kad gave on `file` so far, without those the comment filter leaves out.
    pub(super) fn kad_notes(&self, file: &[u8; 16]) -> Vec<KadNote> {
        self.kad.notes.notes(file, &self.comment_filter())
    }

    /// Searches Kad for the notes on `file`, which we download or share, and hands over where the notes then known
    /// come once the search ends, or why it could not start; none for another file.
    pub(super) fn search_kad_notes(&mut self, file: [u8; 16]) -> Option<NotesOutcome> {
        let size = self
            .downloads
            .find(&file)
            .map(|d| d.file.size())
            .or_else(|| self.shared_files.get(&file).map(|f| f.size))?;
        let (done, outcome) = oneshot::channel();
        let KadSide { running, notes, .. } = &mut self.kad;
        let finder = running
            .as_mut()
            .filter(|running| running.node.is_connected())
            .map(|running| &mut running.node as &mut dyn kad_notes::Finder);
        notes.search(file, size, finder, done);
        Some(outcome)
    }

    // Now and then Kad is asked what others said of a running download not asked of yet in this run.
    fn look_for_notes(&mut self) {
        let downloads: Vec<kad_notes::Download> = self
            .downloads
            .downloads()
            .iter()
            .filter(|d| d.status == DownloadStatus::Downloading)
            .map(|d| kad_notes::Download { hash: d.hash, size: d.size })
            .collect();
        let KadSide { running, notes, .. } = &mut self.kad;
        let finder = running
            .as_mut()
            .filter(|running| running.node.is_connected())
            .map(|running| &mut running.node as &mut dyn kad_notes::Finder);
        notes.tick(Instant::now(), finder, &downloads);
    }

    fn comment_filter(&self) -> CommentFilter {
        CommentFilter::of(&self.shared.preferences.borrow().comment_filter)
    }

    fn buddies(&self) -> MutexGuard<'_, BuddyLink> {
        self.kad.buddies.lock().unwrap_or_else(PoisonError::into_inner)
    }

    pub(super) fn has_buddy_link(&self, link: LinkId) -> bool {
        self.buddies().has_link(link)
    }

    /// The client on `link`, which we connected to, answered our hello.
    pub(super) fn buddy_greeted(&mut self, link: LinkId, hello: &Hello) {
        self.buddies().greeted(link, hello);
        self.buddy_actions();
    }

    /// A client that connected to us greeted us: the firewalled node we agreed to serve becomes our buddy.
    pub(super) fn buddy_adopt(&mut self, link: &LinkHandle, hello: &Hello) {
        self.buddies().adopt(link, hello);
    }

    pub(super) fn buddy_packet(&mut self, link: LinkId, packet: &Packet) {
        self.buddies().received(link, packet);
        self.buddy_actions();
    }

    pub(super) fn buddy_closed(&mut self, link: LinkId, reason: Option<&str>) {
        self.buddies().closed(link, reason);
        self.buddy_actions();
    }

    // Our hello goes first on the connection to a node that offered to be our buddy, which answers it.
    pub(super) fn buddy_dialed(&mut self, attempt: u64, result: io::Result<tcp_connector::Connection>) {
        match result {
            Ok(connection) => {
                let handle = self.new_link(connection, None);
                handle.set_timeout(peer_greetings::HELLO_TIMEOUT);
                let wanted = self.buddies().opened(attempt, &handle);
                match self.shared.our_hello().to_peer_at(&handle.remote().ip()).encode(false) {
                    Ok(hello) if wanted => {
                        handle.send(hello);
                    }
                    Ok(_) => handle.close(),
                    Err(e) => {
                        tracing::warn!("Cannot greet {}: {e}", handle.remote());
                        handle.close();
                    }
                }
            }
            Err(e) => self.buddies().failed(attempt, &e.to_string()),
        }
        self.buddy_actions();
    }

    // Acts on what the buddy link asked for or passed on.
    fn buddy_actions(&mut self) {
        let actions = self.buddies().take_actions();
        for action in actions {
            match action {
                BuddyAction::Connect { attempt, address, user_hash, connect_options } => {
                    self.dial_buddy(attempt, address, user_hash, connect_options);
                }
                BuddyAction::CallBack(callback) => self.relayed_callback(&callback),
                BuddyAction::AnswerReask(reask) => self.relayed_reask(&reask),
                BuddyAction::Rendezvous { from, .. } => {
                    tracing::debug!("Ignoring a rendezvous relayed from {from}: no traversal of routers");
                }
            }
        }
    }

    fn dial_buddy(&mut self, attempt: u64, address: SocketAddrV4, user_hash: [u8; 16], connect_options: u8) {
        let obfuscation = self.shared.preferences.borrow().obfuscation;
        let obfuscate_for =
            (connect_options & 0x01 != 0 && obfuscation != ObfuscationMode::Disabled).then_some(user_hash);
        let events = self.peer_events.clone();
        let routes = self.shared.routes.clone();
        tokio::spawn(async move {
            let mut random = StdRng::from_rng(&mut rand::rng());
            let timeout = peer_greetings::HELLO_TIMEOUT;
            let connection =
                tcp_connector::connect_to_client(address.into(), obfuscate_for, timeout, &routes, &mut random).await;
            let _ = events.send(PeerEvent::BuddyDialed(attempt, Box::new(connection)));
        });
    }

    // Our buddy relays a client's callback request: for a file we share or download, we connect to it.
    fn relayed_callback(&mut self, callback: &Callback) {
        let file = callback.file.to_big_endian();
        let ours = self.kad.running.as_ref().is_some_and(|running| running.node.is_our_buddy_id(callback.buddy_id));
        if !ours || (self.shared_files.get(&file).is_none() && self.downloads.find(&file).is_none()) {
            tracing::debug!("Ignoring a callback relayed for {}", callback.file.to_hex());
            return;
        }
        let address = SocketAddrV4::new(Ipv4Addr::from(callback.requester_ip), callback.requester_port);
        self.call_client_back(address, None, "through our buddy");
    }

    // Our buddy relays the re-ask of a client: answered straight to the client, as one it sent us.
    fn relayed_reask(&mut self, reask: &Relayed) {
        let from = SocketAddr::from((Ipv4Addr::from(reask.requester_ip), reask.requester_port));
        self.answer_reask(from, &reask.ping);
    }

    // What Kad's searches found goes to the searches still open, the sources to the downloads, and the notes to those
    // kept of our files.
    fn kad_events(&mut self, events: Vec<KadEvent>) {
        for event in events {
            match event {
                KadEvent::Files { search, results } => {
                    let Some(&number) = self.kad.searches.get(&search) else {
                        continue;
                    };
                    let found = results.into_iter().map(kad_node::FileResult::into_search_result).collect();
                    let preferences = self.preferences();
                    let mut effects = Vec::new();
                    self.searches.kad_results(
                        number,
                        found,
                        &mut Rater::of(&mut self.spam, &preferences),
                        &mut effects,
                    );
                    self.search_effects(effects);
                }
                KadEvent::Sources { file, sources } => self.kad_sources_found(&file, &sources),
                KadEvent::Notes { file, notes } => self.kad.notes.found(&file, notes),
                KadEvent::NotesEnded { file } => {
                    let filter = self.comment_filter();
                    self.kad.notes.ended(&file, &filter);
                }
            }
        }
    }

    // A firewalled source can only connect to us: useless while we cannot be called back, by the server with our
    // HighID, or by Kad that finds us reachable.
    fn kad_sources_found(&mut self, file: &[u8; 16], found: &[kad_node::Source]) {
        let Some(running) = &self.kad.running else {
            return;
        };
        let high_id = self.shared.context.lock().is_ok_and(|context| context.client_id >= LOW_ID_LIMIT);
        let callable = !running.node.is_firewalled() || high_id;
        let public_ip = if running.node.is_firewalled() { 0 } else { running.node.public_ip() };
        let kept: Vec<KadSource> = found
            .iter()
            .filter(|source| !source.is_firewalled() || callable)
            .map(|source| KadSource {
                address: SocketAddrV4::new(Ipv4Addr::from(source.ip), source.tcp_port),
                user_hash: Some(source.user_hash),
                crypt_options: source.connect_options,
                callback: callback_of(source),
                ipv6: source.ipv6,
            })
            .collect();
        let (settings, ours) = (self.settings(), self.shared.our_hello());
        self.downloads.found_on_kad(file, &kept, public_ip, &settings, &ours);
        self.after_downloads();
    }

    /// The search manager ended a search over Kad: so does the node.
    pub(super) fn stop_kad_search(&mut self, number: u64) {
        let ended: Vec<SearchHandle> =
            self.kad.searches.iter().filter(|&(_, &search)| search == number).map(|(&handle, _)| handle).collect();
        for handle in ended {
            self.kad.searches.remove(&handle);
            if let Some(running) = &mut self.kad.running {
                running.node.end_search(handle);
            }
        }
    }

    /// A UDP test helper's link ended, after asking it for its datagram or not.
    pub(super) fn kad_udp_check_ended(&mut self, ip: u32, asked: bool) {
        if let Some(running) = &mut self.kad.running {
            running.node.udp_check_ended(ip, asked);
        }
    }

    /// What clients told our Kad node over their upload connections: that they reached our TCP port, or that they
    /// want the datagram of their UDP firewall test.
    pub(super) fn kad_requests(&mut self, requests: Vec<KadRequest>) {
        let Some(running) = &mut self.kad.running else {
            return;
        };
        for request in requests {
            match request {
                KadRequest::Reached(ip) => running.node.reachable_confirmed(ip),
                KadRequest::UdpCheck { ip, request, known } => running.node.udp_check_requested(
                    ip,
                    request.internal_port,
                    request.external_port,
                    request.sender_key,
                    known,
                ),
            }
        }
    }

    /// A client greeted us: one that runs Kad may give us contacts while we have none.
    pub(super) fn kad_met(&mut self, hello: &Hello, address: IpAddr) {
        if let (Some(running), IpAddr::V4(ip)) = (&mut self.kad.running, address) {
            let version = u8::try_from(hello.kad_version()).unwrap_or(u8::MAX);
            running.node.bootstrap_via(u32::from(ip), hello.kad_port(), version);
        }
    }

    /// The preferences changed: turning Kad off stops it, on starts it unless it was stopped by hand, and a new UDP
    /// port moves it.
    pub(super) fn kad_preferences(&mut self, was_enabled: bool, was_port: u16) {
        let now = self.preferences();
        if was_enabled && !now.kad_enabled {
            self.stop_kad();
            self.kad.port_taken = 0;
            return;
        }
        if !now.kad_enabled || self.kad.stopped_by_hand {
            return;
        }
        if self.kad.running.is_none() {
            self.start_kad();
        } else if was_port != now.udp_port {
            self.restart_kad();
        }
    }

    /// Stops Kad and starts it again once its socket has left the port, as when the port or the interface changes.
    pub(super) fn restart_kad(&mut self) {
        self.kad.searches.clear();
        let Some(mut running) = self.kad.running.take() else {
            self.start_kad();
            return;
        };
        running.node.close();
        running.relay.close();
        let ticks = self.ticks.clone();
        tokio::spawn(async move {
            running.socket.close().await;
            let _ = ticks.send(Tick::KadStart);
        });
    }

    /// Kad starts again after a restart, unless it was turned off or stopped by hand meanwhile.
    pub(super) fn kad_start_again(&mut self) {
        if self.preferences().kad_enabled && !self.kad.stopped_by_hand {
            self.start_kad();
        }
    }
}

// How a firewalled source is asked to call us: through its buddy, or straight to its Kad port.
fn callback_of(source: &kad_node::Source) -> Option<KadCallback> {
    match source.buddy {
        Some(buddy) => Some(KadCallback {
            buddy_ip: buddy.ip,
            buddy_port: buddy.udp_port,
            buddy_id: Some(buddy.id.to_big_endian()),
            udp_port: source.udp_port,
        }),
        None => source.direct_callback.then_some(KadCallback {
            buddy_ip: 0,
            buddy_port: 0,
            buddy_id: None,
            udp_port: source.udp_port,
        }),
    }
}

// What the node's connection options say of our obfuscation.
fn kad_options(mode: ObfuscationMode) -> u8 {
    u8::try_from(crypt_options(mode) >> 7).unwrap_or(0)
}

/// What the Kad node needs of the rest of the client: connections to other clients, over TCP.
struct NetworkHost {
    shared: Arc<Shared>,
    ticks: mpsc::UnboundedSender<Tick>,
    known: Arc<Mutex<HashSet<u32>>>,
}

impl Host for NetworkHost {
    fn check_reachable(&mut self, ip: u32, port: u16, user_hash: [u8; 16], connect_options: u8) {
        let address = SocketAddr::from((Ipv4Addr::from(ip), port));
        let obfuscation = self.shared.preferences.borrow().obfuscation;
        let obfuscate_for =
            (connect_options & 0x01 != 0 && obfuscation != ObfuscationMode::Disabled).then_some(user_hash);
        let hello = self.shared.our_hello().to_peer_at(&address.ip());
        let routes = self.shared.routes.clone();
        tokio::spawn(async move {
            if let Err(e) = reach(address, obfuscate_for, &hello, &routes).await {
                tracing::debug!("Kad check of {address} failed: {e}");
            }
        });
    }

    fn check_udp(&mut self, ip: u32, tcp_port: u16, internal_port: u16, external_port: u16, sender_key: u32) {
        let address = SocketAddr::from((Ipv4Addr::from(ip), tcp_port));
        let request = UdpCheckRequest { internal_port, external_port, sender_key };
        let hello = self.shared.our_hello().to_peer_at(&address.ip());
        let routes = self.shared.routes.clone();
        let ticks = self.ticks.clone();
        tokio::spawn(async move {
            let asked = ask_for_datagram(address, &hello, &request, &routes).await.unwrap_or_else(|e| {
                tracing::debug!("UDP test helper {address} unreachable: {e}");
                false
            });
            let _ = ticks.send(Tick::KadUdpCheckEnded(ip, asked));
        });
    }

    fn is_known_client(&self, ip: u32) -> bool {
        self.known.lock().unwrap_or_else(PoisonError::into_inner).contains(&ip)
    }

    fn has_high_id(&self) -> bool {
        self.shared.context.lock().is_ok_and(|context| context.client_id >= LOW_ID_LIMIT)
    }
}

// Connects to a node that asked to check our TCP port and, after the hellos, tells it that we could; the link stays
// open while the node may still be reading.
async fn reach(address: SocketAddr, obfuscate_for: Option<[u8; 16]>, hello: &Hello, routes: &Routes) -> io::Result<()> {
    let mut random = StdRng::from_rng(&mut rand::rng());
    let hello = hello.encode(false).map_err(io::Error::other)?;
    let timeout = peer_greetings::HELLO_TIMEOUT;
    let connection = tcp_connector::connect_to_client(address, obfuscate_for, timeout, routes, &mut random).await?;
    let (mut reader, mut writer) = (connection.reader, connection.writer);
    writer.write_packet(&hello).await?;
    let answered = tokio::time::timeout(timeout, async {
        while let Some(packet) = reader.read_packet(&frame_limits::client_handshake).await? {
            if packet.protocol() == protocol::EDONKEY && packet.opcode() == client::HELLOANSWER {
                writer.write_packet(&Packet::empty(protocol::EMULE, extended::KAD_FWTCPCHECK_ACK)).await?;
                return Ok(true);
            }
        }
        Ok::<_, io::Error>(false)
    })
    .await;
    if matches!(answered, Ok(Ok(true))) {
        tokio::time::sleep(timeout).await;
    }
    writer.shutdown().await
}

// Asks a UDP test helper, once it greeted us as a Kad node able to help, for its datagram, then keeps the link open
// while the datagram may come; whether it was asked.
async fn ask_for_datagram(
    address: SocketAddr,
    hello: &Hello,
    request: &UdpCheckRequest,
    routes: &Routes,
) -> io::Result<bool> {
    let mut random = StdRng::from_rng(&mut rand::rng());
    let hello = hello.encode(false).map_err(io::Error::other)?;
    let timeout = peer_greetings::HELLO_TIMEOUT;
    let connection = tcp_connector::connect_to_client(address, None, timeout, routes, &mut random).await?;
    let (mut reader, mut writer) = (connection.reader, connection.writer);
    writer.write_packet(&hello).await?;
    let answer = tokio::time::timeout(timeout, async {
        loop {
            let Some(packet) = reader.read_packet(&frame_limits::client_handshake).await? else {
                return Ok(None);
            };
            if packet.protocol() == protocol::EDONKEY && packet.opcode() == client::HELLOANSWER {
                return Hello::decode(&packet).map(Some).map_err(io::Error::other);
            }
        }
    })
    .await
    .map_err(|_| io::Error::new(ErrorKind::TimedOut, "no hello answer"))??;
    let Some(peer) = answer else {
        return Ok(false);
    };
    if peer.kad_version() < UDP_CHECK_VERSION || peer.kad_port() == 0 {
        return Ok(false);
    }
    writer.write_packet(&request.encode()).await?;
    // The link stays open while the datagram may come; whatever the helper says meanwhile is not needed.
    let _ = tokio::time::timeout(UDP_CHECK_WAIT, async {
        while let Ok(Some(_)) = reader.read_packet(&frame_limits::client).await {}
    })
    .await;
    Ok(true)
}

/// The buddy link as the Kad node holds it, shared with the network.
struct SharedBuddies(Arc<Mutex<BuddyLink>>);

impl SharedBuddies {
    fn link(&self) -> MutexGuard<'_, BuddyLink> {
        self.0.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

impl Buddies for SharedBuddies {
    fn state(&self) -> BuddyState {
        self.link().state()
    }

    fn buddy_ip(&self) -> u32 {
        self.link().buddy_ip()
    }

    fn buddy_udp_port(&self) -> u16 {
        self.link().buddy_udp_port()
    }

    fn offered(&mut self, ip: u32, tcp_port: u16, udp_port: u16, user_hash: [u8; 16], connect_options: u8) {
        self.link().offered(ip, tcp_port, udp_port, user_hash, connect_options);
    }

    fn asked(&mut self, ip: u32, tcp_port: u16, udp_port: u16, user_hash: [u8; 16], buddy_id: UInt128) -> bool {
        self.link().asked(ip, tcp_port, udp_port, user_hash, buddy_id)
    }

    fn relay(&mut self, buddy_id: UInt128, file: UInt128, requester_ip: u32, requester_port: u16) -> bool {
        self.link().relay(buddy_id, file, requester_ip, requester_port)
    }

    fn release(&mut self) {
        self.link().release();
    }
}
