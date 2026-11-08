// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's EngineTest: the start, with its configuration, first set-up and lock, the
//! preferences, the servers of a server.met, the status and the totals across restarts. Those of the schedule are in
//! `engine_schedule_test.rs` and `scheduler_test.rs`.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::path::PathBuf;
use std::time::Duration;

use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::engine_status::EngineStatus;
use njmule_engine::api::list_source::ListSource;
use njmule_engine::api::nj_mule_engine::Subscription;
use njmule_engine::api::notification_result::NotificationResult;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::config::schedule::{DayOfWeek, Rule, Schedule};
use njmule_engine::engine::Engine;
use njmule_engine::persist::format::server_met::{self, Server};
use njmule_engine::persist::preferences_codec;
use njmule_engine::protocol::tag::{Tag, TagName, server_tag};
use njmule_engine::server::server_list::{Address, Priority};
use strum::VariantArray;
use tokio::sync::broadcast::error::RecvError;

const WAIT: Duration = Duration::from_secs(5);

#[tokio::test]
async fn should_create_the_layout_and_save_the_default_preferences_at_the_start() {
    let mut test = Engines::new();

    test.when_the_engine_starts().await;

    test.then_the_engine_runs();
    test.then_the_log_folder_exists();
    test.then_the_preferences_are_saved();
    test.then_the_preferences_are_the_defaults();
}

#[tokio::test]
async fn should_wait_to_be_set_up_when_its_start_created_the_configuration() {
    let mut test = Engines::new();

    test.when_the_engine_starts().await;

    test.then_the_setup_is_pending(true).await;
}

#[tokio::test]
async fn should_still_wait_to_be_set_up_after_a_restart() {
    let mut test = Engines::new();
    test.given_a_running_engine().await;

    test.when_the_engine_is_restarted().await;

    test.then_the_setup_is_pending(true).await;
}

#[tokio::test]
async fn should_no_longer_wait_to_be_set_up_once_the_setup_is_done() {
    let mut test = Engines::new();
    test.given_a_running_engine().await;
    test.given_the_setup_is_done().await;

    test.when_the_engine_is_restarted().await;

    test.then_the_setup_is_pending(false).await;
}

#[tokio::test]
async fn should_not_wait_to_be_set_up_on_a_configuration_of_before() {
    let mut test = Engines::new();
    test.given_preferences_saved_before();

    test.when_the_engine_starts().await;

    test.then_the_setup_is_pending(false).await;
}

#[tokio::test]
async fn should_publish_a_change_of_the_preferences_once_and_the_stop_last() {
    let mut test = Engines::new();
    test.given_a_running_engine().await;
    test.given_someone_listens();
    test.given_the_nick_is_changed_to("renamed");
    test.given_the_same_preferences_are_set_again();

    test.when_the_engine_is_closed().await;

    test.then_the_events_end().await;
    test.then_the_changes_of_the_preferences_published_are(1);
    test.then_the_last_event_is_the_stop();
}

#[tokio::test]
async fn should_keep_a_changed_nick_after_a_restart() {
    let mut test = Engines::new();
    test.given_a_running_engine().await;
    test.given_the_nick_is_changed_to("renamed");

    test.when_the_engine_is_restarted().await;

    test.then_the_nick_is("renamed");
}

#[tokio::test]
async fn should_refuse_an_invalid_change_and_keep_the_previous_preferences() {
    let mut test = Engines::new();
    test.given_a_running_engine().await;

    test.when_the_tcp_port_is_changed_to(0);

    test.then_the_change_is_refused();
    test.then_the_preferences_are_the_defaults();
}

#[tokio::test]
async fn should_add_the_servers_of_a_server_met_with_their_names_and_priorities() {
    let mut test = Engines::new();
    test.given_a_server_met_of_two_servers();
    test.given_a_running_engine().await;

    test.when_the_servers_of_the_file_are_added().await;

    test.then_the_servers_added_are(2);
    test.then_the_server_names_are(&["One", "81.2.3.5:4242"]).await;
    test.then_the_server_priorities_are(&[Priority::High, Priority::Normal]).await;
}

#[tokio::test]
async fn should_remove_a_server_only_once() {
    let mut test = Engines::new();
    test.given_a_server_met_of_two_servers();
    test.given_a_running_engine().await;
    test.given_the_servers_of_the_file_were_added(2).await;
    test.given_the_server_was_removed("81.2.3.4:4661").await;

    test.when_the_server_is_removed("81.2.3.4:4661").await;

    test.then_nothing_is_removed();
    test.then_the_server_count_is(1).await;
}

#[tokio::test]
async fn should_tell_a_status_without_server_traffic_or_uploads() {
    let mut test = Engines::new();
    test.given_a_server_met_of_two_servers();
    test.given_a_running_engine().await;
    test.given_the_servers_of_the_file_were_added(2).await;
    test.given_the_server_was_removed("81.2.3.4:4661").await;

    test.when_the_engine_status_is_asked().await;

    test.then_the_status_has_no_server_no_id_no_download_and_no_traffic();
    test.then_there_are_no_uploads().await;
}

#[tokio::test]
async fn should_keep_servers_known_by_host_name_and_act_on_them_by_it() {
    let mut test = Engines::new();
    test.given_a_server_met_of_servers_known_by_name("localhost", "b.example");
    test.given_a_running_engine().await;
    test.given_the_servers_of_the_file_were_added(2).await;
    test.given_the_server_addresses_are(&["localhost:4661", "b.example:4661"]).await;
    test.given_the_server_is_made_static("localhost:4661").await;
    test.given_the_server_priority_is_set("localhost:4661", Priority::Low).await;

    test.when_the_server_is_removed("localhost:4661").await;

    test.then_the_server_is_removed();
    test.then_the_server_addresses_are(&["b.example:4661"]).await;
}

#[tokio::test]
async fn should_keep_the_rule_in_force_when_the_preferences_change() {
    let mut test = Engines::new();
    test.given_a_running_engine().await;
    test.given_the_schedule_holds(always()).await;

    test.when_the_nick_is_changed_to("other");

    test.then_the_effective_nick_is("other").await;
    test.then_the_effective_upload_limit_is(7).await;
}

#[tokio::test]
async fn should_count_the_first_session() {
    let mut test = Engines::new();

    test.when_the_engine_starts().await;

    test.then_the_sessions_are(1).await;
    test.then_the_total_downloaded_is(0).await;
    test.then_the_total_time_is_that_of_the_session().await;
}

#[tokio::test]
async fn should_count_the_sessions_across_restarts() {
    let mut test = Engines::new();
    test.given_a_running_engine().await;

    test.when_the_engine_is_restarted().await;

    test.then_the_sessions_are(2).await;
}

#[tokio::test]
async fn should_take_only_the_valid_totals_of_the_statistics() {
    let mut test = Engines::new();
    test.given_a_running_engine().await;
    test.given_the_engine_was_restarted().await;
    test.given_the_engine_was_closed().await;
    test.given_the_statistics("downloaded=12\nuploaded=x\nsessions=-4\n");

    test.when_the_engine_starts().await;

    test.then_the_total_downloaded_is(12).await;
    test.then_the_total_uploaded_is(0).await;
    test.then_the_sessions_are(1).await;
}

#[tokio::test]
async fn should_save_the_server_list_addresses_in_addresses_dat() {
    let mut test = Engines::new();
    test.given_a_running_engine().await;
    test.given_no_server_list_address_is_known().await;
    test.given_the_server_list_addresses(&["https://example.org/server.met"]).await;

    test.when_the_engine_is_closed().await;

    test.then_the_configuration_has_the_file("addresses.dat");
}

#[tokio::test]
async fn should_read_the_server_list_addresses_back_at_the_next_start() {
    let mut test = Engines::new();
    test.given_a_running_engine().await;
    test.given_the_server_list_addresses(&["https://example.org/server.met"]).await;
    test.given_the_engine_was_closed().await;

    test.when_the_engine_starts().await;

    test.then_the_server_list_addresses_are(&["https://example.org/server.met"]).await;
}

#[tokio::test]
async fn should_stay_closed_when_closed_again() {
    let mut test = Engines::new();
    test.given_a_running_engine().await;
    test.given_the_engine_was_closed().await;

    test.when_the_engine_is_closed().await;

    test.then_the_engine_does_not_run();
}

#[tokio::test]
async fn should_refuse_a_second_engine_on_the_same_configuration() {
    let mut test = Engines::new();
    test.given_an_engine_on_the_configuration_in("locked").await;

    test.when_a_second_engine_starts_on_the_same_configuration().await;

    test.then_the_second_engine_is_refused_as("already running");
    test.then_the_engine_runs();
}

#[tokio::test]
async fn should_let_the_next_engine_start_once_the_first_is_closed() {
    let mut test = Engines::new();
    test.given_an_engine_on_the_configuration_in("locked").await;
    test.given_a_second_engine_was_refused().await;
    test.given_the_engine_was_closed().await;

    test.when_a_second_engine_starts_on_the_same_configuration().await;

    test.then_no_exception_occurred();
}

#[tokio::test]
async fn should_test_no_notification_channel_while_none_is_on() {
    let mut test = Engines::new();
    test.given_a_running_engine().await;

    test.when_the_notifications_are_tested().await;

    test.then_no_channel_was_tested();
}

/// Engines started on a profile of a test folder, with the preferences of a first start, and what they answered.
struct Engines {
    root: tempfile::TempDir,
    dirs: AppDirectories,
    engine: Option<Engine>,
    second: Option<io::Result<Engine>>,
    subscription: Option<Subscription>,
    events: Vec<EngineEvent>,
    updated: Option<Result<Preferences, String>>,
    met: PathBuf,
    added: usize,
    removed: bool,
    status: Option<EngineStatus>,
    tested: Option<Vec<NotificationResult>>,
}

impl Engines {
    fn new() -> Self {
        let root = tempfile::tempdir().unwrap();
        let dirs = AppDirectories::under_root(root.path()).unwrap();
        Self {
            root,
            dirs,
            engine: None,
            second: None,
            subscription: None,
            events: Vec::new(),
            updated: None,
            met: PathBuf::new(),
            added: 0,
            removed: false,
            status: None,
            tested: None,
        }
    }

    async fn given_a_running_engine(&mut self) {
        self.when_the_engine_starts().await;
    }

    async fn given_an_engine_on_the_configuration_in(&mut self, folder: &str) {
        self.dirs = AppDirectories::under_root(&self.root.path().join(folder)).unwrap();
        self.when_the_engine_starts().await;
    }

    async fn given_the_setup_is_done(&self) {
        within(self.engine().finish_setup()).await.unwrap();
    }

    fn given_preferences_saved_before(&self) {
        fs::create_dir_all(&self.dirs.config).unwrap();
        preferences_codec::save(&self.dirs.config.join(preferences_codec::FILE_NAME), &Preferences::default()).unwrap();
    }

    fn given_someone_listens(&mut self) {
        self.subscription = Some(self.engine().subscribe());
    }

    fn given_the_nick_is_changed_to(&mut self, nick: &str) {
        self.when_the_nick_is_changed_to(nick);
        let updated = self.updated.clone().unwrap().unwrap();
        assert_eq!(updated.nick, nick);
        assert_eq!(self.engine().preferences(), updated);
    }

    // A change to nothing publishes nothing.
    fn given_the_same_preferences_are_set_again(&self) {
        self.engine().update_preferences(&BTreeMap::new()).unwrap();
    }

    async fn given_the_engine_was_closed(&self) {
        self.when_the_engine_is_closed().await;
    }

    async fn given_the_engine_was_restarted(&mut self) {
        self.when_the_engine_is_restarted().await;
    }

    fn given_a_server_met_of_two_servers(&mut self) {
        let named = vec![
            Tag::String { name: TagName::id(server_tag::SERVERNAME), value: "One".to_owned() },
            Tag::Int { name: TagName::id(server_tag::PREFERENCE), value: 1 },
        ];
        self.write_met(
            "other-server.met",
            &[
                Server { ip: u32::from_be_bytes([81, 2, 3, 4]), port: 4661, tags: named },
                Server { ip: u32::from_be_bytes([81, 2, 3, 5]), port: 4242, tags: Vec::new() },
            ],
        );
    }

    // localhost resolves without any network: acting on it by name must not resolve it.
    fn given_a_server_met_of_servers_known_by_name(&mut self, host: &str, other_host: &str) {
        let known_by = |host: &str| Server {
            ip: 0,
            port: 4661,
            tags: vec![Tag::String { name: TagName::id(server_tag::DYNIP), value: host.to_owned() }],
        };
        self.write_met("named-server.met", &[known_by(host), known_by(other_host)]);
    }

    async fn given_the_servers_of_the_file_were_added(&mut self, count: usize) {
        self.when_the_servers_of_the_file_are_added().await;
        assert_eq!(self.added, count);
    }

    async fn given_the_server_was_removed(&mut self, address: &str) {
        self.when_the_server_is_removed(address).await;
        assert!(self.removed);
    }

    async fn given_the_server_addresses_are(&self, addresses: &[&str]) {
        self.then_the_server_addresses_are(addresses).await;
    }

    async fn given_the_server_is_made_static(&self, address: &str) {
        assert!(within(self.engine().set_static_server(Address::parse(address).unwrap(), true)).await);
    }

    async fn given_the_server_priority_is_set(&self, address: &str, priority: Priority) {
        assert!(within(self.engine().set_server_priority(Address::parse(address).unwrap(), priority)).await);
    }

    async fn given_the_schedule_holds(&self, rule: Rule) {
        within(self.engine().update_schedule(Schedule { rules: vec![rule] })).await.unwrap();
    }

    fn given_the_statistics(&self, content: &str) {
        fs::write(self.dirs.config.join("statistics.properties"), content).unwrap();
    }

    async fn given_no_server_list_address_is_known(&self) {
        assert_eq!(within(self.engine().server_list_addresses()).await, Vec::<String>::new());
    }

    async fn given_the_server_list_addresses(&self, addresses: &[&str]) {
        let addresses = addresses.iter().map(|address| (*address).to_owned()).collect();
        within(self.engine().set_server_list_addresses(addresses)).await.unwrap();
    }

    async fn given_a_second_engine_was_refused(&mut self) {
        self.when_a_second_engine_starts_on_the_same_configuration().await;
        self.then_the_second_engine_is_refused_as("already running");
    }

    async fn when_the_engine_starts(&mut self) {
        self.engine = Some(within(Engine::start(self.dirs.clone())).await.unwrap());
    }

    async fn when_the_engine_is_closed(&self) {
        within(self.engine().stop()).await;
    }

    async fn when_the_engine_is_restarted(&mut self) {
        self.when_the_engine_is_closed().await;
        self.when_the_engine_starts().await;
    }

    fn when_the_nick_is_changed_to(&mut self, nick: &str) {
        self.updated = Some(self.engine().update_preferences(&changes("user.nick", nick)));
    }

    fn when_the_tcp_port_is_changed_to(&mut self, port: u16) {
        self.updated = Some(self.engine().update_preferences(&changes("net.tcpPort", &port.to_string())));
    }

    async fn when_the_servers_of_the_file_are_added(&mut self) {
        self.added = within(self.engine().add_servers(ListSource::File(self.met.clone()))).await.unwrap();
    }

    async fn when_the_server_is_removed(&mut self, address: &str) {
        self.removed = within(self.engine().remove_server(Address::parse(address).unwrap())).await;
    }

    async fn when_the_notifications_are_tested(&mut self) {
        self.tested = Some(within(self.engine().test_notifications()).await);
    }

    async fn when_the_engine_status_is_asked(&mut self) {
        self.status = within(self.engine().status()).await;
    }

    async fn when_a_second_engine_starts_on_the_same_configuration(&mut self) {
        let second = within(Engine::start(self.dirs.clone())).await;
        if let Some(Ok(earlier)) = self.second.replace(second) {
            earlier.stop().await;
        }
    }

    fn then_no_channel_was_tested(&self) {
        assert_eq!(self.tested, Some(Vec::new()));
    }

    fn then_the_engine_runs(&self) {
        assert!(self.engine().is_running());
    }

    fn then_the_engine_does_not_run(&self) {
        assert!(!self.engine().is_running());
    }

    fn then_the_log_folder_exists(&self) {
        assert!(self.engine().directories().logs.is_dir());
    }

    fn then_the_preferences_are_saved(&self) {
        assert!(self.engine().directories().config.join(preferences_codec::FILE_NAME).exists());
    }

    fn then_the_preferences_are_the_defaults(&self) {
        assert_eq!(self.engine().preferences(), Preferences::default());
    }

    async fn then_the_setup_is_pending(&self, pending: bool) {
        assert_eq!(within(self.engine().setup_pending()).await, pending);
    }

    // The events end once the engine stopped and is let go: the stop is the last one sent.
    async fn then_the_events_end(&mut self) {
        let mut subscription = self.subscription.take().expect("someone listens");
        self.engine.take();
        loop {
            match within(subscription.live.recv()).await {
                Ok(event) => self.events.push(event),
                Err(RecvError::Closed) => return,
                Err(RecvError::Lagged(missed)) => panic!("{missed} events missed"),
            }
        }
    }

    fn then_the_changes_of_the_preferences_published_are(&self, count: usize) {
        let published = self.events.iter().filter(|event| **event == EngineEvent::PreferencesChanged).count();
        assert_eq!(published, count);
    }

    fn then_the_last_event_is_the_stop(&self) {
        assert_eq!(self.events.last(), Some(&EngineEvent::Stopping));
    }

    fn then_the_nick_is(&self, nick: &str) {
        assert_eq!(self.engine().preferences().nick, nick);
    }

    fn then_the_change_is_refused(&self) {
        assert!(matches!(self.updated, Some(Err(_))), "{:?}", self.updated);
    }

    fn then_the_second_engine_is_refused_as(&self, reason: &str) {
        match &self.second {
            Some(Err(e)) => assert!(e.to_string().contains(reason), "{e}"),
            other => panic!("not refused: {:?}", other.as_ref().map(Result::is_ok)),
        }
    }

    fn then_no_exception_occurred(&self) {
        assert!(matches!(self.second, Some(Ok(_))), "{:?}", self.second.as_ref().map(|second| second.as_ref().err()));
    }

    fn then_the_servers_added_are(&self, count: usize) {
        assert_eq!(self.added, count);
    }

    async fn then_the_server_names_are(&self, names: &[&str]) {
        let servers = within(self.engine().servers()).await;
        assert_eq!(servers.iter().map(|server| server.name.as_str()).collect::<Vec<_>>(), names);
    }

    async fn then_the_server_priorities_are(&self, priorities: &[Priority]) {
        let servers = within(self.engine().servers()).await;
        assert_eq!(servers.iter().map(|server| server.priority).collect::<Vec<_>>(), priorities);
    }

    async fn then_the_server_addresses_are(&self, addresses: &[&str]) {
        let servers = within(self.engine().servers()).await;
        assert_eq!(servers.iter().map(|server| server.address.as_str()).collect::<Vec<_>>(), addresses);
    }

    fn then_the_server_is_removed(&self) {
        assert!(self.removed);
    }

    fn then_nothing_is_removed(&self) {
        assert!(!self.removed);
    }

    async fn then_the_server_count_is(&self, count: usize) {
        assert_eq!(within(self.engine().servers()).await.len(), count);
    }

    fn then_the_status_has_no_server_no_id_no_download_and_no_traffic(&self) {
        let status = self.status.as_ref().expect("a status");
        assert_eq!(status.server, None);
        assert_eq!(status.client_id, 0);
        assert_eq!(status.downloads, 0);
        assert_eq!(status.download_bytes_per_second, 0);
    }

    async fn then_there_are_no_uploads(&self) {
        assert!(within(self.engine().uploads()).await.is_empty());
    }

    async fn then_the_effective_nick_is(&self, nick: &str) {
        assert_eq!(within(self.engine().effective_preferences()).await.nick, nick);
    }

    async fn then_the_effective_upload_limit_is(&self, kibps: u32) {
        assert_eq!(within(self.engine().effective_preferences()).await.max_upload_kibps, kibps);
    }

    async fn then_the_sessions_are(&self, count: u64) {
        assert_eq!(self.current_status().await.sessions, count);
    }

    async fn then_the_total_downloaded_is(&self, bytes: u64) {
        assert_eq!(self.current_status().await.total_downloaded, bytes);
    }

    async fn then_the_total_uploaded_is(&self, bytes: u64) {
        assert_eq!(self.current_status().await.total_uploaded, bytes);
    }

    async fn then_the_total_time_is_that_of_the_session(&self) {
        let status = self.current_status().await;
        assert_eq!(status.total_seconds, status.session_seconds);
    }

    fn then_the_configuration_has_the_file(&self, name: &str) {
        assert!(self.dirs.config.join(name).exists());
    }

    async fn then_the_server_list_addresses_are(&self, addresses: &[&str]) {
        assert_eq!(within(self.engine().server_list_addresses()).await, addresses);
    }

    fn write_met(&mut self, name: &str, servers: &[Server]) {
        self.met = self.root.path().join(name);
        fs::write(&self.met, server_met::write(servers).unwrap()).unwrap();
    }

    async fn current_status(&self) -> EngineStatus {
        within(self.engine().status()).await.expect("a status")
    }

    fn engine(&self) -> Engine {
        self.engine.clone().expect("a running engine")
    }
}

impl Drop for Engines {
    fn drop(&mut self) {
        for engine in self.engine.take().into_iter().chain(self.second.take().and_then(Result::ok)) {
            tokio::spawn(async move { engine.stop().await });
        }
    }
}

// Holds all week with an upload limit of 7 KiB/s.
fn always() -> Rule {
    Rule { days: DayOfWeek::VARIANTS.iter().copied().collect(), upload_kibps: Some(7), ..Rule::new("always") }
}

fn changes(key: &str, value: &str) -> BTreeMap<String, String> {
    BTreeMap::from([(key.to_owned(), value.to_owned())])
}

async fn within<T>(work: impl Future<Output = T>) -> T {
    tokio::time::timeout(WAIT, work).await.expect("answered in time")
}
