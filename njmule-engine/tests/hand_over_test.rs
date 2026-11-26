// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Tests for hand over.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use jiff::Timestamp;
use njmule_engine::api::install_kind::InstallKind;
use njmule_engine::api::update_status::Installer;
use njmule_engine::update::apply_plan::{ApplyPlan, State};
use njmule_engine::update::commands::{CommandResult, Commands};
use njmule_engine::update::hand_over::{self, HandOver};
use njmule_engine::update::installation::Installation;
use njmule_engine::update::update_files::UpdateFiles;

use crate::fake_process::{FakeLauncher, FakeProcess};

#[test]
fn should_hand_an_archive_to_this_program_run_again_as_the_installer() {
    let mut test = Handing::new();
    test.given_an_installation();

    test.when_handed_over();

    test.then_no_failure_occurred();
    test.then_the_first_start_was(&[
        &test.executable().to_string_lossy(),
        "--apply-update",
        &test.files().plan().to_string_lossy(),
        "--wait",
        &pid(),
    ]);
    test.then_the_plan_starts_again_with(&test.executable(), &["--config-dir", "/data"]);
}

#[test]
fn should_start_again_through_the_desktop_window() {
    let mut test = Handing::new();
    test.given_an_installation();
    test.given_the_desktop_window();

    test.when_handed_over();

    test.then_the_plan_starts_again_with(&test.launcher(), &["--config-dir", "/data"]);
}

#[test]
fn should_start_the_old_version_again_when_the_installer_does_not_run() {
    let mut test = Handing::new();
    test.given_an_installation();
    test.given_an_installer_that_stops_at_once();

    test.when_handed_over();

    test.then_it_failed_with("stopped at once");
    test.then_the_plan_says(State::Failed);
    test.then_the_last_start_was(&[&test.executable().to_string_lossy(), "--config-dir", "/data", "--wait", &pid()]);
}

#[test]
fn should_hand_an_installer_package_to_a_copy_of_the_launcher() {
    let mut test = Handing::new();
    test.given_an_installation();
    test.given_the_kind(InstallKind::Msi);
    test.given_the_desktop_window();

    test.when_handed_over();

    test.then_no_failure_occurred();
    test.then_the_launcher_was_copied();
    test.then_the_first_start_was(&[
        &test.copied_launcher().to_string_lossy(),
        "--apply-update",
        &test.package_file().to_string_lossy(),
        "--wait",
        &pid(),
        "--ui",
        "passive",
        "--log",
        &test.version().join(hand_over::INSTALLER_LOG).to_string_lossy(),
        "--result",
        &test.version().join(hand_over::INSTALLER_RESULT).to_string_lossy(),
        "--relaunch",
        &test.launcher().to_string_lossy(),
        "--config-dir",
        "/data",
    ]);
}

#[test]
fn should_install_quietly_when_nobody_watches() {
    let mut test = Handing::new();
    test.given_an_installation();
    test.given_the_kind(InstallKind::Msi);
    test.given_nobody_watches();

    test.when_handed_over();

    test.then_the_first_start_has(&["--ui", "quiet"]);
}

#[test]
fn should_let_windows_ask_for_administrator_rights() {
    let mut test = Handing::new();
    test.given_an_installation();
    test.given_the_kind(InstallKind::Msi);
    test.given_the_installer(Installer::Elevated);

    test.when_handed_over();

    test.then_the_first_start_has(&["--all-users"]);
}

#[test]
fn should_swap_the_files_from_the_command_line() {
    let mut test = Handing::new();
    test.given_an_installation();
    test.given_no_restart();

    test.when_installed_here();

    test.then_no_failure_occurred();
    test.then_it_is_in_place(true);
    test.then_the_installed_version_is("0.2.0");
    test.then_the_plan_says(State::Installed);
}

#[test]
fn should_leave_an_installer_package_to_its_installer_from_the_command_line() {
    let mut test = Handing::new();
    test.given_an_installation();
    test.given_the_kind(InstallKind::Msi);
    test.given_no_restart();

    test.when_installed_here();

    test.then_it_is_in_place(false);
    test.then_the_first_start_has(&["--apply-update", &test.package_file().to_string_lossy()]);
    test.then_the_first_start_has_no("--relaunch");
}

struct Handing {
    dir: tempfile::TempDir,
    started: Arc<Mutex<Vec<Vec<String>>>>,
    kind: InstallKind,
    installer: Installer,
    restart: bool,
    unattended: bool,
    desktop: bool,
    helper_takes_over: bool,
    failure: Option<io::Error>,
    in_place: bool,
}

// The new program answers with its version.
struct Answering;

impl Commands for Answering {
    fn run(&self, _command: &[String], _timeout: Duration) -> io::Result<CommandResult> {
        Ok(CommandResult { exit_code: 0, output: "njMule 0.2.0 (GPL-3.0-or-later)".to_owned() })
    }
}

impl Handing {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            started: Arc::default(),
            kind: InstallKind::WindowsZip,
            installer: Installer::Itself,
            restart: true,
            unattended: false,
            desktop: false,
            helper_takes_over: true,
            failure: None,
            in_place: false,
        }
    }

    fn given_an_installation(&self) {
        write(&self.executable(), "0.1.0");
        write(&self.launcher(), "launcher 0.1.0");
        write(&self.files().unpacked("0.2.0").unwrap().join("njmule.exe"), "0.2.0");
        write(&self.package_file(), "package");
    }

    fn given_the_kind(&mut self, chosen: InstallKind) {
        self.kind = chosen;
    }

    fn given_the_installer(&mut self, chosen: Installer) {
        self.installer = chosen;
    }

    fn given_the_desktop_window(&mut self) {
        self.desktop = true;
    }

    fn given_nobody_watches(&mut self) {
        self.unattended = true;
    }

    fn given_no_restart(&mut self) {
        self.restart = false;
    }

    fn given_an_installer_that_stops_at_once(&mut self) {
        self.helper_takes_over = false;
    }

    fn when_handed_over(&mut self) {
        self.failure = self.hand_over().start(&["--config-dir".to_owned(), "/data".to_owned()]).err();
    }

    fn when_installed_here(&mut self) {
        match self.hand_over().install_here() {
            Ok(in_place) => self.in_place = in_place,
            Err(e) => self.failure = Some(e),
        }
    }

    fn then_no_failure_occurred(&self) {
        assert!(self.failure.is_none(), "{:?}", self.failure);
    }

    fn then_it_failed_with(&self, words: &str) {
        let failure = self.failure.as_ref().expect("a failure");
        assert!(failure.to_string().contains(words), "{failure}");
    }

    fn then_the_first_start_was(&self, command: &[&str]) {
        assert_eq!(
            self.started.lock().unwrap().first().unwrap(),
            &command.iter().map(|word| (*word).to_owned()).collect::<Vec<_>>()
        );
    }

    fn then_the_last_start_was(&self, command: &[&str]) {
        assert_eq!(
            self.started.lock().unwrap().last().unwrap(),
            &command.iter().map(|word| (*word).to_owned()).collect::<Vec<_>>()
        );
    }

    fn then_the_first_start_has(&self, words: &[&str]) {
        let started = self.started.lock().unwrap();
        let first = started.first().unwrap();
        assert!(
            first.windows(words.len()).any(|run| run.iter().zip(words).all(|(word, wanted)| word == wanted)),
            "{first:?}"
        );
    }

    fn then_the_first_start_has_no(&self, word: &str) {
        assert!(!self.started.lock().unwrap().first().unwrap().iter().any(|each| each == word));
    }

    fn then_the_plan_starts_again_with(&self, program: &Path, arguments: &[&str]) {
        let plan = ApplyPlan::load(&self.files().plan()).unwrap().unwrap();
        assert_eq!(plan.program.as_deref(), Some(program));
        assert_eq!(plan.arguments, arguments.iter().map(|word| (*word).to_owned()).collect::<Vec<_>>());
        assert!(plan.restart);
    }

    fn then_the_plan_says(&self, state: State) {
        assert_eq!(ApplyPlan::load(&self.files().plan()).unwrap().unwrap().state, state);
    }

    fn then_the_launcher_was_copied(&self) {
        assert_eq!(fs::read_to_string(self.copied_launcher()).unwrap(), "launcher 0.1.0");
    }

    fn then_it_is_in_place(&self, expected: bool) {
        assert_eq!(self.in_place, expected);
    }

    fn then_the_installed_version_is(&self, version: &str) {
        assert_eq!(fs::read_to_string(self.executable()).unwrap(), version);
    }

    fn hand_over(&self) -> HandOver {
        let swapped = self.kind != InstallKind::Msi;
        let root = self.executable().parent().unwrap().to_path_buf();
        let plan = ApplyPlan {
            version: "0.2.0".to_owned(),
            from: "0.1.0".to_owned(),
            kind: self.kind,
            package_file: self.package_file(),
            staged: swapped.then(|| self.files().unpacked("0.2.0").unwrap()),
            target: swapped.then_some(root),
            probe: swapped.then(|| "njmule.exe".to_owned()),
            restart: self.restart,
            unattended: self.unattended,
            program: None,
            arguments: Vec::new(),
            state: State::HandedOver,
            reason: None,
            helper: None,
            time: now(),
        };
        let installation = Installation {
            kind: self.kind,
            platform: "windows-x64".to_owned(),
            executable: Some(self.executable()),
            installer: self.installer,
        };
        let files = self.files();
        let (kind, takes_over) = (self.kind, self.helper_takes_over);
        // The installer of an archive says that it runs by taking the plan over.
        let launcher = FakeLauncher::new(Arc::clone(&self.started), move |command| {
            if command.iter().any(|word| word == "--apply-update") && kind != InstallKind::Msi {
                if !takes_over {
                    return Ok(FakeProcess::ended(1));
                }
                let plan = ApplyPlan::load(&files.plan()).unwrap().unwrap();
                plan.with_helper(4242, now()).save(&files.plan())?;
            }
            Ok(FakeProcess::default())
        });
        HandOver::new(
            plan,
            installation,
            self.files(),
            Arc::new(Answering),
            Arc::new(launcher),
            Arc::new(now),
            self.desktop,
        )
    }

    fn executable(&self) -> PathBuf {
        self.dir.path().join("installed").join("njmule.exe")
    }

    fn launcher(&self) -> PathBuf {
        self.dir.path().join("installed").join(hand_over::LAUNCHER_FILE)
    }

    fn copied_launcher(&self) -> PathBuf {
        self.version().join(hand_over::LAUNCHER_FILE)
    }

    fn package_file(&self) -> PathBuf {
        let ending = if self.kind == InstallKind::Msi { "msi" } else { "zip" };
        self.version().join(format!("njmule-0.2.0-windows-x64.{ending}"))
    }

    fn version(&self) -> PathBuf {
        self.files().version("0.2.0").unwrap()
    }

    fn files(&self) -> UpdateFiles {
        UpdateFiles::new(self.dir.path().join("cache").join("update"))
    }
}

fn now() -> Timestamp {
    "2026-11-03T08:00:00Z".parse().unwrap()
}

fn pid() -> String {
    std::process::id().to_string()
}

fn write(file: &Path, text: &str) {
    fs::create_dir_all(file.parent().unwrap()).unwrap();
    fs::write(file, text).unwrap();
}
