// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The version numbers njMule announces to servers and peers.

/// eDonkey protocol version announced by every current client.
pub const EDONKEY: u32 = 0x3C;
/// Compatible-client id of njMule in the version tag ('n'); the known clients use other ids.
pub const NJMULE_COMPAT: u8 = 0x6E;

/// The version tag value: `compat<<24 | major<<17 | minor<<10 | patch<<7`.
pub fn encode(compat: u8, major: i32, minor: i32, patch: i32) -> u32 {
    let part = |value: i32, max: i32| value.clamp(0, max).unsigned_abs();
    u32::from(compat) << 24 | part(major, 0x7F) << 17 | part(minor, 0x7F) << 10 | part(patch, 0x7) << 7
}

/// njMule's own version tag, from its semantic version.
pub fn nj_mule() -> u32 {
    from_sem_ver(crate::api::nj_mule_info::version())
}

pub fn from_sem_ver(version: &str) -> u32 {
    let mut parts = version.splitn(4, ['.', '-', '+']).map(|part| part.parse::<i32>().map_or(0, |n| n.max(0)));
    let mut next = || parts.next().unwrap_or(0);
    let (major, minor, patch) = (next(), next(), next());
    encode(NJMULE_COMPAT, major, minor, patch)
}
