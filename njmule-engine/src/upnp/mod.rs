// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Our ports forwarded by the router: found with UPnP, or else with NAT-PMP or PCP.

pub mod default_gateway;
pub mod gateway;
pub mod igd;
pub mod nat_pmp;
pub mod port_forwarding;
