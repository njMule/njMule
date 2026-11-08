// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The default gateways of this computer, where NAT-PMP and PCP are asked, as the system tells them (netdev).

use std::net::{Ipv4Addr, Ipv6Addr};

/// The IPv4 default gateway, if the system names one.
pub fn find() -> Option<Ipv4Addr> {
    netdev::get_default_interface().ok()?.gateway?.ipv4.first().copied()
}

/// The IPv6 default gateway, mostly a link-local address, with the index of the interface it is reached on.
pub fn find_ipv6() -> Option<(Ipv6Addr, u32)> {
    let interface = netdev::get_default_interface().ok()?;
    let gateway = interface.gateway?.ipv6.first().copied()?;
    Some((gateway, interface.index))
}
