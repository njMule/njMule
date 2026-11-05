// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the engine tells its front ends, in the order it happens.

use crate::api::search_result::SearchResult;
use crate::api::server_info::ServerInfo;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EngineEvent {
    Started {
        version: String,
    },
    Stopping,
    PreferencesChanged,
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
}
