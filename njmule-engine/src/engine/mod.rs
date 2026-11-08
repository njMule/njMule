// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The engine: one profile, its preferences and identity, the listening socket, the network side, and the events its
//! front ends follow.

pub mod countries;
pub mod event_replay;
mod import_target;
mod instance_lock;
pub mod ip_filter_lists;
pub mod network;
pub mod port_check;
pub mod scheduler;
pub mod server_lists;
pub mod setup_mark;
pub mod switches;
pub mod totals;

use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU16, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Instant, SystemTime};

use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::net::TcpListener;
use tokio::sync::{broadcast, mpsc, oneshot, watch};
use tokio::task::JoinSet;

use crate::api::category::Category;
use crate::api::download_info::{DownloadInfo, DownloadPriority, SourceInfo};
use crate::api::ed2k_link::FileLink;
use crate::api::emule_history::EmuleHistory;
use crate::api::engine_event::EngineEvent;
use crate::api::engine_status::EngineStatus;
use crate::api::file_comment::FileComment;
use crate::api::file_history::FileHistory;
use crate::api::import_options::ImportOptions;
use crate::api::import_report::ImportReport;
use crate::api::kad_contact_info::KadContactInfo;
use crate::api::kad_info::KadInfo;
use crate::api::kad_note::KadNote;
use crate::api::kad_search_info::KadSearchInfo;
use crate::api::known_client::KnownClient;
use crate::api::list_source::{ListError, ListSource};
use crate::api::log_line::{Level, LogLine};
use crate::api::nj_mule_engine::{NjMuleEngine, Subscription};
use crate::api::nj_mule_info;
use crate::api::peer_comment::PeerComment;
use crate::api::peer_info::PeerInfo;
use crate::api::port_test::PortTest;
use crate::api::saved_search::SavedSearch;
use crate::api::search_result::SearchResult;
use crate::api::server_info::ServerInfo;
use crate::api::shared_directory::SharedDirectory;
use crate::api::shared_file_info::SharedFileInfo;
use crate::api::spam_marks::SpamMarks;
use crate::api::upload_info::{BanInfo, UploadInfo};
use crate::api::upload_priority::UploadPriority;
use crate::config::app_directories::AppDirectories;
use crate::config::bind_interface::{self, Binding};
use crate::config::obfuscation_mode::ObfuscationMode;
use crate::config::preferences::Preferences;
use crate::config::schedule::{Rule, Schedule};
use crate::config::shared_files_visibility::SharedFilesVisibility;
use crate::engine::countries::Step;
use crate::engine::event_replay::EventReplay;
use crate::engine::import_target::ImportTarget;
use crate::engine::instance_lock::InstanceLock;
use crate::engine::network::{Command, ConnectError, DownloadChange, MoreAnswer};
use crate::engine::switches::Switches;
use crate::net::country_table::CountryTable;
use crate::net::routes::Routes;
use crate::net::web_lists::WebLists;
use crate::peer::{peer_greetings, shared_files_browser};
use crate::persist::emule_importer::EmuleImporter;
use crate::persist::format::known_met::KnownFile;
use crate::persist::format::server_met::{self, Server};
use crate::persist::format::{known2_met, nodes_dat};
use crate::persist::{atomic_files, emule_importer, preferences_codec, user_hash_store};
use crate::protocol::ed2k::client_reasks;
use crate::protocol::ed2k::hello::{self, Hello, Ours};
use crate::protocol::ed2k::server_messages::LOW_ID_LIMIT;
use crate::protocol::kad::kad_messages;
use crate::search::search_manager::{Scope, StartError};
use crate::server::server_list::{Address, Priority};
use crate::server::server_udp::ServerUdp;

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
    // 0 while nothing listens, as while the chosen network interface is gone.
    listen_port: AtomicU16,
    // What our hello tells of Kad while it is connected: its UDP port (low 16 bits), and whether firewalled clients may
    // call us back over it directly (bit 16); 0 otherwise.
    kad_announced: AtomicU32,
    // Our Kad buddy's IPv4 address and UDP port while firewalled with one, as `ip << 16 | port`; 0 otherwise.
    kad_buddy: AtomicU64,
    preferences: watch::Sender<Preferences>,
    routes: Routes,
    events: broadcast::Sender<EngineEvent>,
    context: Mutex<ServerContext>,
    // What a late follower missed, recorded as the events are sent.
    replay: Mutex<EventReplay>,
    // The countries of addresses, empty while unknown or turned off.
    countries: watch::Sender<Arc<CountryTable>>,
    // Set with the event that tells the stop, the last one sent.
    ended: AtomicBool,
    commands: mpsc::UnboundedSender<Command>,
    stop: watch::Sender<bool>,
    tasks: tokio::sync::Mutex<JoinSet<()>>,
    // The lock of the configuration, let go once the engine stopped.
    instance: Mutex<Option<InstanceLock>>,
    // Whether an eMule runs, which keeps an imported identity waiting.
    emule_running: fn() -> bool,
}

impl Shared {
    // Recorded and sent at once, so that a follower gets each event once: missed or live. Once the engine stops,
    // the events go nowhere: the last one tells the stop.
    fn publish(&self, event: EngineEvent) {
        let mut replay = self.replay.lock().unwrap_or_else(PoisonError::into_inner);
        if self.ended.load(Ordering::Relaxed) {
            return;
        }
        if event == EngineEvent::Stopping {
            self.ended.store(true, Ordering::Relaxed);
        }
        replay.record(&event);
        // Without a front end following, nobody misses the event.
        let _ = self.events.send(event);
    }

    fn subscribe(&self) -> Subscription {
        let replay = self.replay.lock().unwrap_or_else(PoisonError::into_inner);
        Subscription { missed: replay.missed(), live: self.events.subscribe() }
    }

    /// Tells the user, through the log and the front ends.
    fn user_log(&self, level: Level, message: String) {
        match level {
            Level::Debug => tracing::debug!("{message}"),
            Level::Info | Level::Success => tracing::info!("{message}"),
            Level::Warning => tracing::warn!("{message}"),
            Level::Error => tracing::error!("{message}"),
        }
        self.publish(EngineEvent::Log(LogLine::now(level, message)));
    }

    fn listen_port(&self) -> u16 {
        self.listen_port.load(Ordering::Relaxed)
    }

    /// Tells our hello Kad's UDP port while Kad is connected, 0 otherwise, and whether firewalled clients may call us
    /// back over it directly.
    fn announce_kad(&self, udp_port: u16, direct_callback: bool) {
        let announced = if udp_port == 0 { 0 } else { u32::from(udp_port) | u32::from(direct_callback) << 16 };
        self.kad_announced.store(announced, Ordering::Relaxed);
    }

    /// Tells our hello the address and UDP port of our Kad buddy while we are firewalled with one, 0 otherwise.
    fn announce_buddy(&self, ip: u32, udp_port: u16) {
        self.kad_buddy.store(u64::from(ip) << 16 | u64::from(udp_port), Ordering::Relaxed);
    }

    /// Our greeting as it stands: on the server we are on, and on Kad while it is connected, which takes re-asks over
    /// its UDP port.
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
        let mut misc_options2 = hello::MISC2_LARGE_FILES
            | hello::MISC2_SOURCE_EXCHANGE2
            | hello::MISC2_SUPPORTS_CAPTCHA
            | crypt_options(preferences.obfuscation);
        let announced = self.kad_announced.load(Ordering::Relaxed);
        let kad_port = u16::try_from(announced & 0xFFFF).unwrap_or(0);
        if kad_port != 0 {
            misc_options1 |= client_reasks::UDP_VERSION << hello::MISC1_UDP_VERSION_SHIFT;
            misc_options2 |= u32::from(kad_messages::VERSION);
            if announced >> 16 & 1 != 0 {
                misc_options2 |= hello::MISC2_DIRECT_UDP_CALLBACK;
            }
        }
        let ours = Ours { nick: &preferences.nick, udp_port: kad_port, kad_port, misc_options1, misc_options2 };
        let hello = Hello::of(self.user_hash, context.client_id, self.listen_port(), &ours, context.ip, context.port);
        // Firewalled with a buddy: clients that know us reach us through it.
        let buddy = self.kad_buddy.load(Ordering::Relaxed);
        let buddy_ip = u32::try_from(buddy >> 16).unwrap_or(0);
        let buddy_port = u16::try_from(buddy & 0xFFFF).unwrap_or(0);
        if buddy_ip != 0 && context.client_id < LOW_ID_LIMIT { hello.with_buddy(buddy_ip, buddy_port) } else { hello }
    }
}

impl Engine {
    /// Opens the profile under `dirs`, starts listening for clients and starts the network side; it reaches no third
    /// party on its own.
    pub async fn start(dirs: AppDirectories) -> io::Result<Self> {
        Self::start_with(dirs, Switches::QUIET).await
    }

    /// As [`Engine::start`], with what the `switches` allow.
    pub async fn start_with(dirs: AppDirectories, switches: Switches) -> io::Result<Self> {
        dirs.create_all()?;
        // Before touching any file: another engine may be using them.
        let instance = InstanceLock::acquire(&dirs.config)?;
        let preferences_file = dirs.config.join(preferences_codec::FILE_NAME);
        atomic_files::cleanup_interrupted_write(&preferences_file);
        let created = !preferences_file.exists();
        let preferences = preferences_codec::load(&preferences_file)?;
        // Where the downloads go, complete and in parts, which need not be the default folders.
        fs::create_dir_all(preferences.effective_incoming_dir(&dirs))?;
        fs::create_dir_all(preferences.effective_temp_dir(&dirs))?;
        preferences_codec::save(&preferences_file, &preferences)?;
        if created {
            setup_mark::make(&dirs.config)?;
        }
        emule_importer::apply_pending_identity(&dirs.config, &switches.emule_running)?;
        emule_importer::apply_pending_files(&dirs.config);
        let user_hash = user_hash_store::load_or_create(&dirs.config, &mut rand::rng())?;
        // What the user's log tells of the start, once the front ends can follow it.
        let mut told = Vec::new();
        // Sockets go on the network interface of the preferences; while it is gone, none opens and nothing connects.
        let local = match bind_interface::resolve_now(&preferences.bind_interface) {
            Binding::Any => Some(None),
            Binding::Bound(address) => {
                told.push((Level::Info, format!("Connections go through {} ({address})", preferences.bind_interface)));
                Some(Some(address))
            }
            Binding::Missing(choice) => {
                told.push((Level::Warning, network::offline(&choice)));
                None
            }
        };
        let listener = match local {
            Some(local) => {
                let address = SocketAddr::from((local.unwrap_or(Ipv4Addr::UNSPECIFIED), preferences.tcp_port));
                match TcpListener::bind(address).await {
                    Ok(listener) => Some(listener),
                    Err(e) => {
                        // Other clients cannot reach us: we get a low id.
                        told.push((Level::Warning, format!("Cannot listen on TCP port {}: {e}", preferences.tcp_port)));
                        None
                    }
                }
            }
            None => None,
        };
        // The servers' UDP answers come to a port of their own, not the one Kad and clients use.
        let udp = match local {
            Some(local) => match ServerUdp::bind_beside(preferences.udp_port, local).await {
                Ok(socket) => Some(socket),
                Err(e) => {
                    told.push((Level::Warning, format!("No server UDP socket: global searches are off ({e})")));
                    None
                }
            },
            None => None,
        };
        let listen_port = listener.as_ref().and_then(|l| l.local_addr().ok()).map_or(0, |address| address.port());
        let (events, _) = broadcast::channel(EVENT_BACKLOG);
        let (commands, commands_out) = mpsc::unbounded_channel();
        let preferences = watch::Sender::new(preferences);
        let proxy = preferences.subscribe();
        let interface = preferences.subscribe();
        // Read at each connection: the interface may go, as a VPN that drops, or come back with another address.
        let routes = Routes::new(
            move || proxy.borrow().proxy.clone(),
            move || bind_interface::resolve_now(&interface.borrow().bind_interface),
        );
        let shared = Arc::new(Shared {
            dirs,
            user_hash,
            listen_port: AtomicU16::new(listen_port),
            kad_announced: AtomicU32::new(0),
            kad_buddy: AtomicU64::new(0),
            preferences,
            routes,
            events,
            context: Mutex::new(ServerContext::default()),
            replay: Mutex::default(),
            countries: watch::Sender::new(Arc::new(CountryTable::empty())),
            ended: AtomicBool::new(false),
            commands,
            stop: watch::Sender::new(false),
            tasks: tokio::sync::Mutex::new(JoinSet::new()),
            instance: Mutex::new(Some(instance)),
            emule_running: switches.emule_running,
        });
        let engine = Self { shared: shared.clone() };
        let network = network::start(shared, commands_out, udp, listener)?;
        engine.shared.tasks.lock().await.spawn(async move {
            let _ = network.await;
        });
        engine.publish(EngineEvent::Started { version: nj_mule_info::version().to_owned() });
        for (level, message) in told {
            engine.user_log(level, message);
        }
        engine.user_log(Level::Info, format!("{} {} started", nj_mule_info::NAME, nj_mule_info::version()));
        let following = engine.clone();
        engine.shared.tasks.lock().await.spawn(async move { following.follow_countries(switches).await });
        if engine.preferences().update_servers_at_start {
            let updating = engine.clone();
            engine.shared.tasks.lock().await.spawn(async move { updating.update_server_lists().await });
        }
        Ok(engine)
    }

    // The table of countries, looked at shortly after the start, then every hour and as the preferences turn it on or
    // off.
    async fn follow_countries(&self, switches: Switches) {
        let mut preferences = self.shared.preferences.subscribe();
        let mut showing = preferences.borrow_and_update().show_countries;
        let mut tried = None;
        tokio::time::sleep(countries::FIRST_CHECK).await;
        loop {
            self.update_countries(switches, &mut tried).await;
            let next = tokio::time::sleep(countries::CHECK);
            tokio::pin!(next);
            loop {
                tokio::select! {
                    () = &mut next => break,
                    changed = preferences.changed() => {
                        if changed.is_err() {
                            return;
                        }
                        let now = preferences.borrow_and_update().show_countries;
                        if now != showing {
                            showing = now;
                            break;
                        }
                    }
                }
            }
        }
    }

    async fn update_countries(&self, switches: Switches, tried: &mut Option<Instant>) {
        if !self.preferences().show_countries {
            self.shared.countries.send_replace(Arc::new(CountryTable::empty()));
            return;
        }
        let cache = self.shared.dirs.cache.clone();
        let held = self.shared.countries.borrow().size() > 0;
        let table =
            match countries::step(switches.countries, countries::written(&cache), SystemTime::now(), *tried, held) {
                Step::Nothing => return,
                Step::Read => tokio::task::spawn_blocking(move || countries::read(&cache))
                    .await
                    .map_err(|e| e.to_string())
                    .and_then(|read| read),
                Step::Fetch => {
                    *tried = Some(Instant::now());
                    let web = WebLists::create(self.shared.routes.clone());
                    let fetched = countries::fetch(&web, &cache).await;
                    if let Ok(table) = &fetched {
                        self.user_log(
                            Level::Info,
                            format!("Country database updated: {} ranges (IP Geolocation by DB-IP)", table.size()),
                        );
                    }
                    fetched
                }
            };
        match table {
            // Turned off meanwhile, the table is let go.
            Ok(table) if self.preferences().show_countries => {
                self.shared.countries.send_replace(Arc::new(table));
            }
            Ok(_) => {
                self.shared.countries.send_replace(Arc::new(CountryTable::empty()));
            }
            Err(e) => self.user_log(Level::Warning, format!("Country database not updated: {e}")),
        }
    }

    fn countries(&self) -> Arc<CountryTable> {
        self.shared.countries.borrow().clone()
    }

    // The server lists of addresses.dat, taken again at each start when the preferences ask for it; each tells its
    // outcome in the log.
    async fn update_server_lists(&self) {
        for url in self.server_list_addresses().await {
            let host = server_lists::host(&url);
            match self.add_servers(ListSource::Web(url)).await {
                Ok(added) => self.user_log(Level::Info, format!("{added} new servers from {host}")),
                Err(e) => self.user_log(Level::Warning, format!("Cannot update the server list from {host}: {e}")),
            }
        }
    }

    pub fn directories(&self) -> &AppDirectories {
        &self.shared.dirs
    }

    /// The TCP port clients reach us on; 0 when we could not listen.
    pub fn listen_port(&self) -> u16 {
        self.shared.listen_port()
    }

    pub fn user_hash(&self) -> [u8; 16] {
        self.shared.user_hash
    }

    pub fn preferences(&self) -> Preferences {
        self.shared.preferences.borrow().clone()
    }

    /// Changes the preferences named in `changes`, all or none, and saves them; preferences as they were change
    /// nothing.
    pub fn update_preferences(&self, changes: &BTreeMap<String, String>) -> Result<Preferences, String> {
        self.change_preferences(&|current| preferences_codec::apply(current, changes))
    }

    /// Changes the preferences as `change` makes them from those of now, and saves them; preferences that are not valid
    /// are refused, and preferences as they were change nothing.
    pub fn change_preferences(
        &self,
        change: &dyn Fn(&Preferences) -> Result<Preferences, String>,
    ) -> Result<Preferences, String> {
        let current = self.preferences();
        let changed = change(&current)?.validated()?;
        if changed == current {
            return Ok(changed);
        }
        let file = self.shared.dirs.config.join(preferences_codec::FILE_NAME);
        if let Err(e) = preferences_codec::save(&file, &changed) {
            tracing::warn!("Cannot save {}: {e}", file.display());
        }
        self.shared.preferences.send_replace(changed.clone());
        self.publish(EngineEvent::PreferencesChanged);
        Ok(changed)
    }

    /// The events from now on, after the latest lines of the log and the server's state.
    pub fn subscribe(&self) -> Subscription {
        self.shared.subscribe()
    }

    /// Tells the user, through the log and the front ends.
    pub fn user_log(&self, level: Level, message: impl Into<String>) {
        self.shared.user_log(level, message.into());
    }

    /// Says hello to a client and answers what it said, and whether it proved its user hash.
    pub async fn hello(&self, address: SocketAddr) -> io::Result<PeerInfo> {
        // Past the hello and the identification, the engine is not answering.
        let wait = peer_greetings::HELLO_TIMEOUT.saturating_add(peer_greetings::IDENTIFY_WAIT).saturating_mul(2);
        let asked = self.ask(|answer| Command::Hello(address, answer));
        match tokio::time::timeout(wait, asked).await {
            Ok(Some(answer)) => answer,
            Ok(None) => Err(io::Error::other("the engine is stopping")),
            Err(_) => Err(io::Error::new(io::ErrorKind::TimedOut, "no answer in time")),
        }
    }

    /// The files the client listening at `address` shares, when it shows them.
    pub async fn browse(&self, address: SocketAddr) -> io::Result<Vec<SearchResult>> {
        let mut random = StdRng::from_rng(&mut rand::rng());
        shared_files_browser::browse(address, &self.shared.our_hello(), &self.shared.routes, &mut random).await
    }

    /// The clients met in the last 90 days, those met last first.
    pub async fn known_clients(&self) -> Vec<KnownClient> {
        self.ask(Command::KnownClients).await.unwrap_or_default()
    }

    /// Adds the servers of a server.met not known yet; answers how many.
    pub async fn add_servers(&self, source: ListSource) -> Result<usize, ListError> {
        let servers = server_lists::read(source, self.shared.routes.clone(), |data| {
            server_met::read(data).map_err(|e| format!("not a server.met: {e}"))
        })
        .await?;
        Ok(self.import_servers(servers).await)
    }

    /// Adds the servers not known yet; answers how many.
    pub async fn import_servers(&self, servers: Vec<Server>) -> usize {
        self.ask(|reply| Command::ImportServers(servers, reply)).await.unwrap_or(0)
    }

    /// Adds a server the user gave; false when it is known already.
    pub async fn add_server(&self, address: Address) -> bool {
        self.ask(|reply| Command::AddServer(address, reply)).await.unwrap_or(false)
    }

    /// Removes a server, from the static list too; false when it is unknown.
    pub async fn remove_server(&self, address: Address) -> bool {
        self.ask(|reply| Command::RemoveServer(address, reply)).await.unwrap_or(false)
    }

    /// Sets the priority a server is tried with; false when it is unknown.
    pub async fn set_server_priority(&self, address: Address, priority: Priority) -> bool {
        self.ask(|reply| Command::SetServerPriority(address, priority, reply)).await.unwrap_or(false)
    }

    /// The addresses of the server lists, which the engine takes again at its start when the preferences ask for it.
    pub async fn server_list_addresses(&self) -> Vec<String> {
        let config = self.shared.dirs.config.clone();
        let read = tokio::task::spawn_blocking(move || server_lists::load_addresses(&config)).await;
        match read {
            Ok(Ok(addresses)) => addresses,
            Ok(Err(e)) => {
                tracing::warn!("Cannot read {}: {e}", server_lists::ADDRESSES_FILE);
                Vec::new()
            }
            Err(_) => Vec::new(),
        }
    }

    /// Replaces the addresses of the server lists, http or https ones.
    pub async fn set_server_list_addresses(&self, addresses: Vec<String>) -> Result<(), String> {
        let config = self.shared.dirs.config.clone();
        tokio::task::spawn_blocking(move || server_lists::save_addresses(&config, &addresses))
            .await
            .map_err(|e| e.to_string())?
            .map_err(|e| format!("Cannot save {}: {e}", server_lists::ADDRESSES_FILE))
    }

    /// Connects to the server at `address` (added to the list if new), or to any known one.
    pub async fn connect_server(&self, address: Option<Address>) -> Result<(), ConnectError> {
        self.ask(|reply| Command::ConnectServer(address, reply)).await.unwrap_or(Err(ConnectError::NoServer))
    }

    pub async fn disconnect_server(&self) {
        let _ = self.ask(Command::DisconnectServer).await;
    }

    /// The state of Kad, none while it is off.
    pub async fn kad(&self) -> Option<KadInfo> {
        self.ask(Command::Kad).await.flatten()
    }

    pub async fn kad_contacts(&self) -> Vec<KadContactInfo> {
        let mut contacts = self.ask(Command::KadContacts).await.unwrap_or_default();
        let table = self.countries();
        if table.size() > 0 {
            for contact in &mut contacts {
                contact.country = table.country_of(&contact.address);
            }
        }
        contacts
    }

    pub async fn kad_searches(&self) -> Vec<KadSearchInfo> {
        self.ask(Command::KadSearches).await.unwrap_or_default()
    }

    /// The notes Kad gave on a file so far, without those the comment filter leaves out.
    pub async fn kad_notes(&self, file: [u8; 16]) -> Vec<KadNote> {
        self.ask(|answer| Command::KadNotes(file, answer)).await.unwrap_or_default()
    }

    /// Asks Kad for the notes on a file we download or share, and answers those known once the search ends.
    pub async fn search_kad_notes(&self, file: [u8; 16]) -> Result<Vec<KadNote>, NotesError> {
        let Some(started) = self.ask(|answer| Command::SearchKadNotes(file, answer)).await else {
            return Err(NotesError::Kad("njMule is stopping".to_owned()));
        };
        let outcome = started.ok_or(NotesError::UnknownFile)?;
        match outcome.await {
            Ok(found) => found.map_err(NotesError::Kad),
            Err(_) => Err(NotesError::Kad("njMule is stopping".to_owned())),
        }
    }

    /// Joins Kad, also after it was stopped by hand.
    pub async fn connect_kad(&self) {
        let _ = self.ask(Command::ConnectKad).await;
    }

    /// Leaves Kad until it is joined again or njMule starts again.
    pub async fn disconnect_kad(&self) {
        let _ = self.ask(Command::DisconnectKad).await;
    }

    /// Checks again whether other Kad nodes reach us; false while Kad is off.
    pub async fn recheck_kad(&self) -> bool {
        self.ask(Command::RecheckKad).await.unwrap_or(false)
    }

    /// Joins Kad through the node at `node`; why not while Kad does not run.
    pub async fn bootstrap_kad(&self, node: SocketAddrV4) -> Result<(), String> {
        if self.ask(|reply| Command::BootstrapKad(node, reply)).await.unwrap_or(false) {
            Ok(())
        } else {
            Err("Kad is not running".to_owned())
        }
    }

    /// Adds the Kad contacts of a nodes.dat to ours and to Kad while it runs; answers how many were new.
    pub async fn add_kad_contacts(&self, source: ListSource) -> Result<usize, ListError> {
        let contents = server_lists::read(source, self.shared.routes.clone(), |data| {
            nodes_dat::read(data).map_err(|e| format!("not a nodes.dat: {e}"))
        })
        .await?;
        self.keep_kad_contacts(contents).await.map_err(ListError::Failed)
    }

    /// Keeps the Kad contacts read from a list; answers how many were new.
    pub async fn keep_kad_contacts(&self, contents: nodes_dat::Contents) -> Result<usize, String> {
        self.ask(|reply| Command::AddKadContacts(Box::new(contents), reply))
            .await
            .unwrap_or_else(|| Err(io::Error::other("the engine stopped")))
            .map_err(|e| format!("Cannot keep the contacts: {e}"))
    }

    /// Remembers another client's records of its files, and their AICH trees, so that its files are shared without
    /// hashing them again; answers how many records were new.
    pub async fn add_known_files(&self, files: Vec<KnownFile>, trees: Vec<known2_met::Entry>) -> usize {
        self.ask(|reply| Command::AddKnownFiles(files, trees, reply)).await.unwrap_or(0)
    }

    /// Where another client's download is copied before it is adopted; none once the engine stopped.
    pub async fn temp_dir(&self) -> Option<PathBuf> {
        self.ask(Command::TempDir).await
    }

    /// Takes over another client's download, its part.met and data copied into [`Engine::temp_dir`] under names the
    /// loader leaves alone, in `category` when we have it.
    pub async fn adopt_download(&self, met: PathBuf, data: PathBuf, category: u32) -> io::Result<DownloadInfo> {
        self.ask(|reply| Command::AdoptDownload(met, data, category, reply))
            .await
            .unwrap_or_else(|| Err(io::Error::other("the engine stopped")))
    }

    /// Imports an eMule profile from its configuration folder `emule`, read off the engine's task; a dry run only reads
    /// it.
    pub async fn import_from_emule(&self, emule: PathBuf, options: ImportOptions) -> Result<ImportReport, String> {
        let engine = self.clone();
        let runtime = tokio::runtime::Handle::current();
        tokio::task::spawn_blocking(move || {
            let running = engine.shared.emule_running;
            let mut target = ImportTarget::new(engine, runtime);
            EmuleImporter::new(&emule, options, &mut target, &running).run()
        })
        .await
        .map_err(|e| format!("The import stopped: {e}"))
    }

    /// The configuration folder of the eMule of this computer, when one is found.
    pub async fn emule_profile(&self) -> Option<PathBuf> {
        let env: BTreeMap<String, String> = std::env::vars_os()
            .filter_map(|(name, value)| Some((name.into_string().ok()?, value.into_string().ok()?)))
            .collect();
        emule_importer::find_config_directory(&env)
    }

    /// What was typed in the boxes of the eMule imported, which the interfaces offer again.
    pub async fn emule_history(&self) -> EmuleHistory {
        emule_importer::history(&self.shared.dirs.config)
    }

    pub async fn servers(&self) -> Vec<ServerInfo> {
        let mut servers = self.ask(Command::Servers).await.unwrap_or_default();
        let table = self.countries();
        if table.size() > 0 {
            for server in &mut servers {
                server.country = table.country_of(&server.address);
            }
        }
        servers
    }

    /// Puts a known server in the static list or takes it out; false for an unknown server.
    pub async fn set_static_server(&self, address: Address, wanted: bool) -> bool {
        self.ask(|reply| Command::SetStaticServer(address, wanted, reply)).await.unwrap_or(false)
    }

    /// Starts a search where `scope` says, or where the networks connected allow without one; answers its id.
    pub async fn start_search(&self, query: String, scope: Option<Scope>) -> Result<u64, StartError> {
        self.ask(|reply| Command::StartSearch(query, scope, reply))
            .await
            .unwrap_or_else(|| Err(StartError::Unavailable("the engine is stopping".to_owned())))
    }

    /// Asks the server for more results of a search.
    pub async fn more_results(&self, search: u64) -> MoreAnswer {
        self.ask(|reply| Command::MoreResults(search, reply)).await.unwrap_or(MoreAnswer::Unknown)
    }

    /// The folders shared alone, then those shared with their subfolders.
    pub async fn shared_directories(&self) -> Vec<SharedDirectory> {
        self.ask(Command::SharedDirectories).await.unwrap_or_default()
    }

    /// Shares a folder from now on, with its subfolders (also those made later) or alone; why not for a private one.
    pub async fn share_directory(&self, dir: PathBuf, subdirectories: bool) -> Result<(), String> {
        self.ask(|reply| Command::ShareDirectory(dir, subdirectories, reply))
            .await
            .unwrap_or_else(|| Err("the engine is stopping".to_owned()))
    }

    /// Stops sharing a folder, alone or with its subfolders.
    pub async fn unshare_directory(&self, dir: PathBuf) -> Result<(), String> {
        self.ask(|reply| Command::UnshareDirectory(dir, reply))
            .await
            .unwrap_or_else(|| Err("the engine is stopping".to_owned()))
    }

    /// The files we share, those hashed so far.
    pub async fn shared_files(&self) -> Vec<SharedFileInfo> {
        self.ask(Command::SharedFiles).await.unwrap_or_default()
    }

    /// Renames a shared file in its folder; false for a file not shared, why not for a name never shared or a move that
    /// fails.
    pub async fn rename_shared_file(&self, hash: [u8; 16], name: String) -> Result<bool, String> {
        self.ask(|reply| Command::RenameSharedFile(hash, name, reply))
            .await
            .unwrap_or_else(|| Err("the engine is stopping".to_owned()))
    }

    /// Sets the upload priority of a shared file; false for a file not shared.
    pub async fn set_upload_priority(&self, hash: [u8; 16], priority: UploadPriority) -> bool {
        self.ask(|reply| Command::SetUploadPriority(hash, priority, reply)).await.unwrap_or(false)
    }

    /// Adds a download, paused when `paused` says so or, without it, as the preferences say.
    pub async fn add_download(&self, link: FileLink, paused: Option<bool>) -> Result<DownloadInfo, String> {
        self.ask(|reply| Command::AddDownload(Box::new(link), paused, reply))
            .await
            .unwrap_or_else(|| Err("the engine is stopping".to_owned()))
    }

    /// The downloads, the completed ones last.
    pub async fn downloads(&self) -> Vec<DownloadInfo> {
        self.ask(Command::Downloads).await.unwrap_or_default()
    }

    /// The clients we upload to, then those waiting for their slot, the next one first.
    pub async fn uploads(&self) -> Vec<UploadInfo> {
        let mut uploads = self.ask(Command::Uploads).await.unwrap_or_default();
        let table = self.countries();
        if table.size() > 0 {
            for upload in &mut uploads {
                upload.country = table.country_of(&upload.address);
            }
        }
        uploads
    }

    /// The addresses banned, with the time left.
    pub async fn bans(&self) -> Vec<BanInfo> {
        self.ask(Command::Bans).await.unwrap_or_default()
    }

    /// Lifts the ban on an IPv4 address; false when it was not banned, why not when `ip` is no address.
    pub async fn unban(&self, ip: String) -> Result<bool, String> {
        let address: Ipv4Addr = ip.trim().parse().map_err(|_| format!("Not an IPv4 address: {ip}"))?;
        Ok(self.ask(|reply| Command::Unban(IpAddr::V4(address), reply)).await.unwrap_or(false))
    }

    pub async fn sources(&self) -> Vec<SourceInfo> {
        let mut sources = self.ask(Command::Sources).await.unwrap_or_default();
        let table = self.countries();
        if table.size() > 0 {
            for source in &mut sources {
                source.country = table.country_of(&source.address);
            }
        }
        sources
    }

    /// Pauses a download; answers it as it is now, none when unknown.
    pub async fn pause_download(&self, hash: [u8; 16]) -> Option<DownloadInfo> {
        self.ask(|reply| Command::PauseDownload(hash, reply)).await.flatten()
    }

    /// Resumes a paused, stopped or failed download; answers it as it is now, none when unknown or running.
    pub async fn resume_download(&self, hash: [u8; 16]) -> Option<DownloadInfo> {
        self.ask(|reply| Command::ResumeDownload(hash, reply)).await.flatten()
    }

    /// Stops a download and deletes its data; false when unknown or while its finished file is moved.
    pub async fn cancel_download(&self, hash: [u8; 16]) -> bool {
        self.ask(|reply| Command::CancelDownload(hash, reply)).await.unwrap_or(false)
    }

    /// Takes the completed downloads off the list; their files stay. Answers how many.
    pub async fn clear_completed_downloads(&self) -> usize {
        self.ask(Command::ClearCompleted).await.unwrap_or(0)
    }

    /// Takes one completed download off the list; its file stays.
    pub async fn clear_completed_download(&self, hash: [u8; 16]) -> bool {
        self.ask(|reply| Command::ClearCompletedOne(hash, reply)).await.unwrap_or(false)
    }

    /// Completes once the engine stops.
    pub fn stopped(&self) -> impl Future<Output = ()> + Send + use<> {
        let mut stop = self.shared.stop.subscribe();
        async move {
            let _ = stop.wait_for(|stopped| *stopped).await;
        }
    }

    /// Stops the network side, which saves what it keeps, and the listening, and waits for the tasks to end; then
    /// another engine may start on the configuration.
    pub async fn stop(&self) {
        self.publish(EngineEvent::Stopping);
        let _ = self.ask(Command::Stop).await;
        self.shared.stop.send_replace(true);
        let mut tasks = self.shared.tasks.lock().await;
        tasks.abort_all();
        while tasks.join_next().await.is_some() {}
        self.shared.instance.lock().unwrap_or_else(PoisonError::into_inner).take();
    }

    /// Whether the engine runs: true until it is stopped.
    pub fn is_running(&self) -> bool {
        !*self.shared.stop.borrow()
    }

    /// Whether njMule waits for the questions of its first start: from the start that created its configuration until
    /// an interface says they were answered or put aside.
    pub async fn setup_pending(&self) -> bool {
        setup_mark::present(&self.shared.dirs.config)
    }

    /// Marks the questions of the first start answered or put aside.
    pub async fn finish_setup(&self) -> Result<(), String> {
        setup_mark::remove(&self.shared.dirs.config).map_err(|e| format!("Cannot mark the setup done: {e}"))
    }

    /// Stops a download and forgets its sources; false when unknown.
    pub async fn stop_download(&self, hash: [u8; 16]) -> bool {
        self.change_download(hash, DownloadChange::Stop).await.unwrap_or(false)
    }

    /// Takes back a download cancelled in the last seconds, with what it had; false when it cannot be.
    pub async fn restore_download(&self, hash: [u8; 16]) -> bool {
        self.change_download(hash, DownloadChange::Restore).await.unwrap_or(false)
    }

    /// Asks for the first and last parts first, so that the file can be previewed early.
    pub async fn set_download_preview(&self, hash: [u8; 16], preview_first: bool) -> bool {
        self.change_download(hash, DownloadChange::PreviewFirst(preview_first)).await.unwrap_or(false)
    }

    /// Takes the sources shared with other downloads whenever they are free to move.
    pub async fn set_download_auto_swap(&self, hash: [u8; 16], auto_swap: bool) -> bool {
        self.change_download(hash, DownloadChange::AutoSwap(auto_swap)).await.unwrap_or(false)
    }

    pub async fn set_download_priority(&self, hash: [u8; 16], priority: DownloadPriority) -> bool {
        self.change_download(hash, DownloadChange::Priority(priority)).await.unwrap_or(false)
    }

    /// Moves a download to a category; 0 takes it out of every category.
    pub async fn set_download_category(&self, hash: [u8; 16], category: u32) -> bool {
        self.change_download(hash, DownloadChange::Category(category)).await.unwrap_or(false)
    }

    /// Renames a download; false when unknown, an error for a blank name.
    pub async fn rename_download(&self, hash: [u8; 16], name: String) -> Result<bool, String> {
        self.change_download(hash, DownloadChange::Rename(name)).await
    }

    /// Adds a client known to have the file as a source of a download; false when unknown or refused.
    pub async fn add_source(&self, download: [u8; 16], source: SocketAddr) -> bool {
        self.change_download(download, DownloadChange::AddSource(source)).await.unwrap_or(false)
    }

    /// Moves a source asked for another file to this download, when it is free to move.
    pub async fn swap_source_to_download(&self, download: [u8; 16], source: SocketAddr) -> bool {
        self.change_download(download, DownloadChange::SwapSource(source)).await.unwrap_or(false)
    }

    /// Moves the sources shared with other downloads to this one now; answers how many.
    pub async fn swap_sources_to_download(&self, hash: [u8; 16]) -> usize {
        self.ask(|reply| Command::SwapSources(hash, true, reply)).await.unwrap_or(0)
    }

    /// Moves this download's sources shared with others to them now; answers how many.
    pub async fn swap_sources_from_download(&self, hash: [u8; 16]) -> usize {
        self.ask(|reply| Command::SwapSources(hash, false, reply)).await.unwrap_or(0)
    }

    /// The data file of a download, once the start of the file has come.
    pub async fn download_data(&self, hash: [u8; 16]) -> Option<PathBuf> {
        self.ask(|reply| Command::DownloadData(hash, reply)).await.flatten()
    }

    /// The comments the sources of a download gave on it.
    pub async fn peer_comments(&self, download: [u8; 16]) -> Vec<PeerComment> {
        self.ask(|reply| Command::PeerComments(download, reply)).await.unwrap_or_default()
    }

    /// Our rating and comment of a file, if any.
    pub async fn file_comment(&self, file: [u8; 16]) -> Option<FileComment> {
        self.ask(|reply| Command::FileComment(file, reply)).await.flatten()
    }

    /// Keeps our rating and comment of a file, which our Kad notes and the clients asking for the file hear; an empty
    /// one takes it away.
    pub async fn set_file_comment(&self, file: [u8; 16], comment: FileComment) -> Result<(), String> {
        self.ask(|reply| Command::SetFileComment(file, comment, reply))
            .await
            .unwrap_or_else(|| Err("the engine stopped".to_owned()))
    }

    /// Whether the server holds more results of a search, which [`more_results`](Self::more_results) asks for.
    pub async fn has_more_results(&self, search: u64) -> bool {
        self.ask(|reply| Command::HasMoreResults(search, reply)).await.unwrap_or(false)
    }

    /// Stops a search: no more of its results come.
    pub async fn stop_search(&self, search: u64) -> () {
        self.ask(|reply| Command::StopSearch(search, reply)).await.unwrap_or_default()
    }

    /// Rates results for spam by the marks as they are now; off in the preferences, nothing is spam.
    pub async fn rate_spam(&self, results: Vec<SearchResult>) -> Vec<SearchResult> {
        self.ask(|reply| Command::RateSpam(results, reply)).await.unwrap_or_default()
    }

    /// Marks results as spam, or as not spam, and keeps the marks; answers them all.
    pub async fn mark_spam(&self, results: Vec<SearchResult>, is_spam: bool) -> SpamMarks {
        self.ask(|reply| Command::MarkSpam(results, is_spam, reply)).await.unwrap_or_default()
    }

    /// The files marked as spam or as not spam, and the names of those marked spam.
    pub async fn spam_marks(&self) -> SpamMarks {
        self.ask(Command::SpamMarks).await.unwrap_or_default()
    }

    /// The files downloaded or hashed before, and those cancelled.
    pub async fn file_history(&self) -> FileHistory {
        self.ask(Command::FileHistory).await.unwrap_or_default()
    }

    /// The searches left open at the last exit.
    pub async fn saved_searches(&self) -> Vec<SavedSearch> {
        self.ask(Command::SavedSearches).await.unwrap_or_default()
    }

    /// Keeps these searches for the next start, in place of those kept before.
    pub async fn save_searches(&self, searches: Vec<SavedSearch>) -> Result<(), String> {
        self.ask(|reply| Command::SaveSearches(searches, reply))
            .await
            .unwrap_or_else(|| Err("the engine is stopping".to_owned()))
    }

    pub async fn categories(&self) -> Vec<Category> {
        self.ask(Command::Categories).await.unwrap_or_default()
    }

    /// Replaces the categories, kept in their file; they must be valid and have different ids.
    pub async fn set_categories(&self, categories: Vec<Category>) -> Result<(), String> {
        self.ask(|reply| Command::SetCategories(categories, reply))
            .await
            .unwrap_or_else(|| Err("the engine is stopping".to_owned()))
    }

    /// The times of the week when other limits apply.
    pub async fn schedule(&self) -> Schedule {
        self.ask(Command::Schedule).await.unwrap_or_default()
    }

    /// The rule of the schedule in force, if any.
    pub async fn active_rule(&self) -> Option<Rule> {
        self.ask(Command::ActiveRule).await.flatten()
    }

    /// The preferences in force: those saved, with what the rule in force changes in them; those saved once the engine
    /// stopped.
    pub async fn effective_preferences(&self) -> Preferences {
        match self.ask(Command::Effective).await {
            Some(effective) => effective,
            None => self.preferences(),
        }
    }

    /// Saves the schedule and puts it in place of the one before; answers it as kept, or why it cannot be.
    pub async fn update_schedule(&self, schedule: Schedule) -> Result<Schedule, String> {
        self.ask(|reply| Command::SetSchedule(schedule, reply))
            .await
            .unwrap_or_else(|| Err("the engine is stopping".to_owned()))
    }

    /// Tests our ports, a test already running on them going on: a new login to the server and Kad's checks. Answers
    /// the test as it stands, none once the engine stopped; it goes on for minutes at most.
    pub async fn test_ports(&self) -> Option<PortTest> {
        self.ask(Command::TestPorts).await
    }

    /// The test of our ports running or run last; none before the first.
    pub async fn port_test(&self) -> Option<PortTest> {
        self.ask(Command::PortTest).await.flatten()
    }

    /// Replaces the IP filter with the list at `url`, an http or https address; answers how many ranges it has. Lists
    /// are fetched from public addresses only.
    pub async fn update_ip_filter(&self, url: String) -> Result<usize, String> {
        let web = WebLists::create(self.shared.routes.clone());
        let ranges = ip_filter_lists::fetch(&web, &url).await?;
        self.ask(|reply| Command::SetIpFilter(ranges, reply))
            .await
            .unwrap_or_else(|| Err("the engine is stopping".to_owned()))
    }

    /// Replaces the IP filter with the lists at `urls` joined into one; a list that cannot be read is left out and told
    /// in the log. Answers how many ranges the filter has.
    pub async fn update_ip_filter_lists(&self, urls: Vec<String>) -> Result<usize, String> {
        let web = WebLists::create(self.shared.routes.clone());
        let shared = Arc::clone(&self.shared);
        let ranges =
            ip_filter_lists::fetch_joined(&web, &urls, |warning| shared.user_log(Level::Warning, warning)).await?;
        self.ask(|reply| Command::SetIpFilter(ranges, reply))
            .await
            .unwrap_or_else(|| Err("the engine is stopping".to_owned()))
    }

    /// What the engine is doing now; none once it stopped.
    pub async fn status(&self) -> Option<EngineStatus> {
        self.ask(Command::Status).await.map(|status| *status)
    }

    /// Downloads and uploads in detail, for diagnosing tests.
    pub async fn describe(&self) -> String {
        self.ask(Command::Describe).await.unwrap_or_default()
    }

    async fn change_download(&self, hash: [u8; 16], change: DownloadChange) -> Result<bool, String> {
        self.ask(|reply| Command::ChangeDownload(hash, change, reply)).await.unwrap_or(Ok(false))
    }

    async fn ask<T>(&self, command: impl FnOnce(oneshot::Sender<T>) -> Command) -> Option<T> {
        let (reply, answer) = oneshot::channel();
        self.shared.commands.send(command(reply)).ok()?;
        answer.await.ok()
    }

    fn publish(&self, event: EngineEvent) {
        self.shared.publish(event);
    }
}

impl NjMuleEngine for Engine {
    fn directories(&self) -> AppDirectories {
        self.shared.dirs.clone()
    }

    fn setup_pending(&self) -> impl Future<Output = bool> + Send {
        Engine::setup_pending(self)
    }

    fn import_from_emule(
        &self,
        emule: PathBuf,
        options: ImportOptions,
    ) -> impl Future<Output = Result<ImportReport, String>> + Send {
        Engine::import_from_emule(self, emule, options)
    }

    fn emule_profile(&self) -> impl Future<Output = Option<PathBuf>> + Send {
        Engine::emule_profile(self)
    }

    fn emule_history(&self) -> impl Future<Output = EmuleHistory> + Send {
        Engine::emule_history(self)
    }

    fn finish_setup(&self) -> impl Future<Output = Result<(), String>> + Send {
        Engine::finish_setup(self)
    }

    fn status(&self) -> impl Future<Output = Option<EngineStatus>> + Send {
        Engine::status(self)
    }

    fn categories(&self) -> impl Future<Output = Vec<Category>> + Send {
        Engine::categories(self)
    }

    fn saved_searches(&self) -> impl Future<Output = Vec<SavedSearch>> + Send {
        Engine::saved_searches(self)
    }

    fn save_searches(&self, searches: Vec<SavedSearch>) -> impl Future<Output = Result<(), String>> + Send {
        Engine::save_searches(self, searches)
    }

    fn set_categories(&self, categories: Vec<Category>) -> impl Future<Output = Result<(), String>> + Send {
        Engine::set_categories(self, categories)
    }

    fn schedule(&self) -> impl Future<Output = Schedule> + Send {
        Engine::schedule(self)
    }

    fn active_rule(&self) -> impl Future<Output = Option<Rule>> + Send {
        Engine::active_rule(self)
    }

    fn update_schedule(&self, schedule: Schedule) -> impl Future<Output = Result<Schedule, String>> + Send {
        Engine::update_schedule(self, schedule)
    }

    fn test_ports(&self) -> impl Future<Output = Option<PortTest>> + Send {
        Engine::test_ports(self)
    }

    fn port_test(&self) -> impl Future<Output = Option<PortTest>> + Send {
        Engine::port_test(self)
    }

    fn update_ip_filter(&self, url: String) -> impl Future<Output = Result<usize, String>> + Send {
        Engine::update_ip_filter(self, url)
    }

    fn update_ip_filter_lists(&self, urls: Vec<String>) -> impl Future<Output = Result<usize, String>> + Send {
        Engine::update_ip_filter_lists(self, urls)
    }

    fn preferences(&self) -> Preferences {
        Engine::preferences(self)
    }

    fn update_preferences(&self, changes: &BTreeMap<String, String>) -> Result<Preferences, String> {
        Engine::update_preferences(self, changes)
    }

    fn subscribe(&self) -> Subscription {
        Engine::subscribe(self)
    }

    fn stopped(&self) -> futures_util::future::BoxFuture<'static, ()> {
        Box::pin(Engine::stopped(self))
    }

    fn hello(&self, address: SocketAddr) -> impl Future<Output = io::Result<PeerInfo>> + Send {
        Engine::hello(self, address)
    }

    fn browse(&self, address: SocketAddr) -> impl Future<Output = io::Result<Vec<SearchResult>>> + Send {
        Engine::browse(self, address)
    }

    fn known_clients(&self) -> impl Future<Output = Vec<KnownClient>> + Send {
        Engine::known_clients(self)
    }

    fn add_servers(&self, source: ListSource) -> impl Future<Output = Result<usize, ListError>> + Send {
        Engine::add_servers(self, source)
    }

    fn add_server(&self, address: Address) -> impl Future<Output = bool> + Send {
        Engine::add_server(self, address)
    }

    fn remove_server(&self, address: Address) -> impl Future<Output = bool> + Send {
        Engine::remove_server(self, address)
    }

    fn set_server_priority(&self, address: Address, priority: Priority) -> impl Future<Output = bool> + Send {
        Engine::set_server_priority(self, address, priority)
    }

    fn server_list_addresses(&self) -> impl Future<Output = Vec<String>> + Send {
        Engine::server_list_addresses(self)
    }

    fn set_server_list_addresses(&self, addresses: Vec<String>) -> impl Future<Output = Result<(), String>> + Send {
        Engine::set_server_list_addresses(self, addresses)
    }

    fn connect_server(&self, address: Option<Address>) -> impl Future<Output = Result<(), ConnectError>> + Send {
        Engine::connect_server(self, address)
    }

    fn disconnect_server(&self) -> impl Future<Output = ()> + Send {
        Engine::disconnect_server(self)
    }

    fn servers(&self) -> impl Future<Output = Vec<ServerInfo>> + Send {
        Engine::servers(self)
    }

    fn set_static_server(&self, address: Address, wanted: bool) -> impl Future<Output = bool> + Send {
        Engine::set_static_server(self, address, wanted)
    }

    fn kad(&self) -> impl Future<Output = Option<KadInfo>> + Send {
        Engine::kad(self)
    }

    fn kad_contacts(&self) -> impl Future<Output = Vec<KadContactInfo>> + Send {
        Engine::kad_contacts(self)
    }

    fn kad_searches(&self) -> impl Future<Output = Vec<KadSearchInfo>> + Send {
        Engine::kad_searches(self)
    }

    fn kad_notes(&self, file: [u8; 16]) -> impl Future<Output = Vec<KadNote>> + Send {
        Engine::kad_notes(self, file)
    }

    fn search_kad_notes(&self, file: [u8; 16]) -> impl Future<Output = Result<Vec<KadNote>, NotesError>> + Send {
        Engine::search_kad_notes(self, file)
    }

    fn connect_kad(&self) -> impl Future<Output = ()> + Send {
        Engine::connect_kad(self)
    }

    fn disconnect_kad(&self) -> impl Future<Output = ()> + Send {
        Engine::disconnect_kad(self)
    }

    fn recheck_kad(&self) -> impl Future<Output = bool> + Send {
        Engine::recheck_kad(self)
    }

    fn bootstrap_kad(&self, node: SocketAddrV4) -> impl Future<Output = Result<(), String>> + Send {
        Engine::bootstrap_kad(self, node)
    }

    fn add_kad_contacts(&self, source: ListSource) -> impl Future<Output = Result<usize, ListError>> + Send {
        Engine::add_kad_contacts(self, source)
    }

    fn start_search(
        &self,
        query: String,
        scope: Option<Scope>,
    ) -> impl Future<Output = Result<u64, StartError>> + Send {
        Engine::start_search(self, query, scope)
    }

    fn more_results(&self, search: u64) -> impl Future<Output = MoreAnswer> + Send {
        Engine::more_results(self, search)
    }

    fn shared_directories(&self) -> impl Future<Output = Vec<SharedDirectory>> + Send {
        Engine::shared_directories(self)
    }

    fn share_directory(&self, dir: PathBuf, subdirectories: bool) -> impl Future<Output = Result<(), String>> + Send {
        Engine::share_directory(self, dir, subdirectories)
    }

    fn unshare_directory(&self, dir: PathBuf) -> impl Future<Output = Result<(), String>> + Send {
        Engine::unshare_directory(self, dir)
    }

    fn shared_files(&self) -> impl Future<Output = Vec<SharedFileInfo>> + Send {
        Engine::shared_files(self)
    }

    fn rename_shared_file(&self, hash: [u8; 16], name: String) -> impl Future<Output = Result<bool, String>> + Send {
        Engine::rename_shared_file(self, hash, name)
    }

    fn set_upload_priority(&self, hash: [u8; 16], priority: UploadPriority) -> impl Future<Output = bool> + Send {
        Engine::set_upload_priority(self, hash, priority)
    }

    fn add_download(
        &self,
        link: FileLink,
        paused: Option<bool>,
    ) -> impl Future<Output = Result<DownloadInfo, String>> + Send {
        Engine::add_download(self, link, paused)
    }

    fn downloads(&self) -> impl Future<Output = Vec<DownloadInfo>> + Send {
        Engine::downloads(self)
    }

    fn sources(&self) -> impl Future<Output = Vec<SourceInfo>> + Send {
        Engine::sources(self)
    }

    fn uploads(&self) -> impl Future<Output = Vec<UploadInfo>> + Send {
        Engine::uploads(self)
    }

    fn bans(&self) -> impl Future<Output = Vec<BanInfo>> + Send {
        Engine::bans(self)
    }

    fn unban(&self, ip: String) -> impl Future<Output = Result<bool, String>> + Send {
        Engine::unban(self, ip)
    }

    fn pause_download(&self, hash: [u8; 16]) -> impl Future<Output = Option<DownloadInfo>> + Send {
        Engine::pause_download(self, hash)
    }

    fn resume_download(&self, hash: [u8; 16]) -> impl Future<Output = Option<DownloadInfo>> + Send {
        Engine::resume_download(self, hash)
    }

    fn cancel_download(&self, hash: [u8; 16]) -> impl Future<Output = bool> + Send {
        Engine::cancel_download(self, hash)
    }

    fn clear_completed_downloads(&self) -> impl Future<Output = usize> + Send {
        Engine::clear_completed_downloads(self)
    }

    fn clear_completed_download(&self, hash: [u8; 16]) -> impl Future<Output = bool> + Send {
        Engine::clear_completed_download(self, hash)
    }

    fn stop_download(&self, hash: [u8; 16]) -> impl Future<Output = bool> + Send {
        Engine::stop_download(self, hash)
    }

    fn restore_download(&self, hash: [u8; 16]) -> impl Future<Output = bool> + Send {
        Engine::restore_download(self, hash)
    }

    fn set_download_preview(&self, hash: [u8; 16], preview_first: bool) -> impl Future<Output = bool> + Send {
        Engine::set_download_preview(self, hash, preview_first)
    }

    fn set_download_auto_swap(&self, hash: [u8; 16], auto_swap: bool) -> impl Future<Output = bool> + Send {
        Engine::set_download_auto_swap(self, hash, auto_swap)
    }

    fn set_download_priority(&self, hash: [u8; 16], priority: DownloadPriority) -> impl Future<Output = bool> + Send {
        Engine::set_download_priority(self, hash, priority)
    }

    fn set_download_category(&self, hash: [u8; 16], category: u32) -> impl Future<Output = bool> + Send {
        Engine::set_download_category(self, hash, category)
    }

    fn rename_download(&self, hash: [u8; 16], name: String) -> impl Future<Output = Result<bool, String>> + Send {
        Engine::rename_download(self, hash, name)
    }

    fn add_source(&self, download: [u8; 16], source: SocketAddr) -> impl Future<Output = bool> + Send {
        Engine::add_source(self, download, source)
    }

    fn swap_source_to_download(&self, download: [u8; 16], source: SocketAddr) -> impl Future<Output = bool> + Send {
        Engine::swap_source_to_download(self, download, source)
    }

    fn swap_sources_to_download(&self, hash: [u8; 16]) -> impl Future<Output = usize> + Send {
        Engine::swap_sources_to_download(self, hash)
    }

    fn swap_sources_from_download(&self, hash: [u8; 16]) -> impl Future<Output = usize> + Send {
        Engine::swap_sources_from_download(self, hash)
    }

    fn download_data(&self, hash: [u8; 16]) -> impl Future<Output = Option<PathBuf>> + Send {
        Engine::download_data(self, hash)
    }

    fn peer_comments(&self, download: [u8; 16]) -> impl Future<Output = Vec<PeerComment>> + Send {
        Engine::peer_comments(self, download)
    }

    fn file_comment(&self, file: [u8; 16]) -> impl Future<Output = Option<FileComment>> + Send {
        Engine::file_comment(self, file)
    }

    fn set_file_comment(
        &self,
        file: [u8; 16],
        comment: FileComment,
    ) -> impl Future<Output = Result<(), String>> + Send {
        Engine::set_file_comment(self, file, comment)
    }

    fn has_more_results(&self, search: u64) -> impl Future<Output = bool> + Send {
        Engine::has_more_results(self, search)
    }

    fn stop_search(&self, search: u64) -> impl Future<Output = ()> + Send {
        Engine::stop_search(self, search)
    }

    fn rate_spam(&self, results: Vec<SearchResult>) -> impl Future<Output = Vec<SearchResult>> + Send {
        Engine::rate_spam(self, results)
    }

    fn mark_spam(&self, results: Vec<SearchResult>, is_spam: bool) -> impl Future<Output = SpamMarks> + Send {
        Engine::mark_spam(self, results, is_spam)
    }

    fn spam_marks(&self) -> impl Future<Output = SpamMarks> + Send {
        Engine::spam_marks(self)
    }

    fn file_history(&self) -> impl Future<Output = FileHistory> + Send {
        Engine::file_history(self)
    }
}

/// Why Kad was not searched for the notes on a file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum NotesError {
    /// The file is neither downloaded nor shared.
    UnknownFile,
    /// Kad could not search: it is not connected, or busy with the file.
    Kad(String),
}

impl std::fmt::Display for NotesError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::UnknownFile => f.write_str("Neither downloaded nor shared"),
            Self::Kad(reason) => f.write_str(reason),
        }
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
