// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! `--remove-profile`: deletes a profile of njMule, as when it is uninstalled for good: its settings, lists, identity,
//! unfinished downloads, caches and logs. The completed downloads stay.

use std::fs;
use std::io::Write;
use std::path::{Component, Path, PathBuf};
use std::time::Duration;

use crate::app::autostart::{Autostart, SystemAutostart};
use crate::app::launch_options::LaunchOptions;
use crate::app::link_association::LinkAssociation;
use crate::app::link_handoff::LinkHandoff;
use crate::app::restore_links;
use crate::config::app_directories::AppDirectories;
use crate::persist::property_lines;

pub const EXIT_OK: i32 = 0;
pub const EXIT_FAILURE: i32 = 1;

// The preferences of the engine and their key of the folder of the completed downloads.
const PREFERENCES: &str = "njmule.properties";
const INCOMING: &str = "dirs.incoming";

/// Removes the profile of `cli`, after asking through `ask` (`None`: no answer) unless confirmed.
pub fn run_for_this_system<W1, W2, F>(cli: &LaunchOptions, out: &mut W1, err: &mut W2, ask: F) -> i32
where
    W1: Write,
    W2: Write,
    F: FnMut(&str) -> Option<String>,
{
    run(
        cli,
        out,
        err,
        ask,
        |dir| LinkAssociation::for_this_system(dir.to_path_buf()),
        |dir| {
            SystemAutostart::for_this_system(dir.to_path_buf(), &[]).map(|start| Box::new(start) as Box<dyn Autostart>)
        },
    )
}

/// As [`run_for_this_system`]: first the ed2k links go back and the start at login goes, as at an uninstallation,
/// since the profile keeps the handler the links go back to.
pub fn run<FL, FA, W1, W2, F>(
    cli: &LaunchOptions,
    out: &mut W1,
    err: &mut W2,
    mut ask: F,
    associations: FL,
    autostarts: FA,
) -> i32
where
    FL: Fn(&Path) -> Option<LinkAssociation>,
    FA: Fn(&Path) -> Option<Box<dyn Autostart>>,
    W1: Write,
    W2: Write,
    F: FnMut(&str) -> Option<String>,
{
    let dirs = match AppDirectories::resolve_current(cli.config_dir.as_deref()) {
        Ok(dirs) => dirs,
        Err(e) => {
            let _ = writeln!(err, "Cannot locate the profile: {e}");
            return EXIT_FAILURE;
        }
    };
    if !LinkHandoff::await_gone(&dirs.config, Duration::ZERO) {
        let _ = writeln!(err, "njMule runs on this profile: stop it first (njmule --exit).");
        return EXIT_FAILURE;
    }
    let incoming_dir = incoming(&dirs);
    let to_delete = folders(&dirs, &incoming_dir);
    if to_delete.is_empty() {
        let _ = writeln!(out, "There is no profile of njMule to delete.");
        return EXIT_OK;
    }
    let _ = writeln!(out, "These folders of njMule will be deleted:");
    for folder in &to_delete {
        let _ = writeln!(out, "  {}", folder.display());
    }
    let _ = writeln!(out, "The completed downloads in {} stay.", incoming_dir.display());
    if !cli.confirmed {
        let said = ask("Delete them? [y/N] ").map(|s| s.trim().to_ascii_lowercase()).unwrap_or_default();
        if said != "y" && said != "yes" {
            let _ = writeln!(out, "Nothing was deleted.");
            return EXIT_OK;
        }
    }
    // The profile keeps the program the links go back to: without it they could not go back later.
    let mut code = restore_links::restore(&dirs.config, associations, autostarts, out, err);
    if code != EXIT_OK {
        let _ = writeln!(err, "Nothing was deleted.");
        return code;
    }
    for folder in &to_delete {
        if let Err(e) = fs::remove_dir_all(folder) {
            let _ = writeln!(err, "Cannot delete {}: {e}", folder.display());
            code = EXIT_FAILURE;
        } else {
            remove_empty_parent(folder);
        }
    }
    if code == EXIT_OK {
        let _ = writeln!(out, "njMule's profile is deleted.");
    }
    code
}

/// The existing folders of the profile, a folder inside another one counted once; never one holding downloads.
pub fn folders(dirs: &AppDirectories, incoming: &Path) -> Vec<PathBuf> {
    let downloads = normalize_absolute(incoming);
    let mut all: Vec<PathBuf> = Vec::new();
    for candidate in [&dirs.config, &dirs.data, &dirs.temp, &dirs.cache, &dirs.logs, &dirs.gui] {
        let folder = normalize_absolute(candidate);
        let inside = all.iter().any(|existing| folder.starts_with(existing));
        if folder.is_dir() && !inside && !downloads.starts_with(&folder) {
            all.retain(|existing| !existing.starts_with(&folder));
            all.push(folder);
        }
    }
    all
}

/// Where the completed downloads go: the folder the preferences name, else the default one. Read from the file itself,
/// as the engine does not run.
pub fn incoming(dirs: &AppDirectories) -> PathBuf {
    let Ok(properties) = property_lines::read(&dirs.config.join(PREFERENCES)) else {
        return dirs.downloads.clone();
    };
    let chosen = properties.get(INCOMING).map(|value| value.trim()).unwrap_or("");
    if chosen.is_empty() { dirs.downloads.clone() } else { PathBuf::from(chosen) }
}

// The folder named after njMule that held them goes too once empty, as %APPDATA%\njMule.
fn remove_empty_parent(folder: &Path) {
    let Some(parent) = folder.parent() else {
        return;
    };
    let is_njmule =
        parent.file_name().and_then(|name| name.to_str()).is_some_and(|name| name.eq_ignore_ascii_case("njmule"));
    if is_njmule {
        let _ = fs::remove_dir(parent);
    }
}

fn normalize_absolute(path: &Path) -> PathBuf {
    let absolute = std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf());
    let mut clean = PathBuf::new();
    for component in absolute.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                let _ = clean.pop();
            }
            other => clean.push(other.as_os_str()),
        }
    }
    clean
}
