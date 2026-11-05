// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The files we share: so far those of the incoming folder, hashed once and remembered in `known.met` by name, size and
//! modification time, so a start does not hash them again.

use std::collections::HashMap;
use std::io;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

use indexmap::IndexMap;

use crate::api::ed2k_link::FileLink;
use crate::files::hash::file_hasher::{self, FileHashes};
use crate::persist::atomic_files;
use crate::persist::format::known_met::{self, KnownFile};
use crate::protocol::tag::{Tag, TagName, file_tag};

pub const KNOWN_FILE_NAME: &str = "known.met";

/// A shared file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SharedFile {
    pub hash: [u8; 16],
    pub name: String,
    pub size: u64,
    pub path: PathBuf,
    pub mtime: u32,
    pub part_hashes: Vec<[u8; 16]>,
    pub aich_hash: Option<[u8; 20]>,
}

impl SharedFile {
    pub fn link(&self) -> FileLink {
        FileLink {
            name: self.name.clone(),
            size: self.size,
            hash: self.hash,
            aich_hash: self.aich_hash,
            part_hashes: Vec::new(),
            sources: Vec::new(),
            http_sources: Vec::new(),
        }
    }

    fn known(&self) -> KnownFile {
        let mut tags = vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: self.name.clone() },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: self.size },
        ];
        if let Some(aich) = self.aich_hash {
            let text = data_encoding::BASE32_NOPAD.encode(&aich);
            tags.push(Tag::String { name: TagName::id(file_tag::AICH_HASH), value: text });
        }
        KnownFile { mtime: self.mtime, file_hash: self.hash, part_hashes: self.part_hashes.clone(), tags }
    }
}

/// A file found in a shared folder: its name, size and modification time, which tell whether it is known.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Found {
    pub path: PathBuf,
    pub name: String,
    pub size: u64,
    pub mtime: u32,
}

#[derive(Debug, Default)]
pub struct SharedFiles {
    files: IndexMap<[u8; 16], SharedFile>,
    // Files hashed before, by name, size and modification time.
    known: HashMap<(String, u64, u32), KnownFile>,
}

impl SharedFiles {
    /// The files hashed before, from `known.met` in `config`.
    pub fn load(config: &Path) -> Self {
        let mut shared = Self::default();
        let file = config.join(KNOWN_FILE_NAME);
        if let Ok(data) = std::fs::read(&file) {
            for known in known_met::salvage(&data) {
                if let (Some(name), Some(size)) = (known.name().map(str::to_owned), known.size())
                    && known.is_consistent()
                {
                    shared.known.insert((name, size, known.mtime), known);
                }
            }
        }
        shared
    }

    pub fn save(&self, config: &Path) -> io::Result<()> {
        let mut files: Vec<KnownFile> = self.files.values().map(SharedFile::known).collect();
        let shared: std::collections::HashSet<[u8; 16]> = self.files.keys().copied().collect();
        files.extend(self.known.values().filter(|known| !shared.contains(&known.file_hash)).cloned());
        let data = known_met::write(&files).map_err(io::Error::other)?;
        atomic_files::write(&config.join(KNOWN_FILE_NAME), &data, false)
    }

    /// Shares a found file that was hashed before; false when it must be hashed first.
    pub fn take_known(&mut self, found: &Found) -> bool {
        let key = (found.name.clone(), found.size, found.mtime);
        let Some(known) = self.known.get(&key) else {
            return false;
        };
        let aich_hash = known.aich_hash();
        self.add(SharedFile {
            hash: known.file_hash,
            name: found.name.clone(),
            size: found.size,
            path: found.path.clone(),
            mtime: found.mtime,
            part_hashes: known.part_hashes.clone(),
            aich_hash,
        });
        true
    }

    /// Shares a file just hashed.
    pub fn hashed(&mut self, found: Found, hashes: &FileHashes) -> SharedFile {
        let file = SharedFile {
            hash: hashes.ed2k.file_hash(),
            name: found.name,
            size: found.size,
            path: found.path,
            mtime: found.mtime,
            part_hashes: hashes.ed2k.part_hashes().to_vec(),
            aich_hash: hashes.aich.as_ref().map(file_hasher_master),
        };
        self.add(file.clone());
        file
    }

    pub fn files(&self) -> impl Iterator<Item = &SharedFile> {
        self.files.values()
    }

    pub fn get(&self, hash: &[u8; 16]) -> Option<&SharedFile> {
        self.files.get(hash)
    }

    fn add(&mut self, file: SharedFile) {
        self.files.insert(file.hash, file);
    }
}

/// The regular files of `folder`, without going into its folders.
pub fn scan(folder: &Path) -> Vec<Found> {
    let Ok(entries) = std::fs::read_dir(folder) else {
        return Vec::new();
    };
    let mut found: Vec<Found> = entries
        .filter_map(Result::ok)
        .filter_map(|entry| {
            let metadata = entry.metadata().ok().filter(std::fs::Metadata::is_file)?;
            let mtime = metadata
                .modified()
                .ok()
                .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
                .and_then(|since| u32::try_from(since.as_secs()).ok())
                .unwrap_or(0);
            let name = entry.file_name().to_str()?.to_owned();
            Some(Found { path: entry.path(), name, size: metadata.len(), mtime })
        })
        .filter(|found| found.size > 0)
        .collect();
    found.sort_by(|a, b| a.name.cmp(&b.name));
    found
}

/// Hashes a found file, off the engine's task.
pub fn hash(found: &Found) -> io::Result<FileHashes> {
    file_hasher::hash_file(&found.path)
}

fn file_hasher_master(tree: &crate::files::hash::aich_tree::AichTree) -> [u8; 20] {
    tree.master_hash()
}
