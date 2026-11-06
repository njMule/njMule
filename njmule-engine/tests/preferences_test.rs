// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's PreferencesTest, under its names.

#![allow(clippy::unwrap_used)]

use std::collections::BTreeMap;
use std::path::PathBuf;

use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::config::proxy_settings::{ProxySettings, ProxyType};
use njmule_engine::persist::preferences_codec;

fn with_nick(nick: &str) -> Result<Preferences, String> {
    Preferences { nick: nick.to_owned(), ..Preferences::default() }.validated()
}

#[test]
fn should_have_valid_defaults_with_the_nj_mule_ports() {
    let preferences = Preferences::default();

    assert_eq!(preferences.tcp_port, 4662);
    assert_eq!(preferences.udp_port, 4672);
    assert_eq!(preferences.obfuscation, ObfuscationMode::Requested);
    assert!(preferences.upnp, "the router forwards our ports unless turned off");
    assert_eq!(preferences.clone().validated().unwrap(), preferences);
}

#[test]
fn should_change_every_field_through_the_builder() {
    let directory = tempfile::tempdir().unwrap();

    let preferences = Preferences {
        nick: "  Tester  ".to_owned(),
        tcp_port: 1,
        udp_port: 65_535,
        udp_enabled: false,
        ed2k_enabled: false,
        kad_enabled: false,
        auto_connect: false,
        reconnect: false,
        max_upload_kibps: 10,
        max_download_kibps: 20,
        max_connections: 30,
        max_sources_per_file: 40,
        obfuscation: ObfuscationMode::Required,
        secure_ident: false,
        credit_system: false,
        incoming_dir: Some(directory.path().join("in")),
        temp_dir: Some(directory.path().join("tmp")),
        log_level: "DEBUG".to_owned(),
        ..Preferences::default()
    }
    .validated()
    .unwrap();

    let dirs = AppDirectories::under_root(directory.path()).unwrap();
    assert_eq!(preferences.nick, "Tester");
    assert!(!preferences.udp_enabled);
    assert_eq!(preferences.max_sources_per_file, 40);
    assert_eq!(preferences.effective_incoming_dir(&dirs), directory.path().join("in"));
    assert_eq!(preferences.effective_temp_dir(&dirs), directory.path().join("tmp"));
}

#[test]
fn should_meet_firewalled_clients_through_routers_only_without_proxy_nor_required_obfuscation() {
    for (mode, proxy, traversed) in [
        (ObfuscationMode::Requested, ProxyType::None, true),
        (ObfuscationMode::Required, ProxyType::None, false),
        (ObfuscationMode::Supported, ProxyType::Socks, false),
    ] {
        let host = if proxy == ProxyType::None { "" } else { "proxy.example" };
        let preferences = Preferences {
            obfuscation: mode,
            proxy: ProxySettings { kind: proxy, host: host.to_owned(), ..ProxySettings::default() },
            ..Preferences::default()
        }
        .validated()
        .unwrap();

        assert_eq!(preferences.router_traversal(), traversed, "{mode:?} {proxy:?}");
    }
}

#[test]
fn should_use_the_temp_directory_of_the_profile_by_default() {
    let directory = tempfile::tempdir().unwrap();
    let dirs = AppDirectories::under_root(directory.path()).unwrap();

    let temp = Preferences::default().effective_temp_dir(&dirs);

    assert_eq!(temp, dirs.temp);
}

#[test]
fn should_not_let_a_nick_reverse_or_isolate_the_text_around_it() {
    for code_point in [0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069] {
        let nick = format!("abc{}def", char::from_u32(code_point).unwrap());

        assert!(with_nick(&nick).is_err(), "U+{code_point:04X}");
    }
}

#[test]
fn should_keep_a_joiner_in_a_nick() {
    let nick = "Nicolò 名前 a\u{200D}b";

    assert_eq!(with_nick(nick).unwrap().nick, nick);
}

#[test]
fn should_reject_a_blank_nick() {
    assert!(with_nick(" ").is_err());
}

#[test]
fn should_reject_a_control_character_in_a_nick() {
    assert!(with_nick("a\u{1}b").is_err());
}

#[test]
fn should_reject_a_too_long_nick() {
    assert!(with_nick(&"x".repeat(51)).is_err());
}

#[test]
fn should_reject_an_invalid_tcp_port() {
    let refused = Preferences { tcp_port: 0, ..Preferences::default() }.validated();

    assert!(refused.unwrap_err().contains("tcpPort"));
}

#[test]
fn should_reject_an_invalid_udp_port() {
    // A port over 65535 does not fit the field: only a text can hold one.
    let changes = BTreeMap::from([("net.udpPort".to_owned(), "70000".to_owned())]);

    let refused = preferences_codec::apply(&Preferences::default(), &changes);

    assert!(refused.unwrap_err().contains("udpPort"));
}

#[test]
fn should_reject_an_invalid_maximum_of_connections() {
    let refused = Preferences { max_connections: 0, ..Preferences::default() }.validated();

    assert!(refused.unwrap_err().contains("maxConnections"));
}

#[test]
fn should_reject_an_unknown_log_level() {
    let refused = Preferences { log_level: "LOUD".to_owned(), ..Preferences::default() }.validated();

    assert!(refused.unwrap_err().contains("logLevel"));
}

#[test]
fn should_reject_a_relative_temp_directory() {
    let refused = Preferences { temp_dir: Some(PathBuf::from("rel")), ..Preferences::default() }.validated();

    assert!(refused.unwrap_err().contains("tempDir"));
}

#[test]
fn should_map_obfuscation_flags() {
    for (supported, requested, required, expected) in [
        (false, true, true, ObfuscationMode::Disabled),
        (true, false, false, ObfuscationMode::Supported),
        (true, true, false, ObfuscationMode::Requested),
        (true, false, true, ObfuscationMode::Required),
    ] {
        assert_eq!(ObfuscationMode::from_flags(supported, requested, required), expected);
    }
}
