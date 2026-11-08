// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java EngineApiTest that the Rust engine answers, under their names: the API's calls through a
//! running engine.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

mod free_port;

use std::fs;
use std::net::{Ipv4Addr, SocketAddrV4};
use std::time::{Duration, Instant};

use njmule_engine::api::category::Category;
use njmule_engine::api::download_info::{DownloadPriority, DownloadStatus};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::list_source::{ListError, ListSource};
use njmule_engine::api::log_line::Level;
use njmule_engine::api::nj_mule_engine::Subscription;
use njmule_engine::api::search_result::SearchResult;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::schedule::{DayOfWeek, Rule, Schedule};
use njmule_engine::engine::Engine;
use njmule_engine::engine::ip_filter_lists;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::ip_filter_dat::Range;
use njmule_engine::persist::format::nodes_dat::{self, Contact, UdpKey};
use njmule_engine::persist::schedule_codec;
use strum::VariantArray;

const SONG: [u8; 16] = [0xf4; 16];
const FILM: [u8; 16] = [0xf3; 16];
const WAIT: Duration = Duration::from_secs(5);
const FILMS: (u32, &str) = (1, "Films");
const MUSIC: (u32, &str) = (2, "Music");
const LOCAL_LIST: &str = "http://127.0.0.1:9/list";

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

#[tokio::test]
async fn should_share_and_unshare_folders_through_the_engine() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;
    api.given_the_folder_is_shared("films").await;

    api.when_the_folder_is_unshared("films").await;

    api.then_no_folder_is_shared().await;
}

#[tokio::test]
async fn should_lift_a_ban_only_from_an_address() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;

    api.when_the_ban_is_lifted_from("not an address").await;

    api.then_the_address_is_refused();
}

#[tokio::test]
async fn should_refuse_a_kad_bootstrap_while_kad_does_not_run() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;

    api.when_kad_is_bootstrapped_from(4672).await;

    api.then_the_bootstrap_is_refused();
}

#[tokio::test]
async fn should_hand_kad_commands_to_a_running_kad() {
    let mut api = EngineApi::new();
    api.given_a_running_engine_with_kad().await;
    api.given_kad_runs().await;
    api.given_kad_is_bootstrapped_from_a_closed_port_of_this_machine().await;

    api.when_the_kad_firewall_is_rechecked().await;

    api.then_the_firewall_recheck_is(true);
}

#[tokio::test]
async fn should_never_fetch_a_server_list_from_this_machine() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;

    api.when_a_server_list_is_fetched_from(LOCAL_LIST).await;

    api.then_the_list_fails_with("local or private");
}

#[tokio::test]
async fn should_never_fetch_kad_contacts_from_this_machine() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;

    api.when_kad_contacts_are_fetched_from(LOCAL_LIST).await;

    api.then_the_list_fails_with("local or private");
}

#[tokio::test]
async fn should_keep_the_kad_contacts_of_a_nodes_dat_file() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;
    api.given_a_nodes_dat_file_with_contacts(2);

    api.when_the_kad_contacts_of_the_file_are_added().await;

    api.then_the_new_contacts_are(2);
    api.then_our_nodes_dat_holds(2);
}

#[tokio::test]
async fn should_refuse_a_file_that_is_no_nodes_dat() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;
    api.given_a_file_of(&[1, 2, 3]);

    api.when_the_kad_contacts_of_the_file_are_added().await;

    api.then_the_list_fails_with("not a nodes.dat");
}

#[tokio::test]
async fn should_keep_the_kad_contacts_of_a_nodes_dat_sent() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;
    api.given_a_nodes_dat_file_with_contacts(2);

    api.when_the_kad_contacts_of_the_content_are_added().await;

    api.then_the_new_contacts_are(2);
    api.then_our_nodes_dat_holds(2);
}

#[tokio::test]
async fn should_refuse_as_the_callers_error_content_that_is_no_nodes_dat() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;
    api.given_a_file_of(&[1, 2, 3]);

    api.when_the_kad_contacts_of_the_content_are_added().await;

    api.then_the_content_is_refused_as_the_callers_error();
}

#[tokio::test]
async fn should_update_the_server_lists_at_the_start_when_the_preferences_ask_for_it() {
    let mut api = EngineApi::new();
    api.given_a_profile_with("server.updateAtStart=true\n");
    api.given_the_server_list_addresses_file(
        "http://127.0.0.1:9/server.met\nftp://lists.example/server.met\nhttp://bad host/server.met\n",
    );

    api.when_the_engine_starts().await;

    api.then_the_server_list_addresses_are_only_the_web_ones(&["http://127.0.0.1:9/server.met"]).await;
    api.then_the_log_warns("Cannot update the server list from 127.0.0.1").await;
}

#[tokio::test]
async fn should_never_fetch_an_ip_filter_from_this_machine() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;

    api.when_an_ip_filter_is_fetched_from(LOCAL_LIST).await;

    api.then_the_fetch_fails_with("local or private");
}

#[test]
fn should_read_an_ip_filter_at_an_address_without_a_path_in_either_form() {
    let mut api = EngineApi::new();
    api.given_the_ip_filter_list("1.2.3.4 - 1.2.3.5 , 100 , one\n");

    api.when_the_list_is_read_from("urn:lists:ipfilter");

    api.then_the_only_range_is_described_as("one");
}

#[tokio::test]
async fn should_let_rules_go_of_deleted_categories_and_keep_the_active_rule_active() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;
    api.given_the_categories(&[FILMS, MUSIC]).await;
    api.given_the_schedule_is(&[stop_films(), resume_music()]).await;
    api.given_the_rule_in_force_is(&stop_films()).await;

    api.when_the_categories_are(&[MUSIC]).await;

    api.then_the_first_rule_stops_no_category().await;
    api.then_the_second_rule_is(&resume_music()).await;
    api.then_the_first_rule_is_still_in_force().await;
    api.then_the_saved_schedule_is_the_one_of_the_engine().await;
}

#[tokio::test]
async fn should_go_on_without_acting_again_when_a_rule_is_edited_while_it_holds() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;
    api.given_the_schedule_is(&[night()]).await;
    api.given_someone_subscribed();
    api.given_the_schedule_is(&[night()]).await;

    api.when_the_schedule_is(&[late_night()]).await;

    api.then_the_rule_in_force_is(&late_night()).await;
    api.then_the_scheduler_has_logged_only(&["Scheduler: \"night\" applies now"]).await;
}

#[tokio::test]
async fn should_refuse_a_schedule_that_cannot_be_saved() {
    let mut api = EngineApi::new();
    api.given_a_running_engine().await;
    api.given_the_schedule_cannot_be_saved();

    api.when_the_schedule_is_emptied().await;

    api.then_the_schedule_is_refused();
}

// A contact of a public address, its id and address told by `n`.
fn contact(n: u8) -> Contact {
    Contact {
        id: UInt128(u128::from(n)),
        ip: u32::from_be_bytes([81, 2, 3, n]),
        udp_port: 4672,
        tcp_port: 4662,
        version: 8,
        contact_type: 0,
        udp_key: UdpKey::default(),
        verified: false,
    }
}

fn stop_films() -> Rule {
    rule("films off", true, Some(FILMS.0), None)
}

fn resume_music() -> Rule {
    rule("music on", false, None, Some(MUSIC.0))
}

fn night() -> Rule {
    rule("night", true, None, None)
}

fn late_night() -> Rule {
    rule("late night", true, None, None)
}

// A rule that holds all week.
fn rule(name: &str, enabled: bool, stop_category: Option<u32>, resume_category: Option<u32>) -> Rule {
    Rule {
        enabled,
        days: DayOfWeek::VARIANTS.iter().copied().collect(),
        stop_category,
        resume_category,
        ..Rule::new(name)
    }
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
    unbanned: Option<Result<bool, String>>,
    bootstrapped: Option<Result<(), String>>,
    kad_port: u16,
    rated: Vec<SearchResult>,
    recorder: Option<Recorder>,
    slow: Option<Recorder>,
    schedule_update: Option<Result<Schedule, String>>,
    fetched: Option<Result<usize, String>>,
    listed: Option<Result<usize, ListError>>,
    given_preferences: String,
    file: Vec<u8>,
    list: Vec<u8>,
    ranges: Vec<Range>,
}

impl EngineApi {
    fn new() -> Self {
        Self {
            profile: tempfile::tempdir().unwrap(),
            engine: None,
            swap_answers: Vec::new(),
            stopped: false,
            firewall_rechecked: false,
            unbanned: None,
            bootstrapped: None,
            kad_port: 0,
            rated: Vec::new(),
            recorder: None,
            slow: None,
            schedule_update: None,
            fetched: None,
            listed: None,
            given_preferences: String::new(),
            file: Vec::new(),
            list: Vec::new(),
            ranges: Vec::new(),
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

    async fn given_a_running_engine_with_kad(&mut self) {
        self.kad_port = free_port::free_port();
        self.start(&format!("net.kad.enabled=true\nnet.udpPort={}\n", self.kad_port)).await;
    }

    async fn given_kad_runs(&self) {
        let deadline = Instant::now() + WAIT;
        while within(self.engine().kad()).await.is_none() {
            assert!(Instant::now() < deadline, "Kad runs");
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }

    // A bootstrap request to a closed port on this machine.
    async fn given_kad_is_bootstrapped_from_a_closed_port_of_this_machine(&mut self) {
        let closed = if self.kad_port == u16::MAX { self.kad_port - 1 } else { self.kad_port + 1 };
        self.when_kad_is_bootstrapped_from(closed).await;
        assert_eq!(self.bootstrapped, Some(Ok(())));
    }

    async fn when_the_ban_is_lifted_from(&mut self, address: &str) {
        self.unbanned = Some(within(self.engine().unban(address.to_owned())).await);
    }

    async fn when_kad_is_bootstrapped_from(&mut self, port: u16) {
        let node = SocketAddrV4::new(Ipv4Addr::LOCALHOST, port);
        self.bootstrapped = Some(within(self.engine().bootstrap_kad(node)).await);
    }

    async fn given_the_folder_is_shared(&self, name: &str) {
        let folder = self.profile.path().join(name);
        fs::create_dir_all(&folder).unwrap();
        within(self.engine().share_directory(folder.clone(), false)).await.unwrap();
        let shared = within(self.engine().shared_directories()).await;
        assert_eq!(shared.iter().map(|directory| directory.path.clone()).collect::<Vec<_>>(), [folder]);
    }

    async fn when_the_folder_is_unshared(&self, name: &str) {
        within(self.engine().unshare_directory(self.profile.path().join(name))).await.unwrap();
    }

    async fn then_no_folder_is_shared(&self) {
        assert_eq!(within(self.engine().shared_directories()).await, []);
    }

    fn then_the_address_is_refused(&self) {
        assert!(matches!(self.unbanned, Some(Err(_))), "{:?}", self.unbanned);
    }

    fn then_the_bootstrap_is_refused(&self) {
        assert!(matches!(self.bootstrapped, Some(Err(_))), "{:?}", self.bootstrapped);
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

    fn given_a_profile_with(&mut self, preferences: &str) {
        preferences.clone_into(&mut self.given_preferences);
    }

    fn given_the_server_list_addresses_file(&self, text: &str) {
        let config = AppDirectories::under_root(self.profile.path()).unwrap().config;
        fs::create_dir_all(&config).unwrap();
        fs::write(config.join("addresses.dat"), text).unwrap();
    }

    fn given_a_nodes_dat_file_with_contacts(&mut self, count: u8) {
        let contacts: Vec<Contact> = (1..=count).map(contact).collect();
        self.given_a_file_of(&nodes_dat::write(&contacts).unwrap());
    }

    fn given_a_file_of(&mut self, data: &[u8]) {
        self.file = data.to_vec();
        fs::write(self.list_file(), data).unwrap();
    }

    async fn when_the_engine_starts(&mut self) {
        let preferences = self.given_preferences.clone();
        self.start(&preferences).await;
        self.recorder = Some(Recorder::of(&self.engine()));
    }

    async fn when_a_server_list_is_fetched_from(&mut self, url: &str) {
        self.listed = Some(within(self.engine().add_servers(ListSource::Web(url.to_owned()))).await);
    }

    async fn when_kad_contacts_are_fetched_from(&mut self, url: &str) {
        self.listed = Some(within(self.engine().add_kad_contacts(ListSource::Web(url.to_owned()))).await);
    }

    async fn when_the_kad_contacts_of_the_file_are_added(&mut self) {
        let file = ListSource::File(self.list_file());
        self.listed = Some(within(self.engine().add_kad_contacts(file)).await);
    }

    async fn when_the_kad_contacts_of_the_content_are_added(&mut self) {
        let content = ListSource::Data(self.file.clone());
        self.listed = Some(within(self.engine().add_kad_contacts(content)).await);
    }

    fn then_the_list_fails_with(&self, text: &str) {
        match self.listed.as_ref().unwrap() {
            Err(ListError::Failed(reason)) => assert!(reason.contains(text), "{reason}"),
            other => panic!("not failed: {other:?}"),
        }
    }

    fn then_the_content_is_refused_as_the_callers_error(&self) {
        assert!(matches!(self.listed, Some(Err(ListError::NotAList(_)))), "{:?}", self.listed);
    }

    fn then_the_new_contacts_are(&self, count: usize) {
        assert_eq!(self.listed, Some(Ok(count)));
    }

    fn then_our_nodes_dat_holds(&self, count: usize) {
        let ours = fs::read(self.engine().directories().config.join("nodes.dat")).unwrap();
        assert_eq!(nodes_dat::read(&ours).unwrap().contacts.len(), count);
    }

    async fn then_the_server_list_addresses_are_only_the_web_ones(&self, addresses: &[&str]) {
        assert_eq!(within(self.engine().server_list_addresses()).await, addresses);
    }

    async fn then_the_log_warns(&mut self, start: &str) {
        assert!(self.recorder.as_mut().unwrap().await_log(start).await, "{start}");
    }

    // A list the user picked, outside the profile's folders.
    fn list_file(&self) -> std::path::PathBuf {
        self.profile.path().join("picked.dat")
    }

    fn given_the_ip_filter_list(&mut self, list: &str) {
        self.list = list.as_bytes().to_vec();
    }

    async fn when_an_ip_filter_is_fetched_from(&mut self, url: &str) {
        self.fetched = Some(within(self.engine().update_ip_filter(url.to_owned())).await);
    }

    fn when_the_list_is_read_from(&mut self, address: &str) {
        self.ranges = ip_filter_lists::ip_ranges(&self.list, address);
    }

    fn then_the_fetch_fails_with(&self, text: &str) {
        let failure = self.fetched.as_ref().unwrap().as_ref().unwrap_err();
        assert!(failure.contains(text), "{failure}");
    }

    fn then_the_only_range_is_described_as(&self, description: &str) {
        assert_eq!(self.ranges.len(), 1);
        assert_eq!(self.ranges[0].description, description);
    }

    async fn given_the_categories(&self, categories: &[(u32, &str)]) {
        self.when_the_categories_are(categories).await;
    }

    async fn given_the_schedule_is(&self, rules: &[Rule]) {
        self.when_the_schedule_is(rules).await;
    }

    async fn given_the_rule_in_force_is(&self, rule: &Rule) {
        self.then_the_rule_in_force_is(rule).await;
    }

    // A folder where the new file is written first.
    fn given_the_schedule_cannot_be_saved(&self) {
        let temporary = self.engine().directories().config.join(format!("{}.tmp", schedule_codec::FILE_NAME));
        fs::create_dir_all(&temporary).unwrap();
        fs::write(temporary.join("keep"), "x").unwrap();
    }

    async fn when_the_categories_are(&self, categories: &[(u32, &str)]) {
        let categories = categories
            .iter()
            .map(|&(id, name)| Category {
                id,
                name: name.to_owned(),
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

    async fn when_the_schedule_is(&self, rules: &[Rule]) {
        within(self.engine().update_schedule(Schedule { rules: rules.to_vec() })).await.unwrap();
    }

    async fn when_the_schedule_is_emptied(&mut self) {
        self.schedule_update = Some(within(self.engine().update_schedule(Schedule::default())).await);
    }

    async fn then_the_first_rule_stops_no_category(&self) {
        assert_eq!(within(self.engine().schedule()).await.rules[0].stop_category, None);
    }

    async fn then_the_second_rule_is(&self, rule: &Rule) {
        assert_eq!(&within(self.engine().schedule()).await.rules[1], rule);
    }

    async fn then_the_first_rule_is_still_in_force(&self) {
        let first = within(self.engine().schedule()).await.rules[0].clone();
        assert_eq!(within(self.engine().active_rule()).await, Some(first));
    }

    async fn then_the_saved_schedule_is_the_one_of_the_engine(&self) {
        let saved = schedule_codec::load(&self.engine().directories().config.join(schedule_codec::FILE_NAME)).unwrap();
        assert_eq!(saved, within(self.engine().schedule()).await);
    }

    async fn then_the_rule_in_force_is(&self, rule: &Rule) {
        assert_eq!(within(self.engine().active_rule()).await.as_ref(), Some(rule));
    }

    // A line of our own marks the end of what was logged before it.
    async fn then_the_scheduler_has_logged_only(&mut self, messages: &[&str]) {
        self.engine().user_log(Level::Info, "done");
        let recorder = self.recorder.as_mut().unwrap();
        assert!(recorder.await_log("done").await);
        let logged: Vec<String> =
            recorder.logs().into_iter().filter(|message| message.starts_with("Scheduler")).collect();
        assert_eq!(logged, messages, "only the first rule acted, at the first update");
    }

    fn then_the_schedule_is_refused(&self) {
        let refused = self.schedule_update.as_ref().unwrap().as_ref().unwrap_err();
        assert!(refused.starts_with("Cannot save the schedule"), "{refused}");
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
