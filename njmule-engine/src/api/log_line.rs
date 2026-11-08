// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A line of the log the front ends show the user: what njMule did that the user would want to know.

use std::time::SystemTime;

/// Severity; `Success` marks a positive outcome that front ends highlight.
#[derive(Debug, Clone, Copy, PartialEq, Eq, strum::VariantArray)]
pub enum Level {
    Debug,
    Info,
    Success,
    Warning,
    Error,
}

impl Level {
    pub fn name(self) -> &'static str {
        match self {
            Self::Debug => "debug",
            Self::Info => "info",
            Self::Success => "success",
            Self::Warning => "warning",
            Self::Error => "error",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LogLine {
    pub time: SystemTime,
    pub level: Level,
    pub message: String,
}

impl LogLine {
    /// A line logged now.
    pub fn now(level: Level, message: impl Into<String>) -> Self {
        Self { time: SystemTime::now(), level, message: message.into() }
    }
}
