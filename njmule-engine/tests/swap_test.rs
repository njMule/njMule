// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Tests for swap.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use njmule_engine::update::swap::{self, Copier, Swap};

const JOURNAL: &str = "swap.journal";

#[test]
fn should_put_the_new_files_in_place_and_keep_the_old_ones() {
    let mut test = Swapping::new();
    test.given_the_installed_file("njmule.exe", "0.1.0");
    test.given_the_new_file("njmule.exe", "0.2.0");

    test.when_applied();

    test.then_no_failure_occurred();
    test.then_the_target_holds("njmule.exe", "0.2.0");
    test.then_the_target_holds("njmule.exe.old", "0.1.0");
}

#[test]
fn should_leave_what_the_package_does_not_hold() {
    let mut test = Swapping::new();
    test.given_the_installed_file("njmule.exe", "0.1.0");
    test.given_the_installed_file("portable.flag", "");
    test.given_the_installed_file("config/njmule.properties", "user.nick=me");
    test.given_the_new_file("njmule.exe", "0.2.0");

    test.when_applied();

    test.then_the_target_holds("portable.flag", "");
    test.then_the_target_holds("config/njmule.properties", "user.nick=me");
}

#[test]
fn should_add_the_files_and_folders_only_the_new_version_has() {
    let mut test = Swapping::new();
    test.given_the_installed_file("njmule.exe", "0.1.0");
    test.given_the_new_file("njmule.exe", "0.2.0");
    test.given_the_new_file("lib/fx/glass.dll", "dll");

    test.when_applied();

    test.then_the_target_holds("lib/fx/glass.dll", "dll");
}

#[test]
fn should_put_the_old_version_back_when_asked_later() {
    let mut test = Swapping::new();
    test.given_the_installed_file("njmule.exe", "0.1.0");
    test.given_the_new_file("njmule.exe", "0.2.0");
    test.given_the_new_file("lib/fx/glass.dll", "dll");
    test.given_applied();

    test.when_rolled_back();

    test.then_no_failure_occurred();
    test.then_the_target_holds("njmule.exe", "0.1.0");
    test.then_the_target_has_no("njmule.exe.old");
    test.then_the_target_has_no("lib");
}

#[test]
fn should_put_the_old_version_back_when_the_swap_fails() {
    let mut test = Swapping::new();
    test.given_the_installed_file("a.dll", "old a");
    test.given_the_installed_file("b.dll", "old b");
    test.given_the_new_file("a.dll", "new a");
    test.given_the_new_file("b.dll", "new b");
    test.given_a_copy_that_fails_on("b.dll");

    test.when_applied();

    test.then_a_failure_occurred();
    test.then_the_target_holds("a.dll", "old a");
    test.then_the_target_holds("b.dll", "old b");
    test.then_the_target_has_no("a.dll.old");
}

#[test]
fn should_remove_the_old_files_once_the_new_version_stays() {
    let mut test = Swapping::new();
    test.given_the_installed_file("njmule.exe", "0.1.0");
    test.given_the_new_file("njmule.exe", "0.2.0");
    test.given_applied();

    test.when_cleaned_up();

    test.then_no_failure_occurred();
    test.then_the_target_holds("njmule.exe", "0.2.0");
    test.then_the_target_has_no("njmule.exe.old");
}

#[test]
fn should_replace_old_files_an_earlier_update_left() {
    let mut test = Swapping::new();
    test.given_the_installed_file("njmule.exe", "0.1.5");
    test.given_the_installed_file("njmule.exe.old", "0.1.0");
    test.given_the_new_file("njmule.exe", "0.2.0");

    test.when_applied();

    test.then_the_target_holds("njmule.exe.old", "0.1.5");
}

#[test]
fn should_swap_a_whole_bundle() {
    let mut test = Swapping::new();
    test.given_an_installed_bundle("0.1.0");
    test.given_a_new_bundle("0.2.0");

    test.when_applied();

    test.then_no_failure_occurred();
    test.then_the_bundle_is("0.2.0");
    test.then_the_old_bundle_is("0.1.0");
}

#[test]
fn should_put_the_old_bundle_back() {
    let mut test = Swapping::new();
    test.given_an_installed_bundle("0.1.0");
    test.given_a_new_bundle("0.2.0");
    test.given_applied();

    test.when_rolled_back();

    test.then_the_bundle_is("0.1.0");
    test.then_there_is_no_old_bundle();
}

#[test]
fn should_remove_the_old_bundle() {
    let mut test = Swapping::new();
    test.given_an_installed_bundle("0.1.0");
    test.given_a_new_bundle("0.2.0");
    test.given_applied();

    test.when_cleaned_up();

    test.then_the_bundle_is("0.2.0");
    test.then_there_is_no_old_bundle();
}

struct Swapping {
    dir: tempfile::TempDir,
    staged: PathBuf,
    target: PathBuf,
    copier: Copier,
    failure: Option<io::Error>,
}

impl Swapping {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        Self { dir, staged: PathBuf::new(), target: PathBuf::new(), copier: swap::files(), failure: None }
    }

    fn given_the_installed_file(&mut self, name: &str, text: &str) {
        self.target = self.dir.path().join("installed");
        write(&self.target.join(name), text);
    }

    fn given_the_new_file(&mut self, name: &str, text: &str) {
        self.staged = self.dir.path().join("cache").join("files");
        write(&self.staged.join(name), text);
    }

    fn given_an_installed_bundle(&mut self, version: &str) {
        self.target = self.dir.path().join("Applications").join("njMule.app");
        write(&self.target.join("Contents/Info.plist"), version);
    }

    fn given_a_new_bundle(&mut self, version: &str) {
        self.staged = self.dir.path().join("cache").join("files").join("njMule.app");
        write(&self.staged.join("Contents/Info.plist"), version);
    }

    fn given_a_copy_that_fails_on(&mut self, name: &str) {
        let name = name.to_owned();
        self.copier = Arc::new(move |from: &Path, to: &Path| {
            if from.file_name().is_some_and(|file| file.to_string_lossy() == name) {
                return Err(io::Error::other("disk full"));
            }
            swap::files()(from, to)
        });
    }

    fn given_applied(&self) {
        self.swap().apply().unwrap();
    }

    fn when_applied(&mut self) {
        self.failure = self.swap().apply().err();
    }

    fn when_rolled_back(&mut self) {
        self.failure = self.swap().roll_back().err();
    }

    fn when_cleaned_up(&mut self) {
        self.failure = self.swap().clean_up().err();
    }

    fn swap(&self) -> Swap {
        let journal = self.dir.path().join("cache").join(JOURNAL);
        if self.target.to_string_lossy().ends_with(".app") {
            Swap::bundle(&self.staged, &self.target, &journal, self.copier.clone())
        } else {
            Swap::folder(&self.staged, &self.target, &journal, self.copier.clone())
        }
    }

    fn then_no_failure_occurred(&self) {
        assert!(self.failure.is_none(), "{:?}", self.failure);
    }

    fn then_a_failure_occurred(&self) {
        assert!(self.failure.is_some());
    }

    fn then_the_target_holds(&self, name: &str, text: &str) {
        assert_eq!(fs::read_to_string(self.target.join(name)).unwrap(), text);
    }

    fn then_the_target_has_no(&self, name: &str) {
        assert!(!self.target.join(name).exists());
    }

    fn then_the_bundle_is(&self, version: &str) {
        assert_eq!(fs::read_to_string(self.target.join("Contents/Info.plist")).unwrap(), version);
    }

    fn then_the_old_bundle_is(&self, version: &str) {
        let old = self.target.with_file_name("njMule.app.old").join("Contents/Info.plist");
        assert_eq!(fs::read_to_string(old).unwrap(), version);
    }

    fn then_there_is_no_old_bundle(&self) {
        assert!(!self.target.with_file_name("njMule.app.old").exists());
    }
}

fn write(file: &Path, text: &str) {
    fs::create_dir_all(file.parent().unwrap()).unwrap();
    fs::write(file, text).unwrap();
}
