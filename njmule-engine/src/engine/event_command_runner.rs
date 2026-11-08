// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Runs the user's commands on engine events. The program is started directly, never through a shell, on a task of
//! its own: the engine never waits for it, and only a few run at once.

use std::io;
use std::path::Path;
use std::process::Stdio;
use std::sync::Arc;

use futures_util::future::BoxFuture;
use tokio::sync::Semaphore;

use crate::api::engine_event::EngineEvent;
use crate::config::event_commands::{self, Event, EventCommands};

/// Commands running at once at most; the events beyond them run nothing.
pub const MAX_RUNNING: usize = 4;

const PLACEHOLDERS: [&str; 6] = ["NAME", "FILE", "HASH", "SIZE", "SENDER", "MESSAGE"];

/// Runs a command, the program first, until it ends.
pub type Launcher = Arc<dyn Fn(Vec<String>) -> BoxFuture<'static, io::Result<()>> + Send + Sync>;
/// Runs a task that may wait for a command.
pub type Executor = Arc<dyn Fn(BoxFuture<'static, ()>) + Send + Sync>;

pub struct EventCommandRunner {
    commands: Box<dyn Fn() -> EventCommands + Send + Sync>,
    launcher: Launcher,
    executor: Executor,
    windows: bool,
    running: Arc<Semaphore>,
}

impl EventCommandRunner {
    /// `commands` are those of the current preferences; `windows` tells whether the system takes a command line as one
    /// text, as Windows does.
    pub fn new(
        commands: impl Fn() -> EventCommands + Send + Sync + 'static,
        launcher: Launcher,
        executor: Executor,
        windows: bool,
    ) -> Self {
        Self {
            commands: Box::new(commands),
            launcher,
            executor,
            windows,
            running: Arc::new(Semaphore::new(MAX_RUNNING)),
        }
    }

    /// A runner that starts the programs on tasks of the runtime.
    pub fn create(commands: impl Fn() -> EventCommands + Send + Sync + 'static) -> Self {
        Self::new(
            commands,
            Arc::new(|command| Box::pin(launch(command))),
            Arc::new(|task| {
                tokio::spawn(task);
            }),
            cfg!(windows),
        )
    }

    /// Runs the command of `event`, if it has one and it is on.
    pub fn handle(&self, event: &EngineEvent) {
        match event {
            EngineEvent::DownloadCompleted { download, file } => self.run(
                Event::DownloadCompleted,
                &[
                    ("NAME", download.name.clone()),
                    ("FILE", absolute(file)),
                    ("HASH", data_encoding::HEXUPPER.encode(&download.hash)),
                    ("SIZE", download.size.to_string()),
                ],
            ),
            EngineEvent::DownloadFailed { download, reason } => self.run(
                Event::DownloadFailed,
                &[
                    ("NAME", download.name.clone()),
                    ("HASH", data_encoding::HEXUPPER.encode(&download.hash)),
                    ("SIZE", download.size.to_string()),
                    ("MESSAGE", reason.clone()),
                ],
            ),
            EngineEvent::MessageReceived { address, nick, text, .. } => self.run(
                Event::ChatMessage,
                &[("SENDER", nick.clone().unwrap_or_else(|| address.clone())), ("MESSAGE", text.clone())],
            ),
            EngineEvent::DiskSpace { directory, enough: false } => {
                self.run(Event::DiskSpaceShort, &[("FILE", absolute(directory))]);
            }
            // No command runs on the other events.
            _ => {}
        }
    }

    fn run(&self, event: Event, values: &[(&str, String)]) {
        let commands = (self.commands)();
        let command = commands.get(event);
        if !command.enabled() || command.command_line().is_empty() {
            return;
        }
        let arguments: Vec<String> = match event_commands::arguments(command.command_line()) {
            Ok(arguments) => arguments.iter().map(|argument| self.fill(argument, values)).collect(),
            Err(e) => {
                tracing::warn!("The command for {} cannot run: {e}", event.id());
                return;
            }
        };
        let Some(program) = arguments.first().filter(|program| !program.is_empty()).cloned() else {
            return;
        };
        let Ok(permit) = Arc::clone(&self.running).try_acquire_owned() else {
            tracing::warn!("{MAX_RUNNING} event commands are still running: the one for {} does not run", event.id());
            return;
        };
        let launched = (self.launcher)(arguments);
        (self.executor)(Box::pin(async move {
            tracing::info!("Running {program} for {}", event.id());
            if let Err(e) = launched.await {
                tracing::warn!("Cannot run {program} for {}: {e}", event.id());
            }
            drop(permit);
        }));
    }

    // The values come from the network (names, messages): they only ever fill in text, never become a format.
    fn fill(&self, argument: &str, values: &[(&str, String)]) -> String {
        let mut pieces = argument.split('%');
        let mut filled = pieces.next().unwrap_or_default().to_owned();
        for piece in pieces {
            let placeholder = PLACEHOLDERS.iter().find_map(|name| piece.strip_prefix(name).map(|rest| (*name, rest)));
            match placeholder {
                Some((name, rest)) => {
                    let value = values.iter().find(|(named, _)| *named == name).map_or("", |(_, value)| value);
                    filled.push_str(&self.safe(value));
                    filled.push_str(rest);
                }
                None => {
                    filled.push('%');
                    filled.push_str(piece);
                }
            }
        }
        filled
    }

    // On Windows the arguments travel as one command line, where a quote in a name or a message would split it.
    fn safe(&self, value: &str) -> String {
        if self.windows { value.replace('"', "'") } else { value.to_owned() }
    }
}

/// Starts `command` and waits for its end, with nothing to read on its input and its output discarded.
pub async fn launch(command: Vec<String>) -> io::Result<()> {
    let Some((program, arguments)) = command.split_first() else {
        return Ok(());
    };
    let status = tokio::process::Command::new(program)
        .args(arguments)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await?;
    if !status.success() {
        tracing::info!("{program} ended with {status}");
    }
    Ok(())
}

fn absolute(path: &Path) -> String {
    std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf()).display().to_string()
}
