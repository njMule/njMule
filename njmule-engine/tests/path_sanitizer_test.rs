// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's PathSanitizerTest, under their names; its properties over any name are left to
//! the examples.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use std::collections::HashSet;
use std::fs;

use njmule_engine::security::path_sanitizer::{self, MAX_NAME_BYTES};

#[test]
fn should_make_names_safe() {
    let cases = [
        ("movie.mkv", "movie.mkv"),
        ("../../etc/passwd", ".._.._etc_passwd"),
        ("a\\b/c:d*e?f\"g<h>i", "a_b_c_d_e_f_g_h_i"),
        ("con", "_con"),
        ("NUL.txt", "_NUL.txt"),
        ("com1.tar.gz", "_com1.tar.gz"),
        ("console.txt", "console.txt"),
        ("name. . .", "name"),
        ("...", "_"),
        ("  ", "_"),
    ];

    let names: Vec<String> = cases.iter().map(|(raw, _)| path_sanitizer::file_name(raw)).collect();

    assert_eq!(names, cases.iter().map(|(_, expected)| (*expected).to_owned()).collect::<Vec<_>>());
}

#[test]
fn should_replace_control_characters_and_keep_unicode() {
    let name = path_sanitizer::file_name("a\u{0}b\nc\u{7F}é✓.txt");

    assert_eq!(name, "a_b_c_é✓.txt");
}

#[test]
fn should_truncate_long_names_but_keep_the_extension() {
    let name = path_sanitizer::file_name(&format!("{}.mkv", "è".repeat(300)));

    assert!(name.ends_with(".mkv"));
    assert!(name.len() <= MAX_NAME_BYTES);
}

#[test]
fn should_pick_a_free_name_inside_the_directory() {
    let directory = tempfile::tempdir().unwrap();
    fs::write(directory.path().join("file.iso"), b"").unwrap();
    fs::write(directory.path().join("file (2).iso"), b"").unwrap();

    let path = path_sanitizer::reserve(directory.path(), "file.iso").unwrap();

    assert_eq!(path, directory.path().join("file (3).iso"));
}

#[test]
fn should_resolve_a_name_inside_the_directory() {
    let directory = tempfile::tempdir().unwrap();

    let path = path_sanitizer::resolve_inside(directory.path(), "../x").unwrap();

    assert_eq!(path.parent(), Some(directory.path()));
}

#[test]
fn should_never_leave_trailing_dots_or_spaces_when_cutting() {
    let cases = [
        (format!("a{}{}", ".".repeat(300), "b".repeat(20)), "a"),
        (format!("a{}{}", " ".repeat(300), "b".repeat(20)), "a"),
        (format!("{}{}", ".".repeat(300), "b".repeat(20)), "_"),
        ("a\u{3000}.".to_owned(), "a"),
        ("a.\u{3000}".to_owned(), "a"),
    ];

    for (raw, expected) in cases {
        assert_eq!(path_sanitizer::file_name(&raw), expected);
    }
}

#[test]
fn should_check_the_cut_name_for_reserved_names() {
    let cases = [(format!("CON{}x", " ".repeat(300)), "_CON"), (format!("AUX{}x.mkv", " ".repeat(200)), "_AUX.mkv")];

    for (raw, expected) in cases {
        assert_eq!(path_sanitizer::file_name(&raw), expected);
    }
}

#[test]
fn should_escape_a_long_reserved_name_and_still_cut_it() {
    let name = path_sanitizer::file_name(&format!("NUL.{}", "x".repeat(300)));

    assert!(name.starts_with("_NUL."));
    assert!(name.len() <= MAX_NAME_BYTES);
}

#[test]
fn should_escape_reserved_device_names() {
    let cases = [
        ("COM¹.txt", "_COM¹.txt"),
        ("lpt³", "_lpt³"),
        ("COM0.log", "_COM0.log"),
        ("lpt0", "_lpt0"),
        ("CONIN$", "_CONIN$"),
        ("conout$.txt", "_conout$.txt"),
        ("CoN .txt", "_CoN .txt"),
        ("con.", "_con"),
        ("COM10", "COM10"),
        ("LPT10.txt", "LPT10.txt"),
    ];

    for (raw, expected) in cases {
        assert_eq!(path_sanitizer::file_name(raw), expected, "{raw}");
    }
}

#[test]
fn should_reserve_distinct_numbered_names_for_long_names() {
    for long in [format!("{}.mkv", "x".repeat(250)), "y".repeat(300), format!("{}.a", "z".repeat(197))] {
        let directory = tempfile::tempdir().unwrap();

        let reserved: Vec<_> = (0..12).map(|_| path_sanitizer::reserve(directory.path(), &long).unwrap()).collect();

        assert_eq!(reserved.iter().collect::<HashSet<_>>().len(), 12);
        assert!(reserved.iter().all(|path| path.file_name().unwrap().len() <= MAX_NAME_BYTES));
    }
}

#[test]
fn should_pick_the_number_after_the_reserved_names_of_a_long_name() {
    for long in [format!("{}.mkv", "x".repeat(250)), "y".repeat(300), format!("{}.a", "z".repeat(197))] {
        let directory = tempfile::tempdir().unwrap();
        let reserved: Vec<_> = (0..12).map(|_| path_sanitizer::reserve(directory.path(), &long).unwrap()).collect();

        let path = path_sanitizer::reserve(directory.path(), &long).unwrap();

        assert!(!reserved.contains(&path));
        assert!(path.file_name().unwrap().to_string_lossy().contains(" (13)"));
    }
}
