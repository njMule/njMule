// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Tests for package download.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fs;
use std::io;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use njmule_engine::api::new_version::Download;
use njmule_engine::update::package_download::{self, part_of};
use sha2::{Digest, Sha256};

use crate::fake_web::FakeWeb;

const URL: &str = "https://github.com/njMule/njMule/releases/download/v0.2.0/njmule.zip";

#[tokio::test]
async fn should_keep_a_package_that_matches_its_signed_hash() {
    let mut test = Downloading::new();
    test.given_the_web_serves(&package());
    test.given_the_release_says(&package());

    test.when_downloaded().await;

    test.then_no_failure_occurred();
    test.then_the_file_holds(&package());
    test.then_the_progress_ended_at(package().len() as u64);
}

#[tokio::test]
async fn should_go_on_from_what_an_earlier_try_left() {
    let mut test = Downloading::new();
    test.given_the_web_serves(&package());
    test.given_the_release_says(&package());
    test.given_an_earlier_try_cut_after(1000).await;

    test.when_downloaded().await;

    test.then_no_failure_occurred();
    test.then_the_file_holds(&package());
}

#[tokio::test]
async fn should_leave_what_came_for_the_next_try_when_the_line_drops() {
    let mut test = Downloading::new();
    test.given_the_web_serves(&package());
    test.given_the_release_says(&package());
    test.given_the_line_drops_after(1000);

    test.when_downloaded().await;

    test.then_it_failed_with("stopped at 1000");
    test.then_the_part_holds(1000);
}

#[tokio::test]
async fn should_refuse_a_package_that_does_not_match_its_signed_hash() {
    let mut test = Downloading::new();
    test.given_the_web_serves(b"a package someone changed");
    test.given_the_release_says(b"a package someone sealed");

    test.when_downloaded().await;

    test.then_it_failed_with("does not match its signed hash");
    test.then_nothing_is_kept();
}

#[tokio::test]
async fn should_not_download_a_package_that_is_there_already() {
    let mut test = Downloading::new();
    test.given_the_release_says(&package());
    test.given_the_package_is_there(&package());

    test.when_downloaded().await;

    test.then_no_failure_occurred();
    test.then_the_web_was_not_asked();
}

struct Downloading {
    dir: tempfile::TempDir,
    web: FakeWeb,
    progress: Arc<Mutex<Vec<u64>>>,
    download: Option<Download>,
    file: PathBuf,
    failure: Option<io::Error>,
}

impl Downloading {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("0.2.0").join("njmule.zip");
        Self { dir, web: FakeWeb::default(), progress: Arc::default(), download: None, file, failure: None }
    }

    fn given_the_web_serves(&self, body: &[u8]) {
        self.web.serve(URL, body);
    }

    fn given_the_release_says(&mut self, body: &[u8]) {
        let sha256 = hex::encode(Sha256::digest(body));
        self.download = Some(Download { url: URL.to_owned(), size: body.len() as u64, sha256 });
    }

    async fn given_an_earlier_try_cut_after(&mut self, bytes: u64) {
        self.given_the_line_drops_after(bytes);
        // The line dropped, as it was asked to.
        let _ = self.fetch().await;
    }

    fn given_the_line_drops_after(&self, bytes: u64) {
        self.web.cut_next_download_after(bytes);
    }

    fn given_the_package_is_there(&self, body: &[u8]) {
        fs::create_dir_all(self.file.parent().unwrap()).unwrap();
        fs::write(&self.file, body).unwrap();
    }

    async fn when_downloaded(&mut self) {
        self.failure = self.fetch().await.err();
    }

    async fn fetch(&self) -> io::Result<PathBuf> {
        let progress = Arc::clone(&self.progress);
        let told = move |bytes: u64| progress.lock().unwrap().push(bytes);
        package_download::fetch(&self.web, self.download.as_ref().unwrap(), &self.file, &told).await
    }

    fn then_no_failure_occurred(&self) {
        assert!(self.failure.is_none(), "{:?}", self.failure);
    }

    fn then_it_failed_with(&self, words: &str) {
        let failure = self.failure.as_ref().expect("a failure");
        assert!(failure.to_string().contains(words), "{failure}");
    }

    fn then_the_file_holds(&self, body: &[u8]) {
        assert_eq!(fs::read(&self.file).unwrap(), body);
        assert!(!part_of(&self.file).exists());
    }

    fn then_the_part_holds(&self, bytes: u64) {
        assert_eq!(fs::metadata(part_of(&self.file)).unwrap().len(), bytes);
        assert!(!self.file.exists());
    }

    fn then_nothing_is_kept(&self) {
        assert!(!self.file.exists());
        assert!(!part_of(&self.file).exists());
    }

    fn then_the_progress_ended_at(&self, bytes: u64) {
        assert_eq!(self.progress.lock().unwrap().last(), Some(&bytes));
    }

    fn then_the_web_was_not_asked(&self) {
        assert!(self.web.asked().is_empty());
        assert!(self.dir.path().exists());
    }
}

fn package() -> Vec<u8> {
    "the package of njMule 0.2.0".repeat(100).into_bytes()
}
