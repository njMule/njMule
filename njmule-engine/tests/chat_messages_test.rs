// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's ChatMessagesTest, under their names: messages cut at the limit and CAPTCHA
//! pictures.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use njmule_engine::protocol::ed2k::chat_messages::{self, MAX_LENGTH};
use njmule_engine::protocol::io::{PacketWriter, StringEncoding};
use njmule_engine::protocol::packet::opcodes::client;
use njmule_engine::protocol::packet::{Packet, protocol};

const EMOJI: &str = "😀";

fn bmp(size: usize, width: i32, height: i32) -> Vec<u8> {
    let mut image = vec![0u8; size];
    image[0] = b'B';
    image[1] = b'M';
    image[18..22].copy_from_slice(&width.to_le_bytes());
    image[22..26].copy_from_slice(&height.to_le_bytes());
    image
}

fn sent(text: &str, unicode: bool) -> String {
    chat_messages::text(&chat_messages::message(text, unicode).unwrap()).unwrap()
}

#[test]
fn should_never_end_a_cut_outgoing_message_in_half_a_character() {
    for unicode in [true, false] {
        let text = format!("{}{EMOJI}tail", "a".repeat(MAX_LENGTH - 1));

        assert_eq!(sent(&text, unicode), "a".repeat(MAX_LENGTH - 1), "unicode {unicode}");
    }
}

#[test]
fn should_never_end_a_cut_received_message_in_half_a_character() {
    let mut out = PacketWriter::new();
    out.string16(&format!("{}{EMOJI}tail", "a".repeat(MAX_LENGTH - 1)), StringEncoding::Utf8).unwrap();

    let read = chat_messages::text(&Packet::written(protocol::EDONKEY, client::MESSAGE, out)).unwrap();

    assert_eq!(read, "a".repeat(MAX_LENGTH - 1));
}

#[test]
fn should_pass_a_message_up_to_the_limit_whole() {
    let text = format!("{}{EMOJI}", "b".repeat(MAX_LENGTH - 2));

    assert_eq!(sent(&text, true), text);
}

#[test]
fn should_cut_a_longer_message_to_the_limit() {
    assert_eq!(sent(&"x".repeat(451), true).len(), MAX_LENGTH);
}

#[test]
fn should_accept_a_captcha_picture_of_plausible_size_and_dimensions() {
    for (size, width, height) in [(129, 150, 50), (4095, 200, -100)] {
        let picture = chat_messages::captcha(&chat_messages::captcha_request(&bmp(size, width, height)));

        assert_eq!(picture.map(|p| p.len()), Ok(size), "{size} bytes, {width}x{height}");
    }
}

#[test]
fn should_refuse_a_captcha_picture_of_implausible_size_or_dimensions() {
    for (size, width, height) in
        [(128, 150, 50), (4096, 150, 50), (200, 201, 50), (200, 0, 50), (200, 150, 101), (200, 150, i32::MIN)]
    {
        let picture = chat_messages::captcha(&chat_messages::captcha_request(&bmp(size, width, height)));

        assert!(picture.is_err(), "{size} bytes, {width}x{height}");
    }
}

#[test]
fn should_refuse_a_captcha_picture_that_is_not_a_bitmap() {
    let mut image = bmp(200, 150, 50);
    image[0] = b'X';

    assert!(chat_messages::captcha(&chat_messages::captcha_request(&image)).is_err());
}

// Garbage is read or refused as malformed: never a panic.
proptest::proptest! {
    #[test]
    fn should_only_fail_with_decode_exception_on_garbage_read_as_a_captcha(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=300)) {
        let _ = chat_messages::captcha(&Packet::of(protocol::EMULE, 0xA5, data));
    }

    #[test]
    fn should_only_fail_with_decode_exception_on_garbage_read_as_a_message(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=300)) {
        let _ = chat_messages::text(&Packet::of(protocol::EDONKEY, client::MESSAGE, data));
    }
}
