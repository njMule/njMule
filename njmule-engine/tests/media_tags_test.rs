// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's MediaTagsTest, under their names.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use njmule_engine::api::search_result::Media;
use njmule_engine::protocol::ed2k::file_entry::FileEntry;
use njmule_engine::protocol::ed2k::server_messages::SearchResults;
use njmule_engine::protocol::tag::media_tags::{self, MAX_NUMBER};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};

fn string(name: &str, value: &str) -> Tag {
    Tag::String { name: TagName::of_bytes(name.as_bytes()), value: value.to_owned() }
}

fn string_id(id: u8, value: &str) -> Tag {
    Tag::String { name: TagName::id(id), value: value.to_owned() }
}

fn int(name: &str, value: u64) -> Tag {
    Tag::Int { name: TagName::of_bytes(name.as_bytes()), value }
}

fn int_id(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

#[test]
fn should_read_the_media_tags_a_server_sends_by_name() {
    let mut tags = vec![string_id(file_tag::FILENAME, "song.mp3"), int_id(file_tag::FILESIZE, 10)];
    tags.extend([
        string("Artist", "Some Band"),
        string("ALBUM", "First Album"),
        string("title", "Opening Song"),
        string("length", "3:25"),
        int("bitrate", 192),
        string("Codec", "mp3"),
    ]);
    let sent = FileEntry { hash: [1; 16], client_id: 0, port: 0, tags };
    let packet = (SearchResults { files: vec![sent], more: false }).encode().unwrap();

    let media = SearchResults::decode(&packet).unwrap().files[0].media();

    assert_eq!(media, Media::new("Some Band", "First Album", "Opening Song", 205, 192, "mp3"));
}

#[test]
fn should_take_the_first_non_empty_tag_of_each_detail_by_number_or_name() {
    let tags = [
        string("Artist", "Named"),
        string_id(file_tag::MEDIA_ARTIST, "Numbered"),
        string_id(file_tag::MEDIA_TITLE, "  "),
        string("Title", "Named title"),
        int_id(file_tag::MEDIA_BITRATE, 0),
        int("bitrate", 320),
        int_id(file_tag::MEDIA_LENGTH, 61),
    ];

    let media = media_tags::read(&tags);

    assert_eq!(media, Media::new("Named", "", "Named title", 61, 320, ""));
}

#[test]
fn should_take_unsigned_huge_numbers_as_the_largest_ones() {
    let tags = [int_id(file_tag::MEDIA_LENGTH, u64::MAX), int_id(file_tag::MEDIA_BITRATE, 0x1_0000_0000)];

    let media = media_tags::read(&tags);

    assert_eq!(media, Media::new("", "", "", MAX_NUMBER, MAX_NUMBER, ""));
}

#[test]
fn should_read_length_texts_as_hours_minutes_and_seconds() {
    for (text, expected) in [("1:02:03", 3723), (" 4 : 05 ", 245), ("17 s", 17), ("12:ab", 12)] {
        assert_eq!(media_tags::duration(text), expected, "{text}");
    }
}

#[test]
fn should_refuse_length_texts_that_are_no_length() {
    let texts = ["long", "", "12345678901", "1:2:3 and then a long explanation of the length that goes beyond the cap"];

    for text in texts {
        assert_eq!(media_tags::duration(text), 0, "{text}");
    }
}

#[test]
fn should_clean_and_cut_the_texts_of_other_users() {
    let tags =
        [string_id(file_tag::MEDIA_ARTIST, " Bad\u{7}\nArtist "), string_id(file_tag::MEDIA_ALBUM, &"a".repeat(1000))];

    let media = media_tags::read(&tags);

    assert_eq!(media.artist, "Bad  Artist");
    assert_eq!(media.album.chars().count(), Media::MAX_TEXT);
}

#[test]
fn should_write_the_media_back_as_numeric_tags() {
    let read = media_tags::read(&[
        string("Artist", "Some Band"),
        int("length", 200),
        string_id(file_tag::MEDIA_CODEC, "flac"),
    ]);

    let again = media_tags::read(&media_tags::of(&read));

    assert_eq!(again, Media::new("Some Band", "", "", 200, 0, "flac"));
}

#[test]
fn should_give_nothing_for_a_file_without_tags() {
    let media = media_tags::read(&[]);

    assert_eq!(media, Media::default());
}
