// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

#![no_main]

use std::io::Cursor;

use libfuzzer_sys::fuzz_target;
use njmule_engine::protocol::ed2k::hello::Hello;
use njmule_engine::protocol::ed2k::{client_messages, server_messages};
use njmule_engine::protocol::packet::{Packet, frame_limits, packing, tcp_framing, udp_framing};

const MAX_UNPACKED: usize = 256 * 1024;

fn decode_packet(packet: &Packet) {
    let _ = Hello::decode(packet);
    let _ = server_messages::Login::decode(packet);
    let _ = server_messages::IdChange::decode(packet);
    let _ = server_messages::ServerStatus::decode(packet);
    let _ = server_messages::ServerIdent::decode(packet);
    let _ = server_messages::SearchResults::decode(packet);
    let _ = server_messages::CallbackRequested::decode(packet);
    let _ = server_messages::FoundSources::decode(packet);
    let _ = server_messages::decode_message(packet);
    let _ = server_messages::decode_server_list(packet);
    let _ = client_messages::FileName::decode(packet);
    let _ = client_messages::FileStatus::decode(packet);
    let _ = client_messages::HashSet::decode(packet);
    let _ = client_messages::RequestParts::decode(packet);
    let _ = client_messages::SendingPart::decode(packet);
    let _ = client_messages::CompressedPart::decode(packet);
    let _ = client_messages::decode_queue_rank(packet);
}

fuzz_target!(|data: &[u8]| {
    let mut cursor = Cursor::new(data);
    while let Ok(Some(packet)) = tcp_framing::read(&mut cursor, &frame_limits::client) {
        if let Ok(unpacked) = packing::unpack(packet, MAX_UNPACKED) {
            decode_packet(&unpacked);
        }
    }
    if let Ok(packet) = udp_framing::decode(data)
        && let Ok(unpacked) = packing::unpack(packet, MAX_UNPACKED)
    {
        decode_packet(&unpacked);
    }
});
