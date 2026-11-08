// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The complete files we share: those of the incoming folders and of the shared folders, alone or with their
//! subfolders. Hashes are remembered in `known.met` by name, size and modification time, so unchanged files are not
//! hashed again. The disk is read off the engine's task ([`list`], [`hash`]); this state only decides what is shared.

use std::collections::{HashMap, HashSet};
use std::fmt;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use indexmap::{IndexMap, IndexSet};
use walkdir::WalkDir;

use crate::api::ed2k_link::FileLink;
use crate::api::shared_directory::{self, SharedDirectory};
use crate::api::shared_file_info::Statistics;
use crate::api::upload_priority::UploadPriority;
use crate::files::hash::aich_tree::AichTree;
use crate::files::hash::file_hasher::{self, FileHashes};
use crate::files::hash::{aich_recovery, ed2k};
use crate::files::part_file;
use crate::persist::atomic_files;
use crate::persist::format::known_met::{self, KnownFile};
use crate::persist::format::known2_met;
use crate::persist::format::text_files;
use crate::protocol::tag::{Tag, TagName, file_tag, tags};
use crate::security::path_sanitizer;

pub const KNOWN_FILE_NAME: &str = "known.met";
/// The AICH trees of the known files, by master hash.
pub const KNOWN2_FILE_NAME: &str = "known2_64.met";
/// The folders shared alone, besides the incoming ones, one a line as other clients write them.
pub const SHARED_DIRS: &str = "shareddir.dat";
/// The folders shared with their subfolders; other clients know only [`SHARED_DIRS`].
pub const SHARED_TREES: &str = "sharedtrees.dat";
/// A client's private key: a folder holding one is a client's configuration.
pub const CLIENT_KEY: &str = "cryptkey.dat";
/// The folders are looked at again this often.
pub const RESCAN_INTERVAL: Duration = Duration::from_secs(10 * 60);

// Seconds a file date may be off from the one recorded, besides an hour.
const DATE_SLACK: i64 = 2;
const HOUR: i64 = 3600;
// A shared tree is walked this deep, and up to so many folders.
const MAX_TREE_DEPTH: usize = 32;
const MAX_TREE_DIRS: usize = 10_000;
const HASH_ATTEMPTS: u32 = 10;
// The AICH tree of a file not shared for so long is dropped; the file is hashed again when it is shared again.
const TREE_KEPT_UNSHARED: u64 = 31 * 24 * 3600;
// AICH trees kept built, for the files clients ask recovery data of now.
const MAX_BUILT_TREES: usize = 64;
const HASH_RETRY_DELAY: Duration = Duration::from_millis(500);
const SKIPPED: [&str; 9] = [
    "desktop.ini",
    "thumbs.db",
    "ehthumbs.db",
    "ehthumbs_vista.db",
    ".ds_store",
    ".localized",
    ".directory",
    "icon\r",
    "folder.jpg",
];
// Files other programs keep beside their work: Office owner files (they carry the user's name), macOS resource forks,
// lock files, NFS leftovers and the temporary files of sync tools.
const SKIPPED_PREFIXES: [&str; 6] = ["~$", "._", ".~lock.", ".nfs", ".sb-", ".syncthing."];
// Downloads still being written by browsers and other clients, links, temporary files and backups.
const SKIPPED_SUFFIXES: [&str; 14] = [
    ".crdownload",
    ".download",
    ".partial",
    ".opdownload",
    ".!ut",
    ".!qb",
    ".bc!",
    ".temp",
    "~",
    ".lnk",
    ".tmp",
    ".bak",
    part_file::DATA_SUFFIX,
    part_file::MET_SUFFIX,
];

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

    fn record(&self) -> KnownFile {
        let mut tags = vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: self.name.clone() },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: self.size },
        ];
        if let Some(aich) = self.aich_hash {
            tags.push(aich_tag(aich));
        }
        KnownFile { mtime: self.mtime, file_hash: self.hash, part_hashes: self.part_hashes.clone(), tags }
    }

    fn key(&self) -> Key {
        (self.name.clone(), self.size, self.mtime)
    }
}

/// A finished download: its hash and part hashes, which its download checked, and the AICH hash of its link or
/// sources, which nothing proved to be the file's.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Finished {
    pub hash: [u8; 16],
    pub size: u64,
    pub part_hashes: Vec<[u8; 16]>,
    pub aich_hash: Option<[u8; 20]>,
}

/// A file found in a shared folder: its name, size and modification time, which tell whether it is known.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Found {
    pub path: PathBuf,
    pub name: String,
    pub size: u64,
    pub mtime: u32,
}

/// A file of a shared folder, as looked at.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Looked {
    Found(Found),
    /// There but not to be looked at, such as a locked file: shared, it stays so.
    Unreadable(PathBuf),
}

/// What is never shared: what lies in or below the configuration folder and the other private ones, the home itself,
/// and any folder holding a client's private key, another client's configuration.
#[derive(Debug, Clone, Default)]
pub struct Private {
    roots: Vec<PathBuf>,
    home: Option<PathBuf>,
}

impl Private {
    pub fn holds(&self, dir: &Path) -> bool {
        let absolute = shared_directory::absolute(dir);
        // Through a link or a junction, the folder itself may be a private one.
        let real = shared_directory::real(&absolute);
        self.roots.iter().any(|root| absolute.starts_with(root) || real.starts_with(root))
            || self.home.as_ref().is_some_and(|home| absolute == *home || real == *home)
            || absolute.join(CLIENT_KEY).exists()
    }
}

/// Where a scan looks, handed to [`list`] off the engine's task.
#[derive(Debug, Clone, Default)]
pub struct Places {
    folders: Vec<PathBuf>,
    trees: Vec<PathBuf>,
    private: Private,
}

/// What a scan read.
#[derive(Debug, Clone, Default)]
pub struct Listing {
    pub files: Vec<Looked>,
    /// The folders read: the shared ones and the subfolders of the trees, which changes in are watched for.
    pub folders: Vec<PathBuf>,
}

/// What a scan or a hash changed.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Changes {
    /// The files shared and unshared, in turn.
    pub sharing: Vec<Sharing>,
    /// The files to hash, whose hashes come back through [`SharedFiles::hashed`].
    pub to_hash: Vec<Found>,
    /// Whether a file changed while it was hashed, so that a scan must look again once it rests.
    pub rescan: bool,
}

/// A content shared, or no longer shared from any copy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Sharing {
    Shared(SharedFile),
    Unshared(SharedFile),
}

// A known file by name, size and modification time.
type Key = (String, u64, u32);

#[derive(Debug, Default)]
pub struct SharedFiles {
    config: PathBuf,
    // The folders whose files are shared besides those of the lists, and those never shared besides the configuration.
    incoming: Vec<PathBuf>,
    private: Vec<PathBuf>,
    // By hash, the copy shared; by path, every copy.
    files: IndexMap<[u8; 16], SharedFile>,
    by_path: IndexMap<PathBuf, SharedFile>,
    // Files hashed before.
    known: IndexMap<Key, KnownFile>,
    hashing: HashSet<PathBuf>,
    // The asks, slots and bytes of the shared files in this run.
    counts: HashMap<[u8; 16], Counts>,
    // The AICH trees, by master hash; those built, the least recently asked for first: building one hashes all its
    // block hashes again.
    aich: IndexMap<[u8; 20], known2_met::Entry>,
    built: IndexMap<[u8; 20], Option<Arc<AichTree>>>,
    // Whether the trees changed since known2_64.met was written; it is not written again otherwise.
    trees_changed: bool,
    // Finished downloads hashed again for their own tree; and those that finished while a scan was hashing their file,
    // maybe before it was whole.
    awaiting_tree: HashMap<PathBuf, Finished>,
    finishing: HashMap<PathBuf, Finished>,
    // Whether a scan said which files are shared.
    scanned: bool,
    // Whether known.met leaves out the files no longer shared, and with them what tells the files downloaded before.
    forget_downloaded: bool,
    // Whether known.met is behind what is known.
    unsaved: bool,
    closed: bool,
}

// The counts of a run, which start from the totals known.met kept; both are written back at each save.
#[derive(Debug, Clone, Copy, Default)]
struct Counts {
    requests_before: u64,
    accepted_before: u64,
    transferred_before: u64,
    requests: u64,
    accepted: u64,
    transferred: u64,
}

impl Counts {
    fn before(record: &KnownFile) -> Self {
        Self {
            requests_before: record.requests(),
            accepted_before: record.accepted(),
            transferred_before: record.transferred(),
            ..Self::default()
        }
    }

    fn statistics(&self) -> Statistics {
        Statistics {
            requests: self.requests,
            accepted: self.accepted,
            transferred: self.transferred,
            total_requests: self.requests_before.saturating_add(self.requests),
            total_accepted: self.accepted_before.saturating_add(self.accepted),
            total_transferred: self.transferred_before.saturating_add(self.transferred),
        }
    }
}

/// Why a shared file cannot be renamed.
#[derive(Debug)]
pub enum RenameError {
    /// A name the scans never share, such as that of a backup or of a hidden file: renamed so, the file would be
    /// unshared a moment later.
    NeverShared(String),
    /// The file could not be moved: another file has the name, or the disk refused.
    Failed(io::Error),
}

impl fmt::Display for RenameError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NeverShared(name) => write!(f, "A file named {name} is not shared"),
            Self::Failed(e) => write!(f, "Cannot rename the file: {e}"),
        }
    }
}

// The file, else its backup; when both are damaged, what comes before the damage, from the one that keeps more. True
// with what was read from the backup or saved from damage, which the next save writes whole again.
fn load<T, E: std::error::Error + Send + Sync + 'static>(
    file: &Path,
    strict: impl Fn(&[u8]) -> Result<Vec<T>, E>,
    salvage: impl Fn(&[u8]) -> Vec<T>,
) -> (Vec<T>, bool) {
    match atomic_files::read_telling(file, |data| {
        strict(data).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
    }) {
        Ok(read) => (read.value, read.from_backup),
        Err(e) if e.kind() == io::ErrorKind::NotFound => (Vec::new(), false),
        Err(e) => {
            tracing::warn!("Cannot read {}; what can be read of it is kept: {e}", file.display());
            let best = [atomic_files::bak_path(file), file.to_path_buf()]
                .iter()
                .filter_map(|candidate| fs::read(candidate).ok())
                .map(|data| salvage(&data))
                .max_by_key(Vec::len)
                .unwrap_or_default();
            (best, true)
        }
    }
}

// The folders a list names; an entry that names no folder on any system is left out.
fn read_folders(file: &Path) -> Vec<PathBuf> {
    match fs::read(file) {
        Ok(data) => text_files::entries(&data)
            .into_iter()
            .filter(|entry| {
                let valid = !entry.contains('\0');
                if !valid {
                    tracing::warn!("Ignoring the shared folder {entry:?} of {}", file.display());
                }
                valid
            })
            .map(PathBuf::from)
            .collect(),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Vec::new(),
        Err(e) => {
            tracing::warn!("Cannot read {}: {e}", file.display());
            Vec::new()
        }
    }
}

impl SharedFiles {
    /// The files hashed before, from `known.met` in `config`; a scan says which are shared.
    pub fn load(config: &Path) -> Self {
        let mut shared = Self { config: config.to_path_buf(), ..Self::default() };
        let (trees, damaged) = load(&config.join(KNOWN2_FILE_NAME), known2_met::read, known2_met::salvage);
        shared.aich = trees.into_iter().map(|entry| (entry.master_hash, entry)).collect();
        shared.trees_changed = damaged;
        let (records, _) = load(&config.join(KNOWN_FILE_NAME), known_met::read, known_met::salvage);
        for known in records {
            if let Some(key) = usable_key(&known) {
                shared.known.insert(key, known);
            }
        }
        shared
    }

    /// Whether `known.met` keeps the files no longer shared, which tells the files downloaded before.
    pub fn remember_downloaded(&mut self, remember: bool) {
        if self.forget_downloaded == remember {
            self.forget_downloaded = !remember;
            self.unsaved = true;
        }
    }

    /// Remembers the records of another client's files, so that a scan shares the same files without hashing them
    /// again; returns how many were new. Of its AICH trees, only those of these files are kept.
    pub fn add_known(&mut self, files: &[KnownFile], trees: &[known2_met::Entry]) -> usize {
        let mut wanted = HashSet::new();
        let mut added = 0usize;
        for file in files {
            let Some(key) = usable_key(file) else {
                continue;
            };
            if let indexmap::map::Entry::Vacant(vacant) = self.known.entry(key) {
                vacant.insert(file.clone());
                added = added.saturating_add(1);
                wanted.extend(file.aich_hash());
            }
        }
        for tree in trees {
            if wanted.contains(&tree.master_hash) && !self.aich.contains_key(&tree.master_hash) {
                self.aich.insert(tree.master_hash, tree.clone());
                self.trees_changed = true;
            }
        }
        if added > 0 {
            self.unsaved = true;
        }
        added
    }

    /// Sets the incoming folders, whose files are shared, and the folders never shared besides the configuration one,
    /// such as the temp one.
    pub fn set_folders(&mut self, incoming: Vec<PathBuf>, private: Vec<PathBuf>) {
        self.incoming = incoming;
        self.private = private;
    }

    /// Writes `known.met`, the records of the files shared taking the date and the counts of this run, and
    /// `known2_64.met` when the trees changed. Once a scan said which files are shared, a tree no record needs goes, and
    /// so does that of a file not shared for a month: a record keeps the file known as downloaded before, a tree is
    /// needed only to share it. Then too the records of the files no longer shared go, when they are not remembered.
    pub fn save(&mut self) -> io::Result<()> {
        let now = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |since| since.as_secs());
        let mut needed = HashSet::new();
        let mut records = Vec::with_capacity(self.known.len());
        for record in self.known.values_mut() {
            let shared = self.files.contains_key(&record.file_hash);
            if self.forget_downloaded && !shared && self.scanned {
                continue;
            }
            if shared {
                set_int(record, file_tag::LASTSHARED, now);
                if let Some(counts) = self.counts.get(&record.file_hash) {
                    with_totals(record, &counts.statistics());
                }
            }
            let seen = tags::int_value(&record.tags, file_tag::LASTSHARED).unwrap_or(now);
            if (shared || !self.scanned || now.saturating_sub(seen) < TREE_KEPT_UNSHARED)
                && let Some(aich) = record.aich_hash()
            {
                needed.insert(aich);
            }
            records.push(record.clone());
        }
        let before = self.aich.len();
        self.aich.retain(|master, _| needed.contains(master));
        if self.aich.len() != before {
            self.built.retain(|master, _| needed.contains(master));
            self.trees_changed = true;
        }
        let data = known_met::write(&records).map_err(io::Error::other)?;
        atomic_files::write(&self.config.join(KNOWN_FILE_NAME), &data)?;
        self.unsaved = false;
        if self.trees_changed {
            let trees: Vec<known2_met::Entry> = self.aich.values().cloned().collect();
            let data = known2_met::write(&trees).map_err(io::Error::other)?;
            atomic_files::write(&self.config.join(KNOWN2_FILE_NAME), &data)?;
            self.trees_changed = false;
        }
        Ok(())
    }

    /// The AICH tree of the file shared with `hash`, when its block hashes are kept; built once for the requests that
    /// follow.
    pub fn aich_tree(&mut self, hash: &[u8; 16]) -> Option<Arc<AichTree>> {
        let file = self.files.get(hash)?;
        let entry = self.aich.get(&file.aich_hash?)?;
        let master = entry.master_hash;
        if let Some(built) = self.built.shift_remove(&master) {
            self.built.insert(master, built.clone());
            return built;
        }
        let tree = match AichTree::from_block_hashes(file.size, entry.block_hashes.clone()) {
            Ok(tree) if tree.master_hash() == master => Some(Arc::new(tree)),
            Ok(_) => None,
            Err(e) => {
                tracing::debug!("Unusable AICH hashes for {}: {e}", file.name);
                None
            }
        };
        self.built.insert(master, tree.clone());
        if self.built.len() > MAX_BUILT_TREES {
            self.built.shift_remove_index(0);
        }
        tree
    }

    /// The AICH recovery data of `part` of the file shared with `hash`, when we have its tree with `master`.
    pub fn aich_recovery(&mut self, hash: &[u8; 16], part: u32, master: &[u8; 20]) -> Option<Vec<u8>> {
        let tree = self.aich_tree(hash)?;
        if tree.master_hash() != *master || !aich_recovery::has_recovery(tree.size(), part) {
            return None;
        }
        aich_recovery::create(&tree, part).ok()
    }

    /// Whether `known.met` is behind what is known now.
    pub fn is_unsaved(&self) -> bool {
        self.unsaved
    }

    /// Saves, and from then on scans and hashes change nothing.
    pub fn close(&mut self) -> io::Result<()> {
        self.closed = true;
        self.save()
    }

    /// The folders shared alone, besides the incoming ones.
    pub fn directories(&self) -> Vec<PathBuf> {
        read_folders(&self.config.join(SHARED_DIRS))
    }

    /// The folders shared with their subfolders.
    pub fn trees(&self) -> Vec<PathBuf> {
        read_folders(&self.config.join(SHARED_TREES))
    }

    /// The folders shared alone, then those shared with their subfolders.
    pub fn shared_directories(&self) -> Vec<SharedDirectory> {
        let alone = self.directories().into_iter().map(|path| SharedDirectory { path, subdirectories: false });
        let trees = self.trees().into_iter().map(|path| SharedDirectory { path, subdirectories: true });
        alone.chain(trees).collect()
    }

    /// Whether the files of `dir` must never be shared (see [`Private`]).
    pub fn is_private(&self, dir: &Path) -> bool {
        self.private_folders().holds(dir)
    }

    fn private_folders(&self) -> Private {
        let mut roots: Vec<PathBuf> = self.private.iter().map(|dir| shared_directory::absolute(dir)).collect();
        roots.push(shared_directory::absolute(&self.config));
        Private { roots, home: dirs::home_dir().map(|home| shared_directory::absolute(&home)) }
    }

    /// Shares `dir` too from now on, with its subfolders (also those made later) or alone; a scan shares its files.
    /// Answers why not for a private folder.
    pub fn add_directory(&mut self, dir: &Path, subdirectories: bool) -> Result<(), String> {
        let dir = shared_directory::absolute(dir);
        if self.is_private(&dir) {
            return Err(format!("Refusing to share the private folder {}", dir.display()));
        }
        self.change(SHARED_DIRS, &dir, !subdirectories)
            .and_then(|_| self.change(SHARED_TREES, &dir, subdirectories))
            .map(|_| ())
            .map_err(|e| format!("Cannot save the shared folders: {e}"))
    }

    /// Stops sharing `dir`, alone or with its subfolders; a scan unshares its files. False when it was not shared.
    pub fn remove_directory(&mut self, dir: &Path) -> io::Result<bool> {
        let dir = shared_directory::absolute(dir);
        let alone = self.change(SHARED_DIRS, &dir, false)?;
        let tree = self.change(SHARED_TREES, &dir, false)?;
        Ok(alone || tree)
    }

    // Lists `dir` in the list `name`, or takes it out; true when that changed the list.
    fn change(&self, name: &str, dir: &Path, listed: bool) -> io::Result<bool> {
        let file = self.config.join(name);
        let mut folders = read_folders(&file);
        let changed = if listed {
            let missing = !folders.iter().any(|folder| folder == dir);
            if missing {
                folders.push(dir.to_path_buf());
            }
            missing
        } else {
            let before = folders.len();
            folders.retain(|folder| folder != dir);
            folders.len() != before
        };
        if changed {
            let lines: Vec<String> = folders.iter().map(|folder| folder.to_string_lossy().into_owned()).collect();
            atomic_files::write(&file, &text_files::write_lines(&lines))?;
        }
        Ok(changed)
    }

    /// Where a scan looks: the incoming folders, the shared ones and the trees, with the private folders to leave out.
    pub fn places(&self) -> Places {
        let mut folders = self.incoming.clone();
        folders.extend(self.directories());
        Places { folders, trees: self.trees(), private: self.private_folders() }
    }

    /// Takes what a scan read: a new or changed file is shared at once when known, else to be hashed, and a file no
    /// longer there is unshared.
    pub fn scanned(&mut self, listing: Listing) -> Changes {
        let mut changes = Changes::default();
        if self.closed {
            return changes;
        }
        self.scanned = true;
        let mut seen = HashSet::new();
        for looked in listing.files {
            match looked {
                Looked::Found(found) => {
                    seen.insert(found.path.clone());
                    self.consider(found, &mut changes);
                }
                // A file locked meanwhile stays shared.
                Looked::Unreadable(path) => {
                    seen.insert(path);
                }
            }
        }
        let gone: Vec<SharedFile> = self.by_path.values().filter(|file| !seen.contains(&file.path)).cloned().collect();
        for file in gone {
            self.unshare(&file, &mut changes);
        }
        changes
    }

    fn consider(&mut self, found: Found, changes: &mut Changes) {
        if self.by_path.get(&found.path).is_some_and(|shared| shared.size == found.size && shared.mtime == found.mtime)
        {
            return;
        }
        if let Some(record) = self.known_record(&found.name, found.size, found.mtime) {
            let file = SharedFile {
                hash: record.file_hash,
                name: found.name.clone(),
                size: found.size,
                path: found.path.clone(),
                mtime: found.mtime,
                part_hashes: record.part_hashes.clone(),
                aich_hash: record.aich_hash(),
            };
            let has_tree = file.aich_hash.is_some_and(|aich| self.aich.contains_key(&aich));
            self.share(file, None, changes);
            // Without its AICH tree, lost, dropped while the file was not shared or never built, it is hashed again.
            if has_tree {
                return;
            }
        }
        if self.hashing.insert(found.path.clone()) {
            changes.to_hash.push(found);
        }
    }

    /// Shares a finished download at once, its part hashes known. Its AICH hash comes with the tree that hashing it once
    /// more gives: the one of the link or of the sources is not proven to be the file's.
    pub fn finished(&mut self, path: &Path, download: Finished) -> Changes {
        let mut changes = Changes::default();
        let (Ok(metadata), Some(name)) = (fs::metadata(path), path.file_name().and_then(|name| name.to_str())) else {
            tracing::warn!("Cannot share {}", path.display());
            return changes;
        };
        let file = SharedFile {
            hash: download.hash,
            name: name.to_owned(),
            size: download.size,
            path: path.to_path_buf(),
            mtime: mtime(&metadata),
            part_hashes: download.part_hashes.clone(),
            aich_hash: None,
        };
        let mtime = file.mtime;
        self.share(file, None, &mut changes);
        self.hash_finished(path, mtime, download, &mut changes);
        changes
    }

    // The download checked the parts, not the AICH blocks: hashing the file once more gives its tree, and with it the
    // recovery data other clients may ask for.
    fn hash_finished(&mut self, path: &Path, mtime: u32, download: Finished, changes: &mut Changes) {
        if !self.hashing.insert(path.to_path_buf()) {
            self.finishing.insert(path.to_path_buf(), download);
            return;
        }
        let name = path.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default();
        changes.to_hash.push(Found { path: path.to_path_buf(), name, size: download.size, mtime });
        self.awaiting_tree.insert(path.to_path_buf(), download);
    }

    fn tree_of_finished(&mut self, found: Found, download: Finished, hashes: Result<FileHashes, String>) -> Changes {
        let mut changes = Changes::default();
        match &hashes {
            Err(_) if fs::symlink_metadata(&found.path).is_err() => {
                // Renamed before it could be read: the copy shared now is hashed instead.
                if let Some(current) = self.files.get(&download.hash).cloned()
                    && current.path != found.path
                {
                    self.hash_finished(&current.path, current.mtime, download, &mut changes);
                }
                return changes;
            }
            Ok(hashes) if hashes.ed2k.file_hash() != download.hash => {
                tracing::warn!("{} changed since its download finished", found.path.display());
                return changes;
            }
            Ok(hashes)
                if download.aich_hash.is_some()
                    && hashes.aich.as_ref().map(AichTree::master_hash) != download.aich_hash =>
            {
                tracing::warn!("The AICH hash of the link of {} was wrong; the file's own is kept", found.name);
            }
            _ => {}
        }
        self.hashed_file(found, hashes, &mut changes);
        changes
    }

    /// Takes the hashes of a found file, or why it could not be hashed. A file that changed meanwhile is left for a
    /// scan once it rests, one gone is left out.
    pub fn hashed(&mut self, found: Found, hashes: Result<FileHashes, String>) -> Changes {
        self.hashing.remove(&found.path);
        let mut changes = Changes::default();
        if self.closed {
            return changes;
        }
        if let Some(download) = self.finishing.remove(&found.path) {
            // What the scan read may not be the whole download, nor still under this name: the file is read once more.
            if let Some(current) = self.files.get(&download.hash).cloned() {
                self.hash_finished(&current.path, current.mtime, download, &mut changes);
            }
            return changes;
        }
        if let Some(download) = self.awaiting_tree.remove(&found.path) {
            return self.tree_of_finished(found, download, hashes);
        }
        self.hashed_file(found, hashes, &mut changes);
        changes
    }

    fn hashed_file(&mut self, found: Found, hashes: Result<FileHashes, String>, changes: &mut Changes) {
        let hashes = match hashes {
            Ok(hashes) => hashes,
            Err(e) => {
                tracing::warn!("Cannot hash {}: {e}", found.path.display());
                return;
            }
        };
        let tree = hashes.aich.as_ref().map(|tree| known2_met::Entry {
            master_hash: tree.master_hash(),
            block_hashes: tree.block_hashes().to_vec(),
        });
        match fs::symlink_metadata(&found.path) {
            Ok(now) if now.len() == hashes.ed2k.size() && mtime(&now) == found.mtime => {}
            Ok(_) => {
                // Written to while it was read: the hashes may be of neither content. A scan hashes it again once it
                // rests.
                tracing::debug!("{} changed while it was hashed", found.path.display());
                changes.rescan = true;
                return;
            }
            Err(_) => {
                // Renamed or gone while hashed: the copy shared now, if any, gets the AICH tree.
                if let Some(current) = self.files.get(&hashes.ed2k.file_hash()).cloned()
                    && current.size == hashes.ed2k.size()
                {
                    let master = tree.as_ref().map(|tree| tree.master_hash);
                    self.share(SharedFile { aich_hash: master, ..current }, tree, changes);
                }
                return;
            }
        }
        let file = SharedFile {
            hash: hashes.ed2k.file_hash(),
            name: found.name,
            size: found.size,
            path: found.path,
            mtime: found.mtime,
            part_hashes: hashes.ed2k.part_hashes().to_vec(),
            aich_hash: tree.as_ref().map(|tree| tree.master_hash),
        };
        self.share(file, tree, changes);
    }

    /// A request of the shared file with `hash`, counted in its statistics.
    pub fn asked(&mut self, hash: &[u8; 16]) {
        self.counted(hash, 1, 0, 0);
    }

    /// An upload slot for the shared file with `hash`, counted in its statistics.
    pub fn accepted(&mut self, hash: &[u8; 16]) {
        self.counted(hash, 0, 1, 0);
    }

    /// Bytes of the shared file with `hash` that went out, counted in its statistics.
    pub fn sent(&mut self, hash: &[u8; 16], bytes: u64) {
        self.counted(hash, 0, 0, bytes);
    }

    /// Requests, slots and bytes of the shared file with `hash`, counted at once.
    pub fn counted(&mut self, hash: &[u8; 16], requests: u64, accepted: u64, bytes: u64) {
        let Some(file) = self.files.get(hash) else {
            return;
        };
        let record = self.known.get(&file.key());
        let counts = self.counts.entry(*hash).or_insert_with(|| record.map_or_else(Counts::default, Counts::before));
        counts.requests = counts.requests.saturating_add(requests);
        counts.accepted = counts.accepted.saturating_add(accepted);
        counts.transferred = counts.transferred.saturating_add(bytes);
    }

    /// What the shared file with `hash` was asked for and sent, in this run and in all; nothing for a file not shared.
    pub fn statistics(&self, hash: &[u8; 16]) -> Statistics {
        if let Some(counts) = self.counts.get(hash) {
            return counts.statistics();
        }
        self.record_of(hash).map(|record| Counts::before(record).statistics()).unwrap_or_default()
    }

    /// The upload priority of the file shared with `hash`: automatic unless one was chosen.
    pub fn priority(&self, hash: &[u8; 16]) -> UploadPriority {
        self.record_of(hash)
            .and_then(KnownFile::upload_priority)
            .map_or(UploadPriority::Auto, UploadPriority::of_code)
    }

    /// Sets the upload priority of a shared file, kept in `known.met`; false for a file not shared.
    pub fn set_priority(&mut self, hash: &[u8; 16], priority: UploadPriority) -> bool {
        let Some(file) = self.files.get(hash) else {
            return false;
        };
        let key = file.key();
        let mut record = self.known.get(&key).cloned().unwrap_or_else(|| file.record());
        set_int(&mut record, file_tag::ULPRIORITY, priority.code());
        self.known.insert(key, record);
        self.unsaved = true;
        true
    }

    fn record_of(&self, hash: &[u8; 16]) -> Option<&KnownFile> {
        self.files.get(hash).and_then(|file| self.known.get(&file.key()))
    }

    /// Renames a shared file in its folder; its hashes and priority stay known under the new name. None for a file not
    /// shared; the file as it was for an empty name or the same one.
    pub fn rename(&mut self, hash: &[u8; 16], new_name: &str) -> Result<Option<(SharedFile, Changes)>, RenameError> {
        let Some(file) = self.files.get(hash).cloned() else {
            return Ok(None);
        };
        let name = path_sanitizer::file_name(new_name);
        if new_name.trim().is_empty() || name == file.name {
            return Ok(Some((file, Changes::default())));
        }
        if name.starts_with('.') || is_skipped(&name) {
            return Err(RenameError::NeverShared(name));
        }
        let target = file.path.with_file_name(&name);
        move_file(&file.path, &target).map_err(RenameError::Failed)?;
        let mtime = fs::metadata(&target).map_or(file.mtime, |metadata| mtime(&metadata));
        let renamed = SharedFile { path: target, name, mtime, ..file.clone() };
        let record = self.known.shift_remove(&file.key());
        self.known
            .insert(renamed.key(), record.map_or_else(|| renamed.record(), |known| with_name(known, &renamed)));
        let mut changes = Changes::default();
        self.unshare(&file, &mut changes);
        self.share(renamed.clone(), None, &mut changes);
        self.unsaved = true;
        Ok(Some((renamed, changes)))
    }

    /// The files shared now or hashed before, by their hash.
    pub fn known_hashes(&self) -> HashSet<[u8; 16]> {
        self.files.keys().copied().chain(self.known.values().map(|known| known.file_hash)).collect()
    }

    pub fn files(&self) -> impl Iterator<Item = &SharedFile> {
        self.files.values()
    }

    pub fn get(&self, hash: &[u8; 16]) -> Option<&SharedFile> {
        self.files.get(hash)
    }

    fn share(&mut self, file: SharedFile, tree: Option<known2_met::Entry>, changes: &mut Changes) {
        if let Some(old) = self.by_path.insert(file.path.clone(), file.clone())
            && old.hash != file.hash
        {
            self.forget(&old, changes);
        }
        let fresh = !self.files.contains_key(&file.hash);
        self.files.insert(file.hash, file.clone());
        let key = file.key();
        match self.known.get(&key) {
            None => {
                let record = self.take_older_date(&file).map_or_else(|| file.record(), |older| redated(older, &file));
                self.known.insert(key, record);
                self.unsaved = true;
            }
            Some(known) if file.aich_hash.is_some() && known.aich_hash() != file.aich_hash => {
                // A finished download comes with its AICH hash; its other tags stay.
                let record = with_aich(known.clone(), file.aich_hash);
                self.known.insert(key, record);
                self.unsaved = true;
            }
            Some(_) => {}
        }
        if let Some(tree) = tree {
            self.built.shift_remove(&tree.master_hash);
            self.aich.insert(tree.master_hash, tree);
            self.trees_changed = true;
            self.unsaved = true;
        }
        if fresh {
            tracing::debug!("Sharing {}", file.path.display());
            changes.sharing.push(Sharing::Shared(file));
        }
    }

    fn unshare(&mut self, file: &SharedFile, changes: &mut Changes) {
        self.by_path.shift_remove(&file.path);
        self.forget(file, changes);
    }

    // A path that went or changed: its content stays shared from another copy, if there is one.
    fn forget(&mut self, old: &SharedFile, changes: &mut Changes) {
        if self.files.get(&old.hash) != Some(old) {
            return;
        }
        if let Some(other) = self.by_path.values().find(|other| other.hash == old.hash && other.path != old.path) {
            self.files.insert(old.hash, other.clone());
            return;
        }
        self.files.shift_remove(&old.hash);
        tracing::debug!("No longer sharing {}", old.path.display());
        changes.sharing.push(Sharing::Unshared(old.clone()));
    }

    // The record of a file by name, size and date. FAT keeps dates to 2 s and in local time, so a copy there or a
    // change of daylight saving time moves them by up to 2 s or by an hour: such a record is taken too, under the date
    // now.
    fn known_record(&mut self, name: &str, size: u64, mtime: u32) -> Option<KnownFile> {
        let key = (name.to_owned(), size, mtime);
        if let Some(exact) = self.known.get(&key) {
            return Some(exact.clone());
        }
        for hour in [0, HOUR, HOUR.saturating_neg()] {
            for slack in DATE_SLACK.saturating_neg()..=DATE_SLACK {
                let Some(near_mtime) =
                    i64::from(mtime).checked_add(hour.saturating_add(slack)).and_then(|near| u32::try_from(near).ok())
                else {
                    continue;
                };
                let near = (name.to_owned(), size, near_mtime);
                if near == key || !self.known.get(&near).is_some_and(|record| !self.is_in_use(&near, record)) {
                    continue;
                }
                let record = self.known.shift_remove(&near)?;
                let moved = KnownFile { mtime, ..record };
                self.known.insert(key, moved.clone());
                self.unsaved = true;
                return Some(moved);
            }
        }
        None
    }

    // Whether a file shared now, elsewhere, is the one recorded under the key.
    fn is_in_use(&self, key: &Key, record: &KnownFile) -> bool {
        self.files.get(&record.file_hash).is_some_and(|shared| shared.key() == *key)
    }

    // The record of the same name, size and content under another date: the file before it was touched, whose record
    // would otherwise stay for good. One that a copy shared now uses stays.
    fn take_older_date(&mut self, file: &SharedFile) -> Option<KnownFile> {
        let older = self
            .known
            .iter()
            .find(|((name, size, _), record)| {
                *name == file.name
                    && *size == file.size
                    && record.file_hash == file.hash
                    && !self.by_path.values().any(|shared| shared.key() == (name.clone(), *size, record.mtime))
            })
            .map(|(key, _)| key.clone())?;
        self.known.shift_remove(&older)
    }
}

// Where a record is known: its name, size and date. A record without them, or whose part hashes do not make its hash,
// is of no use.
fn usable_key(known: &KnownFile) -> Option<Key> {
    let (Some(name), Some(size)) = (known.name(), known.size()) else {
        return None;
    };
    known.is_consistent().then(|| (name.to_owned(), size, known.mtime))
}

// The record of a file whose date changed: its other tags, such as its priority, stay.
fn redated(older: KnownFile, file: &SharedFile) -> KnownFile {
    let moved =
        KnownFile { mtime: file.mtime, file_hash: file.hash, part_hashes: file.part_hashes.clone(), tags: older.tags };
    with_aich(moved, file.aich_hash)
}

fn with_aich(mut known: KnownFile, aich: Option<[u8; 20]>) -> KnownFile {
    let Some(aich) = aich else {
        return known;
    };
    known.tags.retain(|tag| *tag.name() != TagName::id(file_tag::AICH_HASH));
    known.tags.push(aich_tag(aich));
    known
}

// The record of a renamed file: its name and date change, its other tags stay.
fn with_name(mut known: KnownFile, file: &SharedFile) -> KnownFile {
    known.tags.retain(|tag| *tag.name() != TagName::id(file_tag::FILENAME));
    known.tags.push(Tag::String { name: TagName::id(file_tag::FILENAME), value: file.name.clone() });
    known.mtime = file.mtime;
    known
}

// The totals of all runs, as other clients keep them: the counts on 32 bits, the bytes on two.
fn with_totals(known: &mut KnownFile, statistics: &Statistics) {
    let low = u64::from(u32::MAX);
    set_int(known, file_tag::ATREQUESTED, statistics.total_requests.min(low));
    set_int(known, file_tag::ATACCEPTED, statistics.total_accepted.min(low));
    set_int(known, file_tag::ATTRANSFERRED, statistics.total_transferred & low);
    set_int(known, file_tag::ATTRANSFERREDHI, statistics.total_transferred >> 32);
}

fn set_int(known: &mut KnownFile, id: u8, value: u64) {
    known.tags.retain(|tag| *tag.name() != TagName::id(id));
    known.tags.push(Tag::Int { name: TagName::id(id), value });
}

// Moves a file to a free name. A change of case only is made through another name: a system that ignores case does not
// see it as a move.
fn move_file(from: &Path, to: &Path) -> io::Result<()> {
    if to.exists() {
        if !same_file::is_same_file(from, to)? {
            return Err(io::Error::new(io::ErrorKind::AlreadyExists, format!("{} exists", to.display())));
        }
        let step = atomic_files::tmp_path(to);
        fs::rename(from, &step)?;
        return fs::rename(&step, to).inspect_err(|_| {
            let _ = fs::rename(&step, from);
        });
    }
    fs::rename(from, to)
}

fn aich_tag(aich: [u8; 20]) -> Tag {
    Tag::String { name: TagName::id(file_tag::AICH_HASH), value: data_encoding::BASE32_NOPAD.encode(&aich) }
}

/// Reads the shared folders off the engine's task: the files that may be shared, and the folders looked at.
pub fn list(places: &Places) -> Listing {
    let mut folders: IndexSet<PathBuf> = places.folders.iter().map(|dir| shared_directory::absolute(dir)).collect();
    for tree in &places.trees {
        walk(&shared_directory::absolute(tree), &places.private, &mut folders);
    }
    let mut files = Vec::new();
    let mut read = Vec::new();
    for folder in folders {
        if !folder.is_dir() {
            continue;
        }
        if places.private.holds(&folder) {
            tracing::warn!("Not sharing the private folder {}", folder.display());
            continue;
        }
        match files_in(&folder) {
            Ok(looked) => {
                files.extend(looked);
                read.push(folder);
            }
            Err(e) => tracing::warn!("Cannot list the shared folder {}: {e}", folder.display()),
        }
    }
    Listing { files, folders: read }
}

/// The files of `folder` that may be shared, looked at one by one, without going into its folders: a file gone
/// meanwhile is left out and the others are looked at still.
pub fn files_in(folder: &Path) -> io::Result<impl Iterator<Item = Looked> + use<>> {
    Ok(fs::read_dir(folder)?.filter_map(Result::ok).filter_map(|entry| {
        let path = entry.path();
        match look(&path) {
            Ok(found) => found.map(Looked::Found),
            Err(e) if e.kind() == io::ErrorKind::NotFound => None,
            Err(e) => {
                tracing::debug!("Cannot look at {}: {e}", path.display());
                Some(Looked::Unreadable(path))
            }
        }
    }))
}

// A file that may be shared: a complete file of our own, neither empty, hidden nor one other programs keep.
fn look(path: &Path) -> io::Result<Option<Found>> {
    // Looked at again, not as the folder was listed: a file gone meanwhile is seen gone.
    let metadata = fs::symlink_metadata(path)?;
    let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
        return Ok(None);
    };
    if !metadata.is_file()
        || metadata.len() == 0
        || metadata.len() > ed2k::MAX_FILE_SIZE
        || is_hidden(name, &metadata)
        || is_skipped(name)
    {
        return Ok(None);
    }
    Ok(Some(Found { path: path.to_path_buf(), name: name.to_owned(), size: metadata.len(), mtime: mtime(&metadata) }))
}

// The subfolders of a tree, without hidden or private ones, never through a link or a junction: so never out of the
// tree nor round in a loop. A folder that cannot be read leaves the rest of the tree alone.
fn walk(root: &Path, private: &Private, folders: &mut IndexSet<PathBuf>) {
    if !root.is_dir() {
        return;
    }
    let walker = WalkDir::new(root).max_depth(MAX_TREE_DEPTH).into_iter().filter_entry(|entry| {
        entry.depth() == 0
            || (entry.file_type().is_dir()
                && !entry.metadata().is_ok_and(|metadata| is_hidden(&entry.file_name().to_string_lossy(), &metadata))
                && !private.holds(entry.path()))
    });
    for entry in walker.filter_map(Result::ok) {
        folders.insert(entry.into_path());
        if folders.len() >= MAX_TREE_DIRS {
            return;
        }
    }
}

// A dot file, and on Windows one marked hidden too.
fn is_hidden(name: &str, metadata: &fs::Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        const FILE_ATTRIBUTE_HIDDEN: u32 = 0x2;
        if metadata.file_attributes() & FILE_ATTRIBUTE_HIDDEN != 0 {
            return true;
        }
    }
    #[cfg(not(windows))]
    let _ = metadata;
    name.starts_with('.')
}

fn is_skipped(name: &str) -> bool {
    let lower = name.to_lowercase();
    SKIPPED.contains(&lower.as_str())
        || SKIPPED_PREFIXES.iter().any(|prefix| lower.starts_with(prefix))
        || SKIPPED_SUFFIXES.iter().any(|suffix| lower.ends_with(suffix))
}

fn mtime(metadata: &fs::Metadata) -> u32 {
    metadata
        .modified()
        .ok()
        .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
        .and_then(|since| u32::try_from(since.as_secs()).ok())
        .unwrap_or(0)
}

/// Hashes a found file, off the engine's task. A file another program holds or locks, such as one it is still writing
/// on Windows, is tried again for a few seconds before it is left for the next scan; one we may not read is not.
pub fn hash(found: &Found) -> io::Result<FileHashes> {
    let mut attempt = 1;
    loop {
        match file_hasher::hash_file(&found.path) {
            Err(e)
                if attempt < HASH_ATTEMPTS
                    && !matches!(
                        e.kind(),
                        io::ErrorKind::NotFound
                            | io::ErrorKind::PermissionDenied
                            | io::ErrorKind::UnexpectedEof
                            | io::ErrorKind::Interrupted
                    ) =>
            {
                tracing::debug!("Cannot read {} yet: {e}", found.path.display());
                attempt = attempt.saturating_add(1);
                std::thread::sleep(HASH_RETRY_DELAY);
            }
            hashed => return hashed,
        }
    }
}
