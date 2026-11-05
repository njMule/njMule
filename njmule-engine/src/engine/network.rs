// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The network side of the engine: the server list, the session with a server and the UDP status of the others. One
//! task owns all of it; the API, the connection tasks and the timers reach it through its queues.

use std::collections::{HashMap, HashSet};
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::net::UdpSocket;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;

use crate::api::engine_event::EngineEvent;
use crate::api::server_info::ServerInfo;
use crate::config::preferences::{ObfuscationMode, Preferences};
use crate::engine::{ServerContext, Shared};
use crate::net::tcp_connector;
use crate::peer::peer_greetings;
use crate::persist::format::server_met::Server;
use crate::protocol::ed2k::server_messages::{self, CallbackRequested, Endpoint};
use crate::protocol::packet::{Packet, udp_framing};
use crate::protocol::tag::server_tag;
use crate::server::server_list::{self, Address, ServerList};
use crate::server::server_pinger::{self, Effect, ServerPinger};
use crate::server::server_session::{Context, Notice, Outcome, ServerSession};

/// How long changes of the list wait to be saved together.
const SAVE_DELAY: Duration = Duration::from_secs(30);
/// A client called back is not called again before this.
const CALLBACK_REPEAT: Duration = Duration::from_secs(60);
/// Callbacks open at once at most.
const MAX_CALLBACKS_OPEN: usize = 8;
// Crypt options of a callback request: the client asks for or requires obfuscation.
const CRYPT_REQUESTED_OR_REQUIRED: u8 = 0x06;
const MAX_DATAGRAM: usize = 8 * 1024;

/// What the API asks the network side.
#[derive(Debug)]
pub enum Command {
    ImportServers(Vec<Server>, oneshot::Sender<usize>),
    ConnectServer(Option<Address>, oneshot::Sender<Result<(), ConnectError>>),
    DisconnectServer(oneshot::Sender<()>),
    Servers(oneshot::Sender<Vec<ServerInfo>>),
    SetStaticServer(Address, bool, oneshot::Sender<bool>),
    Stop(oneshot::Sender<()>),
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
}

pub struct Network {
    shared: Arc<Shared>,
    servers: ServerList,
    session: ServerSession,
    pinger: ServerPinger,
    pinging: Option<JoinHandle<()>>,
    saving: Option<JoinHandle<()>>,
    udp: Option<Arc<UdpSocket>>,
    ticks: mpsc::UnboundedSender<Tick>,
    random: StdRng,
    callbacks: HashMap<SocketAddr, Instant>,
    calling: HashSet<SocketAddr>,
    our_ip: u32,
}

/// Starts the network side; it runs until it is asked to stop.
pub fn start(
    shared: Arc<Shared>,
    commands: mpsc::UnboundedReceiver<Command>,
    udp: Option<UdpSocket>,
) -> JoinHandle<()> {
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
    let udp = udp.map(Arc::new);
    let receiving = udp.clone().map(|socket| tokio::spawn(receive(socket, datagrams_in)));
    let network = Network {
        shared,
        servers,
        session: ServerSession::new(outcomes_in),
        pinger: ServerPinger::default(),
        pinging: None,
        saving: None,
        udp,
        ticks: ticks_in,
        random: StdRng::from_rng(&mut rand::rng()),
        callbacks: HashMap::new(),
        calling: HashSet::new(),
        our_ip: 0,
    };
    tokio::spawn(async move {
        network.run(commands, outcomes, ticks, datagrams).await;
        if let Some(receiving) = receiving {
            receiving.abort();
        }
    })
}

impl Network {
    async fn run(
        mut self,
        mut commands: mpsc::UnboundedReceiver<Command>,
        mut outcomes: mpsc::UnboundedReceiver<Outcome>,
        mut ticks: mpsc::UnboundedReceiver<Tick>,
        mut datagrams: mpsc::UnboundedReceiver<(SocketAddr, Packet)>,
    ) {
        loop {
            tokio::select! {
                command = commands.recv() => match command {
                    Some(Command::Stop(done)) => {
                        self.stop();
                        let _ = done.send(());
                        return;
                    }
                    Some(command) => self.command(command),
                    None => return,
                },
                Some(outcome) = outcomes.recv() => self.outcome(outcome),
                Some(tick) = ticks.recv() => self.tick(tick),
                Some((from, packet)) = datagrams.recv() => self.datagram(from, &packet),
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
            Notice::SearchResults { .. } | Notice::FoundSources(_) => {}
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
        }
    }

    fn datagram(&mut self, from: SocketAddr, packet: &Packet) {
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
        if let Err(e) = udp.try_send_to(&udp_framing::encode(packet), target) {
            tracing::debug!("Cannot send a datagram to {target}: {e}");
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
        tokio::spawn(async move {
            let mut random = StdRng::from_rng(&mut rand::rng());
            match tcp_connector::connect_to_client(address, obfuscate, peer_greetings::HELLO_TIMEOUT, &mut random).await
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
        self.with_session(|session, ctx| session.disconnect(ctx));
        self.stop_pinging();
        if let Some(saving) = self.saving.take() {
            saving.abort();
        }
        self.save_servers();
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

// A server the user gave.
fn by_hand(address: &Address) -> Server {
    Server { ip: address.ip, port: address.port, tags: Vec::new() }
}

// Reads the server datagrams: plain eD2K packets from the servers' UDP ports.
async fn receive(socket: Arc<UdpSocket>, datagrams: mpsc::UnboundedSender<(SocketAddr, Packet)>) {
    let mut buffer = vec![0u8; MAX_DATAGRAM];
    loop {
        match socket.recv_from(&mut buffer).await {
            Ok((length, from)) => {
                let Some(data) = buffer.get(..length) else {
                    continue;
                };
                match udp_framing::decode(data) {
                    Ok(packet) if packet.protocol() == crate::protocol::packet::protocol::EDONKEY => {
                        if datagrams.send((from, packet)).is_err() {
                            return;
                        }
                    }
                    Ok(_) => {}
                    Err(_) => tracing::debug!("Dropping a malformed datagram from {from}"),
                }
            }
            // On Windows an ICMP error for an earlier datagram fails a receive: harmless.
            Err(e) => tracing::debug!("Server UDP: {e}"),
        }
    }
}
