// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Twin of Java's `KeepAwakeTest`: inhibiting system sleep while files transfer and lingering afterwards.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::arithmetic_side_effects)]

use std::io;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use njmule_engine::engine::keep_awake::{Helper, KeepAwake, LINGER};

struct FakeHelper {
    alive: Arc<AtomicBool>,
}

impl Helper for FakeHelper {
    fn is_alive(&mut self) -> bool {
        self.alive.load(Ordering::Relaxed)
    }

    fn stop(&mut self) {
        self.alive.store(false, Ordering::Relaxed);
    }
}

struct State {
    now: Instant,
    started: Arc<AtomicUsize>,
    refused: Arc<AtomicBool>,
    launcher: bool,
    keep_awake: Option<KeepAwake>,
    command: Option<Vec<String>>,
}

impl State {
    fn new() -> Self {
        Self {
            now: Instant::now(),
            started: Arc::new(AtomicUsize::new(0)),
            refused: Arc::new(AtomicBool::new(false)),
            launcher: true,
            keep_awake: None,
            command: None,
        }
    }

    fn given_the_helper(&mut self) {
        let cmd = vec!["caffeinate".to_owned(), "-i".to_owned(), "-w".to_owned(), "42".to_owned()];
        let started = Arc::clone(&self.started);
        let refused = Arc::clone(&self.refused);
        self.keep_awake = Some(KeepAwake::new(
            Some(cmd),
            Box::new(move |_| {
                if refused.load(Ordering::Relaxed) {
                    return Err(io::Error::other("no such program"));
                }
                started.fetch_add(1, Ordering::Relaxed);
                Ok(Box::new(FakeHelper { alive: Arc::new(AtomicBool::new(true)) }))
            }),
        ));
    }

    fn given_no_launcher(&mut self) {
        self.launcher = false;
    }

    fn given_the_system_refuses(&self) {
        self.refused.store(true, Ordering::Relaxed);
    }

    fn given_updated(&mut self, wanted: bool, transferring: bool, at: Instant) {
        self.keep_awake.as_mut().unwrap().update(wanted, transferring, at);
    }

    fn when_updated(&mut self, wanted: bool, transferring: bool, at: Instant) {
        self.keep_awake.as_mut().unwrap().update(wanted, transferring, at);
    }

    fn when_the_command_is_found(&mut self, os: &str, program: Option<&Path>) {
        let launcher = self.launcher;
        self.command = KeepAwake::command(os, 42, program, |_| launcher);
    }

    fn then_it_is_awake(&mut self, expected: bool) {
        assert_eq!(self.keep_awake.as_mut().unwrap().awake(), expected);
    }

    fn then_the_helpers_started_are(&self, count: usize) {
        assert_eq!(self.started.load(Ordering::Relaxed), count);
    }

    fn then_the_command_is(&self, expected: &[&str]) {
        let expected: Vec<String> = expected.iter().map(|s| (*s).to_owned()).collect();
        assert_eq!(self.command.as_ref(), Some(&expected));
    }

    fn then_there_is_no_command(&self) {
        assert_eq!(self.command, None);
    }

    fn then_the_command_starts_with(&self, prefix: &[&str]) {
        let cmd = self.command.as_ref().unwrap();
        let expected: Vec<String> = prefix.iter().map(|s| (*s).to_owned()).collect();
        assert!(cmd.starts_with(&expected), "expected prefix {expected:?}, got {cmd:?}");
    }
}

#[test]
fn should_keep_the_computer_awake_while_files_are_transferred() {
    let mut t = State::new();
    let now = t.now;
    t.given_the_helper();

    t.when_updated(true, true, now);

    t.then_it_is_awake(true);
    t.then_the_helpers_started_are(1);
}

#[test]
fn should_let_it_sleep_when_not_asked() {
    let mut t = State::new();
    let now = t.now;
    t.given_the_helper();

    t.when_updated(false, true, now);

    t.then_it_is_awake(false);
    t.then_the_helpers_started_are(0);
}

#[test]
fn should_keep_it_awake_through_a_short_pause() {
    let mut t = State::new();
    let now = t.now;
    t.given_the_helper();
    t.given_updated(true, true, now);

    t.when_updated(true, false, now + LINGER - Duration::from_secs(1));

    t.then_it_is_awake(true);
    t.then_the_helpers_started_are(1);
}

#[test]
fn should_let_it_sleep_a_while_after_the_last_transfer() {
    let mut t = State::new();
    let now = t.now;
    t.given_the_helper();
    t.given_updated(true, true, now);

    t.when_updated(true, false, now + LINGER);

    t.then_it_is_awake(false);
}

#[test]
fn should_not_try_again_when_the_system_refuses() {
    let mut t = State::new();
    let now = t.now;
    t.given_the_helper();
    t.given_the_system_refuses();
    t.given_updated(true, true, now);

    t.when_updated(true, true, now + Duration::from_secs(30));

    t.then_it_is_awake(false);
    t.then_the_helpers_started_are(0);
}

#[test]
fn should_use_the_launcher_on_windows() {
    let mut t = State::new();
    let exe: PathBuf = ["C:", "njMule", "njmule.exe"].iter().collect();
    let gui: PathBuf = ["C:", "njMule", "njmule-gui.exe"].iter().collect();

    t.when_the_command_is_found("Windows 11", Some(&exe));

    t.then_the_command_is(&[gui.to_str().unwrap(), "--keep-awake", "42"]);
}

#[test]
fn should_have_none_on_windows_without_the_launcher() {
    let mut t = State::new();
    let exe: PathBuf = ["C:", "tools", "other.exe"].iter().collect();
    t.given_no_launcher();

    t.when_the_command_is_found("Windows 11", Some(&exe));

    t.then_there_is_no_command();
}

#[test]
fn should_inhibit_sleep_through_systemd_on_linux() {
    let mut t = State::new();

    t.when_the_command_is_found("Linux", None);

    t.then_the_command_starts_with(&["systemd-inhibit", "--what=sleep:idle"]);
}
