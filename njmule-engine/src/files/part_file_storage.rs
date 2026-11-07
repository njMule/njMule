// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The data of a download on the disk: writes, hashes of parts and blocks, saves of its metadata and the move of the
//! finished file, all in order on a thread of the file's own, each reported back when done.

use std::fs::{self, File, OpenOptions};
use std::io::{self, ErrorKind, Read, Seek, SeekFrom, Write};
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, PoisonError, mpsc};
use std::time::{Duration, Instant};

use md4::{Digest, Md4};
use sha1::Sha1;
use tokio::sync::mpsc::UnboundedSender;

use crate::files::hash::ed2k;
use crate::files::part_file;
use crate::persist::format::part_met::Contents;
use crate::security::path_sanitizer;

const READ_CHUNK: usize = 256 * 1024;

/// What the disk thread did, for the download of `download`.
#[derive(Debug)]
pub struct DiskDone {
    pub download: u64,
    pub work: Done,
}

#[derive(Debug)]
pub enum Done {
    /// Data written, from the source at `source` when known.
    Written {
        start: u64,
        end: u64,
        source: Option<IpAddr>,
        result: Result<(), String>,
    },
    PartHashed {
        part: usize,
        recheck: bool,
        result: Result<[u8; 16], String>,
    },
    BlocksHashed {
        part: usize,
        result: Result<Vec<[u8; 20]>, String>,
    },
    Saved {
        generation: u64,
        result: Result<(), String>,
    },
    Finished(Result<PathBuf, FinishFailure>),
}

/// Why the finished file could not be moved; `held` when the data file itself could not be, as while another program
/// holds it.
#[derive(Debug)]
pub struct FinishFailure {
    pub reason: String,
    pub held: bool,
}

impl FinishFailure {
    fn of(e: &io::Error) -> Self {
        Self { reason: e.to_string(), held: false }
    }
}

enum Work {
    Write { start: u64, data: Vec<u8>, source: Option<IpAddr> },
    HashPart { size: u64, part: usize, recheck: bool },
    HashBlocks { size: u64, part: usize },
    SaveMet { met: PathBuf, contents: Box<Contents>, generation: u64 },
    Finish { directory: PathBuf, name: String },
    Close,
}

type AfterClose = Box<dyn FnOnce() + Send>;

/// The end of a disk thread, once its last work is done: waited for, or work run once it comes.
#[derive(Clone, Default)]
pub struct DiskClosed(Arc<ClosedState>);

#[derive(Default)]
struct ClosedState {
    state: Mutex<(bool, Vec<AfterClose>)>,
    signal: Condvar,
}

impl DiskClosed {
    pub fn is_closed(&self) -> bool {
        self.0.state.lock().unwrap_or_else(PoisonError::into_inner).0
    }

    /// Waits at most `timeout` for the end; whether it came.
    pub fn wait(&self, timeout: Duration) -> bool {
        let deadline = Instant::now().checked_add(timeout);
        let mut state = self.0.state.lock().unwrap_or_else(PoisonError::into_inner);
        while !state.0 {
            let left = deadline.map_or(timeout, |deadline| deadline.saturating_duration_since(Instant::now()));
            if left.is_zero() {
                return false;
            }
            state = self.0.signal.wait_timeout(state, left).unwrap_or_else(PoisonError::into_inner).0;
        }
        true
    }

    /// Runs `work` once the thread has ended: at once, on this thread, when it has already.
    pub fn then(&self, work: impl FnOnce() + Send + 'static) {
        let mut state = self.0.state.lock().unwrap_or_else(PoisonError::into_inner);
        if state.0 {
            drop(state);
            work();
        } else {
            state.1.push(Box::new(work));
        }
    }

    // The work asked for runs before the end is told, so that whoever waits finds it done.
    fn close(&self) {
        loop {
            let after = {
                let mut state = self.0.state.lock().unwrap_or_else(PoisonError::into_inner);
                if state.1.is_empty() {
                    state.0 = true;
                    break;
                }
                std::mem::take(&mut state.1)
            };
            for work in after {
                work();
            }
        }
        self.0.signal.notify_all();
    }
}

// Ends the thread's watch however the thread ends.
struct CloseOnDrop(DiskClosed);

impl Drop for CloseOnDrop {
    fn drop(&mut self) {
        self.0.close();
    }
}

/// A download's data file, open on its own thread until it is finished or closed.
pub struct PartFileStorage {
    work: Option<mpsc::Sender<Work>>,
    saves: Arc<AtomicU64>,
    closed: DiskClosed,
}

impl PartFileStorage {
    /// Opens the data file, creating an empty one when missing; what the thread does goes to `done`.
    pub fn open(path: &Path, download: u64, done: UnboundedSender<DiskDone>) -> io::Result<Self> {
        let file = OpenOptions::new().read(true).write(true).create(true).truncate(false).open(path)?;
        let (work, queue) = mpsc::channel();
        let saves = Arc::new(AtomicU64::new(0));
        let latest = saves.clone();
        let path = path.to_path_buf();
        let closed = DiskClosed::default();
        let ending = CloseOnDrop(closed.clone());
        std::thread::Builder::new()
            .name(format!("njmule-disk-{}", path.file_name().unwrap_or_default().to_string_lossy()))
            .spawn(move || {
                let _ending = ending;
                run(file, &path, &queue, &latest, download, &done);
            })?;
        Ok(Self { work: Some(work), saves, closed })
    }

    /// The end of the thread, after its last work.
    pub fn closed(&self) -> DiskClosed {
        self.closed.clone()
    }

    /// Whether the file still takes work: not after [`finish`](Self::finish) or [`close`](Self::close).
    pub fn is_open(&self) -> bool {
        self.work.is_some()
    }

    /// Writes `data` at `start`; `source` is told back with the outcome.
    pub fn write(&self, start: u64, data: Vec<u8>, source: Option<IpAddr>) {
        self.send(Work::Write { start, data, source });
    }

    /// The MD4 of a data part, read back after the writes queued before it.
    pub fn hash_part(&self, size: u64, part: usize, recheck: bool) {
        self.send(Work::HashPart { size, part, recheck });
    }

    /// The SHA-1 of each 180 KiB block of `part`, as AICH hashes them.
    pub fn hash_blocks(&self, size: u64, part: usize) {
        self.send(Work::HashBlocks { size, part });
    }

    /// Writes `contents` to `met` once the writes queued before are flushed; skipped when a newer save follows.
    pub fn save_met(&self, met: PathBuf, contents: Contents) -> u64 {
        let generation = self.saves.fetch_add(1, Ordering::SeqCst).saturating_add(1);
        self.send(Work::SaveMet { met, contents: Box::new(contents), generation });
        generation
    }

    /// Skips the saves queued so far.
    pub fn cancel_saves(&self) {
        self.saves.fetch_add(1, Ordering::SeqCst);
    }

    /// Flushes, closes and moves the finished file into `directory` under `name`, after the queued writes.
    pub fn finish(&mut self, directory: PathBuf, name: String) {
        self.send(Work::Finish { directory, name });
        self.work = None;
    }

    /// Closes the file after the queued work.
    pub fn close(&mut self) {
        self.send(Work::Close);
        self.work = None;
    }

    fn send(&self, work: Work) {
        if let Some(sender) = &self.work {
            // A thread gone has failed its last work, which it reported.
            let _ = sender.send(work);
        }
    }
}

impl Drop for PartFileStorage {
    fn drop(&mut self) {
        self.close();
    }
}

fn run(
    mut file: File,
    path: &Path,
    queue: &mpsc::Receiver<Work>,
    latest: &AtomicU64,
    download: u64,
    done: &UnboundedSender<DiskDone>,
) {
    let report = |work: Done| {
        let _ = done.send(DiskDone { download, work });
    };
    while let Ok(work) = queue.recv() {
        match work {
            Work::Write { start, data, source } => {
                let end = start.saturating_add(u64::try_from(data.len()).unwrap_or(u64::MAX));
                let result = write_at(&mut file, start, &data).map_err(|e| e.to_string());
                report(Done::Written { start, end, source, result });
            }
            Work::HashPart { size, part, recheck } => {
                let result = hash_part(&mut file, size, part).map_err(|e| e.to_string());
                report(Done::PartHashed { part, recheck, result });
            }
            Work::HashBlocks { size, part } => {
                let result = hash_blocks(&mut file, size, part).map_err(|e| e.to_string());
                report(Done::BlocksHashed { part, result });
            }
            Work::SaveMet { met, contents, generation } => {
                if latest.load(Ordering::SeqCst) != generation {
                    continue;
                }
                // The data it tells as written reaches the disk first: else a crash could lose the data and keep the
                // part.met.
                let result = file
                    .sync_data()
                    .and_then(|()| part_file::write_met(&met, &contents, path))
                    .map_err(|e| e.to_string());
                report(Done::Saved { generation, result });
            }
            Work::Finish { directory, name } => {
                let result = file.sync_all().map_err(|e| FinishFailure::of(&e));
                drop(file);
                report(Done::Finished(result.and_then(|()| finish(path, &directory, &name))));
                return;
            }
            Work::Close => {
                let _ = file.sync_all();
                return;
            }
        }
    }
}

fn write_at(file: &mut File, start: u64, data: &[u8]) -> io::Result<()> {
    file.seek(SeekFrom::Start(start))?;
    file.write_all(data)
}

fn hash_part(file: &mut File, size: u64, part: usize) -> io::Result<[u8; 16]> {
    let range = part_file::part_range(size, part);
    let mut md4 = Md4::new();
    read_range(file, range.start, range.end, READ_CHUNK, |chunk| md4.update(chunk))?;
    Ok(md4.finalize().into())
}

fn hash_blocks(file: &mut File, size: u64, part: usize) -> io::Result<Vec<[u8; 20]>> {
    let range = part_file::part_range(size, part);
    let mut blocks = Vec::new();
    let mut at = range.start;
    while at < range.end {
        let end = range.end.min(at.saturating_add(ed2k::EMBLOCKSIZE));
        let mut sha1 = Sha1::new();
        read_range(file, at, end, READ_CHUNK, |chunk| sha1.update(chunk))?;
        blocks.push(sha1.finalize().into());
        at = end;
    }
    Ok(blocks)
}

// Bytes of a sparse part never written read as zeros; past the end there is nothing, so the hash cannot match.
fn read_range(file: &mut File, start: u64, end: u64, chunk: usize, mut take: impl FnMut(&[u8])) -> io::Result<()> {
    file.seek(SeekFrom::Start(start))?;
    let mut buffer = vec![0u8; chunk];
    let mut left = end.saturating_sub(start);
    while left > 0 {
        let wanted = usize::try_from(left).map_or(chunk, |left| left.min(chunk));
        let Some(slice) = buffer.get_mut(..wanted) else {
            break;
        };
        let read = file.read(slice)?;
        if read == 0 {
            break;
        }
        take(slice.get(..read).unwrap_or_default());
        left = left.saturating_sub(u64::try_from(read).unwrap_or(left));
    }
    Ok(())
}

// Reserved and moved in one step, so two finished files never take the same name; across volumes, a hidden copy is
// flushed and renamed over the reserved name, so a crash never leaves a truncated file to share. A data file that
// cannot be moved but is there is held, as by a virus scanner or an indexer that opened it.
fn finish(data: &Path, directory: &Path, name: &str) -> Result<PathBuf, FinishFailure> {
    fs::create_dir_all(directory).map_err(|e| FinishFailure::of(&e))?;
    let target = path_sanitizer::reserve(directory, name).map_err(|e| FinishFailure::of(&e))?;
    match fs::rename(data, &target) {
        Ok(()) => Ok(target),
        Err(e) if e.kind() == ErrorKind::CrossesDevices => {
            let mut hidden = target.file_name().unwrap_or_default().to_os_string();
            hidden.push(".tmp");
            let copy = target.with_file_name(hidden);
            let copied = fs::copy(data, &copy)
                .and_then(|_| File::options().write(true).open(&copy)?.sync_all())
                .and_then(|()| fs::rename(&copy, &target));
            if let Err(e) = copied {
                let _ = fs::remove_file(&copy);
                let _ = fs::remove_file(&target);
                return Err(FinishFailure::of(&e));
            }
            if let Err(e) = fs::remove_file(data) {
                tracing::warn!("Cannot delete {} after copying it to {}: {e}", data.display(), target.display());
            }
            Ok(target)
        }
        Err(e) => {
            let _ = fs::remove_file(&target);
            Err(FinishFailure { reason: e.to_string(), held: e.kind() != ErrorKind::NotFound })
        }
    }
}
