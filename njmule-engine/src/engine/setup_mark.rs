// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A configuration no one has set up yet: marked by the start that created it, until the questions of the first start
//! are answered or put aside in any interface. Kept as a file, so that an interface opened at a later start still asks.

use std::fs;
use std::io;
use std::path::Path;

pub const FILE_NAME: &str = "setup-pending";

pub fn make(config: &Path) -> io::Result<()> {
    fs::write(config.join(FILE_NAME), [])
}

pub fn present(config: &Path) -> bool {
    config.join(FILE_NAME).exists()
}

pub fn remove(config: &Path) -> io::Result<()> {
    match fs::remove_file(config.join(FILE_NAME)) {
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(()),
        removed => removed,
    }
}
