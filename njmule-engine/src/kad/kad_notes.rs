// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What Kad nodes said of our files: the notes found by searching Kad, on request and once a run for each download.
//! Each publisher counts once, with its latest note; the comment filter applies when they are read. A search's notes
//! and its end come back through [`KadNotes::found`] and [`KadNotes::ended`].

use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

use indexmap::IndexMap;
use tokio::sync::oneshot;

use crate::api::file_comment::FileComment;
use crate::api::kad_note::KadNote;
use crate::files::comment_filter::CommentFilter;
use crate::kad::kad_index;
use crate::kad::uint128::UInt128;

/// Notes kept per file at most.
pub const MAX_NOTES_PER_FILE: usize = kad_index::MAX_NOTES_PER_FILE;
/// Notes kept in all at most: the files searched longest ago lose theirs first.
pub const MAX_NOTES: usize = 10_000;
/// Files whose notes are kept at most, likewise.
pub const MAX_FILES: usize = 1_000;
/// Searches nobody asked for start this far apart at least, one at a time.
pub const AUTOMATIC_EVERY: Duration = Duration::from_secs(10);

/// A note a search found.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Note {
    pub publisher: UInt128,
    pub file_name: String,
    pub size: u64,
    pub comment: FileComment,
}

/// Kad, while it is connected.
pub trait Finder {
    /// Whether the node knows the network well enough for searches nobody asked for.
    fn is_ready(&self) -> bool;

    /// Starts a search for the notes on a file; false when it cannot start now.
    fn find_notes(&mut self, file: [u8; 16], size: u64) -> bool;
}

/// A download whose notes are looked for once a run.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Download {
    pub hash: [u8; 16],
    pub size: u64,
}

/// Whoever waits for the end of a search: the notes then known, or why there was no search.
pub type Waiter = oneshot::Sender<Result<Vec<KadNote>, String>>;

#[derive(Debug)]
pub struct KadNotes {
    // By file, searched longest ago first; then by publisher, oldest first.
    found: IndexMap<[u8; 16], IndexMap<UInt128, Note>>,
    // The searches running now, with whoever waits for their end.
    running: HashMap<[u8; 16], Vec<Waiter>>,
    searched: HashSet<[u8; 16]>,
    kept: usize,
    next_automatic: Instant,
}

impl KadNotes {
    pub fn new(now: Instant) -> Self {
        Self { found: IndexMap::new(), running: HashMap::new(), searched: HashSet::new(), kept: 0, next_automatic: now }
    }

    /// The notes found on `file`, without those the comment filter leaves out.
    pub fn notes(&self, file: &[u8; 16], filter: &CommentFilter) -> Vec<KadNote> {
        let Some(notes) = self.found.get(file) else {
            return Vec::new();
        };
        notes
            .values()
            // The file name may carry the spam as well as the comment.
            .filter(|note| !filter.leaves_out(&note.file_name) && !filter.leaves_out(note.comment.text()))
            .map(|note| KadNote {
                publisher: note.publisher.to_hex(),
                file_name: note.file_name.clone(),
                comment: note.comment.clone(),
            })
            .collect()
    }

    /// Searches Kad, none while it is not connected, for the notes on `file` of `size` bytes, or joins the search
    /// running for it; `done` gets the notes then known once it ends, or why it could not start.
    pub fn search(&mut self, file: [u8; 16], size: u64, kad: Option<&mut dyn Finder>, done: Waiter) {
        if !self.running.contains_key(&file) {
            let Some(finder) = kad else {
                let _ = done.send(Err("Kad is not connected".to_owned()));
                return;
            };
            if !self.start(finder, file, size) {
                let _ = done.send(Err("Kad is busy with this file: try again in a minute".to_owned()));
                return;
            }
        }
        self.running.entry(file).or_default().push(done);
    }

    /// Runs every second: now and then searches for the notes on a download not searched for in this run yet.
    pub fn tick(&mut self, now: Instant, kad: Option<&mut dyn Finder>, downloads: &[Download]) {
        if !self.running.is_empty() || now < self.next_automatic {
            return;
        }
        self.next_automatic = now.checked_add(AUTOMATIC_EVERY).unwrap_or(now);
        let Some(finder) = kad.filter(|finder| finder.is_ready()) else {
            return;
        };
        let mut current = HashSet::new();
        let mut started = false;
        for download in downloads {
            current.insert(download.hash);
            if !started && !self.searched.contains(&download.hash) {
                started = self.start(&mut *finder, download.hash, download.size);
            }
        }
        // A download taken away and added again is searched for again.
        self.searched.retain(|file| current.contains(file));
    }

    /// Kad stopped: the searches running end with what they found.
    pub fn kad_stopped(&mut self, filter: &CommentFilter) {
        let running: Vec<[u8; 16]> = self.running.keys().copied().collect();
        for file in running {
            self.ended(&file, filter);
        }
    }

    /// Notes a search for `file` found.
    pub fn found(&mut self, file: &[u8; 16], notes: Vec<Note>) {
        if !self.running.contains_key(file) {
            return;
        }
        let known = self.found.entry(*file).or_default();
        for note in notes {
            if known.shift_remove(&note.publisher).is_some() {
                self.kept = self.kept.saturating_sub(1);
            }
            if known.len() >= MAX_NOTES_PER_FILE && known.shift_remove_index(0).is_some() {
                self.kept = self.kept.saturating_sub(1);
            }
            known.insert(note.publisher, note);
            self.kept = self.kept.saturating_add(1);
        }
        let mut at = 0;
        while self.kept > MAX_NOTES && at < self.found.len() {
            match self.found.get_index(at) {
                Some((oldest, _)) if oldest != file => {
                    if let Some((_, gone)) = self.found.shift_remove_index(at) {
                        self.kept = self.kept.saturating_sub(gone.len());
                    }
                }
                _ => at = at.saturating_add(1),
            }
        }
    }

    /// The search for `file` ended: whoever waits for it gets the notes then known.
    pub fn ended(&mut self, file: &[u8; 16], filter: &CommentFilter) {
        if let Some(waiting) = self.running.remove(file) {
            let notes = self.notes(file, filter);
            for waiter in waiting {
                let _ = waiter.send(Ok(notes.clone()));
            }
        }
    }

    fn start(&mut self, finder: &mut dyn Finder, file: [u8; 16], size: u64) -> bool {
        if !finder.find_notes(file, size) {
            return false;
        }
        self.running.entry(file).or_default();
        self.searched.insert(file);
        // The file searched last keeps its notes longest.
        let notes = self.found.shift_remove(&file).unwrap_or_default();
        self.found.insert(file, notes);
        while self.found.len() > MAX_FILES {
            if let Some((_, gone)) = self.found.shift_remove_index(0) {
                self.kept = self.kept.saturating_sub(gone.len());
            }
        }
        true
    }
}
