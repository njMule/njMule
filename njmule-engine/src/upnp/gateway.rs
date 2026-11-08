// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What every router that forwards our ports shares: one found with UPnP, or one that answers NAT-PMP or PCP.

use std::{fmt, io};

/// A transport whose ports a router forwards.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Protocol {
    Tcp,
    Udp,
}

impl Protocol {
    /// The name the protocols write: "TCP" or "UDP".
    pub fn name(self) -> &'static str {
        match self {
            Self::Tcp => "TCP",
            Self::Udp => "UDP",
        }
    }
}

/// Why a router did not do what it was asked.
#[derive(Debug)]
pub enum GatewayError {
    /// The router will not, for this reason.
    Refused(String),
    /// The router could not be asked, or gave no answer that fits.
    Failed(io::Error),
}

impl fmt::Display for GatewayError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Refused(reason) => f.write_str(reason),
            Self::Failed(e) => e.fmt(f),
        }
    }
}

impl std::error::Error for GatewayError {}

impl From<io::Error> for GatewayError {
    fn from(e: io::Error) -> Self {
        Self::Failed(e)
    }
}
