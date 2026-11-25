// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The web interface (or the desktop control endpoint, with `--control`) on example data for the documentation's
//! pictures (`website/scripts/capture-web.mjs`). Free files and documentation addresses (RFC 5737) only. Prints one
//! line of JSON with its address and passwords (or token), then serves until its standard input closes.

use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::fs;
use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, TcpListener, UdpSocket};
use std::path::PathBuf;
use std::str::FromStr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures_util::future::BoxFuture;
use jiff::Timestamp;
use md5::{Digest, Md5};
use rand::rngs::StdRng;
use rand::{Rng, RngExt, SeedableRng};
use serde_json::json;
use strum::VariantArray;
use time::Time;
use tokio::sync::broadcast;

use njmule_engine::api::category::Category;
use njmule_engine::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus, SourceInfo, SourceState};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::emule_history::EmuleHistory;
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::engine_status::EngineStatus;
use njmule_engine::api::file_comment::FileComment;
use njmule_engine::api::file_history::FileHistory;
use njmule_engine::api::friend::Friend;
use njmule_engine::api::import_options::ImportOptions;
use njmule_engine::api::import_report::{ImportReport, Item as ImportItem, Key as ImportKey, Outcome, Phrase};
use njmule_engine::api::kad_contact_info::KadContactInfo;
use njmule_engine::api::kad_info::{Buddy, KadInfo};
use njmule_engine::api::kad_note::KadNote;
use njmule_engine::api::kad_search_info::{KadSearchInfo, Kind as KadSearchKind};
use njmule_engine::api::known_client::KnownClient;
use njmule_engine::api::list_source::{ListError, ListSource};
use njmule_engine::api::log_line::{Level, LogLine};
use njmule_engine::api::nj_mule_engine::{NjMuleEngine, Subscription};
use njmule_engine::api::notification_result::NotificationResult;
use njmule_engine::api::peer_comment::PeerComment;
use njmule_engine::api::peer_info::PeerInfo;
use njmule_engine::api::port_test::PortTest;
use njmule_engine::api::saved_search::SavedSearch;
use njmule_engine::api::search_request::{Scope as RequestScope, SearchRequest};
use njmule_engine::api::search_result::{Media, Origin, SearchResult};
use njmule_engine::api::server_info::ServerInfo;
use njmule_engine::api::shared_directory::SharedDirectory;
use njmule_engine::api::shared_file_info::{SharedFileInfo, Statistics as SharedStatistics};
use njmule_engine::api::spam_marks::SpamMarks;
use njmule_engine::api::update_status::UpdateStatus;
use njmule_engine::api::upload_info::{BanInfo, UploadInfo, UploadState};
use njmule_engine::api::upload_priority::UploadPriority;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::config::schedule::{DayOfWeek, Rule, Schedule};
use njmule_engine::engine::network::{ConnectError, MoreAnswer};
use njmule_engine::engine::switches::Switches;
use njmule_engine::engine::{Engine, NotesError};
use njmule_engine::files::hash::ed2k;
use njmule_engine::files::part_file::PartMap;
use njmule_engine::remote::control_endpoint::ControlEndpoint;
use njmule_engine::remote::passwords;
use njmule_engine::remote::web_ui::WebUi;
use njmule_engine::remote::web_ui_settings::WebUiSettings;
use njmule_engine::search::search_manager::{Scope, StartError};
use njmule_engine::server::server_list::{Address, Priority};

const EMULE_PROFILE: &str = r"C:\Users\Lupo\AppData\Local\eMule\config";
const LIVE_SEARCH: u64 = 77;
const DEMO_PASSWORD_ROUNDS: u32 = 1_000;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let control_mode = std::env::args().skip(1).any(|arg| arg == "--control");
    let temp = tempfile::Builder::new().prefix("njmule-web-capture").tempdir()?;
    let dirs = AppDirectories::under_root(temp.path())?;
    fs::create_dir_all(&dirs.config)?;
    let tcp_port = free_tcp_port()?;
    let udp_port = free_udp_port()?;
    fs::write(
        dirs.config.join("njmule.properties"),
        format!(
            "net.tcpPort={tcp_port}\nnet.udpPort={udp_port}\nnet.kad.enabled=false\n\
             security.filterLan=false\nsearch.store=true\nuser.nick=Lupo\n\
             limits.uploadKiBps=250\nlimits.downloadKiBps=0\nnet.upnp=false\nnotify.sound=false\n"
        ),
    )?;

    let engine = Engine::start_with(dirs, Switches::QUIET).await?;
    engine.update_schedule(demo_schedule()).await.map_err(io::Error::other)?;
    let demo = DemoEngine::new(engine.clone(), temp.path().to_path_buf());

    if control_mode {
        let control = ControlEndpoint::start(demo, None).await?;
        let line = json!({ "url": control.url(), "token": control.token() });
        println!("{line}");
        io::stdout().flush()?;
        wait_for_stdin_eof().await;
        control.stop().await;
    } else {
        let password = secret();
        let guest = secret();
        let settings = WebUiSettings {
            enabled: true,
            port: 0,
            lan: false,
            password_hash: demo_password_hash(&password),
            api: true,
            hosts: Vec::new(),
            proxies: Vec::new(),
            guest_password_hash: demo_password_hash(&guest),
        };
        let web = WebUi::start(demo, settings, None).await?;
        let url = format!("http://127.0.0.1:{}/", web.port());
        let line = json!({ "url": url, "password": password, "guest": guest });
        println!("{line}");
        io::stdout().flush()?;
        wait_for_stdin_eof().await;
        web.stop().await;
    }

    engine.stop().await;
    Ok(())
}

fn free_tcp_port() -> io::Result<u16> {
    Ok(TcpListener::bind((Ipv4Addr::LOCALHOST, 0))?.local_addr()?.port())
}

fn free_udp_port() -> io::Result<u16> {
    Ok(UdpSocket::bind((Ipv4Addr::LOCALHOST, 0))?.local_addr()?.port())
}

fn secret() -> String {
    let mut bytes = [0u8; 12];
    rand::rng().fill_bytes(&mut bytes);
    data_encoding::HEXLOWER.encode(&bytes)
}

fn demo_password_hash(password: &str) -> String {
    let mut salt = [0u8; 16];
    rand::rng().fill_bytes(&mut salt);
    passwords::hash_with(password, &salt, DEMO_PASSWORD_ROUNDS)
}

async fn wait_for_stdin_eof() {
    let _ = tokio::task::spawn_blocking(|| {
        let mut stdin = io::stdin().lock();
        let mut buf = [0u8; 64];
        while let Ok(read) = stdin.read(&mut buf) {
            if read == 0 {
                break;
            }
        }
    })
    .await;
}

#[derive(Clone)]
struct DemoEngine {
    real: Engine,
    state: Arc<DemoState>,
}

struct DemoState {
    root: PathBuf,
    events: broadcast::Sender<EngineEvent>,
    told: AtomicBool,
}

impl DemoEngine {
    fn new(real: Engine, root: PathBuf) -> Self {
        let (events, _) = broadcast::channel(1024);
        let state = Arc::new(DemoState { root, events: events.clone(), told: AtomicBool::new(false) });
        let mut real_events = real.subscribe().live;
        tokio::spawn(async move {
            loop {
                match real_events.recv().await {
                    Ok(EngineEvent::Log(_)) => {}
                    Ok(event) => {
                        let _ = events.send(event);
                    }
                    Err(broadcast::error::RecvError::Lagged(_)) => {}
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
        });
        Self { real, state }
    }
}

impl NjMuleEngine for DemoEngine {
    fn preferences(&self) -> Preferences {
        self.real.preferences()
    }

    fn directories(&self) -> AppDirectories {
        self.real.directories().clone()
    }

    async fn setup_pending(&self) -> bool {
        self.real.setup_pending().await
    }

    async fn finish_setup(&self) -> Result<(), String> {
        self.real.finish_setup().await
    }

    async fn test_notifications(&self) -> Vec<NotificationResult> {
        self.real.test_notifications().await
    }

    async fn import_from_emule(&self, _emule: PathBuf, _options: ImportOptions) -> Result<ImportReport, String> {
        Ok(demo_import_report())
    }

    async fn emule_profile(&self) -> Option<PathBuf> {
        Some(PathBuf::from(EMULE_PROFILE))
    }

    async fn emule_history(&self) -> EmuleHistory {
        self.real.emule_history().await
    }

    async fn status(&self) -> Option<EngineStatus> {
        let seconds = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs_f64();
        let wave = (seconds / 37.0).sin() * 0.35 + (seconds / 9.0).sin() * 0.1 + 0.6;
        let down = (wave.max(0.0) * 1_400_000.0) as u64;
        let up = (90_000.0 + (seconds / 21.0).cos() * 25_000.0).max(0.0) as u64;
        Some(demo_status(down, up, &self.real.preferences()))
    }

    async fn saved_searches(&self) -> Vec<SavedSearch> {
        demo_saved_searches()
    }

    async fn save_searches(&self, _searches: Vec<SavedSearch>) -> Result<(), String> {
        Ok(())
    }

    async fn categories(&self) -> Vec<Category> {
        demo_categories()
    }

    async fn set_categories(&self, categories: Vec<Category>) -> Result<(), String> {
        self.real.set_categories(categories).await
    }

    async fn schedule(&self) -> Schedule {
        self.real.schedule().await
    }

    async fn active_rule(&self) -> Option<Rule> {
        self.real.active_rule().await
    }

    async fn update_schedule(&self, schedule: Schedule) -> Result<Schedule, String> {
        self.real.update_schedule(schedule).await
    }

    async fn test_ports(&self) -> Option<PortTest> {
        self.real.test_ports().await
    }

    async fn port_test(&self) -> Option<PortTest> {
        self.real.port_test().await
    }

    async fn update_ip_filter(&self, url: String) -> Result<usize, String> {
        self.real.update_ip_filter(url).await
    }

    async fn update_ip_filter_lists(&self, urls: Vec<String>) -> Result<usize, String> {
        self.real.update_ip_filter_lists(urls).await
    }

    fn update_preferences(&self, changes: &BTreeMap<String, String>) -> Result<Preferences, String> {
        self.real.update_preferences(changes)
    }

    fn subscribe(&self) -> Subscription {
        let missed = if !self.state.told.swap(true, Ordering::AcqRel) { demo_events() } else { Vec::new() };
        Subscription { missed, live: self.state.events.subscribe() }
    }

    fn stopped(&self) -> BoxFuture<'static, ()> {
        Box::pin(self.real.stopped())
    }

    async fn hello(&self, address: SocketAddr) -> io::Result<PeerInfo> {
        self.real.hello(address).await
    }

    async fn browse(&self, _address: SocketAddr) -> io::Result<Vec<SearchResult>> {
        Ok(demo_browse_results())
    }

    async fn known_clients(&self) -> Vec<KnownClient> {
        self.real.known_clients().await
    }

    async fn add_servers(&self, source: ListSource) -> Result<usize, ListError> {
        self.real.add_servers(source).await
    }

    async fn add_server(&self, address: Address) -> bool {
        self.real.add_server(address).await
    }

    async fn remove_server(&self, address: Address) -> bool {
        self.real.remove_server(address).await
    }

    async fn set_server_priority(&self, address: Address, priority: Priority) -> bool {
        self.real.set_server_priority(address, priority).await
    }

    async fn server_list_addresses(&self) -> Vec<String> {
        vec!["https://example.org/ed2k/server.met".to_owned(), "https://example.net/lists/server.met".to_owned()]
    }

    async fn set_server_list_addresses(&self, addresses: Vec<String>) -> Result<(), String> {
        self.real.set_server_list_addresses(addresses).await
    }

    async fn connect_server(&self, _address: Option<Address>) -> Result<(), ConnectError> {
        Ok(())
    }

    async fn disconnect_server(&self) {}

    async fn servers(&self) -> Vec<ServerInfo> {
        demo_servers()
    }

    async fn set_static_server(&self, address: Address, wanted: bool) -> bool {
        self.real.set_static_server(address, wanted).await
    }

    async fn kad(&self) -> Option<KadInfo> {
        Some(demo_kad(self.real.preferences().udp_port))
    }

    async fn kad_contacts(&self) -> Vec<KadContactInfo> {
        demo_kad_contacts()
    }

    async fn kad_searches(&self) -> Vec<KadSearchInfo> {
        demo_kad_searches()
    }

    async fn kad_notes(&self, _file: [u8; 16]) -> Vec<KadNote> {
        demo_kad_notes()
    }

    async fn search_kad_notes(&self, _file: [u8; 16]) -> Result<Vec<KadNote>, NotesError> {
        Ok(demo_kad_notes())
    }

    async fn connect_kad(&self) {}

    async fn disconnect_kad(&self) {}

    async fn recheck_kad(&self) -> bool {
        self.real.recheck_kad().await
    }

    async fn bootstrap_kad(&self, node: SocketAddrV4) -> Result<(), String> {
        self.real.bootstrap_kad(node).await
    }

    async fn add_kad_contacts(&self, source: ListSource) -> Result<usize, ListError> {
        self.real.add_kad_contacts(source).await
    }

    async fn start_search(&self, _query: String, _scope: Option<Scope>) -> Result<u64, StartError> {
        let events = self.state.events.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(400)).await;
            let _ = events.send(EngineEvent::SearchResults { search: LIVE_SEARCH, results: demo_live_results() });
        });
        Ok(LIVE_SEARCH)
    }

    async fn more_results(&self, _search: u64) -> MoreAnswer {
        MoreAnswer::Asked
    }

    async fn shared_directories(&self) -> Vec<SharedDirectory> {
        demo_shared_directories()
    }

    async fn share_directory(&self, dir: PathBuf, subdirectories: bool) -> Result<(), String> {
        self.real.share_directory(dir, subdirectories).await
    }

    async fn unshare_directory(&self, dir: PathBuf) -> Result<(), String> {
        self.real.unshare_directory(dir).await
    }

    async fn shared_files(&self) -> Vec<SharedFileInfo> {
        demo_shared_files()
    }

    async fn rename_shared_file(&self, hash: [u8; 16], name: String) -> Result<bool, String> {
        self.real.rename_shared_file(hash, name).await
    }

    async fn set_upload_priority(&self, hash: [u8; 16], priority: UploadPriority) -> bool {
        self.real.set_upload_priority(hash, priority).await
    }

    async fn add_download(&self, link: FileLink, paused: Option<bool>) -> Result<DownloadInfo, String> {
        self.real.add_download(link, paused).await
    }

    async fn downloads(&self) -> Vec<DownloadInfo> {
        demo_downloads()
    }

    async fn sources(&self) -> Vec<SourceInfo> {
        demo_sources()
    }

    async fn uploads(&self) -> Vec<UploadInfo> {
        demo_uploads()
    }

    async fn bans(&self) -> Vec<BanInfo> {
        demo_bans()
    }

    async fn unban(&self, ip: String) -> Result<bool, String> {
        self.real.unban(ip).await
    }

    async fn pause_download(&self, hash: [u8; 16]) -> Option<DownloadInfo> {
        self.real.pause_download(hash).await
    }

    async fn resume_download(&self, hash: [u8; 16]) -> Option<DownloadInfo> {
        self.real.resume_download(hash).await
    }

    async fn cancel_download(&self, hash: [u8; 16]) -> bool {
        self.real.cancel_download(hash).await
    }

    async fn clear_completed_downloads(&self) -> usize {
        self.real.clear_completed_downloads().await
    }

    async fn clear_completed_download(&self, hash: [u8; 16]) -> bool {
        self.real.clear_completed_download(hash).await
    }

    async fn stop_download(&self, hash: [u8; 16]) -> bool {
        self.real.stop_download(hash).await
    }

    async fn restore_download(&self, hash: [u8; 16]) -> bool {
        self.real.restore_download(hash).await
    }

    async fn set_download_preview(&self, hash: [u8; 16], preview_first: bool) -> bool {
        self.real.set_download_preview(hash, preview_first).await
    }

    async fn set_download_auto_swap(&self, hash: [u8; 16], auto_swap: bool) -> bool {
        self.real.set_download_auto_swap(hash, auto_swap).await
    }

    async fn set_download_priority(&self, hash: [u8; 16], priority: DownloadPriority) -> bool {
        self.real.set_download_priority(hash, priority).await
    }

    async fn set_download_category(&self, hash: [u8; 16], category: u32) -> bool {
        self.real.set_download_category(hash, category).await
    }

    async fn rename_download(&self, hash: [u8; 16], name: String) -> Result<bool, String> {
        self.real.rename_download(hash, name).await
    }

    async fn add_source(&self, download: [u8; 16], source: SocketAddr) -> bool {
        self.real.add_source(download, source).await
    }

    async fn swap_source_to_download(&self, download: [u8; 16], source: SocketAddr) -> bool {
        self.real.swap_source_to_download(download, source).await
    }

    async fn swap_sources_to_download(&self, hash: [u8; 16]) -> usize {
        self.real.swap_sources_to_download(hash).await
    }

    async fn swap_sources_from_download(&self, hash: [u8; 16]) -> usize {
        self.real.swap_sources_from_download(hash).await
    }

    async fn download_data(&self, _hash: [u8; 16]) -> Option<PathBuf> {
        Some(self.state.root.join("data.part"))
    }

    async fn peer_comments(&self, _download: [u8; 16]) -> Vec<PeerComment> {
        demo_peer_comments()
    }

    async fn file_comment(&self, _file: [u8; 16]) -> Option<FileComment> {
        Some(FileComment::of("Cinema cut, 24 fps", 4))
    }

    async fn friends(&self) -> Vec<Friend> {
        demo_friends()
    }

    async fn send_message(&self, _to: SocketAddr, _text: String) {}

    async fn add_friend(&self, friend: Friend) -> Result<(), String> {
        self.real.add_friend(friend).await
    }

    async fn remove_friend(&self, user_hash: [u8; 16]) -> Result<bool, String> {
        self.real.remove_friend(user_hash).await
    }

    async fn friend_slot(&self) -> Option<[u8; 16]> {
        Some(hash("f1"))
    }

    async fn set_friend_slot(&self, friend: Option<[u8; 16]>) -> bool {
        self.real.set_friend_slot(friend).await
    }

    async fn set_file_comment(&self, _file: [u8; 16], _comment: FileComment) -> Result<(), String> {
        Ok(())
    }

    async fn has_more_results(&self, _search: u64) -> bool {
        true
    }

    async fn stop_search(&self, _search: u64) {}

    async fn rate_spam(&self, results: Vec<SearchResult>) -> Vec<SearchResult> {
        self.real.rate_spam(results).await
    }

    async fn mark_spam(&self, results: Vec<SearchResult>, is_spam: bool) -> SpamMarks {
        self.real.mark_spam(results, is_spam).await
    }

    async fn spam_marks(&self) -> SpamMarks {
        demo_spam_marks()
    }

    async fn file_history(&self) -> FileHistory {
        demo_file_history()
    }

    fn update_status(&self) -> UpdateStatus {
        self.real.update_status()
    }

    async fn check_for_update(&self) -> UpdateStatus {
        self.real.check_for_update().await
    }

    async fn skip_update(&self, version: String) -> Result<UpdateStatus, String> {
        self.real.skip_update(version).await
    }

    async fn download_update(&self) -> Result<UpdateStatus, String> {
        self.real.download_update().await
    }

    async fn install_update(&self, restart: bool) -> Result<UpdateStatus, String> {
        self.real.install_update(restart).await
    }
}

fn hash(text: &str) -> [u8; 16] {
    Md5::digest(text.as_bytes()).into()
}

fn hex_upper(bytes: &[u8]) -> String {
    data_encoding::HEXUPPER.encode(bytes)
}

fn parts(name: &str, size: u64, done: f64, max_sources: u32) -> PartMap {
    let seed = u64::from_le_bytes(hash(name).get(..8).and_then(|s| s.try_into().ok()).unwrap_or([0; 8]));
    let mut rng = StdRng::seed_from_u64(seed);
    let count = size.saturating_add(ed2k::PARTSIZE.saturating_sub(1)) / ed2k::PARTSIZE;
    let mut missing = Vec::new();
    let mut requested = Vec::new();
    let mut sources = Vec::new();
    for i in 0..count {
        let start = i.saturating_mul(ed2k::PARTSIZE);
        let end = size.min(start.saturating_add(ed2k::PARTSIZE));
        let have = rng.random::<f64>() < done;
        if !have {
            if rng.random::<f64>() < 0.08 && requested.len() < 4 {
                let mid = start.saturating_add(end.saturating_sub(start) / 2);
                requested.push((mid, end));
                missing.push((mid, end));
            } else {
                missing.push((start, end));
            }
        }
        let s = if max_sources == 0 { 0 } else { rng.random_range(0..=max_sources) };
        sources.push(s);
    }
    PartMap { size, missing: merge_spans(missing), requested, sources }
}

fn merge_spans(spans: Vec<(u64, u64)>) -> Vec<(u64, u64)> {
    let mut out: Vec<(u64, u64)> = Vec::new();
    for (start, end) in spans {
        if let Some(last) = out.last_mut()
            && last.1 == start
        {
            last.1 = end;
        } else {
            out.push((start, end));
        }
    }
    out
}

#[allow(clippy::too_many_arguments)]
fn download(
    name: &str,
    size: u64,
    done: f64,
    status: DownloadStatus,
    priority: DownloadPriority,
    category: u32,
    sources: usize,
    transferring: usize,
    rate: u64,
    preview_first: bool,
) -> DownloadInfo {
    let part_map = if done >= 1.0 {
        PartMap { size, missing: Vec::new(), requested: Vec::new(), sources: Vec::new() }
    } else {
        parts(name, size, done, u32::try_from(sources).unwrap_or(u32::MAX))
    };
    let missing: u64 = part_map.missing.iter().map(|(s, e)| e.saturating_sub(*s)).sum();
    DownloadInfo {
        hash: hash(name),
        name: name.to_owned(),
        size,
        completed: size.saturating_sub(missing),
        status,
        priority,
        category,
        sources,
        transferring,
        bytes_per_second: rate,
        parts: part_map,
        preview_first,
        auto_swap: false,
    }
}

fn demo_downloads() -> Vec<DownloadInfo> {
    vec![
        download(
            "lumenos-4.2-amd64-dvd.iso",
            3_994_091_520,
            0.62,
            DownloadStatus::Downloading,
            DownloadPriority::High,
            1,
            48,
            6,
            412_300,
            false,
        ),
        download(
            "Harbor.Lights.2024.1080p.x264.mkv",
            1_245_822_976,
            0.88,
            DownloadStatus::Downloading,
            DownloadPriority::Auto,
            2,
            23,
            3,
            186_400,
            true,
        ),
        download(
            "Paper_Boats_2160p_60fps.mp4",
            712_934_400,
            0.12,
            DownloadStatus::Downloading,
            DownloadPriority::Normal,
            2,
            9,
            0,
            0,
            false,
        ),
        download(
            "Pinecone_Office_7.3_Win_x64.msi",
            361_234_432,
            1.0,
            DownloadStatus::Complete,
            DownloadPriority::Normal,
            0,
            0,
            0,
            0,
            false,
        ),
        download(
            "Glass_Orchard_4k.mov",
            6_442_450_944,
            0.34,
            DownloadStatus::Paused,
            DownloadPriority::Low,
            2,
            31,
            0,
            0,
            false,
        ),
        download(
            "Studio_Sessions_-_Complete_Collection.zip",
            2_147_483_648,
            0.05,
            DownloadStatus::Downloading,
            DownloadPriority::Normal,
            3,
            2,
            1,
            21_800,
            false,
        ),
        download(
            "openatlas-20260901-articles.xml.bz2",
            5_153_960_755,
            0.0,
            DownloadStatus::Downloading,
            DownloadPriority::Auto,
            0,
            0,
            0,
            0,
            false,
        ),
        download(
            "highland-trails-2026.pbf",
            2_040_109_465,
            0.47,
            DownloadStatus::Stopped,
            DownloadPriority::Normal,
            0,
            14,
            0,
            0,
            false,
        ),
        download(
            "The_Lantern_Keeper_-_audiobook.zip",
            943_718_400,
            0.71,
            DownloadStatus::Error,
            DownloadPriority::Normal,
            3,
            5,
            0,
            0,
            false,
        ),
        download(
            "lumenos-5.0-beta-desktop-amd64.iso",
            6_291_456_000,
            0.20,
            DownloadStatus::NoSpace,
            DownloadPriority::High,
            1,
            120,
            0,
            0,
            false,
        ),
        download(
            "Northern_Meadow_2022_1080p.mkv",
            1_503_238_553,
            0.95,
            DownloadStatus::Downloading,
            DownloadPriority::High,
            2,
            17,
            4,
            702_000,
            true,
        ),
    ]
}

fn demo_categories() -> Vec<Category> {
    vec![
        Category {
            id: 1,
            name: "Systems".to_owned(),
            incoming: None,
            comment: "Disk images".to_owned(),
            color: 2,
            priority: DownloadPriority::Normal,
            patterns: "*.iso|*.img".to_owned(),
            regex: false,
        },
        Category {
            id: 2,
            name: "Films".to_owned(),
            incoming: None,
            comment: "Short films".to_owned(),
            color: 4,
            priority: DownloadPriority::Normal,
            patterns: "*.mkv|*.mp4|*.mov".to_owned(),
            regex: false,
        },
        Category {
            id: 3,
            name: "Music and audiobooks".to_owned(),
            incoming: None,
            comment: String::new(),
            color: 6,
            priority: DownloadPriority::Low,
            patterns: "*.mp3|*.flac|*.zip".to_owned(),
            regex: false,
        },
    ]
}

fn demo_uploads() -> Vec<UploadInfo> {
    let mut list = vec![
        UploadInfo {
            address: "192.0.2.17:4662".to_owned(),
            user_hash: hash("u1"),
            nick: Some("Sandro_88".to_owned()),
            software: "eMule v0.70b".to_owned(),
            file: "Harbor.Lights.2024.1080p.x264.mkv".to_owned(),
            state: UploadState::Uploading,
            transferred: 48_234_496,
            bytes_per_second: 41_200,
            waiting_seconds: 0,
            country: None,
        },
        UploadInfo {
            address: "198.51.100.5:5662".to_owned(),
            user_hash: hash("u2"),
            nick: None,
            software: "aMule 2.3.3".to_owned(),
            file: "lumenos-4.2-amd64-dvd.iso".to_owned(),
            state: UploadState::Uploading,
            transferred: 12_582_912,
            bytes_per_second: 23_900,
            waiting_seconds: 0,
            country: None,
        },
        UploadInfo {
            address: "203.0.113.33:4672".to_owned(),
            user_hash: hash("u3"),
            nick: Some("Owl".to_owned()),
            software: "eMule Community v0.72".to_owned(),
            file: "Pinecone_Office_7.3_Win_x64.msi".to_owned(),
            state: UploadState::Uploading,
            transferred: 120_586_240,
            bytes_per_second: 33_000,
            waiting_seconds: 0,
            country: None,
        },
    ];
    let queued = [
        ("marta.r", "eMule v0.70b", "Harbor.Lights.2024.1080p.x264.mkv"),
        ("", "aMule 2.3.3", "lumenos-4.2-amd64-dvd.iso"),
        ("Ranger", "eMule v0.50a", "Paper_Boats_2160p_60fps.mp4"),
        ("penguin", "njMule 0.1.0", "Pinecone_Office_7.3_Win_x64.msi"),
        ("", "Shareaza 2.7", "Studio_Sessions_-_Complete_Collection.zip"),
        ("Fog", "eMule v0.70b", "highland-trails-2026.pbf"),
        ("bookworm", "aMule 2.3.2", "The_Lantern_Keeper_-_audiobook.zip"),
    ];
    for (i, (nick, software, file)) in queued.into_iter().enumerate() {
        let i_u64 = u64::try_from(i).unwrap_or(0);
        list.push(UploadInfo {
            address: format!("203.0.113.{}:{}", 60usize.saturating_add(i), 4662usize.saturating_add(i)),
            user_hash: hash(&format!("q{i}")),
            nick: (!nick.is_empty()).then(|| nick.to_owned()),
            software: software.to_owned(),
            file: file.to_owned(),
            state: UploadState::Queued,
            transferred: 0,
            bytes_per_second: 0,
            waiting_seconds: 300u64.saturating_add(i_u64.saturating_mul(977)),
            country: None,
        });
    }
    list
}

fn reported_name(name: &str, n: usize) -> Option<String> {
    match n % 4 {
        0 | 1 => Some(name.to_owned()),
        2 => Some(name.replace(' ', "_").replace('-', ".")),
        _ => None,
    }
}

fn demo_sources() -> Vec<SourceInfo> {
    let mut list = Vec::new();
    let downloads = demo_downloads();
    let nicks = ["Fox", "", "Kiwi", "", "astro_nauta", "Lynx", "Heron", ""];
    let countries = ["de", "it", "", "fr", "jp", "br", "se", "ca", "nl"];
    let mut n = 0usize;
    let a4af_target = downloads.get(2);
    for idx in [0usize, 1, 10] {
        let Some(d) = downloads.get(idx) else {
            continue;
        };
        let part_count =
            usize::try_from(d.size.saturating_add(ed2k::PARTSIZE.saturating_sub(1)) / ed2k::PARTSIZE).unwrap_or(0);
        for &state in SourceState::VARIANTS {
            let a4af = state == SourceState::A4af;
            let nick = n.checked_rem(nicks.len()).and_then(|idx| nicks.get(idx)).copied().unwrap_or("");
            let country = n.checked_rem(countries.len()).and_then(|idx| countries.get(idx)).copied().unwrap_or("");
            let software = match n % 3 {
                0 => "eMule v0.70b",
                1 => "aMule 2.3.3",
                _ => "eMule Community v0.72",
            };
            let n_u32 = u32::try_from(n).unwrap_or(0);
            let n_u64 = u64::try_from(n).unwrap_or(0);
            list.push(SourceInfo {
                file: d.hash,
                file_name: d.name.clone(),
                address: format!("198.51.100.{}:{}", 20usize.saturating_add(n), 4662usize.saturating_add(n)),
                nick: (!nick.is_empty()).then(|| nick.to_owned()),
                software: software.to_owned(),
                state,
                queue_rank: if state == SourceState::Queued {
                    12u32.saturating_add(n_u32.saturating_mul(7))
                } else {
                    0
                },
                received: if state == SourceState::Downloading {
                    31_457_280u64.saturating_add(n_u64.saturating_mul(1_000_000))
                } else {
                    0
                },
                parts: if state == SourceState::Waiting {
                    0
                } else {
                    part_count.saturating_mul((n % 5).saturating_add(1)) / 6
                },
                part_count: if state == SourceState::Waiting { 0 } else { part_count },
                reported_name: reported_name(&d.name, n),
                asked_for: if a4af { a4af_target.map(|t| t.hash) } else { None },
                asked_for_name: if a4af {
                    a4af_target.map(|t| t.name.clone()).unwrap_or_default()
                } else {
                    String::new()
                },
                country: (!country.is_empty()).then(|| country.to_owned()),
            });
            n = n.saturating_add(1);
        }
    }
    list
}

fn demo_bans() -> Vec<BanInfo> {
    vec![
        BanInfo { address: "192.0.2.200".to_owned(), seconds_left: 5400 },
        BanInfo { address: "198.51.100.250".to_owned(), seconds_left: 840 },
    ]
}

fn demo_server(i: usize) -> ServerInfo {
    const SPECS: [(&str, &str, &str, u64); 8] = [
        ("Lighthouse", "Stable, no fake files", "203.0.113.10:4661", 412_345),
        ("North Wind", "Fast searches, obfuscation", "203.0.113.11:4232", 287_904),
        ("Southwest", "Verified files only", "198.51.100.40:4661", 97_320),
        ("Nordlicht", "Deutscher Server", "192.0.2.44:7111", 54_201),
        ("Northeast", "", "198.51.100.41:5687", 12_880),
        ("Aurora Hub", "Low ID friendly", "203.0.113.12:4184", 8_410),
        ("Sirocco", "Test server", "192.0.2.45:4661", 1_204),
        ("Mistral", "Silent for days", "192.0.2.46:4661", 0),
    ];
    let (name, description, address, users) = SPECS.get(i).copied().unwrap_or(SPECS[0]);
    let i_u64 = u64::try_from(i).unwrap_or(0);
    ServerInfo {
        address: address.to_owned(),
        name: name.to_owned(),
        description: description.to_owned(),
        users,
        files: users.saturating_mul(61),
        max_users: if users == 0 { 0 } else { (users / 1000).saturating_add(1).saturating_mul(1100) },
        failed: if i == 7 {
            4
        } else if i == 6 {
            1
        } else {
            0
        },
        priority: if i < 2 {
            Priority::High
        } else if i == 6 {
            Priority::Low
        } else {
            Priority::Normal
        },
        ping: if i == 7 { 0 } else { 24u64.saturating_add(i_u64.saturating_mul(13)) },
        soft_files: users.saturating_mul(70),
        hard_files: users.saturating_mul(90),
        low_id_users: users / 5,
        version: if i.is_multiple_of(2) { "17.15" } else { "17.13" }.to_owned(),
        obfuscation: i != 4,
        is_static: i < 3,
        country: None,
    }
}

fn demo_servers() -> Vec<ServerInfo> {
    (0..8).map(demo_server).collect()
}

fn demo_kad(udp_port: u16) -> KadInfo {
    KadInfo {
        connected: true,
        contacts: 1243,
        verified_contacts: 873,
        firewalled: false,
        id: "8F3C2A1D90B44E6E9A0C7E51D2B3F4A5".to_owned(),
        public_ip: "198.51.100.23".to_owned(),
        udp_port,
        udp_firewalled: false,
        udp_verified: true,
        buddy: Buddy::None,
        users: 1_840_000,
        files: 112_000_000,
    }
}

fn demo_status(down: u64, up: u64, profile: &Preferences) -> EngineStatus {
    EngineStatus {
        server: Some(demo_server(0)),
        client_id: 0x1764_3C6A,
        low_id: false,
        obfuscated: true,
        kad: Some(demo_kad(profile.udp_port)),
        download_bytes_per_second: down,
        upload_bytes_per_second: up,
        downloaded: 2_576_980_378,
        uploaded: 640_942_080,
        downloads: 11,
        uploads: 3,
        queued: 7,
        shared_files: 26,
        total_downloaded: 442_381_959_168,
        total_uploaded: 308_163_100_672,
        sessions: 214,
        session_seconds: 3u64.saturating_mul(3600).saturating_add(42u64.saturating_mul(60)),
        total_seconds: 1_911u64.saturating_mul(3600),
        public_ip: "198.51.100.23".to_owned(),
        tcp_port: profile.tcp_port,
        related_search: true,
        missing_interface: None,
        public_ipv6: None,
        overhead_uploaded: 0,
        overhead_downloaded: 0,
        forwarding: "off".to_owned(),
        router: String::new(),
        kad_port_taken: None,
    }
}

fn demo_kad_contacts() -> Vec<KadContactInfo> {
    let mut rng = StdRng::seed_from_u64(7);
    let mut list = Vec::with_capacity(1243);
    for i in 0..1243usize {
        let mut id = [0u8; 16];
        rng.fill_bytes(&mut id);
        let hex = hex_upper(&id);
        let random128 = u128::from_be_bytes(id);
        let distance = if i < 1083 {
            random128
        } else {
            let bucket = i.saturating_sub(1083) / 10;
            let right = bucket.saturating_add(6).min(127);
            let bit = 127usize.saturating_sub(bucket.saturating_add(5));
            (random128 >> right) | (1u128 << bit)
        };
        let host: u8 = rng.random_range(0..250);
        let port: u16 = 4000u16.saturating_add(rng.random_range(0..60_000));
        list.push(KadContactInfo {
            id: hex,
            address: format!("198.51.100.{host}:{port}"),
            contact_type: rng.random_range(0..5),
            distance: format!("{distance:032X}"),
            version: 8u8.saturating_add(rng.random_range(0..2)),
            verified: rng.random_range(0..4u8) != 0,
            country: None,
        });
    }
    list
}

fn demo_kad_searches() -> Vec<KadSearchInfo> {
    vec![
        KadSearchInfo { target: "lumenos".to_owned(), kind: KadSearchKind::Keyword, answers: 37 },
        KadSearchInfo {
            target: hex_upper(&hash("lumenos-4.2-amd64-dvd.iso")),
            kind: KadSearchKind::Sources,
            answers: 12,
        },
        KadSearchInfo { target: "8F3C2A1D90B44E6E9A0C7E51D2B3F4A5".to_owned(), kind: KadSearchKind::Node, answers: 9 },
        KadSearchInfo {
            target: hex_upper(&hash("Harbor.Lights.2024.1080p.x264.mkv")),
            kind: KadSearchKind::Notes,
            answers: 2,
        },
        KadSearchInfo { target: "harbor".to_owned(), kind: KadSearchKind::StoreKeyword, answers: 4 },
        KadSearchInfo {
            target: hex_upper(&hash("Pinecone_Office_7.3_Win_x64.msi")),
            kind: KadSearchKind::StoreSource,
            answers: 6,
        },
    ]
}

fn demo_shared_files() -> Vec<SharedFileInfo> {
    const FILES: [(&str, &str, u64); 18] = [
        ("Systems", "lumenos-3.9-amd64-netinst.iso", 662_700_032),
        ("Systems", "lumenos-server-4.2-x86_64.iso", 2_343_567_360),
        ("Systems", "tidewater-desktop-2.1-64bit.iso", 2_985_123_840),
        ("Systems", "quartz-rolling-2026.10.01-x86_64.iso", 1_288_490_188),
        ("Films", "Copper_Kite_1080p.avi", 854_589_440),
        ("Films", "Salt_and_Ember_2015_1080p.mkv", 1_395_864_371),
        ("Films", "The_Quiet_Valley_1968.mp4", 1_103_101_952),
        ("Music", "Aurora_Quartet_-_Night_Sketches.flac.zip", 377_487_360),
        ("Music", "Elm_Street_Ensemble_-_Nocturnes.zip", 214_958_080),
        ("Music", "Studio_Sessions_-_Weather_Report.mp3", 7_340_032),
        ("Books", "Mara_Vale_-_The_Long_Road_Home.epub", 1_572_864),
        ("Books", "The_Lantern_Keeper.pdf", 4_823_449),
        ("Books", "OpenAtlas_-_Selection_2026.zim", 4_294_967_296),
        ("Software", "Pinecone_Office_7.3_Win_x64.msi", 361_234_432),
        ("Software", "PixelLoom-3.2-setup.exe", 350_224_384),
        ("Software", "Vectorly-1.6-x64.msi", 135_266_304),
        ("Software", "Meshworks-2.8-windows-x64.msi", 382_730_240),
        ("Maps", "highland-trails-north-2026.pbf", 1_288_490_188),
    ];
    let priorities = UploadPriority::ALL;
    FILES
        .into_iter()
        .enumerate()
        .map(|(i, (folder, name, size))| {
            let priority = i
                .saturating_mul(5)
                .checked_rem(priorities.len())
                .and_then(|idx| priorities.get(idx))
                .copied()
                .unwrap_or_default();
            SharedFileInfo {
                hash: hash(name),
                name: name.to_owned(),
                size,
                path: PathBuf::from(format!(r"D:\Shared\{folder}\{name}")),
                priority,
                statistics: SharedStatistics::default(),
            }
        })
        .collect()
}

fn demo_shared_directories() -> Vec<SharedDirectory> {
    vec![
        SharedDirectory { path: PathBuf::from(r"D:\Shared"), subdirectories: true },
        SharedDirectory { path: PathBuf::from(r"E:\Archive\Music"), subdirectories: false },
        SharedDirectory { path: PathBuf::from(r"E:\Archive\Books"), subdirectories: true },
    ]
}

fn demo_friends() -> Vec<Friend> {
    vec![
        Friend::new(hash("f1"), "Owl", Address::parse("203.0.113.33:4672")),
        Friend::new(hash("f2"), "marta.r", Address::parse("203.0.113.60:4662")),
        Friend::new(hash("f3"), "Ranger", None),
    ]
}

fn search_result(
    name: &str,
    size: u64,
    sources: u32,
    complete: u32,
    file_type: Option<&str>,
    media: Media,
) -> SearchResult {
    SearchResult {
        hash: hash(name),
        name: name.to_owned(),
        size,
        sources,
        complete_sources: Some(complete),
        file_type: file_type.map(str::to_owned),
        media,
        origin: Origin::default(),
        spam_rating: 0,
        aich_hash: None,
        names: Vec::new(),
    }
}

fn demo_system_results() -> Vec<SearchResult> {
    vec![
        search_result("lumenos-4.2-amd64-dvd.iso", 3_994_091_520, 214, 180, Some("Iso"), Media::default()),
        search_result("lumenos-4.2-amd64-netinst.iso", 702_545_920, 512, 498, Some("Iso"), Media::default()),
        search_result("lumenos-4.2-arm64-netinst.iso", 593_494_016, 87, 80, Some("Iso"), Media::default()),
        search_result("lumenos-3.9-amd64-netinst.iso", 662_700_032, 301, 290, Some("Iso"), Media::default()),
        search_result("lumenos-live-4.2-amd64-maple.iso", 3_481_272_320, 45, 31, Some("Iso"), Media::default()),
        search_result("lumenos-live-4.2-amd64-birch.iso", 3_212_836_864, 52, 40, Some("Iso"), Media::default()),
        search_result("LumenOS_Handbook_4_en.pdf", 9_437_184, 33, 33, Some("Doc"), Media::default()),
        search_result("lumenos-4-cheatsheet.png", 1_258_291, 12, 12, Some("Image"), Media::default()),
        search_result("lumenos 4 ULTIMATE PRO EDITION.exe", 2_457_600, 3, 0, Some("Pro"), Media::default()),
        search_result(
            "lumenos-installer-tutorial.mp4",
            156_237_824,
            8,
            2,
            Some("Video"),
            Media::new("", "", "Installing LumenOS 4", 1_260, 2_400, "h264"),
        ),
        search_result("lumenos-4.2-i386-netinst.iso", 650_117_120, 19, 17, Some("Iso"), Media::default()),
        search_result("lumenos-4.2-amd64-dvd.iso.torrent", 77_824, 4, 4, None, Media::default()),
    ]
}

fn demo_music_results() -> Vec<SearchResult> {
    vec![
        search_result(
            "Aurora_Quartet_-_Night_Sketches_-_Prelude.flac",
            31_457_280,
            41,
            41,
            Some("Audio"),
            Media::new("Aurora Quartet", "Night Sketches", "Prelude", 263, 1_010, "flac"),
        ),
        search_result(
            "Elm_Street_Ensemble_-_Nocturne_No2.mp3",
            7_340_032,
            120,
            118,
            Some("Audio"),
            Media::new("Elm Street Ensemble", "Nocturnes", "Nocturne No. 2", 271, 320, "mp3"),
        ),
        search_result(
            "Elm_Street_Ensemble_-_Four_Winds_-_Spring.ogg",
            12_582_912,
            66,
            60,
            Some("Audio"),
            Media::new("Elm Street Ensemble", "The Four Winds", "Spring", 612, 160, "vorbis"),
        ),
        search_result(
            "Lena_Hart_-_Moonlit_Sonata.mp3",
            14_680_064,
            95,
            90,
            Some("Audio"),
            Media::new("Lena Hart", "Sonatas", "Moonlit", 917, 128, "mp3"),
        ),
        search_result(
            "Studio_Sessions_-_Slow_Waltz_1.flac",
            26_214_400,
            23,
            20,
            Some("Audio"),
            Media::new("Studio Sessions", "Quiet Pieces", "Slow Waltz No. 1", 189, 1_100, "flac"),
        ),
        search_result("night_music_collection_2026.zip", 1_073_741_824, 6, 1, Some("Arc"), Media::default()),
    ]
}

fn demo_live_results() -> Vec<SearchResult> {
    vec![
        search_result(
            "Paper_Boats_2160p_60fps.mp4",
            712_934_400,
            77,
            70,
            Some("Video"),
            Media::new("", "", "Paper Boats", 634, 8_900, "h264"),
        ),
        search_result(
            "Paper_Boats_1080p_surround.avi",
            928_514_048,
            140,
            133,
            Some("Video"),
            Media::new("", "", "Paper Boats", 596, 12_000, "mpeg4"),
        ),
        search_result(
            "paper_boats_480p_stereo.ogg",
            157_286_400,
            29,
            25,
            Some("Video"),
            Media::new("", "", "Paper Boats", 596, 2_000, "theora"),
        ),
        search_result(
            "PaperBoats_soundtrack.flac",
            104_857_600,
            11,
            11,
            Some("Audio"),
            Media::new("Jonas Reed", "Paper Boats OST", "Main theme", 418, 1_000, "flac"),
        ),
    ]
}

fn demo_browse_results() -> Vec<SearchResult> {
    vec![
        search_result("Copper_Kite_1080p.avi", 854_589_440, 1, 1, Some("Video"), Media::default()),
        search_result("Harbor.Lights.2024.1080p.x264.mkv", 1_245_822_976, 1, 1, Some("Video"), Media::default()),
        search_result(
            "Aurora_Quartet_-_Night_Sketches_-_Prelude.flac",
            31_457_280,
            1,
            1,
            Some("Audio"),
            Media::default(),
        ),
        search_result("The_Lantern_Keeper.pdf", 4_823_449, 1, 1, Some("Doc"), Media::default()),
    ]
}

fn demo_saved_searches() -> Vec<SavedSearch> {
    vec![
        SavedSearch {
            title: "lumenos".to_owned(),
            request: SearchRequest::new("lumenos", RequestScope::Global),
            results: demo_system_results(),
        },
        SavedSearch {
            title: "night music".to_owned(),
            request: SearchRequest::new("night music", RequestScope::Kad),
            results: demo_music_results(),
        },
    ]
}

fn demo_spam_marks() -> SpamMarks {
    SpamMarks {
        spam: HashSet::from([hash("lumenos 4 ULTIMATE PRO EDITION.exe")]),
        not_spam: HashSet::new(),
        names: HashSet::new(),
    }
}

fn demo_file_history() -> FileHistory {
    FileHistory::of(HashSet::from([hash("lumenos-3.9-amd64-netinst.iso")]), HashSet::new())
}

fn demo_peer_comments() -> Vec<PeerComment> {
    vec![
        PeerComment {
            user: "Fox".to_owned(),
            file_name: "Harbor.Lights.2024.1080p.x264.mkv".to_owned(),
            comment: FileComment::of("Great quality, 5.1 audio", 5),
        },
        PeerComment {
            user: "Kiwi".to_owned(),
            file_name: "Harbor_Lights_1080p.mkv".to_owned(),
            comment: FileComment::of("Subtitles included", 4),
        },
    ]
}

fn demo_kad_notes() -> Vec<KadNote> {
    vec![KadNote {
        publisher: "astro_nauta".to_owned(),
        file_name: "Harbor.Lights.2024.1080p.x264.mkv".to_owned(),
        comment: FileComment::of("Verified file", 5),
    }]
}

fn demo_log() -> Vec<LogLine> {
    let base: SystemTime = Timestamp::from_str("2026-10-15T08:40:00Z")
        .ok()
        .and_then(|ts| u64::try_from(ts.as_second()).ok())
        .and_then(|secs| UNIX_EPOCH.checked_add(Duration::from_secs(secs)))
        .unwrap_or(UNIX_EPOCH);
    let at = |secs: u64| base.checked_add(Duration::from_secs(secs)).unwrap_or(base);
    vec![
        LogLine { time: at(0), level: Level::Info, message: "njMule started".to_owned() },
        LogLine { time: at(1), level: Level::Info, message: "26 shared files in 3 folders".to_owned() },
        LogLine { time: at(2), level: Level::Debug, message: "UPnP, NAT-PMP and PCP: no router answered".to_owned() },
        LogLine {
            time: at(3),
            level: Level::Info,
            message: "Connecting to Lighthouse (203.0.113.10:4661)\u{2026}".to_owned(),
        },
        LogLine { time: at(5), level: Level::Success, message: "Connected to Lighthouse with a high ID".to_owned() },
        LogLine { time: at(9), level: Level::Info, message: "Kad: connected, 1243 contacts".to_owned() },
        LogLine {
            time: at(40),
            level: Level::Warning,
            message: "Not enough space for lumenos-5.0-beta-desktop-amd64.iso".to_owned(),
        },
        LogLine {
            time: at(61),
            level: Level::Error,
            message: "The_Lantern_Keeper_-_audiobook.zip: part 41 corrupt, downloaded again".to_owned(),
        },
        LogLine {
            time: at(90),
            level: Level::Success,
            message: "Download completed: Pinecone_Office_7.3_Win_x64.msi".to_owned(),
        },
    ]
}

fn demo_schedule() -> Schedule {
    let weekdays = BTreeSet::from([
        DayOfWeek::Monday,
        DayOfWeek::Tuesday,
        DayOfWeek::Wednesday,
        DayOfWeek::Thursday,
        DayOfWeek::Friday,
    ]);
    let weekend = BTreeSet::from([DayOfWeek::Saturday, DayOfWeek::Sunday]);
    let hms = |h: u8| Time::from_hms(h, 0, 0).unwrap_or(Time::MIDNIGHT);
    Schedule {
        rules: vec![
            Rule {
                name: "Night".to_owned(),
                enabled: true,
                days: weekdays.clone(),
                start: hms(1),
                end: hms(7),
                upload_kibps: None,
                download_kibps: None,
                max_connections: Some(800),
                max_sources_per_file: None,
                stop_category: None,
                resume_category: Some(1),
            },
            Rule {
                name: "Office hours".to_owned(),
                enabled: true,
                days: weekdays,
                start: hms(9),
                end: hms(18),
                upload_kibps: Some(30),
                download_kibps: Some(200),
                max_connections: Some(150),
                max_sources_per_file: Some(100),
                stop_category: Some(2),
                resume_category: None,
            },
            Rule {
                name: "Weekend".to_owned(),
                enabled: false,
                days: weekend,
                start: Time::MIDNIGHT,
                end: Time::MIDNIGHT,
                upload_kibps: None,
                download_kibps: None,
                max_connections: None,
                max_sources_per_file: None,
                stop_category: None,
                resume_category: None,
            },
        ],
    }
}

fn demo_import_report() -> ImportReport {
    ImportReport {
        dry_run: true,
        items: vec![
            ImportItem::new(
                "preferences.ini",
                Outcome::WouldImport,
                vec![Phrase::of(ImportKey::Preferences, vec![]), Phrase::of(ImportKey::PortsOurs, vec![])],
            ),
            ImportItem::new(
                "server.met",
                Outcome::WouldImport,
                vec![Phrase::of(ImportKey::Servers, vec![214i64.into()])],
            ),
            ImportItem::new(
                "known.met",
                Outcome::WouldImport,
                vec![Phrase::of(ImportKey::KnownFiles, vec![1208i64.into()])],
            ),
            ImportItem::new(
                "clients.met",
                Outcome::WouldImport,
                vec![Phrase::of(ImportKey::Credits, vec![3402i64.into()])],
            ),
            ImportItem::new("cryptkey.dat", Outcome::Skipped, vec![Phrase::of(ImportKey::OwnIdentity, vec![])]),
            ImportItem::new(
                "nodes.dat",
                Outcome::WouldImport,
                vec![Phrase::of(ImportKey::KadContacts, vec![512i64.into()])],
            ),
            ImportItem::new("ipfilter.dat", Outcome::Missing, vec![]),
            ImportItem::new(
                "Category.ini",
                Outcome::WouldImport,
                vec![Phrase::of(ImportKey::Categories, vec![3i64.into()])],
            ),
            ImportItem::text("staticservers.dat", Outcome::Failed, "line 4 unreadable"),
        ],
        start_at_login: false,
    }
}

fn demo_events() -> Vec<EngineEvent> {
    let mut events = Vec::new();
    let primary = demo_server(0);
    events.push(EngineEvent::ServerConnecting(primary.clone()));
    for line in demo_log() {
        events.push(EngineEvent::Log(line));
    }
    events.push(EngineEvent::ServerConnected {
        server: primary.clone(),
        client_id: 0x1764_3C6A,
        low_id: false,
        obfuscated: true,
    });
    events.push(EngineEvent::ServerMessage {
        server: primary,
        text: "Welcome to Lighthouse! Rules: no fake files, no spam.".to_owned(),
    });
    events.push(EngineEvent::MessageReceived {
        address: "203.0.113.33:4672".to_owned(),
        user_hash: hash("f1"),
        nick: Some("Owl".to_owned()),
        text: "Hi! Do you have the 4K one too?".to_owned(),
    });
    events.push(EngineEvent::MessageReceived {
        address: "203.0.113.33:4672".to_owned(),
        user_hash: hash("f1"),
        nick: Some("Owl".to_owned()),
        text: "Thanks for Harbor Lights :)".to_owned(),
    });
    events.push(EngineEvent::MessageReceived {
        address: "198.51.100.77:4662".to_owned(),
        user_hash: hash("x9"),
        nick: Some("Fog".to_owned()),
        text: "May I add you as a friend?".to_owned(),
    });
    events.push(EngineEvent::CaptchaRequested {
        address: "192.0.2.99:4662".to_owned(),
        user_hash: hash("c9"),
        image: demo_captcha_bmp(),
    });
    events
}

// 120x36 uncompressed 24-bit BMP showing "K7Q2M" with a diagonal strike-through line, no external image crate needed.
fn demo_captcha_bmp() -> Vec<u8> {
    const W: usize = 120;
    const H: usize = 36;
    const BG: [u8; 3] = [0xEE, 0xEE, 0xEE];
    const FG: [u8; 3] = [0x55, 0x44, 0x33]; // BGR for #334455
    const GLYPHS: [[&str; 7]; 5] = [
        ["#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"], // K
        ["#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."], // 7
        [".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"], // Q
        [".###.", "#...#", "....#", "..##.", ".#...", "#....", "#####"], // 2
        ["#...#", "##.##", "#.#.#", "#...#", "#...#", "#...#", "#...#"], // M
    ];

    let mut pixels = vec![BG; W.saturating_mul(H)];
    let mut set_pixel = |x: usize, y: usize| {
        if x < W && y < H {
            let idx = y.saturating_mul(W).saturating_add(x);
            if let Some(slot) = pixels.get_mut(idx) {
                *slot = FG;
            }
        }
    };

    for (gi, glyph) in GLYPHS.iter().enumerate() {
        let base_x = 14usize.saturating_add(gi.saturating_mul(19));
        let base_y = 7usize;
        for (ry, row) in glyph.iter().enumerate() {
            for (rx, ch) in row.bytes().enumerate() {
                if ch == b'#' {
                    for dy in 0..3usize {
                        for dx in 0..3usize {
                            set_pixel(
                                base_x.saturating_add(rx.saturating_mul(3)).saturating_add(dx),
                                base_y.saturating_add(ry.saturating_mul(3)).saturating_add(dy),
                            );
                        }
                    }
                }
            }
        }
    }

    // Diagonal line from (5, 20) to (115, 12).
    for x in 5..=115usize {
        let progress = x.saturating_sub(5);
        let y = 20usize.saturating_sub(progress.saturating_mul(8) / 110);
        set_pixel(x, y);
    }

    // 120 * 3 = 360 bytes per row (already a multiple of 4).
    let pixel_bytes = W.saturating_mul(H).saturating_mul(3);
    let file_size = 54usize.saturating_add(pixel_bytes);
    let mut out = Vec::with_capacity(file_size);
    // BITMAPFILEHEADER (14 bytes)
    out.extend_from_slice(b"BM");
    out.extend_from_slice(&u32::try_from(file_size).unwrap_or(0).to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&54u32.to_le_bytes());
    // BITMAPINFOHEADER (40 bytes)
    out.extend_from_slice(&40u32.to_le_bytes());
    out.extend_from_slice(&i32::try_from(W).unwrap_or(0).to_le_bytes());
    out.extend_from_slice(&i32::try_from(H).unwrap_or(0).to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes()); // planes
    out.extend_from_slice(&24u16.to_le_bytes()); // bpp
    out.extend_from_slice(&0u32.to_le_bytes()); // BI_RGB
    out.extend_from_slice(&u32::try_from(pixel_bytes).unwrap_or(0).to_le_bytes());
    out.extend_from_slice(&2835i32.to_le_bytes()); // x ppm
    out.extend_from_slice(&2835i32.to_le_bytes()); // y ppm
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    // Bottom-up BGR rows
    for row in (0..H).rev() {
        let row_start = row.saturating_mul(W);
        for col in 0..W {
            let bgr = pixels.get(row_start.saturating_add(col)).copied().unwrap_or(BG);
            out.extend_from_slice(&bgr);
        }
    }
    out
}
