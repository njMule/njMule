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
