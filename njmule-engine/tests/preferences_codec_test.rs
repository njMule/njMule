// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's PreferencesCodecTest, under its names, and the changes the API makes.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use crate::held;

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use njmule_engine::config::night_hours::NightHours;
use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::config::proxy_settings::{ProxySettings, ProxyType};
use njmule_engine::config::update_channel::UpdateChannel;
use njmule_engine::config::update_install_time::UpdateInstallTime;
use njmule_engine::config::update_mode::UpdateMode;
use njmule_engine::persist::{preferences_codec, property_lines};
use proptest::prelude::any;

fn file() -> (tempfile::TempDir, PathBuf) {
    let directory = tempfile::tempdir().unwrap();
    let file = directory.path().join(preferences_codec::FILE_NAME);
    (directory, file)
}

fn changes(pairs: &[(&str, &str)]) -> BTreeMap<String, String> {
    pairs.iter().map(|(key, value)| ((*key).to_owned(), (*value).to_owned())).collect()
}

fn with_the_proxy_credentials(user: &str, password: &str) -> Preferences {
    let proxy = ProxySettings {
        kind: ProxyType::Socks,
        host: "proxy.lan".to_owned(),
        port: 1080,
        user: user.to_owned(),
        password: password.to_owned(),
    };
    Preferences { proxy, ..Preferences::default() }.validated().unwrap()
}

fn saved_with_the_tcp_port(file: &Path, tcp_port: u16) {
    preferences_codec::save(file, &Preferences { tcp_port, ..Preferences::default() }).unwrap();
}

#[test]
fn should_round_trip_through_the_file() {
    let (directory, file) = file();
    let defaults = Preferences::default();
    let preferences = Preferences {
        nick: "Ünïcødé = : # nick".to_owned(),
        tcp_port: 12_345,
        kad_enabled: false,
        obfuscation: ObfuscationMode::Required,
        incoming_dir: Some(directory.path().join("in dir")),
        log_level: "DEBUG".to_owned(),
        upnp: false,
        download_capacity_kibps: 4096,
        upload_capacity_kibps: 512,
        min_free_space_mib: 2048,
        proxy: ProxySettings {
            kind: ProxyType::Socks,
            host: "proxy.lan".to_owned(),
            port: 9050,
            user: "me".to_owned(),
            password: "pw".to_owned(),
        },
        name_cleanups: "dvdrip|sponsored".to_owned(),
        search_spam_filter: false,
        clean_up_new_names: true,
        update_mode: UpdateMode::Install,
        update_install_time: UpdateInstallTime::ExitOrNight,
        night_hours: NightHours::parse("01:00-04:30").unwrap(),
        update_url: Some("http://192.168.1.2:8000/njmule".to_owned()),
        update_channel: if defaults.update_channel == UpdateChannel::Stable {
            UpdateChannel::Prerelease
        } else {
            UpdateChannel::Stable
        },
        ..defaults
    }
    .validated()
    .unwrap();

    preferences_codec::save(&file, &preferences).unwrap();

    assert_eq!(preferences_codec::load(&file).unwrap(), preferences);
    let text = fs::read_to_string(&file).unwrap();
    for part in [
        "net.tcpPort=12345",
        "update.mode=INSTALL",
        "update.installAt=EXIT_OR_NIGHT",
        "update.nightHours=01\\:00-04\\:30",
    ] {
        assert!(text.contains(part), "{part} in {text}");
    }
    assert!(!text.contains("#Mon"), "{text}");
}

#[cfg(unix)]
#[test]
fn should_keep_the_passwords_for_their_owner_only() {
    use std::os::unix::fs::PermissionsExt;
    let (_directory, file) = file();
    fs::write(&file, "proxy.password=old\n").unwrap();
    fs::set_permissions(&file, fs::Permissions::from_mode(0o644)).unwrap();

    preferences_codec::save(&file, &with_the_proxy_credentials("user", "secret")).unwrap();

    assert_eq!(fs::metadata(&file).unwrap().permissions().mode() & 0o777, 0o600);
}

#[test]
fn should_give_the_defaults_for_a_missing_file() {
    let (directory, _file) = file();

    let loaded = preferences_codec::load(&directory.path().join("none")).unwrap();

    assert_eq!(loaded, Preferences::default());
}

#[test]
fn should_keep_the_defaults_of_invalid_values_and_apply_the_valid_ones() {
    let text = "net.tcpPort=99999\nnet.udpPort=5000\nnet.kad.enabled=maybe\nnet.ed2k.enabled=off\n\
        security.obfuscation=supported\nlog.level=nope\nunknown.key=1\ndirs.temp=\n";

    let loaded = preferences_codec::parse(text);

    let defaults = Preferences::default();
    assert_eq!(loaded.tcp_port, defaults.tcp_port);
    assert_eq!(loaded.udp_port, 5000);
    assert_eq!(loaded.kad_enabled, defaults.kad_enabled);
    assert!(!loaded.ed2k_enabled);
    assert_eq!(loaded.obfuscation, ObfuscationMode::Supported);
    assert_eq!(loaded.log_level, defaults.log_level);
    assert_eq!(loaded.temp_dir, None);
}

#[test]
fn should_not_let_a_byte_order_mark_hide_the_first_key() {
    let (_directory, file) = file();
    fs::write(&file, "\u{FEFF}net.tcpPort=12345\nnet.udpPort=5000\n").unwrap();

    let loaded = preferences_codec::load(&file).unwrap();

    assert_eq!(loaded.tcp_port, 12_345);
}

#[test]
fn should_lose_only_the_line_of_a_malformed_escape() {
    let (_directory, file) = file();
    fs::write(&file, "user.nick=bad\\u12\nnet.tcpPort=12345\nchat.filter=a\\\n  b\\u00e9\n").unwrap();

    let loaded = preferences_codec::load(&file).unwrap();

    assert_eq!(loaded.nick, Preferences::default().nick);
    assert_eq!(loaded.tcp_port, 12_345);
    assert_eq!(loaded.message_filter, "abé");
}

#[test]
fn should_keep_the_keys_of_a_newer_version_through_a_save() {
    let (_directory, file) = file();
    fs::write(&file, "net.tcpPort=12345\nfuture.option=on: yes\n").unwrap();

    let loaded = preferences_codec::load(&file).unwrap();
    preferences_codec::save(&file, &Preferences { udp_port: 5000, ..loaded }).unwrap();

    let saved = property_lines::parse(&fs::read_to_string(&file).unwrap());
    assert_eq!(saved["future.option"], "on: yes");
    assert_eq!(saved["net.udpPort"], "5000");
    assert_eq!(preferences_codec::load(&file).unwrap().tcp_port, 12_345);
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

#[test]
fn should_write_a_sorted_and_stable_output() {
    let first = preferences_codec::format(&Preferences::default());

    let second = preferences_codec::format(&Preferences::default());

    assert_eq!(first, second);
    let lines: Vec<&str> = first.lines().skip(1).collect();
    assert!(lines.is_sorted(), "{first}");
}

#[test]
fn should_read_windows_line_ends_and_padded_values() {
    let loaded = preferences_codec::parse("net.tcpPort = 12345 \r\nnet.kad.enabled=false");

    assert_eq!(loaded.tcp_port, 12_345);
    assert!(!loaded.kad_enabled);
}

proptest::proptest! {
    #[test]
    fn should_keep_the_proxy_credentials_through_the_file(
        user in proptest::collection::vec(any::<char>(), 0..=40),
        password in proptest::collection::vec(any::<char>(), 0..=40),
    ) {
        let preferences = with_the_proxy_credentials(&String::from_iter(user), &String::from_iter(password));

        let loaded = preferences_codec::parse(&preferences_codec::format(&preferences));

        proptest::prop_assert_eq!(loaded.proxy, preferences.proxy);
    }

    #[test]
    fn should_load_escaped_values_back_unchanged(value in any::<String>()) {
        let mut line = String::new();
        property_lines::append(&mut line, "k", &value);

        let loaded = property_lines::parse(&line);

        proptest::prop_assert_eq!(loaded.get("k"), Some(&value));
    }
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
    for (refused, named) in [
        (&[("no.such", "1")][..], "no.such"),
        (&[("user.nick", "x"), ("net.udpPort", "70000")][..], "udpPort"),
        (&[("net.tcpPort", "0")][..], "tcpPort"),
    ] {
        let reason = preferences_codec::apply(&Preferences::default(), &changes(refused)).unwrap_err();

        assert!(reason.contains(named), "{reason}");
    }
}

#[test]
fn should_give_every_key_as_text() {
    let values = preferences_codec::values(&Preferences::default());

    assert_eq!(values.len(), preferences_codec::keys().count());
    assert_eq!(values["net.tcpPort"], "4662");
    assert_eq!(values["security.obfuscation"], "REQUESTED");
    assert_eq!(values["dirs.incoming"], "");
    assert_eq!(values["notify.events"], "downloadCompleted,downloadFailed,diskSpaceShort");
}
