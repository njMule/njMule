// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the updates of njMule are doing.

/// Who puts a new version in place.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Installer {
    /// The user, or the system's package manager: njMule tells how.
    Nobody,
    /// njMule itself, also unattended.
    Itself,
    /// njMule, once the system has asked the user for administrator rights: never unattended.
    Elevated,
}

impl Installer {
    /// The name the remote-control API gives it.
    pub fn name(self) -> &'static str {
        match self {
            Self::Nobody => "none",
            Self::Itself => "self",
            Self::Elevated => "elevated",
        }
    }
}
