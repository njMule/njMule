// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A known server as the front ends see it.

use crate::persist::format::server_met::Server;
use crate::server::server_list::{self, Priority};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServerInfo {
    /// `ip:port`, or `host:port` for a server known by its name.
    pub address: String,
    pub name: String,
    pub description: String,
    pub users: u64,
    pub files: u64,
    pub max_users: u64,
    pub failed: u64,
    pub priority: Priority,
    pub ping: u64,
    pub soft_files: u64,
    pub hard_files: u64,
    pub low_id_users: u64,
    pub version: String,
    pub obfuscation: bool,
    pub is_static: bool,
    /// Its country as an ISO 3166-1 code in lower case, when known.
    pub country: Option<String>,
}

impl ServerInfo {
    /// What `server` shows; its name defaults to its address.
    pub fn of(server: &Server, is_static: bool) -> Self {
        let address = server_list::address_of(server).to_string();
        Self {
            name: server.name().map_or_else(|| address.clone(), str::to_owned),
            description: server.description().unwrap_or_default().to_owned(),
            users: server.users().unwrap_or(0),
            files: server.files().unwrap_or(0),
            max_users: server.max_users().unwrap_or(0),
            failed: server.failed_count().unwrap_or(0),
            priority: server_list::priority(server),
            ping: server.ping().unwrap_or(0).min(u64::from(i32::MAX.unsigned_abs())),
            soft_files: server.soft_files().unwrap_or(0),
            hard_files: server.hard_files().unwrap_or(0),
            low_id_users: server.low_id_users().unwrap_or(0),
            version: server.version().unwrap_or_default(),
            obfuscation: server_list::supports_obfuscation(server),
            is_static,
            country: None,
            address,
        }
    }
}
