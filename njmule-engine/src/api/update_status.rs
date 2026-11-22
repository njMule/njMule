// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What njMule knows of its new versions.

use std::path::PathBuf;

use jiff::Timestamp;

use crate::api::install_kind::InstallKind;
use crate::api::new_version::NewVersion;

/// What njMule knows of its new versions: the version running, how it was installed, who puts a new version in place,
/// whether a check runs now, when one last succeeded, why the last check, download or installation failed, the newest
/// version when newer than this one, the version skipped, the download's progress, the package downloaded and checked,
/// and when it installs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateStatus {
    pub current: String,
    pub installation: InstallKind,
    pub installer: Installer,
    pub checking: bool,
    pub last_check: Option<Timestamp>,
    pub failure: Option<String>,
    pub available: Option<NewVersion>,
    pub skipped: Option<String>,
    pub download: Option<Progress>,
    pub ready: Option<PathBuf>,
    pub install: Install,
}

impl UpdateStatus {
    /// No check run yet on an installation njMule does not replace itself.
    pub fn idle(current: impl Into<String>) -> Self {
        Self {
            current: current.into(),
            installation: InstallKind::Unknown,
            installer: Installer::Nobody,
            checking: false,
            last_check: None,
            failure: None,
            available: None,
            skipped: None,
            download: None,
            ready: None,
            install: Install::Asked,
        }
    }

    /// Whether the user should hear of the available version: one not skipped, or one that fixes a security problem.
    pub fn is_worth_telling(&self) -> bool {
        self.available
            .as_ref()
            .is_some_and(|version| version.critical || self.skipped.as_deref() != Some(version.version.as_str()))
    }

    /// Whether njMule can install the version ready here, by itself or once the system has asked for rights.
    pub fn can_install(&self) -> bool {
        self.ready.is_some() && self.installer != Installer::Nobody
    }

    /// Where the update stands, in one word.
    pub fn phase(&self) -> Phase {
        if self.install == Install::Restart {
            Phase::Installing
        } else if self.checking {
            Phase::Checking
        } else if self.download.is_some() {
            Phase::Downloading
        } else if self.failure.is_some() {
            Phase::Failed
        } else if self.ready.is_some() {
            Phase::Ready
        } else if self.available.is_some() {
            Phase::Available
        } else if self.last_check.is_some() {
            Phase::UpToDate
        } else {
            Phase::Idle
        }
    }
}

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

/// When the version ready is installed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Install {
    /// When the user asks.
    Asked,
    /// When njMule exits, as the preferences say.
    AtExit,
    /// Now: njMule stops, installs it and starts again.
    Restart,
}

impl Install {
    /// The name the remote-control API gives it.
    pub fn name(self) -> &'static str {
        match self {
            Self::Asked => "asked",
            Self::AtExit => "at_exit",
            Self::Restart => "restart",
        }
    }
}

/// Where the update stands, in one word.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Phase {
    Idle,
    Checking,
    UpToDate,
    Available,
    Downloading,
    Ready,
    Installing,
    Failed,
}

impl Phase {
    /// The name the remote-control API gives it.
    pub fn name(self) -> &'static str {
        match self {
            Self::Idle => "idle",
            Self::Checking => "checking",
            Self::UpToDate => "up_to_date",
            Self::Available => "available",
            Self::Downloading => "downloading",
            Self::Ready => "ready",
            Self::Installing => "installing",
            Self::Failed => "failed",
        }
    }
}

/// How far a download is: the bytes on disk and the bytes in all.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Progress {
    pub received: u64,
    pub size: u64,
}

impl Progress {
    /// The bytes received, capped at `size`.
    pub fn new(received: u64, size: u64) -> Self {
        Self { received: received.min(size), size }
    }
}
