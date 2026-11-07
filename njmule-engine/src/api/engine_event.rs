// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the engine tells its front ends, in the order it happens.

use std::path::PathBuf;

use crate::api::download_info::DownloadInfo;
use crate::api::log_line::LogLine;
use crate::api::search_result::SearchResult;
use crate::api::server_info::ServerInfo;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EngineEvent {
    Started {
        version: String,
    },
    Stopping,
    PreferencesChanged,
    /// A line of the user's log.
    Log(LogLine),
    ServerConnecting(ServerInfo),
    ServerConnected {
        server: ServerInfo,
        client_id: u32,
        low_id: bool,
        obfuscated: bool,
    },
    ServerDisconnected {
        server: Option<ServerInfo>,
        reason: String,
    },
    ServerMessage {
        server: ServerInfo,
        text: String,
    },
    ServerUpdated(ServerInfo),
    ServersChanged {
        count: usize,
    },
    /// Results a search added or changed, as merged so far.
    SearchResults {
        search: u64,
        results: Vec<SearchResult>,
    },
    /// A search ended with this many distinct results.
    SearchEnded {
        search: u64,
        results: usize,
    },
    DownloadAdded(DownloadInfo),
    DownloadProgress(DownloadInfo),
    /// The download is complete, its file now at `file`.
    DownloadCompleted {
        download: DownloadInfo,
        file: PathBuf,
    },
    DownloadFailed {
        download: DownloadInfo,
        reason: String,
    },
    /// The download left the list: cancelled, or cleared once complete.
    DownloadRemoved([u8; 16]),
}
