// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The completed downloads, listed until the user clears them; kept in `finished.json` from one run to the next.

use std::fs;
use std::path::{Path, PathBuf};

use indexmap::IndexMap;
use serde_json::{Value, json};

use crate::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus};
use crate::files::part_file::PartMap;
use crate::persist::atomic_files;

pub const FILE_NAME: &str = "finished.json";

const FORMAT: u64 = 1;

pub struct FinishedDownloads {
    file: PathBuf,
    // In the order they completed.
    finished: IndexMap<[u8; 16], DownloadInfo>,
}

impl FinishedDownloads {
    /// The completed downloads kept in `config`; none when they cannot be read.
    pub fn load(config: &Path) -> Self {
        let mut kept = Self { file: config.join(FILE_NAME), finished: IndexMap::new() };
        let Ok(data) = fs::read(&kept.file) else {
            return kept;
        };
        let root: Value = match serde_json::from_slice(&data) {
            Ok(root) => root,
            Err(e) => {
                tracing::warn!("Starting a new list of completed downloads: cannot read {}: {e}", kept.file.display());
                return kept;
            }
        };
        if root.get("format").and_then(Value::as_u64) != Some(FORMAT) {
            return kept;
        }
        for item in root.get("downloads").and_then(Value::as_array).into_iter().flatten() {
            let hash = item.get("hash").and_then(Value::as_str).and_then(parse_hash);
            let name = item.get("name").and_then(Value::as_str);
            let size = item.get("size").and_then(Value::as_u64);
            let priority = item.get("priority").and_then(Value::as_str).and_then(priority);
            let category = item.get("category").and_then(Value::as_u64).and_then(|c| u32::try_from(c).ok());
            if let (Some(hash), Some(name), Some(size), Some(priority), Some(category)) =
                (hash, name, size, priority, category)
            {
                kept.finished.insert(hash, complete(hash, name, size, priority, category));
            }
        }
        kept
    }

    /// Keeps `download`, which has just completed.
    pub fn add(&mut self, download: &DownloadInfo) {
        self.finished.shift_remove(&download.hash);
        self.finished.insert(
            download.hash,
            complete(download.hash, &download.name, download.size, download.priority, download.category),
        );
        self.save();
    }

    /// Forgets the completed download of `hash`, as one downloaded again; false when there is none.
    pub fn remove(&mut self, hash: &[u8; 16]) -> bool {
        if self.finished.shift_remove(hash).is_none() {
            return false;
        }
        self.save();
        true
    }

    /// Forgets every completed download, and tells which they were.
    pub fn clear(&mut self) -> Vec<[u8; 16]> {
        let cleared: Vec<[u8; 16]> = self.finished.keys().copied().collect();
        if !cleared.is_empty() {
            self.finished.clear();
            self.save();
        }
        cleared
    }

    /// The completed downloads, in the order they completed.
    pub fn downloads(&self) -> impl Iterator<Item = &DownloadInfo> {
        self.finished.values()
    }

    fn save(&self) {
        let downloads: Vec<Value> = self
            .finished
            .values()
            .map(|d| {
                json!({
                    "hash": hex(&d.hash),
                    "name": d.name,
                    "size": d.size,
                    "priority": d.priority.name().to_uppercase(),
                    "category": d.category,
                })
            })
            .collect();
        let root = json!({"format": FORMAT, "downloads": downloads});
        let written = serde_json::to_vec(&root)
            .map_err(std::io::Error::other)
            .and_then(|data| atomic_files::write(&self.file, &data, false));
        if let Err(e) = written {
            tracing::warn!("Cannot save the completed downloads to {}: {e}", self.file.display());
        }
    }
}

// Sources and rates end with the download; every byte is there.
fn complete(hash: [u8; 16], name: &str, size: u64, priority: DownloadPriority, category: u32) -> DownloadInfo {
    DownloadInfo {
        hash,
        name: name.to_owned(),
        size,
        completed: size,
        status: DownloadStatus::Complete,
        priority,
        category,
        sources: 0,
        transferring: 0,
        bytes_per_second: 0,
        parts: PartMap { size, missing: Vec::new(), requested: Vec::new(), sources: Vec::new() },
        preview_first: false,
        auto_swap: false,
    }
}

fn priority(name: &str) -> Option<DownloadPriority> {
    match name {
        "LOW" => Some(DownloadPriority::Low),
        "NORMAL" => Some(DownloadPriority::Normal),
        "HIGH" => Some(DownloadPriority::High),
        "AUTO" => Some(DownloadPriority::Auto),
        _ => None,
    }
}

fn hex(hash: &[u8; 16]) -> String {
    data_encoding::HEXUPPER.encode(hash)
}

fn parse_hash(text: &str) -> Option<[u8; 16]> {
    data_encoding::HEXUPPER_PERMISSIVE.decode(text.as_bytes()).ok()?.try_into().ok()
}
