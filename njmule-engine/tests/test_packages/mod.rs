// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Packages as the release builds them, for tests: a zip or a tar.xz of named files.

#![allow(dead_code, clippy::unwrap_used, clippy::expect_used)]

use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};

use lzma_rust2::{XzOptions, XzWriter};
use tar::{Builder, EntryType, Header};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipWriter};

/// A file of a package: its text and Unix permissions, or a link to `link`.
#[derive(Debug, Clone)]
pub struct Entry {
    pub text: String,
    pub mode: u32,
    pub link: Option<String>,
}

impl Entry {
    pub fn file(text: &str) -> Self {
        Self { text: text.to_owned(), mode: 0o644, link: None }
    }

    pub fn program(text: &str) -> Self {
        Self { text: text.to_owned(), mode: 0o755, link: None }
    }

    pub fn link_to(target: &str) -> Self {
        Self { text: String::new(), mode: 0o777, link: Some(target.to_owned()) }
    }
}

/// Writes the zip `file` with `entries` by their names, in their order.
pub fn zip(file: &Path, entries: &[(String, Entry)]) -> PathBuf {
    fs::create_dir_all(file.parent().unwrap()).unwrap();
    let mut zip = ZipWriter::new(File::create(file).unwrap());
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
    for (name, entry) in entries {
        zip.start_file(name.as_str(), options).unwrap();
        zip.write_all(entry.text.as_bytes()).unwrap();
    }
    zip.finish().unwrap();
    file.to_path_buf()
}

/// Writes the tar.xz `file` with `entries` by their names, in their order.
pub fn tar_xz(file: &Path, entries: &[(String, Entry)]) -> PathBuf {
    fs::create_dir_all(file.parent().unwrap()).unwrap();
    let xz = XzWriter::new(File::create(file).unwrap(), XzOptions::with_preset(6)).unwrap();
    let mut tar = Builder::new(xz);
    for (name, entry) in entries {
        let mut header = Header::new_gnu();
        header.set_mode(entry.mode);
        match &entry.link {
            Some(target) => {
                header.set_entry_type(EntryType::Symlink);
                header.set_size(0);
                tar.append_link(&mut header, name, target).unwrap();
            }
            None => {
                header.set_entry_type(EntryType::Regular);
                header.set_size(entry.text.len() as u64);
                tar.append_data(&mut header, name, entry.text.as_bytes()).unwrap();
            }
        }
    }
    tar.into_inner().unwrap().finish().unwrap();
    file.to_path_buf()
}
