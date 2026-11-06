// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Where njMule keeps its files, following each operating system's conventions (Known Folders, `~/Library`, XDG). An
//! explicit root (`--config-dir`) or the portable mode ([`PORTABLE_MARKER`] beside the executable) puts everything below
//! one folder: `config`, `data`, `temp` (part files), `cache`, `logs`, `gui` and `incoming` (completed downloads,
//! shared by default). The installation folder is never written.

use std::collections::HashMap;
use std::io;
use std::path::{Path, PathBuf};

use crate::config::os_family::OsFamily;

pub const PORTABLE_MARKER: &str = "portable.flag";

const APP_DIR: &str = "njMule";
const XDG_DIR: &str = "njmule";
const DOWNLOAD_LINE: &str = "XDG_DOWNLOAD_DIR=";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppDirectories {
    /// The preferences and the `.met` and `.dat` files.
    pub config: PathBuf,
    /// The backups of the configuration.
    pub data: PathBuf,
    /// The downloads in progress.
    pub temp: PathBuf,
    /// What can be made again.
    pub cache: PathBuf,
    pub logs: PathBuf,
    /// The interface's state, as the window's layout.
    pub gui: PathBuf,
    /// The completed downloads.
    pub downloads: PathBuf,
    pub portable: bool,
}

impl AppDirectories {
    /// A layout of absolute folders only.
    pub fn new(folders: [PathBuf; 7], portable: bool) -> Result<Self, String> {
        if let Some(relative) = folders.iter().find(|folder| !folder.is_absolute()) {
            return Err(format!("a folder must be absolute: {}", relative.display()));
        }
        let [config, data, temp, cache, logs, gui, downloads] = folders;
        Ok(Self { config, data, temp, cache, logs, gui, downloads, portable })
    }

    /// The layout of a profile kept whole under `root`, made absolute.
    pub fn under_root(root: &Path) -> io::Result<Self> {
        Ok(Self::below(&std::path::absolute(root)?, false))
    }

    /// The layout of the running program: below `override_root` when given, beside the executable in portable mode,
    /// else in the system's places.
    pub fn resolve_current(override_root: Option<&Path>) -> io::Result<Self> {
        let env: HashMap<String, String> = std::env::vars_os()
            .filter_map(|(name, value)| Some((name.into_string().ok()?, value.into_string().ok()?)))
            .collect();
        let home = dirs::home_dir().ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no home folder"))?;
        let executable = std::env::current_exe().ok();
        let executable_dir = executable.as_deref().and_then(Path::parent);
        // Windows keeps the Downloads folder where the user moved it, behind its Known Folder API.
        let downloads = |_: &HashMap<String, String>| dirs::download_dir().filter(|folder| folder.is_dir());
        Self::resolve(OsFamily::current(), &env, &home, override_root, executable_dir, downloads)
    }

    /// The layout from explicit inputs, without reading the real environment; `windows_downloads` gives the Downloads
    /// folder Windows keeps for the user, moved maybe.
    pub fn resolve(
        os: OsFamily,
        env: &HashMap<String, String>,
        home: &Path,
        override_root: Option<&Path>,
        executable_dir: Option<&Path>,
        windows_downloads: impl FnOnce(&HashMap<String, String>) -> Option<PathBuf>,
    ) -> io::Result<Self> {
        if let Some(root) = override_root {
            return Self::under_root(root);
        }
        if let Some(folder) = executable_dir.filter(|folder| folder.join(PORTABLE_MARKER).is_file()) {
            return Ok(Self::below(&std::path::absolute(folder.join("data"))?, true));
        }
        Ok(match os {
            OsFamily::Windows => windows(env, home, windows_downloads(env)),
            OsFamily::Macos => macos(home),
            OsFamily::Linux => linux(env, home),
        })
    }

    /// Creates the folders of the profile. Those of the completed downloads and of the part files are left to the
    /// engine, which creates those the preferences name, maybe others.
    pub fn create_all(&self) -> io::Result<()> {
        for folder in [&self.config, &self.data, &self.cache, &self.logs, &self.gui] {
            std::fs::create_dir_all(folder)?;
        }
        Ok(())
    }

    fn below(root: &Path, portable: bool) -> Self {
        Self {
            config: root.join("config"),
            data: root.join("data"),
            temp: root.join("temp"),
            cache: root.join("cache"),
            logs: root.join("logs"),
            gui: root.join("gui"),
            downloads: root.join("incoming"),
            portable,
        }
    }
}

/// The `XDG_DOWNLOAD_DIR` of xdg-user-dirs' `user-dirs.dirs`, a shell file where the last assignment wins.
pub fn user_dirs_download(file: &Path, home: &Path) -> Option<PathBuf> {
    let text = std::fs::read_to_string(file).ok()?;
    let value = text.lines().filter_map(|line| line.trim().strip_prefix(DOWNLOAD_LINE)).next_back()?.trim();
    let value = value.strip_prefix('"').and_then(|inner| inner.strip_suffix('"')).unwrap_or(value);
    if value == "$HOME" {
        return Some(home.to_path_buf());
    }
    if let Some(below) = value.strip_prefix("$HOME/") {
        return Some(home.join(below));
    }
    Some(PathBuf::from(value)).filter(|path| path.is_absolute())
}

fn windows(env: &HashMap<String, String>, home: &Path, downloads: Option<PathBuf>) -> AppDirectories {
    // Windows' variables are without case.
    let variable =
        |name: &str| env.iter().find(|(key, _)| key.eq_ignore_ascii_case(name)).and_then(|(_, value)| absolute(value));
    let roaming = variable("APPDATA").unwrap_or_else(|| home.join("AppData").join("Roaming")).join(APP_DIR);
    let local = variable("LOCALAPPDATA").unwrap_or_else(|| home.join("AppData").join("Local")).join(APP_DIR);
    let profile = variable("USERPROFILE").unwrap_or_else(|| home.to_path_buf());
    AppDirectories {
        config: roaming.join("config"),
        data: local.join("data"),
        temp: local.join("temp"),
        cache: local.join("cache"),
        logs: local.join("logs"),
        gui: roaming.join("gui"),
        downloads: downloads.unwrap_or_else(|| profile.join("Downloads")).join(APP_DIR),
        portable: false,
    }
}

fn macos(home: &Path) -> AppDirectories {
    let library = home.join("Library");
    let support = library.join("Application Support").join(APP_DIR);
    AppDirectories {
        config: support.join("config"),
        data: support.join("data"),
        temp: support.join("temp"),
        cache: library.join("Caches").join("org.njmule.njMule"),
        logs: library.join("Logs").join(APP_DIR),
        gui: support.join("gui"),
        downloads: home.join("Downloads").join(APP_DIR),
        portable: false,
    }
}

fn linux(env: &HashMap<String, String>, home: &Path) -> AppDirectories {
    // The XDG specification has relative paths in these variables ignored.
    let variable = |name: &str| env.get(name).and_then(|value| absolute(value));
    let config_home = variable("XDG_CONFIG_HOME").unwrap_or_else(|| home.join(".config"));
    let data_home = variable("XDG_DATA_HOME").unwrap_or_else(|| home.join(".local").join("share"));
    let cache_home = variable("XDG_CACHE_HOME").unwrap_or_else(|| home.join(".cache"));
    let state_home = variable("XDG_STATE_HOME").unwrap_or_else(|| home.join(".local").join("state"));
    let config = config_home.join(XDG_DIR);
    let data = data_home.join(XDG_DIR);
    let downloads = variable("XDG_DOWNLOAD_DIR")
        .or_else(|| user_dirs_download(&config_home.join("user-dirs.dirs"), home))
        .unwrap_or_else(|| home.join("Downloads"));
    AppDirectories {
        temp: data.join("temp"),
        cache: cache_home.join(XDG_DIR),
        logs: state_home.join(XDG_DIR).join("logs"),
        gui: config.join("gui"),
        downloads: downloads.join(APP_DIR),
        config,
        data,
        portable: false,
    }
}

fn absolute(value: &str) -> Option<PathBuf> {
    Some(PathBuf::from(value.trim())).filter(|path| !value.trim().is_empty() && path.is_absolute())
}
