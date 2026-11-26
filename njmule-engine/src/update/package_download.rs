// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Downloads the package of a new version next to where it is kept, going on from what an earlier try left, and keeps it
//! only when its length and SHA-256 hash are those of the signed release information.

use std::fs::{self, File};
use std::future::Future;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use data_encoding::HEXLOWER;
use sha2::{Digest, Sha256};

use super::update_files::{self, PART};
use crate::api::new_version::Download;

/// What tells the length downloaded so far.
pub type Progress<'a> = &'a (dyn Fn(u64) + Send + Sync);

/// Streams a file to disk, appending to what `part` holds already.
pub trait Transfer: Send + Sync {
    fn download(
        &self,
        url: &str,
        part: &Path,
        size: u64,
        progress: Progress<'_>,
    ) -> impl Future<Output = io::Result<()>> + Send;
}

impl<T: Transfer> Transfer for Arc<T> {
    fn download(
        &self,
        url: &str,
        part: &Path,
        size: u64,
        progress: Progress<'_>,
    ) -> impl Future<Output = io::Result<()>> + Send {
        (**self).download(url, part, size, progress)
    }
}

/// The package `download` at `file`, downloaded unless it is there already and whole.
pub async fn fetch(
    transfer: &impl Transfer,
    download: &Download,
    file: &Path,
    progress: Progress<'_>,
) -> io::Result<PathBuf> {
    if matches(file, download)? {
        progress(download.size);
        return Ok(file.to_path_buf());
    }
    tokio::fs::create_dir_all(update_files::parent(file)?).await?;
    remove_if_there(file)?;
    let part = part_of(file);
    transfer.download(&download.url, &part, download.size, progress).await?;
    if !matches(&part, download)? {
        // Whole but wrong: a new try starts from nothing.
        remove_if_there(&part)?;
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "the downloaded package does not match its signed hash",
        ));
    }
    tokio::fs::rename(&part, file).await?;
    Ok(file.to_path_buf())
}

/// Whether `file` has the length and hash of `download`.
pub fn matches(file: &Path, download: &Download) -> io::Result<bool> {
    Ok(file.is_file() && fs::metadata(file)?.len() == download.size && sha256(file)? == download.sha256)
}

pub fn sha256(file: &Path) -> io::Result<String> {
    let mut digest = Sha256::new();
    let mut input = File::open(file)?;
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        let n = input.read(&mut buffer)?;
        let Some(read) = buffer.get(..n).filter(|read| !read.is_empty()) else {
            break;
        };
        digest.update(read);
    }
    Ok(HEXLOWER.encode(&digest.finalize()))
}

/// The file a package is downloaded to while it comes.
pub fn part_of(file: &Path) -> PathBuf {
    let mut name = file.file_name().unwrap_or_default().to_os_string();
    name.push(PART);
    file.with_file_name(name)
}

fn remove_if_there(file: &Path) -> io::Result<()> {
    match fs::remove_file(file) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}
