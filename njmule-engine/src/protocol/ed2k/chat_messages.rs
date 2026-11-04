// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Chat between clients: a message is `<u16 length><text>`; a client that wants proof of a human sends a CAPTCHA
//! picture (`<u8 tag count>[tags]<BMP>`), whose solution comes back as a message, and answers with a status byte.

use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter, StringEncoding};
use crate::protocol::packet::opcodes::{client, extended};
use crate::protocol::packet::{Packet, protocol};
use crate::protocol::tag::tag_codec::read_ed2k_list;

/// UTF-16 units a message has at most, as the Java engine counts its characters; longer ones are cut.
pub const MAX_LENGTH: usize = 450;
/// CAPTCHA status: solved.
pub const SOLVED: u8 = 0;
/// The largest CAPTCHA picture accepted; the ones clients make are about 150×50 pixels.
pub const MAX_WIDTH: u32 = 200;
pub const MAX_HEIGHT: u32 = 100;

const MIN_IMAGE: usize = 129;
const MAX_IMAGE: usize = 4095;
const MAX_TAGS: usize = 16;
// Offsets of the width and height in a BMP file's header.
const BMP_WIDTH: usize = 18;
const BMP_HEIGHT: usize = 22;

/// A message; `unicode` when the peer announced Unicode support.
pub fn message(text: &str, unicode: bool) -> Result<Packet, EncodeError> {
    let mut out = PacketWriter::new();
    out.string16(cut(text), if unicode { StringEncoding::Utf8 } else { StringEncoding::Utf8BomIfNeeded })?;
    Ok(Packet::written(protocol::EDONKEY, client::MESSAGE, out))
}

/// The text of a message, cut to [`MAX_LENGTH`].
pub fn text(packet: &Packet) -> Result<String, DecodeError> {
    let mut input = packet.reader();
    let text = input.string16()?;
    if input.has_remaining() {
        return Err(DecodeError::new(format!("a message with {} bytes after its text", input.remaining())));
    }
    Ok(cut(&text).to_owned())
}

// Never splits a character, so never half of a surrogate pair either.
fn cut(text: &str) -> &str {
    let mut units = 0usize;
    for (index, c) in text.char_indices() {
        units = units.saturating_add(c.len_utf16());
        if units > MAX_LENGTH {
            return text.get(..index).unwrap_or(text);
        }
    }
    text
}

/// The picture of a CAPTCHA request: a BMP file of a plausible size whose header declares at most
/// [`MAX_WIDTH`]×[`MAX_HEIGHT`] pixels, so that a small compressed file cannot unpack into a huge picture.
pub fn captcha(packet: &Packet) -> Result<Vec<u8>, DecodeError> {
    let mut input = packet.reader();
    let count = input.u8()?;
    read_ed2k_list(&mut input, u64::from(count), MAX_TAGS)?;
    let image = input.rest();
    if !(MIN_IMAGE..=MAX_IMAGE).contains(&image.len()) {
        return Err(DecodeError::new(format!("a CAPTCHA picture of {} bytes", image.len())));
    }
    let mut header = PacketReader::of(image);
    let magic = header.bytes(2)?;
    header.skip(BMP_WIDTH - 2)?;
    let width = header.i32()?;
    header.skip(BMP_HEIGHT - BMP_WIDTH - 4)?;
    let height = header.i32()?.unsigned_abs();
    let plausible =
        u32::try_from(width).is_ok_and(|w| (1..=MAX_WIDTH).contains(&w)) && (1..=MAX_HEIGHT).contains(&height);
    if magic != b"BM" || !plausible {
        return Err(DecodeError::new(format!("a CAPTCHA picture of {width}x{height} pixels")));
    }
    Ok(image.to_vec())
}

/// Whether a CAPTCHA result says solved.
pub fn is_solved(packet: &Packet) -> Result<bool, DecodeError> {
    Ok(packet.reader().u8()? == SOLVED)
}

/// A CAPTCHA request with `image`, as a peer sends it.
pub fn captcha_request(image: &[u8]) -> Packet {
    let mut out = PacketWriter::new();
    out.u8(0).bytes(image);
    Packet::written(protocol::EMULE, extended::CHATCAPTCHAREQ, out)
}

/// A CAPTCHA result, as a peer sends it.
pub fn captcha_result(status: u8) -> Packet {
    Packet::of(protocol::EMULE, extended::CHATCAPTCHARES, [status])
}
