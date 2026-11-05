// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's ResultNamesTest, under their names: the names a file is found under, the counts
//! each gives and the one the file shows.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::api::search_result::{Name, SearchResult};
use njmule_engine::search::result_names::{self, MAX_NAMES};

const HASH: [u8; 16] = [0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF];

fn result(name: &str, sources: u32, complete: Option<u32>) -> SearchResult {
    SearchResult {
        hash: HASH,
        name: name.to_owned(),
        size: 1000,
        sources,
        complete_sources: complete,
        file_type: None,
        spam_rating: 0,
        aich_hash: None,
        names: Vec::new(),
    }
}

fn name(name: &str, sources: u32, complete: Option<u32>) -> Name {
    Name { name: name.to_owned(), sources, complete_sources: complete }
}

#[test]
fn should_add_up_the_sources_of_a_name_found_again_in_another_case() {
    let merged =
        result_names::found_again(&result("holiday.avi", 3, Some(1)), &result("HOLIDAY.AVI", 2, Some(1)), true);

    assert_eq!((merged.name.as_str(), merged.sources, merged.complete_sources), ("holiday.avi", 5, Some(2)));
    assert!(merged.names.is_empty());
}

#[test]
fn should_keep_the_larger_count_of_a_name_kad_nodes_report_again() {
    let merged = result_names::found_again(&result("holiday.avi", 3, None), &result("holiday.avi", 2, None), false);

    assert_eq!((merged.name.as_str(), merged.sources, merged.complete_sources), ("holiday.avi", 3, None));
}

#[test]
fn should_show_the_name_most_sources_give_and_list_every_name() {
    let merged =
        result_names::found_again(&result("holiday.avi", 2, Some(0)), &result("Holiday HD.avi", 5, Some(1)), true);

    assert_eq!((merged.name.as_str(), merged.sources, merged.complete_sources), ("Holiday HD.avi", 7, Some(1)));
    assert_eq!(merged.names, [name("Holiday HD.avi", 5, Some(1)), name("holiday.avi", 2, Some(0))]);
}

#[test]
fn should_keep_showing_the_first_of_names_as_many_sources_give() {
    let merged =
        result_names::found_again(&result("holiday.avi", 3, Some(0)), &result("Holiday HD.avi", 3, Some(0)), true);

    assert_eq!((merged.name.as_str(), merged.sources, merged.complete_sources), ("holiday.avi", 6, Some(0)));
}

#[test]
fn should_take_the_type_of_the_name_shown() {
    let merged =
        result_names::found_again(&result("holiday.avi", 1, Some(0)), &result("holiday.exe", 4, Some(0)), true);

    assert_eq!(merged.file_type.as_deref(), Some("Pro"));
}

#[test]
fn should_count_the_sources_of_names_beyond_the_limit_without_listing_them() {
    let mut known = result("name0.avi", 2, Some(0));
    for i in 1..MAX_NAMES {
        known = result_names::found_again(&known, &result(&format!("name{i}.avi"), 1, Some(0)), true);
    }
    assert_eq!(known.names.len(), MAX_NAMES);

    let merged = result_names::found_again(&known, &result("one too many.avi", 1, Some(0)), true);

    assert_eq!(
        (merged.name.as_str(), merged.sources, merged.complete_sources),
        ("name0.avi", u32::try_from(MAX_NAMES).unwrap() + 2, Some(0))
    );
    assert_eq!(merged.names.len(), MAX_NAMES);
    assert!(merged.names.iter().all(|kept| kept.name != "one too many.avi"));
}

#[test]
fn should_add_nothing_for_a_count_not_told() {
    let merged = result_names::found_again(&result("holiday.avi", 3, None), &result("holiday.avi", 2, Some(1)), true);

    assert_eq!((merged.name.as_str(), merged.sources, merged.complete_sources), ("holiday.avi", 5, Some(1)));
}
