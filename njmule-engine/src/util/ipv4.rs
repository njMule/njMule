// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! IPv4 addresses as `u32`s holding `a.b.c.d` as `a<<24 | b<<16 | c<<8 | d`, as [`std::net::Ipv4Addr`] converts
//! them. Kad files store this value little-endian; eD2K fields carry the four bytes in network order, which read as a
//! little-endian u32 give the byte-swapped value ([`from_ed2k`]).

/// The address from an eD2K field read as a little-endian u32.
pub const fn from_ed2k(little_endian_value: u32) -> u32 {
    little_endian_value.swap_bytes()
}

/// The little-endian u32 to write for an eD2K field.
pub const fn to_ed2k(ip: u32) -> u32 {
    ip.swap_bytes()
}

/// The address `a.b.c.d` as a number, `a` in its high byte.
pub const fn of(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from_be_bytes([a, b, c, d])
}

/// A private address (RFC 1918) or one of the "this network" block, 0.x.
pub fn is_lan(ip: u32) -> bool {
    let [a, b, ..] = ip.to_be_bytes();
    a == 0 || a == 10 || (a == 172 && (16..32).contains(&b)) || (a == 192 && b == 168)
}

/// Usable as a peer address: not 0.x, loopback, multicast, broadcast or reserved.
pub fn is_unicast_public_candidate(ip: u32) -> bool {
    let [a, ..] = ip.to_be_bytes();
    a != 0 && a != 127 && a < 224
}

/// The socket address of `ip`, made without a round trip through its text.
pub fn socket_address(ip: u32, port: u16) -> std::net::SocketAddr {
    std::net::SocketAddr::from((std::net::Ipv4Addr::from(ip), port))
}
