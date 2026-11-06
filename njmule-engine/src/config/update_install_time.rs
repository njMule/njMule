// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! When njMule installs a new version it downloaded, in the [`UpdateMode::Install`] mode.
//!
//! [`UpdateMode::Install`]: crate::config::update_mode::UpdateMode::Install

use strum::{EnumString, IntoStaticStr, VariantArray};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, EnumString, IntoStaticStr, VariantArray)]
#[strum(serialize_all = "SCREAMING_SNAKE_CASE", ascii_case_insensitive)]
pub enum UpdateInstallTime {
    /// When the user exits njMule.
    Exit,
    /// When the user exits, or in the night hours, restarting njMule then: for a computer left on for days.
    ExitOrNight,
}

impl UpdateInstallTime {
    pub fn name(self) -> &'static str {
        self.into()
    }
}
