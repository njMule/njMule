// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Which paused download starts when another one leaves.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::panic
)]

use std::collections::{BTreeSet, HashMap};
use std::fs;
use std::time::Duration;

use njmule_engine::api::category::Category;
use njmule_engine::api::download_info::{DownloadPriority, DownloadStatus};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::schedule::{DayOfWeek, Rule, Schedule};
use njmule_engine::engine::Engine;
use tempfile::TempDir;

use crate::free_port::free_port;

const WAIT: Duration = Duration::from_secs(10);

struct Lab {
    root: TempDir,
    engine: Option<Engine>,
    links: HashMap<String, FileLink>,
    cancelled: bool,
}

impl Lab {
    fn new() -> Self {
        Self { root: TempDir::new().unwrap(), engine: None, links: HashMap::new(), cancelled: false }
    }

    fn engine(&self) -> &Engine {
        self.engine.as_ref().unwrap()
    }

    async fn given_an_engine_that_starts_the_next_download(&mut self) {
        let dirs = AppDirectories::under_root(self.root.path()).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let port = free_port();
        fs::write(dirs.config.join("njmule.properties"), format!("net.tcpPort={port}\ndownload.startNext=ANY\n"))
            .unwrap();
        self.engine = Some(Engine::start(dirs).await.unwrap());
    }

    async fn given_the_categories_films_of_high_and_music_of_low_priority(&self) {
        self.engine()
            .set_categories(vec![
                Category {
                    id: 1,
                    name: "Films".to_owned(),
                    incoming: None,
                    comment: String::new(),
                    color: 1,
                    priority: DownloadPriority::High,
                    patterns: String::new(),
                    regex: false,
                },
                Category {
                    id: 2,
                    name: "Music".to_owned(),
                    incoming: None,
                    comment: String::new(),
                    color: 2,
                    priority: DownloadPriority::Low,
                    patterns: String::new(),
                    regex: false,
                },
            ])
            .await
            .unwrap();
    }

    async fn given_a_download(&mut self, name: &str, hash_byte: u8) {
        let link = FileLink::of(name, 5000, [hash_byte; 16]).unwrap();
        self.engine().add_download(link.clone(), None).await.unwrap();
        self.links.insert(name.to_owned(), link);
    }

    async fn given_the_download_is_in_category(&self, name: &str, category: u32) {
        assert!(self.engine().set_download_category(self.hash(name), category).await);
    }

    async fn given_the_download_is_paused(&self, name: &str) {
        assert!(self.engine().pause_download(self.hash(name)).await.is_some());
    }

    async fn given_the_download_is_cancelled(&self, name: &str) {
        assert!(self.engine().cancel_download(self.hash(name)).await);
    }

    async fn given_the_download_is_still_paused(&self, name: &str) {
        assert_eq!(self.status(name).await, DownloadStatus::Paused, "nothing was running in its place");
    }

    async fn given_a_schedule_rule_stopping_all_day_the_category(&self, category: u32) {
        let mut night = Rule::new("night");
        night.days = [
            DayOfWeek::Monday,
            DayOfWeek::Tuesday,
            DayOfWeek::Wednesday,
            DayOfWeek::Thursday,
            DayOfWeek::Friday,
            DayOfWeek::Saturday,
            DayOfWeek::Sunday,
        ]
        .into_iter()
        .collect::<BTreeSet<_>>();
        night.stop_category = Some(category);
        self.engine().update_schedule(Schedule { rules: vec![night] }).await.unwrap();
    }

    async fn given_the_schedule_stopped_the_download(&self, name: &str) {
        assert_eq!(self.status(name).await, DownloadStatus::Stopped);
    }

    async fn when_the_download_is_cancelled(&mut self, name: &str) {
        self.cancelled = self.engine().cancel_download(self.hash(name)).await;
    }

    fn then_the_cancel_is_accepted(&self) {
        assert!(self.cancelled);
    }

    async fn then_the_status_of_is(&self, name: &str, expected: DownloadStatus) {
        assert_eq!(self.status(name).await, expected);
    }

    async fn then_the_rule_keeps_it_stopped_though_of_higher_priority(&self, name: &str) {
        assert_eq!(self.status(name).await, DownloadStatus::Stopped);
    }

    fn hash(&self, name: &str) -> [u8; 16] {
        self.links[name].hash
    }

    async fn status(&self, name: &str) -> DownloadStatus {
        let hash = self.hash(name);
        tokio::time::timeout(WAIT, self.engine().downloads())
            .await
            .unwrap()
            .into_iter()
            .find(|d| d.hash == hash)
            .unwrap()
            .status
    }

    async fn stop(mut self) {
        if let Some(engine) = self.engine.take() {
            engine.stop().await;
        }
    }
}

#[tokio::test]
async fn should_start_a_paused_download_when_a_running_one_is_cancelled_but_not_when_one_that_waited_is() {
    let mut t = Lab::new();
    t.given_an_engine_that_starts_the_next_download().await;
    t.given_a_download("waiting.bin", 0xb1).await;
    t.given_a_download("paused.bin", 0xb2).await;
    t.given_the_download_is_paused("waiting.bin").await;
    t.given_the_download_is_paused("paused.bin").await;
    t.given_the_download_is_cancelled("waiting.bin").await;
    t.given_the_download_is_still_paused("paused.bin").await;
    t.given_a_download("running.bin", 0xb3).await;

    t.when_the_download_is_cancelled("running.bin").await;

    t.then_the_cancel_is_accepted();
    t.then_the_status_of_is("paused.bin", DownloadStatus::Downloading).await;
    t.stop().await;
}

#[tokio::test]
async fn should_not_start_a_download_the_scheduler_stopped_instead() {
    let mut t = Lab::new();
    t.given_an_engine_that_starts_the_next_download().await;
    t.given_the_categories_films_of_high_and_music_of_low_priority().await;
    t.given_a_download("film.mkv", 0xc1).await;
    t.given_a_download("song.mp3", 0xc2).await;
    t.given_a_download("other.mp3", 0xc3).await;
    t.given_the_download_is_in_category("film.mkv", 1).await;
    t.given_the_download_is_in_category("song.mp3", 2).await;
    t.given_the_download_is_in_category("other.mp3", 2).await;
    t.given_the_download_is_paused("song.mp3").await;
    t.given_a_schedule_rule_stopping_all_day_the_category(1).await;
    t.given_the_schedule_stopped_the_download("film.mkv").await;

    t.when_the_download_is_cancelled("other.mp3").await;

    t.then_the_rule_keeps_it_stopped_though_of_higher_priority("film.mkv").await;
    t.then_the_status_of_is("song.mp3", DownloadStatus::Downloading).await;
    t.stop().await;
}
