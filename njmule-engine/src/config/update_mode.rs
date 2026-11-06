// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Whether njMule looks for new versions of itself on its own, and what it does with one.

use strum::{EnumString, IntoStaticStr, VariantArray};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, EnumString, IntoStaticStr, VariantArray)]
#[strum(serialize_all = "SCREAMING_SNAKE_CASE", ascii_case_insensitive)]
pub enum UpdateMode {
    /// Only when the user asks.
    Off,
    /// At each start and once a day, telling the user of a new version.
    Notify,
    /// As [`UpdateMode::Notify`], downloading a new version in the background too.
    Download,
    /// As [`UpdateMode::Download`], installing it when njMule exits (or in the night hours, if chosen) where njMule can
    /// replace itself; elsewhere as [`UpdateMode::Download`].
    Install,
}

impl UpdateMode {
    pub fn name(self) -> &'static str {
        self.into()
    }
}
