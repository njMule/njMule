// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

#![no_main]

use libfuzzer_sys::fuzz_target;
use njmule_engine::protocol::kad::kad_messages;
use njmule_engine::protocol::packet::{packing, udp_framing};

const MAX_UNPACKED: usize = 64 * 1024;

fuzz_target!(|data: &[u8]| {
    let Ok(packet) = udp_framing::decode(data) else {
        return;
    };
    let Ok(packet) = packing::unpack(packet, MAX_UNPACKED) else {
        return;
    };
    let _ = kad_messages::BootstrapAnswer::decode(&packet);
    let _ = kad_messages::Hello::decode(&packet);
    let _ = kad_messages::decode_hello_ack(&packet);
    let _ = kad_messages::Request::decode(&packet);
    let _ = kad_messages::Answer::decode(&packet);
    let _ = kad_messages::KeywordSearch::decode(&packet);
    let _ = kad_messages::SourceSearch::decode(&packet);
    let _ = kad_messages::NotesSearch::decode(&packet);
    let _ = kad_messages::SearchAnswer::decode(&packet);
    let _ = kad_messages::KeywordPublish::decode(&packet);
    let _ = kad_messages::SourcePublish::decode(&packet);
    let _ = kad_messages::NotesPublish::decode(&packet);
    let _ = kad_messages::PublishAnswer::decode(&packet);
    let _ = kad_messages::FirewallCheck::decode(&packet);
    let _ = kad_messages::decode_firewall_answer(&packet);
    let _ = kad_messages::FindBuddy::decode(&packet);
    let _ = kad_messages::BuddyOffer::decode(&packet);
    let _ = kad_messages::CallbackRequest::decode(&packet);
    let _ = kad_messages::FirewallUdp::decode(&packet);
    let _ = kad_messages::decode_pong(&packet);
});
