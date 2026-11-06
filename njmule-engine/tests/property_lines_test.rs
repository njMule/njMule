// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Properties files as the Java engine reads and writes them, which its codecs' tests check through their files.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use std::collections::{BTreeMap, BTreeSet};
use std::fs;

use njmule_engine::persist::{atomic_files, property_lines};

#[test]
fn should_write_the_escapes_of_the_java_engine() {
    let mut text = String::new();

    property_lines::append(&mut text, "a key=:#!", " lead\tand\r\n\u{c}\\ = : # ! é\u{1}");

    assert_eq!(text, "a\\ key\\=\\:\\#\\!=\\ lead\\tand\\r\\n\\f\\\\ \\= \\: \\# \\! é\u{1}\n");
    assert_eq!(property_lines::parse(&text)["a key=:#!"], " lead\tand\r\n\u{c}\\ = : # ! é\u{1}");
}

#[test]
fn should_read_lines_ended_by_a_carriage_return_alone() {
    let properties = property_lines::parse("a=1\rb=2\\\r  3\r\nc=\\u12\rd=4");

    assert_eq!(properties.get("a").map(String::as_str), Some("1"));
    assert_eq!(properties.get("b").map(String::as_str), Some("23"));
    assert_eq!(properties.get("c"), None);
    assert_eq!(properties.get("d").map(String::as_str), Some("4"));
}

#[test]
fn should_lose_only_a_comment_with_a_malformed_escape() {
    let properties = property_lines::parse("# see \\u12 there\\\na=1\n! more\nb=\\u0041");

    assert_eq!(properties, BTreeMap::from([("a".to_owned(), "1".to_owned()), ("b".to_owned(), "A".to_owned())]));
}

#[test]
fn should_give_the_numbers_of_the_entries_in_order() {
    let properties: BTreeMap<String, String> =
        ["cat.10.name", "cat.2.name", "cat.2.path", "cat.x.name", "cat.7", "other.1.name", "cat.-1.name"]
            .into_iter()
            .map(|key| (key.to_owned(), String::new()))
            .collect();

    let numbers = property_lines::numbers(&properties, "cat.");

    assert_eq!(numbers, BTreeSet::from([-1, 2, 7, 10]));
}

#[test]
fn should_read_the_backup_of_a_missing_file() {
    let directory = tempfile::tempdir().unwrap();
    let file = directory.path().join("Category.ini");
    fs::write(atomic_files::bak_path(&file), "cat.1.name=Films\n").unwrap();

    let properties = property_lines::read(&file).unwrap();

    assert_eq!(properties["cat.1.name"], "Films");
}
