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
