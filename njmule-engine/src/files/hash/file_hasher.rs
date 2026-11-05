// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Computes the eD2K hash set and the AICH tree in one pass over the data, block by block.

use std::fs::File;
use std::io::{self, ErrorKind, Read};
use std::ops::ControlFlow;
use std::path::Path;

use md4::{Digest, Md4};
use sha1::Sha1;

use crate::files::hash::aich_tree::AichTree;
use crate::files::hash::ed2k::{self, EMBLOCKSIZE, EMPTY_PART_HASH, PARTSIZE};
use crate::files::hash::ed2k_hash_set::Ed2kHashSet;

/// The hashes of a file; there is no AICH tree for an empty file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileHashes {
    pub ed2k: Ed2kHashSet,
    pub aich: Option<AichTree>,
}

pub fn hash_file(file: &Path) -> io::Result<FileHashes> {
    let mut input = File::open(file)?;
    let size = input.metadata()?.len();
    hash(&mut input, size, |_| ControlFlow::Continue(()))
}

/// Hashes exactly `size` bytes from `input`; `progress` receives the bytes hashed so far after each block, and breaking
/// cancels the hashing.
pub fn hash(
    input: &mut impl Read,
    size: u64,
    mut progress: impl FnMut(u64) -> ControlFlow<()>,
) -> io::Result<FileHashes> {
    ed2k::check_size(size).map_err(|e| io::Error::new(ErrorKind::InvalidInput, e))?;
    let mut md4 = Md4::new();
    let mut sha1 = Sha1::new();
    let mut block = vec![0u8; usize::try_from(EMBLOCKSIZE).unwrap_or(0)];
    let mut parts = Vec::with_capacity(ed2k::part_count(size));
    let mut blocks = Vec::new();
    let mut done = 0u64;
    for part in 0..ed2k::part_count(size) {
        let mut left = ed2k::part_length(size, part);
        while left > 0 {
            let length = usize::try_from(left.min(EMBLOCKSIZE)).unwrap_or(0);
            let data = block.get_mut(..length).unwrap_or_default();
            input.read_exact(data).map_err(|e| {
                if e.kind() == ErrorKind::UnexpectedEof {
                    io::Error::new(ErrorKind::UnexpectedEof, "data ended before the expected size")
                } else {
                    e
                }
            })?;
            md4.update(&*data);
            sha1.update(&*data);
            blocks.push(sha1.finalize_reset().into());
            let read = u64::try_from(length).unwrap_or(0);
            left = left.saturating_sub(read);
            done = done.saturating_add(read);
            if progress(done).is_break() {
                return Err(io::Error::new(ErrorKind::Interrupted, "hashing cancelled"));
            }
        }
        parts.push(md4.finalize_reset().into());
    }
    let invalid = |e| io::Error::new(ErrorKind::InvalidData, e);
    let ed2k = if size < PARTSIZE {
        let file_hash = parts.first().copied().unwrap_or(EMPTY_PART_HASH);
        Ed2kHashSet::new(size, file_hash, Vec::new())
    } else {
        if size.is_multiple_of(PARTSIZE) {
            parts.push(EMPTY_PART_HASH);
        }
        ed2k::file_hash(&parts).and_then(|file_hash| Ed2kHashSet::new(size, file_hash, parts))
    }
    .map_err(invalid)?;
    let aich = if size == 0 { None } else { Some(AichTree::from_block_hashes(size, blocks).map_err(invalid)?) };
    Ok(FileHashes { ed2k, aich })
}
