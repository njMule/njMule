// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Undoes what the desktop app set up on this system (`--restore-links`), as an uninstaller asks: gives the ed2k links
//! back when njMule still has them, and stops it from starting at login.

use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use rand::RngExt;

use crate::api::nj_mule_info;
use crate::app::autostart::{Autostart, SystemAutostart};
use crate::app::link_association::LinkAssociation;

pub const EXIT_OK: i32 = 0;
pub const EXIT_FAILURE: i32 = 1;

/// Restores ed2k links and disables start-at-login for this computer's installation.
pub fn restore_for_this_system<W1: Write, W2: Write>(config: &Path, out: &mut W1, err: &mut W2) -> i32 {
    restore(
        config,
        |dir| LinkAssociation::for_this_system(dir.to_path_buf()),
        |dir| {
            SystemAutostart::for_this_system(dir.to_path_buf(), &[]).map(|start| Box::new(start) as Box<dyn Autostart>)
        },
        out,
        err,
    )
}

/// Restores ed2k links and disables start-at-login using caller-provided system factories (for tests).
pub fn restore<FL, FA, W1, W2>(config: &Path, associations: FL, autostarts: FA, out: &mut W1, err: &mut W2) -> i32
where
    FL: Fn(&Path) -> Option<LinkAssociation>,
    FA: Fn(&Path) -> Option<Box<dyn Autostart>>,
    W1: Write,
    W2: Write,
{
    let scratch = if config.is_dir() {
        None
    } else {
        match private_temp_directory("njmule-links-") {
            Ok(dir) => Some(dir),
            Err(e) => {
                let _ = writeln!(err, "Cannot give the ed2k links back: {e}");
                return EXIT_FAILURE;
            }
        }
    };
    let dir = scratch.as_ref().map_or(config, ScratchDir::path);
    let mut code = give_links_back(associations(dir), out, err);
    if let Some(start) = autostarts(dir) {
        match start.is_on() {
            Ok(true) => match start.turn_off() {
                Ok(()) => {
                    let _ = writeln!(out, "{} no longer starts when you log in.", nj_mule_info::NAME);
                }
                Err(e) => {
                    let _ = writeln!(err, "Cannot stop {} from starting at login: {e}", nj_mule_info::NAME);
                    code = EXIT_FAILURE;
                }
            },
            Ok(false) => {}
            Err(e) => {
                let _ = writeln!(err, "Cannot stop {} from starting at login: {e}", nj_mule_info::NAME);
                code = EXIT_FAILURE;
            }
        }
    }
    code
}

fn give_links_back<W1: Write, W2: Write>(association: Option<LinkAssociation>, out: &mut W1, err: &mut W2) -> i32 {
    let Some(association) = association else {
        let _ = writeln!(out, "This program does not open ed2k links here: nothing to give back.");
        return EXIT_OK;
    };
    match association.is_ours() {
        Ok(false) => {
            let _ = writeln!(out, "ed2k links open in another program: left as they are.");
            EXIT_OK
        }
        Ok(true) => match association.give_back() {
            Ok(()) => {
                let _ = writeln!(out, "ed2k links given back.");
                EXIT_OK
            }
            Err(e) => {
                let _ = writeln!(err, "Cannot give the ed2k links back: {e}");
                EXIT_FAILURE
            }
        },
        Err(e) => {
            let _ = writeln!(err, "Cannot give the ed2k links back: {e}");
            EXIT_FAILURE
        }
    }
}

struct ScratchDir(PathBuf);

impl ScratchDir {
    fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for ScratchDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir(&self.0);
    }
}

// A temporary directory only this user may enter.
fn private_temp_directory(prefix: &str) -> io::Result<ScratchDir> {
    let base = std::env::temp_dir();
    let mut rng = rand::rng();
    for _ in 0..64 {
        let id: u64 = rng.random();
        let path = base.join(format!("{prefix}{id:016x}"));
        #[cfg_attr(not(unix), allow(unused_mut))]
        let mut builder = fs::DirBuilder::new();
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        match builder.create(&path) {
            Ok(()) => return Ok(ScratchDir(path)),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {}
            Err(e) => return Err(e),
        }
    }
    Err(io::Error::new(io::ErrorKind::AlreadyExists, "cannot create a scratch directory"))
}
