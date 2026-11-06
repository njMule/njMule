// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's PreferencesCodecTest for the preferences this engine has, under their names, and
//! the changes the API makes.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

mod held;

use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;

use njmule_engine::config::preferences::{ObfuscationMode, Preferences};
use njmule_engine::persist::preferences_codec;

fn file() -> (tempfile::TempDir, PathBuf) {
    let directory = tempfile::tempdir().unwrap();
    let file = directory.path().join(preferences_codec::FILE_NAME);
    (directory, file)
}

fn changes(pairs: &[(&str, &str)]) -> BTreeMap<String, String> {
    pairs.iter().map(|(key, value)| ((*key).to_owned(), (*value).to_owned())).collect()
}

#[test]
fn should_round_trip_through_the_file() {
    let (_directory, file) = file();
    let preferences = Preferences {
        nick: "Città user = me".to_owned(),
        tcp_port: 23456,
        obfuscation: ObfuscationMode::Required,
        filter_lan_addresses: false,
        incoming_dir: Some(PathBuf::from("C:\\Down loads")),
        add_servers_from_server: true,
        ..Preferences::default()
    };

    preferences_codec::save(&file, &preferences).unwrap();

    assert_eq!(preferences_codec::load(&file).unwrap(), preferences);
}

#[test]
fn should_give_the_defaults_for_a_missing_file() {
    let (_directory, file) = file();

    assert_eq!(preferences_codec::load(&file).unwrap(), Preferences::default());
}

#[test]
fn should_keep_the_defaults_of_invalid_values_and_apply_the_valid_ones() {
    let (_directory, file) = file();
    fs::write(&file, "net.tcpPort=not a port\nsecurity.obfuscation=SOMETIMES\nuser.nick=valid\n").unwrap();

    let preferences = preferences_codec::load(&file).unwrap();

    assert_eq!(preferences.tcp_port, Preferences::default().tcp_port);
    assert_eq!(preferences.obfuscation, Preferences::default().obfuscation);
    assert_eq!(preferences.nick, "valid");
}

#[test]
fn should_not_let_a_byte_order_mark_hide_the_first_key() {
    let (_directory, file) = file();
    fs::write(&file, "\u{FEFF}net.tcpPort=1234\n").unwrap();

    assert_eq!(preferences_codec::load(&file).unwrap().tcp_port, 1234);
}

#[test]
fn should_keep_the_keys_of_a_newer_version_through_a_save() {
    let (_directory, file) = file();
    fs::write(&file, "future.key=kept\nuser.nick=old\n").unwrap();

    preferences_codec::save(&file, &Preferences::default()).unwrap();

    let text = fs::read_to_string(&file).unwrap();
    assert!(text.contains("future.key=kept"), "{text}");
    assert_eq!(preferences_codec::load(&file).unwrap().nick, "njMule");
}

#[test]
fn should_read_windows_line_ends_and_padded_values() {
    let (_directory, file) = file();
    fs::write(&file, "net.tcpPort = 2345 \r\nsecurity.filterLan=  false\r\n").unwrap();

    let preferences = preferences_codec::load(&file).unwrap();

    assert_eq!(preferences.tcp_port, 2345);
    assert!(!preferences.filter_lan_addresses);
}

#[test]
fn should_apply_the_changes_given_as_text() {
    let changed = preferences_codec::apply(
        &Preferences::default(),
        &changes(&[("server.addFromServer", "on"), ("security.obfuscation", "supported"), ("net.udpPort", "4000")]),
    )
    .unwrap();

    assert!(changed.add_servers_from_server);
    assert_eq!(changed.obfuscation, ObfuscationMode::Supported);
    assert_eq!(changed.udp_port, 4000);
}

#[test]
fn should_refuse_an_unknown_key_or_a_bad_value_and_change_nothing() {
    for refused in [&[("no.such", "1")][..], &[("user.nick", "x"), ("net.tcpPort", "70000")][..]] {
        assert!(preferences_codec::apply(&Preferences::default(), &changes(refused)).is_err(), "{refused:?}");
    }
}

#[test]
fn should_give_every_key_as_text() {
    let map = preferences_codec::to_map(&Preferences::default());

    assert_eq!(map.keys().copied().collect::<Vec<_>>().len(), preferences_codec::KEYS.len());
    assert_eq!(map["net.tcpPort"], "4662");
    assert_eq!(map["security.obfuscation"], "REQUESTED");
    assert_eq!(map["dirs.incoming"], "");
}

fn saved_with_the_tcp_port(file: &std::path::Path, tcp_port: u16) {
    preferences_codec::save(file, &Preferences { tcp_port, ..Preferences::default() }).unwrap();
}

#[test]
fn should_not_take_from_the_older_backup_the_preferences_held_by_another_program() {
    let (_directory, file) = file();
    saved_with_the_tcp_port(&file, 1111);
    saved_with_the_tcp_port(&file, 2222);
    let Some(_held) = held::hold(&file) else { return };

    let loaded = preferences_codec::load(&file);

    // Loaded from the backup, they would be saved over the newer file at the start.
    assert!(loaded.is_err());
}

#[test]
fn should_load_the_newer_preferences_once_the_other_program_lets_them_go() {
    let (_directory, file) = file();
    saved_with_the_tcp_port(&file, 1111);
    saved_with_the_tcp_port(&file, 2222);
    {
        let Some(_held) = held::hold(&file) else { return };
        assert!(preferences_codec::load(&file).is_err());
    }

    let loaded = preferences_codec::load(&file);

    assert_eq!(loaded.unwrap().tcp_port, 2222);
}
