// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the executable's `-D` switches turn off. Started as a library, as tests start it, the engine reaches no third
//! party on its own.

use std::net::SocketAddr;
use std::time::Duration;

use crate::persist::emule_importer;

#[derive(Debug, Clone, Copy)]
pub struct Switches {
    /// Whether the table of countries is fetched from DB-IP once a month.
    pub countries: bool,
    /// Whether an eMule runs on this computer, which keeps an imported identity waiting; tests tell none runs.
    pub emule_running: fn() -> bool,
    /// Where the router that forwards our ports is looked for, when the preferences turn UPnP on.
    pub upnp: Upnp,
}

/// Where the router is looked for: nowhere, on this computer's networks, or at a UPnP search address of a test, looked
/// for again after the wait given while it does not answer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Upnp {
    Off,
    ThisComputer,
    At(SocketAddr, Duration),
}

/// The switch that keeps the table of countries from being fetched.
pub const COUNTRIES_DISABLED: &str = "njmule.countries.disabled";
/// The switch that keeps the router from being asked to forward our ports.
pub const UPNP_DISABLED: &str = "njmule.upnp.disabled";

impl Switches {
    /// Nothing fetched from a third party on the engine's own.
    pub const QUIET: Self = Self { countries: false, emule_running: emule_importer::is_emule_running, upnp: Upnp::Off };

    /// The executable's: everything on, then off what the switches `NAME=VALUE` turn off. Switches the engine does
    /// not have are left alone.
    pub fn of(switches: &[String]) -> Self {
        let mut on = Self { countries: true, upnp: Upnp::ThisComputer, ..Self::QUIET };
        for switch in switches {
            let (name, value) = switch.split_once('=').unwrap_or((switch, "true"));
            if !value.trim().eq_ignore_ascii_case("true") {
                continue;
            }
            match name.trim() {
                COUNTRIES_DISABLED => on.countries = false,
                UPNP_DISABLED => on.upnp = Upnp::Off,
                _ => {}
            }
        }
        on
    }
}
