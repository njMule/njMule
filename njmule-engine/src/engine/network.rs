// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The network side of the engine: the server list, the session with a server and the UDP status of the others, the
//! searches, the downloads and the uploads with the connections to other clients. One task owns all of it; the API,
//! the connection tasks, the disk threads and the timers reach it through its queues.

use std::collections::{HashMap, HashSet};
use std::io;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;

use crate::api::download_info::{DownloadInfo, SourceInfo};
use crate::api::ed2k_link::FileLink;
use crate::api::engine_event::EngineEvent;
use crate::api::search_result::SearchResult;
use crate::api::server_info::ServerInfo;
use crate::config::bind_interface::Binding;
use crate::config::obfuscation_mode::ObfuscationMode;
use crate::config::preferences::Preferences;
use crate::download::download_manager::{self, Action, DownloadEvent, DownloadManager, ServerLink};
use crate::download::download_session;
use crate::download::finished_downloads::FinishedDownloads;
use crate::engine::{ServerContext, Shared};
use crate::files::hash::file_hasher::FileHashes;
use crate::files::part_file_storage::DiskDone;
use crate::files::shared_files::{self, Found, SharedFile, SharedFiles};
use crate::net::relay_keeper::{Level, RelayKeeper};
use crate::net::tcp_connection::{FrameReader, FrameWriter};
use crate::net::tcp_connector;
use crate::peer::peer_greetings;
use crate::peer::peer_link::{self, LinkEvent, LinkHandle, LinkId};
use crate::persist::format::server_met::Server;
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::ed2k::server_datagrams;
use crate::protocol::ed2k::server_messages::{self, CallbackRequested, Endpoint};
use crate::protocol::packet::opcodes::{client, extended, server_udp};
use crate::protocol::packet::{Packet, protocol};
use crate::protocol::tag::server_tag;
use crate::search::search_manager::{Effect as SearchEffect, Scope, SearchManager, SpamRater, StartError, Timer};
use crate::search::spam_filter::{self, SpamFilter};
use crate::server::file_publisher::{FilePublisher, Offer};
use crate::server::global_sources::{Asking, GlobalSources};
use crate::server::server_list::{self, Address, ServerList};
use crate::server::server_pinger::{self, Effect, ServerPinger};
use crate::server::server_route::ServerRoute;
use crate::server::server_session::{Context, Notice, Outcome, ServerSession};
use crate::server::server_udp::ServerUdp;
use crate::upload::upload_manager::{Sent, UploadManager};

/// How long changes of the list wait to be saved together.
const SAVE_DELAY: Duration = Duration::from_secs(30);
/// A client called back is not called again before this.
const CALLBACK_REPEAT: Duration = Duration::from_secs(60);
/// Callbacks open at once at most.
const MAX_CALLBACKS_OPEN: usize = 8;
// Crypt options of a callback request: the client asks for or requires obfuscation.
const CRYPT_REQUESTED_OR_REQUIRED: u8 = 0x06;
/// Sources kept per download at most.
const MAX_SOURCES_PER_FILE: usize = 400;

/// What the API asks the network side.
#[derive(Debug)]
pub enum Command {
    ImportServers(Vec<Server>, oneshot::Sender<usize>),
    ConnectServer(Option<Address>, oneshot::Sender<Result<(), ConnectError>>),
    DisconnectServer(oneshot::Sender<()>),
    Servers(oneshot::Sender<Vec<ServerInfo>>),
    SetStaticServer(Address, bool, oneshot::Sender<bool>),
    StartSearch(String, Scope, oneshot::Sender<Result<u64, StartError>>),
    MoreResults(u64, oneshot::Sender<MoreAnswer>),
    SharedFiles(oneshot::Sender<Vec<SharedFile>>),
    AddDownload(Box<FileLink>, Option<bool>, oneshot::Sender<Result<DownloadInfo, String>>),
    Downloads(oneshot::Sender<Vec<DownloadInfo>>),
    Sources(oneshot::Sender<Vec<SourceInfo>>),
    PauseDownload([u8; 16], oneshot::Sender<Option<DownloadInfo>>),
    ResumeDownload([u8; 16], oneshot::Sender<Option<DownloadInfo>>),
    CancelDownload([u8; 16], oneshot::Sender<bool>),
    ClearCompleted(oneshot::Sender<usize>),
    ClearCompletedOne([u8; 16], oneshot::Sender<bool>),
    Stop(oneshot::Sender<()>),
}

/// What a request for more results of a search comes to.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MoreAnswer {
    Asked,
    /// The search cannot get more results now.
    Refused,
    /// No such search.
    Unknown,
}

/// Why a connection cannot be asked for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConnectError {
    /// The address is refused: a LAN address while they are filtered.
    Refused(String),
    /// No server is known to connect to.
    NoServer,
}

// What timers and helper tasks report.
#[derive(Debug)]
enum Tick {
    Ping,
    Save,
    CallbackOver(SocketAddr),
    Second,
    Search(Timer),
    Hashed(Found, Result<FileHashes, String>),
}

// What the connections to other clients bring.
enum PeerEvent {
    Accepted(TcpStream),
    Connected(SocketAddr, Box<io::Result<tcp_connector::Connection>>),
    Resolved([u8; 16], Vec<SocketAddr>),
}

/// How often the offer of our files, the downloads and the other servers are looked at.
const SECOND: Duration = Duration::from_secs(1);

// A connection with another client, and what it said of itself once it greeted us.
struct Peer {
    handle: LinkHandle,
    hello: Option<Hello>,
    incoming: bool,
}

// What the network side gets on its queues.
struct Queues {
    commands: mpsc::UnboundedReceiver<Command>,
    outcomes: mpsc::UnboundedReceiver<Outcome>,
    ticks: mpsc::UnboundedReceiver<Tick>,
    datagrams: mpsc::UnboundedReceiver<(SocketAddr, Packet)>,
    links: mpsc::UnboundedReceiver<LinkEvent>,
    peers: mpsc::UnboundedReceiver<PeerEvent>,
    disk: mpsc::UnboundedReceiver<DiskDone>,
    sent: mpsc::UnboundedReceiver<Sent>,
}

pub struct Network {
    shared: Arc<Shared>,
    servers: ServerList,
    session: ServerSession,
    pinger: ServerPinger,
    pinging: Option<JoinHandle<()>>,
    saving: Option<JoinHandle<()>>,
    udp: Option<ServerUdp>,
    // Keeps the server socket on the UDP relay of a SOCKS5 proxy, when the preferences have one.
    server_relay: Option<Arc<RelayKeeper>>,
    // The UDP keys of the servers asked obfuscated, by address and port, for their answers.
    server_udp_keys: Arc<Mutex<HashMap<(u32, u16), u32>>>,
    ticks: mpsc::UnboundedSender<Tick>,
    random: StdRng,
    callbacks: HashMap<SocketAddr, Instant>,
    calling: HashSet<SocketAddr>,
    our_ip: u32,
    searches: SearchManager,
    known_searches: HashSet<u64>,
    spam: SpamFilter,
    shared_files: SharedFiles,
    publisher: FilePublisher,
    downloads: DownloadManager,
    finished: FinishedDownloads,
    uploads: UploadManager,
    global: GlobalSources,
    peers: HashMap<LinkId, Peer>,
    next_link: LinkId,
    link_events: mpsc::UnboundedSender<LinkEvent>,
    peer_events: mpsc::UnboundedSender<PeerEvent>,
}

/// Starts the network side; it runs until it is asked to stop.
pub fn start(
    shared: Arc<Shared>,
    commands: mpsc::UnboundedReceiver<Command>,
    udp: Option<UdpSocket>,
    listener: Option<TcpListener>,
) -> io::Result<JoinHandle<()>> {
    let dirs = &shared.dirs;
    let mut servers = ServerList::load(&dirs.config.join(server_list::FILE_NAME)).unwrap_or_else(|e| {
        tracing::warn!("Cannot read the server list: {e}");
        ServerList::default()
    });
    if let Err(e) = servers.load_statics(&dirs.config.join(server_list::STATIC_FILE_NAME)) {
        tracing::warn!("Cannot read the static servers: {e}");
    }
    let (outcomes_in, outcomes) = mpsc::unbounded_channel();
    let (ticks_in, ticks) = mpsc::unbounded_channel();
    let (datagrams_in, datagrams) = mpsc::unbounded_channel();
    let udp = udp.map(|socket| ServerUdp::start(socket, datagrams_in));
    let server_udp_keys: Arc<Mutex<HashMap<(u32, u16), u32>>> = Arc::default();
    // Put on the route of the proxy before anything is sent through it, then kept there as the proxy changes.
    let local = match shared.routes.binding() {
        Binding::Bound(address) => Some(address),
        Binding::Any | Binding::Missing(_) => None,
    };
    let server_relay =
        udp.as_ref().map(|udp| {
            Arc::new(RelayKeeper::start("Server UDP", udp.router(), shared.routes.proxy(), local, |level, text| {
                match level {
                    Level::Info => tracing::info!("{text}"),
                    Level::Warning => tracing::warn!("{text}"),
                }
            }))
        });
    let following = server_relay.clone().map(|relay| {
        let mut changes = shared.preferences.subscribe();
        tokio::spawn(async move {
            while changes.changed().await.is_ok() {
                let proxy = changes.borrow_and_update().proxy.clone();
                relay.follow(proxy);
            }
        })
    });
    if let Some(udp) = &udp {
        let keys = Arc::clone(&server_udp_keys);
        udp.decode_with(move |from| key_of(&keys, from));
    }
    // The incoming folder is shared: the files known from before at once, the others once hashed.
    let mut shared_files = SharedFiles::load(&dirs.config);
    let to_hash: Vec<Found> =
        shared_files::scan(&dirs.downloads).into_iter().filter(|found| !shared_files.take_known(found)).collect();
    let hashed = ticks_in.clone();
    let hashing = tokio::task::spawn_blocking(move || {
        for found in to_hash {
            let hashes = shared_files::hash(&found).map_err(|e| e.to_string());
            if hashed.send(Tick::Hashed(found, hashes)).is_err() {
                return;
            }
        }
    });
    let seconds = ticks_in.clone();
    let publish = tokio::spawn(async move {
        let mut every = tokio::time::interval(SECOND);
        loop {
            every.tick().await;
            if seconds.send(Tick::Second).is_err() {
                break;
            }
        }
    });
    let (links_in, links) = mpsc::unbounded_channel();
    let (peers_in, peers) = mpsc::unbounded_channel();
    let (disk_in, disk) = mpsc::unbounded_channel();
    let (sent_in, sent) = mpsc::unbounded_channel();
    let preferences = shared.preferences.borrow().clone();
    let temp = preferences.temp_dir.clone().unwrap_or_else(|| dirs.temp.clone());
    let incoming = preferences.incoming_dir.clone().unwrap_or_else(|| dirs.downloads.clone());
    let downloads = DownloadManager::open(&temp, &incoming, disk_in)?;
    let accepting = listener.map(|listener| tokio::spawn(accept(listener, peers_in.clone())));
    let finished = FinishedDownloads::load(&dirs.config);
    let spam_file = dirs.config.join(spam_filter::FILE_NAME);
    let spam = SpamFilter::load(&spam_file).unwrap_or_else(|e| {
        tracing::warn!("Cannot read {}: {e}", spam_file.display());
        SpamFilter::default()
    });
    let searches = if udp.is_some() { SearchManager::default() } else { SearchManager::default().without_udp() };
    let network = Network {
        shared,
        servers,
        session: ServerSession::new(outcomes_in),
        pinger: ServerPinger::default(),
        pinging: None,
        saving: None,
        udp,
        server_relay,
        server_udp_keys,
        ticks: ticks_in,
        random: StdRng::from_rng(&mut rand::rng()),
        callbacks: HashMap::new(),
        calling: HashSet::new(),
        our_ip: 0,
        searches,
        known_searches: HashSet::new(),
        spam,
        shared_files,
        publisher: FilePublisher::default(),
        downloads,
        finished,
        uploads: UploadManager::new(sent_in),
        global: GlobalSources::default(),
        peers: HashMap::new(),
        next_link: 1,
        link_events: links_in,
        peer_events: peers_in,
    };
    let queues = Queues { commands, outcomes, ticks, datagrams, links, peers, disk, sent };
    Ok(tokio::spawn(async move {
        network.run(queues).await;
        if let Some(following) = following {
            following.abort();
        }
        if let Some(accepting) = accepting {
            accepting.abort();
        }
        publish.abort();
        hashing.abort();
    }))
}

impl Network {
    async fn run(mut self, mut queues: Queues) {
        loop {
            tokio::select! {
                command = queues.commands.recv() => match command {
                    Some(Command::Stop(done)) => {
                        self.stop();
                        let _ = done.send(());
                        return;
                    }
                    Some(command) => self.command(command),
                    None => return,
                },
                Some(outcome) = queues.outcomes.recv() => self.outcome(outcome),
                Some(tick) = queues.ticks.recv() => self.tick(tick),
                Some((from, packet)) = queues.datagrams.recv() => self.datagram(from, &packet),
                Some(event) = queues.links.recv() => self.link_event(event),
                Some(event) = queues.peers.recv() => self.peer_event(event),
                Some(done) = queues.disk.recv() => {
                    let ours = self.shared.our_hello();
                    self.downloads.disk_done(done, &ours);
                    self.after_downloads();
                }
                Some(sent) = queues.sent.recv() => {
                    let files = &self.shared_files;
                    self.uploads.block_sent(&sent, &|hash: &[u8; 16]| files.get(hash).cloned());
                }
            }
        }
    }

    fn command(&mut self, command: Command) {
        match command {
            Command::ImportServers(read, added) => {
                let count = self.servers.add_all(read);
                if count > 0 {
                    self.save_servers();
                }
                let _ = added.send(count);
            }
            Command::ConnectServer(address, done) => {
                let _ = done.send(self.connect(address));
            }
            Command::DisconnectServer(done) => {
                self.with_session(|session, ctx| session.disconnect(ctx));
                let _ = done.send(());
            }
            Command::Servers(answer) => {
                let infos = self.servers.all().iter().map(|server| self.info(server)).collect();
                let _ = answer.send(infos);
            }
            Command::SetStaticServer(address, wanted, done) => {
                let known = self.servers.get(&address).is_some();
                if known && self.servers.set_static(&address, wanted) {
                    self.save_statics();
                    if let Some(server) = self.servers.get(&address).cloned() {
                        self.publish(EngineEvent::ServerUpdated(self.info(&server)));
                    }
                }
                let _ = done.send(known);
            }
            Command::StartSearch(query, scope, answer) => {
                let id = self.session.id();
                let session = &mut self.session;
                let mut effects = Vec::new();
                // Kad comes with its port.
                let started = self.searches.start(&query, scope, id, None, |packet| session.send(packet), &mut effects);
                if let Ok(search) = started {
                    self.known_searches.insert(search);
                }
                // The search is told first, so that its events find it.
                let _ = answer.send(started);
                self.search_effects(effects);
            }
            Command::MoreResults(search, answer) => {
                if !self.known_searches.contains(&search) {
                    let _ = answer.send(MoreAnswer::Unknown);
                    return;
                }
                let session = &mut self.session;
                let mut effects = Vec::new();
                let asked = self.searches.more(search, |packet| session.send(packet), &mut effects);
                let _ = answer.send(if asked { MoreAnswer::Asked } else { MoreAnswer::Refused });
                self.search_effects(effects);
            }
            Command::SharedFiles(answer) => {
                let _ = answer.send(self.shared_files.files().cloned().collect());
            }
            Command::AddDownload(link, paused, answer) => {
                let ours = self.shared.our_hello();
                let fresh = self.downloads.download(&link.hash).is_none();
                let added = self.downloads.add(&link, &self.settings(), &ours).map_err(|e| e.to_string());
                let paused = paused.unwrap_or_else(|| self.preferences().add_downloads_paused);
                let added = match added {
                    Ok(info) if fresh && paused => {
                        self.downloads.pause(&info.hash, &ours);
                        Ok(self.downloads.download(&info.hash).unwrap_or(info))
                    }
                    other => other,
                };
                if let Ok(info) = &added {
                    tracing::info!("Downloading {}", info.name);
                }
                self.after_downloads();
                let _ = answer.send(added);
            }
            Command::Downloads(answer) => {
                let mut all = self.downloads.downloads();
                let active: HashSet<[u8; 16]> = all.iter().map(|d| d.hash).collect();
                all.extend(self.finished.downloads().filter(|d| !active.contains(&d.hash)).cloned());
                let _ = answer.send(all);
            }
            Command::Sources(answer) => {
                let _ = answer.send(self.downloads.sources());
            }
            Command::PauseDownload(hash, answer) => {
                let ours = self.shared.our_hello();
                let paused = self.downloads.pause(&hash, &ours);
                self.after_downloads();
                let _ = answer.send(self.downloads.download(&hash).filter(|_| paused));
            }
            Command::ResumeDownload(hash, answer) => {
                let ours = self.shared.our_hello();
                let resumed = self.downloads.resume(&hash, &ours);
                self.after_downloads();
                let _ = answer.send(self.downloads.download(&hash).filter(|_| resumed));
            }
            Command::CancelDownload(hash, answer) => {
                let ours = self.shared.our_hello();
                let cancelled = self.downloads.cancel(&hash, &ours);
                self.after_downloads();
                let _ = answer.send(cancelled);
            }
            Command::ClearCompleted(answer) => {
                let cleared = self.finished.clear();
                for hash in &cleared {
                    self.publish(EngineEvent::DownloadRemoved(*hash));
                }
                let _ = answer.send(cleared.len());
            }
            Command::ClearCompletedOne(hash, answer) => {
                let cleared = self.finished.remove(&hash);
                if cleared {
                    self.publish(EngineEvent::DownloadRemoved(hash));
                }
                let _ = answer.send(cleared);
            }
            Command::Stop(_) => {}
        }
    }

    /// Connects to the server at `address` (added to the list if new), or to any.
    fn connect(&mut self, address: Option<Address>) -> Result<(), ConnectError> {
        let Some(address) = address else {
            if self.servers.connectable().is_empty() {
                return Err(ConnectError::NoServer);
            }
            self.with_session(|session, ctx| session.connect(None, ctx));
            return Ok(());
        };
        if address.ip != 0 && self.is_filtered_lan(address.ip) {
            return Err(ConnectError::Refused(format!("LAN addresses are filtered: {address}")));
        }
        let server = self.servers.get(&address).cloned().unwrap_or_else(|| by_hand(&address));
        if self.servers.add(server.clone()) {
            self.save_servers();
        }
        self.with_session(|session, ctx| session.connect(Some(server), ctx));
        Ok(())
    }

    fn outcome(&mut self, outcome: Outcome) {
        self.with_session(|session, ctx| session.handle(outcome, ctx));
    }

    // Lends the session what it works with, then acts on what it tells.
    fn with_session(&mut self, work: impl FnOnce(&mut ServerSession, &mut Context<'_>)) {
        let preferences = self.preferences();
        let mut notices = Vec::new();
        let mut ctx = Context {
            servers: &mut self.servers,
            preferences: &preferences,
            routes: &self.shared.routes,
            user_hash: self.shared.user_hash,
            listen_port: self.shared.listen_port,
            keep_connected: true,
            notices: &mut notices,
        };
        work(&mut self.session, &mut ctx);
        for notice in notices {
            self.notice(notice, &preferences);
        }
    }

    fn notice(&mut self, notice: Notice, preferences: &Preferences) {
        match notice {
            Notice::Connecting(server) => self.publish(EngineEvent::ServerConnecting(self.info(&server))),
            Notice::Connected { server, id, obfuscated } => {
                self.our_ip = id.reported_ip;
                self.set_context(ServerContext { client_id: id.client_id, ip: server.ip, port: server.port });
                let info = self.info(&server);
                tracing::info!("Connected to {} with id {}", info.address, id.client_id);
                self.publish(EngineEvent::ServerConnected {
                    server: info,
                    client_id: id.client_id,
                    low_id: id.is_low_id(),
                    obfuscated,
                });
                self.save_servers();
                self.start_pinging();
                self.publisher.connected(Instant::now());
                self.downloads.server_connected();
                self.global.start();
                self.remove_dead(self.servers.dead(preferences.dead_server_retries));
                if preferences.add_servers_from_server {
                    self.session.send(server_messages::get_server_list());
                }
            }
            Notice::IdChanged { server, id } => {
                self.set_context(ServerContext { client_id: id.client_id, ip: server.ip, port: server.port });
                tracing::info!("The server gave us a new id: {}", id.client_id);
            }
            Notice::Disconnected { server, reason } => {
                self.set_context(ServerContext::default());
                self.stop_pinging();
                self.publisher.disconnected();
                self.global.stop(Instant::now());
                let mut effects = Vec::new();
                self.searches.server_lost(&mut effects);
                self.search_effects(effects);
                let info = server.as_ref().map(|server| self.info(server));
                self.publish(EngineEvent::ServerDisconnected { server: info, reason });
            }
            Notice::AttemptFailed { server, reason } => {
                tracing::info!("Cannot connect to {}: {reason}", server_list::address_of(&server));
            }
            Notice::Retrying(wait) => {
                tracing::info!("No server accepted the connection: trying again in {} s", wait.as_secs());
            }
            Notice::Message { server, line } => {
                tracing::info!("{}: {line}", server_list::address_of(&server));
                self.publish(EngineEvent::ServerMessage { server: self.info(&server), text: line });
            }
            Notice::Updated(server) => self.server_updated(&server),
            Notice::CallbackRequested(request) => self.callback_requested(&request, preferences),
            Notice::ServerList { server, servers } => {
                if preferences.add_servers_from_server {
                    self.servers_learnt(&servers, &server_list::address_of(&server).to_string());
                }
            }
            // Searches and downloads come with their own ports.
            Notice::SearchResults { results, .. } => {
                let connected = self.session.server().map(server_list::address_of);
                let mut effects = Vec::new();
                let mut spam = Rater::of(&mut self.spam, &self.shared.preferences.borrow());
                self.searches.server_results(
                    &results.files,
                    results.more,
                    &self.servers,
                    connected,
                    &mut spam,
                    &mut effects,
                );
                self.search_effects(effects);
            }
            Notice::FoundSources(found) => {
                let ours = self.shared.our_hello();
                let id = self.session.id().map_or(0, |id| id.client_id);
                self.downloads.found_sources(&found, &self.settings(), id, &ours);
                self.after_downloads();
            }
        }
    }

    fn tick(&mut self, tick: Tick) {
        match tick {
            Tick::Ping => {
                let retries = self.preferences().dead_server_retries;
                let effects = self.pinger.ping_next(&mut self.servers, retries, &mut self.random);
                self.effects(effects);
            }
            Tick::Save => {
                self.saving = None;
                self.save_servers();
            }
            Tick::CallbackOver(address) => {
                self.calling.remove(&address);
            }
            Tick::Second => {
                self.publish_files();
                self.second();
            }
            Tick::Search(timer) => {
                let connected = self.session.server().map(server_list::address_of);
                let mut effects = Vec::new();
                self.searches.timer(timer, &self.servers, connected, &mut effects);
                self.search_effects(effects);
            }
            Tick::Hashed(found, hashes) => match hashes {
                Ok(hashes) => {
                    let file = self.shared_files.hashed(found, &hashes);
                    tracing::info!("Sharing {}", file.name);
                    if let Err(e) = self.shared_files.save(&self.shared.dirs.config) {
                        tracing::warn!("Cannot save the known files: {e}");
                    }
                    self.publisher.changed(None);
                }
                Err(e) => tracing::warn!("Cannot hash {}: {e}", found.path.display()),
            },
        }
    }

    fn datagram(&mut self, from: SocketAddr, packet: &Packet) {
        if packet.opcode() == server_udp::GLOBFOUNDSOURCES {
            let ours = self.shared.our_hello();
            let id = self.session.id().map_or(0, |id| id.client_id);
            for found in self.global.received(from, packet) {
                self.downloads.found_sources(&found, &self.settings(), id, &ours);
            }
            self.after_downloads();
            return;
        }
        if packet.opcode() == server_udp::GLOBSEARCHRES {
            let SocketAddr::V4(from) = from else {
                return;
            };
            match server_datagrams::decode_search_results(packet) {
                Ok(files) => {
                    let mut effects = Vec::new();
                    let mut spam = Rater::of(&mut self.spam, &self.shared.preferences.borrow());
                    self.searches.datagram_results(u32::from(*from.ip()), &files, &mut spam, &mut effects);
                    self.search_effects(effects);
                }
                Err(e) => tracing::debug!("Malformed search answer from {from}: {e}"),
            }
            return;
        }
        let effects = self.pinger.received(from, packet, &mut self.servers, self.our_ip, &mut self.random);
        self.effects(effects);
    }

    fn effects(&mut self, effects: Vec<Effect>) {
        for effect in effects {
            match effect {
                Effect::Send(target, packet) => self.send_datagram(target, &packet),
                Effect::Updated(server) => self.server_updated(&server),
                Effect::Dead(server) => self.remove_dead(vec![server_list::address_of(&server)]),
            }
        }
    }

    fn send_datagram(&self, target: SocketAddr, packet: &Packet) {
        let Some(udp) = &self.udp else {
            return;
        };
        if let Err(e) = udp.send(target, packet) {
            tracing::debug!("Cannot send a datagram to {target}: {e}");
        }
    }

    fn send_routed(&mut self, route: &ServerRoute, packet: &Packet) {
        let Some(udp) = &self.udp else {
            return;
        };
        if let Err(e) = udp.send_routed(route, packet, &mut self.random) {
            tracing::debug!("Cannot send a datagram to {}: {e}", route.target);
        }
    }

    // Our address as the server we are on sees it, which server UDP keys are bound to; 0 while unknown.
    fn address_seen_by_server(&self) -> u32 {
        self.session.id().map_or(0, |id| id.public_ip())
    }

    fn search_effects(&mut self, effects: Vec<SearchEffect>) {
        for effect in effects {
            match effect {
                SearchEffect::Query(server, packet) => {
                    let our_ip = self.address_seen_by_server();
                    let mode = self.preferences().obfuscation;
                    if let Some(route) = route_to(&self.server_udp_keys, &server, our_ip, mode) {
                        self.send_routed(&route, &packet);
                    }
                }
                SearchEffect::Results { search, changed } => {
                    self.publish(EngineEvent::SearchResults { search, results: changed });
                }
                SearchEffect::Ended { search, results } => self.publish(EngineEvent::SearchEnded { search, results }),
                SearchEffect::After(delay, timer) => {
                    let ticks = self.ticks.clone();
                    tokio::spawn(async move {
                        tokio::time::sleep(delay).await;
                        let _ = ticks.send(Tick::Search(timer));
                    });
                }
                // No Kad search is started yet.
                SearchEffect::StopKad(_) => {}
            }
        }
    }

    // Offers our files to the server when an offer is due.
    fn publish_files(&mut self) {
        let offers: Vec<Offer> = self
            .shared_files
            .files()
            .map(|file| Offer { hash: file.hash, name: file.name.clone(), size: file.size, complete: true })
            .collect();
        let now = Instant::now();
        let due = self.publisher.tick(now, self.session.id(), self.session.server(), self.shared.listen_port, &offers);
        if let Some((packet, hashes)) = due
            && self.session.send(packet)
        {
            self.publisher.sent(now, hashes);
        }
    }

    // Once a second: the downloads, the other servers asked for sources, the upload slots.
    fn second(&mut self) {
        let ours = self.shared.our_hello();
        let settings = self.settings();
        let context = self.shared.context.lock().map(|context| *context).unwrap_or_default();
        let obfuscation = self.preferences().obfuscation;
        let mut server = SessionLink { session: &mut self.session, context, obfuscation };
        self.downloads.tick(&mut server, &settings, &ours);
        let connected = self.session.server().map(server_list::address_of);
        let wanting = self.downloads.wanting_sources(&settings);
        let our_ip = self.address_seen_by_server();
        let preferences = self.preferences();
        let keys = &self.server_udp_keys;
        let route = |server: &Server| route_to(keys, server, our_ip, preferences.obfuscation);
        let asking = Asking {
            connected: connected.as_ref(),
            dead_retries: preferences.dead_server_retries,
            allowed: preferences.obfuscation != ObfuscationMode::Required,
            route: &route,
        };
        if let Some((route, packet)) = self.global.tick(Instant::now(), &self.servers, &wanting, &asking) {
            self.send_routed(&route, &packet);
        }
        self.uploads.fill_slots();
        self.after_downloads();
    }

    // Acts on what the downloads asked and tells what they did.
    fn after_downloads(&mut self) {
        for action in self.downloads.take_actions() {
            match action {
                Action::Connect { address, obfuscate_for } => {
                    let events = self.peer_events.clone();
                    let routes = self.shared.routes.clone();
                    tokio::spawn(async move {
                        let mut random = StdRng::from_rng(&mut rand::rng());
                        let result = tcp_connector::connect_to_client(
                            address,
                            obfuscate_for,
                            download_session::HANDSHAKE_TIMEOUT,
                            &routes,
                            &mut random,
                        )
                        .await;
                        let _ = events.send(PeerEvent::Connected(address, Box::new(result)));
                    });
                }
                Action::Resolve { file, hosts } => {
                    let events = self.peer_events.clone();
                    tokio::spawn(async move {
                        let mut found = Vec::new();
                        for host in hosts {
                            if let Ok(mut addresses) = tokio::net::lookup_host((host.host.as_str(), host.port)).await
                                && let Some(address) = addresses.find(SocketAddr::is_ipv4)
                            {
                                found.push(address);
                            }
                        }
                        let _ = events.send(PeerEvent::Resolved(file, found));
                    });
                }
            }
        }
        for event in self.downloads.take_events() {
            match event {
                DownloadEvent::Added(info) => {
                    // A file downloaded again takes the place of its completed download.
                    self.finished.remove(&info.hash);
                    self.publish(EngineEvent::DownloadAdded(info));
                }
                DownloadEvent::Progress(info) => self.publish(EngineEvent::DownloadProgress(info)),
                DownloadEvent::Completed { info, path, file } => {
                    tracing::info!("Finished downloading {}", info.name);
                    let shared = self.shared_files.completed(SharedFile {
                        hash: file.hash(),
                        name: path.file_name().map_or_else(|| info.name.clone(), |n| n.to_string_lossy().into_owned()),
                        size: file.size(),
                        path: path.clone(),
                        mtime: 0,
                        part_hashes: file.part_hashes().to_vec(),
                        aich_hash: file.aich_hash(),
                    });
                    if let Err(e) = self.shared_files.save(&self.shared.dirs.config) {
                        tracing::warn!("Cannot save the known files: {e}");
                    }
                    self.publisher.changed(Some(shared.hash));
                    self.finished.add(&info);
                    self.publish(EngineEvent::DownloadCompleted { download: info, file: path });
                }
                DownloadEvent::Failed { info, reason } => {
                    tracing::error!("Download {} stopped: {reason}", info.name);
                    self.publish(EngineEvent::DownloadFailed { download: info, reason });
                }
                DownloadEvent::Removed(hash) => self.publish(EngineEvent::DownloadRemoved(hash)),
            }
        }
    }

    fn peer_event(&mut self, event: PeerEvent) {
        let ours = self.shared.our_hello();
        match event {
            PeerEvent::Accepted(stream) => {
                let Ok(remote) = stream.peer_addr() else {
                    return;
                };
                let (input, output) = stream.into_split();
                self.new_link(remote, FrameReader::new(input, None), FrameWriter::new(output, None), true);
            }
            PeerEvent::Connected(address, result) => match *result {
                Ok(connection) => {
                    let tcp_connector::Connection { reader, writer, remote, .. } = connection;
                    let handle = self.new_link(remote, reader, writer, false);
                    self.downloads.connected(address, Ok(handle), &ours);
                }
                Err(e) => self.downloads.connected(address, Err(e.to_string()), &ours),
            },
            PeerEvent::Resolved(file, addresses) => self.downloads.resolved(&file, &addresses, &self.settings()),
        }
        self.after_downloads();
    }

    fn new_link<R, W>(
        &mut self,
        remote: SocketAddr,
        reader: FrameReader<R>,
        writer: FrameWriter<W>,
        incoming: bool,
    ) -> LinkHandle
    where
        R: tokio::io::AsyncRead + Unpin + Send + 'static,
        W: tokio::io::AsyncWrite + Unpin + Send + 'static,
    {
        let id = self.next_link;
        self.next_link = self.next_link.saturating_add(1);
        let handle = peer_link::start(id, remote, reader, writer, self.link_events.clone());
        self.peers.insert(id, Peer { handle: handle.clone(), hello: None, incoming });
        handle
    }

    fn link_event(&mut self, event: LinkEvent) {
        let ours = self.shared.our_hello();
        match event {
            LinkEvent::Packet(link, packet) => self.peer_packet(link, &packet, &ours),
            LinkEvent::Closed(link, reason) => {
                self.peers.remove(&link);
                self.downloads.closed(link, reason.as_deref(), &ours);
                self.uploads.closed(link);
            }
        }
        self.after_downloads();
    }

    // A client greets first: the hello on its connection, the hello answer on ours; then each packet goes to the side
    // it is for.
    fn peer_packet(&mut self, link: LinkId, packet: &Packet, ours: &Hello) {
        let Some(peer) = self.peers.get_mut(&link) else {
            return;
        };
        let handle = peer.handle.clone();
        let Some(hello) = peer.hello.clone() else {
            let expected = if peer.incoming { client::HELLO } else { client::HELLOANSWER };
            if packet.protocol() != protocol::EDONKEY || packet.opcode() != expected {
                if peer.incoming {
                    handle.close();
                }
                return;
            }
            let Ok(hello) = Hello::decode(packet) else {
                handle.close();
                return;
            };
            peer.hello = Some(hello.clone());
            if !peer.incoming {
                self.downloads.greeted(link, hello, ours);
                return;
            }
            match ours.to_peer_at(&handle.remote().ip()).encode(true) {
                Ok(answer) => {
                    handle.send(answer);
                }
                Err(e) => tracing::warn!("Cannot answer {}: {e}", handle.remote()),
            }
            let server_ip = self.shared.context.lock().map(|context| context.ip).unwrap_or(0);
            self.downloads.adopt(&handle, &hello, server_ip, &self.settings(), ours);
            return;
        };
        if from_uploader(packet) && self.downloads.has_link(link) {
            self.downloads.received(link, packet, ours);
            return;
        }
        let files = &self.shared_files;
        self.uploads.received(&handle, &hello, packet, ours, &|hash: &[u8; 16]| files.get(hash).cloned());
    }

    fn settings(&self) -> download_manager::Settings {
        let preferences = self.preferences();
        download_manager::Settings {
            obfuscation: preferences.obfuscation,
            max_sources_per_file: MAX_SOURCES_PER_FILE,
            listen_port: self.shared.listen_port,
            filter_lan: preferences.filter_lan_addresses,
        }
    }

    fn server_updated(&mut self, server: &Server) {
        self.publish(EngineEvent::ServerUpdated(self.info(server)));
        self.save_servers_soon();
    }

    // A server that failed as many times in a row as the preferences allow leaves the list; static servers and the
    // one we are on stay.
    fn remove_dead(&mut self, dead: Vec<Address>) {
        let current = self.session.server().map(server_list::address_of);
        let mut removed = 0usize;
        let mut statics = false;
        for address in dead.iter().filter(|address| current.as_ref() != Some(*address)) {
            statics |= self.servers.is_static(address);
            if self.servers.remove(address) {
                removed = removed.saturating_add(1);
            }
        }
        if removed == 0 {
            return;
        }
        self.save_servers();
        if statics {
            self.save_statics();
        }
        tracing::info!("{removed} dead servers removed");
        self.publish(EngineEvent::ServersChanged { count: self.servers.len() });
    }

    // Servers learnt from the server we are on, at low priority, named by their address.
    fn servers_learnt(&mut self, learnt: &[Endpoint], from: &str) {
        let mut added = 0usize;
        for endpoint in learnt {
            let name = Address::new(endpoint.ip, endpoint.port).to_string();
            let server = Server {
                ip: endpoint.ip,
                port: endpoint.port,
                tags: vec![
                    server_list::string(server_tag::SERVERNAME, &name),
                    server_list::int(server_tag::PREFERENCE, server_list::PRIORITY_LOW),
                ],
            };
            if self.servers.add(server) {
                added = added.saturating_add(1);
            }
        }
        if added > 0 {
            self.save_servers_soon();
            tracing::info!("{added} new servers from {from}");
            self.publish(EngineEvent::ServersChanged { count: self.servers.len() });
        }
    }

    // A server asks us to call a firewalled client: it connects to us no other way.
    fn callback_requested(&mut self, request: &CallbackRequested, preferences: &Preferences) {
        let address = SocketAddr::from((Ipv4Addr::from(request.endpoint.ip), request.endpoint.port));
        // The client may require obfuscation, which needs its user hash.
        let obfuscate = request.user_hash.filter(|_| {
            request.crypt_options & CRYPT_REQUESTED_OR_REQUIRED != 0
                && preferences.obfuscation != ObfuscationMode::Disabled
        });
        // Nobody must have us probe our LAN, nor call the same client again and again.
        let ip = request.endpoint.ip;
        if !server_list::is_usable(ip) || request.endpoint.port == 0 || self.is_filtered_lan(ip) {
            tracing::debug!("Ignoring a callback request to {address}");
            return;
        }
        self.callbacks.retain(|_, at| at.elapsed() < CALLBACK_REPEAT);
        if self.calling.len() >= MAX_CALLBACKS_OPEN || self.callbacks.contains_key(&address) {
            tracing::debug!("Not calling {address} back now");
            return;
        }
        self.callbacks.insert(address, Instant::now());
        self.calling.insert(address);
        let ours = self.shared.our_hello();
        let ticks = self.ticks.clone();
        let routes = self.shared.routes.clone();
        tokio::spawn(async move {
            let mut random = StdRng::from_rng(&mut rand::rng());
            match tcp_connector::connect_to_client(
                address,
                obfuscate,
                peer_greetings::HELLO_TIMEOUT,
                &routes,
                &mut random,
            )
            .await
            {
                Ok(connection) => {
                    if let Err(e) = peer_greetings::called_back(connection, ours).await {
                        tracing::debug!("Callback to {address} ended: {e}");
                    }
                }
                Err(e) => tracing::debug!("Callback to {address} failed: {e}"),
            }
            let _ = ticks.send(Tick::CallbackOver(address));
        });
    }

    fn start_pinging(&mut self) {
        if self.pinging.is_some() {
            return;
        }
        let ticks = self.ticks.clone();
        let interval = server_pinger::INTERVAL;
        self.pinging = Some(tokio::spawn(async move {
            let mut every = tokio::time::interval(interval);
            // The first tick is at once: the first ping waits one interval.
            every.tick().await;
            loop {
                every.tick().await;
                if ticks.send(Tick::Ping).is_err() {
                    break;
                }
            }
        }));
    }

    fn stop_pinging(&mut self) {
        if let Some(pinging) = self.pinging.take() {
            pinging.abort();
        }
        self.pinger.stop();
    }

    fn save_servers_soon(&mut self) {
        if self.saving.is_none() {
            let ticks = self.ticks.clone();
            self.saving = Some(tokio::spawn(async move {
                tokio::time::sleep(SAVE_DELAY).await;
                let _ = ticks.send(Tick::Save);
            }));
        }
    }

    fn save_servers(&self) {
        let file = self.shared.dirs.config.join(server_list::FILE_NAME);
        if let Err(e) = self.servers.save(&file) {
            tracing::warn!("Cannot save {}: {e}", file.display());
        }
    }

    fn save_statics(&self) {
        let file = self.shared.dirs.config.join(server_list::STATIC_FILE_NAME);
        if let Err(e) = self.servers.save_statics(&file) {
            tracing::warn!("Cannot save {}: {e}", file.display());
        }
    }

    fn stop(&mut self) {
        let ours = self.shared.our_hello();
        self.downloads.close(&ours);
        for peer in self.peers.values() {
            peer.handle.close();
        }
        self.with_session(|session, ctx| session.disconnect(ctx));
        self.stop_pinging();
        if let Some(saving) = self.saving.take() {
            saving.abort();
        }
        self.save_servers();
        if let Some(relay) = &self.server_relay {
            relay.close();
        }
        // The servers' spam shares change with every UDP answer.
        let spam_file = self.shared.dirs.config.join(spam_filter::FILE_NAME);
        if let Err(e) = self.spam.save(&spam_file) {
            tracing::warn!("Cannot save {}: {e}", spam_file.display());
        }
    }

    fn info(&self, server: &Server) -> ServerInfo {
        ServerInfo::of(server, self.servers.is_static(&server_list::address_of(server)))
    }

    fn is_filtered_lan(&self, ip: u32) -> bool {
        self.preferences().filter_lan_addresses && server_list::is_lan(ip)
    }

    fn preferences(&self) -> Preferences {
        self.shared.preferences.borrow().clone()
    }

    fn publish(&self, event: EngineEvent) {
        let _ = self.shared.events.send(event);
    }

    fn set_context(&self, context: ServerContext) {
        if let Ok(mut current) = self.shared.context.lock() {
            *current = context;
        }
    }
}

// The server session as the downloads use it.
struct SessionLink<'a> {
    session: &'a mut ServerSession,
    context: ServerContext,
    obfuscation: ObfuscationMode,
}

impl ServerLink for SessionLink<'_> {
    fn client_id(&self) -> u32 {
        self.context.client_id
    }

    fn server_ip(&self) -> u32 {
        self.context.ip
    }

    fn ask_sources(&mut self, file: &[u8; 16], size: u64) -> bool {
        let Some(id) = self.session.id().filter(|_| self.session.is_connected()) else {
            return false;
        };
        if size > 0xFFFF_FFFF && id.server_flags & server_messages::TCP_LARGEFILES == 0 {
            return false;
        }
        let obfuscated =
            id.server_flags & server_messages::TCP_OBFUSCATION != 0 && self.obfuscation != ObfuscationMode::Disabled;
        self.session.send(server_messages::get_sources(file, size, obfuscated))
    }

    fn request_callback(&mut self, low_id: u32) -> bool {
        self.session.send(server_messages::callback_request(low_id))
    }
}

// What uploaders send to downloaders; anything else is for the upload side.
fn from_uploader(packet: &Packet) -> bool {
    match packet.protocol() {
        protocol::EDONKEY => matches!(
            packet.opcode(),
            client::SENDINGPART
                | client::FILEREQANSNOFIL
                | client::FILESTATUS
                | client::HASHSETANSWER
                | client::ACCEPTUPLOADREQ
                | client::OUTOFPARTREQS
                | client::REQFILENAMEANSWER
                | client::QUEUERANK
                | client::ASKSHAREDFILESANSWER
                | client::ASKSHAREDDIRSANS
                | client::ASKSHAREDFILESDIRANS
                | client::ASKSHAREDDENIEDANS
        ),
        protocol::EMULE => matches!(
            packet.opcode(),
            extended::COMPRESSEDPART
                | extended::COMPRESSEDPART_I64
                | extended::SENDINGPART_I64
                | extended::QUEUERANKING
                | extended::ANSWERSOURCES
                | extended::ANSWERSOURCES2
                | extended::PREVIEWANSWER
                | extended::MULTIPACKETANSWER
                | extended::MULTIPACKETANSWER_EXT2
                | extended::PUBLICIP_ANSWER
                | extended::AICHANSWER
                | extended::AICHFILEHASHANS
                | extended::FILEDESC
                | extended::HASHSETANSWER2
        ),
        _ => false,
    }
}

// Takes the connections other clients open; an obfuscated one is not taken yet.
async fn accept(listener: TcpListener, events: mpsc::UnboundedSender<PeerEvent>) {
    loop {
        match listener.accept().await {
            Ok((stream, remote)) => {
                let events = events.clone();
                tokio::spawn(async move {
                    let mut first = [0u8; 1];
                    let peeked = tokio::time::timeout(peer_greetings::HELLO_TIMEOUT, stream.peek(&mut first)).await;
                    if matches!(peeked, Ok(Ok(1))) && protocol::is_tcp(first[0]) {
                        let _ = stream.set_nodelay(true);
                        let _ = events.send(PeerEvent::Accepted(stream));
                    } else {
                        tracing::debug!("Closing a connection from {remote} that is not plain eD2K");
                    }
                });
            }
            Err(e) => tracing::warn!("Cannot accept a connection: {e}"),
        }
    }
}

// A server the user gave.
fn by_hand(address: &Address) -> Server {
    Server { ip: address.ip, port: address.port, tags: Vec::new() }
}

// How a server is asked over UDP; the key of an obfuscated route is kept for its answers.
fn route_to(
    keys: &Mutex<HashMap<(u32, u16), u32>>,
    server: &Server,
    our_ip: u32,
    mode: ObfuscationMode,
) -> Option<ServerRoute> {
    let route = ServerRoute::of(server, our_ip, mode)?;
    if let (true, SocketAddr::V4(target)) = (route.is_obfuscated(), route.target) {
        keys.lock().unwrap_or_else(PoisonError::into_inner).insert(slot(target), route.key);
    }
    Some(route)
}

// The UDP key of the server that sent from `from`, 0 for none; servers on one address are told apart by their port.
fn key_of(keys: &Mutex<HashMap<(u32, u16), u32>>, from: SocketAddr) -> u32 {
    let SocketAddr::V4(from) = from else {
        return 0;
    };
    keys.lock().unwrap_or_else(PoisonError::into_inner).get(&slot(from)).copied().unwrap_or(0)
}

fn slot(address: SocketAddrV4) -> (u32, u16) {
    (u32::from(*address.ip()), address.port())
}

// The spam filter as the searches rate with it: off in the preferences, nothing is spam, though the servers' UDP answers
// are still counted.
struct Rater<'a> {
    filter: &'a mut SpamFilter,
    on: bool,
}

impl<'a> Rater<'a> {
    fn of(filter: &'a mut SpamFilter, preferences: &Preferences) -> Self {
        Self { filter, on: preferences.search_spam_filter }
    }
}

impl SpamRater for Rater<'_> {
    fn rate(&self, result: &SearchResult) -> u32 {
        if self.on { self.filter.rate(result) } else { 0 }
    }

    fn answered(&mut self, server_ip: u32, results: u32, spam: u32) {
        self.filter.answered(server_ip, results, spam);
    }
}
