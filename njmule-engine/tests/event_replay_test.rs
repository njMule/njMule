// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's EventReplayTest, under its names: what a front end that follows the events late is told first.

#![allow(clippy::arithmetic_side_effects)]

use std::time::SystemTime;

use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::log_line::{Level, LogLine};
use njmule_engine::api::server_info::ServerInfo;
use njmule_engine::engine::event_replay::{EventReplay, SERVER_MESSAGES};
use njmule_engine::server::server_list::Priority;

const RUST: &str = "85.17.116.222:6082";
const SUNRISE: &str = "176.123.5.89:4725";

#[test]
fn should_tell_the_server_connected_to_with_what_it_said_since_the_login() {
    let mut replay = Replay::default();
    replay.given_the_events(&[
        started(),
        EngineEvent::ServerConnecting(server(RUST)),
        EngineEvent::ServerConnecting(server(SUNRISE)),
        message(RUST, "welcome"),
        message(SUNRISE, "other"),
        connected(RUST),
        message(RUST, "news"),
    ]);

    replay.when_a_late_subscriber_comes();

    replay.then_it_is_told(&[started(), connected(RUST), message(RUST, "welcome"), message(RUST, "news")]);
}

#[test]
fn should_tell_the_latest_server_tried_while_connecting() {
    let mut replay = Replay::default();
    replay.given_the_events(&[
        EngineEvent::ServerConnecting(server(RUST)),
        EngineEvent::ServerConnecting(server(SUNRISE)),
    ]);

    replay.when_a_late_subscriber_comes();

    replay.then_it_is_told(&[EngineEvent::ServerConnecting(server(SUNRISE))]);
}

#[test]
fn should_tell_no_server_once_disconnected() {
    let mut replay = Replay::default();
    replay.given_the_events(&[
        started(),
        connected(RUST),
        message(RUST, "welcome"),
        EngineEvent::ServerDisconnected { server: Some(server(RUST)), reason: "lost".to_owned() },
    ]);

    replay.when_a_late_subscriber_comes();

    replay.then_it_is_told(&[started()]);
}

#[test]
fn should_tell_no_more_messages_of_the_server_than_the_limit() {
    let mut replay = Replay::default();
    replay.given_the_events(&[connected(RUST)]);
    replay.given_the_server_said_lines(SERVER_MESSAGES + 10);

    replay.when_a_late_subscriber_comes();

    replay.then_it_is_told_events(1 + SERVER_MESSAGES);
}

#[derive(Default)]
struct Replay {
    replay: EventReplay,
    missed: Vec<EngineEvent>,
}

impl Replay {
    fn given_the_events(&mut self, events: &[EngineEvent]) {
        for event in events {
            self.replay.record(event);
        }
    }

    fn given_the_server_said_lines(&mut self, count: usize) {
        for i in 0..count {
            self.replay.record(&message(RUST, &format!("line {i}")));
        }
    }

    fn when_a_late_subscriber_comes(&mut self) {
        self.missed = self.replay.missed();
    }

    fn then_it_is_told(&self, events: &[EngineEvent]) {
        assert_eq!(self.missed, events);
    }

    fn then_it_is_told_events(&self, count: usize) {
        assert_eq!(self.missed.len(), count);
    }
}

fn started() -> EngineEvent {
    EngineEvent::Log(LogLine { time: SystemTime::UNIX_EPOCH, level: Level::Info, message: "started".to_owned() })
}

fn connected(address: &str) -> EngineEvent {
    EngineEvent::ServerConnected { server: server(address), client_id: 4_220_008_785, low_id: false, obfuscated: true }
}

fn message(address: &str, text: &str) -> EngineEvent {
    EngineEvent::ServerMessage { server: server(address), text: text.to_owned() }
}

fn server(address: &str) -> ServerInfo {
    ServerInfo {
        address: address.to_owned(),
        name: address.to_owned(),
        description: String::new(),
        users: 0,
        files: 0,
        max_users: 0,
        failed: 0,
        priority: Priority::Normal,
        ping: 0,
        soft_files: 0,
        hard_files: 0,
        low_id_users: 0,
        version: String::new(),
        obfuscation: false,
        is_static: false,
        country: None,
    }
}
