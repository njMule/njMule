// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The network side of the engine: the server list, the session with a server and the UDP status of the others, the
//! searches, the downloads and the uploads with the connections to other clients. One task owns all of it; the API,
//! the connection tasks, the disk threads and the timers reach it through its queues.

use std::collections::{HashMap, HashSet};
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::net::{TcpListener, UdpSocket};
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;

use crate::api::download_info::{DownloadInfo, SourceInfo};
use crate::api::ed2k_link::FileLink;
use crate::api::engine_event::EngineEvent;
use crate::api::known_client::KnownClient;
use crate::api::peer_info::{Identification as PeerIdentification, PeerInfo};
use crate::api::search_result::SearchResult;
use crate::api::server_info::ServerInfo;
use crate::config::bind_interface::Binding;
use crate::config::obfuscation_mode::ObfuscationMode;
use crate::config::preferences::Preferences;
use crate::config::proxy_settings::ProxySettings;
use crate::config::shared_files_visibility::SharedFilesVisibility;
use crate::download::download_manager::{self, Action, DownloadEvent, DownloadManager, ServerLink};
use crate::download::download_session;
use crate::download::finished_downloads::FinishedDownloads;
use crate::engine::{ServerContext, Shared};
use crate::files::hash::file_hasher::FileHashes;
use crate::files::part_file_storage::DiskDone;
use crate::files::shared_files::{self, Found, SharedFile, SharedFiles};
use crate::net::relay_keeper::{Level, RelayKeeper};
use crate::net::tcp_connector;
use crate::peer::client_history::ClientHistory;
use crate::peer::identities::{self, Identities, Session, Trust};
use crate::peer::identity_handshake::{Identity, Outcome as Identification};
use crate::peer::peer_link::{self, LinkEvent, LinkHandle, LinkId, Side};
use crate::peer::{peer_greetings, peer_software};
use crate::persist::format::server_met::Server;
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::ed2k::server_datagrams;
use crate::protocol::ed2k::server_messages::{self, CallbackRequested, Endpoint};
use crate::protocol::packet::opcodes::{client, server_udp};
use crate::protocol::packet::{Packet, frame_limits, protocol};
use crate::protocol::tag::server_tag;
use crate::search::search_manager::{Effect as SearchEffect, Scope, SearchManager, SpamRater, StartError, Timer};
use crate::search::spam_filter::{self, SpamFilter};
use crate::security::connection_guard::{Admission, ConnectionGuard, Limits, Permit};
use crate::security::ip_filter::IpFilter;
use crate::server::file_publisher::{FilePublisher, Offer};
use crate::server::global_sources::{Asking, GlobalSources};
use crate::server::server_list::{self, Address, ServerList};
use crate::server::server_pinger::{self, Effect, ServerPinger};
use crate::server::server_route::ServerRoute;
use crate::server::server_session::{Context, Notice, Outcome, ServerSession};
use crate::server::server_udp::ServerUdp;
use crate::upload::credits::{self, Credits};
use crate::upload::upload_manager::{self, BlockRead, UploadManager};

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
    /// Says hello to a client and runs the identification: told once its outcome is known, the connection closes or
    /// the wait is over.
    Hello(SocketAddr, oneshot::Sender<io::Result<PeerInfo>>),
    KnownClients(oneshot::Sender<Vec<KnownClient>>),
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
    // The wait for the identification of a client we said hello to is over.
    ProbeOver(LinkId),
    Second,
    Uploads,
    Search(Timer),
    Hashed(Found, Result<FileHashes, String>),
    ProxyChanged(ProxySettings),
    // The sockets opened for a binding, which a newer one may have replaced meanwhile.
    Rebound(Binding, Option<TcpListener>, Option<UdpSocket>),
}

/// How often the network interface of the preferences is looked at: it may go, as a VPN that drops, come back, or
/// come back with another address.
const BIND_CHECK_SECONDS: u32 = 10;

// What the connections to other clients bring.
enum PeerEvent {
    // A client's connection the guard admitted, plain or obfuscated, with its permit.
    Accepted(Box<tcp_connector::Connection>, Permit),
    Connected(SocketAddr, Box<io::Result<tcp_connector::Connection>>),
    // A client we call for the upload slot its turn brought, by user hash.
    UploadCall([u8; 16], Box<io::Result<tcp_connector::Connection>>),
    // A client we call back on a server's request.
    CalledBack(SocketAddr, Box<io::Result<tcp_connector::Connection>>),
    Resolved([u8; 16], Vec<SocketAddr>),
    // The connection of a hello asked through the API, and who waits for the answer.
    Probed(Box<io::Result<tcp_connector::Connection>>, oneshot::Sender<io::Result<PeerInfo>>),
}

// How long a client we call for its upload slot has to accept the connection.
const UPLOAD_CALL_TIMEOUT: Duration = Duration::from_secs(45);

/// How often the offer of our files, the downloads and the other servers are looked at.
const SECOND: Duration = Duration::from_secs(1);

// A connection with another client, and what it said of itself once it greeted us.
struct Peer {
    handle: LinkHandle,
    hello: Option<Hello>,
    // The secure identification of the client, once it greeted us.
    identity: Option<Session>,
    incoming: bool,
    // The address of a client we call back, free to be called again once it answered our hello or left.
    called_back: Option<SocketAddr>,
    // The user hash of a client we called for its upload slot, until it answers our hello.
    upload_for: Option<[u8; 16]>,
    // Held while the connection lives: the guard counts it.
    _permit: Option<Permit>,
}

// A hello of ours asked through the API: the client's answer, then the identification.
struct Probe {
    handle: LinkHandle,
    hello: Option<Hello>,
    identity: Option<Session>,
    answer: Option<oneshot::Sender<io::Result<PeerInfo>>>,
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
    reads: mpsc::UnboundedReceiver<BlockRead>,
}

pub struct Network {
    shared: Arc<Shared>,
    servers: ServerList,
    session: ServerSession,
    pinger: ServerPinger,
    pinging: Option<JoinHandle<()>>,
    saving: Option<JoinHandle<()>>,
    udp: Option<ServerUdp>,
    // Admits the connections clients open to us.
    guard: Arc<ConnectionGuard>,
    // Keeps the server socket on the UDP relay of a SOCKS5 proxy, when the preferences have one.
    server_relay: Option<RelayKeeper>,
    datagrams: mpsc::UnboundedSender<(SocketAddr, Packet)>,
    accepting: Option<JoinHandle<()>>,
    following: Option<JoinHandle<()>>,
    // The interface the sockets are on, and the seconds since it was last looked at.
    binding: Binding,
    since_bind_check: u32,
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
    probes: HashMap<LinkId, Probe>,
    next_link: LinkId,
    // Our key, which clients ask us to prove we own; the keys they proved to own are kept with the uploads' credits.
    identities: Identities,
    // The clients met, by user hash.
    history: ClientHistory,
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
    let server_udp_keys: Arc<Mutex<HashMap<(u32, u16), u32>>> = Arc::default();
    // The proxy as it changes, for the relay of the server socket.
    let mut changes = shared.preferences.subscribe();
    let proxies = ticks_in.clone();
    let following = tokio::spawn(async move {
        while changes.changed().await.is_ok() {
            let proxy = changes.borrow_and_update().proxy.clone();
            if proxies.send(Tick::ProxyChanged(proxy)).is_err() {
                return;
            }
        }
    });
    let binding = shared.routes.binding();
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
    // The upload queue is served ten times a second, which spreads the budget over the second.
    let serving = ticks_in.clone();
    let serve = tokio::spawn(async move {
        let mut every = tokio::time::interval(upload_manager::TICK);
        loop {
            every.tick().await;
            if serving.send(Tick::Uploads).is_err() {
                break;
            }
        }
    });
    let (links_in, links) = mpsc::unbounded_channel();
    let (peers_in, peers) = mpsc::unbounded_channel();
    let (disk_in, disk) = mpsc::unbounded_channel();
    let (reads_in, reads) = mpsc::unbounded_channel();
    let preferences = shared.preferences.borrow().clone();
    let temp = preferences.temp_dir.clone().unwrap_or_else(|| dirs.temp.clone());
    let incoming = preferences.incoming_dir.clone().unwrap_or_else(|| dirs.downloads.clone());
    let downloads = DownloadManager::open(&temp, &incoming, disk_in)?;
    let finished = FinishedDownloads::load(&dirs.config);
    let spam_file = dirs.config.join(spam_filter::FILE_NAME);
    let spam = SpamFilter::load(&spam_file).unwrap_or_else(|e| {
        tracing::warn!("Cannot read {}: {e}", spam_file.display());
        SpamFilter::default()
    });
    let identities = Identities::new(Identity::of(identities::load_or_create_key(&dirs.config)?));
    let credits = Credits::load(&dirs.config, credits::system_clock());
    let history = ClientHistory::load(&dirs.config, now_seconds());
    // The guard's clock.
    let started = Instant::now();
    let mut network = Network {
        shared,
        servers,
        session: ServerSession::new(outcomes_in),
        pinger: ServerPinger::default(),
        pinging: None,
        saving: None,
        udp: None,
        guard: Arc::new(ConnectionGuard::new(
            || Arc::new(IpFilter::empty()),
            |_| false,
            |_| false,
            || Limits::DEFAULT,
            move || started.elapsed(),
        )),
        server_relay: None,
        datagrams: datagrams_in,
        accepting: None,
        following: Some(following),
        binding,
        since_bind_check: 0,
        server_udp_keys,
        ticks: ticks_in,
        random: StdRng::from_rng(&mut rand::rng()),
        callbacks: HashMap::new(),
        calling: HashSet::new(),
        our_ip: 0,
        searches: SearchManager::default().without_udp(),
        known_searches: HashSet::new(),
        spam,
        shared_files,
        publisher: FilePublisher::default(),
        downloads,
        finished,
        uploads: UploadManager::new(reads_in, credits, upload_settings(&preferences)),
        global: GlobalSources::default(),
        peers: HashMap::new(),
        probes: HashMap::new(),
        next_link: 1,
        identities,
        history,
        link_events: links_in,
        peer_events: peers_in,
    };
    network.attach(listener, udp);
    let queues = Queues { commands, outcomes, ticks, datagrams, links, peers, disk, reads };
    Ok(tokio::spawn(async move {
        network.run(queues).await;
        publish.abort();
        serve.abort();
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
                Some(read) = queues.reads.recv() => self.uploads.block_read(read, Instant::now()),
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
            Command::Hello(address, answer) => {
                let events = self.peer_events.clone();
                let routes = self.shared.routes.clone();
                tokio::spawn(async move {
                    let mut random = StdRng::from_rng(&mut rand::rng());
                    let connection = tcp_connector::connect_to_client(
                        address,
                        None,
                        peer_greetings::HELLO_TIMEOUT,
                        &routes,
                        &mut random,
                    )
                    .await;
                    let _ = events.send(PeerEvent::Probed(Box::new(connection), answer));
                });
            }
            Command::KnownClients(answer) => {
                let _ = answer.send(self.known_clients());
            }
            Command::Stop(_) => {}
        }
    }

    fn known_clients(&mut self) -> Vec<KnownClient> {
        let credits = self.uploads.credits();
        self.history
            .clients(now_seconds())
            .into_iter()
            .map(|entry| {
                let credit = credits.get(&entry.user_hash);
                KnownClient {
                    user_hash: entry.user_hash,
                    nick: entry.nick,
                    software: entry.software,
                    first_seen: entry.first_seen,
                    last_seen: entry.last_seen,
                    country: entry.country,
                    uploaded: credit.map_or(0, |credit| credit.uploaded),
                    downloaded: credit.map_or(0, |credit| credit.downloaded),
                }
            })
            .collect()
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
            listen_port: self.shared.listen_port(),
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
            Tick::ProbeOver(link) => self.settle_probe(link),
            Tick::Uploads => {
                let settings = upload_settings(&self.preferences());
                let files = &self.shared_files;
                self.uploads.tick(settings, &|hash: &[u8; 16]| files.get(hash).cloned(), Instant::now());
                self.after_uploads();
            }
            Tick::Second => {
                self.publish_files();
                self.second();
                self.since_bind_check = self.since_bind_check.saturating_add(1);
                if self.since_bind_check >= BIND_CHECK_SECONDS {
                    self.since_bind_check = 0;
                    self.check_binding();
                }
            }
            Tick::ProxyChanged(proxy) => {
                // TCP reads the proxy at each connection; UDP keeps an association with it.
                if let Some(relay) = &self.server_relay {
                    relay.follow(proxy);
                }
            }
            Tick::Rebound(binding, listener, udp) => {
                if binding == self.binding {
                    self.attach(listener, udp);
                }
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

    // Puts the sockets opened for the binding to work: clients are accepted, and the server socket decodes the answers
    // of the servers asked obfuscated and goes on the route of the proxy before anything is sent through it.
    fn attach(&mut self, listener: Option<TcpListener>, udp: Option<UdpSocket>) {
        let port =
            listener.as_ref().and_then(|listener| listener.local_addr().ok()).map_or(0, |address| address.port());
        self.shared.listen_port.store(port, std::sync::atomic::Ordering::Relaxed);
        self.accepting = listener.map(|listener| {
            tokio::spawn(accept(listener, self.peer_events.clone(), Arc::clone(&self.shared), Arc::clone(&self.guard)))
        });
        let udp = udp.map(|socket| ServerUdp::start(socket, self.datagrams.clone()));
        if let Some(udp) = &udp {
            let keys = Arc::clone(&self.server_udp_keys);
            udp.decode_with(move |from| key_of(&keys, from));
            let local = match self.binding {
                Binding::Bound(address) => Some(address),
                Binding::Any | Binding::Missing(_) => None,
            };
            self.server_relay = Some(RelayKeeper::start(
                "Server UDP",
                udp.router(),
                self.shared.routes.proxy(),
                local,
                |level, text| match level {
                    Level::Info => tracing::info!("{text}"),
                    Level::Warning => tracing::warn!("{text}"),
                },
            ));
        }
        self.searches.set_udp(udp.is_some());
        self.udp = udp;
    }

    fn close_sockets(&mut self) -> (Option<JoinHandle<()>>, Option<ServerUdp>) {
        if let Some(relay) = self.server_relay.take() {
            relay.close();
        }
        self.searches.set_udp(false);
        self.shared.listen_port.store(0, std::sync::atomic::Ordering::Relaxed);
        let accepting = self.accepting.take();
        if let Some(accepting) = &accepting {
            accepting.abort();
        }
        (accepting, self.udp.take())
    }

    // The interface may go (a VPN that drops), come back, or come back with another address.
    fn check_binding(&mut self) {
        let now = self.shared.routes.binding();
        if now != self.binding {
            self.rebind(now);
        }
    }

    // Every socket bound for the old interface closes; the new one opens them again, or nothing does while it is gone.
    // Connections already made finish as they are: those through a dropped interface end with it.
    fn rebind(&mut self, now: Binding) {
        self.binding = now.clone();
        self.with_session(|session, ctx| session.disconnect(ctx));
        let (accepting, udp) = self.close_sockets();
        let preferences = self.preferences();
        let local = match &now {
            Binding::Missing(choice) => {
                tracing::warn!(
                    "The network interface {choice} is not available: njMule stays offline until it is back"
                );
                None
            }
            Binding::Bound(address) => {
                tracing::info!("Connections go through {} ({address})", preferences.bind_interface);
                Some(Some(*address))
            }
            Binding::Any => {
                tracing::info!("Connections go through any network interface");
                Some(None)
            }
        };
        let ticks = self.ticks.clone();
        tokio::spawn(async move {
            // The old sockets leave their ports before the new ones take them.
            if let Some(accepting) = accepting {
                let _ = accepting.await;
            }
            if let Some(udp) = udp {
                udp.close().await;
            }
            let Some(local) = local else {
                return;
            };
            let address = SocketAddr::from((local.unwrap_or(Ipv4Addr::UNSPECIFIED), preferences.tcp_port));
            let listener = TcpListener::bind(address)
                .await
                .inspect_err(|e| tracing::warn!("Cannot listen on TCP port {}: {e}", preferences.tcp_port))
                .ok();
            let udp = ServerUdp::bind_beside(preferences.udp_port, local)
                .await
                .inspect_err(|e| tracing::warn!("Cannot open the server UDP socket: {e}"))
                .ok();
            let _ = ticks.send(Tick::Rebound(now, listener, udp));
        });
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
        let due =
            self.publisher.tick(now, self.session.id(), self.session.server(), self.shared.listen_port(), &offers);
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
            PeerEvent::Accepted(connection, permit) => {
                self.new_link(*connection, Some(permit));
            }
            PeerEvent::Connected(address, result) => match *result {
                Ok(connection) => {
                    let handle = self.new_link(connection, None);
                    self.downloads.connected(address, Ok(handle), &ours);
                }
                Err(e) => self.downloads.connected(address, Err(e.to_string()), &ours),
            },
            PeerEvent::Resolved(file, addresses) => self.downloads.resolved(&file, &addresses, &self.settings()),
            PeerEvent::UploadCall(user_hash, result) => match *result {
                Ok(connection) => {
                    let handle = self.new_link(connection, None);
                    handle.set_timeout(peer_greetings::HELLO_TIMEOUT);
                    if let Some(peer) = self.peers.get_mut(&handle.id()) {
                        peer.upload_for = Some(user_hash);
                    }
                    match self.shared.our_hello().to_peer_at(&handle.remote().ip()).encode(false) {
                        Ok(hello) => {
                            handle.send(hello);
                        }
                        Err(e) => {
                            tracing::warn!("Cannot greet {}: {e}", handle.remote());
                            handle.close();
                        }
                    }
                }
                Err(e) => self.uploads.call_failed(user_hash, &e.to_string()),
            },
            PeerEvent::CalledBack(address, result) => match *result {
                Ok(connection) => self.call_back(address, connection),
                Err(e) => {
                    tracing::debug!("Callback to {address} failed: {e}");
                    self.calling.remove(&address);
                }
            },
            PeerEvent::Probed(connection, answer) => match *connection {
                Ok(connection) => self.probe(connection, answer),
                Err(e) => {
                    let _ = answer.send(Err(e));
                }
            },
        }
        self.after_downloads();
    }

    // We say hello first; once the hellos are over, the client asks for what it wants over the link, which our upload
    // side answers.
    fn call_back(&mut self, address: SocketAddr, connection: tcp_connector::Connection) {
        let handle = self.new_link(connection, None);
        handle.set_timeout(peer_greetings::HELLO_TIMEOUT);
        if let Some(peer) = self.peers.get_mut(&handle.id()) {
            peer.called_back = Some(address);
        }
        match self.shared.our_hello().to_peer_at(&handle.remote().ip()).encode(false) {
            Ok(hello) => {
                handle.send(hello);
            }
            Err(e) => {
                tracing::warn!("Cannot greet {address}: {e}");
                handle.close();
            }
        }
    }

    // Our hello goes first; the outcome is told at the latest once the client had the time to answer and identify.
    fn probe(&mut self, connection: tcp_connector::Connection, answer: oneshot::Sender<io::Result<PeerInfo>>) {
        let id = self.next_link;
        self.next_link = self.next_link.saturating_add(1);
        let connection = connection.into_tcp_connection(frame_limits::client_handshake);
        let handle = peer_link::start(id, connection, self.link_events.clone());
        handle.set_timeout(peer_greetings::HELLO_TIMEOUT);
        match self.shared.our_hello().to_peer_at(&handle.remote().ip()).encode(false) {
            Ok(hello) => {
                handle.send(hello);
            }
            Err(e) => {
                let _ = answer.send(Err(io::Error::other(e)));
                handle.close();
                return;
            }
        }
        self.probes.insert(id, Probe { handle, hello: None, identity: None, answer: Some(answer) });
        let ticks = self.ticks.clone();
        tokio::spawn(async move {
            tokio::time::sleep(peer_greetings::HELLO_TIMEOUT.saturating_add(peer_greetings::IDENTIFY_WAIT)).await;
            let _ = ticks.send(Tick::ProbeOver(id));
        });
    }

    fn probe_event(&mut self, event: LinkEvent) {
        match event {
            LinkEvent::Packet(link, packet) => self.probe_packet(link, &packet),
            LinkEvent::Closed(link, reason) => {
                let Some(mut probe) = self.probes.remove(&link) else {
                    return;
                };
                if probe.hello.is_some() {
                    settle(&mut probe);
                } else if let Some(answer) = probe.answer.take() {
                    let reason = reason.unwrap_or_else(|| "closed before the hello".to_owned());
                    let _ = answer.send(Err(io::Error::new(io::ErrorKind::UnexpectedEof, reason)));
                }
            }
        }
    }

    fn probe_packet(&mut self, link: LinkId, packet: &Packet) {
        let our_public_address = self.our_public_address();
        let Some(probe) = self.probes.get_mut(&link) else {
            return;
        };
        let handle = probe.handle.clone();
        match (&probe.hello, probe.identity.as_mut()) {
            (None, _) => {
                if packet.protocol() != protocol::EDONKEY || packet.opcode() != client::HELLOANSWER {
                    return;
                }
                let Ok(hello) = Hello::decode(packet) else {
                    handle.close();
                    return;
                };
                let pinned = pinned_key(self.uploads.credits(), &hello);
                let (session, send) = self.identities.start(
                    &hello,
                    handle.remote().ip(),
                    handle.local(),
                    pinned,
                    our_public_address,
                    &mut self.random,
                );
                send_all(&handle, send);
                probe.hello = Some(hello);
                probe.identity = Some(session);
                handle.set_timeout(peer_greetings::IDLE_TIMEOUT);
            }
            (Some(_), Some(session)) => {
                if !identification(session, self.uploads.credits_mut(), &handle, packet)
                    && packet.protocol() == protocol::EDONKEY
                    && packet.opcode() == client::HELLO
                {
                    match self.shared.our_hello().to_peer_at(&handle.remote().ip()).encode(true) {
                        Ok(answer) => {
                            handle.send(answer);
                        }
                        Err(e) => tracing::warn!("Cannot answer {}: {e}", handle.remote()),
                    }
                }
            }
            (Some(_), None) => {}
        }
        if probe.identity.as_ref().is_some_and(Session::is_settled) {
            settle(probe);
        }
    }

    fn settle_probe(&mut self, link: LinkId) {
        if let Some(probe) = self.probes.get_mut(&link) {
            settle(probe);
        }
    }

    // Our public address, which signatures of the secure identification are bound to: a HighID, else the address the
    // server reports with a LowID.
    fn our_public_address(&self) -> Option<Ipv4Addr> {
        Some(self.address_seen_by_server()).filter(|ip| *ip != 0).map(Ipv4Addr::from)
    }

    fn new_link(&mut self, connection: tcp_connector::Connection, permit: Option<Permit>) -> LinkHandle {
        let id = self.next_link;
        self.next_link = self.next_link.saturating_add(1);
        let connection = connection.into_tcp_connection(frame_limits::client_handshake);
        let handle = peer_link::start(id, connection, self.link_events.clone());
        let incoming = permit.is_some();
        self.peers.insert(
            id,
            Peer {
                handle: handle.clone(),
                hello: None,
                identity: None,
                incoming,
                called_back: None,
                upload_for: None,
                _permit: permit,
            },
        );
        handle
    }

    fn link_event(&mut self, event: LinkEvent) {
        let ours = self.shared.our_hello();
        match event {
            LinkEvent::Packet(link, _) | LinkEvent::Closed(link, _) if self.probes.contains_key(&link) => {
                self.probe_event(event);
            }
            LinkEvent::Packet(link, packet) => self.peer_packet(link, &packet, &ours),
            LinkEvent::Closed(link, reason) => {
                let peer = self.peers.remove(&link);
                if let Some(user_hash) = peer.as_ref().and_then(|peer| peer.upload_for) {
                    self.uploads.call_failed(user_hash, reason.as_deref().unwrap_or("closed before the hello"));
                }
                if let Some(address) = peer.and_then(|peer| peer.called_back) {
                    tracing::debug!(
                        "Callback to {address} failed: {}",
                        reason.as_deref().unwrap_or("closed before the hello")
                    );
                    self.calling.remove(&address);
                }
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
                let upload_for = peer.upload_for.take();
                if let Some(address) = peer.called_back.take() {
                    tracing::debug!("Called {address} back");
                    handle.set_timeout(peer_greetings::IDLE_TIMEOUT);
                    self.calling.remove(&address);
                }
                self.met(&hello, handle.remote().ip());
                self.identify(link);
                if let Some(user_hash) = upload_for {
                    let trusted = self.trusted(link);
                    self.uploads.called(user_hash, &handle, &hello, trusted, Instant::now());
                    self.after_uploads();
                }
                self.downloads.greeted(link, hello, ours);
                return;
            }
            match ours.to_peer_at(&handle.remote().ip()).encode(true) {
                Ok(answer) => {
                    handle.send(answer);
                }
                Err(e) => tracing::warn!("Cannot answer {}: {e}", handle.remote()),
            }
            // Ourselves, or someone posing as us: it hears who we are, which ends a connection to ourselves.
            if hello.user_hash == ours.user_hash {
                handle.close();
                return;
            }
            self.met(&hello, handle.remote().ip());
            self.identify(link);
            let server_ip = self.shared.context.lock().map(|context| context.ip).unwrap_or(0);
            self.downloads.adopt(&handle, &hello, server_ip, &self.settings(), ours);
            return;
        };
        if let Some(session) = self.peers.get_mut(&link).and_then(|peer| peer.identity.as_mut())
            && identification(session, self.uploads.credits_mut(), &handle, packet)
        {
            return;
        }
        match peer_link::side_of(packet) {
            Side::Download if self.downloads.has_link(link) => {
                self.downloads.received(link, packet, ours);
            }
            Side::Upload => {
                let files = &self.shared_files;
                let visibility = self.preferences().shared_files_visibility;
                // Friends are not kept yet: none may look when only friends may.
                let visible =
                    || (visibility == SharedFilesVisibility::Everybody).then(|| files.files().cloned().collect());
                let find = |hash: &[u8; 16]| files.get(hash).cloned();
                let trusted = self
                    .peers
                    .get(&link)
                    .and_then(|peer| peer.identity.as_ref())
                    .is_none_or(|session| session.trust().credits());
                self.uploads.received(&handle, &hello, trusted, packet, ours, &find, &visible, Instant::now());
                self.after_uploads();
            }
            Side::Download | Side::Buddy => {
                tracing::debug!("Nothing on {} takes opcode {:#04x}", handle.remote(), packet.opcode());
            }
        }
    }

    // Who a greeted client said it is goes into the history, never its address. The countries are not shown yet.
    fn met(&mut self, hello: &Hello, _address: IpAddr) {
        let nick = hello.nick().unwrap_or_default();
        self.history.seen(hello.user_hash, nick, &peer_software::describe(hello), None, now_seconds());
    }

    // Whether credits apply to the client of a link: its identity proven, or it does not take part.
    fn trusted(&self, link: LinkId) -> bool {
        self.peers
            .get(&link)
            .and_then(|peer| peer.identity.as_ref())
            .is_none_or(|session| session.trust().credits())
    }

    // The clients whose turn came while they were not connected are called.
    fn after_uploads(&mut self) {
        for call in self.uploads.take_calls() {
            let events = self.peer_events.clone();
            let routes = self.shared.routes.clone();
            tokio::spawn(async move {
                let mut random = StdRng::from_rng(&mut rand::rng());
                let connection = tcp_connector::connect_to_client(
                    call.address,
                    call.obfuscate_for,
                    UPLOAD_CALL_TIMEOUT,
                    &routes,
                    &mut random,
                )
                .await;
                let _ = events.send(PeerEvent::UploadCall(call.user_hash, Box::new(connection)));
            });
        }
    }

    // The hellos are over: the client is asked to prove its user hash, against the key it proved to own before if any.
    fn identify(&mut self, link: LinkId) {
        let our_public_address = self.our_public_address();
        let Some(peer) = self.peers.get_mut(&link) else {
            return;
        };
        let Some(hello) = &peer.hello else {
            return;
        };
        let (remote, local) = (peer.handle.remote().ip(), peer.handle.local());
        let pinned = pinned_key(self.uploads.credits(), hello);
        let (session, send) = self.identities.start(hello, remote, local, pinned, our_public_address, &mut self.random);
        send_all(&peer.handle, send);
        peer.identity = Some(session);
    }

    fn settings(&self) -> download_manager::Settings {
        let preferences = self.preferences();
        download_manager::Settings {
            obfuscation: preferences.obfuscation,
            max_sources_per_file: MAX_SOURCES_PER_FILE,
            listen_port: self.shared.listen_port(),
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
        let events = self.peer_events.clone();
        let routes = self.shared.routes.clone();
        tokio::spawn(async move {
            let mut random = StdRng::from_rng(&mut rand::rng());
            let connection = tcp_connector::connect_to_client(
                address,
                obfuscate,
                peer_greetings::HELLO_TIMEOUT,
                &routes,
                &mut random,
            )
            .await;
            let _ = events.send(PeerEvent::CalledBack(address, Box::new(connection)));
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
        for probe in self.probes.values() {
            probe.handle.close();
        }
        self.with_session(|session, ctx| session.disconnect(ctx));
        self.stop_pinging();
        if let Some(saving) = self.saving.take() {
            saving.abort();
        }
        self.save_servers();
        self.uploads.close();
        self.history.save();
        self.close_sockets();
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
// Takes the connections other clients open; an obfuscated one is not taken yet.
// Clients come plain, or obfuscated with keys from our user hash; plain ones are refused while obfuscation is required.
// The guard admits each first, as for any incoming connection.
async fn accept(
    listener: TcpListener,
    events: mpsc::UnboundedSender<PeerEvent>,
    shared: Arc<Shared>,
    guard: Arc<ConnectionGuard>,
) {
    loop {
        let (stream, remote) = match listener.accept().await {
            Ok(accepted) => accepted,
            Err(e) => {
                tracing::warn!("Cannot accept a connection: {e}");
                continue;
            }
        };
        let permit = match guard.admit(remote.ip()) {
            Admission::Admitted(permit) => permit,
            Admission::Refused(reason) => {
                tracing::debug!("Refused {remote}: {reason:?}");
                continue;
            }
        };
        let events = events.clone();
        let shared = Arc::clone(&shared);
        tokio::spawn(async move {
            let allow_plain = shared.preferences.borrow().obfuscation != ObfuscationMode::Required;
            let mut random = StdRng::from_rng(&mut rand::rng());
            let accepted = tcp_connector::accept(
                stream,
                shared.user_hash,
                allow_plain,
                peer_greetings::HELLO_TIMEOUT,
                &mut random,
            )
            .await;
            match accepted {
                Ok(connection) => {
                    let _ = events.send(PeerEvent::Accepted(Box::new(connection), permit));
                }
                Err(e) => tracing::debug!("Closing a connection from {remote}: {e}"),
            }
        });
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

impl Drop for Network {
    fn drop(&mut self) {
        for task in [self.accepting.take(), self.following.take()].into_iter().flatten() {
            task.abort();
        }
    }
}

// The key the client's user hash proved to own before, if any.
fn pinned_key<'a>(credits: &'a Credits, hello: &Hello) -> Option<&'a [u8]> {
    credits.get(&hello.user_hash).map(|credit| credit.public_key.as_slice()).filter(|key| !key.is_empty())
}

fn send_all(handle: &LinkHandle, packets: Vec<Packet>) {
    for packet in packets {
        handle.send(packet);
    }
}

// A packet of the secure identification: answered, and a key proven pinned with the credits. False for another packet.
fn identification(session: &mut Session, credits: &mut Credits, handle: &LinkHandle, packet: &Packet) -> bool {
    let handled = match session.received(packet) {
        Ok(Some(handled)) => handled,
        Ok(None) => return false,
        Err(e) => {
            tracing::debug!("Bad identification packet from {}: {e}", handle.remote());
            handle.close();
            return true;
        }
    };
    send_all(handle, handled.send);
    match handled.outcome {
        Some(Identification::Identified { user_hash, public_key }) => {
            tracing::debug!("{} proved it owns the key of its user hash", handle.remote());
            credits.identified(user_hash, &public_key);
        }
        Some(Identification::Failed { .. }) => {
            tracing::debug!("{} failed to prove it owns the key of its user hash", handle.remote());
        }
        None => {}
    }
    true
}

// Tells what the client of a hello of ours said, with how far its identification went; once.
fn settle(probe: &mut Probe) {
    let (Some(hello), Some(answer)) = (&probe.hello, probe.answer.take()) else {
        return;
    };
    let mut info = peer_greetings::info(probe.handle.remote(), hello, probe.handle.is_obfuscated());
    info.identification = match &probe.identity {
        Some(session) if session.is_supported() => match session.trust() {
            Trust::Identified => PeerIdentification::Verified,
            Trust::Failed => PeerIdentification::Failed,
            Trust::Unsecured | Trust::Needed => PeerIdentification::Pending,
        },
        _ => PeerIdentification::NotSupported,
    };
    let _ = answer.send(Ok(info));
}

// The time in seconds since 1970, as the client history dates what it sees.
fn now_seconds() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |since| since.as_secs())
}

fn upload_settings(preferences: &Preferences) -> upload_manager::Settings {
    upload_manager::Settings {
        max_upload_kibps: preferences.max_upload_kibps,
        queue_size: usize::try_from(preferences.upload_queue_size).unwrap_or(usize::MAX),
        obfuscation: preferences.obfuscation != ObfuscationMode::Disabled,
    }
}
