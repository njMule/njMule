// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Takes the app out of the macOS disk image of a new version, with the system's own tools: `hdiutil` mounts the image
//! read-only and out of the Finder's sight, `ditto` copies the bundle with its attributes, and `codesign` checks that
//! its signature still holds.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::time::Duration;

use data_encoding::HEXLOWER;

use super::commands::Commands;

const MOUNT_TIME: Duration = Duration::from_secs(120);
const COPY_TIME: Duration = Duration::from_secs(300);
const APP: &str = "njMule.app";

/// Copies njMule's app out of the disk image `dmg` into the empty folder `target`; gives the bundle.
pub fn extract(dmg: &Path, target: &Path, commands: &dyn Commands) -> io::Result<PathBuf> {
    fs::create_dir_all(target)?;
    let folder = std::path::absolute(target)?;
    let mount =
        folder.parent().unwrap_or(&folder).join(format!("mount-{}", HEXLOWER.encode(&rand::random::<[u8; 8]>())));
    fs::create_dir(&mount)?;
    commands.require(
        &[
            "hdiutil".to_owned(),
            "attach".to_owned(),
            "-nobrowse".to_owned(),
            "-readonly".to_owned(),
            "-noautoopen".to_owned(),
            "-mountpoint".to_owned(),
            text(&mount),
            text(dmg),
        ],
        MOUNT_TIME,
    )?;
    let copied = app(&mount).and_then(|app| {
        let bundle = target.join(APP);
        commands.require(&["ditto".to_owned(), text(&app), text(&bundle)], COPY_TIME)?;
        Ok(bundle)
    });
    let detached =
        commands.run(&["hdiutil".to_owned(), "detach".to_owned(), text(&mount), "-force".to_owned()], MOUNT_TIME);
    match detached {
        Ok(result) if result.succeeded() => {}
        Ok(result) => tracing::warn!("Cannot detach {}: {}", mount.display(), result.output.trim()),
        Err(e) => tracing::warn!("Cannot detach {}: {e}", mount.display()),
    }
    let _ = fs::remove_dir(&mount);
    let bundle = copied?;
    verify(&bundle, commands)?;
    Ok(bundle)
}

/// Fails unless the signature of `bundle` covers all it holds.
pub fn verify(bundle: &Path, commands: &dyn Commands) -> io::Result<()> {
    commands
        .require(
            &["codesign".to_owned(), "--verify".to_owned(), "--deep".to_owned(), "--strict".to_owned(), text(bundle)],
            COPY_TIME,
        )
        .map(|_| ())
}

// The image holds the app, its uninstaller and a link to /Applications.
fn app(mount: &Path) -> io::Result<PathBuf> {
    let app = mount.join(APP);
    if !fs::symlink_metadata(&app).is_ok_and(|metadata| metadata.is_dir()) {
        return Err(io::Error::new(io::ErrorKind::NotFound, format!("the disk image holds no {APP}")));
    }
    Ok(app)
}

fn text(path: &Path) -> String {
    path.to_string_lossy().into_owned()
}
