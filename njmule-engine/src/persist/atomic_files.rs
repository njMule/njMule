// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Files replaced whole: written beside under another name, flushed to the disk, then renamed over the old one, so a
//! crash leaves the old file or the new one, never half of one.

use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::Path;

/// Writes `data` as `file`; a `secret` file is for its owner only, as it holds what identifies the user.
pub fn write(file: &Path, data: &[u8], secret: bool) -> io::Result<()> {
    let mut name = file.file_name().unwrap_or_default().to_os_string();
    name.push(".tmp");
    let temporary = file.with_file_name(name);
    {
        let mut out = create(&temporary, secret)?;
        out.write_all(data)?;
        out.sync_all()?;
    }
    fs::rename(&temporary, file)
}

#[cfg(unix)]
fn create(file: &Path, secret: bool) -> io::Result<File> {
    use std::os::unix::fs::OpenOptionsExt;
    OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(if secret { 0o600 } else { 0o644 })
        .open(file)
}

#[cfg(not(unix))]
fn create(file: &Path, _secret: bool) -> io::Result<File> {
    OpenOptions::new().write(true).create(true).truncate(true).open(file)
}
