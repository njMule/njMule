// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's NotesMessagesTest, under their names: publishing and searching the notes on a
//! file.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::kad::kad_messages::{NotesPublish, NotesSearch};
use njmule_engine::protocol::packet::opcodes::kad;
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};

const FILE: UInt128 = UInt128(0x0102_0304_0506_0708_090A_0B0C_0D0E_0F10);
const PUBLISHER: UInt128 = UInt128(0x1112_1314_1516_1718_191A_1B1C_1D1E_1F20);

#[test]
fn should_write_a_note_publish_as_file_publisher_and_tags() {
    let sent = NotesPublish {
        file: FILE,
        publisher: PUBLISHER,
        tags: vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: "a".into() },
            Tag::Int { name: TagName::id(file_tag::FILERATING), value: 5 },
        ],
    };

    let packet = sent.encode().unwrap();

    assert_eq!(packet.opcode(), kad::PUBLISH_NOTES_REQ);
    assert_eq!(
        hex::encode(packet.payload()),
        [
            "0403020108070605",
            "0c0b0a09100f0e0d",
            "1413121118171615",
            "1c1b1a19201f1e1d",
            "02",
            "02010001010061",
            "090100f705",
        ]
        .concat()
    );
    assert_eq!(NotesPublish::decode(&packet), Ok(sent));
}

#[test]
fn should_write_a_notes_search_as_file_and_size() {
    let packet = NotesSearch { target: FILE, size: 1_000 }.encode();

    assert_eq!(packet.opcode(), kad::SEARCH_NOTES_REQ);
    assert_eq!(hex::encode(packet.payload()), "04030201080706050c0b0a09100f0e0de803000000000000");
}
