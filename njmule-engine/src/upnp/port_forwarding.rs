// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The forwarding of our ports by the router, asked through UPnP.

/// What the router made of the mappings asked.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    /// Every port is forwarded.
    Forwarded,
    /// Some ports are forwarded, the router refused the others.
    Partly,
    /// The router refused every port.
    Refused,
    /// No router answered.
    NoRouter,
    /// The router stopped answering, or answered what cannot be read.
    Failed,
}
