// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Little-endian readers and writers of packets, and the network's strings.

pub mod ed2k_strings;

use std::fmt;

/// Malformed, truncated or out-of-limits data received from the network or read from a file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodeError {
    message: String,
}

impl DecodeError {
    pub fn new(message: impl Into<String>) -> Self {
        Self { message: message.into() }
    }
}

impl fmt::Display for DecodeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for DecodeError {}

/// What cannot be written as asked: a value the encoding has no room or no type for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncodeError {
    message: String,
}

impl EncodeError {
    pub fn new(message: impl Into<String>) -> Self {
        Self { message: message.into() }
    }
}

impl fmt::Display for EncodeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for EncodeError {}

/// How strings are written; reading always auto-detects.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StringEncoding {
    /// Plain UTF-8: Kad and peers that announce Unicode support.
    Utf8,
    /// ASCII as is, anything else as UTF-8 with a BOM: .met files.
    Utf8BomIfNeeded,
}

/// Little-endian reader over bytes in memory. Every read is bounds-checked first, so a length field can never make it
/// take more than the data actually received.
#[derive(Debug, Clone)]
pub struct PacketReader<'a> {
    data: &'a [u8],
    position: usize,
}

impl<'a> PacketReader<'a> {
    pub fn of(data: &'a [u8]) -> Self {
        Self { data, position: 0 }
    }

    pub fn position(&self) -> usize {
        self.position
    }

    pub fn remaining(&self) -> usize {
        self.data.len().saturating_sub(self.position)
    }

    pub fn has_remaining(&self) -> bool {
        self.remaining() > 0
    }

    pub fn u8(&mut self) -> Result<u8, DecodeError> {
        let [value] = self.array::<1>()?;
        Ok(value)
    }

    pub fn u16(&mut self) -> Result<u16, DecodeError> {
        Ok(u16::from_le_bytes(self.array()?))
    }

    pub fn u32(&mut self) -> Result<u32, DecodeError> {
        Ok(u32::from_le_bytes(self.array()?))
    }

    /// The raw 32 bits, for values compared as bit patterns (magic numbers, IP addresses).
    pub fn i32(&mut self) -> Result<i32, DecodeError> {
        Ok(i32::from_le_bytes(self.array()?))
    }

    pub fn u64(&mut self) -> Result<u64, DecodeError> {
        Ok(u64::from_le_bytes(self.array()?))
    }

    /// The float of the 32 bits read, every bit kept (a NaN's payload too).
    pub fn f32(&mut self) -> Result<f32, DecodeError> {
        Ok(f32::from_bits(self.u32()?))
    }

    pub fn bytes(&mut self, length: usize) -> Result<&'a [u8], DecodeError> {
        let end = self
            .position
            .checked_add(length)
            .filter(|end| *end <= self.data.len())
            .ok_or_else(|| self.truncated(length))?;
        let bytes = self.data.get(self.position..end).ok_or_else(|| self.truncated(length))?;
        self.position = end;
        Ok(bytes)
    }

    pub fn hash16(&mut self) -> Result<[u8; 16], DecodeError> {
        self.array()
    }

    pub fn skip(&mut self, length: usize) -> Result<(), DecodeError> {
        self.bytes(length).map(|_| ())
    }

    /// A reader over the next `length` bytes, which this reader then skips.
    pub fn slice(&mut self, length: usize) -> Result<PacketReader<'a>, DecodeError> {
        self.bytes(length).map(PacketReader::of)
    }

    pub fn rest(&mut self) -> &'a [u8] {
        let rest = self.data.get(self.position..).unwrap_or_default();
        self.position = self.data.len();
        rest
    }

    /// A `<u16 length><bytes>` string decoded with the network's rules ([`ed2k_strings`]).
    pub fn string16(&mut self) -> Result<String, DecodeError> {
        let length = self.u16()?;
        Ok(ed2k_strings::decode(self.bytes(usize::from(length))?))
    }

    /// Checks that a count field is plausible for the bytes left (each item at least `min_item_size`).
    pub fn count(&self, count: u64, min_item_size: usize, max_count: usize) -> Result<usize, DecodeError> {
        let n = usize::try_from(count)
            .ok()
            .filter(|n| *n <= max_count)
            .ok_or_else(|| DecodeError::new(format!("count {count} exceeds the limit of {max_count}")))?;
        if let Some(fits) = self.remaining().checked_div(min_item_size)
            && n > fits
        {
            return Err(DecodeError::new(format!("count {count} does not fit in {} bytes", self.remaining())));
        }
        Ok(n)
    }

    pub fn expect_end(&self) -> Result<(), DecodeError> {
        if self.has_remaining() {
            return Err(DecodeError::new(format!("{} unexpected trailing bytes", self.remaining())));
        }
        Ok(())
    }

    fn array<const N: usize>(&mut self) -> Result<[u8; N], DecodeError> {
        let bytes = self.bytes(N)?;
        bytes.try_into().map_err(|_| self.truncated(N))
    }

    fn truncated(&self, length: usize) -> DecodeError {
        DecodeError::new(format!(
            "truncated data: need {length} bytes at offset {}, {} left",
            self.position,
            self.remaining()
        ))
    }
}

/// Little-endian writer into a growing buffer.
#[derive(Debug, Clone, Default)]
pub struct PacketWriter {
    data: Vec<u8>,
}

impl PacketWriter {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn size(&self) -> usize {
        self.data.len()
    }

    pub fn u8(&mut self, value: u8) -> &mut Self {
        self.data.push(value);
        self
    }

    pub fn u16(&mut self, value: u16) -> &mut Self {
        self.bytes(&value.to_le_bytes())
    }

    pub fn u32(&mut self, value: u32) -> &mut Self {
        self.bytes(&value.to_le_bytes())
    }

    pub fn u64(&mut self, value: u64) -> &mut Self {
        self.bytes(&value.to_le_bytes())
    }

    /// The float's 32 bits as they are (a NaN's payload too).
    pub fn f32(&mut self, value: f32) -> &mut Self {
        self.u32(value.to_bits())
    }

    pub fn bytes(&mut self, value: &[u8]) -> &mut Self {
        self.data.extend_from_slice(value);
        self
    }

    pub fn hash16(&mut self, value: &[u8; 16]) -> &mut Self {
        self.bytes(value)
    }

    /// A `<u16 length><bytes>` string.
    pub fn string16(&mut self, value: &str, encoding: StringEncoding) -> Result<&mut Self, EncodeError> {
        let raw = ed2k_strings::encode(value, encoding);
        let length =
            u16::try_from(raw.len()).map_err(|_| EncodeError::new(format!("string too long: {} bytes", raw.len())))?;
        Ok(self.u16(length).bytes(&raw))
    }

    /// A file offset in the 32-bit form; an offset at 4 GiB or beyond needs the 64-bit one.
    pub fn offset32(&mut self, offset: u64) -> Result<&mut Self, EncodeError> {
        let narrow =
            u32::try_from(offset).map_err(|_| EncodeError::new(format!("offset {offset} needs the 64-bit form")))?;
        Ok(self.u32(narrow))
    }

    /// A u8 item count; a list too long for the field is the caller's error, never silently cut.
    pub fn count8(&mut self, count: usize) -> Result<&mut Self, EncodeError> {
        Ok(self.u8(narrowed(count)?))
    }

    /// A u16 item count.
    pub fn count16(&mut self, count: usize) -> Result<&mut Self, EncodeError> {
        Ok(self.u16(narrowed(count)?))
    }

    /// A u32 item count.
    pub fn count32(&mut self, count: usize) -> Result<&mut Self, EncodeError> {
        Ok(self.u32(narrowed(count)?))
    }

    pub fn to_byte_array(&self) -> Vec<u8> {
        self.data.clone()
    }

    pub fn into_byte_array(self) -> Vec<u8> {
        self.data
    }
}

fn narrowed<T: TryFrom<usize>>(count: usize) -> Result<T, EncodeError> {
    T::try_from(count).map_err(|_| EncodeError::new(format!("{count} items do not fit in the count field")))
}
