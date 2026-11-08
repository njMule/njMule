// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The schedule in force: which rule holds, when a rule begins or ends, and the preferences it gives.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use std::collections::HashSet;
use std::fs;

use njmule_engine::config::preferences::Preferences;
use njmule_engine::config::schedule::{DayOfWeek, Rule, Schedule};
use njmule_engine::engine::scheduler::{self, Change, Scheduler};
use njmule_engine::persist::schedule_codec;
use strum::VariantArray;
use time::{Date, Month, PrimitiveDateTime, Time};

#[test]
fn should_put_in_force_the_rule_that_holds() {
    let mut test = SchedulerTest::new();
    test.given_the_schedule(&[weekend(), night()]);

    test.when_the_clock_says(23, 30);

    test.then_the_change_is(Some(Change::Began(night())));
    test.then_the_rule_in_force_is(Some(&night()));
}

#[test]
fn should_tell_when_no_rule_holds_any_more() {
    let mut test = SchedulerTest::new();
    test.given_the_schedule(&[night()]);
    test.given_the_clock_said(23, 30);

    test.when_the_clock_says(12, 0);

    test.then_the_change_is(Some(Change::Ended));
    test.then_the_rule_in_force_is(None);
}

#[test]
fn should_tell_nothing_while_the_same_rule_holds() {
    let mut test = SchedulerTest::new();
    test.given_the_schedule(&[night()]);
    test.given_the_clock_said(23, 30);

    test.when_the_clock_says(5, 59);

    test.then_the_change_is(None);
}

#[test]
fn should_apply_the_rule_in_force_over_the_preferences() {
    let mut test = SchedulerTest::new();
    test.given_the_schedule(&[night()]);
    test.given_the_clock_said(23, 30);

    test.when_the_preferences_in_force_are_taken();

    test.then_the_upload_limit_in_force_is(7);
    test.then_the_saved_upload_limit_is(0);
}

#[test]
fn should_keep_in_force_without_acting_again_the_rule_edited_in_the_place_of_the_one_in_force() {
    let mut test = SchedulerTest::new();
    test.given_the_schedule(&[night()]);
    test.given_the_clock_said(23, 30);

    test.when_the_schedule_becomes(&[late_night()], 23, 30);

    test.then_the_change_is(None);
    test.then_the_rule_in_force_is(Some(&late_night()));
}

#[test]
fn should_put_in_force_a_rule_of_another_place_that_holds() {
    let mut test = SchedulerTest::new();
    test.given_the_schedule(&[night()]);
    test.given_the_clock_said(23, 30);

    test.when_the_schedule_becomes(&[disabled(night()), late_night()], 23, 30);

    test.then_the_change_is(Some(Change::Began(late_night())));
}

#[test]
fn should_save_the_schedule_it_puts_in_place() {
    let mut test = SchedulerTest::new();
    test.given_the_schedule(&[]);

    test.when_the_schedule_becomes(&[night(), weekend()], 12, 0);

    test.then_the_saved_schedule_is(&[night(), weekend()]);
}

#[test]
fn should_take_a_deleted_category_out_of_the_rules() {
    let mut test = SchedulerTest::new();
    test.given_the_schedule(&[stopping_and_resuming(2, 1)]);

    test.when_the_categories_are_deleted(&[2]);

    test.then_the_rules_are(&[stopping_and_resuming_none(1)]);
    test.then_the_saved_schedule_is(&[stopping_and_resuming_none(1)]);
}

#[test]
fn should_keep_in_force_the_rule_in_force_when_it_lets_a_category_go() {
    let mut test = SchedulerTest::new();
    test.given_the_schedule(&[stopping_and_resuming(2, 1)]);
    test.given_the_clock_said(12, 0);

    test.when_the_categories_are_deleted(&[2]);

    test.then_the_rule_in_force_is(Some(&stopping_and_resuming_none(1)));
}

#[test]
fn should_start_with_an_empty_schedule_when_its_file_cannot_be_read() {
    let mut test = SchedulerTest::new();
    test.given_a_folder_in_place_of_the_file();

    test.when_the_scheduler_is_loaded();

    test.then_the_rules_are(&[]);
}

#[test]
fn should_read_the_users_clock() {
    let mut test = SchedulerTest::new();

    test.when_the_users_clock_is_read();

    test.then_it_tells_the_local_time_of_the_system();
}

struct SchedulerTest {
    profile: tempfile::TempDir,
    scheduler: Option<Scheduler>,
    change: Option<Change>,
    effective: Option<Preferences>,
    saved: Preferences,
    clock: Option<PrimitiveDateTime>,
}

impl SchedulerTest {
    fn new() -> Self {
        Self {
            profile: tempfile::tempdir().unwrap(),
            scheduler: None,
            change: None,
            effective: None,
            saved: Preferences::default(),
            clock: None,
        }
    }

    fn given_the_schedule(&mut self, rules: &[Rule]) {
        schedule_codec::save(&self.file(), &Schedule { rules: rules.to_vec() }).unwrap();
        self.when_the_scheduler_is_loaded();
    }

    fn given_the_clock_said(&mut self, hour: u8, minute: u8) {
        self.when_the_clock_says(hour, minute);
    }

    fn given_a_folder_in_place_of_the_file(&self) {
        fs::create_dir_all(self.file()).unwrap();
    }

    fn when_the_scheduler_is_loaded(&mut self) {
        self.scheduler = Some(Scheduler::load(self.file()));
    }

    fn when_the_clock_says(&mut self, hour: u8, minute: u8) {
        self.change = self.scheduler().refresh(monday(hour, minute));
    }

    fn when_the_preferences_in_force_are_taken(&mut self) {
        self.effective = Some(self.scheduler.as_ref().unwrap().effective(&self.saved));
    }

    fn when_the_schedule_becomes(&mut self, rules: &[Rule], hour: u8, minute: u8) {
        self.change = self.scheduler().update(Schedule { rules: rules.to_vec() }, monday(hour, minute)).unwrap();
    }

    fn when_the_categories_are_deleted(&mut self, ids: &[u32]) {
        let deleted: HashSet<u32> = ids.iter().copied().collect();
        self.scheduler().forget_categories(&deleted).unwrap();
    }

    fn when_the_users_clock_is_read(&mut self) {
        self.clock = Some(scheduler::local_now());
    }

    fn then_the_change_is(&self, change: Option<Change>) {
        assert_eq!(self.change, change);
    }

    fn then_the_rule_in_force_is(&self, rule: Option<&Rule>) {
        assert_eq!(self.scheduler.as_ref().unwrap().active(), rule);
    }

    fn then_the_upload_limit_in_force_is(&self, kibps: u32) {
        assert_eq!(self.effective.as_ref().unwrap().max_upload_kibps, kibps);
    }

    fn then_the_saved_upload_limit_is(&self, kibps: u32) {
        assert_eq!(self.saved.max_upload_kibps, kibps);
    }

    fn then_the_rules_are(&self, rules: &[Rule]) {
        assert_eq!(self.scheduler.as_ref().unwrap().schedule().rules, rules);
    }

    fn then_the_saved_schedule_is(&self, rules: &[Rule]) {
        assert_eq!(schedule_codec::load(&self.file()).unwrap().rules, rules);
    }

    fn then_it_tells_the_local_time_of_the_system(&self) {
        let local = chrono::Local::now().naive_local().and_utc().timestamp();
        let told = self.clock.unwrap().assume_utc().unix_timestamp();
        assert!(local.abs_diff(told) <= 2, "{told} is not {local}");
    }

    fn scheduler(&mut self) -> &mut Scheduler {
        self.scheduler.as_mut().unwrap()
    }

    fn file(&self) -> std::path::PathBuf {
        self.profile.path().join(schedule_codec::FILE_NAME)
    }
}

// 9 November 2026 is a Monday.
fn monday(hour: u8, minute: u8) -> PrimitiveDateTime {
    PrimitiveDateTime::new(
        Date::from_calendar_date(2026, Month::November, 9).unwrap(),
        Time::from_hms(hour, minute, 0).unwrap(),
    )
}

fn night() -> Rule {
    Rule { upload_kibps: Some(7), ..every_day("night", 22, 6) }
}

fn late_night() -> Rule {
    Rule { upload_kibps: Some(5), ..every_day("late night", 23, 6) }
}

fn weekend() -> Rule {
    Rule { days: [DayOfWeek::Saturday, DayOfWeek::Sunday].into(), ..Rule::new("weekend") }
}

fn disabled(rule: Rule) -> Rule {
    Rule { enabled: false, ..rule }
}

// All week, all day.
fn stopping_and_resuming(stop: u32, resume: u32) -> Rule {
    Rule {
        days: DayOfWeek::VARIANTS.iter().copied().collect(),
        stop_category: Some(stop),
        resume_category: Some(resume),
        ..Rule::new("swap")
    }
}

fn stopping_and_resuming_none(resume: u32) -> Rule {
    Rule { stop_category: None, ..stopping_and_resuming(0, resume) }
}

fn every_day(name: &str, start: u8, end: u8) -> Rule {
    Rule {
        days: DayOfWeek::VARIANTS.iter().copied().collect(),
        start: Time::from_hms(start, 0, 0).unwrap(),
        end: Time::from_hms(end, 0, 0).unwrap(),
        ..Rule::new(name)
    }
}
