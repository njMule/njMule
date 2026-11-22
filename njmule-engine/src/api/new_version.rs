// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A version of njMule newer than the one running, as its signed release information describes it.

use jiff::Timestamp;

/// The version, when it was released, whether it fixes a security problem, its notes in Markdown, the page of the
/// release, and the package for this installation when one fits.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NewVersion {
    pub version: String,
    pub published: Timestamp,
    pub critical: bool,
    pub notes: String,
    pub page: String,
    pub download: Option<Download>,
}

/// A package of the release: where it is downloaded from, its length, and its SHA-256 hash in lower-case hexadecimal.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Download {
    pub url: String,
    pub size: u64,
    pub sha256: String,
}
