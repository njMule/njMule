// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Which clients may see the list of our shared files when they ask for it.

use strum::{EnumString, IntoStaticStr, VariantArray};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, EnumString, IntoStaticStr, VariantArray)]
#[strum(serialize_all = "SCREAMING_SNAKE_CASE", ascii_case_insensitive)]
pub enum SharedFilesVisibility {
    Everybody,
    Friends,
    Nobody,
}

impl SharedFilesVisibility {
    pub fn name(self) -> &'static str {
        self.into()
    }
}
