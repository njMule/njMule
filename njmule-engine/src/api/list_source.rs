// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Where a list of servers or Kad contacts comes from, and why it was not taken.

use std::fmt;
use std::path::PathBuf;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ListSource {
    /// An http or https address; lists are fetched from public addresses only.
    Web(String),
    /// A file of the engine's computer, no larger than a list from the web may be.
    File(PathBuf),
    /// The content itself, as it was sent.
    Data(Vec<u8>),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ListError {
    /// The content sent is not the list: the caller's error.
    NotAList(String),
    /// The list could not be fetched or read, or the engine could not keep it.
    Failed(String),
}

impl fmt::Display for ListError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NotAList(reason) | Self::Failed(reason) => f.write_str(reason),
        }
    }
}
