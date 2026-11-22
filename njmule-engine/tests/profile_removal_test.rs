// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Twin of Java's `org.njmule.app.ProfileRemovalTest`.

#![allow(clippy::unwrap_used)]

use std::fs::{self, File, OpenOptions};
use std::io;
use std::sync::{Arc, Mutex};

use njmule_engine::app::autostart::Autostart;
use njmule_engine::app::launch_options::LaunchOptions;
use njmule_engine::app::profile_removal;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::persist::property_lines;
use tempfile::TempDir;

#[test]
fn should_delete_the_profile_and_keep_the_completed_downloads() {
    let mut test = Scenario::new();
    test.given_a_profile_with_a_download();

    test.when_removed(&["--remove-profile", "--yes"]);

    test.then_the_exit_code_is(profile_removal::EXIT_OK);
    test.then_the_profile_is_gone();
    test.then_the_download_stays();
}

#[test]
fn should_keep_the_folder_of_the_downloads_the_user_chose_inside_the_profile() {
    let mut test = Scenario::new();
    test.given_a_profile_with_a_download();
    test.given_the_downloads_go_inside_the_profile();

    test.when_removed(&["--remove-profile", "--yes"]);

    test.then_the_exit_code_is(profile_removal::EXIT_OK);
    test.then_the_chosen_downloads_stay();
}

#[test]
fn should_stop_starting_at_login_before_deleting_the_profile() {
    let mut test = Scenario::new();
    test.given_a_profile_with_a_download();
    test.given_it_starts_at_login();

    test.when_removed(&["--remove-profile", "--yes"]);

    test.then_the_exit_code_is(profile_removal::EXIT_OK);
    test.then_the_profile_is_gone();
    test.then_it_starts_at_login(false);
}

#[test]
fn should_delete_nothing_when_the_start_at_login_cannot_be_turned_off() {
    let mut test = Scenario::new();
    test.given_a_profile_with_a_download();
    test.given_it_starts_at_login();
    test.given_the_start_at_login_cannot_be_turned_off();

    test.when_removed(&["--remove-profile", "--yes"]);

    test.then_the_exit_code_is(profile_removal::EXIT_FAILURE);
    test.then_the_errors_say("Nothing was deleted.");
    test.then_the_profile_stays();
}

#[test]
fn should_delete_after_a_yes() {
    let mut test = Scenario::new();
    test.given_a_profile_with_a_download();

    test.when_removed(&["--remove-profile"]);

    test.then_the_profile_is_gone();
}

#[test]
fn should_delete_nothing_without_a_yes() {
    let mut test = Scenario::new();
    test.given_a_profile_with_a_download();
    test.given_the_answer("n");

    test.when_removed(&["--remove-profile"]);

    test.then_the_exit_code_is(profile_removal::EXIT_OK);
    test.then_the_profile_stays();
}

#[test]
fn should_leave_a_profile_njmule_runs_on() {
    let mut test = Scenario::new();
    test.given_a_profile_with_a_download();
    test.given_njmule_runs_on_it();

    test.when_removed(&["--remove-profile", "--yes"]);

    test.then_the_exit_code_is(profile_removal::EXIT_FAILURE);
    test.then_the_profile_stays();
}

#[derive(Default)]
struct AutostartState {
    on: bool,
    refuses_to_turn_off: bool,
}

#[derive(Clone)]
struct PretendAutostart(Arc<Mutex<AutostartState>>);

impl PretendAutostart {
    fn new() -> Self {
        Self(Arc::new(Mutex::new(AutostartState::default())))
    }
}

impl Autostart for PretendAutostart {
    fn is_on(&self) -> io::Result<bool> {
        Ok(self.0.lock().unwrap().on)
    }

    fn turn_on(&self) -> io::Result<()> {
        self.0.lock().unwrap().on = true;
        Ok(())
    }

    fn turn_off(&self) -> io::Result<()> {
        let mut state = self.0.lock().unwrap();
        if state.refuses_to_turn_off {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "denied"));
        }
        state.on = false;
        Ok(())
    }
}

struct Scenario {
    dir: TempDir,
    out: Vec<u8>,
    err: Vec<u8>,
    profile: Option<AppDirectories>,
    answer: String,
    autostart: PretendAutostart,
    code: i32,
    running: Option<File>,
}

impl Scenario {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            out: Vec::new(),
            err: Vec::new(),
            profile: None,
            answer: "y".to_owned(),
            autostart: PretendAutostart::new(),
            code: -1,
            running: None,
        }
    }

    fn profile(&self) -> &AppDirectories {
        self.profile.as_ref().unwrap()
    }

    fn given_a_profile_with_a_download(&mut self) {
        let profile = AppDirectories::under_root(self.dir.path()).unwrap();
        profile.create_all().unwrap();
        fs::create_dir_all(&profile.downloads).unwrap();
        fs::write(profile.config.join("preferences.ini"), "nick=Ann").unwrap();
        fs::write(profile.downloads.join("talk.bin"), "data").unwrap();
        self.profile = Some(profile);
    }

    // The completed downloads go to a folder of the data, which would go with it.
    fn given_the_downloads_go_inside_the_profile(&self) {
        let chosen = self.profile().data.join("Incoming");
        fs::create_dir_all(&chosen).unwrap();
        fs::write(chosen.join("film.mkv"), "film").unwrap();
        let mut text = String::new();
        property_lines::append(&mut text, "dirs.incoming", &chosen.to_string_lossy());
        fs::write(self.profile().config.join("njmule.properties"), text).unwrap();
    }

    // The engine's lock of the profile, held as by a running njMule.
    fn given_njmule_runs_on_it(&mut self) {
        let file = OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .truncate(false)
            .open(self.profile().config.join("njmule.lock"))
            .unwrap();
        file.try_lock().unwrap();
        self.running = Some(file);
    }

    fn given_it_starts_at_login(&self) {
        self.autostart.turn_on().unwrap();
    }

    fn given_the_start_at_login_cannot_be_turned_off(&self) {
        self.autostart.0.lock().unwrap().refuses_to_turn_off = true;
    }

    fn given_the_answer(&mut self, typed: &str) {
        self.answer = typed.to_owned();
    }

    fn when_removed(&mut self, options: &[&str]) {
        let mut args = vec!["--config-dir".to_owned(), self.dir.path().to_string_lossy().into_owned()];
        args.extend(options.iter().map(|s| (*s).to_owned()));
        let refs: Vec<&str> = args.iter().map(String::as_str).collect();
        let cli = LaunchOptions::parse(&refs).unwrap();
        let answer = self.answer.clone();
        let autostart = self.autostart.clone();
        self.code = profile_removal::run(
            &cli,
            &mut self.out,
            &mut self.err,
            move |_| Some(answer.clone()),
            |_| None,
            move |_| Some(Box::new(autostart.clone()) as Box<dyn Autostart>),
        );
    }

    fn then_it_starts_at_login(&self, expected: bool) {
        assert_eq!(self.autostart.is_on().unwrap(), expected);
    }

    fn then_the_exit_code_is(&self, expected: i32) {
        assert_eq!(self.code, expected);
    }

    fn then_the_errors_say(&self, text: &str) {
        let err = String::from_utf8_lossy(&self.err);
        assert!(err.contains(text), "missing {text:?} in:\n{err}");
    }

    fn then_the_profile_is_gone(&self) {
        assert!(!self.profile().config.exists());
        assert!(!self.profile().data.exists());
        assert!(!self.profile().logs.exists());
    }

    fn then_the_profile_stays(&self) {
        assert!(self.profile().config.join("preferences.ini").exists());
    }

    fn then_the_chosen_downloads_stay(&self) {
        let chosen = self.profile().data.join("Incoming");
        assert_eq!(fs::read_to_string(chosen.join("film.mkv")).unwrap(), "film");
        let out = String::from_utf8_lossy(&self.out);
        let display = chosen.to_string_lossy();
        assert!(out.contains(display.as_ref()), "missing {display:?} in:\n{out}");
    }

    fn then_the_download_stays(&self) {
        assert_eq!(fs::read_to_string(self.profile().downloads.join("talk.bin")).unwrap(), "data");
    }
}

impl Drop for Scenario {
    fn drop(&mut self) {
        if let Some(file) = self.running.take() {
            let _ = file.unlock();
        }
    }
}
