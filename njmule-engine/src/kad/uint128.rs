// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A Kad id: 128 bits, on the wire as four little-endian u32 words, the most significant first.

use std::fmt;

use rand::{Rng, RngExt};

use crate::protocol::io::{DecodeError, PacketReader, PacketWriter};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Default)]
pub struct UInt128(pub u128);

impl UInt128 {
    pub const ZERO: Self = Self(0);
    pub const BITS: u32 = 128;

    pub fn random(random: &mut impl Rng) -> Self {
        Self(random.random())
    }

    pub fn read(input: &mut PacketReader<'_>) -> Result<Self, DecodeError> {
        let mut value = 0u128;
        for _ in 0..4 {
            value = value << 32 | u128::from(input.u32()?);
        }
        Ok(Self(value))
    }

    pub fn write(&self, out: &mut PacketWriter) {
        for shift in [96, 64, 32, 0] {
            out.u32(self.word(shift));
        }
    }

    /// The wire layout as 16 bytes, as obfuscation keys take it.
    pub fn to_wire_bytes(&self) -> [u8; 16] {
        let mut out = [0u8; 16];
        for (chunk, shift) in out.as_chunks_mut::<4>().0.iter_mut().zip([96, 64, 32, 0]) {
            *chunk = self.word(shift).to_le_bytes();
        }
        out
    }

    pub fn from_wire_bytes(bytes: &[u8]) -> Result<Self, DecodeError> {
        if bytes.len() != 16 {
            return Err(DecodeError::new("a 128-bit id has 16 bytes"));
        }
        Self::read(&mut PacketReader::of(bytes))
    }

    /// The key of a hash: its 16 bytes read as a big-endian number.
    pub fn from_big_endian(bytes: [u8; 16]) -> Self {
        Self(u128::from_be_bytes(bytes))
    }

    pub fn to_big_endian(&self) -> [u8; 16] {
        self.0.to_be_bytes()
    }

    /// Bit `index`, 0 the most significant and 127 the least; 0 past them.
    pub fn bit(&self, index: u32) -> u8 {
        u8::from(self.0.checked_shl(index).is_some_and(|shifted| shifted >> 127 == 1))
    }

    /// The most significant 32 bits, which the search tolerance compares.
    pub fn top32(&self) -> u32 {
        self.word(96)
    }

    pub fn is_zero(&self) -> bool {
        self.0 == 0
    }

    /// 32 hex digits in upper case, the most significant first.
    pub fn to_hex(&self) -> String {
        format!("{:032X}", self.0)
    }

    /// The id of 32 hex digits, none for anything else (a sign included).
    pub fn parse_hex(hex: &str) -> Option<Self> {
        if hex.len() != 32 || !hex.bytes().all(|digit| digit.is_ascii_hexdigit()) {
            return None;
        }
        u128::from_str_radix(hex, 16).ok().map(Self)
    }

    fn word(&self, shift: u32) -> u32 {
        u32::try_from(self.0 >> shift & u128::from(u32::MAX)).unwrap_or(u32::MAX)
    }
}

/// The distance of two ids in the Kad metric.
impl std::ops::BitXor for UInt128 {
    type Output = Self;

    fn bitxor(self, other: Self) -> Self {
        Self(self.0 ^ other.0)
    }
}

impl fmt::Display for UInt128 {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.to_hex())
    }
}
