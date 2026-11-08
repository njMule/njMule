// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! How much a shared file's clients are favoured in the upload queue.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum UploadPriority {
    VeryLow,
    Low,
    Normal,
    High,
    /// Spreads a new file first.
    Release,
    /// Follows how many clients want the file: rare files spread first.
    #[default]
    Auto,
}

impl UploadPriority {
    pub const ALL: [Self; 6] = [Self::VeryLow, Self::Low, Self::Normal, Self::High, Self::Release, Self::Auto];

    pub fn name(self) -> &'static str {
        match self {
            Self::VeryLow => "very_low",
            Self::Low => "low",
            Self::Normal => "normal",
            Self::High => "high",
            Self::Release => "release",
            Self::Auto => "auto",
        }
    }

    /// The priority of its name, in any case.
    pub fn of_name(text: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|priority| priority.name().eq_ignore_ascii_case(text))
    }

    /// The value `known.met` keeps, which other clients write too, so an imported file keeps its priority.
    pub fn code(self) -> u64 {
        match self {
            Self::Low => 0,
            Self::Normal => 1,
            Self::High => 2,
            Self::Release => 3,
            Self::VeryLow => 4,
            Self::Auto => 5,
        }
    }

    /// The priority of a value of `known.met`; normal for one unknown.
    pub fn of_code(code: u64) -> Self {
        Self::ALL.into_iter().find(|priority| priority.code() == code).unwrap_or(Self::Normal)
    }
}
