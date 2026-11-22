// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ApplyPlanTest, under its names.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fs;
use std::io;
use std::path::PathBuf;

use njmule_engine::api::install_kind::InstallKind;
use njmule_engine::update::apply_plan::{ApplyPlan, State};
use njmule_engine::update::update_files::PLAN_FILE;

#[test]
fn should_read_the_plan_as_it_was_kept() {
    let mut test = Planning::new();
    test.given_the_plan(ApplyPlan {
        version: "0.2.0".to_owned(),
        from: "0.1.0".to_owned(),
        kind: InstallKind::Tar,
        package_file: PathBuf::from("/home/u/.cache/njmule/update/0.2.0/njmule-0.2.0-linux-x64.tar.xz"),
        staged: Some(PathBuf::from("/home/u/.cache/njmule/update/0.2.0/files")),
        target: Some(PathBuf::from("/home/u/opt/njmule")),
        probe: Some("njmule-gui".to_owned()),
        restart: true,
        unattended: false,
        program: Some(PathBuf::from("/home/u/opt/njmule/njmule-gui")),
        arguments: vec!["--config-dir".to_owned(), "/home/u/njmule data".to_owned(), String::new()],
        state: State::RolledBack,
        reason: Some("the new version stopped with code 3".to_owned()),
        helper: Some(4242),
        time: "2026-11-03T08:00:00Z".parse().unwrap(),
    });
    test.given_it_kept();

    test.when_read();

    test.then_it_reads_as_kept();
}

#[test]
fn should_read_a_plan_without_the_files_of_an_installer() {
    let mut test = Planning::new();
    test.given_the_plan(ApplyPlan {
        version: "0.2.0".to_owned(),
        from: "0.1.0".to_owned(),
        kind: InstallKind::Msi,
        package_file: PathBuf::from("C:\\Users\\u\\AppData\\Local\\njMule\\cache\\update\\0.2.0\\njmule.msi"),
        staged: None,
        target: None,
        probe: None,
        restart: false,
        unattended: true,
        program: None,
        arguments: Vec::new(),
        state: State::HandedOver,
        reason: None,
        helper: None,
        time: "2026-11-03T08:00:00Z".parse().unwrap(),
    });
    test.given_it_kept();

    test.when_read();

    test.then_it_reads_as_kept();
}

#[test]
fn should_read_nothing_when_no_plan_is_kept() {
    let mut test = Planning::new();

    test.when_read();

    test.then_nothing_was_read();
}

#[test]
fn should_refuse_a_damaged_plan() {
    let mut test = Planning::new();
    test.given_the_file_holds(
        "version=0.2.0\nfrom=0.1.0\nkind=zip\npackage=n.zip\nstate=sleeping\ntime=2026-11-03T08:00:00Z\n",
    );

    test.when_read();

    test.then_it_failed_with("damaged");
}

#[test]
fn should_refuse_a_plan_that_lacks_a_part() {
    let mut test = Planning::new();
    test.given_the_file_holds("version=0.2.0\n");

    test.when_read();

    test.then_it_failed_with("lacks");
}

struct Planning {
    dir: tempfile::TempDir,
    plan: Option<ApplyPlan>,
    read: Option<io::Result<Option<ApplyPlan>>>,
}

impl Planning {
    fn new() -> Self {
        Self { dir: tempfile::tempdir().unwrap(), plan: None, read: None }
    }

    fn file(&self) -> PathBuf {
        self.dir.path().join(PLAN_FILE)
    }

    fn given_the_plan(&mut self, chosen: ApplyPlan) {
        self.plan = Some(chosen);
    }

    fn given_it_kept(&self) {
        self.plan.as_ref().unwrap().save(&self.file()).unwrap();
    }

    fn given_the_file_holds(&self, text: &str) {
        fs::write(self.file(), text).unwrap();
    }

    fn when_read(&mut self) {
        self.read = Some(ApplyPlan::load(&self.file()));
    }

    fn then_it_reads_as_kept(&self) {
        assert_eq!(self.read.as_ref().unwrap().as_ref().unwrap(), &self.plan);
    }

    fn then_nothing_was_read(&self) {
        assert!(self.read.as_ref().unwrap().as_ref().unwrap().is_none());
    }

    fn then_it_failed_with(&self, words: &str) {
        let failure = self.read.as_ref().unwrap().as_ref().expect_err("a failure");
        assert!(failure.to_string().contains(words), "{failure}");
    }
}
