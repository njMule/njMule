// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Cancelled downloads, `cancelled.met`: `<0x0F><version 1><seed u32><u32 count>` then `<key 16><u8 tag count><tags>`
//! per file, where the key is `MD5(<seed LE><file hash>)`, so that the list does not reveal which files were
//! cancelled. The old 0x0E format stored the plain file hashes and has no version or seed.

use md5::{Digest, Md5};

use crate::persist::format::met_codecs;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter};
use crate::protocol::tag::tag_codec;

const HEADER: u8 = 0x0F;
const OLD_HEADER: u8 = 0x0E;
const VERSION: u8 = 0x01;
const MAX_ENTRIES: usize = 1_000_000;

/// `legacy`: the keys are plain file hashes (old format) and must be re-keyed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Contents {
    pub seed: u32,
    pub legacy: bool,
    pub keys: Vec<[u8; 16]>,
}

pub fn read(data: &[u8]) -> Result<Contents, DecodeError> {
    let mut input = PacketReader::of(data);
    let header = met_codecs::header(&mut input, "cancelled.met", &[HEADER, OLD_HEADER])?;
    let mut seed = 0;
    if header == HEADER {
        let version = input.u8()?;
        if version > VERSION {
            return Err(DecodeError::new(format!("unsupported cancelled.met version {version}")));
        }
        seed = input.u32()?;
    }
    let declared = input.u32()?;
    let count = input.count(u64::from(declared), 17, MAX_ENTRIES)?;
    let keys = (0..count)
        .map(|_| {
            let key = input.hash16()?;
            let tags = input.u8()?;
            tag_codec::read_ed2k_list(&mut input, u64::from(tags), 0xFF)?;
            Ok(key)
        })
        .collect::<Result<_, DecodeError>>()?;
    Ok(Contents { seed, legacy: header == OLD_HEADER, keys })
}

pub fn write(seed: u32, keys: &[[u8; 16]]) -> Result<Vec<u8>, EncodeError> {
    let mut out = PacketWriter::new();
    out.u8(HEADER).u8(VERSION).u32(seed).count32(keys.len())?;
    for key in keys {
        out.hash16(key).u8(0);
    }
    Ok(out.into_byte_array())
}

/// The key of a file hash under `seed`.
pub fn key(seed: u32, file_hash: &[u8; 16]) -> [u8; 16] {
    let mut md5 = Md5::new();
    md5.update(seed.to_le_bytes());
    md5.update(file_hash);
    md5.finalize().into()
}
