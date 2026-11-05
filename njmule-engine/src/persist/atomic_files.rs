// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Files replaced whole: written beside under another name, flushed to the disk, then renamed over the old one, so a
//! crash leaves the old file or the new one, never half of one. The version replaced stays as a backup, which reads fall
//! back on when the file is missing or rejected. Opening, renaming and deleting are tried again for a moment: on Windows
//! antivirus scanners and indexers hold files briefly.

use std::error::Error;
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufWriter, ErrorKind, Write};
use std::path::{Path, PathBuf};
use std::thread;
use std::time::Duration;

pub const TMP_SUFFIX: &str = ".tmp";
pub const BAK_SUFFIX: &str = ".bak";

const ATTEMPTS: u32 = 6;
const FIRST_RETRY_DELAY: Duration = Duration::from_millis(15);
const BUFFER_SIZE: usize = 64 * 1024;

/// What a read gave, and whether it came from the backup because the file was missing or rejected: the file is then
/// worth saving again.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Loaded<T> {
    pub value: T,
    pub from_backup: bool,
}

/// A file and its backup both unreadable: the file's failure, with the backup's.
#[derive(Debug)]
pub struct BackupFailed {
    pub file: io::Error,
    pub backup: io::Error,
}

impl fmt::Display for BackupFailed {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} (backup: {})", self.file, self.backup)
    }
}

impl Error for BackupFailed {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        Some(&self.file)
    }
}

pub fn tmp_path(file: &Path) -> PathBuf {
    with_suffix(file, TMP_SUFFIX)
}

pub fn bak_path(file: &Path) -> PathBuf {
    with_suffix(file, BAK_SUFFIX)
}

/// Replaces `file` with `data`, keeping the version replaced as its backup.
pub fn write(file: &Path, data: &[u8]) -> io::Result<()> {
    write_with(file, true, false, |out| out.write_all(data))
}

/// As [`write`], for a file only its owner may read, and its backup: a key or an identity.
pub fn write_secret(file: &Path, data: &[u8]) -> io::Result<()> {
    write_with(file, true, true, |out| out.write_all(data))
}

/// Replaces `file` with what `writer` writes; on failure `file` is left as it was. A `secret` file and its backup are
/// for their owner only, where the file system has POSIX permissions.
pub fn write_with(
    file: &Path,
    keep_backup: bool,
    secret: bool,
    writer: impl FnOnce(&mut dyn Write) -> io::Result<()>,
) -> io::Result<()> {
    let file = std::path::absolute(file)?;
    let folder = file.parent();
    if let Some(folder) = folder {
        fs::create_dir_all(folder)?;
    }
    let temporary = tmp_path(&file);
    let owner_only = secret && cfg!(unix);
    if owner_only {
        // The permissions are set when the file is created, so not on a leftover.
        remove_quietly(&temporary);
    }
    let written = (|| -> io::Result<()> {
        let mut out = BufWriter::with_capacity(BUFFER_SIZE, create(&temporary, owner_only)?);
        writer(&mut out)?;
        out.into_inner().map_err(io::IntoInnerError::into_error)?.sync_all()?;
        if keep_backup && file.exists() {
            backup(&file, &bak_path(&file), owner_only);
        }
        move_over(&temporary, &file)
    })();
    if let Err(e) = written {
        remove_quietly(&temporary);
        return Err(e);
    }
    if let Some(folder) = folder {
        sync_folder(folder);
    }
    Ok(())
}

/// Reads `file` with `reader`, falling back on its backup when the file is missing or rejected; a file held by another
/// program is tried again for a moment first. Neither there is [`ErrorKind::NotFound`].
pub fn read<T>(file: &Path, reader: impl Fn(&[u8]) -> io::Result<T>) -> io::Result<T> {
    load(file, true, reader).map(|loaded| loaded.value)
}

/// As [`read`], but a file that still cannot be opened fails instead of yielding the older backup, which the caller
/// would then save over it.
pub fn read_unless_held<T>(file: &Path, reader: impl Fn(&[u8]) -> io::Result<T>) -> io::Result<T> {
    load(file, false, reader).map(|loaded| loaded.value)
}

/// As [`read`], telling whether the backup was read.
pub fn read_telling<T>(file: &Path, reader: impl Fn(&[u8]) -> io::Result<T>) -> io::Result<Loaded<T>> {
    load(file, true, reader)
}

/// Removes the temporary file an interrupted write left; whether there was one.
pub fn cleanup_interrupted_write(file: &Path) -> bool {
    std::path::absolute(file).is_ok_and(|file| remove_quietly(&tmp_path(&file)))
}

/// Moves `from` over `to` in one step, tried again for a moment while another program holds either; a move between
/// file systems fails at once.
pub fn move_over(from: &Path, to: &Path) -> io::Result<()> {
    retried(|| fs::rename(from, to), |e| !matches!(e.kind(), ErrorKind::NotFound | ErrorKind::CrossesDevices))
}

/// Deletes `file` if it is there, tried again for a moment while another program holds it.
pub fn delete(file: &Path) -> io::Result<()> {
    retried(
        || match fs::remove_file(file) {
            Err(e) if e.kind() == ErrorKind::NotFound => Ok(()),
            done => done,
        },
        |e| e.kind() != ErrorKind::DirectoryNotEmpty,
    )
}

fn load<T>(file: &Path, backup_if_held: bool, reader: impl Fn(&[u8]) -> io::Result<T>) -> io::Result<Loaded<T>> {
    let file = std::path::absolute(file)?;
    let mut failure = None;
    if file.exists() {
        match read_retried(&file) {
            Ok(data) => match reader(&data) {
                Ok(value) => return Ok(Loaded { value, from_backup: false }),
                Err(e) => {
                    tracing::warn!("Cannot read {}, trying its backup: {e}", file.display());
                    failure = Some(e);
                }
            },
            Err(e) if !backup_if_held && e.kind() != ErrorKind::NotFound => return Err(e),
            Err(e) => {
                tracing::warn!("Cannot open {}, trying its backup: {e}", file.display());
                failure = Some(e);
            }
        }
    }
    let backup = bak_path(&file);
    if backup.exists() {
        match read_retried(&backup).and_then(|data| reader(&data)) {
            Ok(value) => {
                tracing::warn!("Recovered {} from its backup", file.display());
                return Ok(Loaded { value, from_backup: true });
            }
            Err(e) => {
                return Err(match failure {
                    Some(first) => io::Error::new(first.kind(), BackupFailed { file: first, backup: e }),
                    None => e,
                });
            }
        }
    }
    Err(failure.unwrap_or_else(|| io::Error::new(ErrorKind::NotFound, format!("no {}", file.display()))))
}

fn read_retried(file: &Path) -> io::Result<Vec<u8>> {
    retried(|| fs::read(file), |e| e.kind() != ErrorKind::NotFound)
}

// Doubling pauses, 465 ms in all: a file being scanned or indexed is not lost.
fn retried<T>(mut action: impl FnMut() -> io::Result<T>, worth_retrying: impl Fn(&io::Error) -> bool) -> io::Result<T> {
    let mut delay = FIRST_RETRY_DELAY;
    for _ in 1..ATTEMPTS {
        match action() {
            Err(e) if worth_retrying(&e) => {}
            done => return done,
        }
        thread::sleep(delay);
        delay = delay.saturating_mul(2);
    }
    action()
}

// A link costs nothing; file systems without links get a copy. Without a fresh backup the old one stays.
fn backup(file: &Path, backup: &Path, owner_only: bool) {
    let refreshed = remove_if_present(backup)
        .and_then(|()| fs::hard_link(file, backup).or_else(|_| fs::copy(file, backup).map(drop)))
        // The backup is the file being replaced, which an older version may have left readable by all.
        .and_then(|()| if owner_only { restrict_to_owner(backup) } else { Ok(()) });
    if let Err(e) = refreshed {
        tracing::warn!("Cannot refresh the backup {}: {e}", backup.display());
    }
}

fn remove_if_present(file: &Path) -> io::Result<()> {
    match fs::remove_file(file) {
        Err(e) if e.kind() == ErrorKind::NotFound => Ok(()),
        done => done,
    }
}

fn remove_quietly(file: &Path) -> bool {
    match fs::remove_file(file) {
        Ok(()) => true,
        Err(e) => {
            if e.kind() != ErrorKind::NotFound {
                tracing::debug!("Cannot delete {}: {e}", file.display());
            }
            false
        }
    }
}

fn with_suffix(file: &Path, suffix: &str) -> PathBuf {
    let mut name = file.file_name().unwrap_or_default().to_os_string();
    name.push(suffix);
    file.with_file_name(name)
}

#[cfg(unix)]
fn create(file: &Path, owner_only: bool) -> io::Result<File> {
    use std::os::unix::fs::OpenOptionsExt;
    let mut options = OpenOptions::new();
    options.write(true).create(true).truncate(true);
    if owner_only {
        options.mode(0o600);
    }
    options.open(file)
}

#[cfg(not(unix))]
fn create(file: &Path, _owner_only: bool) -> io::Result<File> {
    OpenOptions::new().write(true).create(true).truncate(true).open(file)
}

#[cfg(unix)]
fn restrict_to_owner(file: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(file, fs::Permissions::from_mode(0o600))
}

#[cfg(not(unix))]
fn restrict_to_owner(_file: &Path) -> io::Result<()> {
    Ok(())
}

// Makes the renames durable on POSIX.
#[cfg(unix)]
fn sync_folder(folder: &Path) {
    if let Err(e) = File::open(folder).and_then(|folder| folder.sync_all()) {
        tracing::trace!("Cannot sync {}: {e}", folder.display());
    }
}

// Windows cannot open folders as files.
#[cfg(not(unix))]
fn sync_folder(_folder: &Path) {}
