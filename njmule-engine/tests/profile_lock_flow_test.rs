// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Black-box checks of the `njmule` executable matching `ProfileLockFlowTest` and the GPL/legal CLI options.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::fs;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use njmule_engine::api::nj_mule_info;
use njmule_engine::app::link_handoff;
use njmule_engine::config::app_directories::AppDirectories;

use crate::free_port;

const WAIT: Duration = Duration::from_secs(30);
const LINK: &str = "ed2k://|file|handed-over.bin|1000|0123456789ABCDEF0123456789ABCDEF|/";

#[test]
fn should_hand_a_link_to_the_njmule_running_on_the_profile() {
    let mut flow = Flow::new();
    flow.given_njmule_running_on_the_profile();

    flow.when_njmule_starts_on_the_profile_with(&[LINK]);

    flow.then_the_exit_code_is(0);
    flow.then_it_said("The links went to the njMule already running.");
    flow.then_the_running_njmule_downloads_the_link();
}

#[test]
fn should_refuse_a_second_njmule_on_the_profile() {
    let mut flow = Flow::new();
    flow.given_njmule_running_on_the_profile();

    flow.when_njmule_starts_on_the_profile_with(&[]);

    flow.then_the_exit_code_is(1);
    flow.then_it_said("njMule is already running with the configuration in");
}

#[test]
fn should_have_the_njmule_running_on_the_profile_exit() {
    let mut flow = Flow::new();
    flow.given_njmule_running_on_the_profile();

    flow.when_njmule_starts_on_the_profile_with(&["--exit"]);

    flow.then_the_exit_code_is(0);
    flow.then_it_said("has exited.");
    flow.then_the_running_njmule_has_stopped();
    flow.then_the_running_output_contains("New Jack Mule");
}

#[test]
fn should_print_the_version_and_legal_notices_from_the_executable() {
    let cases = [
        ("--version", nj_mule_info::LICENSE_ID),
        ("--license", "GNU GENERAL PUBLIC LICENSE"),
        ("--warranty", "Disclaimer of Warranty"),
        ("--notice", "Third-party components"),
        ("--help", "--config-dir"),
    ];
    for (option, expected) in cases {
        let output = Command::new(env!("CARGO_BIN_EXE_njmule")).arg(option).output().unwrap();
        assert_eq!(output.status.code(), Some(0), "{option}");
        let stdout = String::from_utf8(output.stdout).unwrap();
        assert!(stdout.contains(expected), "{option}: missing {expected:?} in {stdout:?}");
    }
}

#[test]
fn should_print_and_renew_the_api_token_from_the_executable() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().to_string_lossy().into_owned();

    let first =
        Command::new(env!("CARGO_BIN_EXE_njmule")).args(["--config-dir", &root, "--api-token"]).output().unwrap();
    assert_eq!(first.status.code(), Some(0));
    let token1 = String::from_utf8(first.stdout).unwrap().trim().to_owned();
    assert!(!token1.is_empty());

    let renewed = Command::new(env!("CARGO_BIN_EXE_njmule"))
        .args(["--config-dir", &root, "--new-api-token"])
        .output()
        .unwrap();
    assert_eq!(renewed.status.code(), Some(0));
    let token2 = String::from_utf8(renewed.stdout).unwrap().trim().to_owned();
    assert!(!token2.is_empty());
    assert_ne!(token1, token2);
}

struct Flow {
    dir: tempfile::TempDir,
    root: PathBuf,
    dirs: AppDirectories,
    running: Option<Child>,
    running_out: PathBuf,
    exit: i32,
    output: String,
}

impl Flow {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("njmule");
        let dirs = AppDirectories::under_root(&root).unwrap();
        let running_out = root.join("running.out");
        Self { dir, root, dirs, running: None, running_out, exit: -1, output: String::new() }
    }

    fn given_njmule_running_on_the_profile(&mut self) {
        fs::create_dir_all(&self.dirs.config).unwrap();
        let port = free_port::free_port();
        fs::write(
            self.dirs.config.join("njmule.properties"),
            format!("net.tcpPort={port}\nnet.kad.enabled=false\nupdate.mode=OFF\n"),
        )
        .unwrap();
        let log = fs::File::create(&self.running_out).unwrap();
        let child = Command::new(env!("CARGO_BIN_EXE_njmule"))
            .args([
                "--config-dir",
                &self.root.to_string_lossy(),
                "-D",
                "njmule.upnp.disabled=true",
                "-D",
                "njmule.countries.disabled=true",
                "-D",
                "njmule.update.disabled=true",
            ])
            .stdout(Stdio::from(log))
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        self.running = Some(child);
        await_true(|| self.dirs.config.join(link_handoff::FILE_NAME).exists(), "the hand-over to open");
    }

    fn when_njmule_starts_on_the_profile_with(&mut self, more: &[&str]) {
        let mut args = vec!["--config-dir".to_owned(), self.root.to_string_lossy().into_owned()];
        args.extend(more.iter().map(|arg| (*arg).to_owned()));
        let output = Command::new(env!("CARGO_BIN_EXE_njmule")).args(&args).output().unwrap();
        self.exit = output.status.code().unwrap_or(-1);
        self.output = format!("{}{}", String::from_utf8_lossy(&output.stdout), String::from_utf8_lossy(&output.stderr));
    }

    fn then_the_exit_code_is(&self, expected: i32) {
        assert_eq!(self.exit, expected, "output:\n{}", self.output);
    }

    fn then_it_said(&self, text: &str) {
        assert!(self.output.contains(text), "missing {text:?} in:\n{}", self.output);
    }

    fn then_the_running_njmule_downloads_the_link(&self) {
        await_true(
            || fs::read_dir(&self.dirs.temp).map(|entries| entries.count() > 0).unwrap_or(false),
            "the handed link to be downloaded",
        );
    }

    fn then_the_running_njmule_has_stopped(&mut self) {
        let child = self.running.as_mut().unwrap();
        let start = Instant::now();
        loop {
            if child.try_wait().unwrap().is_some() {
                self.running = None;
                return;
            }
            assert!(start.elapsed() < WAIT, "the running njMule did not stop");
            thread::sleep(Duration::from_millis(50));
        }
    }

    fn then_the_running_output_contains(&self, text: &str) {
        let out = fs::read_to_string(&self.running_out).unwrap();
        assert!(out.contains(text), "missing {text:?} in:\n{out}");
    }
}

impl Drop for Flow {
    fn drop(&mut self) {
        let _ = &self.dir;
        if let Some(mut child) = self.running.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

fn await_true(check: impl Fn() -> bool, what: &str) {
    let start = Instant::now();
    while start.elapsed() < WAIT {
        if check() {
            return;
        }
        thread::sleep(Duration::from_millis(100));
    }
    panic!("Waited {} s for {what}", WAIT.as_secs());
}
