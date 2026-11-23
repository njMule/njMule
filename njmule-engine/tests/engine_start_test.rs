// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's EngineStartTest, under its names: start log replay, schedule rules at start and edit, deleted
//! category cleanup in schedule rules, and creating only the configured download folders.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::BTreeSet;
use std::fs;
use std::path::Path;
use std::time::Duration;

use njmule_engine::api::category::Category;
use njmule_engine::api::download_info::{DownloadPriority, DownloadStatus};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::schedule::{DayOfWeek, Rule, Schedule};
use njmule_engine::engine::Engine;
use time::Time;

use crate::free_port;

fn film() -> FileLink {
    FileLink::of("film.mkv", 5000, [0xa1; 16]).unwrap()
}

#[tokio::test]
async fn should_hand_the_log_of_the_start_to_those_who_listen_later() {
    let mut lab = Lab::new();
    lab.given_a_running_engine().await;

    let lines = lab.when_someone_listens().await;

    assert!(lines.iter().any(|l| l.contains("started")), "lines: {lines:?}");
    lab.stop().await;
}

#[tokio::test]
async fn should_act_once_on_a_rule_that_holds_at_the_start() {
    let mut lab = Lab::new();
    lab.given_a_running_engine().await;
    lab.set_categories(&[1, 2]).await;
    lab.given_the_film_in_category(1).await;
    lab.set_rule("night", 1).await;

    lab.restart().await;
    let lines = lab.when_someone_listens().await;

    assert!(lines.iter().any(|l| l.contains("started")), "lines: {lines:?}");
    let stopped_count = lines.iter().filter(|l| l.contains("category 1 stopped")).count();
    assert_eq!(stopped_count, 1, "lines: {lines:?}");
    lab.stop().await;
}

#[tokio::test]
async fn should_not_run_the_actions_of_the_rule_in_force_again_when_it_is_edited() {
    let mut lab = Lab::new();
    lab.given_a_running_engine().await;
    lab.set_categories(&[1]).await;
    lab.given_the_film_in_category(1).await;
    lab.set_rule("night", 1).await;
    assert_eq!(lab.film_status().await, DownloadStatus::Stopped);
    lab.engine().resume_download(film().hash).await.unwrap();

    lab.set_rule("late night", 1).await;

    assert_ne!(lab.film_status().await, DownloadStatus::Stopped, "resumed by hand");
    lab.stop().await;
}

#[tokio::test]
async fn should_take_a_deleted_category_out_of_the_rules() {
    let mut lab = Lab::new();
    lab.given_a_running_engine().await;
    lab.set_categories(&[1, 2]).await;
    let rule = Rule {
        name: "later".to_owned(),
        enabled: false,
        days: all_days(),
        start: Time::MIDNIGHT,
        end: Time::MIDNIGHT,
        download_kibps: None,
        upload_kibps: None,
        max_connections: None,
        max_sources_per_file: None,
        stop_category: Some(2),
        resume_category: Some(1),
    };
    lab.engine().update_schedule(Schedule { rules: vec![rule] }).await.unwrap();

    lab.set_categories(&[1]).await;

    let schedule = lab.engine().schedule().await;
    assert_eq!(schedule.rules[0].stop_category, None, "a new category 2 is not stopped");
    assert_eq!(schedule.rules[0].resume_category, Some(1));
    lab.stop().await;
}

#[tokio::test]
async fn should_create_the_folders_chosen_for_the_downloads_and_not_the_default_ones() {
    let mut lab = Lab::new();
    let incoming = lab.dir.path().join("Download-Media");
    let temp = lab.dir.path().join("Part-Files");
    let dirs = AppDirectories::under_root(&lab.dir.path().join("njmule")).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    let port = free_port::free_port();
    fs::write(
        dirs.config.join("njmule.properties"),
        format!(
            "net.tcpPort={port}\nnet.kad.enabled=false\nnet.ed2k.enabled=false\ndirs.incoming={}\ndirs.temp={}\n",
            escaped(&incoming),
            escaped(&temp),
        ),
    )
    .unwrap();

    lab.given_a_running_engine().await;

    assert!(incoming.is_dir());
    assert!(temp.is_dir());
    assert!(!dirs.downloads.is_dir(), "completed default folder should not exist");
    assert!(!dirs.temp.is_dir(), "part files default folder should not exist");
    lab.stop().await;
}

struct Lab {
    dir: tempfile::TempDir,
    engine: Option<Engine>,
}

impl Lab {
    fn new() -> Self {
        Self { dir: tempfile::tempdir().unwrap(), engine: None }
    }

    fn engine(&self) -> &Engine {
        self.engine.as_ref().unwrap()
    }

    async fn given_a_running_engine(&mut self) {
        let dirs = AppDirectories::under_root(&self.dir.path().join("njmule")).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let prefs = dirs.config.join("njmule.properties");
        if !prefs.exists() {
            let port = free_port::free_port();
            fs::write(&prefs, format!("net.tcpPort={port}\nnet.kad.enabled=false\n")).unwrap();
        }
        self.engine = Some(Engine::start(dirs).await.unwrap());
    }

    async fn restart(&mut self) {
        if let Some(engine) = self.engine.take() {
            engine.stop().await;
        }
        self.given_a_running_engine().await;
    }

    async fn set_categories(&self, ids: &[u32]) {
        let cats: Vec<Category> = ids
            .iter()
            .map(|&id| Category {
                id,
                name: format!("c{id}"),
                incoming: None,
                comment: String::new(),
                color: id,
                priority: DownloadPriority::Auto,
                patterns: String::new(),
                regex: false,
            })
            .collect();
        self.engine().set_categories(cats).await.unwrap();
    }

    async fn given_the_film_in_category(&self, category: u32) {
        self.engine().add_download(film(), None).await.unwrap();
        assert!(self.engine().set_download_category(film().hash, category).await);
    }

    async fn set_rule(&self, name: &str, stop_category: u32) {
        let rule = Rule {
            name: name.to_owned(),
            enabled: true,
            days: all_days(),
            start: Time::MIDNIGHT,
            end: Time::MIDNIGHT,
            download_kibps: None,
            upload_kibps: None,
            max_connections: None,
            max_sources_per_file: None,
            stop_category: Some(stop_category),
            resume_category: None,
        };
        self.engine().update_schedule(Schedule { rules: vec![rule] }).await.unwrap();
    }

    async fn film_status(&self) -> DownloadStatus {
        self.engine().downloads().await.into_iter().find(|d| d.hash == film().hash).unwrap().status
    }

    async fn when_someone_listens(&self) -> Vec<String> {
        let mut sub = self.engine().subscribe();
        let mut lines: Vec<String> = sub
            .missed
            .into_iter()
            .filter_map(|e| match e {
                EngineEvent::Log(line) => Some(line.message),
                _ => None,
            })
            .collect();
        let deadline = tokio::time::Instant::now() + Duration::from_millis(300);
        while tokio::time::Instant::now() < deadline {
            match tokio::time::timeout(Duration::from_millis(50), sub.live.recv()).await {
                Ok(Ok(EngineEvent::Log(line))) => lines.push(line.message),
                Ok(Ok(_)) => {}
                _ => break,
            }
        }
        lines
    }

    async fn stop(mut self) {
        if let Some(engine) = self.engine.take() {
            engine.stop().await;
        }
    }
}

fn all_days() -> BTreeSet<DayOfWeek> {
    [
        DayOfWeek::Monday,
        DayOfWeek::Tuesday,
        DayOfWeek::Wednesday,
        DayOfWeek::Thursday,
        DayOfWeek::Friday,
        DayOfWeek::Saturday,
        DayOfWeek::Sunday,
    ]
    .into_iter()
    .collect()
}

fn escaped(path: &Path) -> String {
    path.to_string_lossy().replace('\\', "\\\\")
}
