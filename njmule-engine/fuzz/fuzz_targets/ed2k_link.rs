// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

#![no_main]

use libfuzzer_sys::fuzz_target;
use njmule_engine::api::ed2k_link::Ed2kLink;

fuzz_target!(|data: &[u8]| {
    if let Ok(text) = std::str::from_utf8(data) {
        let _ = Ed2kLink::parse(text);
    }
});
