// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! IPv6 addresses as the peers who hold them.

use std::net::{IpAddr, Ipv6Addr};

/// Whether peers on the Internet may reach `address`: global unicast IPv6, but no 6to4 or Teredo address, which need
/// relays.
pub fn is_public(address: &Ipv6Addr) -> bool {
    let [first, second, ..] = address.segments();
    let global = first & 0xE000 == 0x2000;
    let six_to_four = first == 0x2002;
    let teredo = first == 0x2001 && second == 0;
    global && !six_to_four && !teredo
}

/// The 16 bytes as an IPv6 address, if peers on the Internet may reach it.
pub fn public_of(bytes: &[u8]) -> Option<Ipv6Addr> {
    let octets: [u8; 16] = bytes.try_into().ok()?;
    Some(Ipv6Addr::from(octets)).filter(is_public)
}

/// The /64 network of an IPv6 address, which one host usually holds whole; an IPv4 address as it is.
pub fn network(address: IpAddr) -> IpAddr {
    match address {
        IpAddr::V6(v6) => {
            let [a, b, c, d, ..] = v6.segments();
            IpAddr::V6(Ipv6Addr::new(a, b, c, d, 0, 0, 0, 0))
        }
        v4 => v4,
    }
}
