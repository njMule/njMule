// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! `preferences.dat`, 61 bytes: `<version 0x14><user hash 16><main window placement 44>`. Only the user hash matters to
//! njMule; the window placement is kept as opaque bytes.

use crate::protocol::io::{DecodeError, PacketReader, PacketWriter};

pub const VERSION: u8 = 0x14;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Contents {
    pub version: u8,
    pub user_hash: [u8; 16],
    pub window_placement: Vec<u8>,
}

pub fn read(data: &[u8]) -> Result<Contents, DecodeError> {
    let mut input = PacketReader::of(data);
    let version = input.u8()?;
    let user_hash = input.hash16()?;
    Ok(Contents { version, user_hash, window_placement: input.rest().to_vec() })
}

pub fn write(contents: &Contents) -> Vec<u8> {
    let mut out = PacketWriter::new();
    out.u8(contents.version).hash16(&contents.user_hash).bytes(&contents.window_placement);
    out.into_byte_array()
}

/// Whether the hash carries the client marker bytes (`[5] = 14`, `[14] = 111`) set by the clients that generate user
/// hashes this way.
pub fn is_marked_user_hash(hash: &[u8; 16]) -> bool {
    hash[5] == 14 && hash[14] == 111
}
