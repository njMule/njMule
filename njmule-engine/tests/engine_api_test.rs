// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java EngineApiTest that the Rust engine answers, under their names: the API's calls through a
//! running engine.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod free_port;

use std::fs;
use std::time::{Duration, Instant};

use njmule_engine::api::download_info::{DownloadPriority, DownloadStatus};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::log_line::Level;
use njmule_engine::api::nj_mule_engine::Subscription;
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

#[tokio::test]
async fn should_replay_the_log_up_to_its_limit_to_a_late_subscriber() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;
    api.given_the_engine_logged(Level::Debug, "a detail");
    api.given_the_engine_logged(Level::Error, "a failure");
    api.given_the_engine_logged_numbered_lines("line ", 150);

    api.when_someone_subscribes();

    api.then_the_subscriber_gets_the_log("line 149").await;
    api.then_the_first_event_is_the_first_log();
    api.then_the_first_log_is("line 50");
}

#[tokio::test]
async fn should_let_a_slow_subscriber_miss_events_while_the_others_get_everything() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;
    api.given_someone_subscribed();
    api.given_a_slow_subscriber_reading_one_event().await;

    api.when_the_engine_logs_numbered_lines_in_steps("more ", 3_000).await;

    api.then_the_other_subscriber_still_gets_the_log("more 2999").await;
    api.then_the_slow_subscriber_missed_events();
}

#[tokio::test]
async fn should_send_nowhere_what_is_logged_after_the_end() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;
    api.given_someone_subscribed();
    api.given_the_engine_is_closed().await;

    api.when_the_engine_logs("too late");

    api.then_the_events_are_completed().await;
    api.then_the_last_event_is_the_stop();
}

// A follower of the engine's events, which keeps those it read.
struct Recorder {
    subscription: Subscription,
    events: Vec<EngineEvent>,
}

impl Recorder {
    fn of(engine: &Engine) -> Self {
        let subscription = engine.subscribe();
        let events = subscription.missed.clone();
        Self { subscription, events }
    }

    // Reads until a log line starting with `start` comes, for 10 seconds at most.
    async fn await_log(&mut self, start: &str) -> bool {
        let deadline = Instant::now() + Duration::from_secs(10);
        while !self.logs().iter().any(|message| message.starts_with(start)) {
            let left = deadline.saturating_duration_since(Instant::now());
            match tokio::time::timeout(left, self.subscription.live.recv()).await {
                Ok(Ok(event)) => self.events.push(event),
                Ok(Err(_)) | Err(_) => return false,
            }
        }
        true
    }

    fn logs(&self) -> Vec<String> {
        self.events
            .iter()
            .filter_map(|event| match event {
                EngineEvent::Log(line) => Some(line.message.clone()),
                _ => None,
            })
            .collect()
    }
}

struct EngineApi {
    profile: tempfile::TempDir,
    engine: Option<Engine>,
    swap_answers: Vec<String>,
    stopped: bool,
    firewall_rechecked: bool,
    rated: Vec<SearchResult>,
    recorder: Option<Recorder>,
    slow: Option<Recorder>,
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
            recorder: None,
            slow: None,
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

    fn given_the_engine_logged(&self, level: Level, line: &str) {
        self.engine().user_log(level, line);
    }

    fn given_the_engine_logged_numbered_lines(&self, prefix: &str, count: usize) {
        for i in 0..count {
            self.engine().user_log(Level::Info, format!("{prefix}{i}"));
        }
    }

    fn given_someone_subscribed(&mut self) {
        self.when_someone_subscribes();
    }

    // It reads the first event, then no more.
    async fn given_a_slow_subscriber_reading_one_event(&mut self) {
        let mut slow = Recorder::of(&self.engine());
        slow.events.clear();
        self.engine().user_log(Level::Info, "first");
        let first = within(slow.subscription.live.recv()).await.unwrap();
        slow.events.push(first);
        self.slow = Some(slow);
    }

    async fn given_the_engine_is_closed(&self) {
        self.engine().stop().await;
    }

    fn when_someone_subscribes(&mut self) {
        self.recorder = Some(Recorder::of(&self.engine()));
    }

    // In steps the others can follow: any subscriber that falls the backlog behind misses events.
    async fn when_the_engine_logs_numbered_lines_in_steps(&mut self, prefix: &str, count: usize) {
        for i in 0..count {
            self.engine().user_log(Level::Info, format!("{prefix}{i}"));
            if i % 500 == 499 {
                let line = format!("{prefix}{i}");
                assert!(self.recorder.as_mut().unwrap().await_log(&line).await);
            }
        }
    }

    fn when_the_engine_logs(&self, line: &str) {
        self.engine().user_log(Level::Info, line);
    }

    async fn then_the_subscriber_gets_the_log(&mut self, start: &str) {
        assert!(self.recorder.as_mut().unwrap().await_log(start).await, "{start} logged");
    }

    fn then_the_first_event_is_the_first_log(&self) {
        let recorder = self.recorder.as_ref().unwrap();
        assert!(matches!(recorder.events.first(), Some(EngineEvent::Log(_))), "{:?}", recorder.events.first());
    }

    fn then_the_first_log_is(&self, message: &str) {
        assert_eq!(self.recorder.as_ref().unwrap().logs().first().map(String::as_str), Some(message));
    }

    async fn then_the_other_subscriber_still_gets_the_log(&mut self, start: &str) {
        assert!(self.recorder.as_mut().unwrap().await_log(start).await, "the others still get everything");
    }

    fn then_the_slow_subscriber_missed_events(&mut self) {
        let slow = self.slow.as_mut().unwrap();
        assert_eq!(slow.events.len(), 1, "only what it read");
        assert!(
            matches!(slow.subscription.live.try_recv(), Err(tokio::sync::broadcast::error::TryRecvError::Lagged(_))),
            "it missed the events it fell behind on"
        );
    }

    // Read to the end of what came: nothing comes after.
    async fn then_the_events_are_completed(&mut self) {
        let recorder = self.recorder.as_mut().unwrap();
        while let Ok(Ok(event)) =
            tokio::time::timeout(Duration::from_millis(200), recorder.subscription.live.recv()).await
        {
            recorder.events.push(event);
        }
    }

    fn then_the_last_event_is_the_stop(&self) {
        assert_eq!(self.recorder.as_ref().unwrap().events.last(), Some(&EngineEvent::Stopping));
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
