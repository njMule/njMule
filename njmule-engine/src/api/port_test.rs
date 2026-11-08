// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A test of our ports as it stands: whether the router forwards them, and whether others reach them.

use strum::IntoStaticStr;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PortTest {
    pub tcp_port: u16,
    /// 0 when Kad does not run.
    pub udp_port: u16,
    pub forwarding: Forwarding,
    /// The host of the router that answered; empty when none did.
    pub router: String,
    pub tcp: Reach,
    pub udp: Reach,
    pub done: bool,
}

/// Whether the router forwards our ports, as asked through UPnP.
#[derive(Debug, Clone, Copy, PartialEq, Eq, IntoStaticStr)]
#[strum(serialize_all = "snake_case")]
pub enum Forwarding {
    /// UPnP is off.
    Off,
    /// The router is being asked.
    Asking,
    Forwarded,
    /// Some ports are forwarded, the router refused the others.
    Partly,
    Refused,
    NoRouter,
    /// The router stopped answering, or answered what cannot be read.
    Failed,
}

impl Forwarding {
    pub fn name(self) -> &'static str {
        self.into()
    }
}

/// Whether others reach one of our ports.
#[derive(Debug, Clone, Copy, PartialEq, Eq, IntoStaticStr)]
#[strum(serialize_all = "snake_case")]
pub enum Reach {
    Testing,
    Open,
    Closed,
    /// Nothing could tell: no server took us and no Kad node tested it in time.
    Unknown,
}

impl Reach {
    pub fn name(self) -> &'static str {
        self.into()
    }
}
