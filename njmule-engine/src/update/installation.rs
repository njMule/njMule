// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! How this copy of njMule was installed and what it runs on, which choose its package among a release's and who puts a
//! new version in place. Each package says what it is in [`FILE_NAME`] beside the executable (`Contents/Resources` in a
//! macOS bundle); [`KIND_VARIABLE`] says it for a container, and wins.

use std::fs::{self, OpenOptions};
use std::path::{Path, PathBuf};

use data_encoding::HEXLOWER;

use super::update_files;
use crate::api::install_kind::InstallKind;
use crate::api::update_status::Installer;
use crate::config::os_family::OsFamily;
use crate::persist::property_lines;

pub const FILE_NAME: &str = "install.properties";
pub const KIND_VARIABLE: &str = "NJMULE_INSTALL_KIND";

/// How it was installed, the system and processor as "windows-x64", the executable as its package placed it, and who
/// puts a new version in place.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Installation {
    pub kind: InstallKind,
    pub platform: String,
    pub executable: Option<PathBuf>,
    pub installer: Installer,
}

impl Installation {
    /// An installation njMule does not update itself.
    pub fn not_updated(kind: InstallKind, platform: &str) -> Self {
        Self { kind, platform: platform.to_owned(), executable: None, installer: Installer::Nobody }
    }

    /// This copy of njMule.
    pub fn current() -> Self {
        let variable = std::env::var(KIND_VARIABLE).ok();
        let executable = std::env::current_exe().ok();
        Self::resolve(variable.as_deref(), executable.as_deref(), OsFamily::current(), std::env::consts::ARCH)
    }

    /// The installation that `variable` or the file beside `executable` describe.
    pub fn resolve(variable: Option<&str>, executable: Option<&Path>, os: OsFamily, arch: &str) -> Self {
        let platform = format!("{}-{}", system(os), processor(arch));
        let declared = variable.map(str::trim).filter(|value| !value.is_empty());
        let by_package = declared.is_none();
        let declared = match declared {
            Some(declared) => Some(declared.to_owned()),
            None => executable.and_then(Path::parent).and_then(|dir| declared_beside(dir, os)),
        };
        let kind = declared.as_deref().and_then(InstallKind::of).unwrap_or(InstallKind::Unknown);
        // A container says what it is: its files are the image's, never ours to replace.
        let placed = if by_package { executable.map(Path::to_path_buf) } else { None };
        let mut found = Self { kind, platform, executable: placed, installer: Installer::Nobody };
        found.installer = found.find_installer();
        found
    }

    /// What a new version replaces: the folder of the executable, or for the macOS app its bundle; none when njMule
    /// does not replace itself.
    pub fn root(&self) -> Option<PathBuf> {
        let dir = self.executable.as_deref()?.parent()?;
        if self.kind == InstallKind::Dmg {
            // njMule.app/Contents/MacOS/njmule-gui
            let bundle = dir.parent()?.parent()?;
            return bundle.to_string_lossy().ends_with(".app").then(|| bundle.to_path_buf());
        }
        Some(dir.to_path_buf())
    }

    // njMule replaces the files of the packages it unpacks itself, where it may write; the MSI's through Windows
    // Installer, which asks for administrator rights when the program is installed for all users.
    fn find_installer(&self) -> Installer {
        let Some(root) = self.root() else {
            return Installer::Nobody;
        };
        let own = |dir: &Path| if writable(dir) { Installer::Itself } else { Installer::Nobody };
        match self.kind {
            InstallKind::Msi => {
                if writable(&root) {
                    Installer::Itself
                } else {
                    Installer::Elevated
                }
            }
            InstallKind::WindowsZip | InstallKind::Tar | InstallKind::StaticTar => own(&root),
            InstallKind::Dmg => update_files::parent(&root).map_or(Installer::Nobody, |dir| own(&dir)),
            _ => Installer::Nobody,
        }
    }
}

/// Whether njMule may write to `dir`, asked by doing: the permissions alone do not tell a folder only an administrator
/// may write to on Windows.
pub fn writable(dir: &Path) -> bool {
    let probe = dir.join(format!(".njmule-probe-{}", HEXLOWER.encode(&rand::random::<[u8; 6]>())));
    let created = OpenOptions::new().write(true).create_new(true).open(&probe);
    created.is_ok() && fs::remove_file(&probe).is_ok()
}

fn declared_beside(dir: &Path, os: OsFamily) -> Option<String> {
    let beside = dir.join(FILE_NAME);
    let places = match (os, dir.parent()) {
        (OsFamily::Macos, Some(contents)) => vec![contents.join("Resources").join(FILE_NAME), beside],
        _ => vec![beside],
    };
    places.into_iter().filter(|file| file.is_file()).find_map(|file| {
        property_lines::read(&file)
            .inspect_err(|e| tracing::debug!("Cannot read {}: {e}", file.display()))
            .ok()
            .and_then(|properties| properties.get("kind").map(|kind| kind.trim().to_owned()))
    })
}

fn system(os: OsFamily) -> &'static str {
    match os {
        OsFamily::Windows => "windows",
        OsFamily::Macos => "macos",
        OsFamily::Linux => "linux",
    }
}

// As the release names them: x64 and arm64.
fn processor(arch: &str) -> String {
    let name = arch.to_lowercase();
    match name.as_str() {
        "amd64" | "x86_64" | "x64" => "x64".to_owned(),
        "aarch64" | "arm64" => "arm64".to_owned(),
        _ => name.chars().filter(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == '_').collect(),
    }
}
