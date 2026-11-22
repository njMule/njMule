// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's UpdateInstallTest, under its names: downloading, verifying, unpacking, install modes, night
//! restarts, hand-over, outcomes and rollback.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use data_encoding::HEXLOWER;
use jiff::Timestamp;
use njmule_engine::api::install_kind::InstallKind;
use njmule_engine::api::new_version::NewVersion;
use njmule_engine::api::update_status::{Install, Installer, Phase, UpdateStatus};
use njmule_engine::config::night_hours::NightHours;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::config::update_channel::UpdateChannel;
use njmule_engine::config::update_install_time::UpdateInstallTime;
use njmule_engine::config::update_mode::UpdateMode;
use njmule_engine::update::apply_plan::{ApplyPlan, State as PlanState};
use njmule_engine::update::commands::{CommandResult, Commands, SystemCommands};
use njmule_engine::update::hand_over::{self, HandOver};
use njmule_engine::update::installation::Installation;
use njmule_engine::update::launcher::{Launcher, Running};
use njmule_engine::update::update_files::UpdateFiles;
use njmule_engine::update::update_helper;
use njmule_engine::update::update_state;
use njmule_engine::update::updates::{Listener, Updates};
use sha2::{Digest, Sha256};
use time::Time;

use crate::fake_web::{self, FakeWeb};
use crate::test_packages::{self, Entry};
use crate::test_signing::TestSigning;
use crate::update_manifest_test;

const NOW: &str = "2026-11-03T08:00:00Z";
const ZIP: &str = "njmule-0.2.0-windows-x64.zip";
const MSI: &str = "njmule-0.2.0-windows-x64.msi";
const SPK: &str = "njmule-0.2.0-synology-x86_64.spk";
const WAIT: Duration = Duration::from_secs(10);

#[tokio::test]
async fn should_download_the_package_check_it_and_unpack_it() {
    let mut test = InstallFixture::new();
    test.given_a_new_release_with_its_package();
    test.given_checked_by_hand().await;

    test.when_downloaded().await;

    test.then_no_failure_occurred();
    test.then_the_phase_is(Phase::Ready);
    test.then_the_version_is_told_ready("0.2.0");
    test.then_the_unpacked_files_hold("njmule.exe", "0.2.0");
    test.then_the_progress_was_told();
}

#[tokio::test]
async fn should_tell_why_a_download_failed() {
    let mut test = InstallFixture::new();
    test.given_a_new_release_whose_package_was_changed();
    test.given_checked_by_hand().await;

    test.when_downloaded().await;

    test.then_the_phase_is(Phase::Failed);
    test.then_the_failure_says("does not match its signed hash");
    test.then_nothing_is_ready();
}

#[tokio::test]
async fn should_refuse_to_download_before_a_new_version_is_known() {
    let mut test = InstallFixture::new();

    test.when_downloaded().await;

    test.then_it_was_refused("no new version is known");
}

#[tokio::test]
async fn should_download_on_its_own_in_the_download_mode() {
    let mut test = InstallFixture::new();
    test.given_a_new_release_with_its_package();
    test.given_the_mode(UpdateMode::Download);

    test.when_checked_on_its_own().await;

    test.then_a_version_becomes_ready().await;
    test.then_it_installs_when(Install::Asked);
}

#[tokio::test]
async fn should_not_download_on_its_own_on_a_synology_nas() {
    let mut test = InstallFixture::new();
    test.given_the_installation(InstallKind::Synology, Installer::Nobody);
    test.given_a_new_release_with_its_package();
    test.given_the_mode(UpdateMode::Install);

    test.when_checked_on_its_own().await;

    test.then_the_new_version_is_known("0.2.0");
    test.then_no_download_started();
}

#[tokio::test]
async fn should_install_at_exit_in_the_install_mode() {
    let mut test = InstallFixture::new();
    test.given_a_new_release_with_its_package();
    test.given_the_mode(UpdateMode::Install);
    test.given_checked_by_hand().await;
    test.given_downloaded().await;

    test.when_nj_mule_stops();

    test.then_it_installs_when(Install::AtExit);
    test.then_an_installation_is_handed_over(false);
}

#[tokio::test]
async fn should_not_install_on_its_own_what_needs_administrator_rights() {
    let mut test = InstallFixture::new();
    test.given_the_installation(InstallKind::Msi, Installer::Elevated);
    test.given_a_new_release_with_its_package();
    test.given_the_mode(UpdateMode::Install);
    test.given_checked_by_hand().await;
    test.given_downloaded().await;

    test.when_nj_mule_stops();

    test.then_it_installs_when(Install::Asked);
    test.then_nothing_is_handed_over();
}

#[tokio::test]
async fn should_restart_to_install_when_the_user_asks() {
    let mut test = InstallFixture::new();
    test.given_a_new_release_with_its_package();
    test.given_checked_by_hand().await;
    test.given_downloaded().await;

    test.when_installed(true);

    test.then_no_failure_occurred();
    test.then_the_phase_is(Phase::Installing);
    test.then_a_restart_was_asked_for("0.2.0");
}

#[tokio::test]
async fn should_hand_over_the_restart_once_nj_mule_stopped() {
    let mut test = InstallFixture::new();
    test.given_a_new_release_with_its_package();
    test.given_checked_by_hand().await;
    test.given_downloaded().await;
    test.given_installed(true);

    test.when_nj_mule_stops();

    test.then_an_installation_is_handed_over(true);
}

#[tokio::test]
async fn should_install_at_exit_when_the_user_asks() {
    let mut test = InstallFixture::new();
    test.given_a_new_release_with_its_package();
    test.given_checked_by_hand().await;
    test.given_downloaded().await;

    test.when_installed(false);

    test.then_it_installs_when(Install::AtExit);
    test.then_no_restart_was_asked_for();
}

#[tokio::test]
async fn should_hand_over_an_installation_asked_for_the_exit() {
    let mut test = InstallFixture::new();
    test.given_a_new_release_with_its_package();
    test.given_checked_by_hand().await;
    test.given_downloaded().await;
    test.given_installed(false);

    test.when_nj_mule_stops();

    test.then_an_installation_is_handed_over(false);
}

#[tokio::test]
async fn should_refuse_to_install_what_is_not_downloaded() {
    let mut test = InstallFixture::new();
    test.given_a_new_release_with_its_package();
    test.given_checked_by_hand().await;

    test.when_installed(true);

    test.then_it_was_refused("no new version is downloaded");
}

#[tokio::test]
async fn should_refuse_to_install_where_a_package_manager_owns_the_files() {
    let mut test = InstallFixture::new();
    test.given_the_installation(InstallKind::Deb, Installer::Nobody);
    test.given_a_new_release_with_its_package();
    test.given_checked_by_hand().await;
    test.given_downloaded().await;

    test.when_installed(true);

    test.then_it_was_refused("cannot replace itself here");
}

#[tokio::test]
async fn should_restart_in_the_night_hours_when_asked() {
    let mut test = InstallFixture::new();
    test.given_a_new_release_with_its_package();
    test.given_the_mode(UpdateMode::Install);
    test.given_the_night_hours(UpdateInstallTime::ExitOrNight, "03:00-05:00");
    test.given_checked_by_hand().await;
    test.given_downloaded().await;

    test.when_the_clock_says(Time::from_hms(4, 0, 0).unwrap());

    test.then_it_restarted(true);
    test.then_a_restart_was_asked_for("0.2.0");
}

#[tokio::test]
async fn should_wait_for_the_night_hours() {
    let mut test = InstallFixture::new();
    test.given_a_new_release_with_its_package();
    test.given_the_mode(UpdateMode::Install);
    test.given_the_night_hours(UpdateInstallTime::ExitOrNight, "03:00-05:00");
    test.given_checked_by_hand().await;
    test.given_downloaded().await;

    test.when_the_clock_says(Time::from_hms(12, 0, 0).unwrap());

    test.then_it_restarted(false);
    test.then_no_restart_was_asked_for();
}

#[tokio::test]
async fn should_not_restart_at_night_when_only_the_exit_installs() {
    let mut test = InstallFixture::new();
    test.given_a_new_release_with_its_package();
    test.given_the_mode(UpdateMode::Install);
    test.given_checked_by_hand().await;
    test.given_downloaded().await;

    test.when_the_clock_says(Time::from_hms(4, 0, 0).unwrap());

    test.then_it_restarted(false);
}

#[test]
fn should_tell_of_a_failed_installation_at_the_next_start() {
    let mut test = InstallFixture::new();
    test.given_the_last_installation_ended(PlanState::Failed, "the new version does not start");
    test.given_the_mode(UpdateMode::Install);

    test.when_nj_mule_starts();

    test.then_the_failure_says("the installation of njMule 0.2.0 failed: the new version does not start");
    test.then_no_plan_is_left();
}

#[tokio::test]
async fn should_not_install_again_on_its_own_a_version_whose_installation_failed() {
    let mut test = InstallFixture::new();
    test.given_the_last_installation_ended(PlanState::Failed, "the new version does not start");
    test.given_a_new_release_with_its_package();
    test.given_the_mode(UpdateMode::Install);
    test.given_checked_by_hand().await;
    test.given_downloaded().await;

    test.when_nj_mule_stops();

    test.then_it_installs_when(Install::Asked);
}

#[test]
fn should_tell_what_windows_installer_said() {
    let mut test = InstallFixture::new();
    test.given_the_installation(InstallKind::Msi, Installer::Itself);
    test.given_the_last_installer_ended_with("1603");

    test.when_nj_mule_starts();

    test.then_the_failure_says("Windows Installer ended with code 1603");
}

#[tokio::test]
async fn should_make_an_installation_final_once_the_new_version_ran_long_enough() {
    let mut test = InstallFixture::new();
    test.given_the_new_version_installed_over_the_old();

    test.when_the_new_version_ran_long_enough();

    test.then_it_told_of("njMule was updated from 0.1.0 to 0.2.0");
    test.then_the_start_is_marked("0.2.0");
    test.then_the_old_files_are_removed_soon().await;
    test.then_no_plan_is_left_soon().await;
}

#[test]
fn should_undo_an_installation_stopped_half_way() {
    let mut test = InstallFixture::new();
    test.given_an_installation_stopped_half_way();

    test.when_nj_mule_starts();

    test.then_the_installed_file_holds("njmule.exe", "0.1.0");
    test.then_the_failure_says("did not finish");
}

#[test]
fn should_explain_common_windows_installer_outcomes() {
    for (code, words) in [
        (Some("1602"), "it was cancelled"),
        (Some("1618"), "another installation was running"),
        (None, "Windows Installer did not run"),
    ] {
        let mut test = InstallFixture::new();
        test.given_the_installation(InstallKind::Msi, Installer::Itself);
        test.plan(PlanState::HandedOver, None, None, None).save(&test.files().plan()).unwrap();
        if let Some(code) = code {
            let folder = test.files().version("0.2.0").unwrap();
            fs::create_dir_all(&folder).unwrap();
            fs::write(folder.join(hand_over::INSTALLER_RESULT), format!("{code}\r\n")).unwrap();
        }

        test.when_nj_mule_starts();

        test.then_the_failure_says(words);
    }
}

#[test]
fn should_discard_a_damaged_or_unrelated_installation_plan() {
    let mut damaged = InstallFixture::new();
    fs::create_dir_all(damaged.files().root()).unwrap();
    fs::write(damaged.files().plan(), "version=0.2.0\n").unwrap();
    damaged.when_nj_mule_starts();
    damaged.then_no_plan_is_left();

    let mut unrelated = InstallFixture::new();
    unrelated.running = "0.3.0".to_owned();
    unrelated.plan(PlanState::Failed, Some("old"), None, None).save(&unrelated.files().plan()).unwrap();
    unrelated.when_nj_mule_starts();
    unrelated.then_no_plan_is_left();
}

#[tokio::test]
async fn should_join_a_download_running_and_skip_repeating_a_ready_one() {
    let mut test = InstallFixture::new();
    test.given_a_new_release_with_its_package();
    test.given_checked_by_hand().await;
    test.web.hold_fetches();

    let first = test.updates().download();
    let second = test.updates().download();
    test.web.release();
    let first = tokio::time::timeout(WAIT, first).await.unwrap().unwrap();
    let second = tokio::time::timeout(WAIT, second).await.unwrap().unwrap();
    assert_eq!(first, second);
    assert_eq!(first.phase(), Phase::Ready);

    let already = tokio::time::timeout(WAIT, test.updates().download()).await.unwrap().unwrap();
    assert_eq!(already, first);
}

#[tokio::test]
async fn should_refuse_to_download_when_no_package_fits_this_installation() {
    let mut test = InstallFixture::new();
    test.given_the_installation(InstallKind::Unknown, Installer::Nobody);
    let json = update_manifest_test::manifest("0.2.0", false, &[update_manifest_test::msi(MSI)]);
    test.web.serve_release(&fake_web::latest(), &json, &test.signer);
    test.given_checked_by_hand().await;

    test.when_downloaded().await;

    test.then_it_was_refused("has no package for this installation");
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

struct InstallListener {
    changes: Arc<Mutex<Vec<UpdateStatus>>>,
    ready: Arc<Mutex<Vec<NewVersion>>>,
    restarts: Arc<Mutex<Vec<String>>>,
}

impl Listener for InstallListener {
    fn changed(&self, status: UpdateStatus) {
        self.changes.lock().unwrap().push(status);
    }

    fn found(&self, _version: NewVersion) {}

    fn ready(&self, version: NewVersion) {
        self.ready.lock().unwrap().push(version);
    }

    fn restart(&self, version: String) {
        self.restarts.lock().unwrap().push(version);
    }
}

struct InstallFixture {
    dir: tempfile::TempDir,
    web: FakeWeb,
    signer: TestSigning,
    changes: Arc<Mutex<Vec<UpdateStatus>>>,
    ready: Arc<Mutex<Vec<NewVersion>>>,
    restarts: Arc<Mutex<Vec<String>>>,
    kind: InstallKind,
    installer: Installer,
    running: String,
    preferences: Preferences,
    updates: Option<Updates<FakeWeb, FakeWeb>>,
    status: Option<UpdateStatus>,
    failure: Option<String>,
    hand_over: Option<HandOver>,
    restarted: bool,
    told: Option<String>,
}

impl Drop for InstallFixture {
    fn drop(&mut self) {
        self.web.release();
        if let Some(updates) = &self.updates {
            updates.close();
        }
    }
}

impl InstallFixture {
    fn new() -> Self {
        let preferences = Preferences {
            update_mode: UpdateMode::Notify,
            update_channel: UpdateChannel::Stable,
            ..Preferences::default()
        };
        Self {
            dir: tempfile::tempdir().unwrap(),
            web: FakeWeb::default(),
            signer: TestSigning::new(),
            changes: Arc::default(),
            ready: Arc::default(),
            restarts: Arc::default(),
            kind: InstallKind::WindowsZip,
            installer: Installer::Itself,
            running: "0.1.0".to_owned(),
            preferences,
            updates: None,
            status: None,
            failure: None,
            hand_over: None,
            restarted: false,
            told: None,
        }
    }

    fn given_the_installation(&mut self, chosen: InstallKind, by: Installer) {
        self.kind = chosen;
        self.installer = by;
    }

    fn given_the_mode(&mut self, mode: UpdateMode) {
        self.preferences.update_mode = mode;
    }

    fn given_the_night_hours(&mut self, when: UpdateInstallTime, hours: &str) {
        self.preferences.update_install_time = when;
        self.preferences.night_hours = NightHours::parse(hours).unwrap();
    }

    fn given_a_new_release_with_its_package(&self) {
        let body = self.package_bytes();
        self.serve_release(&body, &body);
    }

    fn given_a_new_release_whose_package_was_changed(&self) {
        let sealed = self.package_bytes();
        let mut changed = sealed.clone();
        let mid = changed.len() / 2;
        changed[mid] ^= 1;
        self.serve_release(&sealed, &changed);
    }

    async fn given_checked_by_hand(&mut self) {
        let work = self.updates().check(UpdateChannel::Stable, false);
        tokio::time::timeout(WAIT, work).await.unwrap();
    }

    async fn given_downloaded(&mut self) {
        let work = self.updates().download();
        tokio::time::timeout(WAIT, work).await.unwrap().unwrap();
    }

    fn given_installed(&mut self, restart: bool) {
        self.updates().install(restart).unwrap();
    }

    fn given_the_last_installation_ended(&self, state: PlanState, reason: &str) {
        self.plan(state, Some(reason), None, None).save(&self.files().plan()).unwrap();
    }

    fn given_the_last_installer_ended_with(&self, code: &str) {
        self.plan(PlanState::HandedOver, None, None, None).save(&self.files().plan()).unwrap();
        let folder = self.files().version("0.2.0").unwrap();
        fs::create_dir_all(&folder).unwrap();
        fs::write(folder.join(hand_over::INSTALLER_RESULT), format!("{code}\r\n")).unwrap();
    }

    fn given_the_new_version_installed_over_the_old(&mut self) {
        write(&self.installed().join("njmule.exe"), "0.1.0");
        write(&self.files().unpacked("0.2.0").unwrap().join("njmule.exe"), "0.2.0");
        let plan = self.plan(
            PlanState::Installed,
            None,
            Some(self.files().unpacked("0.2.0").unwrap()),
            Some(self.installed()),
        );
        update_helper::swap(&plan, &self.files(), Arc::new(SystemCommands)).unwrap().apply().unwrap();
        plan.save(&self.files().plan()).unwrap();
        self.running = "0.2.0".to_owned();
    }

    fn given_an_installation_stopped_half_way(&mut self) {
        self.given_the_new_version_installed_over_the_old();
        let now: Timestamp = NOW.parse().unwrap();
        ApplyPlan::load(&self.files().plan())
            .unwrap()
            .unwrap()
            .with(PlanState::Waiting, None, now)
            .save(&self.files().plan())
            .unwrap();
        self.running = "0.1.0".to_owned();
    }

    async fn when_downloaded(&mut self) {
        let work = self.updates().download();
        match tokio::time::timeout(WAIT, work).await.unwrap() {
            Ok(status) => self.status = Some(status),
            Err(reason) => self.failure = Some(reason),
        }
    }

    async fn when_checked_on_its_own(&mut self) {
        let work = self.updates().check(UpdateChannel::Stable, true);
        self.status = Some(tokio::time::timeout(WAIT, work).await.unwrap());
    }

    fn when_nj_mule_stops(&mut self) {
        let updates = self.updates();
        updates.close();
        self.status = Some(updates.status());
        self.hand_over = updates.hand_over();
    }

    fn when_installed(&mut self, restart: bool) {
        match self.updates().install(restart) {
            Ok(status) => self.status = Some(status),
            Err(reason) => self.failure = Some(reason),
        }
    }

    fn when_the_clock_says(&mut self, time: Time) {
        self.restarted = self.updates().install_if_night(time);
    }

    fn when_nj_mule_starts(&mut self) {
        self.status = Some(self.updates().status());
    }

    fn when_the_new_version_ran_long_enough(&mut self) {
        self.told = self.updates().ran_long_enough();
    }

    fn then_no_failure_occurred(&self) {
        assert!(self.failure.is_none(), "{:?}", self.failure);
    }

    fn then_it_was_refused(&self, words: &str) {
        let reason = self.failure.as_deref().unwrap();
        assert!(reason.contains(words), "{reason}");
    }

    fn then_the_phase_is(&self, phase: Phase) {
        assert_eq!(self.status.as_ref().unwrap().phase(), phase);
    }

    fn then_the_failure_says(&self, words: &str) {
        let failure = self.status.as_ref().unwrap().failure.as_deref().unwrap();
        assert!(failure.contains(words), "{failure}");
    }

    fn then_the_version_is_told_ready(&self, version: &str) {
        let ready: Vec<String> = self.ready.lock().unwrap().iter().map(|v| v.version.clone()).collect();
        assert_eq!(ready, [version]);
    }

    fn then_the_unpacked_files_hold(&self, name: &str, text: &str) {
        assert_eq!(fs::read_to_string(self.files().unpacked("0.2.0").unwrap().join(name)).unwrap(), text);
    }

    fn then_the_progress_was_told(&self) {
        assert!(self.changes.lock().unwrap().iter().any(|status| status.download.is_some()));
    }

    fn then_nothing_is_ready(&self) {
        assert!(self.status.as_ref().unwrap().ready.is_none());
    }

    fn then_the_new_version_is_known(&self, version: &str) {
        assert_eq!(self.status.as_ref().unwrap().available.as_ref().map(|v| v.version.as_str()), Some(version));
    }

    fn then_no_download_started(&mut self) {
        let status = self.updates().status();
        assert!(status.download.is_none());
        assert!(status.ready.is_none());
        assert!(self.web.asked().iter().all(|url| !url.ends_with(SPK)));
    }

    async fn then_a_version_becomes_ready(&mut self) {
        let end = Instant::now() + WAIT;
        while self.updates().status().ready.is_none() && Instant::now() < end {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        assert!(self.updates().status().ready.is_some());
    }

    fn then_it_installs_when(&mut self, expected: Install) {
        assert_eq!(self.updates().status().install, expected);
    }

    fn then_an_installation_is_handed_over(&self, restart: bool) {
        let hand_over = self.hand_over.as_ref().unwrap();
        assert_eq!(hand_over.version(), "0.2.0");
        assert_eq!(hand_over.restart(), restart);
    }

    fn then_nothing_is_handed_over(&self) {
        assert!(self.hand_over.is_none());
    }

    fn then_a_restart_was_asked_for(&self, version: &str) {
        assert_eq!(*self.restarts.lock().unwrap(), [version]);
    }

    fn then_no_restart_was_asked_for(&self) {
        assert!(self.restarts.lock().unwrap().is_empty());
    }

    fn then_it_restarted(&self, expected: bool) {
        assert_eq!(self.restarted, expected);
    }

    fn then_no_plan_is_left(&self) {
        assert!(!self.files().plan().exists());
    }

    fn then_it_told_of(&self, message: &str) {
        assert_eq!(self.told.as_deref(), Some(message));
    }

    fn then_the_start_is_marked(&self, version: &str) {
        assert!(self.files().started(version).unwrap().exists());
    }

    async fn then_the_old_files_are_removed_soon(&self) {
        await_gone(&self.installed().join("njmule.exe.old")).await;
    }

    async fn then_no_plan_is_left_soon(&self) {
        await_gone(&self.files().plan()).await;
    }

    fn then_the_installed_file_holds(&self, name: &str, text: &str) {
        assert_eq!(fs::read_to_string(self.installed().join(name)).unwrap(), text);
    }

    fn serve_release(&self, sealed: &[u8], served: &[u8]) {
        let name = match self.kind {
            InstallKind::Msi => MSI,
            InstallKind::Deb => "njmule_0.2.0_amd64.deb",
            InstallKind::Synology => SPK,
            _ => ZIP,
        };
        let platform = if self.on_linux() { "linux-x64" } else { "windows-x64" };
        let sha256 = HEXLOWER.encode(&Sha256::digest(sealed));
        let asset = format!(
            "{{\"platform\": \"{platform}\", \"kind\": \"{}\", \"name\": \"{name}\", \"size\": {}, \"sha256\": \
             \"{sha256}\"}}",
            self.kind.id(),
            sealed.len()
        );
        self.web.serve_release(
            &fake_web::latest(),
            &update_manifest_test::manifest("0.2.0", false, &[asset]),
            &self.signer,
        );
        self.web.serve(&format!("{}/releases/download/v0.2.0/{name}", fake_web::REPOSITORY), served);
    }

    fn package_bytes(&self) -> Vec<u8> {
        let zip = test_packages::zip(
            &self.dir.path().join("release").join(ZIP),
            &[("njmule-0.2.0-windows-x64/njmule.exe".to_owned(), Entry::program("0.2.0"))],
        );
        fs::read(zip).unwrap()
    }

    fn plan(
        &self,
        state: PlanState,
        reason: Option<&str>,
        staged: Option<PathBuf>,
        target: Option<PathBuf>,
    ) -> ApplyPlan {
        let package = if self.kind == InstallKind::Msi { MSI } else { ZIP };
        ApplyPlan {
            version: "0.2.0".to_owned(),
            from: "0.1.0".to_owned(),
            kind: self.kind,
            package_file: self.files().package_file("0.2.0", package).unwrap(),
            probe: staged.as_ref().map(|_| "njmule.exe".to_owned()),
            staged,
            target,
            restart: false,
            unattended: true,
            program: None,
            arguments: Vec::new(),
            state,
            reason: reason.map(str::to_owned),
            helper: None,
            time: NOW.parse().unwrap(),
        }
    }

    fn on_linux(&self) -> bool {
        matches!(self.kind, InstallKind::Deb | InstallKind::Synology)
    }

    fn installed(&self) -> PathBuf {
        self.dir.path().join("installed")
    }

    fn files(&self) -> UpdateFiles {
        UpdateFiles::new(self.dir.path().join("cache").join("update"))
    }

    fn updates(&mut self) -> Updates<FakeWeb, FakeWeb> {
        if self.updates.is_none() {
            let installation = if self.on_linux() {
                Installation {
                    kind: self.kind,
                    platform: "linux-x64".to_owned(),
                    executable: None,
                    installer: self.installer,
                }
            } else {
                Installation {
                    kind: self.kind,
                    platform: "windows-x64".to_owned(),
                    executable: Some(self.installed().join("njmule.exe")),
                    installer: self.installer,
                }
            };
            let now: Timestamp = NOW.parse().unwrap();
            let listener = Arc::new(InstallListener {
                changes: Arc::clone(&self.changes),
                ready: Arc::clone(&self.ready),
                restarts: Arc::clone(&self.restarts),
            });
            let updates = Updates::new(
                self.web.releases(),
                self.web.clone(),
                self.signer.keys(),
                installation,
                &self.running,
                self.dir.path().join(update_state::FILE_NAME),
                self.files(),
                Arc::new(NoCommands),
                Arc::new(NoLauncher),
                Arc::new(move || now),
                Duration::ZERO,
                true,
                false,
                listener,
            )
            .unwrap();
            updates.preferences(&self.preferences);
            self.updates = Some(updates);
        }
        self.updates.clone().unwrap()
    }
}

async fn await_gone(file: &Path) {
    let end = Instant::now() + WAIT;
    while file.exists() && Instant::now() < end {
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert!(!file.exists(), "{} still exists", file.display());
}

fn write(file: &Path, text: &str) {
    fs::create_dir_all(file.parent().unwrap()).unwrap();
    fs::write(file, text).unwrap();
}
