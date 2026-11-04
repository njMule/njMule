// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! njMule's name and version.

pub const NAME: &str = "njMule";

/// The semantic version; the crate's own until the engine ships, when it follows `gradle.properties`.
pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}
