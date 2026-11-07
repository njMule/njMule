// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A download queue over a temp directory for the Java engine's tests of it: its disk work brought back and its
//! events heard while the test waits.

#![allow(dead_code, clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::arithmetic_side_effects)]

use std::collections::VecDeque;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use njmule_engine::download::download_queue::{DownloadQueue, QueueEvent};
use njmule_engine::files::part_file::PartFile;
use njmule_engine::files::part_file_storage::DiskDone;
use tokio::sync::mpsc;

pub struct QueueLab {
    pub dir: tempfile::TempDir,
    pub queue: DownloadQueue,
    pub min_free: Arc<AtomicU64>,
    disk: mpsc::UnboundedReceiver<DiskDone>,
    events: VecDeque<QueueEvent>,
}

impl QueueLab {
    pub fn load() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let min_free = Arc::new(AtomicU64::new(0));
        let (done, disk) = mpsc::unbounded_channel();
        let incoming = dir.path().join("incoming");
        let free = Arc::clone(&min_free);
        let queue = DownloadQueue::load(
            &dir.path().join("temp"),
            Box::new(move |_: &PartFile| incoming.clone()),
            Box::new(move || free.load(Ordering::Relaxed)),
            done,
        )
        .unwrap();
        Self { dir, queue, min_free, disk, events: VecDeque::new() }
    }

    /// The next event `wanted` takes within `wait`, the others passed over, while the disk work comes back and the
    /// queue's clock goes on.
    pub fn next<T>(&mut self, wait: Duration, wanted: impl Fn(&QueueEvent) -> Option<T>) -> Option<T> {
        let deadline = Instant::now() + wait;
        loop {
            self.events.extend(self.queue.take_events());
            while let Some(event) = self.events.pop_front() {
                if let Some(found) = wanted(&event) {
                    return Some(found);
                }
            }
            if Instant::now() >= deadline {
                return None;
            }
            self.queue.tick(Instant::now());
            match self.disk.try_recv() {
                Ok(done) => self.queue.disk_done(done),
                Err(_) => std::thread::sleep(Duration::from_millis(5)),
            }
        }
    }

    /// Brings back the disk work done so far.
    pub fn settle(&mut self) {
        while let Ok(done) = self.disk.try_recv() {
            self.queue.disk_done(done);
        }
        self.events.extend(self.queue.take_events());
    }

    /// The queue closed and loaded again over the same directory, as at a restart.
    pub fn reload(&mut self) {
        self.queue.close();
        self.settle();
        let (done, disk) = mpsc::unbounded_channel();
        let incoming = self.dir.path().join("incoming");
        let free = Arc::clone(&self.min_free);
        self.queue = DownloadQueue::load(
            &self.dir.path().join("temp"),
            Box::new(move |_: &PartFile| incoming.clone()),
            Box::new(move || free.load(Ordering::Relaxed)),
            done,
        )
        .unwrap();
        self.disk = disk;
        self.events.clear();
    }
}

/// A directory nobody may write into, nor move a file out of, until this goes: what another program holding its files
/// does to them on Windows.
#[cfg(unix)]
pub struct ReadOnly(std::path::PathBuf);

#[cfg(unix)]
impl ReadOnly {
    pub fn of(directory: &std::path::Path) -> Self {
        set_mode(directory, 0o555);
        Self(directory.to_path_buf())
    }
}

#[cfg(unix)]
impl Drop for ReadOnly {
    fn drop(&mut self) {
        set_mode(&self.0, 0o755);
    }
}

#[cfg(unix)]
fn set_mode(directory: &std::path::Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(directory, std::fs::Permissions::from_mode(mode)).unwrap();
}
