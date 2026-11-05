// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Upload and download credits per user hash, `clients.met`: `<0x12><u32 count>` then 119-byte records `<user hash
//! 16><up lo u32><down lo u32><last seen u32><up hi u32><down hi u32><reserved u16><key size u8><public key 80>`.
//! Version 0x11 records have 38 bytes and no key.

use crate::persist::format::met_codecs;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter};

pub const VERSION: u8 = 0x12;
const VERSION_OLD: u8 = 0x11;
const MAX_KEY: usize = 80;
const RECORD: usize = 119;
const RECORD_OLD: usize = 38;
const MAX_CLIENTS: usize = 2_000_000;

/// `public_key` is empty when the peer never identified itself.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Credit {
    pub user_hash: [u8; 16],
    pub uploaded: u64,
    pub downloaded: u64,
    pub last_seen: u32,
    pub public_key: Vec<u8>,
}

pub fn read(data: &[u8]) -> Result<Vec<Credit>, DecodeError> {
    let mut input = PacketReader::of(data);
    let version = met_codecs::header(&mut input, "clients.met", &[VERSION, VERSION_OLD])?;
    let size = if version == VERSION { RECORD } else { RECORD_OLD };
    let declared = input.u32()?;
    let count = input.count(u64::from(declared), size, MAX_CLIENTS)?;
    (0..count)
        .map(|_| {
            let user_hash = input.hash16()?;
            let up_low = input.u32()?;
            let down_low = input.u32()?;
            let last_seen = input.u32()?;
            let up_high = input.u32()?;
            let down_high = input.u32()?;
            input.u16()?;
            let mut public_key = Vec::new();
            if version == VERSION {
                let key_size = usize::from(input.u8()?);
                let key_area = input.bytes(MAX_KEY)?;
                public_key = key_area
                    .get(..key_size)
                    .ok_or_else(|| DecodeError::new(format!("clients.met: public key of {key_size} bytes")))?
                    .to_vec();
            }
            Ok(Credit {
                user_hash,
                uploaded: wide(up_high, up_low),
                downloaded: wide(down_high, down_low),
                last_seen,
                public_key,
            })
        })
        .collect()
}

pub fn write(credits: &[Credit]) -> Result<Vec<u8>, EncodeError> {
    let mut out = PacketWriter::new();
    out.u8(VERSION).count32(credits.len())?;
    for credit in credits {
        let key_size = u8::try_from(credit.public_key.len())
            .ok()
            .filter(|size| usize::from(*size) <= MAX_KEY)
            .ok_or_else(|| EncodeError::new("public keys have at most 80 bytes"))?;
        let mut key = [0; MAX_KEY];
        for (to, from) in key.iter_mut().zip(&credit.public_key) {
            *to = *from;
        }
        let [up_low, up_high] = halves(credit.uploaded);
        let [down_low, down_high] = halves(credit.downloaded);
        out.hash16(&credit.user_hash)
            .u32(up_low)
            .u32(down_low)
            .u32(credit.last_seen)
            .u32(up_high)
            .u32(down_high)
            .u16(0)
            .u8(key_size)
            .bytes(&key);
    }
    Ok(out.into_byte_array())
}

fn wide(high: u32, low: u32) -> u64 {
    u64::from(high) << 32 | u64::from(low)
}

// The low and the high 32 bits.
fn halves(value: u64) -> [u32; 2] {
    let [a, b, c, d, e, f, g, h] = value.to_le_bytes();
    [u32::from_le_bytes([a, b, c, d]), u32::from_le_bytes([e, f, g, h])]
}
