// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the executable's `-D` switches turn off. Started as a library, as tests start it, the engine reaches no third
//! party on its own.

use std::net::SocketAddr;
use std::time::Duration;

use crate::engine::default_lists;
use crate::persist::emule_importer;
use crate::update::updates;

#[derive(Debug, Clone, Copy)]
pub struct Switches {
    /// Whether the table of countries is fetched from DB-IP once a month.
    pub countries: bool,
    /// Whether an eMule runs on this computer, which keeps an imported identity waiting; tests tell none runs.
    pub emule_running: fn() -> bool,
    /// Where the router that forwards our ports is looked for, when the preferences turn UPnP on.
    pub upnp: Upnp,
    /// Whether njMule looks for new versions on its own or when asked.
    pub updates: bool,
    /// Whether a new profile starts with the shipped server list and Kad bootstrap contacts.
    pub default_lists: bool,
    /// Whether the engine connects to servers automatically at start and reconnects on drop.
    pub automatic_connections: bool,
    /// Whether the engine probes and announces the computer's public IPv6 address.
    pub ipv6: bool,
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
/// The switch that keeps njMule from looking for new versions.
pub const UPDATE_DISABLED: &str = updates::DISABLED_PROPERTY;
/// The switch that keeps a new profile from loading the shipped server and Kad bootstrap lists.
pub const DEFAULT_LISTS_DISABLED: &str = default_lists::DISABLED_PROPERTY;
/// The switch that keeps the engine from looking up and announcing the computer's public IPv6 address.
pub const IPV6_DISABLED: &str = "njmule.ipv6.disabled";

impl Switches {
    /// Nothing fetched from a third party on the engine's own.
    pub const QUIET: Self = Self {
        countries: false,
        emule_running: emule_importer::is_emule_running,
        upnp: Upnp::Off,
        updates: false,
        default_lists: false,
        automatic_connections: true,
        ipv6: false,
    };

    /// The executable's: everything on, then off what the switches `NAME=VALUE` turn off. Switches the engine does
    /// not have are left alone.
    pub fn of(switches: &[String]) -> Self {
        let mut on = Self {
            countries: true,
            upnp: Upnp::ThisComputer,
            updates: true,
            default_lists: true,
            automatic_connections: true,
            ipv6: true,
            ..Self::QUIET
        };
        for switch in switches {
            let (name, value) = switch.split_once('=').unwrap_or((switch, "true"));
            if !value.trim().eq_ignore_ascii_case("true") {
                continue;
            }
            match name.trim() {
                COUNTRIES_DISABLED => on.countries = false,
                UPNP_DISABLED => on.upnp = Upnp::Off,
                UPDATE_DISABLED => on.updates = false,
                DEFAULT_LISTS_DISABLED => on.default_lists = false,
                IPV6_DISABLED => on.ipv6 = false,
                _ => {}
            }
        }
        on
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn should_turn_ipv6_off_when_the_switch_asks() {
        const { assert!(!Switches::QUIET.ipv6) };
        assert!(Switches::of(&[]).ipv6);
        assert!(!Switches::of(&[format!("{IPV6_DISABLED}=true")]).ipv6);
        assert!(Switches::of(&[format!("{IPV6_DISABLED}=false")]).ipv6);
    }
}
