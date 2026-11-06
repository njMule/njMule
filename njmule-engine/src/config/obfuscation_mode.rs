// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Protocol obfuscation policy: the supported, requested and required flags folded into one ordered level.

use strum::{EnumString, IntoStaticStr, VariantArray};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, EnumString, IntoStaticStr, VariantArray)]
#[strum(serialize_all = "SCREAMING_SNAKE_CASE", ascii_case_insensitive)]
pub enum ObfuscationMode {
    Disabled,
    Supported,
    /// The usual default on the network.
    Requested,
    /// Refuse plain connections.
    Required,
}

impl ObfuscationMode {
    pub fn from_flags(supported: bool, requested: bool, required: bool) -> Self {
        if !supported {
            Self::Disabled
        } else if required {
            Self::Required
        } else if requested {
            Self::Requested
        } else {
            Self::Supported
        }
    }

    /// The name the preferences file and the API give it.
    pub fn name(self) -> &'static str {
        self.into()
    }
}
