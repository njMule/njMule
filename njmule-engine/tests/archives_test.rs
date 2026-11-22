// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ArchivesTest, under its names.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::BTreeSet;
use std::fs;
use std::io;
use std::path::PathBuf;

use njmule_engine::update::archives;

use crate::test_packages::{self, Entry};

#[test]
fn should_unpack_a_zip_without_its_top_folder() {
    let mut test = Unpacking::new();
    test.given_the_file("njmule-0.2.0-windows-x64/njmule.exe", Entry::program("exe"));
    test.given_the_file("njmule-0.2.0-windows-x64/lib/glass.dll", Entry::file("dll"));
    test.given_a_zip("njmule-0.2.0-windows-x64.zip");

    test.when_unpacked();

    test.then_no_failure_occurred();
    test.then_the_target_holds("njmule.exe", "exe");
    test.then_the_target_holds("lib/glass.dll", "dll");
    test.then_the_target_has_no("njmule-0.2.0-windows-x64");
}

#[test]
fn should_read_the_backslashes_of_a_zip_written_on_windows() {
    let mut test = Unpacking::new();
    test.given_the_file("njmule-0.2.0-windows-x64\\njmule.exe", Entry::program("exe"));
    test.given_a_zip("njmule-0.2.0-windows-x64.zip");

    test.when_unpacked();

    test.then_the_target_holds("njmule.exe", "exe");
}

#[test]
fn should_unpack_a_tar_xz_without_its_top_folder() {
    let mut test = Unpacking::new();
    test.given_the_file("njmule-0.2.0-linux-x64/njmule-gui", Entry::program("gui"));
    test.given_the_file("njmule-0.2.0-linux-x64/share/njmule-icon.svg", Entry::file("svg"));
    test.given_a_tar_xz("njmule-0.2.0-linux-x64.tar.xz");

    test.when_unpacked();

    test.then_no_failure_occurred();
    test.then_the_target_holds("njmule-gui", "gui");
    test.then_the_target_holds("share/njmule-icon.svg", "svg");
}

#[test]
fn should_keep_a_program_of_a_tar_xz_runnable() {
    let mut test = Unpacking::new();
    test.given_the_file("njmule-0.2.0-linux-x64/njmule-gui", Entry::program("gui"));
    test.given_a_tar_xz("njmule-0.2.0-linux-x64.tar.xz");

    test.when_unpacked();

    test.then_it_runs("njmule-gui");
}

#[test]
fn should_keep_a_file_named_as_the_top_folder() {
    let mut test = Unpacking::new();
    test.given_the_file("njmule/njmule", Entry::program("cli"));
    test.given_a_tar_xz("njmule.tar.xz");

    test.when_unpacked();

    test.then_the_target_holds("njmule", "cli");
}

#[test]
fn should_keep_the_names_of_a_package_without_a_top_folder() {
    let mut test = Unpacking::new();
    test.given_the_file("njmule.exe", Entry::program("exe"));
    test.given_the_file("LICENSE", Entry::file("GPL"));
    test.given_a_zip("loose.zip");

    test.when_unpacked();

    test.then_the_target_holds("njmule.exe", "exe");
    test.then_the_target_holds("LICENSE", "GPL");
}

#[test]
fn should_refuse_a_name_that_leaves_the_target() {
    for name in ["../evil.exe", "top/../../evil.exe", "/etc/evil", "C:/evil.exe", "top/./x"] {
        let mut test = Unpacking::new();
        test.given_the_file(name, Entry::file("evil"));
        test.given_a_zip("evil.zip");

        test.when_unpacked();

        test.then_it_failed_with("may not hold");
        test.then_nothing_left_the_target();
    }
}

#[test]
fn should_refuse_a_link_in_a_tar_xz() {
    let mut test = Unpacking::new();
    test.given_the_file("njmule/njmule", Entry::program("cli"));
    test.given_the_file("njmule/passwd", Entry::link_to("/etc/passwd"));
    test.given_a_tar_xz("link.tar.xz");

    test.when_unpacked();

    test.then_it_failed_with("link");
}

#[test]
fn should_refuse_an_empty_package() {
    let mut test = Unpacking::new();
    test.given_a_zip("empty.zip");

    test.when_unpacked();

    test.then_it_failed_with("empty");
}

#[test]
fn should_refuse_a_package_of_another_format() {
    let mut test = Unpacking::new();
    test.given_the_file("njmule.exe", Entry::program("exe"));
    test.given_a_zip("njmule.7z");

    test.when_unpacked();

    test.then_it_failed_with("not a zip or tar.xz");
}

struct Unpacking {
    folder: tempfile::TempDir,
    entries: Vec<(String, Entry)>,
    archive: PathBuf,
    target: PathBuf,
    failure: Option<io::Error>,
}

impl Unpacking {
    fn new() -> Self {
        let folder = tempfile::tempdir().unwrap();
        let target = folder.path().join("staged").join("files");
        Self { folder, entries: Vec::new(), archive: PathBuf::new(), target, failure: None }
    }

    fn given_the_file(&mut self, name: &str, entry: Entry) {
        self.entries.push((name.to_owned(), entry));
    }

    fn given_a_zip(&mut self, name: &str) {
        self.archive = test_packages::zip(&self.folder.path().join("download").join(name), &self.entries);
    }

    fn given_a_tar_xz(&mut self, name: &str) {
        self.archive = test_packages::tar_xz(&self.folder.path().join("download").join(name), &self.entries);
    }

    fn when_unpacked(&mut self) {
        self.failure = archives::unpack(&self.archive, &self.target).err();
    }

    fn then_no_failure_occurred(&self) {
        assert!(self.failure.is_none(), "{:?}", self.failure);
    }

    fn then_the_target_holds(&self, name: &str, text: &str) {
        assert_eq!(fs::read_to_string(self.target.join(name)).unwrap(), text);
    }

    fn then_the_target_has_no(&self, name: &str) {
        assert!(!self.target.join(name).exists());
    }

    #[cfg(unix)]
    fn then_it_runs(&self, name: &str) {
        use std::os::unix::fs::PermissionsExt;
        assert_ne!(fs::metadata(self.target.join(name)).unwrap().permissions().mode() & 0o100, 0);
    }

    // Windows has no execute permission to keep.
    #[cfg(not(unix))]
    fn then_it_runs(&self, name: &str) {
        assert!(self.target.join(name).is_file());
    }

    fn then_it_failed_with(&self, words: &str) {
        let failure = self.failure.as_ref().expect("a failure");
        assert!(failure.to_string().contains(words), "{failure}");
    }

    fn then_nothing_left_the_target(&self) {
        let left: BTreeSet<String> = fs::read_dir(self.folder.path())
            .unwrap()
            .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        assert_eq!(left, ["download", "staged"].iter().map(|name| (*name).to_owned()).collect());
        assert!(!self.folder.path().join("evil.exe").exists());
    }
}
