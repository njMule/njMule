// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Twin of Java's `org.njmule.app.CommandRunTest`: `link_association::run_command_with_timeout` on real child
//! processes, never a system tool.

#![allow(clippy::unwrap_used)]

use std::fs;
use std::io;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use njmule_engine::app::link_association::{self, CommandResult};
use sysinfo::{Pid, ProcessRefreshKind, ProcessesToUpdate, System};
use tempfile::TempDir;

#[test]
fn should_stop_a_command_that_outlives_its_timeout() {
    let mut test = Scenario::new();
    test.given_a_program_that_prints_then_sleeps(60);

    test.when_it_runs_with_a_timeout_of(Duration::from_secs(5));

    test.then_it_failed_as("did not finish");
    test.then_it_answered_within(Duration::from_secs(25));
    test.then_the_sleeper_is_soon_gone();
}

#[test]
fn should_answer_when_a_command_ends_but_what_it_started_keeps_its_output_open() {
    let mut test = Scenario::new();
    test.given_a_program_that_starts_a_sleeper_on_its_output_then_ends(60);

    test.when_it_runs_with_a_timeout_of(Duration::from_secs(30));

    test.then_it_ended_with(0, "started a sleeper");
    test.then_it_answered_within(Duration::from_secs(45));
}

struct Scenario {
    dir: TempDir,
    pid_file: Option<PathBuf>,
    command: Vec<String>,
    result: Option<CommandResult>,
    error: Option<io::Error>,
    took: Duration,
}

impl Scenario {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            pid_file: None,
            command: Vec::new(),
            result: None,
            error: None,
            took: Duration::ZERO,
        }
    }

    fn given_a_program_that_prints_then_sleeps(&mut self, seconds: u64) {
        let pid_file = self.dir.path().join("sleeper.pid");
        let path = pid_file.to_string_lossy().into_owned();
        #[cfg(windows)]
        {
            let script = format!(
                "$p = '{}.tmp'; $PID | Set-Content -LiteralPath $p; Move-Item -Force -LiteralPath $p -Destination '{}'; \
                 Write-Output sleeping; Start-Sleep -Seconds {seconds}",
                path.replace('\'', "''"),
                path.replace('\'', "''")
            );
            self.command = vec![
                "powershell.exe".to_owned(),
                "-NoProfile".to_owned(),
                "-NonInteractive".to_owned(),
                "-Command".to_owned(),
                script,
            ];
        }
        #[cfg(not(windows))]
        {
            self.command = vec![
                "sh".to_owned(),
                "-c".to_owned(),
                format!("echo $$ > \"$1.tmp\" && mv \"$1.tmp\" \"$1\" && echo sleeping && exec sleep {seconds}"),
                "_".to_owned(),
                path,
            ];
        }
        self.pid_file = Some(pid_file);
    }

    fn given_a_program_that_starts_a_sleeper_on_its_output_then_ends(&mut self, seconds: u64) {
        let pid_file = self.dir.path().join("sleeper.pid");
        let path = pid_file.to_string_lossy().into_owned();
        #[cfg(windows)]
        {
            // Inherit the parent's stdout pipe (`-NoNewWindow` without redirection) and exit right away.
            let script = format!(
                "$child = Start-Process -FilePath powershell.exe -ArgumentList '-NoProfile','-NonInteractive',\
                 '-Command','Start-Sleep -Seconds {seconds}' -PassThru -NoNewWindow; \
                 $p = '{}.tmp'; $child.Id | Set-Content -LiteralPath $p; \
                 Move-Item -Force -LiteralPath $p -Destination '{}'; \
                 Write-Output 'started a sleeper'",
                path.replace('\'', "''"),
                path.replace('\'', "''")
            );
            self.command = vec![
                "powershell.exe".to_owned(),
                "-NoProfile".to_owned(),
                "-NonInteractive".to_owned(),
                "-Command".to_owned(),
                script,
            ];
        }
        #[cfg(not(windows))]
        {
            self.command = vec![
                "sh".to_owned(),
                "-c".to_owned(),
                format!("sleep {seconds} & echo $! > \"$1.tmp\" && mv \"$1.tmp\" \"$1\" && echo 'started a sleeper'"),
                "_".to_owned(),
                path,
            ];
        }
        self.pid_file = Some(pid_file);
    }

    fn when_it_runs_with_a_timeout_of(&mut self, timeout: Duration) {
        let start = Instant::now();
        match link_association::run_command_with_timeout(&self.command, timeout) {
            Ok(result) => self.result = Some(result),
            Err(e) => self.error = Some(e),
        }
        self.took = start.elapsed();
    }

    fn then_it_failed_as(&self, message: &str) {
        let err = self.error.as_ref().unwrap();
        assert!(err.to_string().contains(message), "unexpected error: {err}");
    }

    fn then_it_ended_with(&self, exit: i32, output: &str) {
        assert!(self.error.is_none(), "{:?}", self.error);
        let result = self.result.as_ref().unwrap();
        assert_eq!(result.exit, exit);
        assert!(result.output.contains(output), "missing {output:?} in {:?}", result.output);
    }

    fn then_it_answered_within(&self, limit: Duration) {
        assert!(self.took < limit, "took {:?}", self.took);
    }

    fn then_the_sleeper_is_soon_gone(&self) {
        let start = Instant::now();
        let mut pid = self.sleeper_id();
        while pid.is_none() && start.elapsed() < Duration::from_secs(10) {
            thread::sleep(Duration::from_millis(50));
            pid = self.sleeper_id();
        }
        let Some(pid) = pid else {
            return;
        };
        let mut system = System::new().unwrap();
        while start.elapsed() < Duration::from_secs(10) && is_alive(&mut system, pid) {
            thread::sleep(Duration::from_millis(50));
        }
        assert!(!is_alive(&mut system, pid), "sleeper {pid} still alive");
    }

    fn sleeper_id(&self) -> Option<u32> {
        let path = self.pid_file.as_ref()?;
        let text = fs::read_to_string(path).ok()?;
        text.trim().parse().ok()
    }
}

impl Drop for Scenario {
    fn drop(&mut self) {
        if let Some(pid) = self.sleeper_id() {
            kill_process(pid);
        }
    }
}

fn is_alive(system: &mut System, pid: u32) -> bool {
    let target = Pid::from_u32(pid);
    system.refresh_processes_specifics(ProcessesToUpdate::Some(&[target]), true, ProcessRefreshKind::nothing());
    system.process(target).is_some()
}

fn kill_process(pid: u32) {
    #[cfg(windows)]
    let _ = Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/F", "/T"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
    #[cfg(not(windows))]
    let _ = Command::new("kill").args(["-9", &pid.to_string()]).stdout(Stdio::null()).stderr(Stdio::null()).status();
}
