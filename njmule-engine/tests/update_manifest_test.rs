// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's UpdateManifestTest, under its names.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::io;

use njmule_engine::update::update_manifest::{Asset, MAX_SIZE, UpdateManifest};

pub(crate) const SHA256: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

#[test]
fn should_read_the_release_and_its_packages() {
    let mut test = Manifest::default();

    test.when_read(&manifest("0.2.0", true, &[msi("njmule-0.2.0-windows-x64.msi")]));

    test.then_the_version_is("0.2.0");
    test.then_it_was_published("2026-11-02T10:00:00Z");
    test.then_it_is_critical(true);
    test.then_the_notes_are("### Added");
    test.then_there_are_packages(1);
}

#[test]
fn should_find_the_package_of_a_platform_and_kind() {
    let mut test = Manifest::default();
    test.given_read(&manifest(
        "0.2.0",
        false,
        &[msi("njmule-0.2.0-windows-x64.msi"), asset("linux-x64", "deb", "njmule_0.2.0_amd64.deb")],
    ));

    test.when_the_package_is_looked_up("linux-x64", "deb");

    test.then_the_package_is("njmule_0.2.0_amd64.deb");
}

#[test]
fn should_find_no_package_for_another_platform() {
    let mut test = Manifest::default();
    test.given_read(&manifest("0.2.0", false, &[msi("njmule-0.2.0-windows-x64.msi")]));

    test.when_the_package_is_looked_up("windows-arm64", "msi");

    test.then_there_is_no_package();
}

#[test]
fn should_leave_out_packages_of_a_kind_this_version_does_not_know_the_form_of() {
    let mut test = Manifest::default();

    test.when_read(&manifest(
        "0.2.0",
        false,
        &[msi("njmule-0.2.0-windows-x64.msi"), asset("windows-x64", "Some New Kind", "njmule-0.2.0.msix")],
    ));

    test.then_there_are_packages(1);
}

#[test]
fn should_refuse_a_package_name_that_is_no_plain_file_name() {
    for name in ["../../evil.exe", "a/b.msi", "..", "name with spaces.msi", ""] {
        let mut test = Manifest::default();

        test.when_read(&manifest("0.2.0", false, &[asset("windows-x64", "msi", name)]));

        test.then_exception_occurred("asset");
    }
}

#[test]
fn should_refuse_a_package_without_a_valid_hash() {
    let mut test = Manifest::default();

    test.when_read(&manifest("0.2.0", false, &[msi("njmule.msi").replace(SHA256, "not-a-hash")]));

    test.then_exception_occurred("asset");
}

#[test]
fn should_refuse_information_of_another_schema() {
    let mut test = Manifest::default();

    test.when_read(&manifest("0.2.0", false, &[]).replace("\"schema\": 1", "\"schema\": 2"));

    test.then_exception_occurred("schema");
}

#[test]
fn should_refuse_information_without_a_valid_version() {
    let mut test = Manifest::default();

    test.when_read(&manifest("two", false, &[]));

    test.then_exception_occurred("version");
}

#[test]
fn should_refuse_text_that_is_no_json() {
    let mut test = Manifest::default();

    test.when_read("<html>Not Found</html>");

    test.then_exception_occurred("malformed");
}

#[test]
fn should_refuse_information_larger_than_any_release() {
    let mut test = Manifest::default();

    test.when_read(&manifest("0.2.0", false, &[]).replace("### Added", &"x".repeat(MAX_SIZE)));

    test.then_exception_occurred("too large");
}

#[derive(Default)]
struct Manifest {
    read: Option<io::Result<UpdateManifest>>,
    asset: Option<Asset>,
}

impl Manifest {
    fn given_read(&mut self, json: &str) {
        self.when_read(json);
        assert!(self.read.as_ref().unwrap().is_ok());
    }

    fn when_read(&mut self, json: &str) {
        self.read = Some(UpdateManifest::parse(json.as_bytes()));
    }

    fn when_the_package_is_looked_up(&mut self, platform: &str, kind: &str) {
        self.asset = self.manifest().asset(platform, kind).cloned();
    }

    fn manifest(&self) -> &UpdateManifest {
        self.read.as_ref().unwrap().as_ref().unwrap()
    }

    fn then_the_version_is(&self, expected: &str) {
        assert_eq!(self.manifest().version.to_string(), expected);
    }

    fn then_it_was_published(&self, expected: &str) {
        assert_eq!(self.manifest().published, expected.parse().unwrap());
    }

    fn then_it_is_critical(&self, expected: bool) {
        assert_eq!(self.manifest().critical, expected);
    }

    fn then_the_notes_are(&self, expected: &str) {
        assert_eq!(self.manifest().notes, expected);
    }

    fn then_there_are_packages(&self, count: usize) {
        assert_eq!(self.manifest().assets.len(), count);
    }

    fn then_the_package_is(&self, name: &str) {
        assert_eq!(self.asset.as_ref().map(|asset| asset.name.as_str()), Some(name));
    }

    fn then_there_is_no_package(&self) {
        assert!(self.asset.is_none());
    }

    fn then_exception_occurred(&self, message: &str) {
        let e = self.read.as_ref().unwrap().as_ref().expect_err("a failure");
        assert!(e.to_string().contains(message), "{e}");
    }
}

/// The manifest of a release with `assets`, as [`asset`] writes them.
pub(crate) fn manifest(version: &str, critical: bool, assets: &[String]) -> String {
    format!(
        "{{\n  \"schema\": 1,\n  \"version\": \"{version}\",\n  \"published\": \"2026-11-02T10:00:00Z\",\n  \
         \"critical\": {critical},\n  \"notes\": \"### Added\",\n  \"assets\": [{}]\n}}\n",
        assets.join(",")
    )
}

pub(crate) fn asset(platform: &str, kind: &str, name: &str) -> String {
    format!(
        "{{\"platform\": \"{platform}\", \"kind\": \"{kind}\", \"name\": \"{name}\", \"size\": 31719424, \"sha256\": \
         \"{SHA256}\"}}"
    )
}

pub(crate) fn msi(name: &str) -> String {
    asset("windows-x64", "msi", name)
}
