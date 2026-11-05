// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What a peer said of itself when greeted.

/// Whether the peer proved the user hash it gave.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Identification {
    Verified,
    Failed,
    NotSupported,
    Pending,
}

impl Identification {
    pub fn name(self) -> &'static str {
        match self {
            Self::Verified => "verified",
            Self::Failed => "failed",
            Self::NotSupported => "not_supported",
            Self::Pending => "pending",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeerInfo {
    /// `host:port` as it was reached.
    pub address: String,
    pub user_hash: [u8; 16],
    pub nick: Option<String>,
    pub software: String,
    pub obfuscated: bool,
    pub identification: Identification,
}
