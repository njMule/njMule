// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A Kad id: 128 bits, on the wire as four little-endian u32 words, the most significant first.

use crate::protocol::io::{DecodeError, PacketReader, PacketWriter};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Default)]
pub struct UInt128(pub u128);

impl UInt128 {
    pub const ZERO: Self = Self(0);
    pub const BITS: u32 = 128;

    pub fn read(input: &mut PacketReader<'_>) -> Result<Self, DecodeError> {
        let mut value = 0u128;
        for _ in 0..4 {
            value = value << 32 | u128::from(input.u32()?);
        }
        Ok(Self(value))
    }

    pub fn write(&self, out: &mut PacketWriter) {
        for shift in [96, 64, 32, 0] {
            out.u32(u32::try_from(self.0 >> shift & u128::from(u32::MAX)).unwrap_or(u32::MAX));
        }
    }

    pub fn from_big_endian(bytes: [u8; 16]) -> Self {
        Self(u128::from_be_bytes(bytes))
    }

    pub fn to_big_endian(&self) -> [u8; 16] {
        self.0.to_be_bytes()
    }
}
