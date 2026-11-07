// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A Kad lookup running now, as the interface lists it: its target in hex, what it is for, and the values it got.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Node,
    Keyword,
    Sources,
    Notes,
    StoreKeyword,
    StoreSource,
    StoreNotes,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KadSearchInfo {
    pub target: String,
    pub kind: Kind,
    pub answers: u32,
}
