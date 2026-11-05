// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's FinishedDownloadsTest, under their names.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use std::fs;

use njmule_engine::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus};
use njmule_engine::download::finished_downloads::{FILE_NAME, FinishedDownloads};
use njmule_engine::files::part_file::PartMap;

const FILM: [u8; 16] = [1; 16];
const TALK: [u8; 16] = [2; 16];

fn completed(hash: [u8; 16], name: &str, category: u32) -> DownloadInfo {
    DownloadInfo {
        hash,
        name: name.to_owned(),
        size: 5000,
        completed: 3000,
        status: DownloadStatus::Downloading,
        priority: DownloadPriority::High,
        category,
        sources: 4,
        transferring: 1,
        bytes_per_second: 900,
        parts: PartMap { size: 5000, missing: vec![(3000, 5000)], requested: Vec::new(), sources: vec![4] },
        preview_first: false,
        auto_swap: false,
    }
}

fn names(finished: &FinishedDownloads) -> Vec<String> {
    finished.downloads().map(|d| d.name.clone()).collect()
}

#[test]
fn should_show_a_download_that_completed_as_complete_with_every_byte() {
    let dir = tempfile::tempdir().unwrap();
    let mut finished = FinishedDownloads::load(dir.path());

    finished.add(&completed(FILM, "film.mkv", 3));

    let shown: Vec<&DownloadInfo> = finished.downloads().collect();
    assert_eq!(
        shown,
        [&DownloadInfo {
            hash: FILM,
            name: "film.mkv".to_owned(),
            size: 5000,
            completed: 5000,
            status: DownloadStatus::Complete,
            priority: DownloadPriority::High,
            category: 3,
            sources: 0,
            transferring: 0,
            bytes_per_second: 0,
            parts: PartMap { size: 5000, missing: Vec::new(), requested: Vec::new(), sources: Vec::new() },
            preview_first: false,
            auto_swap: false,
        }]
    );
}

#[test]
fn should_keep_the_completed_downloads_across_a_restart() {
    let dir = tempfile::tempdir().unwrap();
    let mut finished = FinishedDownloads::load(dir.path());
    finished.add(&completed(FILM, "film.mkv", 0));
    finished.add(&completed(TALK, "talk.mp3", 1));

    let again = FinishedDownloads::load(dir.path());

    assert_eq!(names(&again), ["film.mkv", "talk.mp3"]);
}

#[test]
fn should_forget_every_completed_download_when_cleared() {
    let dir = tempfile::tempdir().unwrap();
    let mut finished = FinishedDownloads::load(dir.path());
    finished.add(&completed(FILM, "film.mkv", 0));
    finished.add(&completed(TALK, "talk.mp3", 0));

    let cleared = finished.clear();

    assert_eq!(cleared, [FILM, TALK]);
    assert!(names(&FinishedDownloads::load(dir.path())).is_empty());
}

#[test]
fn should_forget_a_completed_download_downloaded_again() {
    let dir = tempfile::tempdir().unwrap();
    let mut finished = FinishedDownloads::load(dir.path());
    finished.add(&completed(FILM, "film.mkv", 0));
    finished.add(&completed(TALK, "talk.mp3", 0));

    let removed = finished.remove(&FILM);

    assert!(removed);
    assert_eq!(names(&finished), ["talk.mp3"]);
}

#[test]
fn should_start_with_none_from_a_file_it_cannot_read() {
    let dir = tempfile::tempdir().unwrap();
    fs::write(dir.path().join(FILE_NAME), r#"{"format":1,"downloads":[{"hash":"nothex"}]}"#).unwrap();

    let finished = FinishedDownloads::load(dir.path());

    assert!(names(&finished).is_empty());
}
