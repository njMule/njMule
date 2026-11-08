// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The schedule through a running engine: what a rule does to the downloads when it begins, at a start too, and the
//! rule in force across a restart.

#![allow(clippy::unwrap_used, clippy::expect_used)]

use crate::free_port;

use std::fs;
use std::time::Duration;

use njmule_engine::api::category::Category;
use njmule_engine::api::download_info::{DownloadPriority, DownloadStatus};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::schedule::{DayOfWeek, Rule, Schedule};
use njmule_engine::engine::Engine;
use strum::VariantArray;

const FILM: [u8; 16] = [0xf3; 16];
const SONG: [u8; 16] = [0xf4; 16];
const WAIT: Duration = Duration::from_secs(5);

#[tokio::test]
async fn should_stop_the_downloads_of_one_category_and_resume_those_of_another_by_a_rule() {
    let mut test = EngineSchedule::new();
    test.given_a_running_engine().await;
    test.given_the_categories(&[1, 2]).await;
    test.given_the_download_in_category(FILM, 1).await;
    test.given_the_download_in_category(SONG, 2).await;
    test.given_the_download_is_paused(SONG).await;

    test.when_the_schedule_holds(&swap(1, 2)).await;

    test.then_the_status_of_the_download_is(FILM, DownloadStatus::Stopped).await;
    test.then_the_status_of_the_download_is(SONG, DownloadStatus::Downloading).await;
}

#[tokio::test]
async fn should_act_once_on_a_rule_that_holds_at_the_start() {
    let mut test = EngineSchedule::new();
    test.given_a_running_engine().await;
    test.given_the_categories(&[1, 2]).await;
    test.given_the_download_in_category(FILM, 1).await;
    test.given_the_schedule_holds(&stop("night", 1)).await;

    test.when_the_engine_is_restarted().await;

    test.then_the_log_says_once("started");
    test.then_the_log_says_once("Scheduler: downloads of category 1 stopped");
}

#[tokio::test]
async fn should_not_run_the_actions_of_the_rule_in_force_again_when_it_is_edited() {
    let mut test = EngineSchedule::new();
    test.given_a_running_engine().await;
    test.given_the_categories(&[1]).await;
    test.given_the_download_in_category(FILM, 1).await;
    test.given_the_schedule_holds(&stop("night", 1)).await;
    test.given_the_download_is_resumed(FILM).await;

    test.when_the_schedule_holds(&stop("late night", 1)).await;

    test.then_the_status_of_the_download_is(FILM, DownloadStatus::Downloading).await;
}

#[tokio::test]
async fn should_lift_the_rule_kept_across_a_restart_when_the_schedule_is_emptied() {
    let mut test = EngineSchedule::new();
    test.given_a_running_engine().await;
    test.given_the_schedule_holds(&stop("always", 0)).await;
    test.given_the_engine_was_restarted().await;
    test.given_the_rule_in_force_is(Some(&stop("always", 0))).await;

    test.when_the_schedule_is_emptied().await;

    test.then_the_rule_in_force_is(None).await;
}

struct EngineSchedule {
    profile: tempfile::TempDir,
    engine: Option<Engine>,
    started: Vec<EngineEvent>,
}

impl EngineSchedule {
    fn new() -> Self {
        Self { profile: tempfile::tempdir().unwrap(), engine: None, started: Vec::new() }
    }

    async fn given_a_running_engine(&mut self) {
        let dirs = self.directories();
        fs::create_dir_all(&dirs.config).unwrap();
        let preferences = format!("net.tcpPort={}\nnet.kad.enabled=false\n", free_port::free_port());
        fs::write(dirs.config.join("njmule.properties"), preferences).unwrap();
        self.start().await;
    }

    async fn given_the_categories(&self, ids: &[u32]) {
        let categories = ids
            .iter()
            .map(|&id| Category {
                id,
                name: format!("category {id}"),
                incoming: None,
                comment: String::new(),
                color: 0,
                priority: DownloadPriority::Auto,
                patterns: String::new(),
                regex: false,
            })
            .collect();
        within(self.engine().set_categories(categories)).await.unwrap();
    }

    async fn given_the_download_in_category(&self, hash: [u8; 16], category: u32) {
        let link = FileLink::of(&format!("{}.bin", hash[0]), 5000, hash).unwrap();
        within(self.engine().add_download(link, Some(false))).await.unwrap();
        assert!(within(self.engine().set_download_category(hash, category)).await);
    }

    async fn given_the_download_is_paused(&self, hash: [u8; 16]) {
        assert!(within(self.engine().pause_download(hash)).await.is_some());
    }

    async fn given_the_download_is_resumed(&self, hash: [u8; 16]) {
        assert!(within(self.engine().resume_download(hash)).await.is_some());
    }

    async fn given_the_schedule_holds(&self, rule: &Rule) {
        self.when_the_schedule_holds(rule).await;
    }

    async fn given_the_engine_was_restarted(&mut self) {
        self.when_the_engine_is_restarted().await;
    }

    async fn given_the_rule_in_force_is(&self, rule: Option<&Rule>) {
        self.then_the_rule_in_force_is(rule).await;
    }

    async fn when_the_schedule_holds(&self, rule: &Rule) {
        within(self.engine().update_schedule(Schedule { rules: vec![rule.clone()] })).await.unwrap();
    }

    async fn when_the_schedule_is_emptied(&self) {
        within(self.engine().update_schedule(Schedule::default())).await.unwrap();
    }

    // What the new engine logged at its start, rule acted on included, is what a front end following it late is told.
    async fn when_the_engine_is_restarted(&mut self) {
        within(self.engine().stop()).await;
        self.start().await;
        self.started = self.engine().subscribe().missed;
    }

    async fn then_the_status_of_the_download_is(&self, hash: [u8; 16], status: DownloadStatus) {
        let downloads = within(self.engine().downloads()).await;
        let download = downloads.iter().find(|download| download.hash == hash).expect("the download");
        assert_eq!(download.status, status);
    }

    fn then_the_log_says_once(&self, text: &str) {
        let told = self
            .started
            .iter()
            .filter(|event| matches!(event, EngineEvent::Log(line) if line.message.contains(text)))
            .count();
        assert_eq!(told, 1, "{text} in {:?}", self.started);
    }

    async fn then_the_rule_in_force_is(&self, rule: Option<&Rule>) {
        assert_eq!(within(self.engine().active_rule()).await.as_ref(), rule);
    }

    async fn start(&mut self) {
        self.engine = Some(Engine::start(self.directories()).await.unwrap());
    }

    fn directories(&self) -> AppDirectories {
        AppDirectories::under_root(self.profile.path()).unwrap()
    }

    fn engine(&self) -> Engine {
        self.engine.clone().expect("a running engine")
    }
}

impl Drop for EngineSchedule {
    fn drop(&mut self) {
        if let Some(engine) = self.engine.take() {
            tokio::spawn(async move { engine.stop().await });
        }
    }
}

// A rule that holds all week, all day; it stops no category with 0.
fn stop(name: &str, category: u32) -> Rule {
    Rule {
        days: DayOfWeek::VARIANTS.iter().copied().collect(),
        upload_kibps: Some(7),
        stop_category: Some(category).filter(|&id| id != 0),
        ..Rule::new(name)
    }
}

fn swap(stop: u32, resume: u32) -> Rule {
    Rule { resume_category: Some(resume), ..self::stop("swap", stop) }
}

async fn within<T>(work: impl Future<Output = T>) -> T {
    tokio::time::timeout(WAIT, work).await.expect("answered in time")
}
