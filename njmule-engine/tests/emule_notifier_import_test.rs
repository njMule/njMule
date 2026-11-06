// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's EmuleNotifierImportTest, under its names.

#![allow(clippy::unwrap_used)]

use njmule_engine::config::preferences::Preferences;
use njmule_engine::persist::emule_importer;
use njmule_engine::persist::format::ini_file::IniFile;

fn imported(before: &Preferences, says: &[(&str, &str)]) -> Preferences {
    let mut text = "[eMule]\n".to_owned();
    for (key, value) in says {
        text.push_str(&format!("{key}={value}\n"));
    }
    emule_importer::map_preferences(&IniFile::parse(text.as_bytes()), before).unwrap()
}

fn sound_on() -> Preferences {
    Preferences { notification_sound: true, ..Preferences::default() }
}

#[test]
fn should_take_the_sound_file_emule_plays_with_its_notifications() {
    let dir = tempfile::tempdir().unwrap();
    let ding = dir.path().join("ding.wav");

    let imported = imported(
        &Preferences::default(),
        &[("NotifierUseSound", "1"), ("NotifierSoundPath", &ding.display().to_string())],
    );

    assert!(imported.notification_sound);
    assert_eq!(imported.notification_sound_file, Some(ding));
}

#[test]
fn should_keep_the_sound_off_when_emule_speaks_instead() {
    let imported = imported(&sound_on(), &[("NotifierUseSound", "2")]);

    assert!(!imported.notification_sound);
}

#[test]
fn should_leave_out_a_relative_sound_file() {
    let imported =
        imported(&Preferences::default(), &[("NotifierUseSound", "1"), ("NotifierSoundPath", "sounds\\ding.wav")]);

    assert!(imported.notification_sound);
    assert_eq!(imported.notification_sound_file, None);
}

#[test]
fn should_keep_our_sound_when_emule_says_nothing_of_it() {
    let imported = imported(&sound_on(), &[]);

    assert!(imported.notification_sound);
}
