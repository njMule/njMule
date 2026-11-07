// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The remote-control API against an engine the test controls: the examples of the Java RemoteApiTest, under their
//! names, for the calls the Rust engine serves.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::{BTreeMap, HashSet};
use std::io;
use std::net::{SocketAddr, SocketAddrV4};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use futures_util::future::BoxFuture;
use njmule_engine::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus, SourceInfo};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::engine_status::EngineStatus;
use njmule_engine::api::file_history::FileHistory;
use njmule_engine::api::kad_contact_info::KadContactInfo;
use njmule_engine::api::kad_info::{Buddy, KadInfo};
use njmule_engine::api::kad_note::KadNote;
use njmule_engine::api::kad_search_info::KadSearchInfo;
use njmule_engine::api::known_client::KnownClient;
use njmule_engine::api::nj_mule_engine::NjMuleEngine;
use njmule_engine::api::peer_comment::PeerComment;
use njmule_engine::api::peer_info::PeerInfo;
use njmule_engine::api::search_result::SearchResult;
use njmule_engine::api::server_info::ServerInfo;
use njmule_engine::api::spam_marks::SpamMarks;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::engine::NotesError;
use njmule_engine::engine::network::{ConnectError, MoreAnswer};
use njmule_engine::files::part_file::PartMap;
use njmule_engine::files::shared_files::SharedFile;
use njmule_engine::persist::format::nodes_dat;
use njmule_engine::persist::format::server_met::Server;
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

impl NjMuleEngine for FakeEngine {
    fn preferences(&self) -> Preferences {
        self.with(|fake| fake.preferences.clone())
    }

    fn directories(&self) -> AppDirectories {
        self.dirs.clone()
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

    fn subscribe(&self) -> broadcast::Receiver<EngineEvent> {
        self.events.subscribe()
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

    async fn import_servers(&self, _servers: Vec<Server>) -> usize {
        0
    }

    async fn connect_server(&self, _address: Option<Address>) -> Result<(), ConnectError> {
        Err(ConnectError::NoServer)
    }

    async fn disconnect_server(&self) {}

    async fn servers(&self) -> Vec<ServerInfo> {
        vec![server(); self.with(|fake| fake.servers_known)]
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

    async fn bootstrap_kad(&self, _node: SocketAddrV4) {}

    async fn add_kad_contacts(&self, _contents: nodes_dat::Contents) -> io::Result<usize> {
        Ok(0)
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

    async fn shared_files(&self) -> Vec<SharedFile> {
        Vec::new()
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

    fn given_the_engine_knows_the_server(&self, known: &str) {
        self.engine.with(|fake| fake.known_server = Some(known.to_owned()));
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
