// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The all-time statistics kept across sessions: the bytes downloaded and uploaded, the sessions and the seconds run.

use std::fmt::Write as _;
use std::path::{Path, PathBuf};

use crate::persist::{atomic_files, property_lines};

pub const FILE_NAME: &str = "statistics.properties";

// The largest number the file holds, which the Java engine reads too.
const LARGEST: u64 = i64::MAX.unsigned_abs();

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Totals {
    file: PathBuf,
    downloaded: u64,
    uploaded: u64,
    sessions: u64,
    seconds: u64,
}

impl Totals {
    /// The totals before this session; a broken file counts as none.
    pub fn load(file: &Path) -> Self {
        let mut totals = Self { file: file.to_path_buf(), downloaded: 0, uploaded: 0, sessions: 0, seconds: 0 };
        if !file.exists() && !atomic_files::bak_path(file).exists() {
            return totals;
        }
        match property_lines::read(file) {
            Ok(values) => {
                let number = |key: &str| {
                    values
                        .get(key)
                        .and_then(|text| text.trim().parse::<i64>().ok())
                        .map_or(0, |n| n.max(0).unsigned_abs())
                };
                totals.downloaded = number("downloaded");
                totals.uploaded = number("uploaded");
                totals.sessions = number("sessions");
                totals.seconds = number("seconds");
            }
            Err(e) => tracing::warn!("Cannot read {}, the totals start again: {e}", file.display()),
        }
        totals
    }

    pub fn downloaded(&self) -> u64 {
        self.downloaded
    }

    pub fn uploaded(&self) -> u64 {
        self.uploaded
    }

    /// Sessions before this one.
    pub fn sessions(&self) -> u64 {
        self.sessions
    }

    /// Seconds run before this session.
    pub fn seconds(&self) -> u64 {
        self.seconds
    }

    /// Saves the totals with this session's bytes and time; they stop at the largest number rather than wrap.
    pub fn save(&self, session_downloaded: u64, session_uploaded: u64, session_seconds: u64) {
        let mut text = String::from("# njMule all-time statistics\n");
        for (key, total) in [
            ("downloaded", add(self.downloaded, session_downloaded)),
            ("uploaded", add(self.uploaded, session_uploaded)),
            ("sessions", add(self.sessions, 1)),
            ("seconds", add(self.seconds, session_seconds)),
        ] {
            let _ = writeln!(text, "{key}={total}");
        }
        if let Err(e) = atomic_files::write(&self.file, text.as_bytes()) {
            tracing::warn!("Cannot save {}: {e}", self.file.display());
        }
    }
}

/// A total with more added, at most the largest number the file holds.
pub fn add(total: u64, more: u64) -> u64 {
    total.saturating_add(more).min(LARGEST)
}
