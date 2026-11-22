// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Puts the unpacked files of a new version in the place of the installed ones, so that both can be told apart until
//! the new version proves itself: each file is copied beside its old self as `name.new`, the old one renamed `name.old`
//! and the new one to the name. Windows lets a running program and its libraries be renamed, so the swap works while
//! they run; anything the package does not hold (a `portable.flag`, the data) stays as it is. What the swap did is kept
//! in a journal, so that it can be undone ([`Swap::roll_back`]) or made final ([`Swap::clean_up`]) by a later process.
//! For the macOS app the bundle is the one file swapped.

use std::fs::{self, File};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use walkdir::WalkDir;

use super::archives;
use super::commands::Commands;
use super::update_files::{self, delete_tree};

pub const OLD: &str = ".old";
pub const NEW: &str = ".new";

const REPLACED: &str = "replaced ";
const ADDED: &str = "added ";
const FOLDER: &str = "folder ";
const BUNDLE_STEP: &str = "bundle";

/// Copies a file or a whole bundle.
pub type Copier = Arc<dyn Fn(&Path, &Path) -> io::Result<()> + Send + Sync>;

/// A copy that keeps the permissions; a bundle is copied file by file.
pub fn files() -> Copier {
    Arc::new(copy_tree)
}

/// The bundle copied by macOS's `ditto`, which keeps the extended attributes a signature may need.
pub fn ditto(commands: Arc<dyn Commands>) -> Copier {
    Arc::new(move |from, to| {
        let command = ["ditto".to_owned(), from.to_string_lossy().into_owned(), to.to_string_lossy().into_owned()];
        commands.require(&command, Duration::from_secs(300)).map(|_| ())
    })
}

pub struct Swap {
    staged: PathBuf,
    target: PathBuf,
    journal: PathBuf,
    copier: Copier,
    bundle: bool,
}

impl Swap {
    /// The files unpacked in `staged` into the installed folder `target`, each copied by `copier`; the steps go to
    /// `journal`.
    pub fn folder(staged: &Path, target: &Path, journal: &Path, copier: Copier) -> Self {
        Self::new(staged, target, journal, copier, false)
    }

    /// The bundle `staged` in the place of the installed bundle `target`, copied by `copier`.
    pub fn bundle(staged: &Path, target: &Path, journal: &Path, copier: Copier) -> Self {
        Self::new(staged, target, journal, copier, true)
    }

    fn new(staged: &Path, target: &Path, journal: &Path, copier: Copier, bundle: bool) -> Self {
        Self {
            staged: staged.to_path_buf(),
            target: target.to_path_buf(),
            journal: journal.to_path_buf(),
            copier,
            bundle,
        }
    }

    /// Puts the new files in place; on any failure puts the old ones back and fails.
    pub fn apply(&self) -> io::Result<()> {
        fs::create_dir_all(update_files::parent(&self.journal)?)?;
        // Each step is written as soon as it is done: a process stopped half-way can be undone by the next.
        let mut steps = File::create(&self.journal)?;
        let mut done = Vec::new();
        let applied = if self.bundle {
            self.apply_bundle(&mut done, &mut steps)
        } else {
            self.apply_files(&mut done, &mut steps)
        };
        if let Err(e) = applied {
            let _ = self.undo(&done);
            return Err(e);
        }
        Ok(())
    }

    // Copied beside it first: the same volume, so that both renames are instant.
    fn apply_bundle(&self, done: &mut Vec<String>, steps: &mut File) -> io::Result<()> {
        let old = sibling(&self.target, OLD);
        let fresh = sibling(&self.target, NEW);
        delete_tree(&old)?;
        delete_tree(&fresh)?;
        (self.copier)(&self.staged, &fresh)?;
        fs::rename(&self.target, &old)?;
        step(done, steps, BUNDLE_STEP)?;
        fs::rename(&fresh, &self.target)
    }

    fn apply_files(&self, done: &mut Vec<String>, steps: &mut File) -> io::Result<()> {
        let files = files_below(&self.staged)?;
        // Every copy first: a full disk stops the swap before anything was renamed.
        for name in &files {
            let to = self.target.join(name);
            for missing in self.missing(to.parent()) {
                fs::create_dir(&missing)?;
                let relative = missing.strip_prefix(&self.target).unwrap_or(&missing);
                step(done, steps, &format!("{FOLDER}{}", slashed(relative)))?;
            }
            let fresh = sibling(&to, NEW);
            remove_file_if_there(&fresh)?;
            (self.copier)(&self.staged.join(name), &fresh)?;
        }
        for name in &files {
            let to = self.target.join(name);
            let fresh = sibling(&to, NEW);
            if fs::symlink_metadata(&to).is_ok() {
                let old = sibling(&to, OLD);
                // What an earlier update left, not yet made final: this version runs, so it is no longer needed.
                remove_file_if_there(&old)?;
                fs::rename(&to, &old)?;
                step(done, steps, &format!("{REPLACED}{name}"))?;
            } else {
                step(done, steps, &format!("{ADDED}{name}"))?;
            }
            fs::rename(&fresh, &to)?;
        }
        Ok(())
    }

    // The folders to create for `parent`, outermost first.
    fn missing(&self, parent: Option<&Path>) -> Vec<PathBuf> {
        let mut missing = Vec::new();
        let mut folder = parent;
        while let Some(path) = folder
            && path != self.target
            && !path.exists()
        {
            missing.insert(0, path.to_path_buf());
            folder = path.parent();
        }
        missing
    }

    /// Puts back what the journal says the swap replaced or added.
    pub fn roll_back(&self) -> io::Result<()> {
        self.undo(&self.read_journal()?)?;
        remove_file_if_there(&self.journal)
    }

    /// Removes the old files the swap kept: the new version is there to stay.
    pub fn clean_up(&self) -> io::Result<()> {
        let mut first = None;
        for step in self.read_journal()? {
            let place = if step == BUNDLE_STEP {
                self.target.clone()
            } else if let Some(name) = step.strip_prefix(REPLACED) {
                self.target.join(name)
            } else {
                continue;
            };
            // A file still in use, such as the program that waited for this one: tried again later.
            if let Err(e) = delete_tree_or_file(&sibling(&place, OLD)) {
                first.get_or_insert(e);
            }
        }
        if let Some(e) = first {
            return Err(e);
        }
        remove_file_if_there(&self.journal)
    }

    // In reverse: the files back, then the folders the swap made.
    fn undo(&self, done: &[String]) -> io::Result<()> {
        let mut first = None;
        for step in done.iter().rev() {
            if let Err(e) = self.undo_step(step) {
                tracing::warn!("Cannot undo \"{step}\" of the update: {e}");
                first.get_or_insert(e);
            }
        }
        first.map_or(Ok(()), Err)
    }

    fn undo_step(&self, step: &str) -> io::Result<()> {
        let replaced = if step == BUNDLE_STEP {
            Some(self.target.clone())
        } else {
            step.strip_prefix(REPLACED).map(|name| self.target.join(name))
        };
        if let Some(place) = replaced {
            let old = sibling(&place, OLD);
            if fs::symlink_metadata(&old).is_ok() {
                delete_tree_or_file(&place)?;
                fs::rename(&old, &place)?;
            }
        } else if let Some(name) = step.strip_prefix(ADDED) {
            remove_file_if_there(&self.target.join(name))?;
        } else if let Some(name) = step.strip_prefix(FOLDER) {
            let folder = self.target.join(name);
            if fs::read_dir(&folder)?.next().is_none() {
                fs::remove_dir(&folder)?;
            }
        }
        Ok(())
    }

    fn read_journal(&self) -> io::Result<Vec<String>> {
        if !self.journal.is_file() {
            return Ok(Vec::new());
        }
        let damaged = || {
            io::Error::new(io::ErrorKind::InvalidData, format!("a damaged update journal: {}", self.journal.display()))
        };
        let mut steps = Vec::new();
        for line in fs::read_to_string(&self.journal)?.lines() {
            let step = line.trim();
            if step == BUNDLE_STEP {
                steps.push(step.to_owned());
                continue;
            }
            let name =
                [REPLACED, ADDED, FOLDER].iter().find_map(|prefix| step.strip_prefix(prefix)).ok_or_else(damaged)?;
            // The journal is ours, in the cache; still, no step leaves the target.
            if archives::checked(name).ok().as_deref() != Some(name) {
                return Err(damaged());
            }
            steps.push(step.to_owned());
        }
        Ok(steps)
    }
}

fn step(done: &mut Vec<String>, steps: &mut File, step: &str) -> io::Result<()> {
    writeln!(steps, "{step}")?;
    steps.flush()?;
    done.push(step.to_owned());
    Ok(())
}

fn sibling(path: &Path, suffix: &str) -> PathBuf {
    let mut name = path.file_name().unwrap_or_default().to_os_string();
    name.push(suffix);
    path.with_file_name(name)
}

fn slashed(path: &Path) -> String {
    path.to_string_lossy().replace('\\', "/")
}

// The regular files below `root`, by their names relative to it with forward slashes.
fn files_below(root: &Path) -> io::Result<Vec<String>> {
    let mut names = Vec::new();
    for entry in WalkDir::new(root).follow_links(false) {
        let entry = entry.map_err(io::Error::other)?;
        if entry.file_type().is_dir() {
            continue;
        }
        if !entry.file_type().is_file() {
            return Err(io::Error::other(format!("not a regular file: {}", entry.path().display())));
        }
        names.push(slashed(entry.path().strip_prefix(root).unwrap_or(entry.path())));
    }
    names.sort();
    Ok(names)
}

fn copy_tree(from: &Path, to: &Path) -> io::Result<()> {
    if !fs::symlink_metadata(from)?.is_dir() {
        fs::copy(from, to)?;
        return Ok(());
    }
    for entry in WalkDir::new(from).follow_links(false) {
        let entry = entry.map_err(io::Error::other)?;
        let place = to.join(entry.path().strip_prefix(from).unwrap_or(entry.path()));
        if entry.file_type().is_dir() {
            fs::create_dir_all(&place)?;
        } else {
            fs::copy(entry.path(), &place)?;
        }
    }
    Ok(())
}

fn delete_tree_or_file(path: &Path) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_dir() => delete_tree(path),
        Ok(_) => fs::remove_file(path),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e),
    }
}

fn remove_file_if_there(file: &Path) -> io::Result<()> {
    match fs::remove_file(file) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}
