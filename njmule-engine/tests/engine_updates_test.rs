// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's EngineUpdatesTest, under its names: the engine's side of the updates, with the checks off as in
//! every test so nothing reaches GitHub, plus the `--apply-update` entry point of the executable.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::fs;
use std::process::Command;
use std::time::Duration;

use njmule_engine::api::install_kind::InstallKind;
use njmule_engine::api::nj_mule_info;
use njmule_engine::api::update_status::UpdateStatus;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::persist::config_backup;

use crate::free_port;

const WAIT: Duration = Duration::from_secs(10);

#[tokio::test]
async fn should_tell_the_version_running_and_that_it_knows_of_no_newer() {
    let mut test = EngineUpdates::new();
    test.given_a_profile_without_networks();

    test.when_the_status_is_read().await;

    test.then_the_version_running_is(nj_mule_info::version());
    test.then_the_installation_is(InstallKind::Unknown);
    test.then_no_newer_version_is_known();
}

#[tokio::test]
async fn should_answer_a_check_with_the_reason_it_cannot_look() {
    let mut test = EngineUpdates::new();
    test.given_a_profile_without_networks();

    test.when_asked_to_check().await;

    test.then_the_check_failed_with("turned off");
}

#[tokio::test]
async fn should_remember_a_skipped_version_in_the_next_run() {
    let mut test = EngineUpdates::new();
    test.given_a_profile_without_networks();
    test.given_the_version_skipped("0.2.0").await;

    test.when_the_status_is_read().await;

    test.then_the_skipped_version_is("0.2.0");
}

#[tokio::test]
async fn should_back_up_the_configuration_before_another_version_first_uses_it() {
    let mut test = EngineUpdates::new();
    test.given_a_profile_without_networks();
    test.given_the_version_that_ran_last_was("0.0.1");

    test.when_the_engine_starts().await;

    test.then_the_backups_are(1);
}

#[tokio::test]
async fn should_back_up_a_configuration_that_kept_no_version() {
    let mut test = EngineUpdates::new();
    test.given_a_profile_without_networks();

    test.when_the_engine_starts().await;

    test.then_the_backups_are(1);
}

#[tokio::test]
async fn should_not_back_up_again_for_the_same_version() {
    let mut test = EngineUpdates::new();
    test.given_a_profile_without_networks();
    test.given_the_version_that_ran_last_was(nj_mule_info::version());

    test.when_the_engine_starts().await;

    test.then_the_backups_are(0);
}

#[tokio::test]
async fn should_not_back_up_a_new_configuration() {
    let mut test = EngineUpdates::new();
    test.given_a_new_profile();

    test.when_the_engine_starts().await;

    test.then_the_backups_are(0);
}

#[tokio::test]
async fn should_refuse_to_install_when_no_new_version_is_known() {
    let mut test = EngineUpdates::new();
    test.given_a_profile_without_networks();

    test.when_asked_to_install().await;

    test.then_it_was_refused("no new version is downloaded");
    test.then_nothing_is_handed_over();
}

#[test]
fn should_fail_apply_update_when_no_plan_is_there() {
    let dir = tempfile::tempdir().unwrap();
    let missing = dir.path().join("apply.properties");

    let output = Command::new(env!("CARGO_BIN_EXE_njmule")).arg("--apply-update").arg(&missing).output().unwrap();

    assert_eq!(output.status.code(), Some(1));
}

struct EngineUpdates {
    dir: tempfile::TempDir,
    dirs: Option<AppDirectories>,
    status: Option<UpdateStatus>,
    failure: Option<String>,
    handed_over: bool,
    backups: usize,
}

impl EngineUpdates {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            dirs: None,
            status: None,
            failure: None,
            handed_over: false,
            backups: 0,
        }
    }

    fn dirs(&self) -> AppDirectories {
        self.dirs.clone().unwrap()
    }

    fn given_a_new_profile(&mut self) {
        self.dirs = Some(AppDirectories::under_root(&self.dir.path().join("njmule")).unwrap());
    }

    fn given_the_version_that_ran_last_was(&self, version: &str) {
        fs::write(self.dirs().config.join("update.properties"), format!("ran={version}\n")).unwrap();
    }

    fn given_a_profile_without_networks(&mut self) {
        let dirs = AppDirectories::under_root(&self.dir.path().join("njmule")).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let port = free_port::free_port();
        fs::write(
            dirs.config.join("njmule.properties"),
            format!("net.tcpPort={port}\nnet.ed2k.enabled=false\nnet.kad.enabled=false\n"),
        )
        .unwrap();
        self.dirs = Some(dirs);
    }

    async fn given_the_version_skipped(&self, version: &str) {
        let engine = tokio::time::timeout(WAIT, Engine::start(self.dirs())).await.unwrap().unwrap();
        tokio::time::timeout(WAIT, engine.skip_update(version.to_owned())).await.unwrap().unwrap();
        tokio::time::timeout(WAIT, engine.stop()).await.unwrap();
    }

    async fn when_the_status_is_read(&mut self) {
        let engine = tokio::time::timeout(WAIT, Engine::start(self.dirs())).await.unwrap().unwrap();
        self.status = Some(engine.update_status());
        tokio::time::timeout(WAIT, engine.stop()).await.unwrap();
    }

    // The copies counted while it runs: closing makes one of its own.
    async fn when_the_engine_starts(&mut self) {
        let engine = tokio::time::timeout(WAIT, Engine::start(self.dirs())).await.unwrap().unwrap();
        let copies = config_backup::copies(&self.dirs().data.join(config_backup::FOLDER)).unwrap();
        self.backups = copies.len();
        tokio::time::timeout(WAIT, engine.stop()).await.unwrap();
    }

    async fn when_asked_to_install(&mut self) {
        let engine = tokio::time::timeout(WAIT, Engine::start(self.dirs())).await.unwrap().unwrap();
        match tokio::time::timeout(WAIT, engine.install_update(true)).await.unwrap() {
            Ok(status) => self.status = Some(status),
            Err(reason) => self.failure = Some(reason),
        }
        tokio::time::timeout(WAIT, engine.stop()).await.unwrap();
        self.handed_over = engine.update_hand_over().is_some();
    }

    async fn when_asked_to_check(&mut self) {
        let engine = tokio::time::timeout(WAIT, Engine::start(self.dirs())).await.unwrap().unwrap();
        self.status = Some(tokio::time::timeout(WAIT, engine.check_for_update()).await.unwrap());
        tokio::time::timeout(WAIT, engine.stop()).await.unwrap();
    }

    fn then_the_version_running_is(&self, version: &str) {
        assert_eq!(self.status.as_ref().unwrap().current, version);
    }

    fn then_the_installation_is(&self, kind: InstallKind) {
        assert_eq!(self.status.as_ref().unwrap().installation, kind);
    }

    fn then_no_newer_version_is_known(&self) {
        assert!(self.status.as_ref().unwrap().available.is_none());
    }

    fn then_the_check_failed_with(&self, reason: &str) {
        let failure = self.status.as_ref().unwrap().failure.as_deref().unwrap();
        assert!(failure.contains(reason), "{failure}");
    }

    fn then_the_backups_are(&self, count: usize) {
        assert_eq!(self.backups, count);
    }

    fn then_it_was_refused(&self, words: &str) {
        let reason = self.failure.as_deref().unwrap();
        assert!(reason.contains(words), "{reason}");
    }

    fn then_nothing_is_handed_over(&self) {
        assert!(!self.handed_over);
    }

    fn then_the_skipped_version_is(&self, version: &str) {
        assert_eq!(self.status.as_ref().unwrap().skipped.as_deref(), Some(version));
    }
}
