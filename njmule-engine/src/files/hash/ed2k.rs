// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! eD2K's division of files into parts and blocks.

/// Size of an eD2K part, which one MD4 hash covers.
pub const PARTSIZE: u64 = 9_728_000;
/// Size of a transfer block and of an AICH leaf.
pub const EMBLOCKSIZE: u64 = 184_320;
/// Largest file the network supports (2^38 bytes = 256 GiB).
pub const MAX_FILE_SIZE: u64 = 1 << 38;
/// Largest file before 64-bit sizes: (2^32 - 1) / PARTSIZE * PARTSIZE.
pub const OLD_MAX_FILE_SIZE: u64 = 4_290_048_000;

/// Number of data parts: ⌈size / PARTSIZE⌉.
pub fn part_count(size: u64) -> usize {
    usize::try_from(size.div_ceil(PARTSIZE)).unwrap_or(usize::MAX)
}
