// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Starts a program that goes on by itself, the installer or njMule again, and tells of processes that are not ours.

use std::fs::{self, OpenOptions};
use std::io;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};

use sysinfo::{Pid, ProcessRefreshKind, ProcessesToUpdate, System};

use super::update_files;

/// The variable the desktop window sets for its engine; a program started from it must not inherit it.
pub const DESKTOP_VARIABLE: &str = "NJMULE_DESKTOP";

/// A program started.
pub trait Running: Send {
    fn is_alive(&mut self) -> bool;

    /// How it ended; none while it runs.
    fn exit_code(&mut self) -> Option<i32>;

    fn destroy(&mut self);
}

pub trait Launcher: Send + Sync {
    /// Starts `command`, the program first, its output appended to `log` or dropped.
    fn start(&self, command: &[String], log: Option<&Path>) -> io::Result<Box<dyn Running>>;
}

/// The real processes of this computer.
#[derive(Debug, Clone, Copy, Default)]
pub struct SystemLauncher;

impl Launcher for SystemLauncher {
    fn start(&self, command: &[String], log: Option<&Path>) -> io::Result<Box<dyn Running>> {
        let Some((program, arguments)) = command.split_first() else {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "no program to start"));
        };
        let mut builder = Command::new(program);
        builder.args(arguments).env_remove(DESKTOP_VARIABLE).stdin(Stdio::null());
        match log {
            Some(log) => {
                fs::create_dir_all(update_files::parent(log)?)?;
                let out = OpenOptions::new().create(true).append(true).open(log)?;
                builder.stdout(out.try_clone()?).stderr(out);
            }
            None => {
                builder.stdout(Stdio::null()).stderr(Stdio::null());
            }
        }
        Ok(Box::new(builder.spawn()?))
    }
}

impl Running for Child {
    fn is_alive(&mut self) -> bool {
        matches!(self.try_wait(), Ok(None))
    }

    fn exit_code(&mut self) -> Option<i32> {
        self.try_wait().ok().flatten().map(|status| status.code().unwrap_or(-1))
    }

    fn destroy(&mut self) {
        let _ = self.kill();
        let _ = self.wait();
    }
}

/// Whether the process `pid` runs.
pub fn is_running(pid: u32) -> bool {
    let Ok(mut system) = System::new() else {
        return false;
    };
    let pid = Pid::from_u32(pid);
    system.refresh_processes_specifics(ProcessesToUpdate::Some(&[pid]), true, ProcessRefreshKind::nothing());
    system.process(pid).is_some()
}

/// The process that started this one, and its executable when known.
pub fn parent() -> Option<(u32, Option<PathBuf>)> {
    let mut system = System::new().ok()?;
    let own = Pid::from_u32(std::process::id());
    system.refresh_processes_specifics(ProcessesToUpdate::Some(&[own]), true, ProcessRefreshKind::nothing());
    let parent = system.process(own)?.parent()?;
    system.refresh_processes_specifics(
        ProcessesToUpdate::Some(&[parent]),
        true,
        ProcessRefreshKind::nothing().with_exe(sysinfo::UpdateKind::Always),
    );
    let executable = system.process(parent).and_then(|process| process.exe().map(Path::to_path_buf));
    Some((parent.as_u32(), executable))
}
