// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What a source said of a file it shares with us: who, under which name, and the comment.

use crate::api::file_comment::FileComment;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeerComment {
    /// The nick of the source, or its address when it gave none.
    pub user: String,
    pub file_name: String,
    pub comment: FileComment,
}
