// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What we know of files from before: those downloaded or hashed for sharing, and those cancelled.

use std::collections::HashSet;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FileHistory {
    downloaded: HashSet<[u8; 16]>,
    cancelled: HashSet<[u8; 16]>,
}

impl FileHistory {
    pub fn of(downloaded: HashSet<[u8; 16]>, cancelled: HashSet<[u8; 16]>) -> Self {
        Self { downloaded, cancelled }
    }

    pub fn downloaded(&self, file: &[u8; 16]) -> bool {
        self.downloaded.contains(file)
    }

    pub fn cancelled(&self, file: &[u8; 16]) -> bool {
        self.cancelled.contains(file)
    }
}
