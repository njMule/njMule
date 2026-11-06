// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's CategoriesCodecTest, under its names.

#![allow(clippy::unwrap_used)]

use std::fs;
use std::path::{Path, PathBuf};

use njmule_engine::api::category::Category;
use njmule_engine::api::download_info::DownloadPriority;
use njmule_engine::persist::categories_codec;

fn file(directory: &Path) -> PathBuf {
    directory.join(categories_codec::FILE_NAME)
}

fn two_saved_categories(directory: &Path) -> Vec<Category> {
    let categories = vec![
        Category {
            id: 2,
            name: "Films = ok".to_owned(),
            incoming: Some(directory.join("films")),
            comment: "évening".to_owned(),
            color: 3,
            priority: DownloadPriority::High,
            patterns: "*.mkv|*.avi".to_owned(),
            regex: false,
        },
        Category {
            id: 5,
            name: "Music".to_owned(),
            incoming: None,
            comment: String::new(),
            color: 0,
            priority: DownloadPriority::Auto,
            patterns: "\\.flac$".to_owned(),
            regex: true,
        },
    ];
    categories_codec::save(&file(directory), &categories).unwrap();
    categories
}

fn names(categories: &[Category]) -> Vec<&str> {
    categories.iter().map(|category| category.name.as_str()).collect()
}

#[test]
fn should_load_no_category_from_a_missing_file() {
    let directory = tempfile::tempdir().unwrap();

    let loaded = categories_codec::load(&file(directory.path())).unwrap();

    assert!(loaded.is_empty());
}

#[test]
fn should_load_the_categories_it_saved() {
    let directory = tempfile::tempdir().unwrap();
    let saved = two_saved_categories(directory.path());

    let loaded = categories_codec::load(&file(directory.path())).unwrap();

    assert_eq!(loaded, saved);
}

#[test]
fn should_leave_out_broken_categories() {
    let directory = tempfile::tempdir().unwrap();
    let saved = two_saved_categories(directory.path());
    let text = fs::read_to_string(file(directory.path())).unwrap();
    fs::write(
        file(directory.path()),
        text + "category.7.name=\ncategory.8.color=99\ncategory.9.name=x\ncategory.9.priority=urgent\n\
                category.-1.name=y\ncategory.10.name=z\ncategory.10.color=-2\n",
    )
    .unwrap();

    let loaded = categories_codec::load(&file(directory.path())).unwrap();

    assert_eq!(loaded, saved);
}

#[test]
fn should_lose_only_the_line_of_a_malformed_escape() {
    let directory = tempfile::tempdir().unwrap();
    fs::write(
        file(directory.path()),
        "\u{FEFF}category.1.name=Films\ncategory.1.comment=\\u12x\ncategory.2.name=Music\n",
    )
    .unwrap();

    let loaded = categories_codec::load(&file(directory.path())).unwrap();

    assert_eq!(names(&loaded), ["Films", "Music"]);
}
