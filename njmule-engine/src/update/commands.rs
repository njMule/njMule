// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Runs a program to its end: what njMule cannot do itself, such as mounting a disk image, or asks a new version.

use std::io::{self, Read};
use std::process::{Child, Command, Stdio};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub const MAX_OUTPUT: usize = 64 * 1024;

// How often a program is looked at while it runs.
const POLL: Duration = Duration::from_millis(20);

/// What a program said, standard output then error, and how it ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommandResult {
    pub exit_code: i32,
    pub output: String,
}

impl CommandResult {
    pub fn succeeded(&self) -> bool {
        self.exit_code == 0
    }
}

pub trait Commands: Send + Sync {
    /// Runs `command`, the program first; fails when it does not end within `timeout`.
    fn run(&self, command: &[String], timeout: Duration) -> io::Result<CommandResult>;

    /// As [`Commands::run`], failing also when the program ends with an error.
    fn require(&self, command: &[String], timeout: Duration) -> io::Result<CommandResult> {
        let result = self.run(command, timeout)?;
        if !result.succeeded() {
            return Err(io::Error::other(format!(
                "{} ended with code {}: {}",
                program(command),
                result.exit_code,
                result.output.trim()
            )));
        }
        Ok(result)
    }
}

/// The programs of this computer, their output kept up to [`MAX_OUTPUT`] bytes.
#[derive(Debug, Clone, Copy, Default)]
pub struct SystemCommands;

impl Commands for SystemCommands {
    fn run(&self, command: &[String], timeout: Duration) -> io::Result<CommandResult> {
        let Some((name, arguments)) = command.split_first() else {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "no program to run"));
        };
        let mut child = Command::new(name)
            .args(arguments)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()?;
        // Read beside: a program blocked on a full pipe would never end.
        let output = child.stdout.take().map(keep);
        let errors = child.stderr.take().map(keep);
        let status = wait(&mut child, timeout).inspect_err(|_| {
            let _ = child.kill();
            let _ = child.wait();
        })?;
        let Some(status) = status else {
            let _ = child.kill();
            let _ = child.wait();
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                format!("{name} did not end within {} s", timeout.as_secs()),
            ));
        };
        let mut said = Vec::new();
        for reader in [output, errors].into_iter().flatten() {
            said.extend(reader.join().unwrap_or_default());
        }
        said.truncate(MAX_OUTPUT);
        Ok(CommandResult {
            exit_code: status.code().unwrap_or(-1),
            output: String::from_utf8_lossy(&said).into_owned(),
        })
    }
}

fn wait(child: &mut Child, timeout: Duration) -> io::Result<Option<std::process::ExitStatus>> {
    let deadline = Instant::now().checked_add(timeout);
    loop {
        if let Some(status) = child.try_wait()? {
            return Ok(Some(status));
        }
        if deadline.is_some_and(|deadline| Instant::now() >= deadline) {
            return Ok(None);
        }
        thread::sleep(POLL);
    }
}

// What a stream says, the first MAX_OUTPUT bytes kept; read to its end so that the program never blocks on it.
fn keep(mut stream: impl Read + Send + 'static) -> JoinHandle<Vec<u8>> {
    thread::spawn(move || {
        let mut kept = Vec::new();
        let mut buffer = [0u8; 8192];
        while let Ok(n) = stream.read(&mut buffer) {
            let Some(read) = buffer.get(..n).filter(|read| !read.is_empty()) else {
                break;
            };
            let room = MAX_OUTPUT.saturating_sub(kept.len());
            kept.extend(read.iter().take(room));
        }
        kept
    })
}

fn program(command: &[String]) -> &str {
    command.first().map_or("", String::as_str)
}
