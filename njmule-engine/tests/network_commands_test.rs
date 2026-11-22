// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Twin of Java's `org.njmule.app.NetworkCommandsTest`: the command-line network work against a scripted engine.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::{BTreeMap, VecDeque};
use std::io::{self, Write};
use std::net::{SocketAddr, SocketAddrV4};
use std::path::PathBuf;
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
use njmule_engine::api::peer_info::{Identification, PeerInfo};
use njmule_engine::api::port_test::PortTest;
use njmule_engine::api::saved_search::SavedSearch;
use njmule_engine::api::search_result::SearchResult;
use njmule_engine::api::server_info::ServerInfo;
use njmule_engine::api::shared_directory::SharedDirectory;
use njmule_engine::api::shared_file_info::SharedFileInfo;
use njmule_engine::api::spam_marks::SpamMarks;
use njmule_engine::api::update_status::UpdateStatus;
use njmule_engine::api::upload_info::{BanInfo, UploadInfo};
use njmule_engine::api::upload_priority::UploadPriority;
use njmule_engine::app::launch_options::LaunchOptions;
use njmule_engine::app::network_commands;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::config::schedule::{Rule, Schedule};
use njmule_engine::engine::NotesError;
use njmule_engine::engine::network::{ConnectError, MoreAnswer};
use njmule_engine::files::part_file::PartMap;
use njmule_engine::search::search_manager::{Scope, StartError};
use njmule_engine::server::server_list::{Address, Priority};
use tempfile::TempDir;
use tokio::sync::broadcast;

use crate::fake_engine::FakeEngine;

const SERVER: &str = "10.0.0.1:4661";
const USER: [u8; 16] = [0x0F; 16];
const GOOD_HASH: [u8; 16] = [0xAA; 16];
const BAD_HASH: [u8; 16] = [0xBB; 16];

fn good_link() -> FileLink {
    FileLink::new("good.bin".to_owned(), 1000, GOOD_HASH, None, Vec::new(), Vec::new(), Vec::new()).unwrap()
}

fn bad_link() -> FileLink {
    FileLink::new("bad.bin".to_owned(), 3u64 << 20, BAD_HASH, None, Vec::new(), Vec::new(), Vec::new()).unwrap()
}

fn file_link_uri(name: &str, size: u64, byte: u8) -> String {
    FileLink::new(name.to_owned(), size, [byte; 16], None, Vec::new(), Vec::new(), Vec::new()).unwrap().to_uri()
}

fn user_hex() -> String {
    data_encoding::HEXUPPER.encode(&USER)
}

#[tokio::test]
async fn should_add_servers_share_and_join_kad_without_waiting() {
    let mut test = Scenario::new();
    test.given_add_servers_returns(Ok(3));
    let music = test.folder("music");
    let films = test.folder("films");

    test.when_the_commands_run_with(&[
        "--add-servers",
        "server.met",
        "--share",
        &music,
        &films,
        "--kad-bootstrap",
        "10.0.0.9:4672",
    ])
    .await;

    test.then_the_exit_code_is(network_commands::EXIT_OK);
    test.then_the_output_contains(&[
        "Added 3 servers from server.met",
        &format!("Sharing {music}"),
        &format!("Sharing {films}"),
        "Joining Kad through 10.0.0.9:4672",
    ]);
    test.then_the_engine_is_never_asked_to("connect");
}

#[tokio::test]
async fn should_only_connect_to_a_server_alone() {
    let mut test = Scenario::new();

    test.when_the_commands_run_with(&["--connect-server", SERVER]).await;

    test.then_the_exit_code_is(network_commands::EXIT_OK);
    test.then_the_calls_are(&[&format!("connect[{SERVER}]")]);
    test.then_there_is_no_output();
}

#[tokio::test]
async fn should_end_the_run_with_the_reason_of_a_failed_command() {
    let mut test = Scenario::new();
    test.given_add_servers_returns(Err("no such file".to_owned()));

    test.when_the_commands_run_with(&["--add-servers", "missing.met"]).await;

    test.then_the_exit_code_is(network_commands::EXIT_FAILURE);
    test.then_the_errors_contain(&["Failed: no such file"]);
}

#[tokio::test]
async fn should_report_what_an_import_took_file_by_file() {
    let mut test = Scenario::new();
    test.given_an_import_that_took(vec![
        Item::text("server.met", Outcome::Imported, "12 servers"),
        Item::text("cryptkey.dat", Outcome::Skipped, "not asked"),
    ]);
    let emule = test.folder("emule");

    test.when_the_commands_run_with(&["--import-emule", &emule]).await;

    test.then_the_exit_code_is(network_commands::EXIT_OK);
    test.then_the_output_contains(&[&format!("Importing from {emule}")]);
    test.then_the_output_has_line_parts("  imported", "server.met", "12 servers");
    test.then_the_output_has_line_parts("  skipped", "cryptkey.dat", "not asked");
}

#[tokio::test]
async fn should_greet_a_peer_log_in_and_print_the_results_by_their_sources() {
    let mut test = Scenario::new();
    test.given_a_peer_that_answers_the_hello(PeerInfo {
        address: "10.0.0.2:4662".to_owned(),
        user_hash: USER,
        nick: Some("peer".to_owned()),
        software: "eMule".to_owned(),
        obfuscated: true,
        identification: Identification::Verified,
    });
    test.given_a_connection_that_sends(vec![
        EngineEvent::ServersChanged { count: 1 },
        EngineEvent::ServerConnecting(server(SERVER, SERVER)),
        EngineEvent::ServerMessage { server: server(SERVER, "Fake"), text: "welcome".to_owned() },
        EngineEvent::ServerConnected {
            server: server(SERVER, "Fake"),
            client_id: 1234,
            low_id: true,
            obfuscated: true,
        },
    ]);
    test.given_a_search_that_sends(
        7,
        vec![
            EngineEvent::SearchResults { search: 7, results: vec![result("few.iso", 2)] },
            EngineEvent::SearchResults { search: 8, results: vec![result("someone else's.iso", 99)] },
            EngineEvent::ServerMessage { server: server(SERVER, "Fake"), text: "busy".to_owned() },
            EngineEvent::SearchResults { search: 7, results: vec![result("many.iso", 50)] },
            EngineEvent::SearchEnded { search: 8, results: 1 },
            EngineEvent::SearchEnded { search: 7, results: 2 },
        ],
    );

    test.when_the_commands_run_with(&["--hello", "10.0.0.2:4662", "--search", "iso"]).await;

    test.then_the_exit_code_is(network_commands::EXIT_OK);
    test.then_the_output_contains(&[
        &format!(
            "Hello answered by 10.0.0.2:4662: eMule, \"peer\", obfuscated, user hash {}, identity verified",
            user_hex()
        ),
        &format!("Connecting to {SERVER}\n"),
        "  > welcome",
        &format!("Connected to Fake ({SERVER}) with LowID 1234, obfuscated"),
        "Searching \"iso\" on the server",
        "  > busy",
        "ed2k://|file|many.iso|1000|",
        "2 results",
    ]);
    test.then_the_output_does_not_contain(&["someone else's.iso", "Keeping the connection open"]);
    test.then_the_output_shows_before("many.iso", "few.iso");
    test.then_the_calls_include(&["connect[none]", "stopSearch[7]"]);
}

#[tokio::test]
async fn should_tell_how_the_peer_proved_its_identity() {
    let cases = [
        (Identification::Failed, "NOT verified"),
        (Identification::NotSupported, "not offered"),
        (Identification::Pending, "not proven in time"),
    ];
    for (outcome, identity) in cases {
        let mut test = Scenario::new();
        test.given_connect_fails("no server");
        test.given_a_peer_that_answers_the_hello(PeerInfo {
            address: "10.0.0.2:4662".to_owned(),
            user_hash: USER,
            nick: None,
            software: "aMule".to_owned(),
            obfuscated: false,
            identification: outcome,
        });

        test.when_the_commands_run_with(&["--hello", "10.0.0.2:4662", "--search", "iso"]).await;

        test.then_the_exit_code_is(network_commands::EXIT_FAILURE);
        test.then_the_output_contains(&[&format!(
            "Hello answered by 10.0.0.2:4662: aMule, user hash {}, identity {identity}",
            user_hex()
        )]);
        test.then_the_output_does_not_contain(&["\"", "obfuscated"]);
        test.then_the_errors_contain(&["Failed: no server"]);
    }
}

#[tokio::test]
async fn should_keep_the_connection_of_a_hello_alone_open_for_a_while() {
    let mut test = Scenario::new();
    test.given_a_peer_that_answers_the_hello(PeerInfo {
        address: "10.0.0.2:4662".to_owned(),
        user_hash: USER,
        nick: None,
        software: "aMule".to_owned(),
        obfuscated: false,
        identification: Identification::Verified,
    });
    let handle = test.given_the_commands_running_with(&["--hello", "10.0.0.2:4662"]);
    test.given_the_output_shows_within("Keeping the connection open", Duration::from_secs(10)).await;

    handle.abort();
    let _ = handle.await;

    test.then_the_output_contains(&[&format!(
        "Keeping the connection open for {}",
        network_commands::HELLO_HOLD.as_secs()
    )]);
}

#[tokio::test]
async fn should_end_the_run_on_a_refused_login() {
    let mut test = Scenario::new();
    test.given_a_connection_that_sends(vec![
        EngineEvent::ServerConnecting(server(SERVER, "Fake")),
        EngineEvent::ServerDisconnected { server: None, reason: "refused".to_owned() },
    ]);

    test.when_the_commands_run_with(&["--connect-server", SERVER, "--search", "iso"]).await;

    test.then_the_exit_code_is(network_commands::EXIT_FAILURE);
    test.then_the_output_contains(&[&format!("Connecting to Fake ({SERVER})")]);
    test.then_the_errors_contain(&["Not connected: refused"]);
    test.then_the_engine_is_never_asked_to("search");
}

#[tokio::test]
async fn should_end_a_global_search_when_the_server_goes() {
    let mut test = Scenario::new();
    test.given_a_server_that_takes_us_with_a_high_id();
    test.given_a_search_that_sends(
        3,
        vec![
            EngineEvent::SearchResults { search: 3, results: vec![result("kept.iso", 4)] },
            EngineEvent::ServersChanged { count: 2 },
            EngineEvent::ServerDisconnected { server: None, reason: "connection lost".to_owned() },
        ],
    );

    test.when_the_commands_run_with(&["--connect-server", SERVER, "--search", "iso", "--global"]).await;

    test.then_the_exit_code_is(network_commands::EXIT_OK);
    test.then_the_output_contains(&[
        &format!("Connected to {SERVER} with HighID 99\n"),
        "on every known server",
        "kept.iso",
        "1 results",
    ]);
    test.then_the_errors_contain(&["Disconnected: connection lost"]);
    test.then_a_call_contains("scope=Global");
}

#[tokio::test]
async fn should_end_a_server_search_once_its_results_stop() {
    let mut test = Scenario::new();
    test.given_a_server_that_takes_us_with_a_high_id();
    test.given_a_search_that_sends(
        4,
        vec![EngineEvent::SearchResults { search: 4, results: vec![result("only.iso", 1)] }],
    );

    test.when_the_commands_run_with(&["--connect-server", SERVER, "--search", "iso"]).await;

    test.then_the_exit_code_is(network_commands::EXIT_OK);
    test.then_the_run_took_at_least_but_less_than(network_commands::SEARCH_QUIET, network_commands::SEARCH_WAIT);
    test.then_the_output_contains(&["only.iso", "1 results"]);
}

#[tokio::test]
async fn should_need_kad_running_for_a_kad_search() {
    let mut test = Scenario::new();
    test.given_kad_is_off();

    test.when_the_commands_run_with(&["--search", "iso", "--kad"]).await;

    test.then_the_exit_code_is(network_commands::EXIT_FAILURE);
    test.then_the_errors_contain(&["Kad is not running"]);
    test.then_the_engine_is_never_asked_to("search");
}

#[tokio::test]
async fn should_wait_for_enough_kad_contacts_then_search_and_download() {
    let mut test = Scenario::new();
    test.given_kad_goes_through(vec![
        kad(false, 3, 0, true),
        kad(true, 10, 2, true),
        kad(true, 40, network_commands::KAD_CONTACTS, false),
    ]);
    test.given_a_search_that_sends(5, vec![EngineEvent::SearchEnded { search: 5, results: 0 }]);
    test.given_each_download_completes_at_once();
    let link = file_link_uri("found.iso", 4096, 0xAB);

    test.when_the_commands_run_with(&["--search", "iso", "--kad", "--download", &link]).await;

    test.then_the_exit_code_is(network_commands::EXIT_OK);
    test.then_the_output_contains(&[
        "Kad: 3 contacts, 0 verified, firewalled",
        &format!("Kad: 40 contacts, {} verified, connected, reachable", network_commands::KAD_CONTACTS),
        "Searching \"iso\" on Kad",
        "0 results",
        "Downloading found.iso (4.0 KiB, 1 source)",
        "Finished found.iso",
    ]);
    test.then_the_output_does_not_contain(&["Kad: 10 contacts"]);
}

#[tokio::test]
async fn should_end_a_kad_search_alone_with_its_results() {
    let mut test = Scenario::new();
    test.given_kad_goes_through(vec![kad(true, 50, network_commands::KAD_CONTACTS + 1, false)]);
    test.given_a_search_that_sends(
        9,
        vec![
            EngineEvent::SearchResults { search: 9, results: vec![result("kad.iso", 7)] },
            EngineEvent::SearchEnded { search: 9, results: 1 },
        ],
    );

    test.when_the_commands_run_with(&["--search", "iso", "--kad"]).await;

    test.then_the_exit_code_is(network_commands::EXIT_OK);
    test.then_the_output_contains(&["Searching \"iso\" on Kad", "kad.iso", "1 results"]);
    test.then_a_call_contains("scope=Kad");
    test.then_the_engine_is_never_asked_to("connect");
}

#[tokio::test]
async fn should_wait_only_for_the_login_with_a_server_and_downloads() {
    let mut test = Scenario::new();
    test.given_a_server_that_takes_us_with_a_high_id();
    test.given_each_download_completes_at_once();
    let link = file_link_uri("direct.iso", 2048, 0xFA);

    test.when_the_commands_run_with(&["--connect-server", SERVER, "--download", &link]).await;

    test.then_the_exit_code_is(network_commands::EXIT_OK);
    test.then_the_output_contains(&[&format!("Connected to {SERVER}"), "Finished direct.iso"]);
    test.then_the_engine_is_never_asked_to("search");
}

#[tokio::test]
async fn should_report_each_download_outcome_and_fail_when_one_fails() {
    let mut test = Scenario::new();
    test.given_downloads_where_the_bad_one_fails_amid_other_events();

    test.when_the_commands_run_with(&["--download", &good_link().to_uri(), &bad_link().to_uri()]).await;

    test.then_the_exit_code_is(network_commands::EXIT_FAILURE);
    test.then_the_output_contains(&[
        "Downloading good.bin (1000 B, 1 source)",
        "Downloading bad.bin (3.0 MiB, 3 sources)",
        &format!("Connected to {SERVER}"),
        "Finished good.bin: good.bin",
    ]);
    test.then_the_output_does_not_contain(&["other.bin"]);
    test.then_the_errors_contain(&["Failed bad.bin: disk full", "Disconnected from the server: lost"]);
    test.then_the_errors_do_not_contain(&["not ours"]);
}

#[tokio::test]
async fn should_search_then_download_what_was_asked() {
    let mut test = Scenario::new();
    test.given_a_server_that_takes_us_with_a_high_id();
    test.given_a_search_that_sends(6, vec![EngineEvent::SearchEnded { search: 6, results: 0 }]);
    test.given_each_download_completes_at_once();
    let link = file_link_uri("wanted.iso", 2048, 0xDD);

    test.when_the_commands_run_with(&["--connect-server", SERVER, "--search", "iso", "--download", &link]).await;

    test.then_the_exit_code_is(network_commands::EXIT_OK);
    test.then_the_output_contains(&["0 results", "Finished wanted.iso"]);
}

#[tokio::test]
async fn should_need_no_server_for_downloads_alone() {
    let mut test = Scenario::new();
    test.given_each_download_completes_at_once();
    let link = file_link_uri("alone.iso", 2048, 0xEE);

    test.when_the_commands_run_with(&["--download", &link]).await;

    test.then_the_exit_code_is(network_commands::EXIT_OK);
    test.then_the_output_contains(&["Finished alone.iso"]);
    test.then_the_engine_is_never_asked_to("connect");
}

#[test]
fn should_write_sizes_in_binary_units() {
    let cases =
        [(0u64, "0 B"), (1023, "1023 B"), (1536, "1.5 KiB"), (3u64 << 40, "3.0 TiB"), (2048u64 << 40, "2048.0 TiB")];
    for (bytes, expected) in cases {
        assert_eq!(network_commands::size(bytes), expected);
    }
}

#[test]
fn should_write_the_progress_of_an_empty_download_as_complete() {
    let info = DownloadInfo {
        hash: [0xAB; 16],
        name: "empty.txt".to_owned(),
        size: 0,
        completed: 0,
        status: DownloadStatus::Downloading,
        priority: DownloadPriority::Normal,
        category: 0,
        sources: 2,
        transferring: 1,
        bytes_per_second: 2048,
        parts: PartMap { size: 0, missing: Vec::new(), requested: Vec::new(), sources: Vec::new() },
        preview_first: false,
        auto_swap: false,
    };
    assert_eq!(network_commands::progress(&info), " 100.0%       2.0 KiB/s  1/2 sources  empty.txt");
}

#[test]
fn should_write_the_progress_of_a_download_a_quarter_done() {
    let info = DownloadInfo {
        hash: [0xAB; 16],
        name: "a.iso".to_owned(),
        size: 4000,
        completed: 1000,
        status: DownloadStatus::Downloading,
        priority: DownloadPriority::Normal,
        category: 0,
        sources: 0,
        transferring: 0,
        bytes_per_second: 0,
        parts: PartMap { size: 0, missing: Vec::new(), requested: Vec::new(), sources: Vec::new() },
        preview_first: false,
        auto_swap: false,
    };
    let text = network_commands::progress(&info);
    assert!(text.starts_with("  25.0%"), "{text:?}");
    assert!(text.ends_with("0/0 sources  a.iso"), "{text:?}");
}

#[test]
fn should_write_the_kad_state() {
    let state = kad(true, 5, 4, true);
    assert_eq!(network_commands::kad_line(&state), "Kad: 5 contacts, 4 verified, connected, firewalled");
}

#[test]
fn should_tell_the_tested_udp_state_and_the_buddy_in_the_kad_line() {
    let state = KadInfo {
        connected: true,
        contacts: 5,
        verified_contacts: 4,
        firewalled: true,
        id: String::new(),
        public_ip: String::new(),
        udp_port: 4672,
        udp_firewalled: true,
        udp_verified: true,
        buddy: Buddy::Connected,
        users: 0,
        files: 0,
    };
    assert_eq!(
        network_commands::kad_line(&state),
        "Kad: 5 contacts, 4 verified, connected, firewalled, UDP firewalled, with a buddy"
    );
}

#[derive(Clone, Default)]
struct SharedBuffer(Arc<Mutex<Vec<u8>>>);

impl SharedBuffer {
    fn text(&self) -> String {
        String::from_utf8_lossy(&self.0.lock().unwrap()).into_owned()
    }
}

impl Write for SharedBuffer {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

enum DownloadMode {
    None,
    CompleteAtOnce,
    BadFails,
}

struct ScriptState {
    calls: Vec<String>,
    add_servers: Result<usize, String>,
    import_items: Vec<Item>,
    hello_peer: Option<PeerInfo>,
    connect_fail: Option<String>,
    connect_events: Vec<EngineEvent>,
    search_id: u64,
    search_events: Vec<EngineEvent>,
    kad_states: Option<VecDeque<KadInfo>>,
    download_mode: DownloadMode,
}

impl Default for ScriptState {
    fn default() -> Self {
        Self {
            calls: Vec::new(),
            add_servers: Ok(0),
            import_items: Vec::new(),
            hello_peer: None,
            connect_fail: None,
            connect_events: Vec::new(),
            search_id: 1,
            search_events: Vec::new(),
            kad_states: None,
            download_mode: DownloadMode::None,
        }
    }
}

#[derive(Clone)]
struct ScriptedEngine {
    base: FakeEngine,
    state: Arc<Mutex<ScriptState>>,
    events: broadcast::Sender<EngineEvent>,
}

impl ScriptedEngine {
    fn new(dir: &std::path::Path) -> Self {
        let (events, _) = broadcast::channel(128);
        Self { base: FakeEngine::new(dir), state: Arc::new(Mutex::new(ScriptState::default())), events }
    }
}

impl NjMuleEngine for ScriptedEngine {
    fn preferences(&self) -> Preferences {
        self.base.preferences()
    }
    fn directories(&self) -> AppDirectories {
        self.base.directories()
    }
    fn setup_pending(&self) -> impl Future<Output = bool> + Send {
        self.base.setup_pending()
    }
    fn finish_setup(&self) -> impl Future<Output = Result<(), String>> + Send {
        self.base.finish_setup()
    }
    fn test_notifications(&self) -> impl Future<Output = Vec<NotificationResult>> + Send {
        self.base.test_notifications()
    }
    async fn import_from_emule(&self, _emule: PathBuf, options: ImportOptions) -> Result<ImportReport, String> {
        let mut s = self.state.lock().unwrap();
        s.calls.push("importFromEmule".to_owned());
        Ok(ImportReport { dry_run: options.dry_run, items: s.import_items.clone(), start_at_login: false })
    }
    fn emule_profile(&self) -> impl Future<Output = Option<PathBuf>> + Send {
        self.base.emule_profile()
    }
    fn emule_history(&self) -> impl Future<Output = EmuleHistory> + Send {
        self.base.emule_history()
    }
    fn status(&self) -> impl Future<Output = Option<EngineStatus>> + Send {
        self.base.status()
    }
    fn saved_searches(&self) -> impl Future<Output = Vec<SavedSearch>> + Send {
        self.base.saved_searches()
    }
    fn save_searches(&self, searches: Vec<SavedSearch>) -> impl Future<Output = Result<(), String>> + Send {
        self.base.save_searches(searches)
    }
    fn categories(&self) -> impl Future<Output = Vec<Category>> + Send {
        self.base.categories()
    }
    fn set_categories(&self, categories: Vec<Category>) -> impl Future<Output = Result<(), String>> + Send {
        self.base.set_categories(categories)
    }
    fn schedule(&self) -> impl Future<Output = Schedule> + Send {
        self.base.schedule()
    }
    fn active_rule(&self) -> impl Future<Output = Option<Rule>> + Send {
        self.base.active_rule()
    }
    fn update_schedule(&self, schedule: Schedule) -> impl Future<Output = Result<Schedule, String>> + Send {
        self.base.update_schedule(schedule)
    }
    fn test_ports(&self) -> impl Future<Output = Option<PortTest>> + Send {
        self.base.test_ports()
    }
    fn port_test(&self) -> impl Future<Output = Option<PortTest>> + Send {
        self.base.port_test()
    }
    fn update_ip_filter(&self, url: String) -> impl Future<Output = Result<usize, String>> + Send {
        self.base.update_ip_filter(url)
    }
    fn update_ip_filter_lists(&self, urls: Vec<String>) -> impl Future<Output = Result<usize, String>> + Send {
        self.base.update_ip_filter_lists(urls)
    }
    fn update_preferences(&self, changes: &BTreeMap<String, String>) -> Result<Preferences, String> {
        self.base.update_preferences(changes)
    }
    fn subscribe(&self) -> Subscription {
        Subscription { missed: Vec::new(), live: self.events.subscribe() }
    }
    fn stopped(&self) -> BoxFuture<'static, ()> {
        self.base.stopped()
    }
    async fn hello(&self, _address: SocketAddr) -> io::Result<PeerInfo> {
        let mut s = self.state.lock().unwrap();
        s.calls.push("hello".to_owned());
        s.hello_peer.clone().ok_or_else(|| io::Error::other("no peer"))
    }
    fn browse(&self, address: SocketAddr) -> impl Future<Output = io::Result<Vec<SearchResult>>> + Send {
        self.base.browse(address)
    }
    fn known_clients(&self) -> impl Future<Output = Vec<KnownClient>> + Send {
        self.base.known_clients()
    }
    async fn add_servers(&self, _source: ListSource) -> Result<usize, ListError> {
        let mut s = self.state.lock().unwrap();
        s.calls.push("addServers".to_owned());
        match &s.add_servers {
            Ok(n) => Ok(*n),
            Err(message) => Err(ListError::Failed(message.clone())),
        }
    }
    fn add_server(&self, address: Address) -> impl Future<Output = bool> + Send {
        self.base.add_server(address)
    }
    fn remove_server(&self, address: Address) -> impl Future<Output = bool> + Send {
        self.base.remove_server(address)
    }
    fn set_server_priority(&self, address: Address, priority: Priority) -> impl Future<Output = bool> + Send {
        self.base.set_server_priority(address, priority)
    }
    fn server_list_addresses(&self) -> impl Future<Output = Vec<String>> + Send {
        self.base.server_list_addresses()
    }
    fn set_server_list_addresses(&self, addresses: Vec<String>) -> impl Future<Output = Result<(), String>> + Send {
        self.base.set_server_list_addresses(addresses)
    }
    async fn connect_server(&self, address: Option<Address>) -> Result<(), ConnectError> {
        let mut s = self.state.lock().unwrap();
        let label = address.map_or_else(|| "none".to_owned(), |a| a.to_string());
        s.calls.push(format!("connect[{label}]"));
        if let Some(reason) = &s.connect_fail {
            return Err(ConnectError::Refused(reason.clone()));
        }
        for event in s.connect_events.clone() {
            let _ = self.events.send(event);
        }
        Ok(())
    }
    fn disconnect_server(&self) -> impl Future<Output = ()> + Send {
        self.base.disconnect_server()
    }
    fn servers(&self) -> impl Future<Output = Vec<ServerInfo>> + Send {
        self.base.servers()
    }
    fn set_static_server(&self, address: Address, wanted: bool) -> impl Future<Output = bool> + Send {
        self.base.set_static_server(address, wanted)
    }
    async fn kad(&self) -> Option<KadInfo> {
        let mut s = self.state.lock().unwrap();
        let states = s.kad_states.as_mut()?;
        if states.len() > 1 { states.pop_front() } else { states.front().cloned() }
    }
    fn kad_contacts(&self) -> impl Future<Output = Vec<KadContactInfo>> + Send {
        self.base.kad_contacts()
    }
    fn kad_searches(&self) -> impl Future<Output = Vec<KadSearchInfo>> + Send {
        self.base.kad_searches()
    }
    fn kad_notes(&self, file: [u8; 16]) -> impl Future<Output = Vec<KadNote>> + Send {
        self.base.kad_notes(file)
    }
    fn search_kad_notes(&self, file: [u8; 16]) -> impl Future<Output = Result<Vec<KadNote>, NotesError>> + Send {
        self.base.search_kad_notes(file)
    }
    fn connect_kad(&self) -> impl Future<Output = ()> + Send {
        self.base.connect_kad()
    }
    fn disconnect_kad(&self) -> impl Future<Output = ()> + Send {
        self.base.disconnect_kad()
    }
    fn recheck_kad(&self) -> impl Future<Output = bool> + Send {
        self.base.recheck_kad()
    }
    async fn bootstrap_kad(&self, node: SocketAddrV4) -> Result<(), String> {
        self.state.lock().unwrap().calls.push(format!("kadBootstrap[{node}]"));
        Ok(())
    }
    fn add_kad_contacts(&self, source: ListSource) -> impl Future<Output = Result<usize, ListError>> + Send {
        self.base.add_kad_contacts(source)
    }
    async fn start_search(&self, query: String, scope: Option<Scope>) -> Result<u64, StartError> {
        let mut s = self.state.lock().unwrap();
        s.calls.push(format!("search[{query}, scope={:?}]", scope.unwrap_or(Scope::Server)));
        for event in s.search_events.clone() {
            let _ = self.events.send(event);
        }
        Ok(s.search_id)
    }
    fn more_results(&self, search: u64) -> impl Future<Output = MoreAnswer> + Send {
        self.base.more_results(search)
    }
    fn shared_directories(&self) -> impl Future<Output = Vec<SharedDirectory>> + Send {
        self.base.shared_directories()
    }
    async fn share_directory(&self, dir: PathBuf, _subdirectories: bool) -> Result<(), String> {
        self.state.lock().unwrap().calls.push(format!("shareDirectory[{}]", dir.display()));
        Ok(())
    }
    fn unshare_directory(&self, dir: PathBuf) -> impl Future<Output = Result<(), String>> + Send {
        self.base.unshare_directory(dir)
    }
    fn shared_files(&self) -> impl Future<Output = Vec<SharedFileInfo>> + Send {
        self.base.shared_files()
    }
    fn rename_shared_file(&self, hash: [u8; 16], name: String) -> impl Future<Output = Result<bool, String>> + Send {
        self.base.rename_shared_file(hash, name)
    }
    fn set_upload_priority(&self, hash: [u8; 16], priority: UploadPriority) -> impl Future<Output = bool> + Send {
        self.base.set_upload_priority(hash, priority)
    }
    async fn add_download(&self, link: FileLink, _paused: Option<bool>) -> Result<DownloadInfo, String> {
        let mut s = self.state.lock().unwrap();
        s.calls.push(format!("addDownload[{}]", link.name));
        match s.download_mode {
            DownloadMode::CompleteAtOnce => {
                let info = download_of(&link, 1);
                let _ = self
                    .events
                    .send(EngineEvent::DownloadCompleted { download: info.clone(), file: PathBuf::from(&info.name) });
                Ok(info)
            }
            DownloadMode::BadFails => {
                let other = download_of(
                    &FileLink::new("other.bin".to_owned(), 1, [0xCC; 16], None, Vec::new(), Vec::new(), Vec::new())
                        .unwrap(),
                    1,
                );
                if link.hash == BAD_HASH {
                    for event in [
                        EngineEvent::DownloadProgress(other.clone()),
                        EngineEvent::DownloadCompleted { download: other.clone(), file: PathBuf::from("other.bin") },
                        EngineEvent::DownloadFailed { download: other, reason: "not ours".to_owned() },
                        EngineEvent::DownloadProgress(download_of(&good_link(), 1)),
                        EngineEvent::ServerConnected {
                            server: server(SERVER, SERVER),
                            client_id: 5,
                            low_id: true,
                            obfuscated: false,
                        },
                        EngineEvent::ServerDisconnected { server: None, reason: "lost".to_owned() },
                        EngineEvent::DownloadCompleted {
                            download: download_of(&good_link(), 1),
                            file: PathBuf::from("good.bin"),
                        },
                        EngineEvent::DownloadFailed {
                            download: download_of(&bad_link(), 3),
                            reason: "disk full".to_owned(),
                        },
                    ] {
                        let _ = self.events.send(event);
                    }
                }
                Ok(download_of(&link, if link.hash == BAD_HASH { 3 } else { 1 }))
            }
            DownloadMode::None => Ok(download_of(&link, 1)),
        }
    }
    fn downloads(&self) -> impl Future<Output = Vec<DownloadInfo>> + Send {
        self.base.downloads()
    }
    fn sources(&self) -> impl Future<Output = Vec<SourceInfo>> + Send {
        self.base.sources()
    }
    fn uploads(&self) -> impl Future<Output = Vec<UploadInfo>> + Send {
        self.base.uploads()
    }
    fn bans(&self) -> impl Future<Output = Vec<BanInfo>> + Send {
        self.base.bans()
    }
    fn unban(&self, ip: String) -> impl Future<Output = Result<bool, String>> + Send {
        self.base.unban(ip)
    }
    fn pause_download(&self, hash: [u8; 16]) -> impl Future<Output = Option<DownloadInfo>> + Send {
        self.base.pause_download(hash)
    }
    fn resume_download(&self, hash: [u8; 16]) -> impl Future<Output = Option<DownloadInfo>> + Send {
        self.base.resume_download(hash)
    }
    fn cancel_download(&self, hash: [u8; 16]) -> impl Future<Output = bool> + Send {
        self.base.cancel_download(hash)
    }
    fn clear_completed_downloads(&self) -> impl Future<Output = usize> + Send {
        self.base.clear_completed_downloads()
    }
    fn clear_completed_download(&self, hash: [u8; 16]) -> impl Future<Output = bool> + Send {
        self.base.clear_completed_download(hash)
    }
    fn stop_download(&self, hash: [u8; 16]) -> impl Future<Output = bool> + Send {
        self.base.stop_download(hash)
    }
    fn restore_download(&self, hash: [u8; 16]) -> impl Future<Output = bool> + Send {
        self.base.restore_download(hash)
    }
    fn set_download_preview(&self, hash: [u8; 16], preview_first: bool) -> impl Future<Output = bool> + Send {
        self.base.set_download_preview(hash, preview_first)
    }
    fn set_download_auto_swap(&self, hash: [u8; 16], auto_swap: bool) -> impl Future<Output = bool> + Send {
        self.base.set_download_auto_swap(hash, auto_swap)
    }
    fn set_download_priority(&self, hash: [u8; 16], priority: DownloadPriority) -> impl Future<Output = bool> + Send {
        self.base.set_download_priority(hash, priority)
    }
    fn set_download_category(&self, hash: [u8; 16], category: u32) -> impl Future<Output = bool> + Send {
        self.base.set_download_category(hash, category)
    }
    fn rename_download(&self, hash: [u8; 16], name: String) -> impl Future<Output = Result<bool, String>> + Send {
        self.base.rename_download(hash, name)
    }
    fn add_source(&self, download: [u8; 16], source: SocketAddr) -> impl Future<Output = bool> + Send {
        self.base.add_source(download, source)
    }
    fn swap_source_to_download(&self, download: [u8; 16], source: SocketAddr) -> impl Future<Output = bool> + Send {
        self.base.swap_source_to_download(download, source)
    }
    fn swap_sources_to_download(&self, hash: [u8; 16]) -> impl Future<Output = usize> + Send {
        self.base.swap_sources_to_download(hash)
    }
    fn swap_sources_from_download(&self, hash: [u8; 16]) -> impl Future<Output = usize> + Send {
        self.base.swap_sources_from_download(hash)
    }
    fn download_data(&self, hash: [u8; 16]) -> impl Future<Output = Option<PathBuf>> + Send {
        self.base.download_data(hash)
    }
    fn peer_comments(&self, download: [u8; 16]) -> impl Future<Output = Vec<PeerComment>> + Send {
        self.base.peer_comments(download)
    }
    fn file_comment(&self, file: [u8; 16]) -> impl Future<Output = Option<FileComment>> + Send {
        self.base.file_comment(file)
    }
    fn friends(&self) -> impl Future<Output = Vec<Friend>> + Send {
        self.base.friends()
    }
    fn send_message(&self, to: SocketAddr, text: String) -> impl Future<Output = ()> + Send {
        self.base.send_message(to, text)
    }
    fn add_friend(&self, friend: Friend) -> impl Future<Output = Result<(), String>> + Send {
        self.base.add_friend(friend)
    }
    fn remove_friend(&self, user_hash: [u8; 16]) -> impl Future<Output = Result<bool, String>> + Send {
        self.base.remove_friend(user_hash)
    }
    fn friend_slot(&self) -> impl Future<Output = Option<[u8; 16]>> + Send {
        self.base.friend_slot()
    }
    fn set_friend_slot(&self, friend: Option<[u8; 16]>) -> impl Future<Output = bool> + Send {
        self.base.set_friend_slot(friend)
    }
    fn set_file_comment(
        &self,
        file: [u8; 16],
        comment: FileComment,
    ) -> impl Future<Output = Result<(), String>> + Send {
        self.base.set_file_comment(file, comment)
    }
    fn has_more_results(&self, search: u64) -> impl Future<Output = bool> + Send {
        self.base.has_more_results(search)
    }
    async fn stop_search(&self, search: u64) {
        self.state.lock().unwrap().calls.push(format!("stopSearch[{search}]"));
    }
    fn rate_spam(&self, results: Vec<SearchResult>) -> impl Future<Output = Vec<SearchResult>> + Send {
        self.base.rate_spam(results)
    }
    fn mark_spam(&self, results: Vec<SearchResult>, is_spam: bool) -> impl Future<Output = SpamMarks> + Send {
        self.base.mark_spam(results, is_spam)
    }
    fn spam_marks(&self) -> impl Future<Output = SpamMarks> + Send {
        self.base.spam_marks()
    }
    fn file_history(&self) -> impl Future<Output = FileHistory> + Send {
        self.base.file_history()
    }
    fn update_status(&self) -> UpdateStatus {
        self.base.update_status()
    }
    fn check_for_update(&self) -> impl Future<Output = UpdateStatus> + Send {
        self.base.check_for_update()
    }
    fn skip_update(&self, version: String) -> impl Future<Output = Result<UpdateStatus, String>> + Send {
        self.base.skip_update(version)
    }
    fn download_update(&self) -> impl Future<Output = Result<UpdateStatus, String>> + Send {
        self.base.download_update()
    }
    fn install_update(&self, restart: bool) -> impl Future<Output = Result<UpdateStatus, String>> + Send {
        self.base.install_update(restart)
    }
}

struct Scenario {
    dir: TempDir,
    engine: ScriptedEngine,
    out: SharedBuffer,
    err: SharedBuffer,
    exit_code: i32,
    took: Duration,
}

impl Scenario {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let engine = ScriptedEngine::new(dir.path());
        Self {
            dir,
            engine,
            out: SharedBuffer::default(),
            err: SharedBuffer::default(),
            exit_code: -1,
            took: Duration::ZERO,
        }
    }

    fn folder(&self, name: &str) -> String {
        self.dir.path().join(name).to_string_lossy().into_owned()
    }

    fn given_add_servers_returns(&mut self, value: Result<usize, String>) {
        self.engine.state.lock().unwrap().add_servers = value;
    }

    fn given_an_import_that_took(&mut self, items: Vec<Item>) {
        self.engine.state.lock().unwrap().import_items = items;
    }

    fn given_a_peer_that_answers_the_hello(&mut self, peer: PeerInfo) {
        self.engine.state.lock().unwrap().hello_peer = Some(peer);
    }

    fn given_connect_fails(&mut self, reason: &str) {
        self.engine.state.lock().unwrap().connect_fail = Some(reason.to_owned());
    }

    fn given_a_connection_that_sends(&mut self, events: Vec<EngineEvent>) {
        self.engine.state.lock().unwrap().connect_events = events;
    }

    fn given_a_server_that_takes_us_with_a_high_id(&mut self) {
        self.given_a_connection_that_sends(vec![EngineEvent::ServerConnected {
            server: server(SERVER, SERVER),
            client_id: 99,
            low_id: false,
            obfuscated: false,
        }]);
    }

    fn given_a_search_that_sends(&mut self, id: u64, events: Vec<EngineEvent>) {
        let mut s = self.engine.state.lock().unwrap();
        s.search_id = id;
        s.search_events = events;
    }

    fn given_kad_is_off(&mut self) {
        self.engine.state.lock().unwrap().kad_states = None;
    }

    fn given_kad_goes_through(&mut self, states: Vec<KadInfo>) {
        self.engine.state.lock().unwrap().kad_states = Some(states.into());
    }

    fn given_each_download_completes_at_once(&mut self) {
        self.engine.state.lock().unwrap().download_mode = DownloadMode::CompleteAtOnce;
    }

    fn given_downloads_where_the_bad_one_fails_amid_other_events(&mut self) {
        self.engine.state.lock().unwrap().download_mode = DownloadMode::BadFails;
    }

    fn given_the_commands_running_with(&self, args: &[&str]) -> tokio::task::JoinHandle<i32> {
        let tasks = LaunchOptions::parse(args).unwrap().network;
        let engine = self.engine.clone();
        let mut out = self.out.clone();
        let mut err = self.err.clone();
        tokio::spawn(async move { network_commands::run(&engine, &tasks, &mut out, &mut err).await })
    }

    async fn given_the_output_shows_within(&self, text: &str, timeout: Duration) {
        let start = Instant::now();
        while !self.out.text().contains(text) && start.elapsed() < timeout {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }

    async fn when_the_commands_run_with(&mut self, args: &[&str]) {
        let tasks = LaunchOptions::parse(args).unwrap().network;
        let start = Instant::now();
        let mut out = self.out.clone();
        let mut err = self.err.clone();
        self.exit_code = network_commands::run(&self.engine, &tasks, &mut out, &mut err).await;
        self.took = start.elapsed();
    }

    fn then_the_exit_code_is(&self, expected: i32) {
        assert_eq!(self.exit_code, expected);
    }

    fn then_the_output_contains(&self, texts: &[&str]) {
        let out = self.out.text();
        for text in texts {
            assert!(out.contains(text), "missing {text:?} in:\n{out}");
        }
    }

    fn then_the_output_does_not_contain(&self, texts: &[&str]) {
        let out = self.out.text();
        for text in texts {
            assert!(!out.contains(text), "unexpected {text:?} in:\n{out}");
        }
    }

    fn then_the_output_has_line_parts(&self, prefix: &str, middle: &str, tail: &str) {
        let out = self.out.text();
        assert!(
            out.lines().any(|line| line.starts_with(prefix) && line.contains(middle) && line.contains(tail)),
            "missing line ({prefix:?}, {middle:?}, {tail:?}) in:\n{out}"
        );
    }

    fn then_the_output_shows_before(&self, first: &str, second: &str) {
        let out = self.out.text();
        assert!(out.find(first).unwrap() < out.find(second).unwrap(), "{out}");
    }

    fn then_there_is_no_output(&self) {
        assert!(self.out.text().is_empty(), "{:?}", self.out.text());
    }

    fn then_the_errors_contain(&self, texts: &[&str]) {
        let err = self.err.text();
        for text in texts {
            assert!(err.contains(text), "missing {text:?} in:\n{err}");
        }
    }

    fn then_the_errors_do_not_contain(&self, texts: &[&str]) {
        let err = self.err.text();
        for text in texts {
            assert!(!err.contains(text), "unexpected {text:?} in:\n{err}");
        }
    }

    fn then_the_calls_are(&self, expected: &[&str]) {
        let calls = self.engine.state.lock().unwrap().calls.clone();
        assert_eq!(calls, expected);
    }

    fn then_the_calls_include(&self, expected: &[&str]) {
        let calls = self.engine.state.lock().unwrap().calls.clone();
        for item in expected {
            assert!(calls.iter().any(|c| c == item), "missing {item:?} in {calls:?}");
        }
    }

    fn then_a_call_contains(&self, part: &str) {
        let calls = self.engine.state.lock().unwrap().calls.clone();
        assert!(calls.iter().any(|c| c.contains(part)), "missing {part:?} in {calls:?}");
    }

    fn then_the_engine_is_never_asked_to(&self, prefix: &str) {
        let calls = self.engine.state.lock().unwrap().calls.clone();
        assert!(calls.iter().all(|c| !c.starts_with(prefix)), "unexpected {prefix:?} in {calls:?}");
    }

    fn then_the_run_took_at_least_but_less_than(&self, min: Duration, max: Duration) {
        assert!(self.took >= min && self.took < max, "took {:?}", self.took);
    }
}

fn server(address: &str, name: &str) -> ServerInfo {
    ServerInfo {
        address: address.to_owned(),
        name: name.to_owned(),
        description: String::new(),
        users: 0,
        max_users: 0,
        files: 0,
        failed: 0,
        priority: Priority::Normal,
        ping: 0,
        soft_files: 0,
        hard_files: 0,
        low_id_users: 0,
        version: String::new(),
        obfuscation: false,
        is_static: false,
        country: None,
    }
}

fn result(name: &str, sources: u32) -> SearchResult {
    let mut hash = [0u8; 16];
    for (i, b) in name.bytes().take(16).enumerate() {
        hash[i] = b;
    }
    SearchResult::new(hash, name, 1000, sources, None, None)
}

fn download_of(link: &FileLink, sources: usize) -> DownloadInfo {
    DownloadInfo {
        hash: link.hash,
        name: link.name.clone(),
        size: link.size,
        completed: 0,
        status: DownloadStatus::Downloading,
        priority: DownloadPriority::Normal,
        category: 0,
        sources,
        transferring: 0,
        bytes_per_second: 0,
        parts: PartMap { size: 0, missing: Vec::new(), requested: Vec::new(), sources: Vec::new() },
        preview_first: false,
        auto_swap: false,
    }
}

fn kad(connected: bool, contacts: usize, verified_contacts: usize, firewalled: bool) -> KadInfo {
    KadInfo {
        connected,
        contacts,
        verified_contacts,
        firewalled,
        id: String::new(),
        public_ip: String::new(),
        udp_port: 4672,
        udp_firewalled: false,
        udp_verified: false,
        buddy: Buddy::None,
        users: 0,
        files: 0,
    }
}
