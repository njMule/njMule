// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

#![no_main]

use libfuzzer_sys::fuzz_target;
use njmule_engine::persist::format::{
    cancelled_met, clients_met, friends_met, ip_filter_dat, kad_index_files, known_met, known2_met, nodes_dat,
    part_met, preferences_dat, preferences_kad_dat, search_files, server_met, text_files,
};

fuzz_target!(|data: &[u8]| {
    let Some((selector, payload)) = data.split_first() else {
        return;
    };
    match selector % 16 {
        0 => {
            let _ = server_met::read(payload);
        }
        1 => {
            let _ = known_met::read(payload);
            let _ = known_met::salvage(payload);
        }
        2 => {
            let _ = known2_met::read(payload);
            let _ = known2_met::read_old(payload);
            let _ = known2_met::salvage(payload);
        }
        3 => {
            let _ = part_met::read(payload);
        }
        4 => {
            let _ = nodes_dat::read(payload);
        }
        5 => {
            let _ = cancelled_met::read(payload);
        }
        6 => {
            let _ = clients_met::read(payload);
        }
        7 => {
            let _ = friends_met::read(payload);
        }
        8 => {
            let _ = ip_filter_dat::read(payload, "ipfilter.dat");
        }
        9 => {
            let _ = preferences_dat::read(payload);
        }
        10 => {
            let _ = preferences_kad_dat::read(payload);
        }
        11 => {
            let _ = kad_index_files::read_load(payload);
        }
        12 => {
            let _ = kad_index_files::read_sources(payload);
        }
        13 => {
            let _ = kad_index_files::read_keywords(payload);
        }
        14 => {
            let _ = search_files::read_stored(payload);
            let _ = search_files::read_spam(payload);
        }
        _ => {
            let _ = text_files::static_servers(payload);
            let _ = text_files::shared_files(payload);
        }
    }
});
