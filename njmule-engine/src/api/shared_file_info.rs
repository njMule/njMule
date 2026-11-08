// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A file we share, as the front ends list it.

use std::path::PathBuf;

use crate::api::ed2k_link::FileLink;
use crate::api::upload_priority::UploadPriority;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SharedFileInfo {
    pub hash: [u8; 16],
    pub name: String,
    pub size: u64,
    pub path: PathBuf,
    pub priority: UploadPriority,
    pub statistics: Statistics,
}

impl SharedFileInfo {
    pub fn link(&self) -> FileLink {
        FileLink {
            name: self.name.clone(),
            size: self.size,
            hash: self.hash,
            aich_hash: None,
            part_hashes: Vec::new(),
            sources: Vec::new(),
            http_sources: Vec::new(),
        }
    }
}

/// How often a file was asked for, how many of those asks got a slot and how many bytes of it went out: in this run,
/// and in all as `known.met` keeps it.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Statistics {
    pub requests: u64,
    pub accepted: u64,
    pub transferred: u64,
    pub total_requests: u64,
    pub total_accepted: u64,
    pub total_transferred: u64,
}
