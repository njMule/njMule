// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What to take from an eMule profile.

/// `dry_run` only reports what would be imported. `identity` also takes eMule's user hash, key, credits and Kad id, used
/// from the next start; never while eMule runs, since two clients with one identity spoil each other's credits.
/// `downloads` also copies eMule's downloads in progress, which must be paused or eMule closed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ImportOptions {
    pub dry_run: bool,
    pub identity: bool,
    pub downloads: bool,
}
