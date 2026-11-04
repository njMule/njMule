// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A client's comment on the file a downloader asked for: `<rating u8><u32 length><text>`, sent after the file name to
//! clients that take comments.

use crate::api::file_comment::FileComment;
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::io::{DecodeError, EncodeError, PacketWriter, StringEncoding, ed2k_strings};
use crate::protocol::packet::opcodes::extended;
use crate::protocol::packet::{Packet, protocol};

// UTF-8 may take four bytes a character.
const MAX_BYTES: usize = FileComment::MAX_LENGTH * 4;

pub fn encode(comment: &FileComment) -> Result<Packet, EncodeError> {
    let text = ed2k_strings::encode(comment.text(), StringEncoding::Utf8);
    let mut out = PacketWriter::new();
    out.u8(comment.rating()).count32(text.len())?.bytes(&text);
    Ok(Packet::written(protocol::EMULE, extended::FILEDESC, out))
}

/// The comment, made fit: a longer text is cut, a rating out of range kept within it.
pub fn decode(packet: &Packet) -> Result<FileComment, DecodeError> {
    let mut input = packet.reader();
    let rating = input.u8()?;
    let length = input.u32()?;
    let length = usize::try_from(length)
        .ok()
        .filter(|n| *n <= input.remaining())
        .ok_or_else(|| DecodeError::new(format!("a comment of {length} bytes in {}", input.remaining())))?;
    let text = input.bytes(length.min(MAX_BYTES))?;
    Ok(FileComment::of(&ed2k_strings::decode(text), rating))
}

/// Whether the client of `hello` takes comments.
pub fn accepted(hello: &Hello) -> bool {
    hello.misc_options1() >> 4 & 0x0F != 0
}
