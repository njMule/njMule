// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's PreferencesIpFilterListsTest, under its names: the IP filter lists and how often they are fetched
//! again, as kept in the preferences.

#![allow(clippy::unwrap_used)]

use njmule_engine::config::preferences::{self, Preferences};
use njmule_engine::persist::preferences_codec;

fn built(lists: &str, days: u32) -> Result<Preferences, String> {
    Preferences { ip_filter_lists: lists.to_owned(), ip_filter_update_days: days, ..Preferences::default() }.validated()
}

#[test]
fn should_keep_the_lists_and_how_often_they_are_fetched() {
    let directory = tempfile::tempdir().unwrap();
    let file = directory.path().join(preferences_codec::FILE_NAME);
    let saved = built(" https://one.example/ipfilter.zip\n\nHTTP://two.example/level1.p2p.gz ", 3).unwrap();

    preferences_codec::save(&file, &saved).unwrap();
    let loaded = preferences_codec::load(&file).unwrap();

    assert_eq!(
        loaded.ip_filter_list_addresses(),
        ["https://one.example/ipfilter.zip", "HTTP://two.example/level1.p2p.gz"]
    );
    assert_eq!(loaded.ip_filter_update_days, 3);
}

#[test]
fn should_fetch_the_lists_again_every_week_by_default() {
    let built = built("", preferences::DEFAULT_IP_FILTER_UPDATE_DAYS);

    assert_eq!(built.unwrap().ip_filter_update_days, 7);
}

#[test]
fn should_refuse_a_list_that_is_no_web_address() {
    let refused = built("https://one.example/ipfilter.zip\nftp://two.example/ipfilter.dat", 7);

    assert!(refused.unwrap_err().contains("ftp://two.example/ipfilter.dat"));
}

#[test]
fn should_refuse_more_lists_than_a_filter_takes() {
    let refused = built(&"https://one.example/ipfilter.zip\n".repeat(preferences::MAX_IP_FILTER_LISTS + 1), 7);

    assert!(refused.unwrap_err().contains(&format!("at most {}", preferences::MAX_IP_FILTER_LISTS)));
}

#[test]
fn should_refuse_an_update_rarer_than_a_year() {
    let refused = built("", preferences::MAX_IP_FILTER_UPDATE_DAYS + 1);

    assert!(refused.unwrap_err().contains("ipFilterUpdateDays"));
}
