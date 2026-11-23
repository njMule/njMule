// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Keeps the computer from sleeping while files are transferred, when the preferences ask for it, through a small
//! system helper that runs only as long as it is needed and ends with njMule anyway: the Windows launcher of the
//! desktop app (`--keep-awake`), `caffeinate` on macOS, `systemd-inhibit` on Linux.

use std::io;
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

/// How long the computer stays awake after the last transfer, so that a pause between two does not let it go.
pub const LINGER: Duration = Duration::from_secs(2 * 60);

/// A running keep-awake helper process.
pub trait Helper: Send {
    fn is_alive(&mut self) -> bool;
    fn stop(&mut self);
}

impl Helper for Child {
    fn is_alive(&mut self) -> bool {
        matches!(self.try_wait(), Ok(None))
    }

    fn stop(&mut self) {
        let _ = self.kill();
        let _ = self.wait();
    }
}

/// Starts the helper command.
pub type Starter = Box<dyn FnMut(&[String]) -> io::Result<Box<dyn Helper>> + Send>;

pub struct KeepAwake {
    command: Option<Vec<String>>,
    starter: Starter,
    helper: Option<Box<dyn Helper>>,
    last_transfer: Option<Instant>,
    failed: bool,
}

impl KeepAwake {
    pub fn new(command: Option<Vec<String>>, starter: Starter) -> Self {
        Self { command, starter, helper: None, last_transfer: None, failed: false }
    }

    pub fn for_this_system() -> Self {
        let program = std::env::current_exe().ok();
        let os = if cfg!(target_os = "windows") {
            "Windows"
        } else if cfg!(target_os = "macos") {
            "Mac OS X"
        } else {
            "Linux"
        };
        let command = Self::command(os, u64::from(std::process::id()), program.as_deref(), |p| p.is_file());
        Self::new(
            command,
            Box::new(|args| {
                let (program, rest) = args.split_first().ok_or_else(|| io::Error::other("empty keep-awake command"))?;
                let child = Command::new(program).args(rest).stdout(Stdio::null()).stderr(Stdio::null()).spawn()?;
                Ok(Box::new(child))
            }),
        )
    }

    /// The program that keeps the computer awake for the njMule running as `pid`; none where there is none, as on
    /// Windows without the launcher beside `program` (a development build).
    pub fn command(
        os_name: &str,
        pid: u64,
        program: Option<&Path>,
        is_file: impl Fn(&Path) -> bool,
    ) -> Option<Vec<String>> {
        let os = os_name.to_ascii_lowercase();
        let id = pid.to_string();
        if os.starts_with("windows") {
            let launcher = program?.with_file_name("njmule-gui.exe");
            return is_file(&launcher)
                .then(|| vec![launcher.to_string_lossy().into_owned(), "--keep-awake".to_owned(), id]);
        }
        if os.starts_with("mac") {
            return Some(vec!["caffeinate".to_owned(), "-i".to_owned(), "-w".to_owned(), id]);
        }
        // tail ends with njMule, and the lock with it.
        Some(vec![
            "systemd-inhibit".to_owned(),
            "--what=sleep:idle".to_owned(),
            "--who=njMule".to_owned(),
            "--why=Transfers in progress".to_owned(),
            "--mode=block".to_owned(),
            "tail".to_owned(),
            format!("--pid={id}"),
            "-f".to_owned(),
            "/dev/null".to_owned(),
        ])
    }

    /// Holds the computer awake while `wanted` and files are transferred, and lets it go a while after.
    pub fn update(&mut self, wanted: bool, transferring: bool, now: Instant) {
        if transferring {
            self.last_transfer = Some(now);
        }
        let keep = wanted
            && self.command.is_some()
            && !self.failed
            && self.last_transfer.is_some_and(|at| now.saturating_duration_since(at) < LINGER);
        if let Some(helper) = self.helper.as_mut()
            && !helper.is_alive()
        {
            // Ended by itself: the system has no such program, or refuses it; not started again.
            if let Some(program) = self.command.as_ref().and_then(|c| c.first()) {
                tracing::info!("Cannot keep the computer awake: {program} ended");
            }
            self.helper = None;
            self.failed = true;
            return;
        }
        if keep && self.helper.is_none() {
            if let Some(args) = &self.command {
                match (self.starter)(args) {
                    Ok(helper) => {
                        self.helper = Some(helper);
                        tracing::debug!("Keeping the computer awake while files are transferred");
                    }
                    Err(e) => {
                        tracing::info!("Cannot keep the computer awake: {e}");
                        self.failed = true;
                    }
                }
            }
        } else if !keep && self.helper.is_some() {
            self.close();
        }
    }

    pub fn awake(&mut self) -> bool {
        self.helper.as_mut().is_some_and(|h| h.is_alive())
    }

    pub fn close(&mut self) {
        if let Some(mut helper) = self.helper.take() {
            helper.stop();
        }
    }
}

impl Drop for KeepAwake {
    fn drop(&mut self) {
        self.close();
    }
}
