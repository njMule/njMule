// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! njMule's name and version.

pub const NAME: &str = "njMule";
pub const HOMEPAGE: &str = "https://github.com/njMule/njMule";

/// The semantic version, from `gradle.properties`.
pub fn version() -> &'static str {
    env!("NJMULE_VERSION")
}
