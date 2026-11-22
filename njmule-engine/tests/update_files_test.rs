// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's UpdateFilesTest, under its names.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::BTreeSet;
use std::fs;
use std::io;

use njmule_engine::update::update_files::{PLAN_FILE, UpdateFiles};

#[test]
fn should_keep_only_the_versions_asked_for_and_the_plan() {
    let mut test = Files::new();
    test.given_the_folder("0.1.5/njmule.zip");
    test.given_the_folder("0.2.0/njmule.zip");
    test.given_the_folder("0.3.0-beta.1/files/njmule.exe");
    test.given_the_file("started-0.1.5");
    test.given_the_file("started-0.2.0");
    test.given_the_file(PLAN_FILE);

    test.when_keeping_only(&["0.2.0"]);

    test.then_what_is_left_is(&["0.2.0", "started-0.2.0", PLAN_FILE]);
}

#[test]
fn should_keep_the_mark_of_the_version_running() {
    let mut test = Files::new();
    test.given_the_folder("0.2.0/njmule.zip");
    test.given_the_file("started-0.1.5");
    test.given_the_running_version("0.1.5");

    test.when_keeping_only(&[]);

    test.then_what_is_left_is(&["started-0.1.5"]);
}

#[test]
fn should_refuse_a_version_that_is_not_one() {
    for version in ["..", "../0.2.0", "0.2.0/../..", "", ".hidden", "0.2.0\\..\\x"] {
        let mut test = Files::new();

        test.when_the_folder_of_is_asked(version);

        test.then_it_was_refused();
    }
}

struct Files {
    _dir: tempfile::TempDir,
    files: UpdateFiles,
    running: String,
    asked: Option<io::Result<std::path::PathBuf>>,
}

impl Files {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let files = UpdateFiles::in_cache(dir.path());
        Self { _dir: dir, files, running: "0.1.0".to_owned(), asked: None }
    }

    fn given_the_folder(&self, file: &str) {
        let path = self.files.root().join(file);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, "x").unwrap();
    }

    fn given_the_file(&self, name: &str) {
        fs::create_dir_all(self.files.root()).unwrap();
        fs::write(self.files.root().join(name), "x").unwrap();
    }

    fn given_the_running_version(&mut self, version: &str) {
        self.running = version.to_owned();
    }

    fn when_keeping_only(&mut self, versions: &[&str]) {
        self.files.keep_only(versions, &self.running);
    }

    fn when_the_folder_of_is_asked(&mut self, version: &str) {
        self.asked = Some(self.files.version(version));
    }

    fn then_what_is_left_is(&self, names: &[&str]) {
        let left: BTreeSet<String> = fs::read_dir(self.files.root())
            .unwrap()
            .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        assert_eq!(left, names.iter().map(|name| (*name).to_owned()).collect());
    }

    fn then_it_was_refused(&self) {
        let asked = self.asked.as_ref().unwrap();
        assert!(matches!(asked, Err(e) if e.kind() == io::ErrorKind::InvalidInput), "{asked:?}");
    }
}
