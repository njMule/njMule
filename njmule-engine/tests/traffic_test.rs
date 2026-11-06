// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's TrafficTest, under its names. The counters are shared by the whole process, so the tests look at
//! what they grew by.

#![allow(clippy::arithmetic_side_effects)]

use njmule_engine::net::traffic;

#[test]
fn should_count_as_overhead_the_bytes_sent_besides_file_data() {
    let before = traffic::overhead_sent();

    traffic::sent(100, false);

    assert!(traffic::overhead_sent() - before >= 100);
}

#[test]
fn should_leave_file_data_out_of_the_overhead_sent() {
    let before = traffic::overhead_sent();

    traffic::sent(10_240, true);

    assert!(traffic::overhead_sent().saturating_sub(before) < 10_240);
}

#[test]
fn should_count_all_the_bytes_received() {
    let before = traffic::received_bytes();

    traffic::received(1_500);

    assert!(traffic::received_bytes() - before >= 1_500);
}
