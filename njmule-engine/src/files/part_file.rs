// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A file being downloaded, `NNN.part` with its metadata `NNN.part.met`: which byte ranges are still missing, which are
//! being requested, and the part hashes that verify each 9.28 MiB part. The data itself is read and written by
//! [`part_file_storage`](crate::files::part_file_storage).

use std::fs;
use std::io;
use std::ops::Range;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

use rand::{Rng, RngExt};
use rangemap::RangeSet;

use crate::api::download_info::DownloadPriority;
use crate::files::file_types;
use crate::files::hash::ed2k;
use crate::persist::atomic_files;
use crate::persist::format::part_met::{self, Contents, Gap};
use crate::protocol::tag::{Tag, TagName, file_tag, tags};

// njMule's own tags, which other clients skip: the parts that passed their check and the place of the download in
// the order downloads were added. Those of later ports are kept as they were read.
const VERIFIED_TAG: &[u8] = b"njm_verified";
const ADDED_TAG: &[u8] = b"njm_added";
const KEPT_TAGS: [&[u8]; 3] = [b"njm_a4af_auto", b"njm_sources", b"njm_idle"];

pub const DATA_SUFFIX: &str = ".part";
pub const MET_SUFFIX: &str = ".part.met";

/// The bytes still missing at most for the endgame: one part, so that the blocks asked twice stay few.
pub const ENDGAME_MISSING: u64 = ed2k::PARTSIZE;

/// The sources of a download kept for the next run at most.
pub const MAX_KNOWN_SOURCES: usize = 100;

/// A source of a download kept for the next run: its address (as [`crate::util::ipv4`] holds it) and port, when it was
/// last found good (seconds since 1970), its user hash when known and its crypt options.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KnownSource {
    pub ip: u32,
    pub port: u16,
    pub seen: u64,
    pub user_hash: Option<[u8; 16]>,
    pub crypt_options: u8,
}

/// Where a download stands.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    Downloading,
    Paused,
    /// Paused, and its sources are forgotten.
    Stopped,
    /// Disk failure: the file waits for the user.
    Error,
    Complete,
}

/// A range `[start, end)` within one part, at most one block long.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Block {
    pub start: u64,
    pub end: u64,
}

impl Block {
    pub fn length(&self) -> u64 {
        self.end.saturating_sub(self.start)
    }
}

#[derive(Debug, Clone)]
pub struct PartFile {
    number: u32,
    directory: PathBuf,
    hash: [u8; 16],
    size: u64,
    aich_hash: Option<[u8; 20]>,
    // The parts checked in this run and before, and those trusted from the last save.
    verified: Vec<bool>,
    trusted: Vec<bool>,
    // Corrupt parts waiting for their AICH recovery data.
    held: Vec<bool>,
    missing: RangeSet<u64>,
    requested: RangeSet<u64>,
    // Requested from a second source by the endgame: never from a third.
    asked_twice: RangeSet<u64>,
    // Received and on its way to the disk: still missing and requested, but asked of no source again.
    arriving: RangeSet<u64>,
    availability: Vec<u32>,
    name: String,
    part_hashes: Vec<[u8; 16]>,
    status: Status,
    priority: DownloadPriority,
    category: u32,
    added: u64,
    preview_first: bool,
    kept: Vec<Tag>,
}

impl PartFile {
    /// A new, empty download; its hash set may be given (from the link) or arrive later.
    pub fn create(
        number: u32,
        directory: &Path,
        name: &str,
        size: u64,
        hash: [u8; 16],
        aich_hash: Option<[u8; 20]>,
        part_hashes: Vec<[u8; 16]>,
    ) -> io::Result<Self> {
        // The hash set of a link is optional: one that proves nothing is asked from the sources instead.
        let part_hashes = if fits(size, &hash, &part_hashes) { part_hashes } else { Vec::new() };
        let mut file = Self::new(number, directory, name.to_owned(), size, hash, aich_hash, part_hashes)?;
        file.missing.insert(0..size);
        Ok(file)
    }

    fn new(
        number: u32,
        directory: &Path,
        name: String,
        size: u64,
        hash: [u8; 16],
        aich_hash: Option<[u8; 20]>,
        part_hashes: Vec<[u8; 16]>,
    ) -> io::Result<Self> {
        ed2k::check_size(size).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e.to_string()))?;
        if size == 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "nothing to download in an empty file"));
        }
        let parts = ed2k::part_count(size);
        Ok(Self {
            number,
            directory: directory.to_path_buf(),
            hash,
            size,
            aich_hash,
            verified: vec![false; parts],
            trusted: vec![false; parts],
            held: vec![false; parts],
            missing: RangeSet::new(),
            requested: RangeSet::new(),
            asked_twice: RangeSet::new(),
            arriving: RangeSet::new(),
            availability: vec![0; parts],
            name,
            part_hashes,
            status: Status::Downloading,
            priority: DownloadPriority::Auto,
            category: 0,
            added: 0,
            preview_first: false,
            kept: Vec::new(),
        })
    }

    /// Reads `NNN.part.met` (or its backup) from `directory`.
    pub fn load(directory: &Path, number: u32) -> io::Result<Self> {
        let met = directory.join(format!("{}{MET_SUFFIX}", file_name(number)));
        let contents = read_met(&met)?;
        let size = contents.size().ok_or_else(|| invalid("no file size"))?;
        let part_hashes = if fits(size, &contents.file_hash, &contents.part_hashes) {
            contents.part_hashes.clone()
        } else {
            Vec::new()
        };
        let name = contents.name().map_or_else(|| file_name(number), str::to_owned);
        let mut file = Self::new(number, directory, name, size, contents.file_hash, contents.aich_hash(), part_hashes)?;
        for gap in contents.gaps() {
            insert(&mut file.missing, gap.start()..gap.end());
        }
        if contents.stopped() {
            file.status = Status::Stopped;
        } else if contents.paused() {
            file.status = Status::Paused;
        }
        if let Some(code) = contents.download_priority() {
            file.priority = priority(code);
        }
        file.category = contents.category().and_then(|id| u32::try_from(id).ok()).unwrap_or(0);
        file.added = tags::int_value_named(&contents.tags, &TagName::of_bytes(ADDED_TAG)).unwrap_or(0);
        // Bit 0: first and last parts first, for a preview.
        file.preview_first = tags::int_value(&contents.tags, file_tag::DL_PREVIEW).unwrap_or(0) & 1 != 0;
        file.kept = contents
            .tags
            .iter()
            .filter(|tag| KEPT_TAGS.iter().any(|kept| tag.name().to_bytes() == *kept))
            .cloned()
            .collect();
        // Parts checked before are trusted only while the data file is as it was saved.
        if data_date(&file.data_file()) == Some(contents.mtime) {
            if let Some(Tag::Blob { value, .. }) = tags::find(&contents.tags, &TagName::of_bytes(VERIFIED_TAG)) {
                for (part, trusted) in file.trusted.iter_mut().enumerate() {
                    *trusted = bit(value, part);
                }
            }
            file.verified.clone_from(&file.trusted);
        }
        Ok(file)
    }

    /// Records that `part` passed its check, so that a restart need not check it again.
    pub fn set_verified(&mut self, part: usize) {
        set(&mut self.verified, part, true);
    }

    /// The parts that passed their check before the last save, when the data has not changed since.
    pub fn trusted_parts(&self) -> &[bool] {
        &self.trusted
    }

    /// What a save writes, but for the date of the data, which [`write_met`] takes when it writes.
    pub fn met_contents(&self) -> Contents {
        let mut tags = vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: self.name.clone() },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: self.size },
            Tag::String { name: TagName::id(file_tag::PARTFILENAME), value: self.data_name() },
            Tag::Int { name: TagName::id(file_tag::STATUS), value: status_code(self.status) },
            Tag::Int { name: TagName::id(file_tag::DLPRIORITY), value: priority_code(self.priority) },
        ];
        if self.preview_first {
            tags.push(Tag::Int { name: TagName::id(file_tag::DL_PREVIEW), value: 1 });
        }
        if self.category != 0 {
            tags.push(Tag::Int { name: TagName::id(file_tag::CATEGORY), value: u64::from(self.category) });
        }
        if self.added > 0 {
            tags.push(Tag::Int { name: TagName::of_bytes(ADDED_TAG), value: self.added });
        }
        if let Some(aich) = self.aich_hash {
            let value = data_encoding::BASE32_NOPAD.encode(&aich);
            tags.push(Tag::String { name: TagName::id(file_tag::AICH_HASH), value });
        }
        if self.verified.iter().any(|&verified| verified) {
            tags.push(Tag::Blob { name: TagName::of_bytes(VERIFIED_TAG), value: to_bytes(&self.verified) });
        }
        tags.extend(self.kept.iter().cloned());
        let gaps: Vec<Gap> = self.missing.iter().filter_map(|gap| Gap::new(gap.start, gap.end)).collect();
        let large = self.size > ed2k::OLD_MAX_FILE_SIZE;
        Contents {
            version: if large { part_met::VERSION_LARGE } else { part_met::VERSION },
            mtime: 0,
            file_hash: self.hash,
            part_hashes: self.part_hashes.clone(),
            tags,
        }
        .with_gaps(&gaps)
    }

    /// Writes `NNN.part.met` crash-safely, keeping the previous one as backup.
    pub fn save(&self) -> io::Result<()> {
        write_met(&self.met_file(), &self.met_contents(), &self.data_file())
    }

    pub fn number(&self) -> u32 {
        self.number
    }

    pub fn hash(&self) -> [u8; 16] {
        self.hash
    }

    pub fn size(&self) -> u64 {
        self.size
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    pub fn rename(&mut self, name: String) {
        self.name = name;
    }

    /// The trusted AICH master hash: from the link, or agreed by enough sources.
    pub fn aich_hash(&self) -> Option<[u8; 20]> {
        self.aich_hash
    }

    pub fn trust_aich(&mut self, hash: [u8; 20]) {
        self.aich_hash = Some(hash);
    }

    pub fn status(&self) -> Status {
        self.status
    }

    pub fn set_status(&mut self, status: Status) {
        self.status = status;
    }

    pub fn priority(&self) -> DownloadPriority {
        self.priority
    }

    pub fn set_priority(&mut self, priority: DownloadPriority) {
        self.priority = priority;
    }

    pub fn category(&self) -> u32 {
        self.category
    }

    pub fn set_category(&mut self, category: u32) {
        self.category = category;
    }

    /// The place of the download in the order downloads were added, from 1; 0 when not known.
    pub fn added(&self) -> u64 {
        self.added
    }

    pub fn set_added(&mut self, added: u64) {
        self.added = added;
    }

    pub fn preview_first(&self) -> bool {
        self.preview_first
    }

    pub fn data_file(&self) -> PathBuf {
        self.directory.join(self.data_name())
    }

    pub fn met_file(&self) -> PathBuf {
        self.directory.join(format!("{}{MET_SUFFIX}", file_name(self.number)))
    }

    pub fn part_count(&self) -> usize {
        ed2k::part_count(self.size)
    }

    pub fn missing_bytes(&self) -> u64 {
        self.missing.iter().map(|gap| gap.end.saturating_sub(gap.start)).fold(0, u64::saturating_add)
    }

    pub fn completed_bytes(&self) -> u64 {
        self.size.saturating_sub(self.missing_bytes())
    }

    /// Whether every part can be verified: files below one part need only the file hash.
    pub fn has_hash_set(&self) -> bool {
        self.size < ed2k::PARTSIZE || self.part_hashes.len() == ed2k::hash_set_size(self.size)
    }

    /// Sets the part hashes once a source sent them; false, and nothing set, when they do not hash to the file hash.
    pub fn set_hash_set(&mut self, hashes: Vec<[u8; 16]>) -> bool {
        if !fits(self.size, &self.hash, &hashes) {
            return false;
        }
        self.part_hashes = hashes;
        true
    }

    pub fn part_hashes(&self) -> &[[u8; 16]] {
        &self.part_hashes
    }

    /// The hash that verifies `part`, once known.
    pub fn part_hash(&self, part: usize) -> Option<[u8; 16]> {
        if self.size < ed2k::PARTSIZE {
            return Some(self.hash);
        }
        self.part_hashes.get(part).copied()
    }

    pub fn is_complete(&self) -> bool {
        self.missing.is_empty()
    }

    /// Whether every byte of `[start, end)` has been written.
    pub fn is_complete_between(&self, start: u64, end: u64) -> bool {
        start >= end || self.missing.overlapping(&(start..end)).next().is_none()
    }

    /// The ranges of `[start, end)` still missing.
    pub fn missing_between(&self, start: u64, end: u64) -> Vec<Range<u64>> {
        if start >= end {
            return Vec::new();
        }
        self.missing.overlapping(&(start..end)).map(|gap| gap.start.max(start)..gap.end.min(end)).collect()
    }

    pub fn is_part_complete(&self, part: usize) -> bool {
        let range = part_range(self.size, part);
        self.is_complete_between(range.start, range.end)
    }

    /// The parts a source has, as it reports them, update the rarity of each part.
    pub fn add_availability(&mut self, parts: &[bool], add: bool) {
        for (count, _) in self.availability.iter_mut().zip(parts).filter(|(_, has)| **has) {
            *count = if add { count.saturating_add(1) } else { count.saturating_sub(1) };
        }
    }

    /// The bytes still missing, the requested ones and the sources of each part, to show.
    pub fn map(&self) -> PartMap {
        PartMap {
            size: self.size,
            missing: self.missing.iter().map(|r| (r.start, r.end)).collect(),
            requested: self.requested.iter().map(|r| (r.start, r.end)).collect(),
            sources: self.availability.clone(),
        }
    }

    /// Up to `max` blocks to request from a source that has `source_parts`: missing and not already requested, from
    /// the rarest incomplete part the source has. They are marked as requested until [`written`](Self::written) or
    /// [`release`](Self::release).
    pub fn next_blocks(&mut self, source_parts: &[bool], max: usize, random: &mut impl Rng) -> Vec<Block> {
        let mut blocks = Vec::with_capacity(max);
        let Some(part) = self.pick_part(source_parts, random) else {
            return blocks;
        };
        let range = part_range(self.size, part);
        let wanted = subtract(self.missing_between(range.start, range.end), &self.requested);
        cut_blocks(ed2k::part_start(part), &wanted, max, &mut blocks);
        for block in &blocks {
            insert(&mut self.requested, block.start..block.end);
        }
        blocks
    }

    /// Endgame: once nothing else is left to request and at most [`ENDGAME_MISSING`] bytes are missing, up to `max`
    /// blocks requested from other sources, from the parts the source has, asked of it too; the first copy to arrive
    /// is kept. A block is asked twice at most, and never again of the source that asked it (`own`).
    pub fn endgame_blocks(&mut self, source_parts: &[bool], max: usize, own: &[Block]) -> Vec<Block> {
        let mut blocks = Vec::with_capacity(max);
        if self.missing_bytes() > ENDGAME_MISSING {
            return blocks;
        }
        let mut mine = RangeSet::new();
        for block in own {
            insert(&mut mine, block.start..block.end);
        }
        for part in (0..self.part_count()).filter(|&part| has(source_parts, part)) {
            if blocks.len() >= max {
                break;
            }
            if has(&self.held, part) {
                continue;
            }
            let range = part_range(self.size, part);
            let missing = self.missing_between(range.start, range.end);
            let mut again: Vec<Range<u64>> = missing
                .iter()
                .flat_map(|gap| {
                    self.requested.overlapping(gap).map(move |r| r.start.max(gap.start)..r.end.min(gap.end))
                })
                .collect();
            for taken in [&self.asked_twice, &self.arriving, &mine] {
                again = subtract(again, taken);
            }
            let before = blocks.len();
            cut_blocks(ed2k::part_start(part), &again, max, &mut blocks);
            for block in blocks.get(before..).unwrap_or_default() {
                insert(&mut self.asked_twice, block.start..block.end);
            }
        }
        blocks
    }

    /// Keeps `part` from being downloaded until [`unhold`](Self::unhold): its recovery data may save most of it.
    pub fn hold(&mut self, part: usize) {
        set(&mut self.held, part, true);
    }

    pub fn unhold(&mut self, part: usize) {
        set(&mut self.held, part, false);
    }

    pub fn is_held(&self, part: usize) -> bool {
        has(&self.held, part)
    }

    /// Requested data in `[start, end)` that will not reach the disk, so it is handed out again.
    pub fn release(&mut self, start: u64, end: u64) {
        for set in [&mut self.requested, &mut self.asked_twice, &mut self.arriving] {
            remove(set, start..end);
        }
    }

    /// Data in `[start, end)` was received and goes to the disk.
    pub fn arriving(&mut self, start: u64, end: u64) {
        insert(&mut self.arriving, start..end);
    }

    /// Data in `[start, end)` reached the disk.
    pub fn written(&mut self, start: u64, end: u64) {
        for set in [&mut self.missing, &mut self.requested, &mut self.asked_twice, &mut self.arriving] {
            remove(set, start..end);
        }
    }

    /// A part failed its hash check: all of it is missing again.
    pub fn corrupt(&mut self, part: usize) {
        let range = part_range(self.size, part);
        insert(&mut self.missing, range.clone());
        remove(&mut self.asked_twice, range);
        set(&mut self.verified, part, false);
        set(&mut self.trusted, part, false);
    }

    /// The parts that are complete, as reported to other clients.
    pub fn complete_parts(&self) -> Vec<bool> {
        (0..self.part_count()).map(|part| self.is_part_complete(part)).collect()
    }

    fn data_name(&self) -> String {
        format!("{}{DATA_SUFFIX}", file_name(self.number))
    }

    /// The parts a player needs to start: the first and the last, and the one before the last when the last is short.
    fn preview_parts(&self) -> Vec<bool> {
        let count = self.part_count();
        let mut parts = vec![false; count];
        let playable = matches!(file_types::of(&self.name), Some(file_types::VIDEO | file_types::AUDIO));
        if self.preview_first && playable && self.size > ed2k::PARTSIZE.saturating_mul(2) {
            let last = count.saturating_sub(1);
            set(&mut parts, 0, true);
            set(&mut parts, last, true);
            if self.size.saturating_sub(ed2k::part_start(last)) < ed2k::PARTSIZE / 3 {
                set(&mut parts, last.saturating_sub(1), true);
            }
        }
        parts
    }

    // The parts for a preview first; then the rarest; among equally rare parts, one begun already, so that parts
    // complete, are checked and can be shared sooner; then any of the equal ones, by chance.
    fn pick_part(&self, source_parts: &[bool], random: &mut impl Rng) -> Option<usize> {
        let preview = self.preview_parts();
        let mut best: Option<(usize, bool, bool)> = None;
        let mut equal = 0u32;
        for part in (0..self.part_count()).filter(|&part| has(source_parts, part)) {
            if has(&self.held, part) {
                continue;
            }
            let range = part_range(self.size, part);
            let missing_here = self.missing_between(range.start, range.end);
            // Every byte still missing here is asked from another source already.
            if missing_here.iter().all(|gap| self.requested.gaps(gap).next().is_none()) {
                continue;
            }
            let begun = self.missing.gaps(&range).next().is_some();
            let for_preview = has(&preview, part);
            let rarity = self.availability.get(part).copied().unwrap_or(0);
            match best {
                Some((_, _, best_preview)) if best_preview && !for_preview => {}
                Some((best_part, best_begun, best_preview)) => {
                    let best_rarity = self.availability.get(best_part).copied().unwrap_or(0);
                    if (for_preview && !best_preview)
                        || rarity < best_rarity
                        || (rarity == best_rarity && begun && !best_begun)
                    {
                        best = Some((part, begun, for_preview));
                        equal = 1;
                    } else if rarity == best_rarity && begun == best_begun && for_preview == best_preview {
                        // Each of the equal parts seen so far is kept with the same chance.
                        equal = equal.saturating_add(1);
                        if random.random_range(0..equal) == 0 {
                            best = Some((part, begun, for_preview));
                        }
                    }
                }
                None => {
                    best = Some((part, begun, for_preview));
                    equal = 1;
                }
            }
        }
        best.map(|(part, _, _)| part)
    }
}

/// The bytes still missing, the requested ones and the sources of each part.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PartMap {
    pub size: u64,
    pub missing: Vec<(u64, u64)>,
    pub requested: Vec<(u64, u64)>,
    pub sources: Vec<u32>,
}

/// Writes `contents` to `met` crash-safely, dated as the `data` file is now: a data file changed since has its parts
/// checked again. The previous file is kept as backup.
pub fn write_met(met: &Path, contents: &Contents, data: &Path) -> io::Result<()> {
    let date = data_date(data).unwrap_or_else(now_seconds);
    let dated = Contents { mtime: date, ..contents.clone() };
    let bytes = part_met::write(&dated).map_err(io::Error::other)?;
    atomic_files::write(met, &bytes)
}

/// Reads a part.met, or its backup when the file itself is gone or broken.
pub fn read_met(met: &Path) -> io::Result<Contents> {
    atomic_files::read(met, |data| part_met::read(data).map_err(|e| invalid(&e.to_string())))
}

/// `001`, `002`, ...: the base name of download number `n`.
pub fn file_name(number: u32) -> String {
    format!("{number:03}")
}

/// `[start, end)` of a data part.
pub fn part_range(size: u64, part: usize) -> Range<u64> {
    let start = ed2k::part_start(part);
    start..start.saturating_add(ed2k::part_length(size, part))
}

/// The bits of a part set as Java's `BitSet.toByteArray` writes them: bit `i` in byte `i / 8`, lowest bit first.
pub fn to_bytes(bits: &[bool]) -> Vec<u8> {
    let mut bytes = vec![0u8; bits.len().div_ceil(8)];
    for (i, _) in bits.iter().enumerate().filter(|(_, set)| **set) {
        if let Some(byte) = bytes.get_mut(i / 8) {
            *byte |= 1 << (i % 8);
        }
    }
    while bytes.last() == Some(&0) {
        bytes.pop();
    }
    bytes
}

fn bit(bytes: &[u8], i: usize) -> bool {
    bytes.get(i / 8).is_some_and(|byte| byte >> (i % 8) & 1 != 0)
}

pub fn has(bits: &[bool], i: usize) -> bool {
    bits.get(i).copied().unwrap_or(false)
}

fn set(bits: &mut [bool], i: usize, value: bool) {
    if let Some(bit) = bits.get_mut(i) {
        *bit = value;
    }
}

// A hash per eD2K part, the empty one after an exact multiple of PARTSIZE too, that hash to the file hash.
fn fits(size: u64, hash: &[u8; 16], hashes: &[[u8; 16]]) -> bool {
    !hashes.is_empty() && hashes.len() == ed2k::hash_set_size(size) && ed2k::file_hash(hashes).ok() == Some(*hash)
}

// Blocks end on the 180 KiB boundaries of the part, as AICH cuts it: a bad block is one AICH block.
fn cut_blocks(part_start: u64, ranges: &[Range<u64>], max: usize, blocks: &mut Vec<Block>) {
    for gap in ranges {
        let mut start = gap.start;
        while start < gap.end && blocks.len() < max {
            let offset = start.saturating_sub(part_start) / ed2k::EMBLOCKSIZE;
            let boundary = part_start.saturating_add(offset.saturating_add(1).saturating_mul(ed2k::EMBLOCKSIZE));
            let end = gap.end.min(boundary);
            blocks.push(Block { start, end });
            start = end;
        }
        if blocks.len() >= max {
            break;
        }
    }
}

// The parts of `ranges` outside `taken`.
fn subtract(ranges: Vec<Range<u64>>, taken: &RangeSet<u64>) -> Vec<Range<u64>> {
    ranges.into_iter().flat_map(|range| taken.gaps(&range).collect::<Vec<_>>()).collect()
}

pub fn insert(set: &mut RangeSet<u64>, range: Range<u64>) {
    if range.start < range.end {
        set.insert(range);
    }
}

pub fn remove(set: &mut RangeSet<u64>, range: Range<u64>) {
    if range.start < range.end {
        set.remove(range);
    }
}

fn data_date(data: &Path) -> Option<u32> {
    let modified = fs::metadata(data).ok()?.modified().ok()?;
    u32::try_from(modified.duration_since(UNIX_EPOCH).ok()?.as_secs()).ok()
}

fn now_seconds() -> u32 {
    std::time::SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|since| u32::try_from(since.as_secs()).ok())
        .unwrap_or(0)
}

fn status_code(status: Status) -> u64 {
    match status {
        Status::Paused => part_met::PAUSED,
        Status::Stopped => part_met::STOPPED,
        _ => 0,
    }
}

// The values other clients write too, so imported downloads keep their priority.
fn priority_code(priority: DownloadPriority) -> u64 {
    match priority {
        DownloadPriority::Low => 0,
        DownloadPriority::Normal => 1,
        DownloadPriority::High => 2,
        DownloadPriority::Auto => 5,
    }
}

fn priority(code: u64) -> DownloadPriority {
    match code {
        0 | 4 => DownloadPriority::Low,
        2 | 3 => DownloadPriority::High,
        5 => DownloadPriority::Auto,
        _ => DownloadPriority::Normal,
    }
}

fn invalid(reason: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, reason.to_owned())
}
