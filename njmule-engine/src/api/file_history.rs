// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What we know of files from before: those downloaded or hashed for sharing, and those cancelled.

use std::collections::HashSet;

use crate::files::cancelled_files::Snapshot as CancelledSnapshot;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FileHistory {
    downloaded: HashSet<[u8; 16]>,
    cancelled: HashSet<[u8; 16]>,
    cancelled_keyed: CancelledSnapshot,
}

impl FileHistory {
    pub fn of(downloaded: HashSet<[u8; 16]>, cancelled: HashSet<[u8; 16]>) -> Self {
        Self { downloaded, cancelled, cancelled_keyed: CancelledSnapshot::default() }
    }

    pub fn with_cancelled(downloaded: HashSet<[u8; 16]>, cancelled_keyed: CancelledSnapshot) -> Self {
        Self { downloaded, cancelled: HashSet::new(), cancelled_keyed }
    }

    pub fn downloaded(&self, file: &[u8; 16]) -> bool {
        self.downloaded.contains(file)
    }

    pub fn cancelled(&self, file: &[u8; 16]) -> bool {
        self.cancelled.contains(file) || self.cancelled_keyed.contains(file)
    }
}
