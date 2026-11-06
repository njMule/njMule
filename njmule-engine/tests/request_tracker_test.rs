// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's RequestTrackerTest, under its names: how each client program used our queue lately.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]

use std::net::{IpAddr, Ipv4Addr};
use std::time::{Duration, Instant};

use njmule_engine::upload::request_tracker::{Key, RequestTracker};

const MIN_ASK: Duration = Duration::from_secs(10 * 60);
const WINDOW: Duration = Duration::from_secs(4 * 3600);
const FILE: [u8; 16] = [0x11; 16];
const CLIENT: Key = Key { ip: IpAddr::V4(Ipv4Addr::LOCALHOST), port: 4662 };
const OTHER_PROGRAM: Key = Key { ip: IpAddr::V4(Ipv4Addr::LOCALHOST), port: 4663 };

struct Tracking {
    tracker: RequestTracker,
    now: Instant,
}

impl Tracking {
    fn new() -> Self {
        Self { tracker: RequestTracker::new(MIN_ASK, WINDOW), now: Instant::now() }
    }

    fn asked_after(&mut self, client: Key, wait: Duration) -> u32 {
        self.now += wait;
        self.tracker.asked(client, FILE, self.now)
    }

    fn idle_slots(&mut self, slots: u32) {
        for _ in 0..slots {
            self.now += Duration::from_secs(60);
            self.tracker.idle_slot(CLIENT, self.now);
        }
    }
}

#[test]
fn should_not_count_a_re_ask_just_short_of_the_shortest_time() {
    let mut tracking = Tracking::new();
    tracking.asked_after(CLIENT, Duration::ZERO);

    let count = tracking.asked_after(CLIENT, MIN_ASK - Duration::from_secs(5));

    assert_eq!(count, 0);
}

#[test]
fn should_count_the_asks_of_another_program_on_the_same_address_apart() {
    let mut tracking = Tracking::new();
    tracking.asked_after(CLIENT, Duration::ZERO);
    tracking.asked_after(CLIENT, MIN_ASK / 2);
    tracking.asked_after(CLIENT, MIN_ASK / 2);
    tracking.asked_after(OTHER_PROGRAM, Duration::ZERO);

    let count = tracking.asked_after(OTHER_PROGRAM, MIN_ASK / 2);

    assert_eq!(count, 1);
}

#[test]
fn should_no_longer_count_idle_slots_outside_the_window() {
    let mut tracking = Tracking::new();
    tracking.idle_slots(3);
    tracking.now += WINDOW;

    let count = tracking.tracker.idle_slot(CLIENT, tracking.now);

    assert_eq!(count, 1);
}

#[test]
fn should_forget_a_client_not_heard_of_within_the_window() {
    let mut tracking = Tracking::new();
    tracking.idle_slots(2);
    tracking.now += WINDOW;

    tracking.tracker.purge(tracking.now);

    assert!(tracking.tracker.is_empty());
}

// Beyond the Java engine's examples: the branches its other tests reach.

#[test]
fn should_count_the_drops_for_unknown_files_by_ipv6_slash64_for_two_hours() {
    let mut tracking = Tracking::new();
    let home: IpAddr = "2001:db8:1:2::1".parse().unwrap();
    let same_home: IpAddr = "2001:db8:1:2::99".parse().unwrap();
    tracking.tracker.unknown_files(home, tracking.now);

    let again = tracking.tracker.unknown_files(same_home, tracking.now);
    let later = tracking.tracker.unknown_files(home, tracking.now + Duration::from_secs(2 * 3600));

    assert_eq!((again, later), (2, 1));
}

#[test]
fn should_forgive_the_idle_slots_of_a_client_that_took_data() {
    let mut tracking = Tracking::new();
    tracking.idle_slots(2);
    tracking.tracker.productive(CLIENT);
    tracking.now += Duration::from_secs(60);

    let count = tracking.tracker.idle_slot(CLIENT, tracking.now);

    assert_eq!(count, 1);
    assert_eq!(tracking.tracker.len(), 1);
}

#[test]
fn should_keep_the_asks_of_the_most_files_and_drop_the_oldest() {
    let mut tracking = Tracking::new();
    for seed in 0..=32u8 {
        tracking.tracker.asked(CLIENT, [seed; 16], tracking.now);
    }

    let again = tracking.tracker.asked(CLIENT, [0; 16], tracking.now + Duration::from_secs(1));

    assert_eq!(again, 0, "the oldest file was dropped, so it is a first ask again");
}
