// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The remote-control API against an engine the test controls: the examples of the Java RemoteApiTest, under their
//! names, for the calls the Rust engine serves.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::{BTreeMap, HashMap, HashSet};
use std::io;
use std::net::{SocketAddr, SocketAddrV4};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use futures_util::future::BoxFuture;
use njmule_engine::api::category::Category;
use njmule_engine::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus, SourceInfo};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::emule_history::EmuleHistory;
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::engine_status::EngineStatus;
use njmule_engine::api::file_comment::FileComment;
use njmule_engine::api::file_history::FileHistory;
use njmule_engine::api::import_options::ImportOptions;
use njmule_engine::api::import_report::{ImportReport, Item, Outcome};
use njmule_engine::api::kad_contact_info::KadContactInfo;
use njmule_engine::api::kad_info::{Buddy, KadInfo};
use njmule_engine::api::kad_note::KadNote;
use njmule_engine::api::kad_search_info::KadSearchInfo;
use njmule_engine::api::known_client::KnownClient;
use njmule_engine::api::list_source::{ListError, ListSource};
use njmule_engine::api::nj_mule_engine::{NjMuleEngine, Subscription};
use njmule_engine::api::peer_comment::PeerComment;
use njmule_engine::api::peer_info::PeerInfo;
use njmule_engine::api::port_test::{Forwarding, PortTest, Reach};
use njmule_engine::api::saved_search::SavedSearch;
use njmule_engine::api::search_result::SearchResult;
use njmule_engine::api::server_info::ServerInfo;
use njmule_engine::api::shared_directory::SharedDirectory;
use njmule_engine::api::shared_file_info::SharedFileInfo;
use njmule_engine::api::spam_marks::SpamMarks;
use njmule_engine::api::upload_info::{BanInfo, UploadInfo};
use njmule_engine::api::upload_priority::UploadPriority;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::config::schedule::{Rule, Schedule};
use njmule_engine::engine::NotesError;
use njmule_engine::engine::network::{ConnectError, MoreAnswer};
use njmule_engine::files::part_file::PartMap;
use njmule_engine::persist::preferences_codec;
use njmule_engine::remote::control_endpoint::ControlEndpoint;
use njmule_engine::search::search_manager::{Scope, StartError};
use njmule_engine::server::server_list::{Address, Priority};
use serde_json::{Value, json};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::broadcast;

const HASH: &str = "ABABABABABABABABABABABABABABABAB";
const LINK: &str = "ed2k://|file|talk.bin|5000|ABABABABABABABABABABABABABABABAB|/";

/// What the fake engine answers, as the test sets it, and what it was asked.
#[derive(Default)]
struct Fake {
    calls: Vec<String>,
    downloads: Vec<DownloadInfo>,
    kad_contacts: Vec<KadContactInfo>,
    // The calls that change any download they are asked for.
    changing: Vec<&'static str>,
    adding: bool,
    renaming: bool,
    pausing: Option<[u8; 16]>,
    data: Option<PathBuf>,
    swapping: usize,
    known_server: Option<String>,
    // A client and the name of the one file it shares.
    sharing: Option<(SocketAddr, String)>,
    searching_as: Option<u64>,
    more_held: bool,
    asking_more: bool,
    marking_spam: bool,
    downloaded_before: HashSet<[u8; 16]>,
    connected: Option<bool>,
    servers_known: usize,
    // Kept as the engine keeps them.
    preferences: Preferences,
    categories: Vec<Category>,
    schedule: Schedule,
    // The test of the ports as it stands.
    port_test: Option<PortTest>,
    server_lists: Vec<String>,
    // The country of the servers known, when known.
    server_country: Option<String>,
    // The folders shared, with their subfolders.
    shared: Vec<PathBuf>,
    setup_pending: bool,
    // Whether the engine reads the eMule profiles it is given, the one it finds, and what was typed in eMule's boxes.
    reads_emule_profiles: bool,
    emule_profile: Option<PathBuf>,
    emule_history: EmuleHistory,
    // Our comments of files, when the engine keeps them.
    keeps_file_comments: bool,
    file_comments: HashMap<[u8; 16], FileComment>,
}

#[derive(Clone)]
struct FakeEngine {
    fake: Arc<Mutex<Fake>>,
    events: broadcast::Sender<EngineEvent>,
    dirs: AppDirectories,
}

impl FakeEngine {
    fn new(root: &Path) -> Self {
        let dirs = AppDirectories::under_root(root).unwrap();
        Self { fake: Arc::default(), events: broadcast::channel(16).0, dirs }
    }

    fn with<T>(&self, work: impl FnOnce(&mut Fake) -> T) -> T {
        work(&mut self.fake.lock().unwrap())
    }

    // A change to a download, made when the test lets the engine make it.
    fn change(&self, method: &'static str, argument: String) -> bool {
        self.with(|fake| {
            let made = fake.changing.contains(&method);
            if made {
                fake.calls.push(format!("{method} {argument}"));
            }
            made
        })
    }
}

// A list's source as the calls the fake records tell it.
fn described(source: &ListSource) -> String {
    match source {
        ListSource::Web(url) => url.clone(),
        ListSource::File(path) => path.display().to_string(),
        ListSource::Data(data) => format!("{} bytes", data.len()),
    }
}

impl NjMuleEngine for FakeEngine {
    fn preferences(&self) -> Preferences {
        self.with(|fake| fake.preferences.clone())
    }

    fn directories(&self) -> AppDirectories {
        self.dirs.clone()
    }

    async fn setup_pending(&self) -> bool {
        self.with(|fake| fake.setup_pending)
    }

    async fn import_from_emule(&self, emule: PathBuf, options: ImportOptions) -> Result<ImportReport, String> {
        self.with(|fake| {
            if !fake.reads_emule_profiles {
                return Err("no import expected".to_owned());
            }
            fake.calls.push(format!("import {}{}", emule.display(), if options.dry_run { " dry" } else { "" }));
            let item = Item::text("server.met", Outcome::WouldImport, "12 servers");
            Ok(ImportReport { dry_run: options.dry_run, items: vec![item], start_at_login: false })
        })
    }

    async fn file_comment(&self, file: [u8; 16]) -> Option<FileComment> {
        self.with(|fake| fake.file_comments.get(&file).cloned())
    }

    async fn set_file_comment(&self, file: [u8; 16], comment: FileComment) -> Result<(), String> {
        self.with(|fake| {
            if !fake.keeps_file_comments {
                return Err("no comment expected".to_owned());
            }
            fake.calls.push(format!("comment {} {}", comment.rating(), comment.text()));
            fake.file_comments.insert(file, comment);
            Ok(())
        })
    }

    async fn emule_profile(&self) -> Option<PathBuf> {
        self.with(|fake| fake.emule_profile.clone())
    }

    async fn emule_history(&self) -> EmuleHistory {
        self.with(|fake| fake.emule_history.clone())
    }

    async fn finish_setup(&self) -> Result<(), String> {
        self.with(|fake| {
            fake.setup_pending = false;
            fake.calls.push("finish setup".to_owned());
        });
        Ok(())
    }

    async fn saved_searches(&self) -> Vec<SavedSearch> {
        Vec::new()
    }

    async fn save_searches(&self, _searches: Vec<SavedSearch>) -> Result<(), String> {
        Ok(())
    }

    async fn categories(&self) -> Vec<Category> {
        self.with(|fake| fake.categories.clone())
    }

    // As the engine does: valid categories only.
    async fn set_categories(&self, categories: Vec<Category>) -> Result<(), String> {
        let valid = categories.into_iter().map(Category::validated).collect::<Result<Vec<_>, _>>()?;
        self.with(|fake| fake.categories = valid);
        Ok(())
    }

    async fn schedule(&self) -> Schedule {
        self.with(|fake| fake.schedule.clone())
    }

    async fn active_rule(&self) -> Option<Rule> {
        None
    }

    async fn update_schedule(&self, schedule: Schedule) -> Result<Schedule, String> {
        self.with(|fake| fake.schedule.clone_from(&schedule));
        Ok(schedule)
    }

    async fn test_ports(&self) -> Option<PortTest> {
        self.with(|fake| fake.port_test.clone())
    }

    async fn port_test(&self) -> Option<PortTest> {
        self.with(|fake| fake.port_test.clone())
    }

    async fn update_ip_filter(&self, _url: String) -> Result<usize, String> {
        Ok(1)
    }

    async fn update_ip_filter_lists(&self, urls: Vec<String>) -> Result<usize, String> {
        Ok(urls.len())
    }

    async fn status(&self) -> Option<EngineStatus> {
        self.with(|fake| fake.connected).map(status)
    }

    // As the engine does: all the changes, or none.
    fn update_preferences(&self, changes: &BTreeMap<String, String>) -> Result<Preferences, String> {
        self.with(|fake| {
            fake.preferences = preferences_codec::apply(&fake.preferences, changes)?;
            Ok(fake.preferences.clone())
        })
    }

    fn subscribe(&self) -> Subscription {
        Subscription { missed: Vec::new(), live: self.events.subscribe() }
    }

    fn stopped(&self) -> BoxFuture<'static, ()> {
        Box::pin(std::future::pending())
    }

    async fn hello(&self, _address: SocketAddr) -> io::Result<PeerInfo> {
        Err(io::Error::other("not faked"))
    }

    async fn browse(&self, address: SocketAddr) -> io::Result<Vec<SearchResult>> {
        Ok(self.with(|fake| {
            fake.sharing
                .iter()
                .filter(|(client, _)| *client == address)
                .map(|(_, name)| SearchResult::new(hash(HASH), name, 5000, 1, Some(1), None))
                .collect()
        }))
    }

    async fn known_clients(&self) -> Vec<KnownClient> {
        Vec::new()
    }

    async fn add_servers(&self, source: ListSource) -> Result<usize, ListError> {
        self.with(|fake| fake.calls.push(format!("add_servers {}", described(&source))));
        Ok(3)
    }

    async fn server_list_addresses(&self) -> Vec<String> {
        self.with(|fake| fake.server_lists.clone())
    }

    async fn set_server_list_addresses(&self, addresses: Vec<String>) -> Result<(), String> {
        self.with(|fake| fake.server_lists = addresses);
        Ok(())
    }

    async fn connect_server(&self, _address: Option<Address>) -> Result<(), ConnectError> {
        Err(ConnectError::NoServer)
    }

    async fn disconnect_server(&self) {}

    async fn servers(&self) -> Vec<ServerInfo> {
        self.with(|fake| vec![ServerInfo { country: fake.server_country.clone(), ..server() }; fake.servers_known])
    }

    async fn add_server(&self, address: Address) -> bool {
        self.with(|fake| {
            fake.calls.push(format!("add {address}"));
            fake.known_server.as_deref() != Some(address.to_string().as_str())
        })
    }

    async fn remove_server(&self, address: Address) -> bool {
        self.with(|fake| {
            fake.calls.push(format!("remove {address}"));
            fake.known_server.as_deref() == Some(address.to_string().as_str())
        })
    }

    async fn set_server_priority(&self, address: Address, priority: Priority) -> bool {
        self.with(|fake| {
            fake.calls.push(format!("priority {address} {}", priority.name()));
            fake.known_server.as_deref() == Some(address.to_string().as_str())
        })
    }

    async fn set_static_server(&self, address: Address, wanted: bool) -> bool {
        self.with(|fake| {
            fake.calls.push(format!("static {address} {wanted}"));
            fake.known_server.as_deref() == Some(address.to_string().as_str())
        })
    }

    async fn kad(&self) -> Option<KadInfo> {
        None
    }

    async fn kad_contacts(&self) -> Vec<KadContactInfo> {
        self.with(|fake| fake.kad_contacts.clone())
    }

    async fn kad_searches(&self) -> Vec<KadSearchInfo> {
        Vec::new()
    }

    async fn kad_notes(&self, _file: [u8; 16]) -> Vec<KadNote> {
        Vec::new()
    }

    async fn search_kad_notes(&self, _file: [u8; 16]) -> Result<Vec<KadNote>, NotesError> {
        Err(NotesError::UnknownFile)
    }

    async fn connect_kad(&self) {}

    async fn disconnect_kad(&self) {}

    async fn recheck_kad(&self) -> bool {
        false
    }

    async fn bootstrap_kad(&self, _node: SocketAddrV4) -> Result<(), String> {
        Ok(())
    }

    async fn add_kad_contacts(&self, source: ListSource) -> Result<usize, ListError> {
        self.with(|fake| fake.calls.push(format!("add_kad_contacts {}", described(&source))));
        Ok(2)
    }

    async fn start_search(&self, query: String, scope: Option<Scope>) -> Result<u64, StartError> {
        self.with(|fake| {
            let id = fake.searching_as.ok_or_else(|| StartError::Unavailable("not faked".to_owned()))?;
            let scope = scope.map_or_else(|| "AUTOMATIC".to_owned(), |scope| format!("{scope:?}").to_uppercase());
            fake.calls.push(format!("search {query} {scope}"));
            Ok(id)
        })
    }

    async fn more_results(&self, search: u64) -> MoreAnswer {
        self.with(|fake| {
            if !fake.asking_more {
                return MoreAnswer::Refused;
            }
            fake.calls.push(format!("more {search}"));
            MoreAnswer::Asked
        })
    }

    async fn shared_directories(&self) -> Vec<SharedDirectory> {
        self.with(|fake| {
            fake.shared.iter().map(|path| SharedDirectory { path: path.clone(), subdirectories: true }).collect()
        })
    }

    async fn share_directory(&self, dir: PathBuf, subdirectories: bool) -> Result<(), String> {
        self.with(|fake| fake.calls.push(format!("share {} {subdirectories}", dir.display())));
        Ok(())
    }

    async fn unshare_directory(&self, dir: PathBuf) -> Result<(), String> {
        self.with(|fake| fake.calls.push(format!("unshare {}", dir.display())));
        Ok(())
    }

    async fn shared_files(&self) -> Vec<SharedFileInfo> {
        Vec::new()
    }

    async fn rename_shared_file(&self, hash: [u8; 16], name: String) -> Result<bool, String> {
        self.with(|fake| fake.calls.push(format!("renameShared {} {name}", hex::encode(hash))));
        Ok(false)
    }

    async fn set_upload_priority(&self, hash: [u8; 16], priority: UploadPriority) -> bool {
        self.with(|fake| fake.calls.push(format!("uploadPriority {} {}", hex::encode(hash), priority.name())));
        false
    }

    async fn add_download(&self, link: FileLink, paused: Option<bool>) -> Result<DownloadInfo, String> {
        self.with(|fake| {
            if !fake.adding {
                return Err("not faked".to_owned());
            }
            let asked = paused.map(|paused| format!(" paused {paused}")).unwrap_or_default();
            fake.calls.push(format!("addDownload {}{asked}", link.name));
            Ok(download())
        })
    }

    async fn downloads(&self) -> Vec<DownloadInfo> {
        self.with(|fake| fake.downloads.clone())
    }

    async fn sources(&self) -> Vec<SourceInfo> {
        Vec::new()
    }

    async fn uploads(&self) -> Vec<UploadInfo> {
        Vec::new()
    }

    async fn bans(&self) -> Vec<BanInfo> {
        Vec::new()
    }

    async fn unban(&self, _ip: String) -> Result<bool, String> {
        Ok(false)
    }

    async fn pause_download(&self, hash: [u8; 16]) -> Option<DownloadInfo> {
        self.with(|fake| fake.downloads.iter().find(|info| info.hash == hash && fake.pausing == Some(hash)).cloned())
    }

    async fn resume_download(&self, _hash: [u8; 16]) -> Option<DownloadInfo> {
        None
    }

    async fn cancel_download(&self, _hash: [u8; 16]) -> bool {
        false
    }

    async fn clear_completed_downloads(&self) -> usize {
        0
    }

    async fn clear_completed_download(&self, _hash: [u8; 16]) -> bool {
        false
    }

    async fn stop_download(&self, _hash: [u8; 16]) -> bool {
        self.change("stop_download", String::new())
    }

    async fn restore_download(&self, _hash: [u8; 16]) -> bool {
        self.change("restore_download", String::new())
    }

    async fn set_download_preview(&self, _hash: [u8; 16], preview_first: bool) -> bool {
        self.change("set_download_preview", preview_first.to_string())
    }

    async fn set_download_auto_swap(&self, _hash: [u8; 16], auto_swap: bool) -> bool {
        self.change("set_download_auto_swap", auto_swap.to_string())
    }

    async fn set_download_priority(&self, _hash: [u8; 16], priority: DownloadPriority) -> bool {
        self.change("set_download_priority", priority.name().to_owned())
    }

    async fn set_download_category(&self, _hash: [u8; 16], category: u32) -> bool {
        self.change("set_download_category", category.to_string())
    }

    // As the engine does: a blank name is refused.
    async fn rename_download(&self, _hash: [u8; 16], name: String) -> Result<bool, String> {
        self.with(|fake| {
            if !fake.renaming {
                return Ok(false);
            }
            if name.trim().is_empty() {
                return Err("a download needs a name".to_owned());
            }
            fake.calls.push(format!("renameDownload {name}"));
            Ok(true)
        })
    }

    async fn add_source(&self, _download: [u8; 16], source: SocketAddr) -> bool {
        self.change("add_source", source.to_string())
    }

    async fn swap_source_to_download(&self, _download: [u8; 16], source: SocketAddr) -> bool {
        self.change("swap_source_to_download", source.to_string())
    }

    async fn swap_sources_to_download(&self, _hash: [u8; 16]) -> usize {
        self.with(|fake| fake.swapping)
    }

    async fn swap_sources_from_download(&self, _hash: [u8; 16]) -> usize {
        0
    }

    async fn download_data(&self, _hash: [u8; 16]) -> Option<PathBuf> {
        self.with(|fake| fake.data.clone())
    }

    async fn peer_comments(&self, _download: [u8; 16]) -> Vec<PeerComment> {
        Vec::new()
    }

    async fn has_more_results(&self, _search: u64) -> bool {
        self.with(|fake| fake.more_held)
    }

    async fn stop_search(&self, search: u64) {
        self.with(|fake| fake.calls.push(format!("stop {search}")));
    }

    async fn rate_spam(&self, results: Vec<SearchResult>) -> Vec<SearchResult> {
        results
    }

    async fn mark_spam(&self, results: Vec<SearchResult>, is_spam: bool) -> SpamMarks {
        self.with(|fake| {
            if fake.marking_spam {
                for result in results {
                    fake.calls.push(format!("spam {} {is_spam}", result.name));
                }
            }
        });
        SpamMarks::default()
    }

    async fn spam_marks(&self) -> SpamMarks {
        SpamMarks::default()
    }

    async fn file_history(&self) -> FileHistory {
        self.with(|fake| FileHistory::of(fake.downloaded_before.clone(), HashSet::new()))
    }
}

fn download() -> DownloadInfo {
    DownloadInfo {
        hash: hash(HASH),
        name: "talk.bin".to_owned(),
        size: 5000,
        completed: 0,
        status: DownloadStatus::Paused,
        priority: DownloadPriority::Normal,
        category: 0,
        sources: 0,
        transferring: 0,
        bytes_per_second: 0,
        parts: PartMap { size: 0, missing: Vec::new(), requested: Vec::new(), sources: Vec::new() },
        preview_first: false,
        auto_swap: false,
    }
}

fn server() -> ServerInfo {
    ServerInfo {
        address: "203.0.113.10:4661".to_owned(),
        name: "Lighthouse".to_owned(),
        description: "Stable".to_owned(),
        users: 412_345,
        files: 25_153_045,
        max_users: 454_300,
        failed: 0,
        priority: Priority::High,
        ping: 24,
        soft_files: 28_864_150,
        hard_files: 37_111_050,
        low_id_users: 82_469,
        version: "17.15".to_owned(),
        obfuscation: true,
        is_static: false,
        country: None,
    }
}

fn kad() -> KadInfo {
    KadInfo {
        connected: true,
        contacts: 1243,
        verified_contacts: 873,
        firewalled: false,
        id: "8F3C2A1D90B44E6E9A0C7E51D2B3F4A5".to_owned(),
        public_ip: "198.51.100.23".to_owned(),
        udp_port: 4672,
        udp_firewalled: false,
        udp_verified: true,
        buddy: Buddy::None,
        users: 2_100_000,
        files: 310_000_000,
    }
}

// Connected to a server and to Kad, or to neither.
fn status(connected: bool) -> EngineStatus {
    EngineStatus {
        server: connected.then(server),
        client_id: 0x1764_3C6A,
        low_id: false,
        obfuscated: true,
        kad: connected.then(kad),
        download_bytes_per_second: 1_322_500,
        upload_bytes_per_second: 98_100,
        downloaded: 2_576_980_378,
        uploaded: 640_942_080,
        downloads: 11,
        uploads: 3,
        queued: 7,
        shared_files: 26,
        total_downloaded: 442_381_959_168,
        total_uploaded: 308_163_100_672,
        sessions: 214,
        session_seconds: 3 * 3600 + 42 * 60,
        total_seconds: 1_911 * 3600,
        public_ip: "198.51.100.23".to_owned(),
        tcp_port: 4662,
        related_search: false,
        missing_interface: None,
        public_ipv6: None,
        overhead_uploaded: 0,
        overhead_downloaded: 0,
        forwarding: "off".to_owned(),
        router: String::new(),
        kad_port_taken: None,
    }
}

fn kad_contact_in(country: &str) -> KadContactInfo {
    KadContactInfo {
        id: "8F3C2A1D90B44E6E9A0C7E51D2B3F4A5".to_owned(),
        address: "198.51.100.40:4672".to_owned(),
        contact_type: 2,
        distance: "8F3C2A1D…".to_owned(),
        version: 9,
        verified: true,
        country: Some(country.to_owned()),
    }
}

fn hash(text: &str) -> [u8; 16] {
    <[u8; 16]>::try_from(hex::decode(text).unwrap()).unwrap()
}

/// The API on a loopback port, the fake engine behind it, and the last answer.
struct Api {
    dir: tempfile::TempDir,
    engine: FakeEngine,
    endpoint: Option<ControlEndpoint>,
    address: Option<SocketAddr>,
    token: String,
    status: u16,
    headers: Vec<(String, String)>,
    body: String,
    exited: Arc<AtomicBool>,
    // An event stream open, and what it carried so far.
    stream: Option<TcpStream>,
    carried: String,
    // Streams kept open meanwhile.
    kept: Vec<TcpStream>,
    // A folder outside the system's temporary one, which may lie in a personal folder.
    folder: Option<tempfile::TempDir>,
}

impl Api {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        Self {
            engine: FakeEngine::new(dir.path()),
            dir,
            endpoint: None,
            address: None,
            token: String::new(),
            status: 0,
            headers: Vec::new(),
            body: String::new(),
            exited: Arc::default(),
            stream: None,
            carried: String::new(),
            kept: Vec::new(),
            folder: None,
        }
    }

    fn given_the_engine_shares(&self, folders: &[PathBuf]) {
        self.engine.with(|fake| fake.shared = folders.to_vec());
    }

    // Where the system keeps temporary folders inside the user's own, such a folder is shared only on request: the
    // folder is made in the build's own.
    fn given_a_folder_that_holds_nothing_personal(&mut self) {
        let folder = tempfile::tempdir_in(env!("CARGO_TARGET_TMPDIR")).unwrap();
        assert!(!SharedDirectory::is_sensitive(folder.path()), "{} is personal", folder.path().display());
        self.folder = Some(folder);
    }

    fn folder(&self) -> PathBuf {
        self.folder.as_ref().unwrap().path().to_path_buf()
    }

    fn root(&self) -> PathBuf {
        self.dir.path().ancestors().last().unwrap().to_path_buf()
    }

    fn path_kind_body(&self, kind: &str) -> String {
        let path = match kind {
            "folder" => self.dir.path().to_path_buf(),
            "file" => self.file("talk.met"),
            _ => self.file("nothing here"),
        };
        json!({ "path": path }).to_string()
    }

    // Opens the stream and waits for its first lines, once the API follows the engine for it.
    async fn given_an_event_stream_open(&mut self, types: &str) {
        let mut stream = TcpStream::connect(self.address.unwrap()).await.unwrap();
        let request = format!(
            "GET /api/v1/events?types={types} HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {}\r\n\r\n",
            self.token
        );
        stream.write_all(request.as_bytes()).await.unwrap();
        self.stream = Some(stream);
        self.until_the_stream_carries("njMule events").await;
    }

    async fn given_event_streams_open(&mut self, count: usize) {
        for _ in 0..count {
            self.given_an_event_stream_open("").await;
            self.kept.extend(self.stream.take());
            self.carried.clear();
        }
    }

    fn when_the_engine_announces(&self, event: EngineEvent) {
        self.engine.events.send(event).unwrap();
    }

    async fn when_the_api_closes(&mut self) {
        self.endpoint.take().unwrap().stop().await;
    }

    async fn then_the_stream_carries(&mut self, texts: &[&str]) {
        for text in texts {
            self.until_the_stream_carries(text).await;
        }
    }

    fn then_the_stream_never_carried(&self, text: &str) {
        assert!(!self.carried.contains(text), "{}", self.carried);
    }

    async fn then_the_stream_ends(&mut self) {
        let stream = self.stream.as_mut().unwrap();
        let mut rest = Vec::new();
        tokio::time::timeout(Duration::from_secs(5), stream.read_to_end(&mut rest))
            .await
            .expect("the stream ends")
            .unwrap();
    }

    async fn until_the_stream_carries(&mut self, text: &str) {
        let deadline = Instant::now() + Duration::from_secs(5);
        let stream = self.stream.as_mut().unwrap();
        while !self.carried.contains(text) {
            let mut read = [0u8; 4096];
            let left = deadline.saturating_duration_since(Instant::now());
            let count = tokio::time::timeout(left, stream.read(&mut read)).await.expect(text).unwrap();
            assert!(count > 0, "the stream ended before {text}: {}", self.carried);
            self.carried.push_str(&String::from_utf8_lossy(&read[..count]));
        }
    }

    async fn given_the_api_runs(&mut self) {
        let exited = Arc::clone(&self.exited);
        let exit = Arc::new(move || exited.store(true, Ordering::SeqCst));
        let endpoint = ControlEndpoint::start(self.engine.clone(), Some(exit)).await.unwrap();
        let control: Value =
            serde_json::from_str(endpoint.control_line().strip_prefix("njmule-control ").unwrap()).unwrap();
        let url = control["url"].as_str().unwrap();
        self.address = Some(url.strip_prefix("http://").unwrap().strip_suffix("/api/v1").unwrap().parse().unwrap());
        control["token"].as_str().unwrap().clone_into(&mut self.token);
        self.endpoint = Some(endpoint);
    }

    fn given_the_engine_downloads(&self, download: DownloadInfo) {
        self.engine.with(|fake| fake.downloads.push(download));
    }

    fn given_the_engine_tests_the_ports(&self) {
        self.given_the_port_test(Forwarding::Asking, Reach::Testing);
    }

    fn given_the_engine_tests_the_ports_for_minutes(&self) {
        self.given_the_port_test(Forwarding::Forwarded, Reach::Open);
    }

    fn given_the_port_test(&self, forwarding: Forwarding, tcp: Reach) {
        let test = PortTest {
            tcp_port: 4662,
            udp_port: 4672,
            forwarding,
            router: String::new(),
            tcp,
            udp: Reach::Testing,
            done: false,
        };
        self.engine.with(|fake| fake.port_test = Some(test));
    }

    fn given_the_engine_adds_downloads(&self) {
        self.engine.with(|fake| fake.adding = true);
    }

    fn given_the_engine_renames_downloads(&self) {
        self.engine.with(|fake| fake.renaming = true);
    }

    fn given_the_engine_pauses_only(&self, only: &str) {
        self.engine.with(|fake| fake.pausing = Some(hash(only)));
    }

    fn given_the_engine_changes_downloads(&self, method: &'static str) {
        self.engine.with(|fake| fake.changing.push(method));
    }

    fn given_the_engine_swaps_sources(&self, count: usize) {
        self.engine.with(|fake| fake.swapping = count);
    }

    fn given_the_data_of_the_download_in(&self, data: Option<&str>) {
        let data = data.map(|name| self.dir.path().join(name));
        self.engine.with(|fake| fake.data = data);
    }

    fn test_folder(&self) -> PathBuf {
        self.dir.path().to_path_buf()
    }

    fn given_the_engine_keeps_file_comments(&self) {
        self.engine.with(|fake| fake.keeps_file_comments = true);
    }

    async fn given_a_file_commented(&mut self, body: &str) {
        self.when_called("PUT", &format!("/api/v1/files/{HASH}/comment"), body).await;
        self.then_the_status_is(200);
    }

    fn given_the_engine_reads_emule_profiles(&self) {
        self.engine.with(|fake| fake.reads_emule_profiles = true);
    }

    fn given_the_engine_finds_an_emule_profile(&self, profile: Option<PathBuf>) {
        self.engine.with(|fake| fake.emule_profile = profile);
    }

    fn given_the_engine_kept_the_emule_history(&self, typed: EmuleHistory) {
        self.engine.with(|fake| fake.emule_history = typed);
    }

    fn given_the_engine_waits_to_be_set_up(&self, pending: bool) {
        self.engine.with(|fake| fake.setup_pending = pending);
    }

    fn given_the_engine_knows_the_server(&self, known: &str) {
        self.engine.with(|fake| fake.known_server = Some(known.to_owned()));
    }

    fn given_the_engine_knows_a_server_in(&self, country: &str) {
        self.engine.with(|fake| {
            fake.servers_known = 1;
            fake.server_country = Some(country.to_owned());
        });
    }

    fn given_the_engine_knows_the_kad_contact(&self, contact: KadContactInfo) {
        self.engine.with(|fake| fake.kad_contacts.push(contact));
    }

    fn given_the_nick_was_changed(&self) {
        self.given_the_preference("user.nick", "Night Owl");
    }

    fn given_the_proxy_password_is(&self, password: &str) {
        self.given_the_preference("proxy.password", password);
    }

    fn given_the_preference(&self, key: &str, value: &str) {
        let changes = BTreeMap::from([(key.to_owned(), value.to_owned())]);
        self.engine.update_preferences(&changes).unwrap();
    }

    fn given_the_engine_connected(&self, connected: bool) {
        self.engine.with(|fake| fake.connected = Some(connected));
    }

    fn given_the_engine_knows_servers(&self, count: usize) {
        self.engine.with(|fake| fake.servers_known = count);
    }

    fn given_the_engine_searches_as(&self, id: u64) {
        self.engine.with(|fake| fake.searching_as = Some(id));
    }

    fn given_the_server_holds_more_results(&self) {
        self.engine.with(|fake| fake.more_held = true);
    }

    fn given_the_engine_asks_for_more_results(&self) {
        self.engine.with(|fake| fake.asking_more = true);
    }

    fn given_the_engine_marks_spam(&self) {
        self.engine.with(|fake| fake.marking_spam = true);
    }

    fn given_the_engine_downloaded_before(&self, file: &str) {
        self.engine.with(|fake| fake.downloaded_before.insert(hash(file)));
    }

    fn given_the_engine_finds(&self, search: u64, name: &str) {
        let results = vec![SearchResult::new(hash(HASH), name, 5000, 3, Some(1), None)];
        self.engine.events.send(EngineEvent::SearchResults { search, results }).unwrap();
    }

    fn given_the_engine_ended_the_search(&self, search: u64) {
        self.engine.events.send(EngineEvent::SearchEnded { search, results: 0 }).unwrap();
    }

    async fn given_a_search_started(&mut self, body: &str, answered: &str) {
        self.when_called("POST", "/api/v1/searches", body).await;
        self.then_the_status_is(201);
        self.then_the_body_contains(&[answered]);
    }

    async fn given_the_results_came(&mut self, path: &str, name: &str) {
        self.when_the_results_are_asked_until_they_come(path, name).await;
        self.then_the_body_contains(&[name]);
    }

    fn given_a_client_sharing(&self, client: &str, name: &str) {
        let sharing = (client.parse().unwrap(), name.to_owned());
        self.engine.with(|fake| fake.sharing = Some(sharing));
    }

    async fn when_the_results_are_asked_until_they_come(&mut self, path: &str, text: &str) {
        let end = Instant::now() + Duration::from_secs(10);
        loop {
            self.when_called("GET", path, "").await;
            if self.body.contains(text) || Instant::now() > end {
                return;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    async fn when_called(&mut self, method: &str, path: &str, body: &str) {
        let token = self.token.clone();
        self.when_called_with(method, path, Some(&token), body).await;
    }

    async fn when_called_with(&mut self, method: &str, path: &str, token: Option<&str>, body: &str) {
        let mut stream = TcpStream::connect(self.address.unwrap()).await.unwrap();
        let mut request = format!("{method} {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
        if let Some(token) = token {
            request.push_str(&format!("Authorization: Bearer {token}\r\n"));
        }
        if !body.is_empty() {
            request.push_str("Content-Type: application/json\r\n");
        }
        request.push_str(&format!("Content-Length: {}\r\n\r\n{body}", body.len()));
        stream.write_all(request.as_bytes()).await.unwrap();
        let mut answer = Vec::new();
        stream.read_to_end(&mut answer).await.unwrap();
        let answer = String::from_utf8(answer).unwrap();
        let (head, body) = answer.split_once("\r\n\r\n").unwrap();
        let mut lines = head.lines();
        self.status = lines.next().unwrap()[9..12].parse().unwrap();
        self.headers = lines
            .filter_map(|line| line.split_once(':'))
            .map(|(name, value)| (name.trim().to_ascii_lowercase(), value.trim().to_owned()))
            .collect();
        body.clone_into(&mut self.body);
    }

    fn then_the_status_is(&self, status: u16) {
        assert_eq!(self.status, status, "{}", self.body);
    }

    fn then_the_header_is(&self, name: &str, value: &str) {
        let found = self.headers.iter().find(|(header, _)| header.eq_ignore_ascii_case(name));
        assert_eq!(found.map(|(_, value)| value.as_str()), Some(value), "{:?}", self.headers);
    }

    fn then_the_answer_asks_for_a_bearer_token(&self) {
        self.then_the_header_is("WWW-Authenticate", "Bearer");
        self.then_the_header_is("Content-Type", "application/json");
    }

    fn then_the_body_is(&self, body: &str) {
        assert_eq!(self.body, body);
    }

    fn then_the_body_does_not_contain(&self, text: &str) {
        assert!(!self.body.contains(text), "no {text} in {}", self.body);
    }

    fn then_the_proxy_password_is_empty(&self) {
        assert!(self.engine.preferences().proxy.password.is_empty());
    }

    async fn then_the_nick_is_still(&mut self, nick: &str) {
        self.when_called("GET", "/api/v1/preferences", "").await;
        self.then_the_body_contains(&[&format!("\"user.nick\":\"{nick}\"")]);
    }

    fn then_the_folders_are_the_profiles_own(&self) {
        let dirs = AppDirectories::under_root(self.dir.path()).unwrap();
        self.then_the_body_has("/incoming", &json!(dirs.downloads.to_string_lossy()));
        self.then_the_body_has("/temp", &json!(dirs.temp.to_string_lossy()));
    }

    fn then_the_upload_limit_is(&self, kibps: u32) {
        assert_eq!(self.engine.preferences().max_upload_kibps, kibps);
    }

    fn then_nj_mule_was_stopped(&self) {
        assert!(self.exited.load(Ordering::SeqCst));
    }

    fn then_the_body_contains(&self, parts: &[&str]) {
        for part in parts {
            assert!(self.body.contains(part), "{part} in {}", self.body);
        }
    }

    fn then_the_body_has(&self, pointer: &str, value: &Value) {
        let body: Value = serde_json::from_str(&self.body).unwrap();
        assert_eq!(body.pointer(pointer), Some(value), "{}", self.body);
    }

    fn then_the_data_is_in(&self, name: &str) {
        let path = self.dir.path().join(name);
        self.then_the_body_has("/path", &json!(path.to_string_lossy()));
    }

    fn then_the_body_lists(&self, entry: &Value) {
        let body: Value = serde_json::from_str(&self.body).unwrap();
        assert!(body.as_array().unwrap().contains(entry), "{entry} in {}", self.body);
    }

    fn then_the_body_is_a_list(&self) {
        assert!(serde_json::from_str::<Value>(&self.body).unwrap().is_array(), "{}", self.body);
    }

    fn given_a_file(&self, name: &str) {
        std::fs::write(self.file(name), [0xE0, 0, 0, 0, 0]).unwrap();
    }

    fn path_body(&self, name: &str) -> String {
        json!({ "path": self.file(name) }).to_string()
    }

    fn file(&self, name: &str) -> PathBuf {
        self.dir.path().join(name)
    }

    fn then_the_server_lists_are(&self, addresses: &[&str]) {
        assert_eq!(self.engine.with(|fake| fake.server_lists.clone()), addresses);
        assert_eq!(self.body, json!({ "addresses": addresses }).to_string());
    }

    fn then_the_engine_was_not_asked(&self) {
        assert_eq!(self.engine.with(|fake| fake.calls.clone()), Vec::<String>::new());
    }

    fn then_the_engine_was_asked(&self, call: &str) {
        let calls = self.engine.with(|fake| fake.calls.clone());
        assert!(calls.iter().any(|asked| asked == call), "{call} among {calls:?}");
    }
}

impl Drop for Api {
    fn drop(&mut self) {
        if let Some(endpoint) = self.endpoint.take() {
            tokio::spawn(endpoint.stop());
        }
    }
}

#[tokio::test]
async fn should_refuse_a_call_without_the_token() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called_with("GET", "/api/v1/downloads", None, "").await;

    api.then_the_status_is(401);
    api.then_the_answer_asks_for_a_bearer_token();
}

#[tokio::test]
async fn should_list_the_downloads_as_json() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_downloads(download());

    api.when_called("GET", "/api/v1/downloads", "").await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&[
        &format!("\"hash\":\"{HASH}\""),
        "\"name\":\"talk.bin\"",
        "\"status\":\"paused\"",
        "\"priority\":\"normal\"",
    ]);
}

#[tokio::test]
async fn should_give_the_country_of_a_server_when_it_is_known() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_knows_a_server_in("nl");

    api.when_called("GET", "/api/v1/servers", "").await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&["\"name\":\"Lighthouse\"", "\"country\":\"nl\""]);
}

#[tokio::test]
async fn should_give_the_country_of_a_kad_contact_when_it_is_known() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_knows_the_kad_contact(kad_contact_in("nl"));

    api.when_called("GET", "/api/v1/kad/contacts", "").await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&["\"address\":\"198.51.100.40:4672\"", "\"country\":\"nl\""]);
}

#[tokio::test]
async fn should_add_the_downloads_of_the_links_sent() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_adds_downloads();

    api.when_called("POST", "/api/v1/downloads", &format!("{{\"links\":[\"{LINK}\"]}}")).await;

    api.then_the_status_is(201);
    api.then_the_engine_was_asked("addDownload talk.bin");
}

#[tokio::test]
async fn should_add_the_downloads_running_when_asked_whatever_the_preferences() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_adds_downloads();

    api.when_called("POST", "/api/v1/downloads", &format!("{{\"links\":[\"{LINK}\"],\"paused\":false}}")).await;

    api.then_the_status_is(201);
    api.then_the_engine_was_asked("addDownload talk.bin paused false");
}

#[tokio::test]
async fn should_refuse_a_body_that_is_not_a_link() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("POST", "/api/v1/downloads", "{\"links\":[\"http://example.org/\"]}").await;

    api.then_the_status_is(400);
    api.then_the_body_contains(&["Not an eD2K file link"]);
}

#[tokio::test]
async fn should_refuse_malformed_json() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("POST", "/api/v1/downloads", "{\"links\":").await;

    api.then_the_status_is(400);
    api.then_the_body_contains(&["Malformed JSON"]);
}

#[tokio::test]
async fn should_pause_a_known_download() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_downloads(download());
    api.given_the_engine_pauses_only(HASH);

    api.when_called("POST", &format!("/api/v1/downloads/{HASH}/pause"), "").await;

    api.then_the_status_is(200);
}

#[tokio::test]
async fn should_rename_a_download() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_downloads(download());
    api.given_the_engine_renames_downloads();

    api.when_called("PUT", &format!("/api/v1/downloads/{HASH}/name"), "{\"name\":\"Debian 13.iso\"}").await;

    api.then_the_status_is(200);
    api.then_the_engine_was_asked("renameDownload Debian 13.iso");
}

#[tokio::test]
async fn should_refuse_a_blank_name_for_a_download() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_downloads(download());
    api.given_the_engine_renames_downloads();

    api.when_called("PUT", &format!("/api/v1/downloads/{HASH}/name"), "{\"name\":\" \"}").await;

    api.then_the_status_is(400);
}

#[tokio::test]
async fn should_report_an_unknown_download_to_pause() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_downloads(download());
    api.given_the_engine_pauses_only(HASH);

    api.when_called("POST", &format!("/api/v1/downloads/{}/pause", "CD".repeat(16)), "").await;

    api.then_the_status_is(404);
}

#[tokio::test]
async fn should_refuse_a_method_a_call_does_not_take() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("DELETE", "/api/v1/sources", "").await;

    api.then_the_status_is(405);
}

#[tokio::test]
async fn should_tell_the_methods_a_path_takes() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("PUT", &format!("/api/v1/downloads/{HASH}"), "").await;

    api.then_the_status_is(405);
    api.then_the_header_is("Allow", "GET,HEAD,DELETE");
}

#[tokio::test]
async fn should_put_a_server_in_the_static_list() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_knows_the_server("203.0.113.10:4661");

    api.when_called("PUT", "/api/v1/servers/203.0.113.10:4661/static", "{\"static\":true}").await;

    api.then_the_status_is(200);
    api.then_the_engine_was_asked("static 203.0.113.10:4661 true");
}

#[tokio::test]
async fn should_set_the_priority_of_a_server() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_knows_the_server("203.0.113.10:4661");

    api.when_called("PUT", "/api/v1/servers/203.0.113.10:4661/priority", "{\"priority\":\"high\"}").await;

    api.then_the_status_is(200);
    api.then_the_engine_was_asked("priority 203.0.113.10:4661 high");
}

#[tokio::test]
async fn should_not_find_the_static_flag_of_an_unknown_server() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_knows_the_server("203.0.113.10:4661");

    api.when_called("PUT", "/api/v1/servers/198.51.100.1:4661/static", "{\"static\":true}").await;

    api.then_the_status_is(404);
}

#[tokio::test]
async fn should_give_the_map_of_the_parts() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_downloads(download());

    api.when_called("GET", &format!("/api/v1/downloads/{HASH}/parts"), "").await;

    api.then_the_status_is(200);
    api.then_the_body_has("/parts", &json!({"size": 0, "missing": [], "requested": [], "sources": []}));
}

#[tokio::test]
async fn should_ask_for_the_preview_parts_first() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_downloads(download());
    api.given_the_engine_changes_downloads("set_download_preview");

    api.when_called("PUT", &format!("/api/v1/downloads/{HASH}/preview"), "{\"previewFirst\":true}").await;

    api.then_the_status_is(200);
    api.then_the_engine_was_asked("set_download_preview true");
}

#[tokio::test]
async fn should_tell_where_the_data_of_a_download_is() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_data_of_the_download_in(Some("001.part"));

    api.when_called("GET", &format!("/api/v1/downloads/{HASH}/data"), "").await;

    api.then_the_status_is(200);
    api.then_the_data_is_in("001.part");
}

#[tokio::test]
async fn should_find_no_data_of_a_download_whose_start_has_not_come() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_data_of_the_download_in(None);

    api.when_called("GET", &format!("/api/v1/downloads/{HASH}/data"), "").await;

    api.then_the_status_is(404);
}

#[tokio::test]
async fn should_swap_the_shared_sources_to_a_download() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_swaps_sources(3);

    api.when_called("POST", &format!("/api/v1/downloads/{HASH}/swap"), "{\"direction\":\"here\"}").await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&["\"added\":3"]);
}

#[tokio::test]
async fn should_list_the_files_a_client_shares() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_a_client_sharing("203.0.113.5:4662", "talk.bin");

    api.when_called("POST", "/api/v1/clients/203.0.113.5:4662/files", "").await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&["\"name\":\"talk.bin\"", &format!("\"link\":\"{LINK}\"")]);
}

#[tokio::test]
async fn should_list_the_network_interfaces_of_the_engines_computer() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("GET", "/api/v1/interfaces", "").await;

    api.then_the_status_is(200);
    api.then_the_body_is_a_list();
}

#[tokio::test]
async fn should_give_the_results_of_a_search_it_started() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_searches_as(7);
    api.given_a_search_started("{\"query\":\"talk\",\"scope\":\"kad\"}", "\"id\":7").await;
    api.given_the_engine_finds(7, "talk.bin");

    api.when_the_results_are_asked_until_they_come("/api/v1/searches/7", "talk.bin").await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&["\"name\":\"talk.bin\""]);
    api.then_the_engine_was_asked("search talk KAD");
}

#[tokio::test]
async fn should_tell_a_search_runs_with_no_more_results_to_ask_for_while_the_server_holds_none() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_searches_as(7);
    api.given_a_search_started("{\"query\":\"talk\",\"scope\":\"server\"}", "\"id\":7").await;
    api.given_the_engine_finds(7, "talk.bin");

    api.when_the_results_are_asked_until_they_come("/api/v1/searches/7", "talk.bin").await;

    api.then_the_body_has("/running", &json!(true));
    api.then_the_body_has("/more", &json!(false));
}

#[tokio::test]
async fn should_tell_a_search_the_server_holds_more_results_of_waits_for_them_to_be_asked() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_searches_as(7);
    api.given_a_search_started("{\"query\":\"talk\",\"scope\":\"server\"}", "\"id\":7").await;
    api.given_the_engine_finds(7, "talk.bin");
    api.given_the_server_holds_more_results();

    api.when_the_results_are_asked_until_they_come("/api/v1/searches/7", "talk.bin").await;

    api.then_the_body_has("/running", &json!(false));
    api.then_the_body_has("/more", &json!(true));
}

#[tokio::test]
async fn should_list_a_search_the_server_holds_more_results_of_as_waiting_for_them() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_searches_as(7);
    api.given_a_search_started("{\"query\":\"talk\",\"scope\":\"server\"}", "7").await;
    api.given_the_server_holds_more_results();

    api.when_called("GET", "/api/v1/searches", "").await;

    api.then_the_body_lists(&json!({"id": 7, "query": "talk", "scope": "server", "running": false, "results": 0}));
}

#[tokio::test]
async fn should_list_an_ended_search_as_not_running() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_searches_as(7);
    api.given_a_search_started("{\"query\":\"talk\",\"scope\":\"kad\"}", "7").await;
    api.given_the_engine_ended_the_search(7);

    api.when_the_results_are_asked_until_they_come("/api/v1/searches", "\"running\":false").await;

    api.then_the_body_lists(&json!({"id": 7, "query": "talk", "scope": "kad", "running": false, "results": 0}));
}

#[tokio::test]
async fn should_ask_the_server_for_more_results_of_a_search() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_searches_as(7);
    api.given_a_search_started("{\"query\":\"talk\",\"scope\":\"server\"}", "7").await;
    api.given_the_engine_asks_for_more_results();

    api.when_called("POST", "/api/v1/searches/7/more", "").await;

    api.then_the_status_is(200);
    api.then_the_engine_was_asked("more 7");
}

#[tokio::test]
async fn should_mark_a_result_of_a_search_as_spam() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_searches_as(7);
    api.given_the_engine_marks_spam();
    api.given_a_search_started("{\"query\":\"talk\",\"scope\":\"kad\"}", "\"id\":7").await;
    api.given_the_engine_finds(7, "talk.bin");
    api.given_the_results_came("/api/v1/searches/7", "talk.bin").await;

    api.when_called("POST", "/api/v1/searches/7/spam", &format!("{{\"hashes\":[\"{HASH}\"],\"spam\":true}}"))
        .await;

    api.then_the_status_is(200);
    api.then_the_engine_was_asked("spam talk.bin true");
}

#[tokio::test]
async fn should_tell_the_results_whose_files_are_downloaded_already() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_searches_as(7);
    api.given_the_engine_downloaded_before(HASH);
    api.given_a_search_started("{\"query\":\"talk\",\"scope\":\"kad\"}", "\"id\":7").await;
    api.given_the_engine_finds(7, "talk.bin");

    api.when_the_results_are_asked_until_they_come("/api/v1/searches/7", "talk.bin").await;

    api.then_the_body_contains(&["\"known\":\"downloaded\""]);
}

#[tokio::test]
async fn should_list_the_searches_started_with_what_they_asked_for() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_searches_as(7);
    api.given_a_search_started("{\"query\":\"talk\",\"scope\":\"kad\"}", "7").await;

    api.when_called("GET", "/api/v1/searches", "").await;

    api.then_the_status_is(200);
    api.then_the_body_lists(&json!({"id": 7, "query": "talk", "scope": "kad", "running": true, "results": 0}));
}

#[tokio::test]
async fn should_take_a_well_formed_search_expression() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("POST", "/api/v1/searches/check", "{\"query\":\"talk AND (night OR day) @size>10m\"}").await;

    api.then_the_status_is(200);
}

#[tokio::test]
async fn should_tell_what_is_wrong_with_a_malformed_search_expression() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("POST", "/api/v1/searches/check", "{\"query\":\"talk AND\"}").await;

    api.then_the_status_is(400);
    api.then_the_body_contains(&["AND"]);
}

#[tokio::test]
async fn should_tell_its_name_and_version_to_a_token_holder() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("GET", "/api/v1", "").await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&["\"name\":\"njMule\"", "\"api\":1"]);
}

#[tokio::test]
async fn should_search_kad_automatically_from_a_small_server_among_many() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_searches_as(8);
    api.given_the_engine_connected(true);
    api.given_the_engine_knows_servers(40);

    api.when_called("POST", "/api/v1/searches", "{\"query\":\"talk\",\"scope\":\"automatic\"}").await;

    api.then_the_status_is(201);
    api.then_the_engine_was_asked("search talk KAD");
}

#[tokio::test]
async fn should_stay_on_a_big_server_automatically() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_searches_as(8);
    api.given_the_engine_connected(true);
    api.given_the_engine_knows_servers(1);

    api.when_called("POST", "/api/v1/searches", "{\"query\":\"talk\",\"scope\":\"automatic\"}").await;

    api.then_the_status_is(201);
    api.then_the_engine_was_asked("search talk SERVER");
}

#[tokio::test]
async fn should_refuse_an_automatic_search_with_no_network() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_searches_as(8);
    api.given_the_engine_connected(false);
    api.given_the_engine_knows_servers(1);

    api.when_called("POST", "/api/v1/searches", "{\"query\":\"talk\",\"scope\":\"automatic\"}").await;

    api.then_the_status_is(409);
    api.then_the_body_contains(&["Not connected"]);
}

#[tokio::test]
async fn should_give_the_preferences_by_their_keys_but_not_the_proxy_password() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("GET", "/api/v1/preferences", "").await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&["\"user.nick\":\"njMule\"", "\"net.tcpPort\":\"4662\""]);
    api.then_the_body_does_not_contain("proxy.password");
}

#[tokio::test]
async fn should_tell_which_passwords_are_set_without_giving_them() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_proxy_password_is("hunter22");

    api.when_called("GET", "/api/v1/preferences/passwords", "").await;

    api.then_the_status_is(200);
    api.then_the_body_is("{\"notify.smtpPassword\":false,\"proxy.password\":true}");
}

#[tokio::test]
async fn should_clear_a_password_set_empty() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_proxy_password_is("hunter22");

    api.when_called("PATCH", "/api/v1/preferences", "{\"proxy.password\":\"\"}").await;

    api.then_the_status_is(200);
    api.then_the_body_does_not_contain("proxy.password");
    api.then_the_proxy_password_is_empty();
}

#[tokio::test]
async fn should_give_the_default_preferences_whatever_was_changed() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_nick_was_changed();

    api.when_called("GET", "/api/v1/preferences/defaults", "").await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&["\"user.nick\":\"njMule\"", "\"net.tcpPort\":\"4662\""]);
    api.then_the_body_does_not_contain("proxy.password");
}

#[tokio::test]
async fn should_give_the_folders_with_the_defaults_of_those_left_empty() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("GET", "/api/v1/folders", "").await;

    api.then_the_status_is(200);
    api.then_the_folders_are_the_profiles_own();
}

#[tokio::test]
async fn should_change_only_the_preferences_named() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("PATCH", "/api/v1/preferences", "{\"user.nick\":\"Night Owl\"}").await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&["\"user.nick\":\"Night Owl\"", "\"net.tcpPort\":\"4662\""]);
}

#[tokio::test]
async fn should_refuse_a_wrong_preference_naming_it() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("PATCH", "/api/v1/preferences", "{\"user.nick\":\"ok\",\"net.tcpPort\":\"99999\"}").await;

    api.then_the_status_is(400);
    api.then_the_body_contains(&["net.tcpPort"]);
    api.then_the_nick_is_still("njMule").await;
}

#[tokio::test]
async fn should_refuse_an_unknown_preference() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("PATCH", "/api/v1/preferences", "{\"no.such.key\":\"1\"}").await;

    api.then_the_status_is(400);
    api.then_the_body_contains(&["Unknown preference: no.such.key"]);
}

#[tokio::test]
async fn should_give_the_rates_of_the_last_minutes() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("GET", "/api/v1/rates?span=600", "").await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&["\"step\":1", "\"rates\":["]);
}

#[tokio::test]
async fn should_set_the_speed_limits() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("PUT", "/api/v1/limits", "{\"uploadKiBps\":50,\"downloadKiBps\":0}").await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&["\"uploadKiBps\":50", "\"downloadKiBps\":0"]);
    api.then_the_upload_limit_is(50);
}

#[tokio::test]
async fn should_stop_nj_mule_when_asked() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("POST", "/api/v1/shutdown", "").await;

    api.then_the_status_is(202);
    api.then_nj_mule_was_stopped();
}

#[tokio::test]
async fn should_replace_the_categories() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called(
        "PUT",
        "/api/v1/categories",
        "[{\"id\":1,\"name\":\"Films\",\"color\":3,\"patterns\":\"*.mkv|*.avi\",\"priority\":\"high\"}]",
    )
    .await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&[
        "\"name\":\"Films\"",
        "\"color\":3",
        "\"patterns\":\"*.mkv|*.avi\"",
        "\"priority\":\"high\"",
    ]);
}

#[tokio::test]
async fn should_start_a_test_of_the_ports() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_tests_the_ports();

    api.when_called("POST", "/api/v1/ports/test", "").await;

    api.then_the_status_is(202);
    api.then_the_body_contains(&["\"forwarding\":\"asking\"", "\"tcp\":\"testing\"", "\"done\":false"]);
}

// The Rust engine answers the test as it stands, never its end: a test that runs for minutes is told at once.
#[tokio::test]
async fn should_answer_a_test_of_the_ports_without_waiting_for_its_end() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_tests_the_ports_for_minutes();

    api.when_called("POST", "/api/v1/ports/test", "").await;

    api.then_the_status_is(202);
    api.then_the_body_contains(&["\"tcp\":\"open\"", "\"udp\":\"testing\"", "\"done\":false"]);
}

// The preferences hold no list.
#[tokio::test]
async fn should_not_update_the_ip_filter_without_lists() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("POST", "/api/v1/ipfilter/lists", "").await;

    api.then_the_status_is(409);
}

#[tokio::test]
async fn should_keep_the_addresses_of_the_server_lists() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("PUT", "/api/v1/servers/lists", "{\"addresses\":[\"https://example.org/server.met\"]}")
        .await;

    api.then_the_status_is(200);
    api.then_the_server_lists_are(&["https://example.org/server.met"]);
}

#[tokio::test]
async fn should_add_the_servers_of_a_server_met_of_the_engines_computer() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_a_file("server.met");

    api.when_called("POST", "/api/v1/servers/import", &api.path_body("server.met")).await;

    api.then_the_status_is(200);
    api.then_the_body_is("{\"added\":3}");
    api.then_the_engine_was_asked(&format!("add_servers {}", api.file("server.met").display()));
}

#[tokio::test]
async fn should_refuse_a_list_file_that_is_not_there() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("POST", "/api/v1/servers/import", &api.path_body("nothing.met")).await;

    api.then_the_status_is(400);
    api.then_the_engine_was_not_asked();
}

#[tokio::test]
async fn should_add_the_contacts_of_a_nodes_dat_sent() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("POST", "/api/v1/kad/nodes", "{\"data\":\"AQID\"}").await;

    api.then_the_status_is(200);
    api.then_the_engine_was_asked("add_kad_contacts 3 bytes");
}

#[tokio::test]
async fn should_share_a_folder_with_its_subfolders() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_shares(&[]);
    api.given_a_folder_that_holds_nothing_personal();

    api.when_called("POST", "/api/v1/shared/directories", &folder_body(&api.folder(), false)).await;

    api.then_the_status_is(200);
    api.then_the_engine_was_asked(&format!("share {} true", api.folder().display()));
}

#[tokio::test]
async fn should_ask_first_before_sharing_a_whole_drive() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_shares(&[]);

    api.when_called("POST", "/api/v1/shared/directories", &folder_body(&api.root(), false)).await;

    api.then_the_status_is(409);
    api.then_the_engine_was_not_asked();
}

#[tokio::test]
async fn should_share_a_whole_drive_when_told_everything() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_shares(&[]);

    api.when_called("POST", "/api/v1/shared/directories", &folder_body(&api.root(), true)).await;

    api.then_the_status_is(200);
    api.then_the_engine_was_asked(&format!("share {} true", api.root().display()));
}

#[tokio::test]
async fn should_refuse_to_share_what_is_no_folder() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_shares(&[]);

    api.when_called("POST", "/api/v1/shared/directories", &folder_body(&api.file("missing"), false)).await;

    api.then_the_status_is(400);
    api.then_the_engine_was_not_asked();
}

#[tokio::test]
async fn should_stop_sharing_a_shared_folder() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_shares(&[api.dir.path().to_path_buf()]);

    api.when_called("DELETE", &format!("/api/v1/shared/directories?path={}", encoded(api.dir.path())), "").await;

    api.then_the_status_is(200);
    api.then_the_engine_was_asked(&format!("unshare {}", api.dir.path().display()));
}

#[tokio::test]
async fn should_not_find_a_folder_that_is_not_shared() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_shares(&[]);

    api.when_called("DELETE", &format!("/api/v1/shared/directories?path={}", encoded(api.dir.path())), "").await;

    api.then_the_status_is(404);
}

#[tokio::test]
async fn should_refuse_an_upload_priority_it_does_not_know() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("PUT", &format!("/api/v1/shared/{}/priority", "25".repeat(16)), r#"{"priority":"urgent"}"#)
        .await;

    api.then_the_status_is(400);
    api.then_the_engine_was_not_asked();
}

#[tokio::test]
async fn should_not_find_a_shared_file_to_rename_that_is_not_shared() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("PUT", &format!("/api/v1/shared/{}/name", "25".repeat(16)), r#"{"name":"b.bin"}"#).await;

    api.then_the_status_is(404);
    api.then_the_engine_was_asked(&format!("renameShared {} b.bin", "25".repeat(16)));
}

#[tokio::test]
async fn should_tell_what_a_path_names_on_the_engines_computer() {
    for kind in ["folder", "file", "missing"] {
        let mut api = Api::new();
        api.given_the_api_runs().await;
        api.given_a_file("talk.met");

        api.when_called("POST", "/api/v1/folders/check", &api.path_kind_body(kind)).await;

        api.then_the_status_is(200);
        api.then_the_body_is(&json!({ "kind": kind }).to_string());
    }
}

#[tokio::test]
async fn should_stream_the_engine_events() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_an_event_stream_open("download").await;

    api.when_the_engine_announces(EngineEvent::DownloadAdded(download()));

    api.then_the_stream_carries(&["event: download.added", "\"name\":\"talk.bin\""]).await;
}

#[tokio::test]
async fn should_leave_out_of_a_stream_the_events_not_asked_for() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_an_event_stream_open("server").await;

    api.when_the_engine_announces(EngineEvent::DownloadAdded(download()));
    api.when_the_engine_announces(EngineEvent::ServerDisconnected { server: None, reason: "lost".to_owned() });

    api.then_the_stream_carries(&["event: server.disconnected"]).await;
    api.then_the_stream_never_carried("download.added");
}

#[tokio::test]
async fn should_refuse_an_event_stream_past_the_limit() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_event_streams_open(8).await;

    api.when_called("GET", "/api/v1/events", "").await;

    api.then_the_status_is(503);
}

#[tokio::test]
async fn should_end_the_streams_when_it_closes() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_an_event_stream_open("").await;

    api.when_the_api_closes().await;

    api.then_the_stream_ends().await;
}

#[tokio::test]
async fn should_replace_the_rules_of_the_schedule() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called(
        "PUT",
        "/api/v1/schedule",
        "{\"rules\":[{\"name\":\"night\",\"days\":[\"monday\",\"sunday\"],\"start\":\"23:00\",         \"end\":\"06:00\",\"downloadKiBps\":0}]}",
    )
    .await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&[
        "\"name\":\"night\"",
        "\"days\":[\"monday\",\"sunday\"]",
        "\"start\":\"23:00\"",
        "\"downloadKiBps\":0",
    ]);
    api.then_the_body_does_not_contain("uploadKiBps");
}

#[tokio::test]
async fn should_refuse_a_rule_without_a_time() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("PUT", "/api/v1/schedule", "{\"rules\":[{\"start\":\"late\",\"end\":\"06:00\"}]}").await;

    api.then_the_status_is(400);
}

#[tokio::test]
async fn should_keep_our_rating_and_comment_of_a_file() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_keeps_file_comments();
    api.given_a_file_commented("{\"rating\":4,\"text\":\"clean copy\"}").await;

    api.when_called("GET", &format!("/api/v1/files/{HASH}/comment"), "").await;

    api.then_the_status_is(200);
    api.then_the_body_is("{\"rating\":4,\"text\":\"clean copy\"}");
}

#[tokio::test]
async fn should_refuse_a_rating_above_five() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_keeps_file_comments();

    api.when_called("PUT", &format!("/api/v1/files/{HASH}/comment"), "{\"rating\":6}").await;

    api.then_the_status_is(400);
}

#[tokio::test]
async fn should_check_an_emule_profile_without_importing_it() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_reads_emule_profiles();

    api.when_called("POST", "/api/v1/import/emule", &json!({ "path": api.test_folder() }).to_string()).await;

    api.then_the_status_is(200);
    api.then_the_body_contains(&["\"dryRun\":true", "\"file\":\"server.met\"", "\"outcome\":\"would_import\""]);
    api.then_the_engine_was_asked(&format!("import {} dry", api.test_folder().display()));
}

#[tokio::test]
async fn should_refuse_to_import_what_is_no_folder() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_reads_emule_profiles();

    api.when_called("POST", "/api/v1/import/emule", &json!({ "path": api.file("none") }).to_string()).await;

    api.then_the_status_is(400);
    api.then_the_engine_was_not_asked();
}

#[tokio::test]
async fn should_tell_the_emule_profile_found_on_its_computer() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_finds_an_emule_profile(Some(api.test_folder()));

    api.when_called("GET", "/api/v1/import/emule", "").await;

    api.then_the_status_is(200);
    api.then_the_body_is(&json!({ "path": api.test_folder() }).to_string());
}

#[tokio::test]
async fn should_tell_no_path_when_its_computer_has_no_emule_profile() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_finds_an_emule_profile(None);

    api.when_called("GET", "/api/v1/import/emule", "").await;

    api.then_the_status_is(200);
    api.then_the_body_is("{}");
}

#[tokio::test]
async fn should_tell_what_was_typed_in_the_boxes_of_the_emule_imported() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_kept_the_emule_history(EmuleHistory {
        searches: vec!["talk".to_owned(), "linux iso".to_owned()],
        server_lists: vec!["http://example.org/server.met".to_owned()],
    });

    api.when_called("GET", "/api/v1/import/emule/history", "").await;

    api.then_the_status_is(200);
    api.then_the_body_is("{\"searches\":[\"talk\",\"linux iso\"],\"serverLists\":[\"http://example.org/server.met\"]}");
}

#[tokio::test]
async fn should_tell_that_nj_mule_waits_to_be_set_up() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_waits_to_be_set_up(true);

    api.when_called("GET", "/api/v1/setup", "").await;

    api.then_the_status_is(200);
    api.then_the_body_is("{\"pending\":true}");
}

#[tokio::test]
async fn should_mark_the_setup_done() {
    let mut api = Api::new();
    api.given_the_api_runs().await;
    api.given_the_engine_waits_to_be_set_up(true);

    api.when_called("POST", "/api/v1/setup/done", "").await;

    api.then_the_status_is(200);
    api.then_the_engine_was_asked("finish setup");
}

#[tokio::test]
async fn should_refuse_a_category_without_a_name() {
    let mut api = Api::new();
    api.given_the_api_runs().await;

    api.when_called("PUT", "/api/v1/categories", "[{\"id\":1,\"name\":\" \"}]").await;

    api.then_the_status_is(400);
}

fn folder_body(folder: &Path, everything: bool) -> String {
    json!({ "path": folder, "everything": everything }).to_string()
}

fn encoded(path: &Path) -> String {
    percent_encoding::utf8_percent_encode(&path.to_string_lossy(), percent_encoding::NON_ALPHANUMERIC).to_string()
}
