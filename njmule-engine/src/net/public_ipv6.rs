// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Our IPv6 address on the Internet: the one the system sends from, which no NAT translates, so that peers can reach it
//! there.

use std::net::{IpAddr, Ipv6Addr, SocketAddr, UdpSocket};

use crate::net::routes::Routes;
use crate::util::ipv6;

/// Set to `true`, no address is found: tests then send the same packets on any network they run on.
pub const NO_IPV6_VARIABLE: &str = "NJMULE_IPV6_DISABLED";

// Any address on the IPv6 Internet does: only the route towards it is looked up, nothing is ever sent there.
const INTERNET: SocketAddr = SocketAddr::new(IpAddr::V6(Ipv6Addr::new(0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8888)), 53);

/// None without a route to the IPv6 Internet, while njMule keeps to an interface of its own, such as a VPN's, or while
/// its connections go through a proxy.
pub fn find(routes: &Routes) -> Option<Ipv6Addr> {
    if std::env::var(NO_IPV6_VARIABLE).is_ok_and(|value| value.eq_ignore_ascii_case("true"))
        || !routes.may_tell_public_ipv6()
    {
        return None;
    }
    let probe = UdpSocket::bind((Ipv6Addr::UNSPECIFIED, 0)).ok()?;
    probe.connect(INTERNET).ok()?;
    match probe.local_addr().ok()?.ip() {
        IpAddr::V6(ours) if ipv6::is_public(&ours) => Some(ours),
        _ => None,
    }
}
