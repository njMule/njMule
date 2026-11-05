// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The engine: one profile, its preferences and identity, the listening socket, the network side, and the events its
//! front ends follow.

pub mod network;

use std::collections::BTreeMap;
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};

use tokio::net::{TcpListener, UdpSocket};
use tokio::sync::{broadcast, mpsc, oneshot, watch};
use tokio::task::JoinSet;

use crate::api::engine_event::EngineEvent;
use crate::api::nj_mule_info;
use crate::api::peer_info::PeerInfo;
use crate::api::server_info::ServerInfo;
use crate::config::app_directories::AppDirectories;
use crate::config::preferences::{ObfuscationMode, Preferences, SharedFilesVisibility};
use crate::engine::network::{Command, ConnectError};
use crate::peer::peer_greetings;
use crate::persist::format::server_met::Server;
use crate::persist::{preferences_codec, user_hash_store};
use crate::protocol::ed2k::hello::{self, Hello, Ours};
use crate::server::server_list::Address;

/// Events kept for a front end that reads them late; one that falls further behind loses the stream.
const EVENT_BACKLOG: usize = 1024;
/// The comments version announced in the hello.
const COMMENTS_VERSION: u32 = 1;
/// The way of proving user hashes announced in the hello.
const SECURE_IDENT: u32 = 3;

/// A running engine; clones share it.
#[derive(Clone)]
pub struct Engine {
    shared: Arc<Shared>,
}

/// The server we are on, as our hello tells it: our id and its address; zeros when on none.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ServerContext {
    pub client_id: u32,
    pub ip: u32,
    pub port: u16,
}

pub struct Shared {
    dirs: AppDirectories,
    user_hash: [u8; 16],
    listen_port: u16,
    preferences: watch::Sender<Preferences>,
    events: broadcast::Sender<EngineEvent>,
    context: Mutex<ServerContext>,
    commands: mpsc::UnboundedSender<Command>,
    stop: watch::Sender<bool>,
    tasks: tokio::sync::Mutex<JoinSet<()>>,
}

impl Shared {
    /// Our greeting as it stands: on the server we are on, not on Kad yet.
    pub fn our_hello(&self) -> Hello {
        let preferences = self.preferences.borrow().clone();
        let context = self.context.lock().map(|context| *context).unwrap_or_default();
        let mut misc_options1 = 1 << hello::MISC1_AICH_SHIFT
            | hello::MISC1_UNICODE
            | COMMENTS_VERSION << 4
            | hello::MISC1_DATA_COMPRESSION
            | SECURE_IDENT << 16;
        // Clients do not ask for files that nobody may see.
        if preferences.shared_files_visibility == SharedFilesVisibility::Nobody {
            misc_options1 |= hello::MISC1_NO_VIEW_SHARED_FILES;
        }
        let misc_options2 = hello::MISC2_LARGE_FILES
            | hello::MISC2_SOURCE_EXCHANGE2
            | hello::MISC2_SUPPORTS_CAPTCHA
            | crypt_options(preferences.obfuscation);
        let ours = Ours { nick: &preferences.nick, udp_port: 0, kad_port: 0, misc_options1, misc_options2 };
        Hello::of(self.user_hash, context.client_id, self.listen_port, &ours, context.ip, context.port)
    }
}

impl Engine {
    /// Opens the profile under `dirs`, starts listening for clients and starts the network side.
    pub async fn start(dirs: AppDirectories) -> io::Result<Self> {
        dirs.create_all()?;
        let preferences = preferences_codec::load(&dirs.config.join(preferences_codec::FILE_NAME))?;
        let user_hash = user_hash_store::load_or_create(&dirs.config, &mut rand::rng())?;
        let listener = match TcpListener::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, preferences.tcp_port))).await {
            Ok(listener) => Some(listener),
            Err(e) => {
                // Other clients cannot reach us: we get a low id.
                tracing::warn!("Cannot listen on TCP port {}: {e}", preferences.tcp_port);
                None
            }
        };
        // The servers' UDP answers come to a port of their own.
        let udp = match UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await {
            Ok(socket) => Some(socket),
            Err(e) => {
                tracing::warn!("Cannot open the server UDP socket: {e}");
                None
            }
        };
        let listen_port = listener.as_ref().and_then(|l| l.local_addr().ok()).map_or(0, |address| address.port());
        let (events, _) = broadcast::channel(EVENT_BACKLOG);
        let (commands, commands_out) = mpsc::unbounded_channel();
        let shared = Arc::new(Shared {
            dirs,
            user_hash,
            listen_port,
            preferences: watch::Sender::new(preferences),
            events,
            context: Mutex::new(ServerContext::default()),
            commands,
            stop: watch::Sender::new(false),
            tasks: tokio::sync::Mutex::new(JoinSet::new()),
        });
        let engine = Self { shared: shared.clone() };
        let network = network::start(shared, commands_out, udp);
        {
            let mut tasks = engine.shared.tasks.lock().await;
            tasks.spawn(async move {
                let _ = network.await;
            });
            if let Some(listener) = listener {
                let accepting = engine.clone();
                tasks.spawn(async move { accepting.accept(listener).await });
            }
        }
        engine.publish(EngineEvent::Started { version: nj_mule_info::version().to_owned() });
        Ok(engine)
    }

    pub fn directories(&self) -> &AppDirectories {
        &self.shared.dirs
    }

    /// The TCP port clients reach us on; 0 when we could not listen.
    pub fn listen_port(&self) -> u16 {
        self.shared.listen_port
    }

    pub fn user_hash(&self) -> [u8; 16] {
        self.shared.user_hash
    }

    pub fn preferences(&self) -> Preferences {
        self.shared.preferences.borrow().clone()
    }

    /// Changes the preferences named in `changes`, all or none, and saves them.
    pub fn update_preferences(&self, changes: &BTreeMap<String, String>) -> Result<Preferences, String> {
        let changed = preferences_codec::apply(&self.preferences(), changes)?;
        let file = self.shared.dirs.config.join(preferences_codec::FILE_NAME);
        if let Err(e) = preferences_codec::save(&file, &changed) {
            tracing::warn!("Cannot save {}: {e}", file.display());
        }
        self.shared.preferences.send_replace(changed.clone());
        self.publish(EngineEvent::PreferencesChanged);
        Ok(changed)
    }

    pub fn subscribe(&self) -> broadcast::Receiver<EngineEvent> {
        self.shared.events.subscribe()
    }

    /// Says hello to a client and answers what it said.
    pub async fn hello(&self, address: SocketAddr) -> io::Result<PeerInfo> {
        peer_greetings::hello(address, self.shared.our_hello()).await
    }

    /// Adds the servers not known yet; answers how many.
    pub async fn import_servers(&self, servers: Vec<Server>) -> usize {
        self.ask(|reply| Command::ImportServers(servers, reply)).await.unwrap_or(0)
    }

    /// Connects to the server at `address` (added to the list if new), or to any known one.
    pub async fn connect_server(&self, address: Option<Address>) -> Result<(), ConnectError> {
        self.ask(|reply| Command::ConnectServer(address, reply)).await.unwrap_or(Err(ConnectError::NoServer))
    }

    pub async fn disconnect_server(&self) {
        let _ = self.ask(Command::DisconnectServer).await;
    }

    pub async fn servers(&self) -> Vec<ServerInfo> {
        self.ask(Command::Servers).await.unwrap_or_default()
    }

    /// Puts a known server in the static list or takes it out; false for an unknown server.
    pub async fn set_static_server(&self, address: Address, wanted: bool) -> bool {
        self.ask(|reply| Command::SetStaticServer(address, wanted, reply)).await.unwrap_or(false)
    }

    /// Completes once the engine stops.
    pub fn stopped(&self) -> impl Future<Output = ()> + Send + use<> {
        let mut stop = self.shared.stop.subscribe();
        async move {
            let _ = stop.wait_for(|stopped| *stopped).await;
        }
    }

    /// Stops the network side, which saves what it keeps, and the listening, and waits for the tasks to end.
    pub async fn stop(&self) {
        self.publish(EngineEvent::Stopping);
        let _ = self.ask(Command::Stop).await;
        self.shared.stop.send_replace(true);
        let mut tasks = self.shared.tasks.lock().await;
        tasks.abort_all();
        while tasks.join_next().await.is_some() {}
    }

    async fn ask<T>(&self, command: impl FnOnce(oneshot::Sender<T>) -> Command) -> Option<T> {
        let (reply, answer) = oneshot::channel();
        self.shared.commands.send(command(reply)).ok()?;
        answer.await.ok()
    }

    fn publish(&self, event: EngineEvent) {
        // Without a front end following, nobody misses the event.
        let _ = self.shared.events.send(event);
    }

    async fn accept(&self, listener: TcpListener) {
        let mut stop = self.shared.stop.subscribe();
        let mut connections = JoinSet::new();
        loop {
            tokio::select! {
                _ = stop.wait_for(|stopped| *stopped) => break,
                accepted = listener.accept() => match accepted {
                    Ok((stream, _)) => {
                        let ours = self.shared.our_hello();
                        connections.spawn(async move {
                            if let Err(e) = peer_greetings::accepted(stream, ours).await {
                                tracing::debug!("Client connection ended: {e}");
                            }
                        });
                    }
                    Err(e) => tracing::warn!("Cannot accept a connection: {e}"),
                },
                Some(_) = connections.join_next(), if !connections.is_empty() => {}
            }
        }
        connections.abort_all();
    }
}

fn crypt_options(mode: ObfuscationMode) -> u32 {
    match mode {
        ObfuscationMode::Disabled => 0,
        ObfuscationMode::Supported => hello::MISC2_SUPPORTS_CRYPT,
        ObfuscationMode::Requested => hello::MISC2_SUPPORTS_CRYPT | hello::MISC2_REQUESTS_CRYPT,
        ObfuscationMode::Required => {
            hello::MISC2_SUPPORTS_CRYPT | hello::MISC2_REQUESTS_CRYPT | hello::MISC2_REQUIRES_CRYPT
        }
    }
}
