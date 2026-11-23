// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

#![no_main]

use libfuzzer_sys::fuzz_target;
use njmule_engine::protocol::io::PacketReader;
use njmule_engine::search::{search_parser, search_tree_codec};

fuzz_target!(|data: &[u8]| {
    if let Ok(text) = std::str::from_utf8(data) {
        let _ = search_parser::parse(text);
    }
    let mut reader = PacketReader::of(data);
    let _ = search_tree_codec::read(&mut reader);
});
