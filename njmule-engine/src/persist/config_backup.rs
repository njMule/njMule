// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Timestamped zip copies of the configuration, the latest few kept: what a disk failure or a bad edit would otherwise
//! take. Lock and handoff files, temporary and backup copies are left out, and so are the AICH hash sets, which can be
//! large and are rebuilt by hashing the files again, and the Kad index, other nodes' entries that live a day at most.

use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use time::{OffsetDateTime, UtcOffset};
use zip::write::SimpleFileOptions;
use zip::{DateTime, ZipWriter};

use crate::persist::atomic_files;

/// The folder of the copies, below the data directory.
pub const FOLDER: &str = "backups";

const PREFIX: &str = "njmule-config-";
const SUFFIX: &str = ".zip";
const LEFT_OUT: [&str; 7] =
    ["njmule.lock", "handoff", "known2_64.met", "key_index.dat", "src_index.dat", "notes_index.dat", "load_index.dat"];

/// Writes a copy of the files of `config` made `at` into `folder` and removes the oldest copies beyond `keep`; returns
/// the new copy.
pub fn write(config: &Path, folder: &Path, keep: usize, at: OffsetDateTime) -> io::Result<PathBuf> {
    if keep < 1 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("at least one copy is kept: {keep}")));
    }
    fs::create_dir_all(folder)?;
    // In UTC: local time repeats an hour when summer time ends, and the names must sort as the copies were made.
    let utc = at.to_offset(UtcOffset::UTC);
    let name = format!(
        "{PREFIX}{:04}{:02}{:02}-{:02}{:02}{:02}{SUFFIX}",
        utc.year(),
        u8::from(utc.month()),
        utc.day(),
        utc.hour(),
        utc.minute(),
        utc.second()
    );
    let target = folder.join(name);
    let files = files(config)?;
    // The entries carry that time too: a zip's times have no zone, and the local one would need the system's offset.
    let year = u16::try_from(utc.year()).unwrap_or_default();
    let stamp =
        DateTime::from_date_and_time(year, utc.month().into(), utc.day(), utc.hour(), utc.minute(), utc.second())
            .unwrap_or_default();
    atomic_files::write_with(&target, false, true, |out| {
        let mut zip = ZipWriter::new_stream(out);
        let options = SimpleFileOptions::default().last_modified_time(stamp);
        for file in &files {
            let entry = file.strip_prefix(config).unwrap_or(file).to_string_lossy().into_owned();
            zip.start_file(entry, options).map_err(io::Error::other)?;
            zip.write_all(&fs::read(file)?)?;
        }
        // Finished, not closed: the stream belongs to the atomic write, which forces it to disk.
        zip.finish().map_err(io::Error::other)?;
        Ok(())
    })?;
    prune(folder, keep, &target)?;
    Ok(target)
}

/// The copies in `folder`, oldest first.
pub fn copies(folder: &Path) -> io::Result<Vec<PathBuf>> {
    if !folder.is_dir() {
        return Ok(Vec::new());
    }
    let mut copies = Vec::new();
    for entry in fs::read_dir(folder)? {
        let path = entry?.path();
        // A file of the user that only looks like a copy is not ours to prune.
        if path.file_name().and_then(|name| name.to_str()).is_some_and(is_copy_name) {
            copies.push(path);
        }
    }
    // The stamp sorts as the time.
    copies.sort();
    Ok(copies)
}

// `njmule-config-yyyyMMdd-HHmmss.zip`.
fn is_copy_name(name: &str) -> bool {
    name.strip_prefix(PREFIX)
        .and_then(|rest| rest.strip_suffix(SUFFIX))
        .and_then(|stamp| stamp.split_once('-'))
        .is_some_and(|(date, time)| {
            date.len() == 8 && time.len() == 6 && date.bytes().chain(time.bytes()).all(|b| b.is_ascii_digit())
        })
}

fn files(config: &Path) -> io::Result<Vec<PathBuf>> {
    let mut files = Vec::new();
    for entry in fs::read_dir(config)? {
        let path = entry?.path();
        let name = path.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default();
        if path.is_file() && !LEFT_OUT.contains(&name.as_str()) && !name.ends_with(".tmp") && !name.ends_with(".bak") {
            files.push(path);
        }
    }
    files.sort();
    Ok(files)
}

// The new copy stays whatever its name sorts after: a clock set back gives it an older one.
fn prune(folder: &Path, keep: usize, written: &Path) -> io::Result<()> {
    let others: Vec<PathBuf> = copies(folder)?.into_iter().filter(|copy| copy != written).collect();
    for old in others.iter().take(others.len().saturating_sub(keep.saturating_sub(1))) {
        match fs::remove_file(old) {
            Err(e) if e.kind() != io::ErrorKind::NotFound => return Err(e),
            _ => {}
        }
    }
    Ok(())
}
