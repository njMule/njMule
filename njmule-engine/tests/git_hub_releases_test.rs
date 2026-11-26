// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Tests for git hub releases.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::config::update_channel::UpdateChannel;
use njmule_engine::update::git_hub_releases::{SIGNATURE_SUFFIX, Signed};
use njmule_engine::update::update_manifest::{FILE_NAME, UpdateManifest};
use semver::Version;

use crate::fake_web::{self, FakeWeb};
use crate::test_signing::TestSigning;
use crate::update_manifest_test::manifest;

#[tokio::test]
async fn should_take_the_newest_release_on_the_stable_channel() {
    let mut test = Releases::new();
    test.given_the_newest_release_is("0.1.0");

    test.when_the_newest_is_fetched(UpdateChannel::Stable).await;

    test.then_the_manifest_of_is("0.1.0");
    test.then_the_signature_came();
    test.then_the_list_of_releases_was_not_asked();
}

#[tokio::test]
async fn should_take_the_newest_signed_test_version_on_the_test_channel() {
    let mut test = Releases::new();
    test.given_the_newest_release_is("0.1.0");
    let signed = signed_assets();
    test.given_the_list_of_releases(&format!(
        "[\n  {{\"tag_name\": \"v0.3.0-alpha.1\", \"draft\": false, \"assets\": [{{\"name\": \"njmule-0.3.0-alpha.1.msi\"}}]}},\n  \
         {{\"tag_name\": \"v0.2.0\", \"draft\": true, \"assets\": {signed}}},\n  \
         {{\"tag_name\": \"v0.2.0-beta.1\", \"draft\": false, \"assets\": {signed}}},\n  \
         {{\"tag_name\": \"v0.1.0\", \"draft\": false, \"assets\": {signed}}},\n  \
         {{\"tag_name\": \"nightly\", \"draft\": false, \"assets\": {signed}}}\n]\n"
    ));
    test.given_the_release(&test_version(), "0.2.0-beta.1");

    test.when_the_newest_is_fetched(UpdateChannel::Prerelease).await;

    test.then_the_manifest_of_is("0.2.0-beta.1");
}

#[tokio::test]
async fn should_take_the_newest_release_when_the_list_of_releases_cannot_be_had() {
    let mut test = Releases::new();
    test.given_the_newest_release_is("0.1.0");

    test.when_the_newest_is_fetched(UpdateChannel::Prerelease).await;

    test.then_the_manifest_of_is("0.1.0");
}

#[test]
fn should_name_the_files_and_the_page_of_a_release() {
    let mut test = Releases::new();

    test.when_the_addresses_of_the_release_are_named("0.2.0", "njmule-0.2.0-windows-x64.msi");

    test.then_the_file_is_at(&format!(
        "{}/releases/download/v0.2.0/njmule-0.2.0-windows-x64.msi",
        fake_web::REPOSITORY
    ));
    test.then_the_page_is_at(&format!("{}/releases/tag/v0.2.0", fake_web::REPOSITORY));
}

struct Releases {
    web: FakeWeb,
    signer: TestSigning,
    fetched: Option<Signed>,
    file: String,
    page: String,
}

impl Releases {
    fn new() -> Self {
        Self {
            web: FakeWeb::default(),
            signer: TestSigning::new(),
            fetched: None,
            file: String::new(),
            page: String::new(),
        }
    }

    fn given_the_newest_release_is(&self, version: &str) {
        self.given_the_release(&fake_web::latest(), version);
    }

    fn given_the_release(&self, base: &str, version: &str) {
        self.web.serve_release(base, &manifest(version, false, &[]), &self.signer);
    }

    fn given_the_list_of_releases(&self, json: &str) {
        self.web.serve(&fake_web::listing(), json.as_bytes());
    }

    async fn when_the_newest_is_fetched(&mut self, channel: UpdateChannel) {
        self.fetched = Some(self.web.releases().latest(channel).await.unwrap());
    }

    fn when_the_addresses_of_the_release_are_named(&mut self, version: &str, file: &str) {
        let releases = self.web.releases();
        let version = Version::parse(version).unwrap();
        self.file = releases.download(&version, file);
        self.page = releases.page(&version);
    }

    fn then_the_manifest_of_is(&self, version: &str) {
        let manifest = UpdateManifest::parse(&self.fetched.as_ref().unwrap().manifest).unwrap();
        assert_eq!(manifest.version.to_string(), version);
    }

    fn then_the_signature_came(&self) {
        assert!(self.fetched.as_ref().unwrap().signature.starts_with("untrusted comment:"));
    }

    fn then_the_list_of_releases_was_not_asked(&self) {
        assert!(!self.web.asked().contains(&fake_web::listing()));
    }

    fn then_the_file_is_at(&self, expected: &str) {
        assert_eq!(self.file, expected);
    }

    fn then_the_page_is_at(&self, expected: &str) {
        assert_eq!(self.page, expected);
    }
}

fn test_version() -> String {
    format!("{}/releases/download/v0.2.0-beta.1/", fake_web::REPOSITORY)
}

fn signed_assets() -> String {
    format!(
        "[{{\"name\": \"njmule-0.2.0-windows-x64.msi\"}}, {{\"name\": \"{FILE_NAME}\"}}, {{\"name\": \
         \"{FILE_NAME}{SIGNATURE_SUFFIX}\"}}]"
    )
}
