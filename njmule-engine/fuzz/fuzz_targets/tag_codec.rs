// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

#![no_main]

use libfuzzer_sys::fuzz_target;
use njmule_engine::protocol::io::PacketReader;
use njmule_engine::protocol::tag::tag_codec;

const MAX_TAGS: usize = 256;

fuzz_target!(|data: &[u8]| {
    let mut ed2k = PacketReader::of(data);
    let _ = tag_codec::read_ed2k(&mut ed2k);

    let mut ed2k_list = PacketReader::of(data);
    if let Ok(count) = ed2k_list.u32() {
        let _ = tag_codec::read_ed2k_list(&mut ed2k_list, u64::from(count), MAX_TAGS);
    }

    let mut kad = PacketReader::of(data);
    let _ = tag_codec::read_kad(&mut kad);

    let mut kad_list = PacketReader::of(data);
    let _ = tag_codec::read_kad_list(&mut kad_list);
});
