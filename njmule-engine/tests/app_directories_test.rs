// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's AppDirectoriesTest, under their names: the places of a profile on each system,
//! below a root or beside the executable, from an environment and a home given.

#![allow(clippy::unwrap_used)]

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

use njmule_engine::config::app_directories::{self, AppDirectories, PORTABLE_MARKER};
use njmule_engine::config::os_family::OsFamily;
use tempfile::TempDir;

struct System {
    home: TempDir,
    env: HashMap<String, String>,
    override_root: Option<PathBuf>,
    executable_dir: Option<PathBuf>,
    windows_downloads: Option<PathBuf>,
}

impl System {
    fn new() -> Self {
        Self {
            home: TempDir::new().unwrap(),
            env: HashMap::new(),
            override_root: None,
            executable_dir: None,
            windows_downloads: None,
        }
    }

    fn in_home(&self, path: &str) -> PathBuf {
        if path.is_empty() { self.home.path().to_path_buf() } else { self.home.path().join(path) }
    }

    fn with_variable_in_home(mut self, name: &str, path: &str) -> Self {
        let value = self.in_home(path).to_string_lossy().into_owned();
        self.env.insert(name.to_owned(), value);
        self
    }

    fn with_variable(mut self, name: &str, value: &str) -> Self {
        self.env.insert(name.to_owned(), value.to_owned());
        self
    }

    fn with_file_in_home(self, path: &str, text: &str) -> Self {
        let file = self.in_home(path);
        fs::create_dir_all(file.parent().unwrap()).unwrap();
        fs::write(file, text).unwrap();
        self
    }

    fn resolved(&self, os: OsFamily) -> AppDirectories {
        let downloads = self.windows_downloads.clone();
        AppDirectories::resolve(
            os,
            &self.env,
            self.home.path(),
            self.override_root.as_deref(),
            self.executable_dir.as_deref(),
            |_| downloads,
        )
        .unwrap()
    }

    fn user_dirs_download(&self) -> Option<PathBuf> {
        app_directories::user_dirs_download(&self.in_home("user-dirs.dirs"), self.home.path())
    }
}

fn assert_in_home(system: &System, folder: &Path, expected: &str) {
    let expected: PathBuf = expected.split('/').fold(system.home.path().to_path_buf(), |path, part| path.join(part));
    assert_eq!(folder, expected);
}

#[test]
fn should_use_roaming_for_config_and_local_for_bulk_data_on_windows() {
    let system = System::new()
        .with_variable_in_home("APPDATA", "AppData/Roaming")
        .with_variable_in_home("LOCALAPPDATA", "AppData/Local")
        .with_variable_in_home("USERPROFILE", "");

    let dirs = system.resolved(OsFamily::Windows);

    assert_in_home(&system, &dirs.config, "AppData/Roaming/njMule/config");
    assert_in_home(&system, &dirs.gui, "AppData/Roaming/njMule/gui");
    assert_in_home(&system, &dirs.data, "AppData/Local/njMule/data");
    assert_in_home(&system, &dirs.temp, "AppData/Local/njMule/temp");
    assert_in_home(&system, &dirs.cache, "AppData/Local/njMule/cache");
    assert_in_home(&system, &dirs.logs, "AppData/Local/njMule/logs");
    assert_in_home(&system, &dirs.downloads, "Downloads/njMule");
    assert!(!dirs.portable);
}

#[test]
fn should_follow_the_downloads_folder_the_user_moved_on_windows() {
    let mut system = System::new();
    system.windows_downloads = Some(system.in_home("Data/Downloads"));

    let dirs = system.resolved(OsFamily::Windows);

    assert_in_home(&system, &dirs.downloads, "Data/Downloads/njMule");
}

#[test]
fn should_fall_back_to_the_profile_on_windows_when_variables_are_missing() {
    let system = System::new();

    let dirs = system.resolved(OsFamily::Windows);

    assert_in_home(&system, &dirs.config, "AppData/Roaming/njMule/config");
}

#[test]
fn should_use_the_library_on_macos() {
    let system = System::new();

    let dirs = system.resolved(OsFamily::Macos);

    assert_in_home(&system, &dirs.config, "Library/Application Support/njMule/config");
    assert_in_home(&system, &dirs.data, "Library/Application Support/njMule/data");
    assert_in_home(&system, &dirs.cache, "Library/Caches/org.njmule.njMule");
    assert_in_home(&system, &dirs.logs, "Library/Logs/njMule");
    assert_in_home(&system, &dirs.downloads, "Downloads/njMule");
}

#[test]
fn should_follow_xdg_defaults_on_linux() {
    let system = System::new();

    let dirs = system.resolved(OsFamily::Linux);

    assert_in_home(&system, &dirs.config, ".config/njmule");
    assert_in_home(&system, &dirs.data, ".local/share/njmule");
    assert_in_home(&system, &dirs.temp, ".local/share/njmule/temp");
    assert_in_home(&system, &dirs.cache, ".cache/njmule");
    assert_in_home(&system, &dirs.logs, ".local/state/njmule/logs");
    assert_in_home(&system, &dirs.downloads, "Downloads/njMule");
}

#[test]
fn should_honour_absolute_xdg_variables_and_ignore_relative_ones_on_linux() {
    let system = System::new().with_variable_in_home("XDG_CONFIG_HOME", "cfg").with_variable("XDG_CACHE_HOME", "rel");

    let dirs = system.resolved(OsFamily::Linux);

    assert_in_home(&system, &dirs.config, "cfg/njmule");
    assert_in_home(&system, &dirs.cache, ".cache/njmule");
}

#[test]
fn should_read_the_download_directory_from_user_dirs_on_linux() {
    let system = System::new().with_file_in_home(
        ".config/user-dirs.dirs",
        "# comment\nXDG_DESKTOP_DIR=\"$HOME/Desktop\"\nXDG_DOWNLOAD_DIR=\"$HOME/Scaricati\"\n",
    );

    let dirs = system.resolved(OsFamily::Linux);

    assert_in_home(&system, &dirs.downloads, "Scaricati/njMule");
}

#[test]
fn should_read_an_absolute_download_directory_from_user_dirs() {
    let system = System::new();
    let absolute = system.in_home("abs");
    let system = system.with_file_in_home("user-dirs.dirs", &format!("XDG_DOWNLOAD_DIR=\"{}\"\n", absolute.display()));

    let download = system.user_dirs_download();

    assert_eq!(download, Some(absolute));
}

#[test]
fn should_ignore_a_relative_download_directory_in_user_dirs() {
    let system = System::new().with_file_in_home("user-dirs.dirs", "XDG_DOWNLOAD_DIR=relative\n");

    let download = system.user_dirs_download();

    assert_eq!(download, None);
}

#[test]
fn should_read_the_home_as_download_directory_from_user_dirs() {
    let system = System::new().with_file_in_home("user-dirs.dirs", "XDG_DOWNLOAD_DIR=\"$HOME\"\n");

    let download = system.user_dirs_download();

    assert_eq!(download, Some(system.in_home("")));
}

#[test]
fn should_let_the_last_download_directory_line_win_as_in_the_shell() {
    let system = System::new().with_file_in_home(
        "user-dirs.dirs",
        "XDG_DOWNLOAD_DIR=\"$HOME/Old\"\n# moved\nXDG_DOWNLOAD_DIR=\"$HOME/New\"\n",
    );

    let download = system.user_dirs_download();

    assert_eq!(download, Some(system.in_home("New")));
}

#[test]
fn should_read_no_download_directory_when_the_last_line_is_relative() {
    let system = System::new()
        .with_file_in_home("user-dirs.dirs", "XDG_DOWNLOAD_DIR=\"$HOME/Old\"\nXDG_DOWNLOAD_DIR=relative\n");

    let download = system.user_dirs_download();

    assert_eq!(download, None);
}

#[test]
fn should_put_everything_below_an_override_root() {
    let mut system = System::new();
    system.override_root = Some(system.in_home("custom"));

    let dirs = system.resolved(OsFamily::Windows);

    assert_in_home(&system, &dirs.config, "custom/config");
    assert_in_home(&system, &dirs.downloads, "custom/incoming");
    assert!(!dirs.portable);
}

#[test]
fn should_enable_portable_mode_with_a_marker_next_to_the_executable() {
    let mut system = System::new().with_file_in_home(&format!("app/{PORTABLE_MARKER}"), "");
    system.executable_dir = Some(system.in_home("app"));

    let dirs = system.resolved(OsFamily::Linux);

    assert!(dirs.portable);
    assert_in_home(&system, &dirs.config, "app/data/config");
}

#[test]
fn should_create_the_directories_of_the_profile_but_not_those_of_the_downloads() {
    let system = System::new();
    let dirs = AppDirectories::under_root(&system.in_home("r")).unwrap();

    dirs.create_all().unwrap();

    assert!(dirs.logs.is_dir());
    assert!(!dirs.temp.exists() && !dirs.downloads.exists());
}

#[test]
fn should_reject_relative_directories() {
    let absolute = std::path::absolute("x").unwrap();
    let mut folders: [PathBuf; 7] = std::array::from_fn(|_| absolute.clone());
    folders[0] = PathBuf::from("rel");

    let dirs = AppDirectories::new(folders, false);

    assert!(dirs.is_err());
}

#[test]
fn should_detect_the_os_family() {
    for (name, expected) in [
        ("Windows 11", OsFamily::Windows),
        ("Mac OS X", OsFamily::Macos),
        ("Linux", OsFamily::Linux),
        ("FreeBSD", OsFamily::Linux),
        ("", OsFamily::Linux),
    ] {
        assert_eq!(OsFamily::from_os_name(name), expected, "{name:?}");
    }
}

#[test]
fn should_detect_the_current_os_family() {
    let family = OsFamily::current();

    assert_eq!(family, OsFamily::from_os_name(std::env::consts::OS));
}
