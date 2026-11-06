// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Which new versions njMule tells of.

use strum::{EnumString, IntoStaticStr, VariantArray};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, EnumString, IntoStaticStr, VariantArray)]
#[strum(serialize_all = "SCREAMING_SNAKE_CASE", ascii_case_insensitive)]
pub enum UpdateChannel {
    /// Releases only.
    Stable,
    /// Test versions too: alpha, beta and release candidates.
    Prerelease,
}

impl UpdateChannel {
    /// The channel that follows `version`: a test version, or one that is no semantic version, follows the test
    /// versions.
    pub fn of(version: &str) -> Self {
        match semver::Version::parse(version) {
            Ok(parsed) if parsed.pre.is_empty() => Self::Stable,
            _ => Self::Prerelease,
        }
    }

    pub fn name(self) -> &'static str {
        self.into()
    }
}
