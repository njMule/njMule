// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The downloads cancelled before, in `cancelled.met`: each file under a key made with a seed of its own, so that the
//! list tells whether a file was cancelled but not which files were.

use std::collections::HashSet;
use std::io::{self, ErrorKind};
use std::path::{Path, PathBuf};

use indexmap::IndexSet;
use rand::{Rng, RngExt};

use crate::persist::atomic_files;
use crate::persist::format::cancelled_met;

pub const FILE_NAME: &str = "cancelled.met";

pub struct CancelledFiles {
    file: PathBuf,
    seed: u32,
    keys: IndexSet<[u8; 16]>,
}

/// Immutable point-in-time view of cancelled files.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Snapshot {
    seed: u32,
    keys: HashSet<[u8; 16]>,
}

impl Snapshot {
    pub fn contains(&self, file_hash: &[u8; 16]) -> bool {
        self.seed != 0 && self.keys.contains(&cancelled_met::key(self.seed, file_hash))
    }

    pub fn is_empty(&self) -> bool {
        self.keys.is_empty()
    }
}

impl CancelledFiles {
    /// An empty list, saved to `file` when a download is cancelled.
    pub fn empty(file: PathBuf) -> Self {
        Self { file, seed: 0, keys: IndexSet::new() }
    }

    /// Reads `file`, or its backup when it is missing or damaged; none is an empty list.
    pub fn load(file: &Path, random: &mut impl Rng) -> io::Result<Self> {
        let mut cancelled = Self::empty(file.to_path_buf());
        atomic_files::cleanup_interrupted_write(file);
        let contents = match atomic_files::read(file, |data| {
            cancelled_met::read(data)
                .map_err(|e| io::Error::new(ErrorKind::InvalidData, format!("{}: {e}", file.display())))
        }) {
            Ok(contents) => contents,
            Err(e) if e.kind() == ErrorKind::NotFound => return Ok(cancelled),
            Err(e) => return Err(e),
        };
        if contents.legacy {
            // The old format holds the file hashes themselves: they get keys.
            let seed = new_seed(random);
            cancelled.seed = seed;
            for hash in contents.keys {
                cancelled.keys.insert(cancelled_met::key(seed, &hash));
            }
        } else {
            cancelled.seed = contents.seed;
            cancelled.keys.extend(contents.keys);
        }
        Ok(cancelled)
    }

    pub fn contains(&self, file_hash: &[u8; 16]) -> bool {
        self.seed != 0 && self.keys.contains(&cancelled_met::key(self.seed, file_hash))
    }

    /// Remembers that the download of `file_hash` was cancelled.
    pub fn add(&mut self, file_hash: &[u8; 16], random: &mut impl Rng) -> io::Result<()> {
        if self.seed == 0 {
            self.seed = new_seed(random);
        }
        if self.keys.insert(cancelled_met::key(self.seed, file_hash)) {
            self.save()?;
        }
        Ok(())
    }

    /// Forgets that the download of `file_hash` was cancelled, as when the cancel is undone.
    pub fn remove(&mut self, file_hash: &[u8; 16]) -> io::Result<()> {
        if self.seed != 0 && self.keys.shift_remove(&cancelled_met::key(self.seed, file_hash)) {
            self.save()?;
        }
        Ok(())
    }

    /// Forgets every cancelled download.
    pub fn clear(&mut self) -> io::Result<()> {
        if !self.keys.is_empty() {
            self.keys.clear();
            self.save()?;
        }
        Ok(())
    }

    pub fn size(&self) -> usize {
        self.keys.len()
    }

    pub fn is_empty(&self) -> bool {
        self.keys.is_empty()
    }

    /// Whether a file was cancelled, as the list is now.
    pub fn snapshot(&self) -> Snapshot {
        Snapshot { seed: self.seed, keys: self.keys.iter().copied().collect() }
    }

    fn save(&self) -> io::Result<()> {
        let keys: Vec<[u8; 16]> = self.keys.iter().copied().collect();
        let data = cancelled_met::write(self.seed, &keys).map_err(io::Error::other)?;
        atomic_files::write(&self.file, &data)
    }
}

// Never 0, which means "no seed yet".
fn new_seed(random: &mut impl Rng) -> u32 {
    loop {
        let s: u32 = random.random();
        if s != 0 {
            return s;
        }
    }
}
