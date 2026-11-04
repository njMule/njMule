// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Hello, login and extended-info tag ids.

pub const NAME: u8 = 0x01;
pub const PORT: u8 = 0x0F;
pub const VERSION: u8 = 0x11;
pub const SERVER_FLAGS: u8 = 0x20;
pub const MOD_VERSION: u8 = 0x55;
/// Feature bits of newer clients, u32; bit 0 extended source exchange, bit 2 IPv6.
pub const MOD_MISCOPTIONS: u8 = 0xAA;
/// The address the sender sees the receiver at: u32 for IPv4, a hash tag of 16 bytes for IPv6.
pub const MOD_YOUR_IP: u8 = 0xAD;
/// The sender's public IPv6 address, a hash tag of 16 bytes.
pub const MOD_IP_V6: u8 = 0xAE;
/// In an extended source exchange: the server a source is on, its address (eD2K order) and TCP port.
pub const EMULE_SERVERIP: u8 = 0xBA;

pub const EMULE_SERVERTCP: u8 = 0xBB;

pub const EMULECOMPAT_OPTIONS1: u8 = 0xEF;
pub const EMULE_UDPPORTS: u8 = 0xF9;
pub const EMULE_MISCOPTIONS1: u8 = 0xFA;
pub const EMULE_VERSION: u8 = 0xFB;
pub const EMULE_BUDDYIP: u8 = 0xFC;
pub const EMULE_BUDDYUDP: u8 = 0xFD;
pub const EMULE_MISCOPTIONS2: u8 = 0xFE;
pub const SERVER_UDPSEARCH_FLAGS: u8 = 0x0E;

pub const ET_COMPRESSION: u8 = 0x20;
pub const ET_UDPPORT: u8 = 0x21;
pub const ET_UDPVER: u8 = 0x22;
pub const ET_SOURCEEXCHANGE: u8 = 0x23;
pub const ET_COMMENTS: u8 = 0x24;
pub const ET_EXTENDEDREQUEST: u8 = 0x25;
pub const ET_COMPATIBLECLIENT: u8 = 0x26;
pub const ET_FEATURES: u8 = 0x27;
