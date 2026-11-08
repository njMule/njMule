// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! An engine the tests of the remote-control API and of the web interface control: what it answers, as a test sets it,
//! and what it was asked.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::{BTreeMap, HashMap, HashSet};
use std::io;
use std::net::{SocketAddr, SocketAddrV4};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use futures_util::future::BoxFuture;
use njmule_engine::api::category::Category;
use njmule_engine::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus, SourceInfo};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::emule_history::EmuleHistory;
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::engine_status::EngineStatus;
use njmule_engine::api::file_comment::FileComment;
use njmule_engine::api::file_history::FileHistory;
use njmule_engine::api::friend::Friend;
use njmule_engine::api::import_options::ImportOptions;
use njmule_engine::api::import_report::{ImportReport, Item, Outcome};
use njmule_engine::api::kad_contact_info::KadContactInfo;
use njmule_engine::api::kad_info::{Buddy, KadInfo};
use njmule_engine::api::kad_note::KadNote;
use njmule_engine::api::kad_search_info::KadSearchInfo;
use njmule_engine::api::known_client::KnownClient;
use njmule_engine::api::list_source::{ListError, ListSource};
use njmule_engine::api::nj_mule_engine::{NjMuleEngine, Subscription};
use njmule_engine::api::notification_result::NotificationResult;
use njmule_engine::api::peer_comment::PeerComment;
use njmule_engine::api::peer_info::PeerInfo;
use njmule_engine::api::port_test::PortTest;
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
use njmule_engine::search::search_manager::{Scope, StartError};
use njmule_engine::server::server_list::{Address, Priority};
use tokio::sync::{broadcast, watch};

pub const HASH: &str = "ABABABABABABABABABABABABABABABAB";

/// What the fake engine answers, as the test sets it, and what it was asked.
#[derive(Default)]
pub struct Fake {
    pub calls: Vec<String>,
    pub downloads: Vec<DownloadInfo>,
    pub kad_contacts: Vec<KadContactInfo>,
    // The calls that change any download they are asked for.
    pub changing: Vec<&'static str>,
    pub adding: bool,
    pub renaming: bool,
    pub pausing: Option<[u8; 16]>,
    pub data: Option<PathBuf>,
    pub swapping: usize,
    pub known_server: Option<String>,
    // A client and the name of the one file it shares.
    pub sharing: Option<(SocketAddr, String)>,
    pub searching_as: Option<u64>,
    pub more_held: bool,
    pub asking_more: bool,
    pub marking_spam: bool,
    pub downloaded_before: HashSet<[u8; 16]>,
    pub connected: Option<bool>,
    pub servers_known: usize,
    // Kept as the engine keeps them.
    pub preferences: Preferences,
    pub categories: Vec<Category>,
    pub schedule: Schedule,
    // The test of the ports as it stands.
    pub port_test: Option<PortTest>,
    pub server_lists: Vec<String>,
    // The country of the servers known, when known.
    pub server_country: Option<String>,
    // The folders shared, with their subfolders.
    pub shared: Vec<PathBuf>,
    pub setup_pending: bool,
    // Whether the engine reads the eMule profiles it is given, the one it finds, and what was typed in eMule's boxes.
    pub reads_emule_profiles: bool,
    pub emule_profile: Option<PathBuf>,
    pub emule_history: EmuleHistory,
    // The friend served first, which the engine gives to anyone.
    pub friend_slot: Option<[u8; 16]>,
    // Our comments of files, when the engine keeps them.
    pub keeps_file_comments: bool,
    pub file_comments: HashMap<[u8; 16], FileComment>,
}

#[derive(Clone)]
pub struct FakeEngine {
    pub fake: Arc<Mutex<Fake>>,
    pub events: broadcast::Sender<EngineEvent>,
    pub dirs: AppDirectories,
    stop: watch::Sender<bool>,
}

impl FakeEngine {
    pub fn new(root: &Path) -> Self {
        let dirs = AppDirectories::under_root(root).unwrap();
        Self { fake: Arc::default(), events: broadcast::channel(16).0, dirs, stop: watch::channel(false).0 }
    }

    /// Stops the engine, whose events then end.
    pub fn stop(&self) {
        self.stop.send_replace(true);
    }

    pub fn with<T>(&self, work: impl FnOnce(&mut Fake) -> T) -> T {
        work(&mut self.fake.lock().unwrap())
    }

    // A change to a download, made when the test lets the engine make it.
    pub fn change(&self, method: &'static str, argument: String) -> bool {
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

    async fn friends(&self) -> Vec<Friend> {
        Vec::new()
    }

    async fn send_message(&self, to: SocketAddr, text: String) {
        self.with(|fake| fake.calls.push(format!("message to {to}: {text}")));
    }

    async fn add_friend(&self, friend: Friend) -> Result<(), String> {
        self.with(|fake| fake.calls.push(format!("add friend {}", friend.name)));
        Ok(())
    }

    async fn remove_friend(&self, _user_hash: [u8; 16]) -> Result<bool, String> {
        Ok(false)
    }

    async fn friend_slot(&self) -> Option<[u8; 16]> {
        self.with(|fake| fake.friend_slot)
    }

    async fn set_friend_slot(&self, friend: Option<[u8; 16]>) -> bool {
        self.with(|fake| fake.friend_slot = friend);
        true
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

    async fn test_notifications(&self) -> Vec<NotificationResult> {
        Vec::new()
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
        let mut stopped = self.stop.subscribe();
        Box::pin(async move {
            let _ = stopped.wait_for(|stop| *stop).await;
        })
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

    async fn disconnect_server(&self) {
        self.with(|fake| fake.calls.push("disconnect".to_owned()));
    }

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

pub fn download() -> DownloadInfo {
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

pub fn server() -> ServerInfo {
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

pub fn hash(text: &str) -> [u8; 16] {
    <[u8; 16]>::try_from(hex::decode(text).unwrap()).unwrap()
}

pub fn status(connected: bool) -> EngineStatus {
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

pub fn kad() -> KadInfo {
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
