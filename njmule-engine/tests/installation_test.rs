// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's InstallationTest, under its names. The Java engine runs on the JVM without an executable of its
//! own; the Rust engine is always one, so "on the JVM" is a copy whose executable is not known.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fs;
use std::path::{Path, PathBuf};

use njmule_engine::api::install_kind::InstallKind;
use njmule_engine::api::update_status::Installer;
use njmule_engine::config::os_family::OsFamily;
use njmule_engine::update::installation::{self, FILE_NAME, Installation};

#[test]
fn should_take_the_kind_the_package_wrote_beside_the_executable() {
    let mut test = Resolving::new();
    test.given_the_package_wrote_beside_the_executable("kind=msi\n");

    test.when_resolved_on(OsFamily::Windows, "amd64");

    test.then_the_kind_is(InstallKind::Msi);
    test.then_the_platform_is("windows-x64");
}

#[test]
fn should_take_the_kind_of_a_mac_bundle_from_its_resources() {
    let mut test = Resolving::new();
    test.given_a_bundle_whose_package_wrote("kind=dmg\n");

    test.when_resolved_on(OsFamily::Macos, "aarch64");

    test.then_the_kind_is(InstallKind::Dmg);
    test.then_the_platform_is("macos-arm64");
}

#[test]
fn should_let_the_environment_of_a_container_win() {
    let mut test = Resolving::new();
    test.given_the_package_wrote_beside_the_executable("kind=tar\n");
    test.given_the_variable("docker");

    test.when_resolved_on(OsFamily::Linux, "amd64");

    test.then_the_kind_is(InstallKind::Docker);
    test.then_it_is_installed_by(Installer::Nobody);
}

#[test]
fn should_replace_itself_in_a_folder_it_may_write_to() {
    for (text, expected) in [
        ("kind=zip", Installer::Itself),
        ("kind=tar", Installer::Itself),
        ("kind=static-tar", Installer::Itself),
        ("kind=msi", Installer::Itself),
        ("kind=deb", Installer::Nobody),
    ] {
        let mut test = Resolving::new();
        test.given_the_package_wrote_beside_the_executable(&format!("{text}\n"));

        test.when_resolved_on(OsFamily::Linux, "amd64");

        test.then_it_is_installed_by(expected);
        test.then_it_replaces_the_folder();
    }
}

#[test]
fn should_replace_the_whole_bundle_of_the_mac_app() {
    let mut test = Resolving::new();
    test.given_a_bundle_whose_package_wrote("kind=dmg\n");

    test.when_resolved_on(OsFamily::Macos, "aarch64");

    test.then_it_is_installed_by(Installer::Itself);
    test.then_it_replaces(&test.dir().join("njMule.app"));
}

#[test]
fn should_replace_nothing_on_the_jvm() {
    let mut test = Resolving::new();

    test.when_resolved_on(OsFamily::Windows, "amd64");

    test.then_it_is_installed_by(Installer::Nobody);
    test.then_it_replaces_nothing();
}

#[test]
fn should_tell_a_folder_it_may_write_to() {
    let mut test = Resolving::new();

    test.when_asked_whether_it_may_write_to(&test.dir());

    test.then_the_answer_is(true);
}

#[test]
fn should_tell_a_folder_that_is_not_there() {
    let mut test = Resolving::new();

    test.when_asked_whether_it_may_write_to(&test.dir().join("gone"));

    test.then_the_answer_is(false);
}

#[test]
fn should_not_know_a_copy_built_from_the_sources() {
    let mut test = Resolving::new();

    test.when_resolved_on(OsFamily::Linux, "amd64");

    test.then_the_kind_is(InstallKind::Unknown);
}

#[test]
fn should_not_know_a_kind_it_has_no_name_for() {
    let mut test = Resolving::new();
    test.given_the_package_wrote_beside_the_executable("kind=snap\n");

    test.when_resolved_on(OsFamily::Linux, "amd64");

    test.then_the_kind_is(InstallKind::Unknown);
}

#[test]
fn should_name_the_processor_as_the_releases_do() {
    for (arch, platform) in
        [("amd64", "linux-x64"), ("x86_64", "linux-x64"), ("aarch64", "linux-arm64"), ("arm64", "linux-arm64")]
    {
        let mut test = Resolving::new();

        test.when_resolved_on(OsFamily::Linux, arch);

        test.then_the_platform_is(platform);
    }
}

struct Resolving {
    folder: tempfile::TempDir,
    variable: Option<String>,
    executable: Option<PathBuf>,
    installation: Option<Installation>,
    writable: bool,
}

impl Resolving {
    fn new() -> Self {
        Self {
            folder: tempfile::tempdir().unwrap(),
            variable: None,
            executable: None,
            installation: None,
            writable: false,
        }
    }

    fn dir(&self) -> PathBuf {
        self.folder.path().to_path_buf()
    }

    fn given_the_package_wrote_beside_the_executable(&mut self, text: &str) {
        fs::write(self.dir().join(FILE_NAME), text).unwrap();
        self.executable = Some(self.dir().join("njmule"));
    }

    fn given_a_bundle_whose_package_wrote(&mut self, text: &str) {
        let contents = self.dir().join("njMule.app").join("Contents");
        fs::create_dir_all(contents.join("Resources")).unwrap();
        fs::write(contents.join("Resources").join(FILE_NAME), text).unwrap();
        fs::create_dir_all(contents.join("MacOS")).unwrap();
        self.executable = Some(contents.join("MacOS").join("njmule-gui"));
    }

    fn given_the_variable(&mut self, value: &str) {
        self.variable = Some(value.to_owned());
    }

    fn when_resolved_on(&mut self, os: OsFamily, arch: &str) {
        self.installation = Some(Installation::resolve(self.variable.as_deref(), self.executable.as_deref(), os, arch));
    }

    fn when_asked_whether_it_may_write_to(&mut self, folder: &Path) {
        self.writable = installation::writable(folder);
    }

    fn installation(&self) -> &Installation {
        self.installation.as_ref().unwrap()
    }

    fn then_the_answer_is(&self, expected: bool) {
        assert_eq!(self.writable, expected);
    }

    fn then_it_is_installed_by(&self, expected: Installer) {
        assert_eq!(self.installation().installer, expected);
    }

    fn then_it_replaces_the_folder(&self) {
        self.then_it_replaces(&self.dir());
    }

    fn then_it_replaces(&self, root: &Path) {
        assert_eq!(self.installation().root().as_deref(), Some(root));
    }

    fn then_it_replaces_nothing(&self) {
        assert!(self.installation().root().is_none());
    }

    fn then_the_kind_is(&self, expected: InstallKind) {
        assert_eq!(self.installation().kind, expected);
    }

    fn then_the_platform_is(&self, expected: &str) {
        assert_eq!(self.installation().platform, expected);
    }
}
