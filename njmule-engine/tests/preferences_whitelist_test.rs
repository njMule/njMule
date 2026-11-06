// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's PreferencesWhitelistTest, under its names: the addresses the IP filter never blocks, as kept in the
//! preferences.

#![allow(clippy::unwrap_used)]

use std::fs;

use njmule_engine::config::preferences::{self, Preferences};
use njmule_engine::persist::preferences_codec;

fn built(whitelist: &str) -> Result<Preferences, String> {
    Preferences { ip_filter_whitelist: whitelist.to_owned(), ..Preferences::default() }.validated()
}

#[test]
fn should_keep_a_whitelist_of_several_lines() {
    let whitelist = "# friends\n1.2.3.4\n10.0.0.0/8\n5.6.7.0 - 5.6.7.255 , 100 , office";
    let directory = tempfile::tempdir().unwrap();
    let file = directory.path().join(preferences_codec::FILE_NAME);

    preferences_codec::save(&file, &built(whitelist).unwrap()).unwrap();
    let loaded = preferences_codec::load(&file).unwrap();

    assert_eq!(loaded.ip_filter_whitelist, whitelist);
}

#[test]
fn should_keep_no_whitelist_when_the_file_holds_a_wrong_one() {
    let directory = tempfile::tempdir().unwrap();
    let file = directory.path().join(preferences_codec::FILE_NAME);
    fs::write(&file, "security.ipFilterWhitelist=1.2.3.4\\nnot an address\nsecurity.ipFilterLevel=99\n").unwrap();

    let loaded = preferences_codec::load(&file).unwrap();

    assert_eq!(loaded.ip_filter_whitelist, "");
    assert_eq!(loaded.ip_filter_level, 99);
}

#[test]
fn should_refuse_a_whitelist_with_a_wrong_entry() {
    let refused = built("1.2.3.4\n1.2.3.0/40");

    assert!(refused.unwrap_err().contains("1.2.3.0/40"));
}

#[test]
fn should_refuse_a_whitelist_too_long() {
    let refused = built(&"1.2.3.4\n".repeat(preferences::MAX_IP_FILTER_WHITELIST / 8 + 1));

    assert!(refused.unwrap_err().contains(&format!("at most {}", preferences::MAX_IP_FILTER_WHITELIST)));
}
