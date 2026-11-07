// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A download as the interfaces show it, and the sources it has.

use crate::files::part_file::PartMap;

/// Where a download stands.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DownloadStatus {
    Downloading,
    Paused,
    Stopped,
    /// Waiting for free space on the temp disk.
    NoSpace,
    Error,
    Complete,
}

impl DownloadStatus {
    pub fn name(self) -> &'static str {
        match self {
            Self::Downloading => "downloading",
            Self::Paused => "paused",
            Self::Stopped => "stopped",
            Self::NoSpace => "no_space",
            Self::Error => "error",
            Self::Complete => "complete",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum DownloadPriority {
    Low,
    Normal,
    High,
    /// Normal, or high for a download with few sources.
    Auto,
}

// Sources a download with AUTO priority counts as rare below, and as common above.
const FEW_SOURCES: usize = 20;
const MANY_SOURCES: usize = 100;

impl DownloadPriority {
    /// The priority it acts with, for a file with `sources` known sources: AUTO puts rare files first.
    pub fn effective(self, sources: usize) -> Self {
        match self {
            Self::Auto if sources > MANY_SOURCES => Self::Low,
            Self::Auto if sources > FEW_SOURCES => Self::Normal,
            Self::Auto => Self::High,
            other => other,
        }
    }

    /// Where an effective priority comes in the order downloads act in: the higher, the sooner.
    pub fn rank(self) -> u8 {
        match self {
            Self::Low => 0,
            Self::Normal | Self::Auto => 1,
            Self::High => 2,
        }
    }
    pub fn name(self) -> &'static str {
        match self {
            Self::Low => "low",
            Self::Normal => "normal",
            Self::High => "high",
            Self::Auto => "auto",
        }
    }

    /// The priority of its name, in any case.
    pub fn of_name(text: &str) -> Option<Self> {
        [Self::Low, Self::Normal, Self::High, Self::Auto]
            .into_iter()
            .find(|priority| priority.name().eq_ignore_ascii_case(text))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DownloadInfo {
    pub hash: [u8; 16],
    pub name: String,
    pub size: u64,
    pub completed: u64,
    pub status: DownloadStatus,
    pub priority: DownloadPriority,
    pub category: u32,
    pub sources: usize,
    pub transferring: usize,
    pub bytes_per_second: u64,
    pub parts: PartMap,
    pub preview_first: bool,
    pub auto_swap: bool,
}

/// What a source of a download is doing for it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SourceState {
    Waiting,
    Connecting,
    Asking,
    Queued,
    Downloading,
    /// Asked for another download.
    A4af,
    QueueFull,
    NoNeededParts,
}

impl SourceState {
    pub fn name(self) -> &'static str {
        match self {
            Self::Waiting => "waiting",
            Self::Connecting => "connecting",
            Self::Asking => "asking",
            Self::Queued => "queued",
            Self::Downloading => "downloading",
            Self::A4af => "a4af",
            Self::QueueFull => "queue_full",
            Self::NoNeededParts => "no_needed_parts",
        }
    }
}

/// A source of a download, as the list of downloading clients shows it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceInfo {
    pub file: [u8; 16],
    pub file_name: String,
    pub address: String,
    pub nick: Option<String>,
    pub software: String,
    pub state: SourceState,
    pub queue_rank: u32,
    pub received: u64,
    pub parts: usize,
    pub part_count: usize,
    pub reported_name: Option<String>,
}
