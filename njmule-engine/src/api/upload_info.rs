// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A client we upload to or that waits for its slot, and an address we banned.

/// A client of ours, as the front ends list it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UploadInfo {
    /// Its address, with the port it listens on.
    pub address: String,
    pub user_hash: [u8; 16],
    pub nick: Option<String>,
    pub software: String,
    /// The name of the file it asks for.
    pub file: String,
    pub state: UploadState,
    /// Payload bytes sent in this slot.
    pub transferred: u64,
    pub bytes_per_second: u64,
    /// How long it waited for its slot, or has waited so far.
    pub waiting_seconds: u64,
    /// Its country as an ISO 3166-1 code in lower case, when known.
    pub country: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, strum::VariantArray)]
pub enum UploadState {
    Uploading,
    Queued,
}

impl UploadState {
    pub fn name(self) -> &'static str {
        match self {
            Self::Uploading => "uploading",
            Self::Queued => "queued",
        }
    }
}

/// An address we banned, and for how long still.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BanInfo {
    pub address: String,
    pub seconds_left: u64,
}
