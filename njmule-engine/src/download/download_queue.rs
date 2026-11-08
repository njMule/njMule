// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The downloads: their part files in the temp directory, the data arriving from sources, the verification of each
//! finished part and the move of finished files to the incoming directory. Disk work runs on each file's own thread
//! and comes back through [`DownloadQueue::disk_done`].

use std::collections::{HashMap, HashSet};
use std::fs;
use std::io;
use std::net::IpAddr;
use std::ops::Range;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use indexmap::IndexMap;
use tokio::sync::mpsc::UnboundedSender;

use crate::api::download_info::DownloadPriority;
use crate::api::ed2k_link::{self, FileLink};
use crate::download::corruption_black_box::CorruptionBlackBox;
use crate::files::hash::ed2k;
use crate::files::part_file::{self, KnownSource, PartFile, Status};
use crate::files::part_file_storage::{DiskClosed, DiskDone, Done, FinishFailure, PartFileStorage};
use crate::persist::atomic_files;
use crate::persist::format::part_met;
use crate::security::path_sanitizer;
use crate::util::instants;

/// Part files are saved this often while their progress changes.
pub const SAVE_INTERVAL: Duration = Duration::from_secs(60);
/// The free space of the temp disk is looked at this often.
pub const SPACE_CHECK: Duration = Duration::from_secs(10);
/// How long the files of a cancelled download are kept, so that the cancel can be undone.
pub const CANCEL_GRACE: Duration = Duration::from_secs(15);

// A write that fails while the temp disk has less room than this found it full: the downloads wait for room.
const FULL_DISK_RESERVE: u64 = 64 * 1024 * 1024;
// A finished file held by another program is moved again after 2, 4, ... 64 seconds, two minutes in all.
const FINISH_RETRIES: u32 = 6;
const FINISH_RETRY_DELAY: Duration = Duration::from_secs(2);
// How long closing waits for the last saves and writes.
const CLOSE_WAIT: Duration = Duration::from_secs(30);
const IDLE_SAVED_EVERY: u64 = 10 * 60;

// The met of a cancelled download kept for an undo: no start loads it, and the next start deletes it.
const SET_ASIDE: &str = ".cancelled";

/// Where the finished file of a download goes.
pub type IncomingDir = Box<dyn Fn(&PartFile) -> PathBuf + Send>;
/// The bytes the temp disk must keep free while downloads write to it; 0 for no minimum.
pub type MinFree = Box<dyn Fn() -> u64 + Send>;

/// What the queue tells the downloads.
#[derive(Debug)]
pub enum QueueEvent {
    Added([u8; 16]),
    Progress([u8; 16]),
    /// The finished file is now at `path`; `file` has its hashes.
    Completed {
        file: Box<PartFile>,
        path: PathBuf,
    },
    Failed {
        hash: [u8; 16],
        reason: String,
    },
    /// A part failed its check and is missing again; AICH recovery data may save its good blocks.
    Corrupt {
        hash: [u8; 16],
        part: usize,
    },
    /// The sources at `ips`, IPv6 ones by their /64, were found sending corrupt data.
    CorruptSenders {
        hash: [u8; 16],
        ips: Vec<IpAddr>,
    },
    /// The temp disk has dropped below (false), or come back above (true), the free space downloads need.
    DiskSpace(bool),
}

/// One download with its storage, the sources its links gave and the parts verified this run.
pub struct Download {
    id: u64,
    pub file: PartFile,
    storage: PartFileStorage,
    link_sources: Vec<ed2k_link::Source>,
    verified: Vec<bool>,
    dirty: bool,
    finishing: bool,
    // The generation of its last part.met save: a failed one is made again only while no newer one follows.
    last_save: u64,
    // Moves of its finished file tried again because another program held it, and when the next is due.
    finish_retries: u32,
    finish_again: Option<Instant>,
    // Parts that failed their check and are being downloaded again, the bytes written into each since it was last
    // hashed, and those being hashed.
    corrupted: Vec<bool>,
    since_ich: HashMap<usize, u64>,
    ich_running: Vec<bool>,
    // Parts whose check is on its way.
    checking: Vec<bool>,
    black_box: CorruptionBlackBox,
    // The queue's: downloads wait while the temp disk is short of space.
    space_short: Arc<AtomicBool>,
}

impl Download {
    pub fn hash(&self) -> [u8; 16] {
        self.file.hash()
    }

    pub fn is_active(&self) -> bool {
        self.file.status() == Status::Downloading && !self.finishing && !self.space_short.load(Ordering::Relaxed)
    }

    /// Whether its finished file is being moved to the incoming directory.
    pub fn is_finishing(&self) -> bool {
        self.finishing
    }

    /// Sources given by links; the queue does not connect to them itself.
    pub fn link_sources(&self) -> &[ed2k_link::Source] {
        &self.link_sources
    }

    /// The parts whose hash was checked: the ones other clients may download from us.
    pub fn verified_parts(&self) -> &[bool] {
        &self.verified
    }

    /// The end of its disk thread.
    pub fn disk_closed(&self) -> DiskClosed {
        self.storage.closed()
    }
}

// A cancelled download: its storage closes and its met is set aside, then its files go at the end of the grace.
struct Withdrawn {
    download: Download,
    until: Instant,
}

pub struct DownloadQueue {
    temp_dir: PathBuf,
    incoming_dir: IncomingDir,
    min_free: MinFree,
    done: UnboundedSender<DiskDone>,
    downloads: IndexMap<[u8; 16], Download>,
    // Cancelled downloads whose files are kept for a while, so that the cancel can be undone.
    withdrawn: HashMap<[u8; 16], Withdrawn>,
    grace: Duration,
    // The files of cancelled downloads, deleted once their disk threads are done.
    cancelled: Vec<JoinHandle<()>>,
    // The block hashes recovery data proved, by download and part, while ours are read back.
    recovering: HashMap<(u64, usize), Vec<[u8; 20]>>,
    space_short: Arc<AtomicBool>,
    // Whether a write found the disk full: then downloads wait for the reserve at least, whatever the minimum is.
    disk_full: bool,
    next_id: u64,
    next_save: Instant,
    next_space_check: Instant,
    events: Vec<QueueEvent>,
}

impl DownloadQueue {
    /// Loads the part files of `temp_dir`, also those of which only the backup is left; unreadable ones are left alone
    /// and logged. Each finished file goes where `incoming_dir` says; downloads wait while the temp disk has less free
    /// space than `min_free`.
    pub fn load(
        temp_dir: &Path,
        incoming_dir: IncomingDir,
        min_free: MinFree,
        done: UnboundedSender<DiskDone>,
    ) -> io::Result<Self> {
        fs::create_dir_all(temp_dir)?;
        let now = Instant::now();
        let mut queue = Self {
            temp_dir: temp_dir.to_path_buf(),
            incoming_dir,
            min_free,
            done,
            downloads: IndexMap::new(),
            withdrawn: HashMap::new(),
            grace: CANCEL_GRACE,
            cancelled: Vec::new(),
            recovering: HashMap::new(),
            space_short: Arc::new(AtomicBool::new(false)),
            disk_full: false,
            next_id: 1,
            next_save: instants::later(now, SAVE_INTERVAL),
            next_space_check: instants::later(now, SPACE_CHECK),
            events: Vec::new(),
        };
        let mut numbers = Vec::new();
        let mut set_aside = Vec::new();
        for entry in fs::read_dir(temp_dir)?.filter_map(Result::ok) {
            let name = entry.file_name().to_string_lossy().into_owned();
            if let Some(number) = met_number(&name) {
                if name.ends_with(SET_ASIDE) {
                    set_aside.push(number);
                } else {
                    numbers.push(number);
                }
            }
        }
        // What a cancel kept when njMule stopped before deleting it: the cancel stands.
        for number in &set_aside {
            delete_files(&queue.files_of(*number));
        }
        numbers.sort_unstable();
        numbers.dedup();
        let mut loaded = Vec::new();
        for number in numbers.into_iter().filter(|number| !set_aside.contains(number)) {
            match PartFile::load(temp_dir, number) {
                Ok(file) if loaded.iter().any(|d: &Download| d.hash() == file.hash()) => {
                    tracing::warn!("{} downloads a file downloaded already; it is left alone", file.name());
                }
                Ok(file) => match queue.open(file) {
                    Ok(download) => loaded.push(download),
                    Err(e) => tracing::warn!("Cannot open the download {}: {e}", part_file::file_name(number)),
                },
                Err(e) => tracing::warn!("Cannot load the download {}: {e}", part_file::file_name(number)),
            }
        }
        // In the order they were added, which their numbers, taken again once free, do not keep.
        loaded.sort_by_key(|download| download.file.added());
        for download in loaded {
            queue.downloads.insert(download.hash(), download);
        }
        let hashes: Vec<[u8; 16]> = queue.downloads.keys().copied().collect();
        for hash in hashes {
            // A crash may have come between writing a part and checking it.
            queue.hash_set_known(&hash);
        }
        queue.check_space();
        Ok(queue)
    }

    /// What happened since last asked, in order.
    pub fn take_events(&mut self) -> Vec<QueueEvent> {
        std::mem::take(&mut self.events)
    }

    /// Adds a download in `category` with `priority`. An existing one of the same file gets the link's sources, and
    /// its AICH hash if it has none.
    pub fn add(&mut self, link: &FileLink, category: u32, priority: DownloadPriority) -> io::Result<[u8; 16]> {
        if let Some(existing) = self.downloads.get_mut(&link.hash) {
            if same_file(&existing.file, link) {
                for source in &link.sources {
                    if !existing.link_sources.contains(source) {
                        existing.link_sources.push(source.clone());
                    }
                }
                if existing.file.aich_hash().is_none()
                    && let Some(aich) = link.aich_hash
                {
                    existing.file.trust_aich(aich);
                    existing.dirty = true;
                }
            }
            return Ok(link.hash);
        }
        let mut file = PartFile::create(
            self.next_number(),
            &self.temp_dir,
            &path_sanitizer::file_name(&link.name),
            link.size,
            link.hash,
            link.aich_hash,
            link.part_hashes.clone(),
        )?;
        file.set_category(category);
        file.set_priority(priority);
        file.set_added(self.next_added());
        // Written at once, so that a download that cannot be kept is not added.
        file.save()?;
        let mut download = self.open(file)?;
        download.link_sources.clone_from(&link.sources);
        self.downloads.insert(link.hash, download);
        self.events.push(QueueEvent::Added(link.hash));
        Ok(link.hash)
    }

    /// Where the downloads keep their parts.
    pub fn temp_dir(&self) -> &Path {
        &self.temp_dir
    }

    /// Takes over a download another client left in `met` and `data`, both already in our temp directory, in
    /// `category`: they get the next free number and the complete parts are checked again.
    pub fn adopt(&mut self, met: &Path, data: &Path, category: u32) -> io::Result<[u8; 16]> {
        let contents = part_met::read(&fs::read(met)?).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
        if self.downloads.contains_key(&contents.file_hash) {
            return Err(io::Error::new(io::ErrorKind::AlreadyExists, "already downloading"));
        }
        let number = self.next_number();
        let base = part_file::file_name(number);
        let own_data = self.temp_dir.join(format!("{base}{}", part_file::DATA_SUFFIX));
        let own_met = self.temp_dir.join(format!("{base}{}", part_file::MET_SUFFIX));
        fs::rename(data, &own_data)?;
        fs::rename(met, &own_met)?;
        let mut file = match PartFile::load(&self.temp_dir, number) {
            Ok(file) => file,
            Err(e) => {
                for left in [&own_met, &own_data] {
                    let _ = atomic_files::delete(left);
                }
                return Err(e);
            }
        };
        let name = path_sanitizer::file_name(file.name());
        file.rename(name);
        // The other client's category number means nothing here: the download is announced in ours.
        file.set_category(category);
        file.set_added(self.next_added());
        let hash = file.hash();
        let mut download = self.open(file)?;
        download.dirty = true;
        self.downloads.insert(hash, download);
        self.events.push(QueueEvent::Added(hash));
        self.hash_set_known(&hash);
        Ok(hash)
    }

    /// Whether downloads wait because the temp disk has less free space than they need.
    pub fn is_space_short(&self) -> bool {
        self.space_short.load(Ordering::Relaxed)
    }

    pub fn get(&self, hash: &[u8; 16]) -> Option<&Download> {
        self.downloads.get(hash)
    }

    pub fn get_mut(&mut self, hash: &[u8; 16]) -> Option<&mut Download> {
        self.downloads.get_mut(hash)
    }

    pub fn all(&self) -> impl Iterator<Item = &Download> {
        self.downloads.values()
    }

    /// Data for the download from a source, at `source` when known, which is told when its data proves corrupt: what
    /// is still missing of it is written, then the parts it ends are checked.
    pub fn received(&mut self, hash: &[u8; 16], start: u64, data: &[u8], source: Option<IpAddr>) {
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        let end = start.saturating_add(u64::try_from(data.len()).unwrap_or(u64::MAX));
        if !download.is_active() || end > download.file.size() {
            return;
        }
        // Only what is still missing: a part made whole meanwhile, by AICH or ICH, holds checked data already.
        for gap in download.file.missing_between(start, end) {
            let from = usize::try_from(gap.start.saturating_sub(start)).unwrap_or(usize::MAX);
            let to = usize::try_from(gap.end.saturating_sub(start)).unwrap_or(usize::MAX);
            let Some(slice) = data.get(from..to) else {
                continue;
            };
            download.file.arriving(gap.start, gap.end);
            download.storage.write(gap.start, slice.to_vec(), source);
        }
    }

    /// The hash set arrived: parts finished before it can now be checked, unless checked before a restart.
    pub fn hash_set_known(&mut self, hash: &[u8; 16]) {
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        let trusted = download.file.trusted_parts().to_vec();
        for part in 0..download.file.part_count() {
            if !download.file.is_part_complete(part) || part_file::has(&download.verified, part) {
                continue;
            }
            if part_file::has(&trusted, part) {
                set(&mut download.verified, part);
            } else {
                verify(download, part);
            }
        }
        self.finish_if_done(hash);
    }

    pub fn pause(&mut self, hash: &[u8; 16]) {
        self.change(hash, |file| file.set_status(Status::Paused));
    }

    /// Pauses the download for good: the caller forgets its sources.
    pub fn stop(&mut self, hash: &[u8; 16]) {
        self.change(hash, |file| file.set_status(Status::Stopped));
    }

    /// Resumes a paused, stopped or failed download; false if it was none of these.
    pub fn resume(&mut self, hash: &[u8; 16]) -> bool {
        let Some(download) = self.downloads.get_mut(hash) else {
            return false;
        };
        if !matches!(download.file.status(), Status::Paused | Status::Stopped | Status::Error) {
            return false;
        }
        download.file.set_status(Status::Downloading);
        download.dirty = true;
        if download.file.is_complete() && !download.finishing && !download.storage.is_open() {
            // Its move failed: the data is checked again from the reopened file, then moved once more.
            match PartFileStorage::open(&download.file.data_file(), download.id, self.done.clone()) {
                Ok(storage) => download.storage = storage,
                Err(e) => {
                    download.file.set_status(Status::Error);
                    self.events.push(QueueEvent::Failed { hash: *hash, reason: format!("cannot open the data: {e}") });
                    return true;
                }
            }
            download.verified.fill(false);
            download.checking.fill(false);
            for part in 0..download.file.part_count() {
                verify(download, part);
            }
        }
        true
    }

    pub fn set_preview_first(&mut self, hash: &[u8; 16], value: bool) {
        self.change(hash, |file| file.set_preview_first(value));
    }

    pub fn set_auto_swap(&mut self, hash: &[u8; 16], value: bool) {
        self.change(hash, |file| file.set_auto_swap(value));
    }

    /// Keeps `sources` for the next run; the download is saved again only when they changed.
    pub fn set_known_sources(&mut self, hash: &[u8; 16], sources: &[KnownSource]) {
        if let Some(download) = self.downloads.get_mut(hash)
            && download.file.known_sources() != sources
        {
            download.file.set_known_sources(sources);
            download.dirty = true;
        }
    }

    /// Sets how long the download has run without data; saved when it starts again from zero and every ten minutes.
    pub fn set_idle_seconds(&mut self, hash: &[u8; 16], seconds: u64) {
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        let before = download.file.idle_seconds();
        download.file.set_idle_seconds(seconds);
        let due = if seconds == 0 {
            before != 0
        } else {
            seconds.checked_div(IDLE_SAVED_EVERY) != before.checked_div(IDLE_SAVED_EVERY)
        };
        if due {
            download.dirty = true;
        }
    }

    pub fn set_priority(&mut self, hash: &[u8; 16], priority: DownloadPriority) {
        self.change(hash, |file| file.set_priority(priority));
    }

    pub fn set_category(&mut self, hash: &[u8; 16], category: u32) {
        self.change(hash, |file| file.set_category(category));
    }

    /// Names the download `name`, a safe file name: its file gets it once complete.
    pub fn rename(&mut self, hash: &[u8; 16], name: String) {
        self.change(hash, |file| file.rename(name));
    }

    /// Stops the download; its files are kept as they are for the grace ([`CANCEL_GRACE`] unless set otherwise), for
    /// [`restore`](Self::restore), then deleted. False while its finished file is being moved, as a link added again
    /// would take the same files.
    pub fn cancel(&mut self, hash: &[u8; 16]) -> bool {
        if self.downloads.get(hash).is_none_or(|download| download.finishing) {
            return false;
        }
        let Some(mut download) = self.downloads.shift_remove(hash) else {
            return false;
        };
        let met = download.file.met_file();
        // The met as it is now, after the data queued before it; the saves still queued are skipped.
        if download.storage.is_open() {
            download.storage.save_met(met.clone(), download.file.met_contents());
        }
        download.storage.close();
        download.storage.closed().then(move || set_aside(&met));
        let until = instants::later(Instant::now(), self.grace);
        if let Some(old) = self.withdrawn.insert(*hash, Withdrawn { download, until }) {
            self.delete_later(old);
        }
        true
    }

    /// Keeps the files of cancelled downloads for `grace` from now on.
    pub fn keep_cancelled_for(&mut self, grace: Duration) {
        self.grace = grace;
    }

    /// Takes back a download cancelled less than the grace ago, as it was, once its files are closed: false when its
    /// files are gone, or when a link added meanwhile downloads the file.
    pub fn restore(&mut self, hash: &[u8; 16]) -> bool {
        let Some(kept) = self.withdrawn.remove(hash) else {
            return false;
        };
        let old = &kept.download.file;
        if self.downloads.contains_key(hash) || !kept.download.storage.closed().wait(CLOSE_WAIT) {
            self.delete_later(kept);
            return false;
        }
        let number = old.number();
        let loaded = take_back(&old.met_file()).and_then(|()| PartFile::load(&self.temp_dir, number));
        match loaded.and_then(|file| self.open(file)) {
            Ok(download) => {
                self.downloads.insert(*hash, download);
                self.events.push(QueueEvent::Added(*hash));
                self.hash_set_known(hash);
                true
            }
            Err(e) => {
                tracing::warn!("Cannot take back the download {}: {e}", part_file::file_name(number));
                self.delete_later(kept);
                false
            }
        }
    }

    /// Takes back the blocks of `part` whose data on disk has the AICH hash in `blocks`, which recovery data proved
    /// against the trusted master hash; the others are downloaded again.
    pub fn recovered(&mut self, hash: &[u8; 16], part: usize, blocks: Vec<[u8; 20]>) {
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        if part_file::has(&download.verified, part) {
            return;
        }
        let id = download.id;
        download.storage.hash_blocks(download.file.size(), part);
        self.recovering.insert((id, part), blocks);
    }

    /// Due saves, looks at the free space, moves of finished files tried again, and the files of cancelled downloads
    /// whose grace is over.
    pub fn tick(&mut self, now: Instant) {
        if now >= self.next_save {
            self.next_save = instants::later(now, SAVE_INTERVAL);
            self.save_changed();
        }
        if now >= self.next_space_check {
            self.next_space_check = instants::later(now, SPACE_CHECK);
            self.check_space();
        }
        let over: Vec<[u8; 16]> =
            self.withdrawn.iter().filter(|(_, kept)| now >= kept.until).map(|(hash, _)| *hash).collect();
        for hash in over {
            if let Some(kept) = self.withdrawn.remove(&hash) {
                self.delete_later(kept);
            }
        }
        let again: Vec<[u8; 16]> = self
            .downloads
            .iter()
            .filter(|(_, download)| download.finish_again.is_some_and(|at| now >= at))
            .map(|(hash, _)| *hash)
            .collect();
        for hash in again {
            self.finish_again(&hash);
        }
    }

    /// Downloads wait while the temp disk is short of space and go on by themselves once there is room again.
    pub fn check_space(&mut self) {
        let min = (self.min_free)().max(if self.disk_full { FULL_DISK_RESERVE } else { 0 });
        let short = if min == 0 {
            false
        } else {
            match fs4::available_space(&self.temp_dir) {
                Ok(free) => free < min,
                Err(e) => {
                    tracing::warn!("Cannot read the free space of {}: {e}", self.temp_dir.display());
                    return;
                }
            }
        };
        if !short {
            self.disk_full = false;
        }
        if short != self.space_short.swap(short, Ordering::Relaxed) {
            self.events.push(QueueEvent::DiskSpace(!short));
        }
    }

    /// Saves every part file and closes the storages, waiting a while for the last saves and writes; the files of
    /// cancelled downloads go.
    pub fn close(&mut self) {
        // Undo ends with njMule: the cancelled downloads go.
        let kept: Vec<Withdrawn> = self.withdrawn.drain().map(|(_, kept)| kept).collect();
        for kept in kept {
            self.delete_later(kept);
        }
        for download in self.downloads.values_mut() {
            download.dirty = true;
        }
        self.save_changed();
        let mut closing = Vec::new();
        for download in self.downloads.values_mut() {
            download.storage.close();
            closing.push(download.storage.closed());
        }
        // The saves and the last writes end before njMule does.
        let deadline = instants::later(Instant::now(), CLOSE_WAIT);
        if !closing.iter().all(|closed| closed.wait(deadline.saturating_duration_since(Instant::now()))) {
            tracing::warn!("Not every download could be saved");
        }
        for deleting in self.cancelled.drain(..) {
            let _ = deleting.join();
        }
    }

    /// What a disk thread did.
    pub fn disk_done(&mut self, done: DiskDone) {
        let Some(hash) = self.downloads.values().find(|d| d.id == done.download).map(Download::hash) else {
            // A write or a check can finish after its download was cancelled.
            return;
        };
        match done.work {
            Done::Written { start, end, source, result } => self.written(&hash, start..end, source, result),
            Done::PartHashed { part, recheck, result } => {
                if recheck {
                    self.ich_checked(&hash, part, result);
                } else {
                    self.checked(&hash, part, result);
                }
            }
            Done::BlocksHashed { part, result } => self.take_back(&hash, done.download, part, result),
            Done::Saved { generation, result } => {
                if let (Err(e), Some(download)) = (result, self.downloads.get_mut(&hash))
                    && download.last_save == generation
                {
                    tracing::warn!("Cannot save {}: {e}", download.file.met_file().display());
                    download.dirty = true;
                }
            }
            Done::Finished(result) => self.finished(&hash, result),
        }
    }

    fn written(&mut self, hash: &[u8; 16], range: Range<u64>, source: Option<IpAddr>, result: Result<(), String>) {
        if let Err(e) = result {
            // The session has let the block go: the range is asked for again once the download goes on.
            if let Some(download) = self.downloads.get_mut(hash) {
                download.file.release(range.start, range.end);
                download.dirty = true;
            }
            if self.is_out_of_space() {
                tracing::warn!("The temp disk is full: downloads wait for room");
                self.disk_full = true;
                self.check_space();
                return;
            }
            if let Some(download) = self.downloads.get_mut(hash) {
                download.file.set_status(Status::Error);
            }
            self.events.push(QueueEvent::Failed { hash: *hash, reason: format!("cannot write the data: {e}") });
            return;
        }
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        let (start, end) = (range.start, range.end);
        download.file.written(start, end);
        download.dirty = true;
        if let Some(from) = source {
            download.black_box.received(start, end, from);
        }
        let first = usize::try_from(start / ed2k::PARTSIZE).unwrap_or(usize::MAX);
        let last = usize::try_from(end.saturating_sub(1) / ed2k::PARTSIZE).unwrap_or(0);
        for part in first..=last {
            if download.file.is_part_complete(part) {
                if !part_file::has(&download.verified, part) {
                    verify(download, part);
                }
            } else if part_file::has(&download.corrupted, part) {
                let range = part_file::part_range(download.file.size(), part);
                let bytes = end.min(range.end).saturating_sub(start.max(range.start));
                may_be_whole_again(download, part, bytes);
            }
        }
        self.events.push(QueueEvent::Progress(*hash));
    }

    fn ich_checked(&mut self, hash: &[u8; 16], part: usize, actual: Result<[u8; 16], String>) {
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        unset(&mut download.ich_running, part);
        let expected = download.file.part_hash(part);
        if actual.ok() != expected
            || expected.is_none()
            || !part_file::has(&download.corrupted, part)
            || download.file.is_part_complete(part)
        {
            return;
        }
        let range = part_file::part_range(download.file.size(), part);
        let saved: u64 = download
            .file
            .missing_between(range.start, range.end)
            .iter()
            .map(|gap| gap.end.saturating_sub(gap.start))
            .fold(0, u64::saturating_add);
        tracing::info!("ICH saved {saved} bytes of part {part} of {}", download.file.name());
        download.file.written(range.start, range.end);
        download.dirty = true;
        // Checked once more after any write queued meanwhile: no other can come, the part being whole.
        verify(download, part);
        self.events.push(QueueEvent::Progress(*hash));
    }

    fn checked(&mut self, hash: &[u8; 16], part: usize, actual: Result<[u8; 16], String>) {
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        unset(&mut download.checking, part);
        let range = part_file::part_range(download.file.size(), part);
        let read = actual.is_ok();
        if actual.ok() != download.file.part_hash(part) {
            tracing::debug!("Part {part} of {} is corrupt, downloading it again", download.file.name());
            download.file.corrupt(part);
            set(&mut download.corrupted, part);
            download.since_ich.remove(&part);
            download.dirty = true;
            // Data read back that is wrong, not data that could not be read, counts against its senders.
            let guilty = if read { download.black_box.failed(range.start, range.end) } else { HashSet::new() };
            self.blame(hash, guilty);
            // Before progress, which asks for blocks: a part that waits for its recovery data is not among them.
            self.events.push(QueueEvent::Corrupt { hash: *hash, part });
            self.events.push(QueueEvent::Progress(*hash));
            return;
        }
        set(&mut download.verified, part);
        download.file.set_verified(part);
        download.black_box.passed(range.start, range.end);
        unset(&mut download.corrupted, part);
        download.since_ich.remove(&part);
        download.dirty = true;
        self.finish_if_done(hash);
    }

    fn take_back(&mut self, hash: &[u8; 16], id: u64, part: usize, ours: Result<Vec<[u8; 20]>, String>) {
        let blocks = self.recovering.remove(&(id, part));
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        download.file.unhold(part);
        let (Some(blocks), Ok(ours)) = (blocks, ours) else {
            // The part is downloaded again whole, also by the sessions waiting for it.
            self.events.push(QueueEvent::Progress(*hash));
            return;
        };
        if ours.len() != blocks.len() {
            self.events.push(QueueEvent::Progress(*hash));
            return;
        }
        let range = part_file::part_range(download.file.size(), part);
        let mut saved = 0u64;
        let mut good = Vec::new();
        let mut bad = Vec::new();
        let mut from = range.start;
        for (proven, held) in blocks.iter().zip(&ours) {
            let to = range.end.min(from.saturating_add(ed2k::EMBLOCKSIZE));
            if proven == held {
                download.file.written(from, to);
                good.push(from..to);
                saved = saved.saturating_add(to.saturating_sub(from));
            } else {
                bad.push(from..to);
            }
            from = to;
        }
        let guilty = download.black_box.recovered(&good, &bad);
        download.dirty = true;
        tracing::info!(
            "AICH saved {saved} of {} bytes of part {part} of {}",
            range.end.saturating_sub(range.start),
            download.file.name()
        );
        if download.file.is_part_complete(part) {
            verify(download, part);
        }
        self.blame(hash, guilty);
        self.events.push(QueueEvent::Progress(*hash));
    }

    fn blame(&mut self, hash: &[u8; 16], guilty: HashSet<IpAddr>) {
        if !guilty.is_empty() {
            self.events.push(QueueEvent::CorruptSenders { hash: *hash, ips: guilty.into_iter().collect() });
        }
    }

    fn finish_if_done(&mut self, hash: &[u8; 16]) {
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        if download.file.is_complete() && download.verified.iter().all(|&verified| verified) && !download.finishing {
            finish(download, &self.incoming_dir);
        }
    }

    fn finished(&mut self, hash: &[u8; 16], result: Result<PathBuf, FinishFailure>) {
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        match result {
            Err(failure) => {
                if failure.held && download.finish_retries < FINISH_RETRIES {
                    let delay = FINISH_RETRY_DELAY.saturating_mul(1 << download.finish_retries);
                    download.finish_retries = download.finish_retries.saturating_add(1);
                    tracing::info!(
                        "{} is held by another program; it is moved again in {} s",
                        download.file.name(),
                        delay.as_secs()
                    );
                    download.finish_again = Some(instants::later(Instant::now(), delay));
                    return;
                }
                download.finish_retries = 0;
                download.finishing = false;
                download.file.set_status(Status::Error);
                let reason = format!("cannot move the finished file: {}", failure.reason);
                self.events.push(QueueEvent::Failed { hash: *hash, reason });
            }
            Ok(path) => {
                let Some(mut download) = self.downloads.shift_remove(hash) else {
                    return;
                };
                download.file.set_status(Status::Complete);
                let met = download.file.met_file();
                for file in [atomic_files::bak_path(&met), met] {
                    if let Err(e) = fs::remove_file(&file)
                        && e.kind() != io::ErrorKind::NotFound
                    {
                        tracing::warn!("Cannot delete {}: {e}", file.display());
                    }
                }
                self.events.push(QueueEvent::Completed { file: Box::new(download.file), path });
            }
        }
    }

    // The data file is opened again for one more move of the finished file.
    fn finish_again(&mut self, hash: &[u8; 16]) {
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        download.finish_again = None;
        match PartFileStorage::open(&download.file.data_file(), download.id, self.done.clone()) {
            Ok(storage) => {
                download.storage = storage;
                finish(download, &self.incoming_dir);
            }
            Err(e) => {
                download.finish_retries = 0;
                download.finishing = false;
                download.file.set_status(Status::Error);
                self.events.push(QueueEvent::Failed { hash: *hash, reason: format!("cannot open the data: {e}") });
            }
        }
    }

    // A failed write on a disk short of the room downloads need is a full disk, not a broken one.
    fn is_out_of_space(&self) -> bool {
        fs4::available_space(&self.temp_dir).is_ok_and(|free| free < (self.min_free)().max(FULL_DISK_RESERVE))
    }

    /// Saves the part files whose progress or settings changed; the tick does it every [`SAVE_INTERVAL`].
    pub fn save_changed(&mut self) {
        for download in self.downloads.values_mut() {
            // One being moved is complete and saved: its part.met goes once it is moved.
            if download.dirty && !download.finishing {
                save(download);
            }
        }
    }

    fn change(&mut self, hash: &[u8; 16], change: impl FnOnce(&mut PartFile)) {
        if let Some(download) = self.downloads.get_mut(hash) {
            change(&mut download.file);
            download.dirty = true;
        }
    }

    // The files go once set aside, which is once the download's disk thread is done with them; off the engine's task,
    // as a file another program holds is waited for.
    fn delete_later(&mut self, kept: Withdrawn) {
        let closed = kept.download.storage.closed();
        let files = self.files_of(kept.download.file.number());
        self.cancelled.retain(|deleting| !deleting.is_finished());
        let deleting = std::thread::Builder::new().name("njmule-cancelled-files".to_owned()).spawn(move || {
            closed.wait(CLOSE_WAIT);
            delete_files(&files);
        });
        match deleting {
            Ok(deleting) => self.cancelled.push(deleting),
            Err(e) => tracing::warn!("Cannot delete the files of a cancelled download: {e}"),
        }
    }

    fn open(&mut self, file: PartFile) -> io::Result<Download> {
        let id = self.next_id;
        self.next_id = self.next_id.saturating_add(1);
        let storage = PartFileStorage::open(&file.data_file(), id, self.done.clone())?;
        let parts = file.part_count();
        Ok(Download {
            id,
            file,
            storage,
            link_sources: Vec::new(),
            verified: vec![false; parts],
            dirty: false,
            finishing: false,
            last_save: 0,
            finish_retries: 0,
            finish_again: None,
            corrupted: vec![false; parts],
            since_ich: HashMap::new(),
            ich_running: vec![false; parts],
            checking: vec![false; parts],
            black_box: CorruptionBlackBox::default(),
            space_short: Arc::clone(&self.space_short),
        })
    }

    fn next_added(&self) -> u64 {
        self.downloads.values().map(|d| d.file.added()).max().unwrap_or(0).saturating_add(1)
    }

    // A number is taken while any file of it is left, so a new download never reopens an old part file.
    fn next_number(&self) -> u32 {
        let mut number = 1u32;
        while self.downloads.values().any(|d| d.file.number() == number)
            || self.files_of(number).iter().any(|file| file.exists())
        {
            number = number.saturating_add(1);
        }
        number
    }

    // Every file a download of `number` may leave; the set-aside mets last, so that while one is left the next start
    // deletes the rest and the number stays taken.
    fn files_of(&self, number: u32) -> Vec<PathBuf> {
        let base = part_file::file_name(number);
        let data = self.temp_dir.join(format!("{base}{}", part_file::DATA_SUFFIX));
        let met = self.temp_dir.join(format!("{base}{}", part_file::MET_SUFFIX));
        let bak = atomic_files::bak_path(&met);
        vec![data, atomic_files::tmp_path(&met), met.clone(), bak.clone(), aside(&bak), aside(&met)]
    }
}

/// Whether `link` names the file of `file`: the same size, and the same AICH hash when both have one.
pub fn same_file(file: &PartFile, link: &FileLink) -> bool {
    file.size() == link.size
        && (file.aich_hash().is_none() || link.aich_hash.is_none() || file.aich_hash() == link.aich_hash)
}

fn finish(download: &mut Download, incoming_dir: &IncomingDir) {
    download.finishing = true;
    let directory = incoming_dir(&download.file);
    download.storage.finish(directory, download.file.name().to_owned());
}

// Built here and written on the download's disk thread, after the data queued before it. A failed save is made again
// at the next round, unless a newer one is on its way.
fn save(download: &mut Download) {
    download.dirty = false;
    let met = download.file.met_file();
    let contents = download.file.met_contents();
    if !download.storage.is_open() {
        // Closed after its finished file could not be moved: rare enough to be written here.
        if let Err(e) = part_file::write_met(&met, &contents, &download.file.data_file()) {
            tracing::warn!("Cannot save {}: {e}", met.display());
            download.dirty = true;
        }
        return;
    }
    download.last_save = download.storage.save_met(met, contents);
}

// One check of a part at a time: it runs after the writes queued before it.
fn verify(download: &mut Download, part: usize) {
    if download.file.part_hash(part).is_none() || part_file::has(&download.checking, part) {
        return;
    }
    set(&mut download.checking, part);
    download.storage.hash_part(download.file.size(), part, false);
}

// Intelligent corruption handling: a corrupt part keeps its data on disk while it is downloaded again, so once the bad
// bytes were overwritten the part as it stands is whole. It is hashed after each block written into it.
fn may_be_whole_again(download: &mut Download, part: usize, bytes: u64) {
    let written = download.since_ich.entry(part).or_insert(0);
    *written = written.saturating_add(bytes);
    if *written < ed2k::EMBLOCKSIZE
        || part_file::has(&download.ich_running, part)
        || download.file.part_hash(part).is_none()
    {
        return;
    }
    *written = 0;
    set(&mut download.ich_running, part);
    download.storage.hash_part(download.file.size(), part, true);
}

fn set_aside(met: &Path) {
    for file in [met.to_path_buf(), atomic_files::bak_path(met)] {
        if file.exists()
            && let Err(e) = atomic_files::move_over(&file, &aside(&file))
        {
            tracing::warn!("Cannot set {} aside: {e}", file.display());
        }
    }
}

fn take_back(met: &Path) -> io::Result<()> {
    for file in [met.to_path_buf(), atomic_files::bak_path(met)] {
        if aside(&file).exists() {
            atomic_files::move_over(&aside(&file), &file)?;
        }
    }
    Ok(())
}

// In order, until one cannot be deleted: the next start tries again.
fn delete_files(files: &[PathBuf]) {
    for file in files {
        if let Err(e) = atomic_files::delete(file) {
            tracing::warn!("Cannot delete {}; the next start tries again: {e}", file.display());
            return;
        }
    }
}

fn aside(file: &Path) -> PathBuf {
    let mut name = file.file_name().unwrap_or_default().to_os_string();
    name.push(SET_ASIDE);
    file.with_file_name(name)
}

// The number of `NNN.part.met`, its backup or either set aside.
fn met_number(name: &str) -> Option<u32> {
    let rest = name.strip_suffix(SET_ASIDE).unwrap_or(name);
    let rest = rest.strip_suffix(atomic_files::BAK_SUFFIX).unwrap_or(rest);
    let digits = rest.strip_suffix(part_file::MET_SUFFIX)?;
    if digits.is_empty() || digits.len() > 6 || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    digits.parse().ok()
}

fn set(bits: &mut [bool], i: usize) {
    if let Some(bit) = bits.get_mut(i) {
        *bit = true;
    }
}

fn unset(bits: &mut [bool], i: usize) {
    if let Some(bit) = bits.get_mut(i) {
        *bit = false;
    }
}
