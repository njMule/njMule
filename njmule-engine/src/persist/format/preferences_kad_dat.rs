// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! `preferencesKad.dat`, 23 bytes: `<last IP u32><u16 0><Kad id 16><u8 0>`. A zero id is invalid (old versions stored
//! it) and must be replaced by a random one.

use crate::kad::uint128::UInt128;
use crate::protocol::io::{DecodeError, PacketReader, PacketWriter};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Contents {
    pub last_ip: u32,
    pub kad_id: UInt128,
}

pub fn read(data: &[u8]) -> Result<Contents, DecodeError> {
    let mut input = PacketReader::of(data);
    let last_ip = input.u32()?;
    input.u16()?;
    Ok(Contents { last_ip, kad_id: UInt128::read(&mut input)? })
}

pub fn write(contents: &Contents) -> Vec<u8> {
    let mut out = PacketWriter::new();
    out.u32(contents.last_ip).u16(0);
    contents.kad_id.write(&mut out);
    out.u8(0);
    out.into_byte_array()
}
