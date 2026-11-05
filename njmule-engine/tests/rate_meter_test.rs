// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's RateMeterTest, under their names, with the clock in the test's hands.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]

use njmule_engine::util::rate_meter::RateMeter;

const SECOND: i64 = 1_000_000_000;
const START: i64 = 100 * SECOND;

fn meter(seconds: usize) -> RateMeter {
    RateMeter::new(seconds).unwrap()
}

// A second counts once it is over, then for four seconds: the second under way would make the average dip.
#[test]
fn should_average_the_last_seconds() {
    for (seconds_later, expected) in [(1, 1000), (2, 2000), (4, 2000), (5, 1000), (6, 0)] {
        let mut meter = meter(4);
        meter.add(4_000, START);
        meter.add(4_000, START + SECOND);

        let rate = meter.rate(START + seconds_later * SECOND);

        assert_eq!(rate, expected, "{seconds_later} s later");
    }
}

#[test]
fn should_keep_the_rate_while_a_second_has_just_begun() {
    let mut meter = meter(2);
    meter.add(1_000, 10 * SECOND);
    meter.add(1_000, 11 * SECOND);
    meter.add(50, 12 * SECOND);

    let rate = meter.rate(12 * SECOND + SECOND / 10);

    assert_eq!(rate, 1_000);
}

#[test]
fn should_count_every_byte_in_the_total() {
    let mut meter = meter(4);
    meter.add(4_000, START);

    meter.add(4_000, START + SECOND);

    assert_eq!(meter.total(), 8_000);
}

#[test]
fn should_clear_the_window_after_a_long_pause() {
    let mut meter = meter(3);
    meter.add(900, 0);

    meter.add(300, 60 * SECOND);

    assert_eq!(meter.rate(61 * SECOND), 100);
    assert_eq!(meter.total(), 1_200);
}

#[test]
fn should_count_bytes_of_a_clock_going_backwards_in_the_latest_second() {
    let mut meter = meter(2);
    meter.add(1_000, 10 * SECOND);

    meter.add(1_000, 5 * SECOND);

    assert_eq!(meter.rate(11 * SECOND), 1_000);
    assert_eq!(meter.rate(13 * SECOND), 0);
    assert_eq!(meter.total(), 2_000);
}

#[test]
fn should_average_across_the_crossing_of_zero() {
    let mut meter = meter(2);
    meter.add(400, -SECOND / 2);

    meter.add(600, SECOND / 2);

    assert_eq!(meter.rate(SECOND + SECOND / 2), 500);
    assert_eq!(meter.rate(i64::MAX), 0);
}

#[test]
fn should_count_bytes_added_at_the_most_negative_times() {
    let mut meter = meter(2);
    meter.add(400, -SECOND / 2);
    meter.add(600, SECOND / 2);
    meter.rate(i64::MAX);

    meter.add(10, i64::MIN + 1);

    assert_eq!(meter.total(), 1_010);
}

#[test]
fn should_need_a_window() {
    let made = RateMeter::new(0);

    assert!(made.is_none());
}
