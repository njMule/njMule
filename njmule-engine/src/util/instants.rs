// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Points in time with durations added, which saturate rather than overflow.

use std::time::{Duration, Instant};

/// `at` plus `by`, or `at` itself in the unlikely case the sum does not fit.
pub fn later(at: Instant, by: Duration) -> Instant {
    at.checked_add(by).unwrap_or(at)
}
