// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the desktop app sets in the system of njMule's computer: whether njMule opens ed2k links from browsers and
//! other programs, and whether it starts when the user logs in. Only the desktop window on that computer reaches it.

use std::error::Error;
use std::fmt;
use std::io;

/// Each setting, `None` where this system or this installation does not offer it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Settings {
    /// Whether njMule opens ed2k links.
    pub links: Option<bool>,
    /// Whether njMule starts when the user logs in.
    pub autostart: Option<bool>,
}

/// What to change; what is `None` stays.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Change {
    pub links: Option<bool>,
    pub autostart: Option<bool>,
}

/// Why a change to the system's desktop settings did not happen.
#[derive(Debug)]
pub enum DesktopError {
    /// The change names a setting this system or installation does not offer; nothing changed.
    Unsupported(String),
    /// A system tool or file failed.
    Io(io::Error),
}

impl From<io::Error> for DesktopError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

impl fmt::Display for DesktopError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Unsupported(reason) => f.write_str(reason),
            Self::Io(error) => error.fmt(f),
        }
    }
}

impl Error for DesktopError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            Self::Unsupported(_) => None,
            Self::Io(error) => Some(error),
        }
    }
}

/// Reads and changes the desktop settings of njMule's computer.
pub trait DesktopIntegration: Send + Sync {
    /// The settings as the system has them now.
    fn settings(&self) -> io::Result<Settings>;

    /// Applies what `change` names and answers the settings as they are then.
    fn change(&self, change: Change) -> Result<Settings, DesktopError>;
}
