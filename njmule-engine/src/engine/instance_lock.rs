// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! One engine for a configuration: a second one on the same folder would save over what the first keeps.

use std::fs::{File, OpenOptions, TryLockError};
use std::io;
use std::path::Path;

pub const FILE_NAME: &str = "njmule.lock";

/// The lock of a configuration folder, held until it is dropped; the system lets it go with the process too.
#[derive(Debug)]
pub struct InstanceLock {
    _held: File,
}

impl InstanceLock {
    /// Takes the lock of `config_dir`; fails when another engine holds it.
    pub fn acquire(config_dir: &Path) -> io::Result<Self> {
        let file = OpenOptions::new().create(true).write(true).truncate(false).open(config_dir.join(FILE_NAME))?;
        match file.try_lock() {
            Ok(()) => Ok(Self { _held: file }),
            Err(TryLockError::WouldBlock) => Err(io::Error::new(
                io::ErrorKind::ResourceBusy,
                format!("njMule is already running with the configuration in {}", config_dir.display()),
            )),
            Err(TryLockError::Error(e)) => Err(e),
        }
    }
}
