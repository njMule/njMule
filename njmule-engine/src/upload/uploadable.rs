// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A file other clients may download from us: a shared file, or a download whose checked parts are shared while it goes
//! on.

use std::path::PathBuf;

use crate::files::hash::ed2k;
use crate::files::shared_files::SharedFile;
use crate::protocol::ed2k::client_messages::{FileStatus, PartStatus};

// Already compressed: zlib would only cost time.
const PACKED: [&str; 34] = [
    "7z", "aac", "ape", "avi", "bz2", "cab", "flac", "flv", "gif", "gz", "jpeg", "jpg", "m4a", "m4v", "mkv", "mov",
    "mp3", "mp4", "mpeg", "mpg", "ogg", "ogm", "opus", "png", "rar", "rm", "rmvb", "webm", "webp", "wma", "wmv", "xz",
    "zip", "zst",
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Uploadable {
    pub hash: [u8; 16],
    pub name: String,
    pub size: u64,
    pub part_hashes: Vec<[u8; 16]>,
    /// The parts that can be sent, none for a complete file.
    pub parts: Option<Vec<bool>>,
    pub data: PathBuf,
    pub aich_hash: Option<[u8; 20]>,
}

impl Uploadable {
    pub fn of(file: &SharedFile) -> Self {
        Self {
            hash: file.hash,
            name: file.name.clone(),
            size: file.size,
            part_hashes: file.part_hashes.clone(),
            parts: None,
            data: file.path.clone(),
            aich_hash: file.aich_hash,
        }
    }

    /// A file being downloaded, of which the `verified` parts can be sent from its data file.
    pub fn partial(
        hash: [u8; 16],
        name: &str,
        size: u64,
        part_hashes: &[[u8; 16]],
        verified: &[bool],
        data: PathBuf,
        aich_hash: Option<[u8; 20]>,
    ) -> Self {
        Self {
            hash,
            name: name.to_owned(),
            size,
            part_hashes: part_hashes.to_vec(),
            parts: Some(verified.to_vec()),
            data,
            aich_hash,
        }
    }

    /// Whether any of it can be sent: a download with no part checked is ours, but has nothing to give yet.
    pub fn is_shareable(&self) -> bool {
        self.parts.as_ref().is_none_or(|parts| parts.contains(&true))
    }

    /// OP_FILESTATUS of the file: no bits for a complete file, else the checked parts.
    pub fn status(&self) -> FileStatus {
        match &self.parts {
            None => FileStatus { file: self.hash, part_count: 0, parts: Vec::new() },
            Some(parts) => {
                let status = PartStatus::of(self.size, parts);
                FileStatus { file: self.hash, part_count: status.part_count, parts: status.parts }
            }
        }
    }

    pub fn is_complete(&self) -> bool {
        self.parts.is_none()
    }

    /// Whether every byte of `[start, end)` can be sent.
    pub fn has(&self, start: u64, end: u64) -> bool {
        if end > self.size || start >= end {
            return false;
        }
        let Some(parts) = &self.parts else {
            return true;
        };
        let first = start / ed2k::PARTSIZE;
        let last = end.saturating_sub(1) / ed2k::PARTSIZE;
        (first..=last).all(|part| usize::try_from(part).ok().and_then(|part| parts.get(part)).copied().unwrap_or(false))
    }

    /// Whether blocks of the file are worth compressing.
    pub fn compressible(&self) -> bool {
        match self.name.rsplit_once('.') {
            Some((_, extension)) => !PACKED.contains(&extension.to_lowercase().as_str()),
            None => true,
        }
    }
}
