// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the engine is doing: the server and Kad, the transfers, this session's and the all-time totals, and our
//! addresses.

use crate::api::kad_info::KadInfo;
use crate::api::server_info::ServerInfo;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EngineStatus {
    /// The server we are logged in to.
    pub server: Option<ServerInfo>,
    pub client_id: u32,
    pub low_id: bool,
    pub obfuscated: bool,
    pub kad: Option<KadInfo>,
    pub download_bytes_per_second: u64,
    pub upload_bytes_per_second: u64,
    /// Payload bytes of this session.
    pub downloaded: u64,
    pub uploaded: u64,
    pub downloads: usize,
    /// Clients with an upload slot, and those waiting for one.
    pub uploads: usize,
    pub queued: usize,
    pub shared_files: usize,
    pub total_downloaded: u64,
    pub total_uploaded: u64,
    /// The sessions run, this one included.
    pub sessions: u64,
    pub session_seconds: u64,
    pub total_seconds: u64,
    /// Our address as the server sees it; empty before the login.
    pub public_ip: String,
    pub tcp_port: u16,
    /// Whether the server answers searches with related files.
    pub related_search: bool,
    /// The interface the preferences bind to, while it is gone.
    pub missing_interface: Option<String>,
    pub public_ipv6: Option<String>,
    /// Bytes sent and received besides the files' data.
    pub overhead_uploaded: u64,
    pub overhead_downloaded: u64,
    /// What the router answered about forwarding our ports (`off` while UPnP is off), and its name.
    pub forwarding: String,
    pub router: String,
    /// The UDP port Kad wants while another program holds it.
    pub kad_port_taken: Option<u16>,
}
