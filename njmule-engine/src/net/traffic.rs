// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The bytes of the eD2K and Kad packets this process sent and received, over TCP and UDP, with those of the file data
//! it sent apart: what the statistics tell as overhead, besides the files themselves. The counters belong to the
//! process, as the network itself does.

use std::sync::atomic::{AtomicU64, Ordering};

static SENT_BYTES: AtomicU64 = AtomicU64::new(0);
static FILE_DATA_BYTES: AtomicU64 = AtomicU64::new(0);
static RECEIVED_BYTES: AtomicU64 = AtomicU64::new(0);

/// Counts a packet sent, of file data or not.
pub fn sent(bytes: usize, file_data: bool) {
    let bytes = u64::try_from(bytes).unwrap_or(u64::MAX);
    SENT_BYTES.fetch_add(bytes, Ordering::SeqCst);
    if file_data {
        FILE_DATA_BYTES.fetch_add(bytes, Ordering::SeqCst);
    }
}

/// Counts a packet received.
pub fn received(bytes: usize) {
    RECEIVED_BYTES.fetch_add(u64::try_from(bytes).unwrap_or(u64::MAX), Ordering::Relaxed);
}

/// The bytes sent besides file data.
pub fn overhead_sent() -> u64 {
    // File data first: what it counts was counted as sent before, so the difference never falls short.
    let file_data = FILE_DATA_BYTES.load(Ordering::SeqCst);
    SENT_BYTES.load(Ordering::SeqCst).saturating_sub(file_data)
}

/// All the bytes received, file data included.
pub fn received_bytes() -> u64 {
    RECEIVED_BYTES.load(Ordering::Relaxed)
}
