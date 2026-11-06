// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ExemptConnectionsTest, under its names, on the system's clock.

#![allow(clippy::unwrap_used)]

use std::sync::Arc;
use std::time::{Duration, Instant};

use njmule_engine::security::connection_guard::{Admission, ConnectionGuard, Limits};
use njmule_engine::security::ip_filter::IpFilter;

const CHECKER: u32 = 0x0707_0707;

#[test]
fn should_let_exempt_connections_leave_the_accept_rate_to_the_others() {
    let start = Instant::now();
    let limits = Limits::new(100, 5, 1, Duration::from_secs(5)).unwrap();
    let guard = ConnectionGuard::new(
        || Arc::new(IpFilter::empty()),
        |ip| ip == CHECKER,
        |_| false,
        move || limits,
        move || start.elapsed(),
    );
    let checks = [guard.admit_v4(CHECKER), guard.admit_v4(CHECKER)];
    assert!(checks.iter().all(|check| matches!(check, Admission::Admitted(_))));

    let admission = guard.admit_v4(0x0102_0304);

    assert!(matches!(admission, Admission::Admitted(_)), "the one connection a window allows is still free");
}
