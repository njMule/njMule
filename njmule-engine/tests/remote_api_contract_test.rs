// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's RemoteApiContractTest, under its names: every answer and event of the API follows its OpenAPI
//! document.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::{BTreeMap, HashSet};
use std::io;
use std::net::{SocketAddr, SocketAddrV4};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use futures_util::future::BoxFuture;
use njmule_engine::api::category::Category;
use njmule_engine::api::download_info::{DownloadInfo, DownloadPriority, SourceInfo};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::emule_history::EmuleHistory;
use njmule_engine::api::engine_event::{EngineEvent, EventKind};
use njmule_engine::api::engine_status::EngineStatus;
use njmule_engine::api::file_comment::FileComment;
use njmule_engine::api::file_history::FileHistory;
use njmule_engine::api::friend::Friend;
use njmule_engine::api::import_options::ImportOptions;
use njmule_engine::api::import_report::ImportReport;
use njmule_engine::api::kad_contact_info::KadContactInfo;
use njmule_engine::api::kad_info::KadInfo;
use njmule_engine::api::kad_note::KadNote;
use njmule_engine::api::kad_search_info::KadSearchInfo;
use njmule_engine::api::known_client::KnownClient;
use njmule_engine::api::list_source::{ListError, ListSource};
use njmule_engine::api::nj_mule_engine::{NjMuleEngine, Subscription};
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
use njmule_engine::persist::preferences_codec;
use njmule_engine::remote::control_endpoint::ControlEndpoint;
use njmule_engine::search::search_manager::{Scope, StartError};
use njmule_engine::server::server_list::{Address, Priority};
use serde_json::{Value, json};
use strum::VariantArray;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::broadcast;

use crate::api_contract;
use crate::api_samples::{self, HASH, PEER};

const HEX: &str = "ABABABABABABABABABABABABABABABAB";
const LINK: &str = "ed2k://|file|talk.bin|5000|ABABABABABABABABABABABABABABABAB|/";
const LIST: &str = r#"{"url":"http://example.org/list.met"}"#;

// The web interface's settings wait for the web interface.
fn reads() -> Vec<String> {
    let download = format!("/api/v1/downloads/{HEX}");
    let file = format!("/api/v1/files/{HEX}");
    let mut reads: Vec<String> = [
        "/api/v1",
        "/api/v1/openapi.yaml",
        "/api/v1/status",
        "/api/v1/limits",
        "/api/v1/log?after=0",
        "/api/v1/downloads",
        "/api/v1/downloads?include=parts",
        "/api/v1/sources",
        "/api/v1/uploads",
        "/api/v1/shared",
        "/api/v1/shared/directories",
        "/api/v1/servers",
        "/api/v1/kad",
        "/api/v1/kad/contacts",
        "/api/v1/kad/searches",
        "/api/v1/searches",
        "/api/v1/searches/saved",
        "/api/v1/searches/spam",
        "/api/v1/clients/known",
        "/api/v1/categories",
        "/api/v1/friends",
        "/api/v1/bans",
        "/api/v1/update",
        "/api/v1/preferences",
        "/api/v1/preferences/defaults",
        "/api/v1/folders",
        "/api/v1/schedule",
        "/api/v1/rates",
        "/api/v1/rates?span=86400",
        "/api/v1/conversations",
        "/api/v1/friends/slot",
        "/api/v1/ports/test",
        "/api/v1/servers/lists",
        "/api/v1/import/emule",
        "/api/v1/import/emule/history",
        "/api/v1/setup",
        "/api/v1/interfaces",
        "/api/v1/preferences/passwords",
    ]
    .map(str::to_owned)
    .to_vec();
    reads.extend([
        download.clone(),
        format!("{download}/parts"),
        format!("{download}/data"),
        format!("{download}/comments"),
        format!("{file}/comment"),
        format!("{file}/notes"),
    ]);
    reads
}

#[tokio::test]
async fn should_answer_a_read_as_the_document_describes_it() {
    for read in reads() {
        let mut test = Contract::new();
        test.given_the_engine_holds(true);
        test.given_the_api_runs().await;
        test.given_the_engine_logged().await;
        test.given_a_chat_with_a_captcha().await;

        test.when_called("GET", &read, "").await;

        test.then_the_status_is(200);
        test.then_the_answer_follows_the_document();
    }
}

#[tokio::test]
async fn should_describe_an_engine_connected_to_nothing() {
    for read in ["/api/v1/status", "/api/v1/kad", "/api/v1/schedule"] {
        let mut test = Contract::new();
        test.given_the_engine_holds(false);
        test.given_the_api_runs().await;

        test.when_called("GET", read, "").await;

        test.then_the_status_is(200);
        test.then_the_answer_follows_the_document();
    }
}

// The notifications and the updates wait for their ports.
fn changes() -> Vec<(&'static str, String, String, u16)> {
    let download = format!("/api/v1/downloads/{HEX}");
    let shared = format!("/api/v1/shared/{HEX}");
    let file = format!("/api/v1/files/{HEX}");
    let server = "/api/v1/servers/203.0.113.10:4661";
    let temp = std::env::temp_dir();
    let calls = [
        ("POST", "/api/v1/downloads".to_owned(), json!({"links": [LINK], "category": 1}).to_string(), 201),
        ("POST", "/api/v1/downloads".to_owned(), json!({"links": LINK}).to_string(), 201),
        ("POST", format!("{download}/pause"), String::new(), 200),
        ("POST", format!("{download}/resume"), String::new(), 200),
        ("POST", format!("{download}/stop"), String::new(), 200),
        ("PUT", format!("{download}/priority"), r#"{"priority":"high"}"#.to_owned(), 200),
        ("PUT", format!("{download}/category"), r#"{"category":1}"#.to_owned(), 200),
        ("PUT", format!("{download}/name"), r#"{"name":"talk 2.bin"}"#.to_owned(), 200),
        ("POST", format!("{download}/sources"), r#"{"address":"198.51.100.7:4662"}"#.to_owned(), 200),
        ("POST", format!("{download}/sources/swap"), r#"{"address":"198.51.100.7:4662"}"#.to_owned(), 200),
        ("DELETE", download.clone(), String::new(), 200),
        ("DELETE", "/api/v1/downloads/completed".to_owned(), String::new(), 200),
        ("DELETE", format!("/api/v1/downloads/completed/{HEX}"), String::new(), 200),
        ("POST", "/api/v1/names/cleanup".to_owned(), r#"{"name":"Film_(2010).sponsored.mkv"}"#.to_owned(), 200),
        ("PUT", format!("{shared}/priority"), r#"{"priority":"release"}"#.to_owned(), 200),
        ("POST", "/api/v1/servers".to_owned(), r#"{"address":"203.0.113.10:4661"}"#.to_owned(), 201),
        ("POST", "/api/v1/servers/connect".to_owned(), String::new(), 200),
        ("POST", "/api/v1/servers/connect".to_owned(), r#"{"address":"203.0.113.10:4661"}"#.to_owned(), 200),
        ("POST", "/api/v1/servers/disconnect".to_owned(), String::new(), 200),
        ("POST", "/api/v1/servers/import".to_owned(), LIST.to_owned(), 200),
        ("POST", "/api/v1/servers/import".to_owned(), r#"{"data":"AQID"}"#.to_owned(), 200),
        ("DELETE", server.to_owned(), String::new(), 200),
        ("POST", "/api/v1/kad/connect".to_owned(), String::new(), 200),
        ("POST", "/api/v1/kad/disconnect".to_owned(), String::new(), 200),
        ("POST", "/api/v1/kad/recheck".to_owned(), String::new(), 200),
        ("POST", "/api/v1/kad/bootstrap".to_owned(), r#"{"address":"198.51.100.40:4672"}"#.to_owned(), 200),
        ("POST", "/api/v1/kad/nodes".to_owned(), LIST.to_owned(), 200),
        ("POST", "/api/v1/kad/nodes".to_owned(), r#"{"data":"AQID"}"#.to_owned(), 200),
        ("PUT", "/api/v1/limits".to_owned(), r#"{"uploadKiBps":50}"#.to_owned(), 200),
        ("POST", "/api/v1/ipfilter".to_owned(), LIST.to_owned(), 200),
        ("POST", "/api/v1/messages".to_owned(), json!({"to": PEER, "text": "hi"}).to_string(), 200),
        ("DELETE", "/api/v1/bans/192.0.2.200".to_owned(), String::new(), 200),
        ("POST", "/api/v1/searches".to_owned(), r#"{"query":"talk","scope":"global"}"#.to_owned(), 201),
        ("POST", "/api/v1/searches/check".to_owned(), r#"{"query":"talk"}"#.to_owned(), 200),
        ("POST", "/api/v1/folders/check".to_owned(), json!({"path": temp}).to_string(), 200),
        ("POST", "/api/v1/import/emule".to_owned(), json!({"path": temp, "dryRun": true}).to_string(), 200),
        ("POST", "/api/v1/setup/done".to_owned(), String::new(), 200),
        ("POST", "/api/v1/shutdown".to_owned(), String::new(), 202),
        ("PATCH", "/api/v1/preferences".to_owned(), r#"{"user.nick":"Night Owl"}"#.to_owned(), 200),
        (
            "PUT",
            "/api/v1/schedule".to_owned(),
            json!({"rules": [{"name": "Night", "days": ["monday"], "start": "23:00", "end": "06:00",
                "downloadKiBps": 0}]})
            .to_string(),
            200,
        ),
        (
            "PUT",
            "/api/v1/categories".to_owned(),
            json!([{"id": 1, "name": "Talks", "color": 3, "patterns": "*.mp3", "regex": false}]).to_string(),
            200,
        ),
        (
            "PUT",
            "/api/v1/servers/lists".to_owned(),
            json!({"addresses": ["https://example.org/server.met"]}).to_string(),
            200,
        ),
        ("PUT", format!("{server}/priority"), r#"{"priority":"high"}"#.to_owned(), 200),
        ("PUT", format!("{server}/static"), r#"{"static":true}"#.to_owned(), 200),
        ("POST", "/api/v1/ports/test".to_owned(), String::new(), 202),
        ("POST", format!("{download}/restore"), String::new(), 200),
        ("PUT", format!("{download}/preview"), r#"{"previewFirst":true}"#.to_owned(), 200),
        ("PUT", format!("{download}/autoswap"), r#"{"autoSwap":true}"#.to_owned(), 200),
        ("POST", format!("{download}/swap"), r#"{"direction":"here"}"#.to_owned(), 200),
        ("PUT", format!("{file}/comment"), r#"{"rating":4,"text":"good"}"#.to_owned(), 200),
        ("POST", format!("{file}/notes"), String::new(), 200),
        ("PUT", format!("{shared}/name"), r#"{"name":"talk 2.bin"}"#.to_owned(), 200),
        ("POST", "/api/v1/friends".to_owned(), json!({"userHash": HEX, "name": "Ann"}).to_string(), 200),
        ("DELETE", format!("/api/v1/friends/{HEX}"), String::new(), 200),
        ("PUT", "/api/v1/friends/slot".to_owned(), r#"{"userHash":null}"#.to_owned(), 200),
        ("POST", format!("/api/v1/clients/{PEER}/files"), String::new(), 200),
        ("POST", format!("/api/v1/clients/{PEER}/hello"), String::new(), 200),
        (
            "PUT",
            "/api/v1/searches/saved".to_owned(),
            json!([{"title": "talks", "query": "talk", "scope": "global", "results": []}]).to_string(),
            200,
        ),
    ];
    calls.to_vec()
}

#[tokio::test]
async fn should_answer_a_change_as_the_document_describes_it() {
    for (verb, change, body, status) in changes() {
        let mut test = Contract::new();
        test.given_the_engine_holds(true);
        test.given_the_engine_does_what_it_is_asked();
        test.given_the_api_runs().await;

        test.when_called(verb, &change, &body).await;

        test.then_the_status_is(status);
        test.then_the_answer_follows_the_document();
    }
}

// The web interface, the desktop window and the updates wait for their ports.
fn refusals() -> Vec<(&'static str, String, &'static str, u16)> {
    let calls = [
        ("GET", "/api/v1/nothing".to_owned(), "", 404),
        ("DELETE", "/api/v1/status".to_owned(), "", 405),
        ("GET", "/api/v1/downloads/talk".to_owned(), "", 400),
        ("GET", format!("/api/v1/downloads/{}", "CD".repeat(16)), "", 404),
        ("GET", "/api/v1/searches/7".to_owned(), "", 404),
        ("GET", "/api/v1/log?after=-1".to_owned(), "", 400),
        ("PUT", "/api/v1/limits".to_owned(), r#"{"uploadKiBps":-1}"#, 400),
        ("POST", "/api/v1/downloads".to_owned(), "", 400),
        ("POST", "/api/v1/downloads".to_owned(), r#"{"links":[]}"#, 400),
        ("POST", "/api/v1/servers/import".to_owned(), r#"{"url":"file:///etc/passwd"}"#, 400),
        ("POST", "/api/v1/searches".to_owned(), r#"{"query":"talk","scope":"everywhere"}"#, 400),
        ("PATCH", "/api/v1/preferences".to_owned(), r#"{"net.tcpPort":"99999"}"#, 400),
        ("PATCH", "/api/v1/preferences".to_owned(), r#"{"no.such.key":"1"}"#, 400),
        ("PUT", "/api/v1/schedule".to_owned(), r#"{"rules":[{"start":"late","end":"06:00"}]}"#, 400),
        ("PUT", format!("/api/v1/files/{HEX}/comment"), r#"{"rating":6}"#, 400),
        ("POST", "/api/v1/shared/directories".to_owned(), r#"{"path":"relative"}"#, 400),
        ("DELETE", "/api/v1/shared/directories?path=%2Fnowhere".to_owned(), "", 404),
        ("DELETE", "/api/v1/conversations/198.51.100.99:4662".to_owned(), "", 404),
        ("POST", "/api/v1/searches/7/spam".to_owned(), r#"{"hashes":["ABABABABABABABABABABABABABABABAB"]}"#, 404),
        ("POST", "/api/v1/ipfilter/lists".to_owned(), "", 409),
        ("POST", "/api/v1/searches/check".to_owned(), r#"{"query":"talk OR"}"#, 400),
        ("POST", "/api/v1/import/emule".to_owned(), r#"{"path":"relative"}"#, 400),
    ];
    calls.to_vec()
}

#[tokio::test]
async fn should_answer_a_refusal_as_the_document_describes_it() {
    for (verb, refused, body, status) in refusals() {
        let mut test = Contract::new();
        test.given_the_engine_holds(true);
        test.given_the_api_runs().await;

        test.when_called(verb, &refused, body).await;

        test.then_the_status_is(status);
        test.then_the_answer_follows_the_document();
    }
}

#[tokio::test]
async fn should_answer_a_caller_without_the_token_as_the_document_describes_it() {
    let mut test = Contract::new();
    test.given_the_api_runs().await;

    test.when_called_without_the_token("GET", "/api/v1/status").await;

    test.then_the_status_is(401);
    test.then_the_answer_follows_the_document();
}

#[tokio::test]
async fn should_answer_the_results_of_a_search_as_the_document_describes_them() {
    let mut test = Contract::new();
    test.given_the_engine_does_what_it_is_asked();
    test.given_the_api_runs().await;
    test.given_a_search_found_results().await;

    test.when_called("GET", "/api/v1/searches/7", "").await;

    test.then_the_status_is(200);
    test.then_the_answer_follows_the_document();
}

#[tokio::test]
async fn should_stream_an_event_as_the_document_describes_it() {
    for announced in api_samples::events() {
        let mut test = Contract::new();
        test.given_the_api_runs().await;
        test.given_an_event_stream_open().await;

        test.when_the_engine_announces(announced).await;

        test.then_the_streamed_event_follows_the_document();
    }
}

#[test]
fn should_notice_an_answer_that_does_not_follow_the_document() {
    let mut test = Contract::new();

    test.when_an_answer_is_checked("GET", &format!("/api/v1/downloads/{HEX}"), 200, r#"{"hash":"talk"}"#);

    test.then_it_does_not_follow_the_document();
}

#[test]
fn should_have_a_sample_of_every_event_of_the_engine() {
    let mut test = Contract::new();

    test.when_the_sampled_event_kinds_are_listed();

    test.then_they_are_every_event_kind_of_the_engine();
}

/// What the engine holds: one sample of each record, connected or not, and whether it does what it is asked.
#[derive(Clone)]
struct SampleEngine {
    connected: bool,
    obliging: bool,
    events: broadcast::Sender<EngineEvent>,
    dirs: AppDirectories,
}

impl SampleEngine {
    // A change made, when the engine does what it is asked.
    fn done<T>(&self, value: T) -> Result<T, String> {
        if self.obliging { Ok(value) } else { Err("not asked for".to_owned()) }
    }
}

impl NjMuleEngine for SampleEngine {
    fn preferences(&self) -> Preferences {
        Preferences::default()
    }

    fn directories(&self) -> AppDirectories {
        self.dirs.clone()
    }

    async fn setup_pending(&self) -> bool {
        true
    }

    async fn finish_setup(&self) -> Result<(), String> {
        self.done(())
    }

    async fn import_from_emule(&self, _emule: PathBuf, _options: ImportOptions) -> Result<ImportReport, String> {
        self.done(api_samples::import_report())
    }

    async fn emule_profile(&self) -> Option<PathBuf> {
        Some(std::env::temp_dir())
    }

    async fn emule_history(&self) -> EmuleHistory {
        EmuleHistory {
            searches: vec!["talk".to_owned()],
            server_lists: vec!["http://example.org/server.met".to_owned()],
        }
    }

    async fn status(&self) -> Option<EngineStatus> {
        Some(api_samples::status(self.connected))
    }

    async fn saved_searches(&self) -> Vec<SavedSearch> {
        vec![api_samples::saved_search()]
    }

    async fn save_searches(&self, _searches: Vec<SavedSearch>) -> Result<(), String> {
        self.done(())
    }

    async fn categories(&self) -> Vec<Category> {
        vec![api_samples::category()]
    }

    async fn set_categories(&self, _categories: Vec<Category>) -> Result<(), String> {
        self.done(())
    }

    async fn schedule(&self) -> Schedule {
        api_samples::schedule()
    }

    // No rule applies to an engine connected to nothing, whose schedule tells none.
    async fn active_rule(&self) -> Option<Rule> {
        api_samples::schedule().rules.into_iter().next().filter(|_| self.connected)
    }

    async fn update_schedule(&self, _schedule: Schedule) -> Result<Schedule, String> {
        self.done(api_samples::schedule())
    }

    async fn test_ports(&self) -> Option<PortTest> {
        self.obliging.then(api_samples::port_test)
    }

    async fn port_test(&self) -> Option<PortTest> {
        Some(api_samples::port_test())
    }

    async fn update_ip_filter(&self, _url: String) -> Result<usize, String> {
        self.done(14)
    }

    async fn update_ip_filter_lists(&self, _urls: Vec<String>) -> Result<usize, String> {
        self.done(14)
    }

    fn update_preferences(&self, changes: &BTreeMap<String, String>) -> Result<Preferences, String> {
        preferences_codec::apply(&Preferences::default(), changes)
    }

    fn subscribe(&self) -> Subscription {
        Subscription { missed: Vec::new(), live: self.events.subscribe() }
    }

    fn stopped(&self) -> BoxFuture<'static, ()> {
        Box::pin(std::future::pending())
    }

    async fn hello(&self, _address: SocketAddr) -> io::Result<PeerInfo> {
        self.done(api_samples::peer()).map_err(io::Error::other)
    }

    async fn browse(&self, _address: SocketAddr) -> io::Result<Vec<SearchResult>> {
        self.done(vec![api_samples::result()]).map_err(io::Error::other)
    }

    async fn known_clients(&self) -> Vec<KnownClient> {
        vec![api_samples::known_client()]
    }

    async fn add_servers(&self, _source: ListSource) -> Result<usize, ListError> {
        self.done(14).map_err(ListError::Failed)
    }

    async fn add_server(&self, _address: Address) -> bool {
        self.obliging
    }

    async fn remove_server(&self, _address: Address) -> bool {
        self.obliging
    }

    async fn set_server_priority(&self, _address: Address, _priority: Priority) -> bool {
        self.obliging
    }

    async fn server_list_addresses(&self) -> Vec<String> {
        vec!["https://example.org/server.met".to_owned()]
    }

    async fn set_server_list_addresses(&self, _addresses: Vec<String>) -> Result<(), String> {
        self.done(())
    }

    async fn connect_server(&self, _address: Option<Address>) -> Result<(), ConnectError> {
        self.done(()).map_err(|_| ConnectError::NoServer)
    }

    async fn disconnect_server(&self) {}

    async fn servers(&self) -> Vec<ServerInfo> {
        vec![api_samples::server()]
    }

    async fn set_static_server(&self, _address: Address, _wanted: bool) -> bool {
        self.obliging
    }

    async fn kad(&self) -> Option<KadInfo> {
        self.connected.then(api_samples::kad)
    }

    async fn kad_contacts(&self) -> Vec<KadContactInfo> {
        vec![api_samples::kad_contact()]
    }

    async fn kad_searches(&self) -> Vec<KadSearchInfo> {
        vec![api_samples::kad_search()]
    }

    async fn kad_notes(&self, _file: [u8; 16]) -> Vec<KadNote> {
        vec![api_samples::kad_note()]
    }

    async fn search_kad_notes(&self, _file: [u8; 16]) -> Result<Vec<KadNote>, NotesError> {
        self.done(vec![api_samples::kad_note()]).map_err(|_| NotesError::UnknownFile)
    }

    async fn connect_kad(&self) {}

    async fn disconnect_kad(&self) {}

    async fn recheck_kad(&self) -> bool {
        self.obliging
    }

    async fn bootstrap_kad(&self, _node: SocketAddrV4) -> Result<(), String> {
        self.done(())
    }

    async fn add_kad_contacts(&self, _source: ListSource) -> Result<usize, ListError> {
        self.done(14).map_err(ListError::Failed)
    }

    async fn start_search(&self, _query: String, _scope: Option<Scope>) -> Result<u64, StartError> {
        self.done(7).map_err(StartError::Unavailable)
    }

    async fn more_results(&self, _search: u64) -> MoreAnswer {
        if self.obliging { MoreAnswer::Asked } else { MoreAnswer::Refused }
    }

    async fn shared_directories(&self) -> Vec<SharedDirectory> {
        vec![api_samples::directory()]
    }

    async fn share_directory(&self, _dir: PathBuf, _subdirectories: bool) -> Result<(), String> {
        self.done(())
    }

    async fn unshare_directory(&self, _dir: PathBuf) -> Result<(), String> {
        self.done(())
    }

    async fn shared_files(&self) -> Vec<SharedFileInfo> {
        vec![api_samples::shared()]
    }

    async fn rename_shared_file(&self, _hash: [u8; 16], _name: String) -> Result<bool, String> {
        self.done(true)
    }

    async fn set_upload_priority(&self, _hash: [u8; 16], _priority: UploadPriority) -> bool {
        self.obliging
    }

    async fn add_download(&self, _link: FileLink, _paused: Option<bool>) -> Result<DownloadInfo, String> {
        self.done(api_samples::download())
    }

    async fn downloads(&self) -> Vec<DownloadInfo> {
        vec![api_samples::download()]
    }

    async fn sources(&self) -> Vec<SourceInfo> {
        vec![api_samples::source()]
    }

    async fn uploads(&self) -> Vec<UploadInfo> {
        vec![api_samples::upload()]
    }

    async fn bans(&self) -> Vec<BanInfo> {
        vec![api_samples::ban()]
    }

    async fn unban(&self, _ip: String) -> Result<bool, String> {
        self.done(true)
    }

    async fn pause_download(&self, _hash: [u8; 16]) -> Option<DownloadInfo> {
        self.obliging.then(api_samples::download)
    }

    async fn resume_download(&self, _hash: [u8; 16]) -> Option<DownloadInfo> {
        self.obliging.then(api_samples::download)
    }

    async fn cancel_download(&self, _hash: [u8; 16]) -> bool {
        self.obliging
    }

    async fn clear_completed_downloads(&self) -> usize {
        2
    }

    async fn clear_completed_download(&self, _hash: [u8; 16]) -> bool {
        self.obliging
    }

    async fn stop_download(&self, _hash: [u8; 16]) -> bool {
        self.obliging
    }

    async fn restore_download(&self, _hash: [u8; 16]) -> bool {
        self.obliging
    }

    async fn set_download_preview(&self, _hash: [u8; 16], _preview_first: bool) -> bool {
        self.obliging
    }

    async fn set_download_auto_swap(&self, _hash: [u8; 16], _auto_swap: bool) -> bool {
        self.obliging
    }

    async fn set_download_priority(&self, _hash: [u8; 16], _priority: DownloadPriority) -> bool {
        self.obliging
    }

    async fn set_download_category(&self, _hash: [u8; 16], _category: u32) -> bool {
        self.obliging
    }

    async fn rename_download(&self, _hash: [u8; 16], _name: String) -> Result<bool, String> {
        self.done(true)
    }

    async fn add_source(&self, _download: [u8; 16], _source: SocketAddr) -> bool {
        self.obliging
    }

    async fn swap_source_to_download(&self, _download: [u8; 16], _source: SocketAddr) -> bool {
        self.obliging
    }

    async fn swap_sources_to_download(&self, _hash: [u8; 16]) -> usize {
        3
    }

    async fn swap_sources_from_download(&self, _hash: [u8; 16]) -> usize {
        3
    }

    async fn download_data(&self, _hash: [u8; 16]) -> Option<PathBuf> {
        Some(PathBuf::from("001.part"))
    }

    async fn peer_comments(&self, _download: [u8; 16]) -> Vec<PeerComment> {
        vec![api_samples::peer_comment()]
    }

    async fn file_comment(&self, _file: [u8; 16]) -> Option<FileComment> {
        Some(FileComment::of("clean copy", 4))
    }

    async fn friends(&self) -> Vec<Friend> {
        vec![api_samples::friend()]
    }

    async fn send_message(&self, _to: SocketAddr, _text: String) {}

    async fn add_friend(&self, _friend: Friend) -> Result<(), String> {
        self.done(())
    }

    async fn remove_friend(&self, _user_hash: [u8; 16]) -> Result<bool, String> {
        self.done(true)
    }

    async fn friend_slot(&self) -> Option<[u8; 16]> {
        Some(HASH)
    }

    async fn set_friend_slot(&self, _friend: Option<[u8; 16]>) -> bool {
        self.obliging
    }

    async fn set_file_comment(&self, _file: [u8; 16], _comment: FileComment) -> Result<(), String> {
        self.done(())
    }

    async fn has_more_results(&self, _search: u64) -> bool {
        self.obliging
    }

    async fn stop_search(&self, _search: u64) {}

    async fn rate_spam(&self, results: Vec<SearchResult>) -> Vec<SearchResult> {
        results
    }

    async fn mark_spam(&self, _results: Vec<SearchResult>, _is_spam: bool) -> SpamMarks {
        api_samples::spam_marks()
    }

    async fn spam_marks(&self) -> SpamMarks {
        api_samples::spam_marks()
    }

    async fn file_history(&self) -> FileHistory {
        FileHistory::of(HashSet::new(), HashSet::new())
    }
}

/// The API on a loopback port with the sample engine behind it, and what it answered or streamed last.
struct Contract {
    _dir: tempfile::TempDir,
    engine: SampleEngine,
    endpoint: Option<ControlEndpoint>,
    address: Option<SocketAddr>,
    token: String,
    method: String,
    path: String,
    status: u16,
    content_type: String,
    body: String,
    stream: Option<TcpStream>,
    carried: String,
    event: BTreeMap<String, String>,
    sampled: HashSet<EventKind>,
    violations: Vec<String>,
}

impl Contract {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let dirs = AppDirectories::under_root(dir.path()).unwrap();
        Self {
            engine: SampleEngine { connected: false, obliging: false, events: broadcast::channel(16).0, dirs },
            _dir: dir,
            endpoint: None,
            address: None,
            token: String::new(),
            method: String::new(),
            path: String::new(),
            status: 0,
            content_type: String::new(),
            body: String::new(),
            stream: None,
            carried: String::new(),
            event: BTreeMap::new(),
            sampled: HashSet::new(),
            violations: Vec::new(),
        }
    }

    fn given_the_engine_holds(&mut self, connected: bool) {
        self.engine.connected = connected;
    }

    fn given_the_engine_does_what_it_is_asked(&mut self) {
        self.engine.obliging = true;
    }

    async fn given_the_api_runs(&mut self) {
        let endpoint = ControlEndpoint::start(self.engine.clone(), Some(Arc::new(|| {}))).await.unwrap();
        let control: Value =
            serde_json::from_str(endpoint.control_line().strip_prefix("njmule-control ").unwrap()).unwrap();
        let url = control["url"].as_str().unwrap();
        self.address = Some(url.strip_prefix("http://").unwrap().strip_suffix("/api/v1").unwrap().parse().unwrap());
        control["token"].as_str().unwrap().clone_into(&mut self.token);
        self.endpoint = Some(endpoint);
    }

    // Log lines come through the engine's events, which the API gathers on its own task.
    async fn given_the_engine_logged(&mut self) {
        self.engine.events.send(EngineEvent::Log(api_samples::log_line())).unwrap();
        self.until_the_answer_carries("/api/v1/log?after=0", "Connected").await;
    }

    // A chat comes through the engine's events: a message, a CAPTCHA to solve and a message not delivered.
    async fn given_a_chat_with_a_captcha(&mut self) {
        let events = [
            EngineEvent::MessageReceived {
                address: PEER.to_owned(),
                user_hash: api_samples::USER,
                nick: Some("Ann".to_owned()),
                text: "hello".to_owned(),
            },
            EngineEvent::CaptchaRequested {
                address: PEER.to_owned(),
                user_hash: api_samples::USER,
                image: b"BM".into(),
            },
            EngineEvent::MessageFailed { address: PEER.to_owned(), reason: "offline".to_owned() },
        ];
        for event in events {
            self.engine.events.send(event).unwrap();
        }
        self.until_the_answer_carries("/api/v1/conversations", "offline").await;
    }

    async fn given_a_search_found_results(&mut self) {
        self.when_called("POST", "/api/v1/searches", r#"{"query":"talk"}"#).await;
        self.then_the_status_is(201);
        let results = EngineEvent::SearchResults { search: 7, results: vec![api_samples::result()] };
        self.engine.events.send(results).unwrap();
        self.until_the_answer_carries("/api/v1/searches/7", "talk.bin").await;
    }

    // Opens the stream and waits for its first line, once the API follows the engine for it.
    async fn given_an_event_stream_open(&mut self) {
        let mut stream = TcpStream::connect(self.address.unwrap()).await.unwrap();
        let request =
            format!("GET /api/v1/events HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {}\r\n\r\n", self.token);
        stream.write_all(request.as_bytes()).await.unwrap();
        self.stream = Some(stream);
        self.until_the_stream_carries(|carried| carried.contains("njMule events")).await;
        // The stream and the collector of recent events.
        let deadline = Instant::now() + Duration::from_secs(10);
        while self.engine.events.receiver_count() < 2 && Instant::now() < deadline {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(self.engine.events.receiver_count() >= 2);
        self.carried.clear();
    }

    async fn when_called(&mut self, method: &str, path: &str, body: &str) {
        let token = self.token.clone();
        self.when_called_with(method, path, Some(&token), body).await;
    }

    async fn when_called_without_the_token(&mut self, method: &str, path: &str) {
        self.when_called_with(method, path, None, "").await;
    }

    async fn when_called_with(&mut self, method: &str, path: &str, token: Option<&str>, body: &str) {
        method.clone_into(&mut self.method);
        path.clone_into(&mut self.path);
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
        self.content_type = lines
            .filter_map(|line| line.split_once(':'))
            .find(|(name, _)| name.trim().eq_ignore_ascii_case("content-type"))
            .map(|(_, value)| value.trim().to_owned())
            .unwrap_or_default();
        body.clone_into(&mut self.body);
    }

    async fn when_the_engine_announces(&mut self, announced: EngineEvent) {
        self.engine.events.send(announced).unwrap();
        self.until_the_stream_carries(|carried| {
            carried.find("event:").is_some_and(|start| carried[start..].contains("\n\n"))
        })
        .await;
        let start = self.carried.find("event:").unwrap();
        let frame = &self.carried[start..start + self.carried[start..].find("\n\n").unwrap()];
        self.event = frame
            .lines()
            .filter_map(|line| line.split_once(':'))
            .map(|(name, value)| (name.to_owned(), value.strip_prefix(' ').unwrap_or(value).to_owned()))
            .collect();
    }

    fn when_an_answer_is_checked(&mut self, method: &str, path: &str, status: u16, body: &str) {
        self.violations = api_contract::shipped().answer_violations(method, path, status, "application/json", body);
    }

    fn when_the_sampled_event_kinds_are_listed(&mut self) {
        self.sampled = api_samples::events().iter().map(EventKind::from).collect();
    }

    fn then_the_status_is(&self, status: u16) {
        assert_eq!(self.status, status, "{} {}: {}", self.method, self.path, self.body);
    }

    fn then_the_answer_follows_the_document(&self) {
        let violations = api_contract::shipped().answer_violations(
            &self.method,
            &self.path,
            self.status,
            &self.content_type,
            &self.body,
        );
        assert!(violations.is_empty(), "{} {}: {violations:?} in {}", self.method, self.path, self.body);
    }

    fn then_the_streamed_event_follows_the_document(&self) {
        let field = |name: &str| self.event.get(name).unwrap_or_else(|| panic!("no {name} in {:?}", self.event));
        let violations = api_contract::shipped().event_violations(field("event"), field("id"), field("data"));
        assert!(violations.is_empty(), "{violations:?} in {:?}", self.event);
    }

    fn then_it_does_not_follow_the_document(&self) {
        assert!(!self.violations.is_empty());
    }

    fn then_they_are_every_event_kind_of_the_engine(&self) {
        assert_eq!(self.sampled, EventKind::VARIANTS.iter().copied().collect());
    }

    // Asks until the answer holds the text, which the API's own task gathers from the engine's events.
    async fn until_the_answer_carries(&mut self, path: &str, text: &str) {
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            self.when_called("GET", path, "").await;
            if self.body.contains(text) {
                return;
            }
            assert!(Instant::now() < deadline, "{path} never carried {text}: {}", self.body);
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    async fn until_the_stream_carries(&mut self, done: impl Fn(&str) -> bool) {
        let deadline = Instant::now() + Duration::from_secs(5);
        let stream = self.stream.as_mut().unwrap();
        while !done(&self.carried) {
            let mut read = [0u8; 4096];
            let left = deadline.saturating_duration_since(Instant::now());
            let count = tokio::time::timeout(left, stream.read(&mut read)).await.expect("an event").unwrap();
            assert!(count > 0, "the stream ended: {}", self.carried);
            self.carried.push_str(&String::from_utf8_lossy(&read[..count]));
        }
    }
}

impl Drop for Contract {
    fn drop(&mut self) {
        if let Some(endpoint) = self.endpoint.take() {
            tokio::spawn(endpoint.stop());
        }
    }
}
