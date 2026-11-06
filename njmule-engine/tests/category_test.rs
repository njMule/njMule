// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's CategoryTest, under its names.

#![allow(clippy::unwrap_used)]

use std::path::PathBuf;

use njmule_engine::api::category::Category;
use njmule_engine::api::download_info::DownloadPriority;

fn made(
    id: u32,
    name: &str,
    incoming: Option<&str>,
    color: u32,
    patterns: &str,
    regex: bool,
) -> Result<Category, String> {
    Category {
        id,
        name: name.to_owned(),
        incoming: incoming.map(PathBuf::from),
        comment: String::new(),
        color,
        priority: DownloadPriority::Auto,
        patterns: patterns.to_owned(),
        regex,
    }
    .validated()
}

fn category(patterns: &str, regex: bool) -> Category {
    made(1, "Films", None, 0, patterns, regex).unwrap()
}

#[test]
fn should_match_whole_names_with_wildcards_and_parts_without() {
    for (patterns, name, expected) in [
        ("*.mkv | film?.avi", "Holiday.MKV", true),
        ("*.mkv | film?.avi", "film1.avi", true),
        ("*.mkv | film?.avi", "film12.avi", false),
        ("*.mkv | film?.avi", "mkv.txt", false),
        ("ubuntu|debian", "Ubuntu 24.04 desktop.iso", true),
        ("ubuntu|debian", "my debian.iso", true),
        ("", "anything", false),
    ] {
        assert_eq!(category(patterns, false).matches(name), expected, "{patterns} {name}");
    }
}

#[test]
fn should_match_the_whole_name_with_one_regular_expression() {
    for (expression, name, expected) in [
        (".*\\.(avi|mkv)$", "Holiday.MKV", true),
        (".*\\.(avi|mkv)$", "film.avi", true),
        (".*\\.(avi|mkv)$", "film.avi.txt", false),
        ("ubuntu|debian", "Debian", true),
        ("^\\d{4} .*\\.flac$", "2024 live.flac", true),
        ("", "", false),
    ] {
        assert_eq!(category(expression, true).matches(name), expected, "{expression} {name}");
    }
}

#[test]
fn should_not_match_a_part_of_the_name_with_a_regular_expression() {
    let matches = category("ubuntu|debian", true).matches("Ubuntu 24.04 desktop.iso");

    assert!(!matches, "the whole name, not a part of it");
}

#[test]
fn should_refuse_an_id_below_one() {
    assert!(made(0, "x", None, 0, "", false).is_err());
}

#[test]
fn should_refuse_a_blank_name() {
    assert!(made(1, " ", None, 0, "", false).is_err());
}

#[test]
fn should_refuse_a_relative_incoming_folder() {
    assert!(made(1, "x", Some("relative"), 0, "", false).is_err());
}

#[test]
fn should_refuse_a_colour_out_of_range() {
    assert!(made(1, "x", None, 9, "", false).is_err());
}

#[test]
fn should_refuse_an_invalid_regular_expression() {
    assert!(made(1, "Films", None, 0, "(unclosed", true).is_err());
}

#[test]
fn should_read_the_regular_expressions_of_the_java_engine() {
    for (expression, name, expected) in [
        ("(?!sample).*\\.mkv", "film.mkv", true),
        ("(?!sample).*\\.mkv", "Sample.mkv", false),
        ("(\\w+)-\\1\\.iso", "abc-abc.iso", true),
        ("(\\w+)-\\1\\.iso", "abc-abd.iso", false),
        ("ÉTÉ.*", "été 2026.avi", true),
    ] {
        assert_eq!(category(expression, true).matches(name), expected, "{expression} {name}");
    }
}

#[test]
fn should_strip_the_texts_and_refuse_them_too_long() {
    let stripped = made(1, " Films ", None, 8, " *.mkv ", false).unwrap();

    assert_eq!((stripped.name.as_str(), stripped.patterns.as_str()), ("Films", "*.mkv"));
    assert!(made(1, &"x".repeat(65), None, 0, "", false).is_err());
    assert!(made(1, "a\u{1}b", None, 0, "", false).is_err());
    assert!(made(1, "x", None, 0, &"y".repeat(1025), false).is_err());
    let long_comment = Category { comment: "z".repeat(257), ..category("", false) }.validated();
    assert!(long_comment.is_err());
}
