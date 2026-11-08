// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The shared files of the network: the folders read off the engine's task at the start, every ten minutes and when
//! they change, and the files new to them hashed one after the other on a thread of their own.

use std::path::{Path, PathBuf};
use std::sync::mpsc as std_mpsc;
use std::time::{Duration, Instant};

use tokio::sync::mpsc;

use super::{Network, Tick};
use crate::api::shared_directory::SharedDirectory;
use crate::files::hash::file_hasher::FileHashes;
use crate::files::shared_files::{self, Changes, Found, Listing, Sharing};

/// A file that changed while it was hashed is looked at again this long after, once a copy is done.
const RESCAN_DELAY: Duration = Duration::from_secs(5);

/// What the network keeps of its scans.
pub(super) struct SharesSide {
    // The files to hash, which the hasher takes one after the other.
    to_hash: std_mpsc::Sender<Found>,
    // Whether a scan runs, and whether another was asked for meanwhile.
    scanning: bool,
    again: bool,
    next_scan: Instant,
    // The incoming and private folders of the last scan, whose change asks for another.
    folders: (Vec<PathBuf>, Vec<PathBuf>),
}

impl SharesSide {
    /// Starts the hasher, whose hashes come back as ticks. It runs on a thread of its own, not on one of the runtime's,
    /// so that a large file being hashed holds up neither the scans nor the engine's stop.
    pub(super) fn new(ticks: mpsc::UnboundedSender<Tick>) -> Self {
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
        Self { to_hash, scanning: false, again: false, next_scan: Instant::now(), folders: (Vec::new(), Vec::new()) }
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
        let (incoming, private) = self.share_folders();
        self.shared_files.set_folders(incoming.clone(), private.clone());
        self.shares.folders = (incoming, private);
        let places = self.shared_files.places();
        let ticks = self.ticks.clone();
        tokio::task::spawn_blocking(move || {
            let _ = ticks.send(Tick::Listed(shared_files::list(&places)));
        });
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

    /// Each second: a scan when one is due or the incoming or private folders changed, and the known files saved when
    /// they changed.
    pub(super) fn shares_second(&mut self) {
        if Instant::now() >= self.shares.next_scan || self.share_folders() != self.shares.folders {
            self.scan_shares();
        }
        if self.shared_files.is_unsaved()
            && let Err(e) = self.shared_files.save()
        {
            tracing::warn!("Cannot save the known files: {e}");
        }
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

    fn shares_changed(&mut self, changes: Changes) {
        if changes.sharing.iter().any(|sharing| matches!(sharing, Sharing::Shared(_))) {
            self.publisher.changed(None);
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
