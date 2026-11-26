// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Tests for update helper.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use jiff::Timestamp;
use njmule_engine::api::install_kind::InstallKind;
use njmule_engine::update::apply_plan::{ApplyPlan, State};
use njmule_engine::update::commands::{CommandResult, Commands};
use njmule_engine::update::update_files::UpdateFiles;
use njmule_engine::update::update_helper::UpdateHelper;

use crate::fake_process::{FakeLauncher, FakeProcess};

#[test]
fn should_install_the_new_version_and_start_it() {
    let mut test = Helping::new();
    test.given_the_installed_version("0.1.0");
    test.given_the_new_version_unpacked("0.2.0");

    test.when_the_helper_runs();

    test.then_it_ended_with(0);
    test.then_the_installed_version_is("0.2.0");
    test.then_the_old_version_is_kept("0.1.0");
    test.then_the_plan_says(State::Installed);
    test.then_it_started(&[&test.program(), "--config-dir", "/data"]);
}

#[test]
fn should_go_back_when_the_new_version_stops_with_an_error() {
    let mut test = Helping::new();
    test.given_the_installed_version("0.1.0");
    test.given_the_new_version_unpacked("0.2.0");
    test.given_the_new_version(Behaviour::Fails);

    test.when_the_helper_runs();

    test.then_it_ended_with(1);
    test.then_the_installed_version_is("0.1.0");
    test.then_the_plan_says(State::RolledBack);
    test.then_the_plan_tells_why("stopped with code 3");
    test.then_it_started_times(2);
}

#[test]
fn should_keep_the_old_version_when_the_new_one_does_not_answer() {
    let mut test = Helping::new();
    test.given_the_installed_version("0.1.0");
    test.given_the_new_version_unpacked("0.2.0");
    test.given_the_new_program_answers(1, "cannot load glass.dll");

    test.when_the_helper_runs();

    test.then_it_ended_with(1);
    test.then_the_installed_version_is("0.1.0");
    test.then_the_plan_says(State::Failed);
    test.then_the_plan_tells_why("does not start");
    test.then_it_started(&[&test.program(), "--config-dir", "/data"]);
}

#[test]
fn should_refuse_a_new_program_that_names_another_version() {
    let mut test = Helping::new();
    test.given_the_installed_version("0.1.0");
    test.given_the_new_version_unpacked("0.2.0");
    test.given_the_new_program_answers(0, "njMule 0.1.0 (GPL-3.0-or-later)");

    test.when_the_helper_runs();

    test.then_the_installed_version_is("0.1.0");
    test.then_the_plan_says(State::Failed);
}

#[test]
fn should_only_install_when_nj_mule_exits() {
    let mut test = Helping::new();
    test.given_the_installed_version("0.1.0");
    test.given_the_new_version_unpacked("0.2.0");
    test.given_no_restart();

    test.when_the_helper_runs();

    test.then_it_ended_with(0);
    test.then_the_installed_version_is("0.2.0");
    test.then_the_old_version_is_kept("0.1.0");
    test.then_nothing_started();
}

#[test]
fn should_keep_the_new_version_when_the_user_closes_it_at_once() {
    let mut test = Helping::new();
    test.given_the_installed_version("0.1.0");
    test.given_the_new_version_unpacked("0.2.0");
    test.given_the_new_version(Behaviour::ClosedAtOnce);

    test.when_the_helper_runs();

    test.then_it_ended_with(0);
    test.then_the_installed_version_is("0.2.0");
    test.then_the_plan_says(State::Installed);
}

#[test]
fn should_do_nothing_without_a_plan() {
    let mut test = Helping::new();

    test.when_the_helper_runs();

    test.then_it_ended_with(1);
    test.then_nothing_started();
}

/// What the new version does once started.
#[derive(Clone, Copy)]
enum Behaviour {
    ProvesItself,
    Fails,
    ClosedAtOnce,
}

struct Helping {
    dir: tempfile::TempDir,
    started: Arc<Mutex<Vec<Vec<String>>>>,
    target: Option<PathBuf>,
    staged: Option<PathBuf>,
    restart: bool,
    answer: Answer,
    new_version: Behaviour,
    code: i32,
}

#[derive(Clone)]
struct Answer {
    code: i32,
    text: String,
}

impl Commands for Answer {
    fn run(&self, _command: &[String], _timeout: Duration) -> io::Result<CommandResult> {
        Ok(CommandResult { exit_code: self.code, output: self.text.clone() })
    }
}

impl Helping {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            started: Arc::default(),
            target: None,
            staged: None,
            restart: true,
            answer: Answer { code: 0, text: "njMule 0.2.0 (GPL-3.0-or-later)".to_owned() },
            new_version: Behaviour::ProvesItself,
            code: 0,
        }
    }

    fn given_the_installed_version(&mut self, version: &str) {
        let target = self.dir.path().join("installed");
        write(&target.join("njmule.exe"), version);
        self.target = Some(target);
    }

    fn given_the_new_version_unpacked(&mut self, version: &str) {
        let staged = self.files().unpacked(version).unwrap();
        write(&staged.join("njmule.exe"), version);
        self.staged = Some(staged);
    }

    fn given_the_new_version(&mut self, behaviour: Behaviour) {
        self.new_version = behaviour;
    }

    fn given_the_new_program_answers(&mut self, code: i32, text: &str) {
        self.answer = Answer { code, text: text.to_owned() };
    }

    fn given_no_restart(&mut self) {
        self.restart = false;
    }

    fn when_the_helper_runs(&mut self) {
        let files = self.files();
        if self.staged.is_some() {
            self.plan().save(&files.plan()).unwrap();
        }
        let (behaviour, started) = (self.new_version, Arc::clone(&self.started));
        let marks = files.clone();
        let launcher = FakeLauncher::new(Arc::clone(&self.started), move |_command| {
            if started.lock().unwrap().len() > 1 {
                return Ok(FakeProcess::ended(0));
            }
            Ok(match behaviour {
                Behaviour::ProvesItself => {
                    fs::write(marks.started("0.2.0").unwrap(), "")?;
                    FakeProcess::default()
                }
                Behaviour::Fails => FakeProcess::ended(3),
                Behaviour::ClosedAtOnce => FakeProcess::ended(0),
            })
        });
        let helper = UpdateHelper::new(
            &files.plan(),
            Arc::new(self.answer.clone()),
            Arc::new(launcher),
            Arc::new(Timestamp::now),
            Duration::from_secs(1),
            Duration::from_secs(10),
            Duration::from_millis(10),
        );
        self.code = helper.run(&[]);
    }

    fn then_it_ended_with(&self, expected: i32) {
        assert_eq!(self.code, expected);
    }

    fn then_the_installed_version_is(&self, version: &str) {
        assert_eq!(fs::read_to_string(self.target.as_ref().unwrap().join("njmule.exe")).unwrap(), version);
    }

    fn then_the_old_version_is_kept(&self, version: &str) {
        assert_eq!(fs::read_to_string(self.target.as_ref().unwrap().join("njmule.exe.old")).unwrap(), version);
    }

    fn then_the_plan_says(&self, state: State) {
        assert_eq!(ApplyPlan::load(&self.files().plan()).unwrap().unwrap().state, state);
    }

    fn then_the_plan_tells_why(&self, words: &str) {
        let reason = ApplyPlan::load(&self.files().plan()).unwrap().unwrap().reason.unwrap();
        assert!(reason.contains(words), "{reason}");
    }

    fn then_it_started(&self, command: &[&str]) {
        let expected: Vec<String> = command.iter().map(|word| (*word).to_owned()).collect();
        assert_eq!(*self.started.lock().unwrap(), vec![expected]);
    }

    fn then_it_started_times(&self, times: usize) {
        assert_eq!(self.started.lock().unwrap().len(), times);
    }

    fn then_nothing_started(&self) {
        assert!(self.started.lock().unwrap().is_empty());
    }

    fn plan(&self) -> ApplyPlan {
        ApplyPlan {
            version: "0.2.0".to_owned(),
            from: "0.1.0".to_owned(),
            kind: InstallKind::WindowsZip,
            package_file: self.dir.path().join("njmule-0.2.0-windows-x64.zip"),
            staged: self.staged.clone(),
            target: self.target.clone(),
            probe: Some("njmule.exe".to_owned()),
            restart: self.restart,
            unattended: false,
            program: self.restart.then(|| PathBuf::from(self.program())),
            arguments: if self.restart { vec!["--config-dir".to_owned(), "/data".to_owned()] } else { Vec::new() },
            state: State::HandedOver,
            reason: None,
            helper: None,
            time: "2026-11-03T08:00:00Z".parse().unwrap(),
        }
    }

    fn program(&self) -> String {
        self.dir.path().join("installed").join("njmule-gui.exe").to_string_lossy().into_owned()
    }

    fn files(&self) -> UpdateFiles {
        UpdateFiles::new(self.dir.path().join("cache").join("update"))
    }
}

fn write(file: &Path, text: &str) {
    fs::create_dir_all(file.parent().unwrap()).unwrap();
    fs::write(file, text).unwrap();
}
