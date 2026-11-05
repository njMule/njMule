// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The media details a search result carries in its tags: artist, album, title, length, bitrate and codec, under their
//! numeric ids or, as some servers send them, under their names.

use crate::api::search_result::Media;
use crate::protocol::tag::{Tag, TagName, file_tag};

const ARTIST: &str = "artist";
const ALBUM: &str = "album";
const TITLE: &str = "title";
const LENGTH: &str = "length";
const BITRATE: &str = "bitrate";
const CODEC: &str = "codec";
const MAX_DURATION_TEXT: usize = 64;
const MAX_DIGITS: usize = 9;
/// The largest number a detail holds: those beyond, unsigned on the wire, are taken as it.
pub const MAX_NUMBER: u32 = i32::MAX as u32;

/// The media details of `tags`; none for a file without tags.
pub fn read(tags: &[Tag]) -> Media {
    if tags.is_empty() {
        return Media::default();
    }
    Media::new(
        &text(tags, file_tag::MEDIA_ARTIST, ARTIST),
        &text(tags, file_tag::MEDIA_ALBUM, ALBUM),
        &text(tags, file_tag::MEDIA_TITLE, TITLE),
        length(tags),
        number(tags, file_tag::MEDIA_BITRATE, BITRATE),
        &text(tags, file_tag::MEDIA_CODEC, CODEC),
    )
}

/// The tags that carry `media`, with their numeric ids.
pub fn of(media: &Media) -> Vec<Tag> {
    let mut tags = Vec::with_capacity(6);
    add_text(&mut tags, file_tag::MEDIA_ARTIST, &media.artist);
    add_text(&mut tags, file_tag::MEDIA_ALBUM, &media.album);
    add_text(&mut tags, file_tag::MEDIA_TITLE, &media.title);
    if media.length > 0 {
        tags.push(Tag::Int { name: TagName::id(file_tag::MEDIA_LENGTH), value: u64::from(media.length) });
    }
    if media.bitrate > 0 {
        tags.push(Tag::Int { name: TagName::id(file_tag::MEDIA_BITRATE), value: u64::from(media.bitrate) });
    }
    add_text(&mut tags, file_tag::MEDIA_CODEC, &media.codec);
    tags
}

/// Seconds from `h:m:s`, `m:s` or `s` at the start of `text`, whatever follows; 0 when the text is none of them.
/// Numbers of ten digits or more and texts longer than 64 characters are no lengths. Read by hand: Rust's regex has
/// no look-ahead to tell a number of nine digits from the start of a longer one.
pub fn duration(text: &str) -> u32 {
    if text.chars().count() > MAX_DURATION_TEXT {
        return 0;
    }
    let mut rest = text.trim_start();
    let mut seconds: u64 = 0;
    for field in 0..3 {
        if field > 0 {
            let Some(after) = rest.trim_start().strip_prefix(':') else {
                break;
            };
            let after = after.trim_start();
            if !after.starts_with(|c: char| c.is_ascii_digit()) {
                break;
            }
            rest = after;
        }
        let digits = rest.chars().take_while(char::is_ascii_digit).count();
        if digits == 0 || digits > MAX_DIGITS {
            return if field == 0 { 0 } else { clamp(seconds) };
        }
        let (number, after) = rest.split_at(digits);
        seconds = seconds.saturating_mul(60).saturating_add(number.parse::<u64>().unwrap_or(0));
        rest = after;
    }
    clamp(seconds)
}

fn add_text(tags: &mut Vec<Tag>, id: u8, value: &str) {
    if !value.is_empty() {
        tags.push(Tag::String { name: TagName::id(id), value: value.to_owned() });
    }
}

fn text(tags: &[Tag], id: u8, name: &str) -> String {
    tags.iter()
        .find_map(|tag| match tag {
            Tag::String { name: tag_name, value } if !value.trim().is_empty() && named(tag_name, id, name) => {
                Some(value.clone())
            }
            _ => None,
        })
        .unwrap_or_default()
}

fn number(tags: &[Tag], id: u8, name: &str) -> u32 {
    tags.iter()
        .find_map(|tag| match tag {
            Tag::Int { name: tag_name, value } if *value != 0 && named(tag_name, id, name) => Some(clamp(*value)),
            _ => None,
        })
        .unwrap_or(0)
}

fn length(tags: &[Tag]) -> u32 {
    tags.iter()
        .filter(|tag| named(tag.name(), file_tag::MEDIA_LENGTH, LENGTH))
        .map(|tag| match tag {
            Tag::Int { value, .. } => clamp(*value),
            Tag::String { value, .. } => duration(value),
            _ => 0,
        })
        .find(|seconds| *seconds > 0)
        .unwrap_or(0)
}

fn named(tag: &TagName, id: u8, name: &str) -> bool {
    tag.as_id() == Some(id) || (!tag.is_id() && tag.to_bytes().eq_ignore_ascii_case(name.as_bytes()))
}

// Unsigned on the wire: a value beyond the largest is huge, not negative.
fn clamp(value: u64) -> u32 {
    u32::try_from(value).ok().filter(|value| *value <= MAX_NUMBER).unwrap_or(MAX_NUMBER)
}
