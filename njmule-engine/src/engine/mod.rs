// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The engine: one profile, its preferences and identity, the listening socket, the network side, and the events its
//! front ends follow.

pub mod network;
pub mod totals;

use std::collections::BTreeMap;
use std::io;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU16, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::net::TcpListener;
use tokio::sync::{broadcast, mpsc, oneshot, watch};
use tokio::task::JoinSet;

use crate::api::download_info::{DownloadInfo, DownloadPriority, SourceInfo};
use crate::api::ed2k_link::FileLink;
use crate::api::engine_event::EngineEvent;
use crate::api::engine_status::EngineStatus;
use crate::api::file_history::FileHistory;
use crate::api::kad_contact_info::KadContactInfo;
use crate::api::kad_info::KadInfo;
use crate::api::kad_note::KadNote;
use crate::api::kad_search_info::KadSearchInfo;
use crate::api::known_client::KnownClient;
use crate::api::nj_mule_engine::NjMuleEngine;
use crate::api::nj_mule_info;
use crate::api::peer_comment::PeerComment;
use crate::api::peer_info::PeerInfo;
use crate::api::search_result::SearchResult;
use crate::api::server_info::ServerInfo;
use crate::api::spam_marks::SpamMarks;
use crate::config::app_directories::AppDirectories;
use crate::config::bind_interface::{self, Binding};
use crate::config::obfuscation_mode::ObfuscationMode;
use crate::config::preferences::Preferences;
use crate::config::shared_files_visibility::SharedFilesVisibility;
use crate::engine::network::{Command, ConnectError, DownloadChange, MoreAnswer};
use crate::files::shared_files::SharedFile;
use crate::net::routes::Routes;
use crate::peer::{peer_greetings, shared_files_browser};
use crate::persist::format::nodes_dat;
use crate::persist::format::server_met::Server;
use crate::persist::{preferences_codec, user_hash_store};
use crate::protocol::ed2k::client_reasks;
use crate::protocol::ed2k::hello::{self, Hello, Ours};
use crate::protocol::ed2k::server_messages::LOW_ID_LIMIT;
use crate::protocol::kad::kad_messages;
use crate::search::search_manager::{Scope, StartError};
use crate::server::server_list::Address;
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
    commands: mpsc::UnboundedSender<Command>,
    stop: watch::Sender<bool>,
    tasks: tokio::sync::Mutex<JoinSet<()>>,
}

impl Shared {
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
    /// Opens the profile under `dirs`, starts listening for clients and starts the network side.
    pub async fn start(dirs: AppDirectories) -> io::Result<Self> {
        dirs.create_all()?;
        let preferences = preferences_codec::load(&dirs.config.join(preferences_codec::FILE_NAME))?;
        let user_hash = user_hash_store::load_or_create(&dirs.config, &mut rand::rng())?;
        // Sockets go on the network interface of the preferences; while it is gone, none opens and nothing connects.
        let local = match bind_interface::resolve_now(&preferences.bind_interface) {
            Binding::Any => Some(None),
            Binding::Bound(address) => {
                tracing::info!("Connections go through {} ({address})", preferences.bind_interface);
                Some(Some(address))
            }
            Binding::Missing(choice) => {
                tracing::warn!(
                    "The network interface {choice} is not available: njMule stays offline until it is back"
                );
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
                        tracing::warn!("Cannot listen on TCP port {}: {e}", preferences.tcp_port);
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
                    tracing::warn!("Cannot open the server UDP socket: {e}");
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
            commands,
            stop: watch::Sender::new(false),
            tasks: tokio::sync::Mutex::new(JoinSet::new()),
        });
        let engine = Self { shared: shared.clone() };
        let network = network::start(shared, commands_out, udp, listener)?;
        engine.shared.tasks.lock().await.spawn(async move {
            let _ = network.await;
        });
        engine.publish(EngineEvent::Started { version: nj_mule_info::version().to_owned() });
        Ok(engine)
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

    /// The state of Kad, none while it is off.
    pub async fn kad(&self) -> Option<KadInfo> {
        self.ask(Command::Kad).await.flatten()
    }

    pub async fn kad_contacts(&self) -> Vec<KadContactInfo> {
        self.ask(Command::KadContacts).await.unwrap_or_default()
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

    /// Joins Kad through the node at `node`.
    pub async fn bootstrap_kad(&self, node: SocketAddrV4) {
        let _ = self.ask(|reply| Command::BootstrapKad(node, reply)).await;
    }

    /// Adds Kad contacts to `nodes.dat` and to Kad while it runs; answers how many were new.
    pub async fn add_kad_contacts(&self, contents: nodes_dat::Contents) -> io::Result<usize> {
        self.ask(|reply| Command::AddKadContacts(Box::new(contents), reply))
            .await
            .unwrap_or_else(|| Err(io::Error::other("the engine stopped")))
    }

    pub async fn servers(&self) -> Vec<ServerInfo> {
        self.ask(Command::Servers).await.unwrap_or_default()
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

    /// The files we share, those hashed so far.
    pub async fn shared_files(&self) -> Vec<SharedFile> {
        self.ask(Command::SharedFiles).await.unwrap_or_default()
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

    pub async fn sources(&self) -> Vec<SourceInfo> {
        self.ask(Command::Sources).await.unwrap_or_default()
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

    /// Stops the network side, which saves what it keeps, and the listening, and waits for the tasks to end.
    pub async fn stop(&self) {
        self.publish(EngineEvent::Stopping);
        let _ = self.ask(Command::Stop).await;
        self.shared.stop.send_replace(true);
        let mut tasks = self.shared.tasks.lock().await;
        tasks.abort_all();
        while tasks.join_next().await.is_some() {}
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
        // Without a front end following, nobody misses the event.
        let _ = self.shared.events.send(event);
    }
}

impl NjMuleEngine for Engine {
    fn directories(&self) -> AppDirectories {
        self.shared.dirs.clone()
    }

    fn status(&self) -> impl Future<Output = Option<EngineStatus>> + Send {
        Engine::status(self)
    }

    fn preferences(&self) -> Preferences {
        Engine::preferences(self)
    }

    fn update_preferences(&self, changes: &BTreeMap<String, String>) -> Result<Preferences, String> {
        Engine::update_preferences(self, changes)
    }

    fn subscribe(&self) -> broadcast::Receiver<EngineEvent> {
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

    fn import_servers(&self, servers: Vec<Server>) -> impl Future<Output = usize> + Send {
        Engine::import_servers(self, servers)
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

    fn bootstrap_kad(&self, node: SocketAddrV4) -> impl Future<Output = ()> + Send {
        Engine::bootstrap_kad(self, node)
    }

    fn add_kad_contacts(&self, contents: nodes_dat::Contents) -> impl Future<Output = io::Result<usize>> + Send {
        Engine::add_kad_contacts(self, contents)
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

    fn shared_files(&self) -> impl Future<Output = Vec<SharedFile>> + Send {
        Engine::shared_files(self)
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
