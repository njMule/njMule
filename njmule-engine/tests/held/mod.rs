// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A file held as another program holds it, until dropped. On Windows it is open without sharing, as a scanner holds
//! it: no one opens or deletes it meanwhile. Elsewhere its permissions keep it from being read, or its folder's from
//! being deleted from; none for a user who reads or deletes any file, whose test then passes at once.

#![allow(dead_code, clippy::unwrap_used)]

use std::fs;
use std::path::Path;
use std::thread::{self, JoinHandle};
use std::time::Duration;

pub struct Held {
    release: Option<Box<dyn FnOnce() + Send>>,
}

impl Held {
    fn by(release: impl FnOnce() + Send + 'static) -> Self {
        Self { release: Some(Box::new(release)) }
    }

    /// Lets the file go from another thread after `millis`.
    pub fn released_after(self, millis: u64) -> JoinHandle<()> {
        thread::spawn(move || {
            thread::sleep(Duration::from_millis(millis));
            drop(self);
        })
    }
}

impl Drop for Held {
    fn drop(&mut self) {
        if let Some(release) = self.release.take() {
            release();
        }
    }
}

#[cfg(windows)]
pub fn hold(file: &Path) -> Option<Held> {
    use std::os::windows::fs::OpenOptionsExt;
    let open = fs::OpenOptions::new().read(true).share_mode(0).open(file).unwrap();
    Some(Held::by(move || drop(open)))
}

#[cfg(windows)]
pub fn hold_from_deletion(file: &Path) -> Option<Held> {
    hold(file)
}

#[cfg(unix)]
pub fn hold(file: &Path) -> Option<Held> {
    use std::os::unix::fs::PermissionsExt;
    let before = fs::metadata(file).unwrap().permissions();
    fs::set_permissions(file, fs::Permissions::from_mode(0o000)).unwrap();
    let path = file.to_path_buf();
    let held = Held::by(move || fs::set_permissions(&path, before).unwrap());
    fs::File::open(file).is_err().then_some(held)
}

/// The file's folder must hold nothing else of the test: it becomes read-only, and a probe in it tells whether that
/// works for this user.
#[cfg(unix)]
pub fn hold_from_deletion(file: &Path) -> Option<Held> {
    use std::os::unix::fs::PermissionsExt;
    let folder = file.parent().unwrap().to_path_buf();
    let probe = folder.join("probe");
    fs::write(&probe, [1]).unwrap();
    let before = fs::metadata(&folder).unwrap().permissions();
    fs::set_permissions(&folder, fs::Permissions::from_mode(0o555)).unwrap();
    let held = Held::by(move || {
        fs::set_permissions(&folder, before).unwrap();
        let _ = fs::remove_file(&probe);
    });
    fs::remove_file(file.with_file_name("probe")).is_err().then_some(held)
}
