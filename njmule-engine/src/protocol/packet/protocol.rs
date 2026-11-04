// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The protocol byte that starts every eD2K and Kad packet.

pub const EDONKEY: u8 = 0xE3;
pub const EMULE: u8 = 0xC5;
/// zlib-packed [`EMULE`] packet.
pub const PACKED: u8 = 0xD4;
pub const KAD: u8 = 0xE4;
/// zlib-packed [`KAD`] packet.
pub const KAD_PACKED: u8 = 0xE5;
/// Reserved so that obfuscated UDP packets can be told apart from plain ones.
pub const UDP_RESERVED_1: u8 = 0xA3;
pub const UDP_RESERVED_2: u8 = 0xB2;

/// The protocols that may start a plain TCP stream.
pub const fn is_tcp(protocol: u8) -> bool {
    matches!(protocol, EDONKEY | EMULE | PACKED)
}

pub const fn is_packed(protocol: u8) -> bool {
    matches!(protocol, PACKED | KAD_PACKED)
}
