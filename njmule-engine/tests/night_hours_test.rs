// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's NightHoursTest, under their names.

#![allow(clippy::unwrap_used)]

use njmule_engine::config::night_hours::NightHours;
use time::Time;

fn at(hour: u8, minute: u8) -> Time {
    Time::from_hms(hour, minute, 0).unwrap()
}

#[test]
fn should_read_the_hours_as_written() {
    let hours = NightHours::parse(" 01:30 - 04:15 ");

    assert_eq!(hours, NightHours::new(at(1, 30), at(4, 15)));
}

#[test]
fn should_write_the_hours_as_they_are_read() {
    let hours = NightHours::parse("23:00-05:00").unwrap();

    let written = hours.to_string();

    assert_eq!(written, "23:00-05:00");
}

#[test]
fn should_hold_the_times_of_its_window_also_past_midnight() {
    for (window, time, expected) in [
        ("03:00-05:00", at(2, 59), false),
        ("03:00-05:00", at(3, 0), true),
        ("03:00-05:00", at(4, 59), true),
        ("03:00-05:00", at(5, 0), false),
        ("23:00-02:00", at(23, 30), true),
        ("23:00-02:00", at(1, 59), true),
        ("23:00-02:00", at(12, 0), false),
    ] {
        let hours = NightHours::parse(window).unwrap();

        assert_eq!(hours.holds(time), expected, "{window} at {time}");
    }
}

#[test]
fn should_refuse_what_are_not_hours_from_to() {
    for text in ["", "03:00", "03:00-05:00-07:00", "3-5", "03:00-03:00", "25:00-05:00"] {
        let hours = NightHours::parse(text);

        assert!(hours.is_err(), "{text:?}");
    }
}

#[test]
fn should_give_three_to_five_in_the_morning_by_default() {
    assert_eq!(NightHours::default().to_string(), "03:00-05:00");
}
