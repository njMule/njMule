// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Operating system families with their own conventions for where files go.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OsFamily {
    Windows,
    Macos,
    /// Linux and any other Unix-like system (the XDG Base Directory specification).
    Linux,
}

impl OsFamily {
    /// The family of an operating system's name, as `Windows 11` or `Mac OS X`.
    pub fn from_os_name(name: &str) -> Self {
        let name = name.to_lowercase();
        if name.starts_with("windows") {
            Self::Windows
        } else if name.starts_with("mac") || name.starts_with("darwin") {
            Self::Macos
        } else {
            Self::Linux
        }
    }

    pub fn current() -> Self {
        if cfg!(windows) {
            Self::Windows
        } else if cfg!(target_os = "macos") {
            Self::Macos
        } else {
            Self::Linux
        }
    }
}
