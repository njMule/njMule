// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java EngineApiTest that the Rust engine answers, under their names: the API's calls through a
//! running engine.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod free_port;

use std::fs;
use std::time::Duration;

use njmule_engine::api::download_info::{DownloadPriority, DownloadStatus};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::search_result::SearchResult;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;

const SONG: [u8; 16] = [0xf4; 16];
const FILM: [u8; 16] = [0xf3; 16];
const WAIT: Duration = Duration::from_secs(5);

#[tokio::test]
async fn should_swap_sources_and_take_them_by_itself_through_the_engine() {
    let mut api = EngineApi::new();
    api.given_a_download_without_sources().await;

    api.when_every_swap_is_asked_for_the_download().await;

    api.then_the_swap_answers_are("0 0 true false");
    api.then_the_download_takes_a4af_sources_by_itself().await;
}

#[tokio::test]
async fn should_stop_prioritise_and_preview_a_download_through_the_engine() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;
    api.given_a_download_of_the_film().await;
    api.given_the_film_priority_is_set_to(DownloadPriority::Low).await;
    api.given_the_film_preview_is_turned_on().await;
    api.given_the_film_has_no_data_to_preview_yet().await;

    api.when_the_film_is_stopped().await;

    api.then_the_stop_is_done();
    api.then_the_only_download_is(DownloadStatus::Stopped, DownloadPriority::Low).await;
    api.then_the_description_of_the_engine_mentions("film.mkv").await;
}

#[tokio::test]
async fn should_not_recheck_the_kad_firewall_nor_list_anything_while_kad_does_not_run() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;

    api.when_the_kad_firewall_is_rechecked().await;

    api.then_the_firewall_recheck_is(false);
    api.then_there_are_no_kad_contacts().await;
    api.then_there_are_no_kad_searches().await;
}

#[tokio::test]
async fn should_mark_spam_through_the_engine() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;

    api.when_the_search_result_is_marked_as_spam([0xb2; 16], "spam.exe").await;

    api.then_the_spam_is(&[[0xb2; 16]]).await;
}

#[tokio::test]
async fn should_rate_a_lookalike_of_a_search_result_marked_as_spam() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;
    api.given_the_search_result_marked_as_spam([0xb2; 16], "Free movie player.exe").await;

    api.when_the_result_is_rated(result([0xc3; 16], "free movie player.EXE")).await;

    api.then_the_rated_result_is_spam(true);
}

#[tokio::test]
async fn should_rate_nothing_as_spam_with_the_spam_filter_off() {
    let mut api = EngineApi::new();
    api.given_a_running_engine_without_the_spam_filter().await;
    api.given_the_search_result_marked_as_spam([0xb2; 16], "Free movie player.exe").await;

    api.when_the_result_is_rated(result([0xb2; 16], "Free movie player.exe")).await;

    api.then_the_rated_result_is_spam(false);
}

fn result(hash: [u8; 16], name: &str) -> SearchResult {
    SearchResult::new(hash, name, 10, 1, Some(0), None)
}

struct EngineApi {
    profile: tempfile::TempDir,
    engine: Option<Engine>,
    swap_answers: Vec<String>,
    stopped: bool,
    firewall_rechecked: bool,
    rated: Vec<SearchResult>,
}

impl EngineApi {
    fn new() -> Self {
        Self {
            profile: tempfile::tempdir().unwrap(),
            engine: None,
            swap_answers: Vec::new(),
            stopped: false,
            firewall_rechecked: false,
            rated: Vec::new(),
        }
    }

    async fn given_a_running_engine(&mut self) {
        self.start("").await;
    }

    async fn given_a_running_engine_without_the_spam_filter(&mut self) {
        self.start("search.spamFilter=false\n").await;
    }

    async fn given_the_search_result_marked_as_spam(&self, hash: [u8; 16], name: &str) {
        self.when_the_search_result_is_marked_as_spam(hash, name).await;
    }

    async fn when_the_search_result_is_marked_as_spam(&self, hash: [u8; 16], name: &str) {
        within(self.engine().mark_spam(vec![result(hash, name)], true)).await;
    }

    async fn when_the_result_is_rated(&mut self, found: SearchResult) {
        self.rated = within(self.engine().rate_spam(vec![found])).await;
    }

    async fn then_the_spam_is(&self, hashes: &[[u8; 16]]) {
        let marks = within(self.engine().spam_marks()).await;
        assert_eq!(marks.spam, hashes.iter().copied().collect());
    }

    fn then_the_rated_result_is_spam(&self, spam: bool) {
        assert_eq!(self.rated.len(), 1);
        assert_eq!(self.rated[0].is_spam(), spam);
    }

    async fn start(&mut self, preferences: &str) {
        let dirs = AppDirectories::under_root(self.profile.path()).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let preferences = format!("net.tcpPort={}\nnet.kad.enabled=false\n{preferences}", free_port::free_port());
        fs::write(dirs.config.join("njmule.properties"), preferences).unwrap();
        self.engine = Some(Engine::start(dirs).await.unwrap());
    }

    async fn given_a_download_without_sources(&mut self) {
        self.given_a_running_engine().await;
        self.add("song.mp3", SONG).await;
    }

    async fn given_a_download_of_the_film(&self) {
        self.add("film.mkv", FILM).await;
    }

    async fn given_the_film_priority_is_set_to(&self, priority: DownloadPriority) {
        assert!(within(self.engine().set_download_priority(FILM, priority)).await);
    }

    async fn given_the_film_preview_is_turned_on(&self) {
        assert!(within(self.engine().set_download_preview(FILM, true)).await);
    }

    async fn given_the_film_has_no_data_to_preview_yet(&self) {
        assert_eq!(within(self.engine().download_data(FILM)).await, None);
    }

    async fn when_every_swap_is_asked_for_the_download(&mut self) {
        let engine = self.engine();
        let source = "127.0.0.1:4662".parse().unwrap();
        self.swap_answers = vec![
            within(engine.swap_sources_to_download(SONG)).await.to_string(),
            within(engine.swap_sources_from_download(SONG)).await.to_string(),
            within(engine.set_download_auto_swap(SONG, true)).await.to_string(),
            within(engine.swap_source_to_download(SONG, source)).await.to_string(),
        ];
    }

    async fn when_the_film_is_stopped(&mut self) {
        self.stopped = within(self.engine().stop_download(FILM)).await;
    }

    async fn when_the_kad_firewall_is_rechecked(&mut self) {
        self.firewall_rechecked = within(self.engine().recheck_kad()).await;
    }

    fn then_the_firewall_recheck_is(&self, done: bool) {
        assert_eq!(self.firewall_rechecked, done);
    }

    async fn then_there_are_no_kad_contacts(&self) {
        assert!(within(self.engine().kad_contacts()).await.is_empty());
    }

    async fn then_there_are_no_kad_searches(&self) {
        assert!(within(self.engine().kad_searches()).await.is_empty());
    }

    fn then_the_swap_answers_are(&self, expected: &str) {
        assert_eq!(self.swap_answers.join(" "), expected);
    }

    async fn then_the_download_takes_a4af_sources_by_itself(&self) {
        let downloads = within(self.engine().downloads()).await;
        assert_eq!(downloads.len(), 1);
        assert!(downloads[0].auto_swap);
    }

    fn then_the_stop_is_done(&self) {
        assert!(self.stopped);
    }

    async fn then_the_only_download_is(&self, status: DownloadStatus, priority: DownloadPriority) {
        let downloads = within(self.engine().downloads()).await;
        assert_eq!(downloads.len(), 1);
        assert_eq!((downloads[0].status, downloads[0].priority), (status, priority));
    }

    async fn then_the_description_of_the_engine_mentions(&self, text: &str) {
        let described = within(self.engine().describe()).await;
        assert!(described.contains(text), "{text} in {described}");
    }

    async fn add(&self, name: &str, hash: [u8; 16]) {
        within(self.engine().add_download(FileLink::of(name, 5000, hash).unwrap(), None)).await.unwrap();
    }

    fn engine(&self) -> Engine {
        self.engine.clone().expect("a running engine")
    }
}

impl Drop for EngineApi {
    fn drop(&mut self) {
        if let Some(engine) = self.engine.take() {
            tokio::spawn(async move { engine.stop().await });
        }
    }
}

async fn within<T>(work: impl Future<Output = T>) -> T {
    tokio::time::timeout(WAIT, work).await.expect("answered in time")
}
