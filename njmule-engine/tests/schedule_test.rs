// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ScheduleTest, under its names, and how times are read and written.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use std::collections::BTreeSet;
use std::fs;

use njmule_engine::config::preferences::Preferences;
use njmule_engine::config::schedule::{self, DayOfWeek, Rule, Schedule};
use njmule_engine::persist::schedule_codec;
use time::{Date, Month, PrimitiveDateTime, Time};

// `2026-10-12T12:00`; 2026-10-12 is a Monday.
fn at(text: &str) -> PrimitiveDateTime {
    let (date, time) = text.split_once('T').unwrap();
    let mut parts = date.split('-').map(|part| part.parse::<u16>().unwrap());
    let (year, month, day) = (parts.next().unwrap(), parts.next().unwrap(), parts.next().unwrap());
    let date = Date::from_calendar_date(
        year.into(),
        Month::try_from(u8::try_from(month).unwrap()).unwrap(),
        u8::try_from(day).unwrap(),
    );
    PrimitiveDateTime::new(date.unwrap(), schedule::parse_time(time).unwrap())
}

fn time(text: &str) -> Time {
    schedule::parse_time(text).unwrap()
}

fn rule(name: &str, days: &[DayOfWeek], start: &str, end: &str) -> Rule {
    Rule {
        days: days.iter().copied().collect(),
        start: time(start),
        end: time(end),
        upload_kibps: Some(10),
        ..Rule::new(name)
    }
    .validated()
    .unwrap()
}

fn every_day() -> Vec<DayOfWeek> {
    use strum::VariantArray;
    DayOfWeek::VARIANTS.to_vec()
}

fn without_days_and_the_limits(upload: u32, download: u32, connections: u32, sources: u32) -> Rule {
    Rule {
        start: Time::MIDNIGHT,
        end: time("12:00"),
        upload_kibps: Some(upload),
        download_kibps: Some(download),
        max_connections: Some(connections),
        max_sources_per_file: Some(sources),
        ..Rule::new("slow")
    }
    .validated()
    .unwrap()
}

#[test]
fn should_hold_an_office_window_on_its_days_and_hours() {
    use DayOfWeek::{Friday, Monday, Thursday, Tuesday, Wednesday};
    let office = rule("office", &[Monday, Tuesday, Wednesday, Thursday, Friday], "09:00", "18:00");
    for (now, expected) in [
        ("2026-10-12T12:00", true),
        ("2026-10-12T18:00", false),
        ("2026-10-12T09:00", true),
        ("2026-10-11T12:00", false),
    ] {
        assert_eq!(office.holds(at(now)), expected, "{now}");
    }
}

#[test]
fn should_hold_a_night_window_across_midnight() {
    let night = rule("night", &[DayOfWeek::Sunday], "23:00", "07:00");
    for (now, expected) in [
        ("2026-10-11T23:30", true),
        ("2026-10-12T06:00", true),
        ("2026-10-12T07:00", false),
        ("2026-10-11T06:00", false),
    ] {
        assert_eq!(night.holds(at(now)), expected, "{now}");
    }
}

#[test]
fn should_hold_a_window_from_midnight_to_midnight_all_its_day() {
    let saturday = rule("saturday", &[DayOfWeek::Saturday], "00:00", "00:00");
    for (now, expected) in [("2026-10-10T03:00", true), ("2026-10-12T12:00", false)] {
        assert_eq!(saturday.holds(at(now)), expected, "{now}");
    }
}

#[test]
fn should_let_the_first_enabled_rule_that_holds_win() {
    let off =
        Rule { enabled: false, days: every_day().into_iter().collect(), upload_kibps: Some(1), ..Rule::new("off") };
    let schedule = Schedule {
        rules: vec![
            off,
            rule("office", &every_day(), "09:00", "18:00"),
            rule("always", &every_day(), "00:00", "00:00"),
        ],
    };
    for (now, expected) in [("2026-10-12T12:00", "office"), ("2026-10-12T20:00", "always")] {
        let active = schedule.active(at(now));

        assert_eq!(active.map(|rule| rule.name.as_str()), Some(expected), "{now}");
    }
}

#[test]
fn should_find_no_active_rule_in_an_empty_schedule() {
    let active = Schedule::default().active(at("2026-10-12T12:00")).cloned();

    assert_eq!(active, None);
}

#[test]
fn should_change_only_its_own_values() {
    let preferences = Preferences { nick: "me".to_owned(), ..Preferences::default() };

    let applied = without_days_and_the_limits(5, 50, 100, 20).apply_to(&preferences);

    assert_eq!(
        (applied.max_upload_kibps, applied.max_download_kibps, applied.max_connections, applied.max_sources_per_file),
        (5, 50, 100, 20)
    );
    assert_eq!(applied.nick, "me");
}

#[test]
fn should_not_hold_a_rule_without_days() {
    let holds = without_days_and_the_limits(5, 50, 100, 20).holds(at("2026-10-12T12:00"));

    assert!(!holds, "no days");
}

#[test]
fn should_reject_a_rule_without_connections() {
    let refused =
        Rule { max_connections: Some(0), start: time("12:00"), end: time("12:00"), ..Rule::new("x") }.validated();

    assert!(refused.unwrap_err().contains("maxConnections"));
}

#[test]
fn should_survive_the_file() {
    let directory = tempfile::tempdir().unwrap();
    let file = directory.path().join(schedule_codec::FILE_NAME);
    let off = Rule {
        enabled: false,
        start: Time::MIDNIGHT,
        end: time("12:00"),
        download_kibps: Some(0),
        max_sources_per_file: Some(10),
        stop_category: Some(3),
        resume_category: Some(0),
        ..Rule::new("off")
    };
    let saved = Schedule {
        rules: vec![rule("Night = slow", &[DayOfWeek::Friday, DayOfWeek::Saturday], "23:30", "06:15"), off],
    };

    schedule_codec::save(&file, &saved).unwrap();
    let loaded = schedule_codec::load(&file).unwrap();

    assert_eq!(loaded, saved);
}

#[test]
fn should_load_an_empty_schedule_without_a_file() {
    let directory = tempfile::tempdir().unwrap();

    let loaded = schedule_codec::load(&directory.path().join("none")).unwrap();

    assert_eq!(loaded, Schedule::default());
}

#[test]
fn should_leave_broken_rules_out() {
    let directory = tempfile::tempdir().unwrap();
    let file = directory.path().join(schedule_codec::FILE_NAME);
    fs::write(
        &file,
        "rule.1.name=good\nrule.1.days=MONDAY\nrule.1.start=01:00\nrule.1.end=02:00\nrule.2.name=bad\n\
         rule.2.start=25:00\nrule.3.name=worse\nrule.3.days=FUNDAY\nrule.x.name=odd\nrule.4.name=slow\n\
         rule.4.maxConnections=0\nrule.5.name=late\nrule.5.uploadKiBps=-1\n",
    )
    .unwrap();

    let loaded = schedule_codec::load(&file).unwrap();

    let names: Vec<&str> = loaded.rules.iter().map(|rule| rule.name.as_str()).collect();
    assert_eq!(names, ["good"]);
}

#[test]
fn should_read_and_write_times_as_the_java_engine_does() {
    for (text, written) in [
        ("07:05", "07:05"),
        ("07:05:00", "07:05"),
        ("23:59:59", "23:59:59"),
        ("00:00:01.5", "00:00:01.500"),
        ("12:00:00.000250", "12:00:00.000250"),
        ("12:00:00.000000007", "12:00:00.000000007"),
        ("12:00:30.1234", "12:00:30.123400"),
    ] {
        assert_eq!(schedule::time_text(time(text)), written, "{text}");
    }
    for wrong in
        ["7:05", "07:5", "24:00", "07:60", "07:05:60", "07:05:00.", "07:05:00.1234567890", "07", "07:05:00:00", "ab:cd"]
    {
        assert!(schedule::parse_time(wrong).is_err(), "{wrong}");
    }
}

#[test]
fn should_write_the_days_from_monday_and_read_them_in_any_case() {
    let directory = tempfile::tempdir().unwrap();
    let file = directory.path().join(schedule_codec::FILE_NAME);
    fs::write(&file, "rule.1.days= sunday ,,Monday\nrule.1.enabled=yes\n").unwrap();

    let loaded = schedule_codec::load(&file).unwrap();
    schedule_codec::save(&file, &loaded).unwrap();

    let rule = &loaded.rules[0];
    assert_eq!(rule.days, BTreeSet::from([DayOfWeek::Monday, DayOfWeek::Sunday]));
    assert!(!rule.enabled, "only true is true");
    assert!(fs::read_to_string(&file).unwrap().contains("rule.1.days=MONDAY,SUNDAY\n"));
}
