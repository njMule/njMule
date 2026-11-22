// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! How this copy of njMule was installed, which tells how it is updated.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum InstallKind {
    /// The Windows installer, also as winget installs it.
    Msi,
    /// The Windows zip archive.
    WindowsZip,
    /// The macOS disk image.
    Dmg,
    /// The Debian package, which the system's package manager updates.
    Deb,
    /// The RPM package, which the system's package manager updates.
    Rpm,
    /// The Linux archive of the desktop app.
    Tar,
    /// The Linux archive of the engine alone, built static.
    StaticTar,
    /// The Synology DSM package, which the Package Center updates.
    Synology,
    /// The Docker image, which its administrator pulls again.
    Docker,
    /// Built from the sources, or installed some other way.
    Unknown,
}

impl InstallKind {
    const ALL: [Self; 10] = [
        Self::Msi,
        Self::WindowsZip,
        Self::Dmg,
        Self::Deb,
        Self::Rpm,
        Self::Tar,
        Self::StaticTar,
        Self::Synology,
        Self::Docker,
        Self::Unknown,
    ];

    /// The name packages give themselves and release assets go by, as "msi".
    pub fn id(self) -> &'static str {
        match self {
            Self::Msi => "msi",
            Self::WindowsZip => "zip",
            Self::Dmg => "dmg",
            Self::Deb => "deb",
            Self::Rpm => "rpm",
            Self::Tar => "tar",
            Self::StaticTar => "static-tar",
            Self::Synology => "spk",
            Self::Docker => "docker",
            Self::Unknown => "unknown",
        }
    }

    /// The name the remote-control API gives it, as "windows_zip".
    pub fn name(self) -> &'static str {
        match self {
            Self::Msi => "msi",
            Self::WindowsZip => "windows_zip",
            Self::Dmg => "dmg",
            Self::Deb => "deb",
            Self::Rpm => "rpm",
            Self::Tar => "tar",
            Self::StaticTar => "static_tar",
            Self::Synology => "synology",
            Self::Docker => "docker",
            Self::Unknown => "unknown",
        }
    }

    /// The kind named `id`, if any.
    pub fn of(id: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|kind| kind.id() == id)
    }
}
