// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Which paused download starts when another completes or is cancelled; the one of highest priority first.

use strum::{EnumString, IntoStaticStr, VariantArray};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, EnumString, IntoStaticStr, VariantArray)]
#[strum(serialize_all = "SCREAMING_SNAKE_CASE", ascii_case_insensitive)]
pub enum NextDownload {
    /// None starts.
    None,
    /// Any paused download.
    Any,
    /// One of the same category, else any.
    SameCategoryFirst,
    /// One of the same category only.
    SameCategory,
}

impl NextDownload {
    pub fn name(self) -> &'static str {
        self.into()
    }
}
