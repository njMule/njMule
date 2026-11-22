// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The updates of njMule: the keys its releases are signed with.

use super::minisign::{self, PublicKey};

// The signing key first, then the standby key that replaces it if it is ever lost or leaked.
const KEYS: &str = include_str!("../../../njmule-core/src/main/resources/org/njmule/core/update/keys.pub");

/// The keys built in that sign the update information of releases.
pub fn shipped_keys() -> Vec<PublicKey> {
    // Read at build time from the repository: a test holds them valid.
    minisign::public_keys(KEYS).unwrap_or_default()
}
