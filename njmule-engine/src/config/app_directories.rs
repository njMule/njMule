// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Where a profile keeps its files: under one root, `config`, `data`, `temp` (part files), `cache`, `logs`, `gui` and
//! `incoming` (completed downloads, shared by default).

use std::io;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppDirectories {
    pub config: PathBuf,
    pub data: PathBuf,
    pub temp: PathBuf,
    pub cache: PathBuf,
    pub logs: PathBuf,
    pub gui: PathBuf,
    pub downloads: PathBuf,
}

impl AppDirectories {
    /// The layout of a profile kept whole under `root`, made absolute.
    pub fn under_root(root: &Path) -> io::Result<Self> {
        let root = std::path::absolute(root)?;
        Ok(Self {
            config: root.join("config"),
            data: root.join("data"),
            temp: root.join("temp"),
            cache: root.join("cache"),
            logs: root.join("logs"),
            gui: root.join("gui"),
            downloads: root.join("incoming"),
        })
    }

    pub fn create_all(&self) -> io::Result<()> {
        for directory in [&self.config, &self.data, &self.temp, &self.cache, &self.logs, &self.gui, &self.downloads] {
            std::fs::create_dir_all(directory)?;
        }
        Ok(())
    }
}
