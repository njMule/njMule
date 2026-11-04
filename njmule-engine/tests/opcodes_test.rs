// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's OpcodesTest, under their names: opcode tables and frame limits.

use njmule_engine::protocol::packet::frame_limits::{self, CLIENT_MAX, DATA_MAX, HANDSHAKE_MAX, SHARED_FILES_MAX};
use njmule_engine::protocol::packet::opcodes::{client, extended, kad, server};
use njmule_engine::protocol::packet::protocol;

#[test]
fn should_look_up_a_client_opcode() {
    assert_eq!(client::name(0x01), Some("HELLO"));
}

#[test]
fn should_look_up_an_extended_opcode() {
    assert_eq!(extended::name(0x01), Some("EMULEINFO"));
}

#[test]
fn should_look_up_a_server_opcode() {
    assert_eq!(server::name(0x01), Some("LOGINREQUEST"));
}

#[test]
fn should_look_up_a_kad_opcode() {
    assert_eq!(kad::name(0x3B), Some("SEARCH_RES"));
}

#[test]
fn should_find_no_client_opcode_for_an_unknown_code() {
    assert_eq!(client::name(0x02), None);
}

#[test]
fn should_accept_a_small_hello_during_the_handshake() {
    assert_eq!(frame_limits::client_handshake(protocol::EDONKEY, client::HELLO), Some(HANDSHAKE_MAX));
}

#[test]
fn should_reject_data_during_the_handshake() {
    assert_eq!(frame_limits::client_handshake(protocol::EDONKEY, client::SENDINGPART), None);
}

#[test]
fn should_reject_a_packed_hello_during_the_handshake() {
    assert_eq!(frame_limits::client_handshake(protocol::PACKED, client::HELLO), None);
}

#[test]
fn should_cap_the_data_of_an_identified_client() {
    assert_eq!(frame_limits::client(protocol::EMULE, extended::COMPRESSEDPART), Some(DATA_MAX));
}

#[test]
fn should_cap_the_shared_files_answer_of_an_identified_client() {
    assert_eq!(frame_limits::client(protocol::EDONKEY, client::ASKSHAREDFILESANSWER), Some(SHARED_FILES_MAX));
}

#[test]
fn should_cap_other_messages_of_an_identified_client() {
    assert_eq!(frame_limits::client(protocol::EDONKEY, client::MESSAGE), Some(CLIENT_MAX));
}
