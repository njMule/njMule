// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The folder where new versions wait to be installed, `cache/update`: one folder for each version with its package as
//! downloaded (`.part` while it comes) and what it unpacks to, the plan of an installation handed over and the mark a
//! new version leaves once it has run long enough.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

pub const FOLDER: &str = "update";
pub const PLAN_FILE: &str = "apply.properties";
pub const UNPACKED_DIR: &str = "files";
pub const PART: &str = ".part";

const STARTED: &str = "started-";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateFiles {
    root: PathBuf,
}

impl UpdateFiles {
    pub fn new(root: PathBuf) -> Self {
        Self { root }
    }

    /// The folder below the cache directory `cache`.
    pub fn in_cache(cache: &Path) -> Self {
        Self::new(cache.join(FOLDER))
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    /// The folder of `version`, which must be a version as Semantic Versioning writes them: never a step out of it.
    pub fn version(&self, version: &str) -> io::Result<PathBuf> {
        if !is_version(version) {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("not a version: {version}")));
        }
        Ok(self.root.join(version))
    }

    /// Where the package `name` of `version` is kept; the name is one the signed manifest allowed.
    pub fn package_file(&self, version: &str, name: &str) -> io::Result<PathBuf> {
        Ok(self.version(version)?.join(name))
    }

    pub fn unpacked(&self, version: &str) -> io::Result<PathBuf> {
        Ok(self.version(version)?.join(UNPACKED_DIR))
    }

    pub fn plan(&self) -> PathBuf {
        self.root.join(PLAN_FILE)
    }

    /// The mark that `version` ran its engine long enough to be kept.
    pub fn started(&self, version: &str) -> io::Result<PathBuf> {
        self.version(version)?;
        Ok(self.root.join(format!("{STARTED}{version}")))
    }

    /// Removes the folders and marks of every version but `versions`; the mark of `running` stays, as an installer may
    /// still wait for it.
    pub fn keep_only(&self, versions: &[&str], running: &str) {
        let Ok(entries) = fs::read_dir(&self.root) else {
            return;
        };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            let mark = name.strip_prefix(STARTED);
            let kept = versions.contains(&name.as_str())
                || mark.is_some_and(|version| versions.contains(&version) || version == running);
            if kept || name == PLAN_FILE {
                continue;
            }
            let path = entry.path();
            let removed = match entry.file_type() {
                Ok(kind) if kind.is_dir() => delete_tree(&path),
                Ok(_) if mark.is_some() => fs::remove_file(&path),
                _ => Ok(()),
            };
            if let Err(e) = removed {
                tracing::debug!("Cannot clean {}: {e}", path.display());
            }
        }
    }
}

/// The folder `file` lies in.
pub fn parent(file: &Path) -> io::Result<PathBuf> {
    std::path::absolute(file)?.parent().map(Path::to_path_buf).ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("not a file in a folder: {}", file.display()))
    })
}

/// Deletes `tree` and all it holds, never following a link out of it.
pub fn delete_tree(tree: &Path) -> io::Result<()> {
    match fs::remove_dir_all(tree) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

// As Semantic Versioning writes them: a letter or digit, then up to 63 of letters, digits, '.', '+' and '-', no "..".
fn is_version(text: &str) -> bool {
    let mut bytes = text.bytes();
    (1..=64).contains(&text.len())
        && bytes.next().is_some_and(|b| b.is_ascii_alphanumeric())
        && bytes.all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'+' | b'-'))
        && !text.contains("..")
}
