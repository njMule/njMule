// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What was typed in the boxes of the eMule whose profile was imported, the newest first, for the interfaces to offer
//! again.

/// The searches typed and the addresses of the server lists typed; none when nothing was typed or no profile imported.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct EmuleHistory {
    pub searches: Vec<String>,
    pub server_lists: Vec<String>,
}
