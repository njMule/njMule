// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A Kad lookup running now, as the interface lists it: its target in hex, what it is for, and the values it got.

#[derive(Debug, Clone, Copy, PartialEq, Eq, strum::VariantArray)]
pub enum Kind {
    Node,
    Keyword,
    Sources,
    Notes,
    StoreKeyword,
    StoreSource,
    StoreNotes,
}

impl Kind {
    /// The name the API gives it.
    pub fn name(self) -> &'static str {
        match self {
            Self::Node => "node",
            Self::Keyword => "keyword",
            Self::Sources => "sources",
            Self::Notes => "notes",
            Self::StoreKeyword => "store_keyword",
            Self::StoreSource => "store_source",
            Self::StoreNotes => "store_notes",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KadSearchInfo {
    pub target: String,
    pub kind: Kind,
    pub answers: u32,
}
