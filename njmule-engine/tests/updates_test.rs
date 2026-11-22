// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's UpdatesTest, under its names: checking for new versions, signature verification, replay guard,
//! skipping, channel filtering and schedule.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::fs;
use std::io;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures_util::future::BoxFuture;
use jiff::Timestamp;
use njmule_engine::api::install_kind::InstallKind;
use njmule_engine::api::new_version::NewVersion;
use njmule_engine::api::update_status::UpdateStatus;
use njmule_engine::config::update_channel::UpdateChannel;
use njmule_engine::update::commands::{CommandResult, Commands};
use njmule_engine::update::installation::Installation;
use njmule_engine::update::launcher::{Launcher, Running};
use njmule_engine::update::update_files::UpdateFiles;
use njmule_engine::update::update_state;
use njmule_engine::update::updates::{Listener, Updates};

use crate::fake_web::{self, FakeWeb};
use crate::test_signing::TestSigning;
use crate::update_manifest_test::{self, SHA256};

const START: &str = "2026-11-03T08:00:00Z";
const MSI: &str = "njmule-0.2.0-windows-x64.msi";
const WAIT: Duration = Duration::from_secs(10);

#[tokio::test]
async fn should_offer_a_newer_version_with_the_package_of_this_installation() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_is("0.2.0", false);

    test.when_checked_by_hand(UpdateChannel::Stable).await;

    test.then_the_version_available_is("0.2.0");
    test.then_its_package_is_at(&format!("https://github.com/njMule/njMule/releases/download/v0.2.0/{MSI}"));
    test.then_its_page_is_at("https://github.com/njMule/njMule/releases/tag/v0.2.0");
    test.then_the_check_succeeded_at(START);
}

#[tokio::test]
async fn should_tell_of_a_new_version_it_found_on_its_own_once_a_run() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_is("0.2.0", false);
    test.given_checked_on_its_own().await;

    test.when_checked_on_its_own().await;

    test.then_it_told_of(&["0.2.0"]);
}

#[tokio::test]
async fn should_not_tell_of_a_version_the_user_looked_for() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_is("0.2.0", false);
    test.given_checked_by_hand().await;

    test.when_checked_on_its_own().await;

    test.then_it_told_of_nothing();
    test.then_the_version_available_is("0.2.0");
}

#[tokio::test]
async fn should_find_nothing_newer_than_the_version_running() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_is("0.1.0", false);

    test.when_checked_on_its_own().await;

    test.then_no_version_is_available();
    test.then_the_check_did_not_fail();
}

#[tokio::test]
async fn should_not_offer_a_test_version_to_the_stable_channel() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_is("0.2.0-beta.1", false);

    test.when_checked_on_its_own().await;

    test.then_no_version_is_available();
}

#[tokio::test]
async fn should_offer_a_test_version_to_the_test_channel() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_is("0.1.0", false);
    test.given_a_test_version("0.2.0-beta.1");

    test.when_checked_by_hand(UpdateChannel::Prerelease).await;

    test.then_the_version_available_is("0.2.0-beta.1");
}

#[tokio::test]
async fn should_refuse_information_not_signed_by_nj_mule() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_signed_by(&TestSigning::new(), "0.2.0");

    test.when_checked_on_its_own().await;

    test.then_the_check_failed_with("not signed by njMule");
    test.then_no_version_is_available();
    test.then_it_told_of_nothing();
}

#[tokio::test]
async fn should_refuse_an_older_version_than_one_offered_before() {
    let mut test = UpdatesFixture::new();
    test.given_the_kept_state("highest.stable=0.3.0\n");
    test.given_the_newest_release_is("0.2.0", false);

    test.when_checked_on_its_own().await;

    test.then_the_check_failed_with("older than the 0.3.0 it offered before");
    test.then_no_version_is_available();
}

#[tokio::test]
async fn should_keep_quiet_about_a_skipped_version() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_is("0.2.0", false);
    test.given_skipped("0.2.0");

    test.when_checked_on_its_own().await;

    test.then_it_told_of_nothing();
    test.then_the_skipped_version_is("0.2.0");
}

#[tokio::test]
async fn should_tell_of_a_skipped_version_that_fixes_a_security_problem() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_is("0.2.0", true);
    test.given_skipped("0.2.0");

    test.when_checked_on_its_own().await;

    test.then_it_told_of(&["0.2.0"]);
}

#[tokio::test]
async fn should_remember_the_skipped_version_and_the_last_check_in_the_next_run() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_is("0.2.0", false);
    test.given_checked_by_hand().await;
    test.given_skipped("0.2.0");

    test.when_nj_mule_starts_again();

    test.then_the_skipped_version_is("0.2.0");
    test.then_the_check_succeeded_at(START);
}

#[tokio::test]
async fn should_offer_no_package_to_a_copy_built_from_the_sources() {
    let mut test = UpdatesFixture::new();
    test.given_the_installation(Installation::not_updated(InstallKind::Unknown, "linux-x64"));
    test.given_the_newest_release_is("0.2.0", false);

    test.when_checked_by_hand(UpdateChannel::Stable).await;

    test.then_the_version_available_has_no_package();
}

#[test]
fn should_look_at_the_first_chance_of_a_run() {
    let mut test = UpdatesFixture::new();
    test.given_updates();

    test.when_asked_whether_a_check_is_due();

    test.then_it_is_due(true);
}

#[tokio::test]
async fn should_not_look_again_before_a_day() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_is("0.1.0", false);
    test.given_checked_on_its_own().await;
    test.given_time_passed(Duration::from_secs(23 * 3600));

    test.when_asked_whether_a_check_is_due();

    test.then_it_is_due(false);
}

#[tokio::test]
async fn should_look_again_a_day_later() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_is("0.1.0", false);
    test.given_checked_on_its_own().await;
    test.given_time_passed(Duration::from_secs(24 * 3600));

    test.when_asked_whether_a_check_is_due();

    test.then_it_is_due(true);
}

#[tokio::test]
async fn should_look_again_six_hours_after_a_failure() {
    let mut test = UpdatesFixture::new();
    test.given_checked_on_its_own().await;
    test.given_time_passed(Duration::from_secs(6 * 3600));

    test.when_asked_whether_a_check_is_due();

    test.then_it_is_due(true);
}

#[tokio::test]
async fn should_join_the_check_running() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_is("0.2.0", false);
    test.given_the_web_slow();
    test.given_a_check_running();

    test.when_checked_again_meanwhile();

    test.then_the_check_is_reported(true);
    test.then_both_end_with_the_same_status_once_the_web_answers().await;
    test.then_the_release_was_fetched_once();
}

#[tokio::test]
async fn should_never_look_when_turned_off() {
    let mut test = UpdatesFixture::new();
    test.given_turned_off();

    test.when_checked_by_hand(UpdateChannel::Stable).await;

    test.then_the_check_failed_with("turned off");
    test.then_the_web_was_not_asked();
}

#[tokio::test]
async fn should_tell_when_a_check_starts_and_ends() {
    let mut test = UpdatesFixture::new();
    test.given_the_newest_release_is("0.1.0", false);

    test.when_checked_on_its_own().await;

    test.then_the_changes_were_checking_then_done();
}

#[test]
fn should_refuse_to_skip_a_non_version() {
    let mut test = UpdatesFixture::new();
    let refused = test.updates().skip("not-a-version").unwrap_err();
    assert!(refused.contains("not a version"), "{refused}");
}

#[tokio::test]
async fn should_refuse_an_oversized_update_manifest() {
    let mut test = UpdatesFixture::new();
    let huge = vec![b' '; njmule_engine::update::update_manifest::MAX_SIZE + 1];
    let base = fake_web::latest();
    test.web.serve(&format!("{base}njmule-update.json"), &huge);
    test.web.serve(&format!("{base}njmule-update.json.minisig"), test.signer.sign(&huge).as_bytes());

    test.when_checked_on_its_own().await;

    test.then_the_check_failed_with("too large");
}

struct NoCommands;

impl Commands for NoCommands {
    fn run(&self, _command: &[String], _timeout: Duration) -> io::Result<CommandResult> {
        Ok(CommandResult { exit_code: 0, output: String::new() })
    }
}

struct NoLauncher;

impl Launcher for NoLauncher {
    fn start(&self, _command: &[String], _log: Option<&Path>) -> io::Result<Box<dyn Running>> {
        Err(io::Error::other("no process in this test"))
    }
}

struct RecordingListener {
    changes: Arc<Mutex<Vec<UpdateStatus>>>,
    found: Arc<Mutex<Vec<NewVersion>>>,
}

impl Listener for RecordingListener {
    fn changed(&self, status: UpdateStatus) {
        self.changes.lock().unwrap().push(status);
    }

    fn found(&self, version: NewVersion) {
        self.found.lock().unwrap().push(version);
    }

    fn ready(&self, _version: NewVersion) {}

    fn restart(&self, _version: String) {}
}

struct UpdatesFixture {
    dir: tempfile::TempDir,
    web: FakeWeb,
    signer: TestSigning,
    now: Arc<Mutex<Timestamp>>,
    changes: Arc<Mutex<Vec<UpdateStatus>>>,
    found: Arc<Mutex<Vec<NewVersion>>>,
    installation: Installation,
    enabled: bool,
    updates: Option<Updates<FakeWeb, FakeWeb>>,
    status: Option<UpdateStatus>,
    first: Option<BoxFuture<'static, UpdateStatus>>,
    second: Option<BoxFuture<'static, UpdateStatus>>,
    due: bool,
}

impl Drop for UpdatesFixture {
    fn drop(&mut self) {
        self.web.release();
        if let Some(updates) = &self.updates {
            updates.close();
        }
    }
}

impl UpdatesFixture {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            web: FakeWeb::default(),
            signer: TestSigning::new(),
            now: Arc::new(Mutex::new(START.parse().unwrap())),
            changes: Arc::default(),
            found: Arc::default(),
            installation: Installation::not_updated(InstallKind::Msi, "windows-x64"),
            enabled: true,
            updates: None,
            status: None,
            first: None,
            second: None,
            due: false,
        }
    }

    fn given_the_newest_release_is(&self, version: &str, critical: bool) {
        let json = update_manifest_test::manifest(
            version,
            critical,
            &[
                update_manifest_test::msi(MSI),
                update_manifest_test::asset("linux-x64", "deb", "njmule_0.2.0_amd64.deb"),
            ],
        );
        self.web.serve_release(&fake_web::latest(), &json, &self.signer);
    }

    fn given_the_newest_release_signed_by(&self, other: &TestSigning, version: &str) {
        self.web.serve_release(&fake_web::latest(), &update_manifest_test::manifest(version, false, &[]), other);
    }

    fn given_a_test_version(&self, version: &str) {
        let tag = format!("v{version}");
        let listing = format!(
            "[{{\"tag_name\": \"{tag}\", \"assets\": [{{\"name\": \"njmule-update.json\"}}, {{\"name\": \
             \"njmule-update.json.minisig\"}}]}}]"
        );
        self.web.serve(&fake_web::listing(), listing.as_bytes());
        self.web.serve_release(
            &format!("{}/releases/download/{tag}/", fake_web::REPOSITORY),
            &update_manifest_test::manifest(version, false, &[]),
            &self.signer,
        );
    }

    fn given_the_kept_state(&self, text: &str) {
        fs::write(self.dir.path().join(update_state::FILE_NAME), text).unwrap();
    }

    fn given_the_installation(&mut self, chosen: Installation) {
        self.installation = chosen;
    }

    fn given_turned_off(&mut self) {
        self.enabled = false;
    }

    fn given_updates(&mut self) {
        let _ = self.updates();
    }

    fn given_skipped(&mut self, version: &str) {
        self.updates().skip(version).unwrap();
    }

    async fn given_checked_on_its_own(&mut self) {
        self.when_checked_on_its_own().await;
    }

    async fn given_checked_by_hand(&mut self) {
        self.when_checked_by_hand(UpdateChannel::Stable).await;
    }

    fn given_time_passed(&self, time: Duration) {
        let mut now = self.now.lock().unwrap();
        *now = now.checked_add(time).unwrap();
    }

    fn given_the_web_slow(&self) {
        self.web.hold_fetches();
    }

    fn given_a_check_running(&mut self) {
        self.first = Some(self.updates().check(UpdateChannel::Stable, true));
    }

    async fn when_checked_on_its_own(&mut self) {
        let work = self.updates().check(UpdateChannel::Stable, true);
        self.status = Some(tokio::time::timeout(WAIT, work).await.unwrap());
    }

    async fn when_checked_by_hand(&mut self, channel: UpdateChannel) {
        let work = self.updates().check(channel, false);
        self.status = Some(tokio::time::timeout(WAIT, work).await.unwrap());
    }

    fn when_checked_again_meanwhile(&mut self) {
        self.second = Some(self.updates().check(UpdateChannel::Stable, false));
    }

    fn when_asked_whether_a_check_is_due(&mut self) {
        self.due = self.updates().is_due();
    }

    fn when_nj_mule_starts_again(&mut self) {
        if let Some(updates) = self.updates.take() {
            updates.close();
        }
        self.status = Some(self.updates().status());
    }

    fn status(&self) -> &UpdateStatus {
        self.status.as_ref().unwrap()
    }

    fn then_the_version_available_is(&self, version: &str) {
        assert_eq!(self.status().available.as_ref().map(|v| v.version.as_str()), Some(version));
    }

    fn then_its_package_is_at(&self, url: &str) {
        let download = self.status().available.as_ref().unwrap().download.as_ref().unwrap();
        assert_eq!(download.url, url);
        assert_eq!(download.size, 31_719_424);
        assert_eq!(download.sha256, SHA256);
    }

    fn then_its_page_is_at(&self, url: &str) {
        assert_eq!(self.status().available.as_ref().unwrap().page, url);
    }

    fn then_the_version_available_has_no_package(&self) {
        assert!(self.status().available.as_ref().unwrap().download.is_none());
    }

    fn then_no_version_is_available(&self) {
        assert!(self.status().available.is_none());
    }

    fn then_the_check_succeeded_at(&self, when: &str) {
        assert_eq!(self.status().last_check, Some(when.parse().unwrap()));
        assert!(self.status().failure.is_none());
    }

    fn then_the_check_did_not_fail(&self) {
        assert!(self.status().failure.is_none());
    }

    fn then_the_check_failed_with(&self, reason: &str) {
        let failure = self.status().failure.as_deref().unwrap();
        assert!(failure.contains(reason), "{failure}");
    }

    fn then_it_told_of(&self, versions: &[&str]) {
        let found: Vec<String> = self.found.lock().unwrap().iter().map(|v| v.version.clone()).collect();
        assert_eq!(found, versions);
    }

    fn then_it_told_of_nothing(&self) {
        assert!(self.found.lock().unwrap().is_empty());
    }

    fn then_the_skipped_version_is(&self, version: &str) {
        assert_eq!(self.status().skipped.as_deref(), Some(version));
    }

    fn then_it_is_due(&self, expected: bool) {
        assert_eq!(self.due, expected);
    }

    async fn then_both_end_with_the_same_status_once_the_web_answers(&mut self) {
        self.web.release();
        let first = tokio::time::timeout(WAIT, self.first.take().unwrap()).await.unwrap();
        let second = tokio::time::timeout(WAIT, self.second.take().unwrap()).await.unwrap();
        assert_eq!(second, first);
    }

    fn then_the_release_was_fetched_once(&self) {
        assert_eq!(self.web.asked().len(), 2);
    }

    fn then_the_check_is_reported(&mut self, checking: bool) {
        assert_eq!(self.updates().status().checking, checking);
    }

    fn then_the_web_was_not_asked(&self) {
        assert!(self.web.asked().is_empty());
    }

    fn then_the_changes_were_checking_then_done(&self) {
        let flags: Vec<bool> = self.changes.lock().unwrap().iter().map(|status| status.checking).collect();
        assert_eq!(flags, [true, false]);
    }

    fn updates(&mut self) -> Updates<FakeWeb, FakeWeb> {
        if self.updates.is_none() {
            let now = Arc::clone(&self.now);
            let listener =
                Arc::new(RecordingListener { changes: Arc::clone(&self.changes), found: Arc::clone(&self.found) });
            self.updates = Some(
                Updates::new(
                    self.web.releases(),
                    self.web.clone(),
                    self.signer.keys(),
                    self.installation.clone(),
                    "0.1.0",
                    self.dir.path().join(update_state::FILE_NAME),
                    UpdateFiles::new(self.dir.path().join("cache")),
                    Arc::new(NoCommands),
                    Arc::new(NoLauncher),
                    Arc::new(move || *now.lock().unwrap()),
                    Duration::ZERO,
                    self.enabled,
                    false,
                    listener,
                )
                .unwrap(),
            );
        }
        self.updates.clone().unwrap()
    }
}
