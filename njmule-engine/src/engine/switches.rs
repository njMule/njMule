// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the executable's `-D` switches turn off. Started as a library, as tests start it, the engine reaches no third
//! party on its own.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Switches {
    /// Whether the table of countries is fetched from DB-IP once a month.
    pub countries: bool,
}

/// The switch that keeps the table of countries from being fetched.
pub const COUNTRIES_DISABLED: &str = "njmule.countries.disabled";

impl Switches {
    /// Nothing fetched from a third party on the engine's own.
    pub const QUIET: Self = Self { countries: false };

    /// The executable's: everything on, then off what the switches `NAME=VALUE` turn off. Switches the engine does
    /// not have are left alone.
    pub fn of(switches: &[String]) -> Self {
        let mut on = Self { countries: true };
        for switch in switches {
            let (name, value) = switch.split_once('=').unwrap_or((switch, "true"));
            if name.trim() == COUNTRIES_DISABLED && value.trim().eq_ignore_ascii_case("true") {
                on.countries = false;
            }
        }
        on
    }
}
