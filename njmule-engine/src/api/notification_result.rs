// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! How a test message went through one notification channel.

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NotificationResult {
    /// `email` or `webhook`.
    pub channel: String,
    /// Why the message was not sent; none when it was.
    pub error: Option<String>,
}
