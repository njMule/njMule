// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the engine tells its front ends, in the order it happens.

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EngineEvent {
    Started { version: String },
    Stopping,
    PreferencesChanged,
}
