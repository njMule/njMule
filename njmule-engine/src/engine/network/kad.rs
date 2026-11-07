// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Kad in the network: the client UDP socket, which Kad shares, and the node on it, started with the preferences or by
//! hand and ticked each second, and the host the node asks to connect to other clients over TCP.

use std::collections::{HashMap, HashSet};
use std::io::{self, ErrorKind};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::sync::mpsc;

use super::{Network, Rater, Tick};
use crate::api::kad_contact_info::KadContactInfo;
use crate::api::kad_info::{Buddy, KadInfo};
use crate::api::kad_search_info::KadSearchInfo;
use crate::config::bind_interface::Binding;
use crate::config::obfuscation_mode::ObfuscationMode;
use crate::engine::{Shared, crypt_options};
use crate::kad::kad_identity::KadIdentity;
use crate::kad::kad_node::{self, BuddyState, Host, KadEvent, KadNode, SearchHandle, Settings};
use crate::kad::kad_publisher::{self, KadPublisher};
use crate::kad::kad_socket::{Incoming, KadSocket, Keys};
use crate::net::relay_keeper::{Level, RelayKeeper};
use crate::net::routes::Routes;
use crate::net::tcp_connector;
use crate::peer::peer_greetings;
use crate::persist::format::nodes_dat;
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::ed2k::low_id_messages::UdpCheckRequest;
use crate::protocol::ed2k::server_messages::LOW_ID_LIMIT;
use crate::protocol::packet::opcodes::{client, extended};
use crate::protocol::packet::{Packet, frame_limits, protocol};
use crate::search::search_expr::SearchExpr;
use crate::search::search_manager::KadSearches;
use crate::util::ipv4;

/// How often a Kad port another program holds is tried again.
pub(super) const PORT_RETRY_SECONDS: u32 = 10;
// The datagram of a UDP test helper comes within this time, or the test counts it as not come.
const UDP_CHECK_WAIT: Duration = peer_greetings::IDLE_TIMEOUT;
// Kad nodes before this version do not know the UDP test.
const UDP_CHECK_VERSION: u32 = 6;

/// The node on its socket, and what keeps them going.
struct Running {
    socket: KadSocket,
    node: KadNode,
    publisher: KadPublisher,
    relay: RelayKeeper,
}

/// Kad as the network holds it: running or not, and why not.
pub(super) struct KadSide {
    running: Option<Running>,
    // Disconnected by the user: new ports and interfaces do not start it again.
    stopped_by_hand: bool,
    // The UDP port another program held when Kad started, tried again until it is free; 0 when none.
    port_taken: u16,
    since_port_retry: u32,
    incoming: mpsc::UnboundedSender<Incoming>,
    // The clients we deal with, which cannot be UDP test helpers.
    known: Arc<Mutex<HashSet<u32>>>,
    // The keyword searches Kad runs, by the number the search manager gave them.
    searches: HashMap<SearchHandle, u64>,
}

impl KadSide {
    pub(super) fn new(incoming: mpsc::UnboundedSender<Incoming>) -> Self {
        Self {
            running: None,
            stopped_by_hand: false,
            port_taken: 0,
            since_port_retry: 0,
            incoming,
            known: Arc::default(),
            searches: HashMap::new(),
        }
    }

    /// What starts keyword searches on Kad, while it runs.
    pub(super) fn searching(&mut self) -> Option<KadSearching<'_>> {
        let running = self.running.as_mut()?;
        Some(KadSearching { node: &mut running.node, searches: &mut self.searches })
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
                tracing::warn!("Kad waits for the network interface to come back");
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
        let keys = Keys { user_hash: self.shared.user_hash, identity, blocked: Arc::new(|_| false) };
        let socket = match KadSocket::open(local, preferences.udp_port, keys, self.kad.incoming.clone()) {
            Ok(socket) => socket,
            Err(e) if e.kind() == ErrorKind::AddrInUse => {
                // Told once; the port is tried again until it is free.
                if self.kad.port_taken != preferences.udp_port {
                    self.kad.port_taken = preferences.udp_port;
                    tracing::warn!(
                        "Cannot start Kad on UDP port {}: another program uses it. njMule tries again every \
                         {PORT_RETRY_SECONDS} seconds",
                        preferences.udp_port
                    );
                }
                return;
            }
            Err(e) => {
                self.kad.port_taken = 0;
                tracing::warn!("Cannot start Kad on UDP port {}: {e}", preferences.udp_port);
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
            blocked: Arc::new(|_| false),
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
        node.load_nodes();
        node.load_index();
        tracing::info!("Kad id {} on UDP port {}", identity.id().to_hex(), socket.port());
        if self.kad.port_taken != 0 {
            tracing::info!("Kad started: UDP port {} is free again", preferences.udp_port);
            self.kad.port_taken = 0;
        }
        self.kad.running = Some(Running { socket, node, publisher: KadPublisher::new(), relay });
    }

    /// Stops Kad, saving its contacts and index; the port is free again once the socket's task has ended.
    pub(super) fn stop_kad(&mut self) {
        self.kad.searches.clear();
        let Some(mut running) = self.kad.running.take() else {
            return;
        };
        running.node.close();
        running.relay.close();
        tokio::spawn(running.socket.close());
    }

    /// Stops Kad for a new network interface and hands its socket over, for the rebinding to wait until it left its
    /// port; Kad starts again on the new interface.
    pub(super) fn take_kad_socket(&mut self) -> Option<KadSocket> {
        self.kad.searches.clear();
        let mut running = self.kad.running.take()?;
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
            tracing::info!("Kad stopped");
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

    pub(super) fn bootstrap_kad(&mut self, node: SocketAddrV4) {
        if let Some(running) = &mut self.kad.running {
            running.node.bootstrap(u32::from(*node.ip()), node.port());
        }
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
            }
            Incoming::Client { ip, port, packet } => {
                tracing::trace!("Client datagram {:#04x} from {}:{port}", packet.opcode(), Ipv4Addr::from(ip));
            }
            Incoming::ClientOverIpv6 { from, packet } => {
                tracing::trace!("Client datagram {:#04x} from {from}", packet.opcode());
            }
        }
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
    }

    // What Kad's searches found goes to the searches still open; the sources and notes have no taker yet.
    fn kad_events(&mut self, events: Vec<KadEvent>) {
        for event in events {
            if let KadEvent::Files { search, results } = event
                && let Some(&number) = self.kad.searches.get(&search)
            {
                let found = results.into_iter().map(kad_node::FileResult::into_search_result).collect();
                let preferences = self.preferences();
                let mut effects = Vec::new();
                self.searches.kad_results(number, found, &mut Rater::of(&mut self.spam, &preferences), &mut effects);
                self.search_effects(effects);
            }
        }
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

    /// Kad's own packets on a client's connection: the client checked our TCP port and tells so, or asks us for a
    /// datagram to test its UDP port. Whether the packet was one of them.
    pub(super) fn kad_client_packet(&mut self, from: SocketAddr, packet: &Packet) -> bool {
        let IpAddr::V4(ip) = from.ip() else {
            return false;
        };
        if packet.protocol() != protocol::EMULE {
            return false;
        }
        let ip = u32::from(ip);
        match packet.opcode() {
            extended::KAD_FWTCPCHECK_ACK => {
                if let Some(running) = &mut self.kad.running {
                    running.node.reachable_confirmed(ip);
                }
                true
            }
            extended::FWCHECKUDPREQ => {
                match (&mut self.kad.running, UdpCheckRequest::decode(packet)) {
                    // Whether we deal with the client otherwise, a download or an upload, is not told: its test is
                    // taken as worth sending.
                    (Some(running), Ok(request)) => running.node.udp_check_requested(
                        ip,
                        request.internal_port,
                        request.external_port,
                        request.sender_key,
                        false,
                    ),
                    (_, Err(e)) => tracing::debug!("Bad UDP test request from {from}: {e}"),
                    (None, Ok(_)) => {}
                }
                true
            }
            _ => false,
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
