// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's EventCommandRunnerTest, under its names, and the start of a program: the user's commands run on
//! the engine's events.

#![allow(clippy::unwrap_used)]

use std::collections::VecDeque;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use futures_util::future::BoxFuture;
use njmule_engine::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus};
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::config::event_commands::{Command, Event, EventCommands};
use njmule_engine::engine::event_command_runner::{self, EventCommandRunner, MAX_RUNNING};
use njmule_engine::files::part_file::PartMap;

const HASH: [u8; 16] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef];
const HEX: &str = "0123456789ABCDEF0123456789ABCDEF";

#[tokio::test]
async fn should_fill_the_download_inside_its_arguments_when_a_download_completes() {
    let mut test = Runner::new();
    test.given_the_command(Event::DownloadCompleted, r#"notify --file=%FILE "%NAME (%SIZE bytes)" %HASH"#);
    test.given_the_runner();
    test.given_the_engine_said(&test.completed("My Holiday.avi"));

    test.when_the_waiting_commands_run().await;

    let file = format!("--file={}", test.absolute("My Holiday.avi"));
    test.then_the_command_launched_is(&["notify", &file, "My Holiday.avi (1234 bytes)", HEX]);
}

#[tokio::test]
async fn should_run_nothing_for_a_command_that_is_off() {
    let mut test = Runner::new();
    test.given_the_command(Event::DownloadCompleted, "notify %NAME");
    test.given_the_command_is_turned_off(Event::DownloadCompleted);
    test.given_the_runner();
    test.given_the_engine_said(&test.completed("a.iso"));

    test.when_the_waiting_commands_run().await;

    test.then_nothing_was_launched();
}

#[tokio::test]
async fn should_tell_the_reason_when_a_download_fails() {
    let mut test = Runner::new();
    test.given_the_command(Event::DownloadFailed, "alert %NAME %MESSAGE %FILE");
    test.given_the_runner();
    test.given_the_engine_said(&EngineEvent::DownloadFailed {
        download: download("b.iso"),
        reason: "disk full".into(),
    });

    test.when_the_waiting_commands_run().await;

    test.then_the_command_launched_is(&["alert", "b.iso", "disk full", ""]);
}

#[tokio::test]
async fn should_name_the_sender_or_its_address_for_a_chat_message() {
    let mut test = Runner::new();
    test.given_the_command(Event::ChatMessage, "say %SENDER: %MESSAGE");
    test.given_the_runner();
    test.given_the_engine_said(&message(Some("ann"), "hello there"));
    test.given_the_engine_said(&message(None, "hi"));

    test.when_the_waiting_commands_run().await;

    test.then_the_commands_launched_are(&[&["say", "ann:", "hello there"], &["say", "192.0.2.7:4662:", "hi"]]);
}

#[tokio::test]
async fn should_keep_a_quote_in_a_message_from_splitting_the_windows_command_line() {
    let mut test = Runner::new();
    test.given_the_command(Event::ChatMessage, "say %MESSAGE");
    test.given_the_system_is_windows();
    test.given_the_runner();
    test.given_the_engine_said(&message(Some("eve"), r#"x" --delete-all "y"#));

    test.when_the_waiting_commands_run().await;

    test.then_the_command_launched_is(&["say", "x' --delete-all 'y"]);
}

#[tokio::test]
async fn should_run_the_disk_command_only_when_space_runs_short() {
    let mut test = Runner::new();
    test.given_the_command(Event::DiskSpaceShort, "warn %FILE");
    test.given_the_runner();
    test.given_the_engine_said(&EngineEvent::DiskSpace { directory: test.dir.path().to_path_buf(), enough: true });
    test.given_the_engine_said(&EngineEvent::DiskSpace { directory: test.dir.path().to_path_buf(), enough: false });

    test.when_the_waiting_commands_run().await;

    test.then_the_command_launched_is(&["warn", &absolute(test.dir.path())]);
}

#[tokio::test]
async fn should_run_nothing_for_a_command_with_a_quote_left_open() {
    let mut test = Runner::new();
    test.given_the_command(Event::DownloadCompleted, r#"notify "%NAME"#);
    test.given_the_runner();
    test.given_the_engine_said(&test.completed("c.iso"));

    test.when_the_waiting_commands_run().await;

    test.then_nothing_was_launched();
}

#[test]
fn should_run_only_a_few_commands_at_once() {
    let mut test = Runner::new();
    test.given_the_command(Event::DownloadCompleted, "notify %NAME");
    test.given_the_runner();

    test.when_downloads_complete(MAX_RUNNING + 2);

    test.then_commands_are_waiting_to_run(MAX_RUNNING);
}

#[tokio::test]
async fn should_run_again_once_a_command_has_ended() {
    let mut test = Runner::new();
    test.given_the_command(Event::DownloadCompleted, "notify %NAME");
    test.given_the_runner();
    test.given_downloads_completed(MAX_RUNNING);
    test.given_the_waiting_commands_ran().await;

    test.when_downloads_complete(1);

    test.then_commands_are_waiting_to_run(1);
}

#[tokio::test]
async fn should_free_the_slot_of_a_command_that_cannot_start() {
    let mut test = Runner::new();
    test.given_the_command(Event::DownloadCompleted, "missing-program %NAME");
    test.given_the_launcher_fails();
    test.given_the_runner();
    test.given_downloads_completed(MAX_RUNNING);
    test.given_the_waiting_commands_ran().await;

    test.when_downloads_complete(MAX_RUNNING);

    test.then_commands_are_waiting_to_run(MAX_RUNNING);
}

#[tokio::test]
async fn should_fail_to_launch_a_program_that_does_not_exist() {
    let mut test = Runner::new();

    test.when_launched(&["njmule-no-such-program"]).await;

    test.then_the_launch_failed();
}

type Waiting = Arc<Mutex<VecDeque<BoxFuture<'static, ()>>>>;

struct Runner {
    dir: tempfile::TempDir,
    commands: EventCommands,
    windows: bool,
    launcher_fails: bool,
    // The commands handed to the executor, not run until a step runs them.
    waiting: Waiting,
    launched: Arc<Mutex<Vec<Vec<String>>>>,
    runner: Option<EventCommandRunner>,
    launch: Option<io::Result<()>>,
}

impl Runner {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            commands: EventCommands::default(),
            windows: false,
            launcher_fails: false,
            waiting: Waiting::default(),
            launched: Arc::default(),
            runner: None,
            launch: None,
        }
    }

    fn given_the_command(&mut self, event: Event, command_line: &str) {
        self.commands = self.commands.with(event, Command::new(true, command_line).unwrap());
    }

    fn given_the_command_is_turned_off(&mut self, event: Event) {
        let line = self.commands.get(event).command_line().to_owned();
        self.commands = self.commands.with(event, Command::new(false, &line).unwrap());
    }

    fn given_the_system_is_windows(&mut self) {
        self.windows = true;
    }

    fn given_the_launcher_fails(&mut self) {
        self.launcher_fails = true;
    }

    fn given_the_runner(&mut self) {
        let commands = self.commands.clone();
        let fails = self.launcher_fails;
        let launched = Arc::clone(&self.launched);
        let waiting = Arc::clone(&self.waiting);
        self.runner = Some(EventCommandRunner::new(
            move || commands.clone(),
            Arc::new(move |command| {
                let launched = Arc::clone(&launched);
                Box::pin(async move {
                    if fails {
                        return Err(io::Error::new(io::ErrorKind::NotFound, "no such program"));
                    }
                    launched.lock().unwrap().push(command);
                    Ok(())
                })
            }),
            Arc::new(move |task| waiting.lock().unwrap().push_back(task)),
            self.windows,
        ));
    }

    fn given_downloads_completed(&self, count: usize) {
        self.when_downloads_complete(count);
    }

    async fn given_the_waiting_commands_ran(&self) {
        self.when_the_waiting_commands_run().await;
    }

    fn given_the_engine_said(&self, event: &EngineEvent) {
        self.runner.as_ref().unwrap().handle(event);
    }

    fn when_downloads_complete(&self, count: usize) {
        for i in 0..count {
            self.runner.as_ref().unwrap().handle(&self.completed(&format!("file{i}")));
        }
    }

    async fn when_the_waiting_commands_run(&self) {
        loop {
            let next = self.waiting.lock().unwrap().pop_front();
            match next {
                Some(task) => task.await,
                None => return,
            }
        }
    }

    async fn when_launched(&mut self, command: &[&str]) {
        self.launch = Some(event_command_runner::launch(command.iter().map(ToString::to_string).collect()).await);
    }

    fn then_the_command_launched_is(&self, expected: &[&str]) {
        self.then_the_commands_launched_are(&[expected]);
    }

    fn then_the_commands_launched_are(&self, expected: &[&[&str]]) {
        assert_eq!(*self.launched.lock().unwrap(), expected);
    }

    fn then_nothing_was_launched(&self) {
        assert!(self.launched.lock().unwrap().is_empty());
    }

    fn then_commands_are_waiting_to_run(&self, count: usize) {
        assert_eq!(self.waiting.lock().unwrap().len(), count);
    }

    fn then_the_launch_failed(&self) {
        assert!(matches!(self.launch, Some(Err(_))), "{:?}", self.launch);
    }

    fn completed(&self, name: &str) -> EngineEvent {
        EngineEvent::DownloadCompleted { download: download(name), file: self.dir.path().join(name) }
    }

    fn absolute(&self, name: &str) -> String {
        absolute(&self.dir.path().join(name))
    }
}

fn absolute(path: &Path) -> String {
    std::path::absolute(path).unwrap_or_else(|_| PathBuf::from(path)).display().to_string()
}

fn download(name: &str) -> DownloadInfo {
    DownloadInfo {
        hash: HASH,
        name: name.to_owned(),
        size: 1234,
        completed: 1234,
        status: DownloadStatus::Complete,
        priority: DownloadPriority::Normal,
        category: 0,
        sources: 0,
        transferring: 0,
        bytes_per_second: 0,
        parts: PartMap { size: 0, missing: Vec::new(), requested: Vec::new(), sources: Vec::new() },
        preview_first: false,
        auto_swap: false,
    }
}

fn message(nick: Option<&str>, text: &str) -> EngineEvent {
    EngineEvent::MessageReceived {
        address: "192.0.2.7:4662".to_owned(),
        user_hash: HASH,
        nick: nick.map(str::to_owned),
        text: text.to_owned(),
    }
}
