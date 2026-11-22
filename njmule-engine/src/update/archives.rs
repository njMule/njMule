// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Unpacks the zip and tar.xz packages of a new version, with what they hold below their one top folder (the package's
//! name) put straight into the target. Names that would leave the target (absolute, `..`, a drive), links, devices,
//! more than [`MAX_ENTRIES`] entries or 4 GiB in all are refused: the package was checked against its signed hash, this
//! guards against a mistake in it. The Unix permissions of a tar entry are kept.

use std::fs::{self, File};
use std::io::{self, BufReader, Read};
use std::path::{Path, PathBuf};

use lzma_rust2::XzReader;
use tar::EntryType;
use zip::ZipArchive;

pub const MAX_ENTRIES: usize = 20_000;
pub const MAX_BYTES: u64 = 4 * 1024 * 1024 * 1024;

const MAX_BYTES_TEXT: &str = "4 GiB";
const BUFFER: usize = 64 * 1024;
const LIFTED: &str = ".njmule-unpacked";

/// The kind of package a name is, by its ending.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Format {
    Zip,
    TarXz,
}

impl Format {
    pub fn of(name: &str) -> io::Result<Self> {
        let lower = name.to_lowercase();
        if lower.ends_with(".zip") {
            Ok(Self::Zip)
        } else if lower.ends_with(".tar.xz") {
            Ok(Self::TarXz)
        } else {
            Err(io::Error::new(io::ErrorKind::InvalidData, format!("not a zip or tar.xz package: {name}")))
        }
    }
}

// What was unpacked, by its checked name.
struct Unpacked {
    name: String,
    directory: bool,
}

/// Unpacks `archive` into `target`, which must be empty or missing.
pub fn unpack(archive: &Path, target: &Path) -> io::Result<()> {
    fs::create_dir_all(target)?;
    if fs::read_dir(target)?.next().is_some() {
        return Err(io::Error::other(format!("not an empty folder: {}", target.display())));
    }
    let name = archive.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default();
    let files = match Format::of(&name)? {
        Format::Zip => unzip(archive, target)?,
        Format::TarXz => untar(archive, target)?,
    };
    lift(&files, target)
}

fn unzip(archive: &Path, target: &Path) -> io::Result<Vec<Unpacked>> {
    let mut zip = ZipArchive::new(File::open(archive)?).map_err(io::Error::other)?;
    let mut unpacked = Vec::new();
    let mut total: u64 = 0;
    for index in 0..zip.len() {
        count(&unpacked)?;
        let mut entry = zip.by_index(index).map_err(io::Error::other)?;
        let name = checked(&entry.name().map_err(io::Error::other)?)?;
        if entry.is_dir() {
            fs::create_dir_all(target.join(&name))?;
            unpacked.push(Unpacked { name, directory: true });
            continue;
        }
        total = total.saturating_add(write(&mut entry, &target.join(&name), total)?);
        unpacked.push(Unpacked { name, directory: false });
    }
    Ok(unpacked)
}

fn untar(archive: &Path, target: &Path) -> io::Result<Vec<Unpacked>> {
    let xz = XzReader::new(BufReader::with_capacity(BUFFER, File::open(archive)?), false);
    let mut tar = tar::Archive::new(xz);
    let mut unpacked = Vec::new();
    let mut total: u64 = 0;
    for entry in tar.entries()? {
        let mut entry = entry?;
        count(&unpacked)?;
        let name = checked(&String::from_utf8_lossy(&entry.path_bytes()))?;
        let kind = entry.header().entry_type();
        if !matches!(kind, EntryType::Regular | EntryType::Continuous | EntryType::Directory) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("the package holds a link or a special file: {name}"),
            ));
        }
        let to = target.join(&name);
        if kind == EntryType::Directory {
            fs::create_dir_all(&to)?;
            unpacked.push(Unpacked { name, directory: true });
            continue;
        }
        total = total.saturating_add(write(&mut entry, &to, total)?);
        keep_mode(&to, entry.header().mode().unwrap_or(0o644))?;
        unpacked.push(Unpacked { name, directory: false });
    }
    Ok(unpacked)
}

fn count(unpacked: &[Unpacked]) -> io::Result<()> {
    if unpacked.len() >= MAX_ENTRIES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("the package holds more than {MAX_ENTRIES} files"),
        ));
    }
    Ok(())
}

/// A relative name with forward slashes and no step out of the target.
pub fn checked(raw: &str) -> io::Result<String> {
    let refused = || io::Error::new(io::ErrorKind::InvalidData, format!("a name the package may not hold: {raw}"));
    let mut name = raw.replace('\\', "/");
    while let Some(rest) = name.strip_prefix("./") {
        name = rest.to_owned();
    }
    if let Some(rest) = name.strip_suffix('/') {
        name = rest.to_owned();
    }
    if name.is_empty() || name.starts_with('/') || name.contains(':') || name.contains('\0') {
        return Err(refused());
    }
    if name.split('/').any(|step| step.is_empty() || step == "." || step == "..") {
        return Err(refused());
    }
    Ok(name)
}

fn write(input: &mut impl Read, to: &Path, before: u64) -> io::Result<u64> {
    if let Some(parent) = std::path::absolute(to)?.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut out = File::create(to)?;
    let mut written: u64 = 0;
    let mut buffer = vec![0u8; BUFFER];
    loop {
        let n = input.read(&mut buffer)?;
        let Some(read) = buffer.get(..n).filter(|read| !read.is_empty()) else {
            break;
        };
        written = written.saturating_add(u64::try_from(n).unwrap_or(u64::MAX));
        if before.saturating_add(written) > MAX_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("the package unpacks to more than {MAX_BYTES_TEXT}"),
            ));
        }
        io::Write::write_all(&mut out, read)?;
    }
    Ok(written)
}

// The owner must at least read and replace what it unpacked.
#[cfg(unix)]
fn keep_mode(file: &Path, mode: u32) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(file, fs::Permissions::from_mode(mode & 0o777 | 0o600))
}

#[cfg(not(unix))]
fn keep_mode(_file: &Path, _mode: u32) -> io::Result<()> {
    Ok(())
}

// The package's own folder (njmule-0.2.0-linux-x64/) goes: its content is what replaces the installation's.
fn lift(files: &[Unpacked], target: &Path) -> io::Result<()> {
    let Some(first) = files.first() else {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "an empty package"));
    };
    let top = first.name.split('/').next().unwrap_or_default();
    let within = format!("{top}/");
    let one_folder =
        files.iter().all(|file| if file.name == top { file.directory } else { file.name.starts_with(&within) });
    if !one_folder || !target.join(top).is_dir() {
        return Ok(());
    }
    // Moved aside first: it may hold a file of its own name.
    let folder = target.join(LIFTED);
    fs::rename(target.join(top), &folder)?;
    let children: Vec<PathBuf> =
        fs::read_dir(&folder)?.map(|entry| entry.map(|entry| entry.path())).collect::<io::Result<_>>()?;
    for child in children {
        if let Some(name) = child.file_name() {
            fs::rename(&child, target.join(name))?;
        }
    }
    fs::remove_dir(folder)
}
