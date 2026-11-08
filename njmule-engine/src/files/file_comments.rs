// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Our ratings and comments of files, kept in `fileinfo.ini` as other clients keep theirs: a section for each file,
//! named by its hash, with its `Comment` and `Rate`.

use std::collections::BTreeMap;
use std::io;
use std::path::{Path, PathBuf};

use data_encoding::{HEXLOWER_PERMISSIVE, HEXUPPER};

use crate::api::file_comment::FileComment;
use crate::persist::atomic_files;
use crate::persist::format::ini_file::IniFile;

pub const FILE_NAME: &str = "fileinfo.ini";

#[derive(Debug)]
pub struct FileComments {
    file: PathBuf,
    comments: BTreeMap<[u8; 16], FileComment>,
}

impl FileComments {
    /// No comments, kept in `file` once there are.
    pub fn empty(file: &Path) -> Self {
        Self { file: file.to_path_buf(), comments: BTreeMap::new() }
    }

    /// Reads `file`, or its backup when it is missing or unreadable; none holds no comments. A section that names no
    /// file is left out, and a rating out of range is brought into it.
    pub fn load(file: &Path) -> io::Result<Self> {
        let mut store = Self::empty(file);
        atomic_files::cleanup_interrupted_write(file);
        let ini = match atomic_files::read(file, |data| Ok(IniFile::parse(data))) {
            Ok(ini) => ini,
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(store),
            Err(e) => return Err(e),
        };
        for section in ini.sections() {
            let Some(hash) = HEXLOWER_PERMISSIVE
                .decode(section.trim().as_bytes())
                .ok()
                .and_then(|bytes| <[u8; 16]>::try_from(bytes).ok())
            else {
                continue;
            };
            let rating = ini.get_long(section, "Rate").unwrap_or(0).clamp(0, i64::from(FileComment::MAX_RATING));
            let comment = FileComment::of(ini.get(section, "Comment").unwrap_or(""), u8::try_from(rating).unwrap_or(0));
            if !comment.is_empty() {
                store.comments.insert(hash, comment);
            }
        }
        Ok(store)
    }

    pub fn get(&self, file: &[u8; 16]) -> Option<FileComment> {
        self.comments.get(file).cloned()
    }

    /// Keeps `comment` for `file`, and saves the comments when they changed; an empty one takes the comment away.
    pub fn set(&mut self, file: &[u8; 16], comment: FileComment) -> io::Result<()> {
        let changed = if comment.is_empty() {
            self.comments.remove(file).is_some()
        } else {
            self.comments.insert(*file, comment.clone()) != Some(comment)
        };
        if changed { self.save() } else { Ok(()) }
    }

    fn save(&self) -> io::Result<()> {
        let mut text = String::new();
        for (hash, comment) in &self.comments {
            text.push_str(&format!(
                "[{}]\r\nComment={}\r\nRate={}\r\n",
                HEXUPPER.encode(hash),
                comment.text(),
                comment.rating()
            ));
        }
        atomic_files::write(&self.file, text.as_bytes())
    }
}
