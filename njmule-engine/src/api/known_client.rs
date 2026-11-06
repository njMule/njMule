// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A client met in the last 90 days, with what it said of itself last and the bytes exchanged with it; never its
//! address.

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KnownClient {
    pub user_hash: [u8; 16],
    /// The name it gave last; empty when it gave none.
    pub nick: String,
    pub software: String,
    /// In seconds since 1970.
    pub first_seen: u64,
    pub last_seen: u64,
    /// Where it was last met from (ISO 3166-1, lower case), when the countries were shown.
    pub country: Option<String>,
    /// The bytes sent to it, and those it sent, of all times.
    pub uploaded: u64,
    pub downloaded: u64,
}
