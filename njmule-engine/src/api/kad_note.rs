// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A note a Kad node published on a file: its publisher's id in hex, the name it gives the file, and its comment.

use crate::api::file_comment::FileComment;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KadNote {
    pub publisher: String,
    pub file_name: String,
    pub comment: FileComment,
}
