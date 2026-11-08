// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The shared files of the network: the folders read off the engine's task at the start, every ten minutes and when
//! they change, and the files new to them hashed one after the other on a thread of their own.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::mpsc as std_mpsc;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use notify::{EventKind, RecommendedWatcher, RecursiveMode, Watcher};
use tokio::sync::mpsc;

use super::{Network, Tick};
use crate::api::log_line::Level as LogLevel;
use crate::api::shared_directory::SharedDirectory;
use crate::api::shared_file_info::SharedFileInfo;
use crate::api::upload_priority::UploadPriority;
use crate::files::hash::file_hasher::FileHashes;
use crate::files::shared_files::{self, Changes, Found, Listing, Sharing};
use crate::persist::format::known_met::KnownFile;
use crate::persist::format::known2_met;
use crate::upload::upload_manager::{FileCounts, UploadManager};

/// A file that changed while it was hashed is looked at again this long after, once a copy is done.
const RESCAN_DELAY: Duration = Duration::from_secs(5);
/// A change in a shared folder is looked at this long after the last of a burst, such as a file being copied.
const WATCH_DELAY: Duration = Duration::from_secs(5);

/// What the network keeps of its scans.
pub(super) struct SharesSide {
    // The files to hash, which the hasher takes one after the other.
    to_hash: std_mpsc::Sender<Found>,
    // Whether a scan runs, and whether another was asked for meanwhile.
    scanning: bool,
    again: bool,
    next_scan: Instant,
    // When the folders last changed, a scan follows a moment later; the rescans every ten minutes go on meanwhile.
    changed_scan: Option<Instant>,
    // The folders read, watched for changes; none where the system watches nothing.
    watch: Option<Arc<Mutex<Watch>>>,
    // The incoming and private folders of the last scan, whose change asks for another.
    folders: (Vec<PathBuf>, Vec<PathBuf>),
    // The upload priorities chosen for the files shared, which the upload queue reads; and what it counted of them
    // since the shared files last took it.
    priorities: Arc<Mutex<HashMap<[u8; 16], UploadPriority>>>,
    counted: Arc<Mutex<HashMap<[u8; 16], Tally>>>,
}

// Requests, slots and bytes of a file.
type Tally = (u64, u64, u64);

// The watches of the folders read, which each scan updates off the engine's task: a folder watch takes a handle of the
// system, set up in a call each.
struct Watch {
    watcher: RecommendedWatcher,
    watched: HashSet<PathBuf>,
}

impl Watch {
    // Each change but a read asks for a scan; reads come from uploads and hashes.
    fn start(ticks: mpsc::UnboundedSender<Tick>) -> notify::Result<Self> {
        let watcher = notify::recommended_watcher(move |event: notify::Result<notify::Event>| {
            if event.is_ok_and(|event| !matches!(event.kind, EventKind::Access(_))) {
                let _ = ticks.send(Tick::SharesChanged);
            }
        })?;
        Ok(Self { watcher, watched: HashSet::new() })
    }

    // Each folder read is watched alone, not its subfolders, which a tree lists when they are shared; the watch of a
    // folder no longer read ends.
    fn follow(&mut self, folders: &[PathBuf]) {
        let read: HashSet<&PathBuf> = folders.iter().collect();
        let gone: Vec<PathBuf> = self.watched.iter().filter(|dir| !read.contains(dir)).cloned().collect();
        for dir in gone {
            self.watched.remove(&dir);
            let _ = self.watcher.unwatch(&dir);
        }
        for dir in folders {
            if self.watched.contains(dir) {
                continue;
            }
            match self.watcher.watch(dir, RecursiveMode::NonRecursive) {
                Ok(()) => {
                    self.watched.insert(dir.clone());
                }
                Err(e) => tracing::debug!("Cannot watch {}: {e}", dir.display()),
            }
        }
    }
}

// Where the upload queue counts, for the shared files to take.
struct Counter(Arc<Mutex<HashMap<[u8; 16], Tally>>>);

impl Counter {
    fn add(&self, file: &[u8; 16], requests: u64, accepted: u64, bytes: u64) {
        let mut counted = self.0.lock().unwrap_or_else(PoisonError::into_inner);
        let tally = counted.entry(*file).or_default();
        *tally = (tally.0.saturating_add(requests), tally.1.saturating_add(accepted), tally.2.saturating_add(bytes));
    }
}

impl FileCounts for Counter {
    fn asked(&mut self, file: &[u8; 16]) {
        self.add(file, 1, 0, 0);
    }

    fn accepted(&mut self, file: &[u8; 16]) {
        self.add(file, 0, 1, 0);
    }

    fn sent(&mut self, file: &[u8; 16], bytes: u64) {
        self.add(file, 0, 0, bytes);
    }
}

impl SharesSide {
    /// Starts the hasher, whose hashes come back as ticks. It runs on a thread of its own, not on one of the runtime's,
    /// so that a large file being hashed holds up neither the scans nor the engine's stop.
    pub(super) fn new(ticks: mpsc::UnboundedSender<Tick>) -> Self {
        let watch = Watch::start(ticks.clone())
            .map_err(|e| tracing::warn!("Changes in the shared folders show at the next rescan only: {e}"))
            .ok()
            .map(|watch| Arc::new(Mutex::new(watch)));
        let (to_hash, files) = std_mpsc::channel::<Found>();
        let started = std::thread::Builder::new().name("njmule-hasher".to_owned()).spawn(move || {
            // Ends once the network is gone.
            for found in files {
                let hashes = shared_files::hash(&found).map_err(|e| e.to_string());
                if ticks.send(Tick::Hashed(found, hashes)).is_err() {
                    return;
                }
            }
        });
        if let Err(e) = started {
            tracing::error!("Cannot start the hasher, new files are not shared: {e}");
        }
        Self {
            to_hash,
            scanning: false,
            again: false,
            next_scan: Instant::now(),
            changed_scan: None,
            watch,
            folders: (Vec::new(), Vec::new()),
            priorities: Arc::default(),
            counted: Arc::default(),
        }
    }

    /// Lets the upload queue read the priorities of the files and count their requests, slots and bytes.
    pub(super) fn wire(&self, uploads: &mut UploadManager) {
        let priorities = Arc::clone(&self.priorities);
        uploads.set_priorities(move |file| {
            priorities.lock().unwrap_or_else(PoisonError::into_inner).get(file).copied().unwrap_or(UploadPriority::Auto)
        });
        uploads.count_in(Box::new(Counter(Arc::clone(&self.counted))));
    }
}

impl Network {
    /// Looks at the shared folders again, off the engine's task; a scan asked for while one runs follows it.
    pub(super) fn scan_shares(&mut self) {
        if self.shares.scanning {
            self.shares.again = true;
            return;
        }
        self.shares.scanning = true;
        self.shares.next_scan = later(shared_files::RESCAN_INTERVAL);
        self.shares.changed_scan = None;
        let (incoming, private) = self.share_folders();
        self.shared_files.set_folders(incoming.clone(), private.clone());
        self.shares.folders = (incoming, private);
        let places = self.shared_files.places();
        let ticks = self.ticks.clone();
        let watch = self.shares.watch.clone();
        tokio::task::spawn_blocking(move || {
            let listing = shared_files::list(&places);
            if let Some(watch) = watch {
                watch.lock().unwrap_or_else(PoisonError::into_inner).follow(&listing.folders);
            }
            let _ = ticks.send(Tick::Listed(listing));
        });
    }

    /// A shared folder changed: a scan follows once the changes stop for a moment.
    pub(super) fn shares_watched(&mut self) {
        self.shares.changed_scan = Some(later(WATCH_DELAY));
    }

    pub(super) fn shares_listed(&mut self, listing: Listing) {
        self.shares.scanning = false;
        let changes = self.shared_files.scanned(listing);
        self.shares_changed(changes);
        if std::mem::take(&mut self.shares.again) {
            self.scan_shares();
        }
    }

    pub(super) fn shares_hashed(&mut self, found: Found, hashes: Result<FileHashes, String>) {
        let changes = self.shared_files.hashed(found, hashes);
        for sharing in &changes.sharing {
            if let Sharing::Shared(file) = sharing {
                tracing::info!("Sharing {}", file.name);
            }
        }
        self.shares_changed(changes);
    }

    /// Each second: a scan when one is due, the shared folders changed or the incoming or private folders are others,
    /// and the known files saved when they changed.
    pub(super) fn shares_second(&mut self) {
        let now = Instant::now();
        if now >= self.shares.next_scan
            || self.shares.changed_scan.is_some_and(|due| now >= due)
            || self.share_folders() != self.shares.folders
        {
            self.scan_shares();
        }
        self.shared_files.remember_downloaded(self.applied.remember_downloaded_files);
        self.take_counts();
        if self.shared_files.is_unsaved()
            && let Err(e) = self.shared_files.save()
        {
            tracing::warn!("Cannot save the known files: {e}");
        }
    }

    /// Remembers another client's records of its files, and their trees, then looks at the folders again so that the
    /// files they name are shared without hashing them; answers how many records were new.
    pub(super) fn add_known_files(&mut self, files: &[KnownFile], trees: &[known2_met::Entry]) -> usize {
        let added = self.shared_files.add_known(files, trees);
        if added > 0 {
            self.scan_shares();
        }
        added
    }

    pub(super) fn shared_directories(&self) -> Vec<SharedDirectory> {
        self.shared_files.shared_directories()
    }

    /// Shares a folder from now on, with its subfolders or alone; why not for a private folder.
    pub(super) fn share_directory(&mut self, dir: &Path, subdirectories: bool) -> Result<(), String> {
        let (incoming, private) = self.share_folders();
        self.shared_files.set_folders(incoming, private);
        self.shared_files.add_directory(dir, subdirectories)?;
        self.scan_shares();
        Ok(())
    }

    pub(super) fn unshare_directory(&mut self, dir: &Path) -> Result<(), String> {
        self.shared_files.remove_directory(dir).map_err(|e| format!("Cannot save the shared folders: {e}"))?;
        self.scan_shares();
        Ok(())
    }

    /// The files shared, with their priority and what they were asked for and sent.
    pub(super) fn shared_file_infos(&mut self) -> Vec<SharedFileInfo> {
        self.take_counts();
        let files = &self.shared_files;
        files
            .files()
            .map(|file| SharedFileInfo {
                hash: file.hash,
                name: file.name.clone(),
                size: file.size,
                path: file.path.clone(),
                priority: files.priority(&file.hash),
                statistics: files.statistics(&file.hash),
            })
            .collect()
    }

    /// Renames a shared file in its folder; false for a file not shared, why not for a name never shared or a move
    /// that fails.
    pub(super) fn rename_shared_file(&mut self, hash: &[u8; 16], name: &str) -> Result<bool, String> {
        match self.shared_files.rename(hash, name) {
            Ok(Some((file, changes))) => {
                if !changes.sharing.is_empty() {
                    self.user_log(LogLevel::Info, format!("Renamed to {}", file.path.display()));
                }
                self.shares_changed(changes);
                Ok(true)
            }
            Ok(None) => Ok(false),
            Err(e) => Err(e.to_string()),
        }
    }

    /// Sets the upload priority of a shared file; false for a file not shared.
    pub(super) fn set_upload_priority(&mut self, hash: &[u8; 16], priority: UploadPriority) -> bool {
        let set = self.shared_files.set_priority(hash, priority);
        if set {
            self.follow_priority(hash);
        }
        set
    }

    pub(super) fn shares_changed(&mut self, changes: Changes) {
        if changes.sharing.iter().any(|sharing| matches!(sharing, Sharing::Shared(_))) {
            self.publisher.changed(None);
        }
        for sharing in &changes.sharing {
            match sharing {
                Sharing::Shared(file) | Sharing::Unshared(file) => self.follow_priority(&file.hash),
            }
        }
        for found in changes.to_hash {
            if self.shares.to_hash.send(found).is_err() {
                tracing::debug!("The hasher is gone");
            }
        }
        if changes.rescan {
            self.shares.next_scan = self.shares.next_scan.min(later(RESCAN_DELAY));
        }
    }

    // The upload queue reads the priority chosen for a file shared; none, it takes the automatic one.
    fn follow_priority(&self, hash: &[u8; 16]) {
        let priority = self.shared_files.get(hash).map(|_| self.shared_files.priority(hash));
        let mut priorities = self.shares.priorities.lock().unwrap_or_else(PoisonError::into_inner);
        match priority {
            Some(priority) if priority != UploadPriority::Auto => priorities.insert(*hash, priority),
            _ => priorities.remove(hash),
        };
    }

    // What the upload queue counted, into the statistics of the shared files.
    fn take_counts(&mut self) {
        let counted = std::mem::take(&mut *self.shares.counted.lock().unwrap_or_else(PoisonError::into_inner));
        for (file, (requests, accepted, bytes)) in counted {
            self.shared_files.counted(&file, requests, accepted, bytes);
        }
    }

    // The folders whose files are shared besides the lists: the incoming one of the preferences and those of the
    // categories; and the temp folder, never shared.
    fn share_folders(&self) -> (Vec<PathBuf>, Vec<PathBuf>) {
        let dirs = &self.shared.dirs;
        let mut incoming = vec![self.applied.effective_incoming_dir(dirs)];
        incoming.extend(self.categories.iter().filter_map(|category| category.incoming.clone()));
        (incoming, vec![self.applied.effective_temp_dir(dirs)])
    }
}

fn later(wait: Duration) -> Instant {
    let now = Instant::now();
    now.checked_add(wait).unwrap_or(now)
}
