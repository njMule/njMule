// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The downloads: their part files in the temp directory, the data arriving from sources, the verification of each
//! finished part and the move of finished files to the incoming directory. Disk work runs on each file's own thread
//! and comes back through [`DownloadQueue::disk_done`].

use std::collections::HashMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use indexmap::IndexMap;
use tokio::sync::mpsc::UnboundedSender;

use crate::api::download_info::DownloadPriority;
use crate::api::ed2k_link::{self, FileLink};
use crate::files::hash::ed2k;
use crate::files::part_file::{self, PartFile, Status};
use crate::files::part_file_storage::{DiskDone, Done, PartFileStorage};
use crate::persist::atomic_files;
use crate::security::path_sanitizer;
use crate::util::instants;

/// Part files are saved this often while their progress changes.
pub const SAVE_INTERVAL: Duration = Duration::from_secs(60);
/// How long the files of a cancelled download are kept, so that the cancel can be undone.
pub const CANCEL_GRACE: Duration = Duration::from_secs(15);

// The met of a cancelled download kept for an undo: no start loads it, and the next start deletes it.
const SET_ASIDE: &str = ".cancelled";

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
    // Parts that failed their check and are being downloaded again, the bytes written into each since it was last
    // hashed, and those being hashed.
    corrupted: Vec<bool>,
    since_ich: HashMap<usize, u64>,
    ich_running: Vec<bool>,
    // Parts whose check is on its way.
    checking: Vec<bool>,
}

impl Download {
    pub fn hash(&self) -> [u8; 16] {
        self.file.hash()
    }

    pub fn is_active(&self) -> bool {
        self.file.status() == Status::Downloading && !self.finishing
    }

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

    fn new(id: u64, file: PartFile, storage: PartFileStorage) -> Self {
        let parts = file.part_count();
        Self {
            id,
            file,
            storage,
            link_sources: Vec::new(),
            verified: vec![false; parts],
            dirty: false,
            finishing: false,
            corrupted: vec![false; parts],
            since_ich: HashMap::new(),
            ich_running: vec![false; parts],
            checking: vec![false; parts],
        }
    }
}

// A cancelled download: its files go at the end of the grace.
struct Withdrawn {
    files: Vec<PathBuf>,
    until: Instant,
}

pub struct DownloadQueue {
    temp_dir: PathBuf,
    incoming_dir: PathBuf,
    done: UnboundedSender<DiskDone>,
    downloads: IndexMap<[u8; 16], Download>,
    withdrawn: HashMap<[u8; 16], Withdrawn>,
    // The block hashes recovery data proved, by download and part, while ours are read back.
    recovering: HashMap<(u64, usize), Vec<[u8; 20]>>,
    next_id: u64,
    next_save: Instant,
    events: Vec<QueueEvent>,
}

impl DownloadQueue {
    /// Loads the part files of `temp_dir`, also those of which only the backup is left; unreadable ones are left alone
    /// and logged. Finished files go to `incoming_dir`.
    pub fn load(temp_dir: &Path, incoming_dir: &Path, done: UnboundedSender<DiskDone>) -> io::Result<Self> {
        fs::create_dir_all(temp_dir)?;
        let mut queue = Self {
            temp_dir: temp_dir.to_path_buf(),
            incoming_dir: incoming_dir.to_path_buf(),
            done,
            downloads: IndexMap::new(),
            withdrawn: HashMap::new(),
            recovering: HashMap::new(),
            next_id: 1,
            next_save: instants::later(Instant::now(), SAVE_INTERVAL),
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

    pub fn temp_dir(&self) -> &Path {
        &self.temp_dir
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

    /// Data for the download from a source: what is still missing of it is written, then the parts it ends are
    /// checked.
    pub fn received(&mut self, hash: &[u8; 16], start: u64, data: &[u8]) {
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
            download.storage.write(gap.start, slice.to_vec());
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
        self.set_status(hash, Status::Paused);
    }

    /// Pauses the download for good: the caller forgets its sources.
    pub fn stop(&mut self, hash: &[u8; 16]) {
        self.set_status(hash, Status::Stopped);
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
        true
    }

    /// Stops the download; its files are kept as they are for [`CANCEL_GRACE`], then deleted. False while its finished
    /// file is being moved, as a link added again would take the same files.
    pub fn cancel(&mut self, hash: &[u8; 16]) -> bool {
        if self.downloads.get(hash).is_none_or(|download| download.finishing) {
            return false;
        }
        let Some(mut download) = self.downloads.shift_remove(hash) else {
            return false;
        };
        download.storage.cancel_saves();
        download.storage.close(Vec::new());
        let met = download.file.met_file();
        for file in [met.clone(), atomic_files::bak_path(&met)] {
            if file.exists()
                && let Err(e) = fs::rename(&file, aside(&file))
            {
                tracing::warn!("Cannot set {} aside: {e}", file.display());
            }
        }
        let files = self.files_of(download.file.number());
        self.withdrawn.insert(*hash, Withdrawn { files, until: instants::later(Instant::now(), CANCEL_GRACE) });
        true
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

    /// Once a second: due saves, and the files of cancelled downloads whose grace is over.
    pub fn tick(&mut self, now: Instant) {
        if now >= self.next_save {
            self.next_save = instants::later(now, SAVE_INTERVAL);
            self.save_changed();
        }
        let over: Vec<[u8; 16]> =
            self.withdrawn.iter().filter(|(_, kept)| now >= kept.until).map(|(hash, _)| *hash).collect();
        for hash in over {
            if let Some(kept) = self.withdrawn.remove(&hash) {
                // Off the engine's task: a file another program holds is waited for.
                tokio::task::spawn_blocking(move || delete_files(&kept.files));
            }
        }
    }

    /// Saves every part file and closes the storages; the files of cancelled downloads go.
    pub fn close(&mut self) {
        for (_, kept) in self.withdrawn.drain() {
            delete_files(&kept.files);
        }
        for download in self.downloads.values_mut() {
            download.dirty = true;
        }
        self.save_changed();
        for download in self.downloads.values_mut() {
            download.storage.close(Vec::new());
        }
    }

    /// What a disk thread did.
    pub fn disk_done(&mut self, done: DiskDone) {
        let Some(hash) = self.downloads.values().find(|d| d.id == done.download).map(Download::hash) else {
            // A write or a check can finish after its download was cancelled.
            return;
        };
        match done.work {
            Done::Written { start, end, result } => self.written(&hash, start, end, result),
            Done::PartHashed { part, recheck, result } => {
                if recheck {
                    self.ich_checked(&hash, part, result);
                } else {
                    self.checked(&hash, part, result);
                }
            }
            Done::BlocksHashed { part, result } => self.take_back(&hash, done.download, part, result),
            Done::Saved { result, .. } => {
                if let (Err(e), Some(download)) = (result, self.downloads.get_mut(&hash)) {
                    tracing::warn!("Cannot save {}: {e}", download.file.met_file().display());
                    download.dirty = true;
                }
            }
            Done::Finished(result) => self.finished(&hash, result),
        }
    }

    fn written(&mut self, hash: &[u8; 16], start: u64, end: u64, result: Result<(), String>) {
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        if let Err(e) = result {
            // The session has let the block go: the range is asked for again once the download goes on.
            download.file.release(start, end);
            download.dirty = true;
            download.file.set_status(Status::Error);
            self.events.push(QueueEvent::Failed { hash: *hash, reason: format!("cannot write the data: {e}") });
            return;
        }
        download.file.written(start, end);
        download.dirty = true;
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
        if actual.ok() != download.file.part_hash(part) {
            tracing::debug!("Part {part} of {} is corrupt, downloading it again", download.file.name());
            download.file.corrupt(part);
            set(&mut download.corrupted, part);
            download.since_ich.remove(&part);
            download.dirty = true;
            // Before progress, which asks for blocks: a part that waits for its recovery data is not among them.
            self.events.push(QueueEvent::Corrupt { hash: *hash, part });
            self.events.push(QueueEvent::Progress(*hash));
            return;
        }
        set(&mut download.verified, part);
        download.file.set_verified(part);
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
        let mut from = range.start;
        for (proven, held) in blocks.iter().zip(&ours) {
            let to = range.end.min(from.saturating_add(ed2k::EMBLOCKSIZE));
            if proven == held {
                download.file.written(from, to);
                saved = saved.saturating_add(to.saturating_sub(from));
            }
            from = to;
        }
        download.dirty = true;
        tracing::info!(
            "AICH saved {saved} of {} bytes of part {part} of {}",
            range.end.saturating_sub(range.start),
            download.file.name()
        );
        if download.file.is_part_complete(part) {
            verify(download, part);
        }
        self.events.push(QueueEvent::Progress(*hash));
    }

    fn finish_if_done(&mut self, hash: &[u8; 16]) {
        let incoming = self.incoming_dir.clone();
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        if download.file.is_complete() && download.verified.iter().all(|&verified| verified) && !download.finishing {
            download.finishing = true;
            let name = download.file.name().to_owned();
            download.storage.finish(incoming, name);
        }
    }

    fn finished(&mut self, hash: &[u8; 16], result: Result<PathBuf, String>) {
        let Some(download) = self.downloads.get_mut(hash) else {
            return;
        };
        match result {
            Err(e) => {
                download.finishing = false;
                download.file.set_status(Status::Error);
                self.events
                    .push(QueueEvent::Failed { hash: *hash, reason: format!("cannot move the finished file: {e}") });
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

    fn save_changed(&mut self) {
        for download in self.downloads.values_mut() {
            // One being moved is complete and saved: its part.met goes once it is moved.
            if download.dirty && !download.finishing {
                download.dirty = false;
                download.storage.save_met(download.file.met_file(), download.file.met_contents());
            }
        }
    }

    fn set_status(&mut self, hash: &[u8; 16], status: Status) {
        if let Some(download) = self.downloads.get_mut(hash) {
            download.file.set_status(status);
            download.dirty = true;
        }
    }

    fn open(&mut self, file: PartFile) -> io::Result<Download> {
        let id = self.next_id;
        self.next_id = self.next_id.saturating_add(1);
        let storage = PartFileStorage::open(&file.data_file(), id, self.done.clone())?;
        Ok(Download::new(id, file, storage))
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
        let mut temporary = met.file_name().unwrap_or_default().to_os_string();
        temporary.push(".tmp");
        vec![data, met.with_file_name(temporary), met.clone(), bak.clone(), aside(&bak), aside(&met)]
    }
}

/// Whether `link` names the file of `file`: the same size, and the same AICH hash when both have one.
pub fn same_file(file: &PartFile, link: &FileLink) -> bool {
    file.size() == link.size
        && (file.aich_hash().is_none() || link.aich_hash.is_none() || file.aich_hash() == link.aich_hash)
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

fn delete_files(files: &[PathBuf]) {
    for file in files {
        if let Err(e) = fs::remove_file(file)
            && e.kind() != io::ErrorKind::NotFound
        {
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
