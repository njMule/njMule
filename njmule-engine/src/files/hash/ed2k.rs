// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! eD2K file geometry and hash rules: parts, blocks and MD4 (RFC 1320).

use md4::{Digest, Md4};

use crate::files::hash::HashError;

/// Size of an eD2K part ("chunk"), the unit of MD4 part hashes and of sharing.
pub const PARTSIZE: u64 = 9_728_000;
/// Size of a transfer block and of an AICH leaf.
pub const EMBLOCKSIZE: u64 = 184_320;
/// Largest file the network supports (2^38 bytes = 256 GiB).
pub const MAX_FILE_SIZE: u64 = 1 << 38;
/// Largest file before 64-bit sizes: (2^32 - 1) / PARTSIZE * PARTSIZE.
pub const OLD_MAX_FILE_SIZE: u64 = 4_290_048_000;
/// MD4 of no data: the hash of the empty part that ends files sized a multiple of PARTSIZE.
pub const EMPTY_PART_HASH: [u8; 16] =
    [0x31, 0xD6, 0xCF, 0xE0, 0xD1, 0x6A, 0xE9, 0x31, 0xB7, 0x3C, 0x59, 0xD7, 0xE0, 0xC0, 0x89, 0xC0];

pub fn check_size(size: u64) -> Result<(), HashError> {
    if size > MAX_FILE_SIZE {
        return Err(HashError::new(format!("file size out of range: {size}")));
    }
    Ok(())
}

/// Number of data parts: ⌈size / PARTSIZE⌉, for a size up to [`MAX_FILE_SIZE`].
pub fn part_count(size: u64) -> usize {
    usize::try_from(size.div_ceil(PARTSIZE)).unwrap_or(usize::MAX)
}

/// Number of eD2K parts (part hashes, OP_FILESTATUS bits): `size / PARTSIZE + 1`, which counts an extra empty part
/// when the size is an exact multiple of PARTSIZE.
pub fn ed2k_part_count(size: u64) -> usize {
    if size == 0 { 0 } else { usize::try_from(size / PARTSIZE).map_or(usize::MAX, |n| n.saturating_add(1)) }
}

/// Part hashes in the hash set: none below one part, otherwise [`ed2k_part_count`].
pub fn hash_set_size(size: u64) -> usize {
    if size < PARTSIZE { 0 } else { ed2k_part_count(size) }
}

pub fn part_start(part: usize) -> u64 {
    u64::try_from(part).map_or(u64::MAX, |p| p.saturating_mul(PARTSIZE))
}

/// Bytes in a data part (`part` < [`part_count`]).
pub fn part_length(size: u64, part: usize) -> u64 {
    PARTSIZE.min(size.saturating_sub(part_start(part)))
}

/// The file hash: the only part hash for small files, else MD4 of the part hashes.
pub fn file_hash(part_hashes: &[[u8; 16]]) -> Result<[u8; 16], HashError> {
    match part_hashes {
        [] => Err(HashError::new("no part hashes")),
        [only] => Ok(*only),
        all => {
            let mut md4 = Md4::new();
            for part in all {
                md4.update(part);
            }
            Ok(md4.finalize().into())
        }
    }
}

/// MD4 (RFC 1320), the digest of eD2K part and file hashes.
pub fn md4(data: &[u8]) -> [u8; 16] {
    Md4::digest(data).into()
}
