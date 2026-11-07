// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A contact of our Kad routing table, as the interface lists it: its id, address, type, distance from us in hex,
//! Kad version, whether it proved who it is, and its country when known.

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KadContactInfo {
    pub id: String,
    pub address: String,
    pub contact_type: u8,
    pub distance: String,
    pub version: u8,
    pub verified: bool,
    pub country: Option<String>,
}
