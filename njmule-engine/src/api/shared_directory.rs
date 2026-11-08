// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A folder whose files we share, with its subfolders or alone, and whether sharing one deserves a second thought.

use std::path::{Component, Path, PathBuf};

const SYSTEM: [&str; 15] = [
    "/bin",
    "/boot",
    "/dev",
    "/etc",
    "/lib",
    "/lib64",
    "/proc",
    "/sbin",
    "/sys",
    "/usr",
    "/var",
    "/System",
    "/Library",
    "/Applications",
    "/private",
];
const WINDOWS: [&str; 6] =
    ["SystemRoot", "ProgramFiles", "ProgramFiles(x86)", "ProgramData", "APPDATA", "LOCALAPPDATA"];
const PERSONAL: [&str; 5] = ["Documents", "Desktop", "Pictures", ".ssh", ".gnupg"];
// Where Windows keeps the OneDrive folders, which can hold the redirected personal folders.
const ONEDRIVE: [&str; 3] = ["OneDrive", "OneDriveConsumer", "OneDriveCommercial"];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SharedDirectory {
    pub path: PathBuf,
    pub subdirectories: bool,
}

impl SharedDirectory {
    /// Whether sharing `dir` deserves a second thought: a disk's root, a system folder, the user's documents, desktop,
    /// pictures or keys, where personal files are, or a folder holding one of them, such as the home. Links and
    /// junctions are judged by where they lead.
    pub fn is_sensitive(dir: &Path) -> bool {
        Self::is_sensitive_among(dir, &sensitive())
    }

    /// Whether `dir` is a disk's root, or lies in or holds one of the `sensitive` folders.
    pub fn is_sensitive_among(dir: &Path, sensitive: &[PathBuf]) -> bool {
        let forms = forms(dir);
        if forms.iter().any(|form| form.parent().is_none()) {
            return true;
        }
        sensitive.iter().flat_map(|folder| self::forms(folder)).any(|sensitive_form| {
            forms.iter().any(|form| form.starts_with(&sensitive_form) || sensitive_form.starts_with(form))
        })
    }
}

/// The path made absolute, without `.` and `..`.
pub fn absolute(path: &Path) -> PathBuf {
    let full = std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf());
    let mut normal = PathBuf::new();
    for component in full.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                normal.pop();
            }
            other => normal.push(other),
        }
    }
    normal
}

/// The path with its links and junctions resolved; made absolute only when that cannot be done.
pub fn real(path: &Path) -> PathBuf {
    let absolute = absolute(path);
    dunce::canonicalize(&absolute).unwrap_or(absolute)
}

// The path as given and, when it exists, where it really is.
fn forms(path: &Path) -> Vec<PathBuf> {
    let absolute = absolute(path);
    match dunce::canonicalize(&absolute) {
        Ok(real) if real != absolute => vec![absolute, real],
        _ => vec![absolute],
    }
}

fn sensitive() -> Vec<PathBuf> {
    let mut folders = Vec::new();
    let variable = |name: &str| std::env::var_os(name).filter(|value| !value.is_empty()).map(PathBuf::from);
    if cfg!(windows) {
        folders.extend(WINDOWS.iter().filter_map(|name| variable(name)).map(|value| absolute(&value)));
        for drive in ONEDRIVE.iter().filter_map(|name| variable(name)) {
            folders.extend(PERSONAL.iter().map(|personal| absolute(&drive.join(personal))));
        }
    } else {
        folders.extend(SYSTEM.iter().map(PathBuf::from));
    }
    if let Some(home) = dirs::home_dir() {
        folders.extend(PERSONAL.iter().map(|personal| absolute(&home.join(personal))));
    }
    folders
}
