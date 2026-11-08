// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the remote-control API asks of an engine: the running one, or a stand-in in the API's tests. Each method's
//! future runs on the engine's side and answers once it is done.

use std::collections::BTreeMap;
use std::io;
use std::net::{SocketAddr, SocketAddrV4};
use std::path::PathBuf;

use futures_util::future::BoxFuture;
use tokio::sync::broadcast;

use crate::api::category::Category;
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
use crate::api::list_source::{ListError, ListSource};
use crate::api::peer_comment::PeerComment;
use crate::api::peer_info::PeerInfo;
use crate::api::port_test::PortTest;
use crate::api::saved_search::SavedSearch;
use crate::api::search_result::SearchResult;
use crate::api::server_info::ServerInfo;
use crate::api::spam_marks::SpamMarks;
use crate::api::upload_info::{BanInfo, UploadInfo};
use crate::config::app_directories::AppDirectories;
use crate::config::preferences::Preferences;
use crate::config::schedule::{Rule, Schedule};
use crate::engine::NotesError;
use crate::engine::network::{ConnectError, MoreAnswer};
use crate::files::shared_files::SharedFile;
use crate::search::search_manager::{Scope, StartError};
use crate::server::server_list::{Address, Priority};

/// The events of an engine for one follower: those it missed, the latest log lines and the server's state, then those
/// that come.
#[derive(Debug)]
pub struct Subscription {
    pub missed: Vec<EngineEvent>,
    pub live: broadcast::Receiver<EngineEvent>,
}

pub trait NjMuleEngine: Clone + Send + Sync + 'static {
    fn preferences(&self) -> Preferences;

    /// The folders of the profile.
    fn directories(&self) -> AppDirectories;

    /// What the engine is doing now; none once it stopped.
    fn status(&self) -> impl Future<Output = Option<EngineStatus>> + Send;

    /// The searches left open at the last exit.
    fn saved_searches(&self) -> impl Future<Output = Vec<SavedSearch>> + Send;

    /// Keeps these searches for the next start, in place of those kept before.
    fn save_searches(&self, searches: Vec<SavedSearch>) -> impl Future<Output = Result<(), String>> + Send;

    /// The categories downloads may be in, by id.
    fn categories(&self) -> impl Future<Output = Vec<Category>> + Send;

    /// Replaces the categories, kept in their file; they must be valid and have different ids.
    fn set_categories(&self, categories: Vec<Category>) -> impl Future<Output = Result<(), String>> + Send;

    /// The times of the week when other limits apply.
    fn schedule(&self) -> impl Future<Output = Schedule> + Send;

    /// The rule of the schedule in force, if any.
    fn active_rule(&self) -> impl Future<Output = Option<Rule>> + Send;

    /// Saves the schedule and puts it in place of the one before; answers it as kept, or why it cannot be.
    fn update_schedule(&self, schedule: Schedule) -> impl Future<Output = Result<Schedule, String>> + Send;

    /// Tests our ports, a test already running on them going on; answers the test as it stands, none once the engine
    /// stopped.
    fn test_ports(&self) -> impl Future<Output = Option<PortTest>> + Send;

    /// The test of our ports running or run last; none before the first.
    fn port_test(&self) -> impl Future<Output = Option<PortTest>> + Send;

    /// Replaces the IP filter with the list at `url`, an http or https address; answers how many ranges it has.
    fn update_ip_filter(&self, url: String) -> impl Future<Output = Result<usize, String>> + Send;

    /// Replaces the IP filter with the lists at `urls` joined into one; a list that cannot be read is left out and told
    /// in the log. Answers how many ranges the filter has.
    fn update_ip_filter_lists(&self, urls: Vec<String>) -> impl Future<Output = Result<usize, String>> + Send;

    /// Changes the preferences named in `changes`, all or none, and saves them.
    fn update_preferences(&self, changes: &BTreeMap<String, String>) -> Result<Preferences, String>;

    fn subscribe(&self) -> Subscription;

    /// Completes once the engine stops.
    fn stopped(&self) -> BoxFuture<'static, ()>;

    /// Says hello to a client and answers what it said, and whether it proved its user hash.
    fn hello(&self, address: SocketAddr) -> impl Future<Output = io::Result<PeerInfo>> + Send;

    /// The files the client listening at `address` shares, when it shows them.
    fn browse(&self, address: SocketAddr) -> impl Future<Output = io::Result<Vec<SearchResult>>> + Send;

    /// The clients met in the last 90 days, those met last first.
    fn known_clients(&self) -> impl Future<Output = Vec<KnownClient>> + Send;

    /// Adds the servers of a server.met not known yet; answers how many.
    fn add_servers(&self, source: ListSource) -> impl Future<Output = Result<usize, ListError>> + Send;

    /// Adds a server the user gave; false when it is known already.
    fn add_server(&self, address: Address) -> impl Future<Output = bool> + Send;

    /// Removes a server, from the static list too; false when it is unknown.
    fn remove_server(&self, address: Address) -> impl Future<Output = bool> + Send;

    /// Sets the priority a server is tried with; false when it is unknown.
    fn set_server_priority(&self, address: Address, priority: Priority) -> impl Future<Output = bool> + Send;

    /// The addresses of the server lists, which the engine takes again at its start when the preferences ask for it.
    fn server_list_addresses(&self) -> impl Future<Output = Vec<String>> + Send;

    /// Replaces the addresses of the server lists, http or https ones.
    fn set_server_list_addresses(&self, addresses: Vec<String>) -> impl Future<Output = Result<(), String>> + Send;

    /// Connects to the server at `address` (added to the list if new), or to any known one.
    fn connect_server(&self, address: Option<Address>) -> impl Future<Output = Result<(), ConnectError>> + Send;

    fn disconnect_server(&self) -> impl Future<Output = ()> + Send;

    fn servers(&self) -> impl Future<Output = Vec<ServerInfo>> + Send;

    /// Puts a known server in the static list or takes it out; false for an unknown server.
    fn set_static_server(&self, address: Address, wanted: bool) -> impl Future<Output = bool> + Send;

    /// The state of Kad, none while it is off.
    fn kad(&self) -> impl Future<Output = Option<KadInfo>> + Send;

    fn kad_contacts(&self) -> impl Future<Output = Vec<KadContactInfo>> + Send;

    fn kad_searches(&self) -> impl Future<Output = Vec<KadSearchInfo>> + Send;

    /// The notes Kad gave on a file so far, without those the comment filter leaves out.
    fn kad_notes(&self, file: [u8; 16]) -> impl Future<Output = Vec<KadNote>> + Send;

    /// Asks Kad for the notes on a file we download or share, and answers those known once the search ends.
    fn search_kad_notes(&self, file: [u8; 16]) -> impl Future<Output = Result<Vec<KadNote>, NotesError>> + Send;

    /// Joins Kad, also after it was stopped by hand.
    fn connect_kad(&self) -> impl Future<Output = ()> + Send;

    /// Leaves Kad until it is joined again or njMule starts again.
    fn disconnect_kad(&self) -> impl Future<Output = ()> + Send;

    /// Checks again whether other Kad nodes reach us; false while Kad is off.
    fn recheck_kad(&self) -> impl Future<Output = bool> + Send;

    /// Joins Kad through the node at `node`; why not while Kad does not run.
    fn bootstrap_kad(&self, node: SocketAddrV4) -> impl Future<Output = Result<(), String>> + Send;

    /// Adds the Kad contacts of a nodes.dat to ours and to Kad while it runs; answers how many were new.
    fn add_kad_contacts(&self, source: ListSource) -> impl Future<Output = Result<usize, ListError>> + Send;

    /// Starts a search where `scope` says, or where the networks connected allow without one; answers its id.
    fn start_search(&self, query: String, scope: Option<Scope>)
    -> impl Future<Output = Result<u64, StartError>> + Send;

    /// Asks the server for more results of a search.
    fn more_results(&self, search: u64) -> impl Future<Output = MoreAnswer> + Send;

    /// The files we share, those hashed so far.
    fn shared_files(&self) -> impl Future<Output = Vec<SharedFile>> + Send;

    /// Adds a download, paused when `paused` says so or, without it, as the preferences say.
    fn add_download(
        &self,
        link: FileLink,
        paused: Option<bool>,
    ) -> impl Future<Output = Result<DownloadInfo, String>> + Send;

    /// The downloads, the completed ones last.
    fn downloads(&self) -> impl Future<Output = Vec<DownloadInfo>> + Send;

    fn sources(&self) -> impl Future<Output = Vec<SourceInfo>> + Send;

    /// The clients we upload to, then those waiting for their slot, the next one first.
    fn uploads(&self) -> impl Future<Output = Vec<UploadInfo>> + Send;

    /// The addresses banned, with the time left.
    fn bans(&self) -> impl Future<Output = Vec<BanInfo>> + Send;

    /// Lifts the ban on an IPv4 address; false when it was not banned, why not when `ip` is no address.
    fn unban(&self, ip: String) -> impl Future<Output = Result<bool, String>> + Send;

    /// Pauses a download; answers it as it is now, none when unknown.
    fn pause_download(&self, hash: [u8; 16]) -> impl Future<Output = Option<DownloadInfo>> + Send;

    /// Resumes a paused, stopped or failed download; answers it as it is now, none when unknown or running.
    fn resume_download(&self, hash: [u8; 16]) -> impl Future<Output = Option<DownloadInfo>> + Send;

    /// Stops a download and deletes its data; false when unknown or while its finished file is moved.
    fn cancel_download(&self, hash: [u8; 16]) -> impl Future<Output = bool> + Send;

    /// Takes the completed downloads off the list; their files stay. Answers how many.
    fn clear_completed_downloads(&self) -> impl Future<Output = usize> + Send;

    /// Takes one completed download off the list; its file stays.
    fn clear_completed_download(&self, hash: [u8; 16]) -> impl Future<Output = bool> + Send;

    /// Stops a download and forgets its sources; false when unknown.
    fn stop_download(&self, hash: [u8; 16]) -> impl Future<Output = bool> + Send;

    /// Takes back a download cancelled in the last seconds, with what it had; false when it cannot be.
    fn restore_download(&self, hash: [u8; 16]) -> impl Future<Output = bool> + Send;

    /// Asks for the first and last parts first, so that the file can be previewed early.
    fn set_download_preview(&self, hash: [u8; 16], preview_first: bool) -> impl Future<Output = bool> + Send;

    /// Takes the sources shared with other downloads whenever they are free to move.
    fn set_download_auto_swap(&self, hash: [u8; 16], auto_swap: bool) -> impl Future<Output = bool> + Send;

    fn set_download_priority(&self, hash: [u8; 16], priority: DownloadPriority) -> impl Future<Output = bool> + Send;

    /// Moves a download to a category; 0 takes it out of every category.
    fn set_download_category(&self, hash: [u8; 16], category: u32) -> impl Future<Output = bool> + Send;

    /// Renames a download; false when unknown, an error for a blank name.
    fn rename_download(&self, hash: [u8; 16], name: String) -> impl Future<Output = Result<bool, String>> + Send;

    /// Adds a client known to have the file as a source of a download; false when unknown or refused.
    fn add_source(&self, download: [u8; 16], source: SocketAddr) -> impl Future<Output = bool> + Send;

    /// Moves a source asked for another file to this download, when it is free to move.
    fn swap_source_to_download(&self, download: [u8; 16], source: SocketAddr) -> impl Future<Output = bool> + Send;

    /// Moves the sources shared with other downloads to this one now; answers how many.
    fn swap_sources_to_download(&self, hash: [u8; 16]) -> impl Future<Output = usize> + Send;

    /// Moves this download's sources shared with others to them now; answers how many.
    fn swap_sources_from_download(&self, hash: [u8; 16]) -> impl Future<Output = usize> + Send;

    /// The data file of a download, once the start of the file has come.
    fn download_data(&self, hash: [u8; 16]) -> impl Future<Output = Option<PathBuf>> + Send;

    /// The comments the sources of a download gave on it.
    fn peer_comments(&self, download: [u8; 16]) -> impl Future<Output = Vec<PeerComment>> + Send;

    /// Whether the server holds more results of a search, which [`more_results`](Self::more_results) asks for.
    fn has_more_results(&self, search: u64) -> impl Future<Output = bool> + Send;

    /// Stops a search: no more of its results come.
    fn stop_search(&self, search: u64) -> impl Future<Output = ()> + Send;

    /// Rates results for spam by the marks as they are now; off in the preferences, nothing is spam.
    fn rate_spam(&self, results: Vec<SearchResult>) -> impl Future<Output = Vec<SearchResult>> + Send;

    /// Marks results as spam, or as not spam, and keeps the marks; answers them all.
    fn mark_spam(&self, results: Vec<SearchResult>, is_spam: bool) -> impl Future<Output = SpamMarks> + Send;

    /// The files marked as spam or as not spam, and the names of those marked spam.
    fn spam_marks(&self) -> impl Future<Output = SpamMarks> + Send;

    /// The files downloaded or hashed before, and those cancelled.
    fn file_history(&self) -> impl Future<Output = FileHistory> + Send;
}
