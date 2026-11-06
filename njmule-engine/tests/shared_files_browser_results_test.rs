// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's SharedFilesBrowserResultsTest, under its names: the files a client's list gives.

#![allow(clippy::unwrap_used)]

use njmule_engine::files::hash::ed2k;
use njmule_engine::peer::shared_files_browser;
use njmule_engine::protocol::io::{PacketWriter, StringEncoding};
use njmule_engine::protocol::packet::opcodes::client;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::tag_codec::write_ed2k_list;
use njmule_engine::protocol::tag::{Ed2kTagFormat, Tag, TagName, file_tag};

#[test]
fn should_leave_out_files_that_cannot_be_downloaded() {
    let answer = an_answer_listing(&[
        (1, "good.mkv", 1000),
        (2, " ", 1000),
        (3, "", 1000),
        (4, "empty.bin", 0),
        (5, "huge.bin", ed2k::MAX_FILE_SIZE + 1),
        (6, "largest.bin", ed2k::MAX_FILE_SIZE),
    ]);

    let files = shared_files_browser::files(&answer).unwrap();

    let names: Vec<&str> = files.iter().map(|file| file.name.as_str()).collect();
    assert_eq!(names, ["good.mkv", "largest.bin"]);
    assert!(files.iter().all(|file| file.link().unwrap().name == file.name));
}

fn an_answer_listing(files: &[(u8, &str, u64)]) -> Packet {
    let mut out = PacketWriter::new();
    out.u32(u32::try_from(files.len()).unwrap());
    for (seed, name, size) in files {
        let mut hash = [0u8; 16];
        hash[0] = *seed;
        let tags = [
            Tag::String { name: TagName::id(file_tag::FILENAME), value: (*name).to_owned() },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: *size },
        ];
        out.hash16(&hash).u32(0).u16(0).u32(2);
        write_ed2k_list(&mut out, &tags, Ed2kTagFormat::New, StringEncoding::Utf8).unwrap();
    }
    Packet::written(protocol::EDONKEY, client::ASKSHAREDFILESANSWER, out)
}
