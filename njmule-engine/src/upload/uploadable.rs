// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A file other clients may download from us: a shared file, or a download whose checked parts are shared while it goes
//! on.

use std::path::PathBuf;

use crate::files::hash::ed2k;
use crate::files::shared_files::SharedFile;

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
