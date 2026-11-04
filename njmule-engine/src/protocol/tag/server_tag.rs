// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! server.met tag ids; `users` and `files` are string names.

pub const SERVERNAME: u8 = 0x01;
pub const DESCRIPTION: u8 = 0x0B;
pub const PING: u8 = 0x0C;
pub const FAIL: u8 = 0x0D;
pub const PREFERENCE: u8 = 0x0E;
pub const PORT: u8 = 0x0F;
pub const IP: u8 = 0x10;
pub const DYNIP: u8 = 0x85;
pub const MAXUSERS: u8 = 0x87;
pub const SOFTFILES: u8 = 0x88;
pub const HARDFILES: u8 = 0x89;
pub const LASTPING: u8 = 0x90;
pub const VERSION: u8 = 0x91;
pub const UDPFLAGS: u8 = 0x92;
pub const AUXPORTSLIST: u8 = 0x93;
pub const LOWIDUSERS: u8 = 0x94;
pub const UDPKEY: u8 = 0x95;
pub const UDPKEYIP: u8 = 0x96;
pub const TCPPORTOBFUSCATION: u8 = 0x97;
pub const UDPPORTOBFUSCATION: u8 = 0x98;

pub const USERS: &[u8] = b"users";
pub const FILES: &[u8] = b"files";
