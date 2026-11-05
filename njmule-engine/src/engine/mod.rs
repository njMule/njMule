// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The engine: one profile, its preferences and identity, the listening socket, and the events its front ends follow.

use std::collections::BTreeMap;
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;

use tokio::net::TcpListener;
use tokio::sync::{broadcast, watch};
use tokio::task::JoinSet;

use crate::api::engine_event::EngineEvent;
use crate::api::nj_mule_info;
use crate::api::peer_info::PeerInfo;
use crate::config::app_directories::AppDirectories;
use crate::config::preferences::{ObfuscationMode, Preferences, SharedFilesVisibility};
use crate::peer::peer_greetings;
use crate::persist::{preferences_codec, user_hash_store};
use crate::protocol::ed2k::hello::{self, Hello, Ours};

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

struct Shared {
    dirs: AppDirectories,
    user_hash: [u8; 16],
    listen_port: u16,
    preferences: watch::Sender<Preferences>,
    events: broadcast::Sender<EngineEvent>,
    stop: watch::Sender<bool>,
    tasks: tokio::sync::Mutex<JoinSet<()>>,
}

impl Engine {
    /// Opens the profile under `dirs` and starts listening for clients.
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
        let listen_port = listener.as_ref().and_then(|l| l.local_addr().ok()).map_or(0, |address| address.port());
        let (events, _) = broadcast::channel(EVENT_BACKLOG);
        let engine = Self {
            shared: Arc::new(Shared {
                dirs,
                user_hash,
                listen_port,
                preferences: watch::Sender::new(preferences),
                events,
                stop: watch::Sender::new(false),
                tasks: tokio::sync::Mutex::new(JoinSet::new()),
            }),
        };
        if let Some(listener) = listener {
            let accepting = engine.clone();
            engine.shared.tasks.lock().await.spawn(async move { accepting.accept(listener).await });
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
        peer_greetings::hello(address, self.our_hello()).await
    }

    /// Stops listening and waits for the engine's tasks to end.
    pub async fn stop(&self) {
        self.publish(EngineEvent::Stopping);
        self.shared.stop.send_replace(true);
        let mut tasks = self.shared.tasks.lock().await;
        tasks.abort_all();
        while tasks.join_next().await.is_some() {}
    }

    /// Completes once the engine stops.
    pub fn stopped(&self) -> impl Future<Output = ()> + Send + use<> {
        let mut stop = self.shared.stop.subscribe();
        async move {
            let _ = stop.wait_for(|stopped| *stopped).await;
        }
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
                        let ours = self.our_hello();
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

    /// Our greeting as it stands: not on a server and not on Kad yet.
    fn our_hello(&self) -> Hello {
        let preferences = self.preferences();
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
        Hello::of(self.shared.user_hash, 0, self.shared.listen_port, &ours, 0, 0)
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
