// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's NotificationSoundCodecTest, under its names: the sound of the notifications as the preferences
//! file keeps it.

#![allow(clippy::unwrap_used)]

use njmule_engine::config::preferences::Preferences;
use njmule_engine::persist::preferences_codec;

#[test]
fn should_keep_the_notification_sound_through_the_file() {
    let directory = tempfile::tempdir().unwrap();
    let preferences = Preferences {
        notification_sound: true,
        notification_sound_file: Some(directory.path().join("my sounds").join("ding #1.wav")),
        ..Preferences::default()
    }
    .validated()
    .unwrap();

    let loaded = preferences_codec::parse(&preferences_codec::format(&preferences));

    assert_eq!(loaded, preferences);
}

#[test]
fn should_have_no_sound_by_default() {
    let loaded = preferences_codec::parse("");

    assert!(!loaded.notification_sound);
    assert_eq!(loaded.notification_sound_file, None);
}

#[test]
fn should_leave_out_a_relative_sound_file() {
    let loaded = preferences_codec::parse("notify.sound=true\nnotify.soundFile=ding.wav\n");

    assert!(loaded.notification_sound);
    assert_eq!(loaded.notification_sound_file, None);
}
