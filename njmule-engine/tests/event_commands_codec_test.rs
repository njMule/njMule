// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's EventCommandsCodecTest, under its names: the commands as the preferences file keeps them.

#![allow(clippy::unwrap_used)]

use njmule_engine::config::event_commands::{Command, Event};
use njmule_engine::config::preferences::Preferences;
use njmule_engine::persist::preferences_codec;

#[test]
fn should_keep_every_event_command_through_the_file() {
    let mut preferences = Preferences::default();
    for (event, enabled, line) in [
        (Event::DownloadCompleted, true, "\"C:\\Tools\\done.exe\" %FILE"),
        (Event::DownloadFailed, false, "alert = %NAME: %MESSAGE"),
        (Event::ChatMessage, true, "notify-send '%SENDER' #%MESSAGE"),
        (Event::DiskSpaceShort, true, "warn %FILE"),
    ] {
        preferences.event_commands = preferences.event_commands.with(event, Command::new(enabled, line).unwrap());
    }
    let text = preferences_codec::format(&preferences);

    let loaded = preferences_codec::parse(&text);

    assert_eq!(loaded, preferences);
    assert!(text.lines().any(|line| line == "events.chatMessage.enabled=true"), "{text}");
}

#[test]
fn should_read_a_hand_edited_switch_after_its_command_line() {
    let loaded = preferences_codec::parse("events.diskSpaceShort.command=beep\nevents.diskSpaceShort.enabled=on\n");

    assert_eq!(loaded.event_commands.get(Event::DiskSpaceShort), &Command::new(true, "beep").unwrap());
}

#[test]
fn should_keep_the_default_for_a_command_line_of_several_lines() {
    let loaded = preferences_codec::parse(
        "events.downloadCompleted.command=first\\nsecond\nevents.downloadCompleted.enabled=true\n",
    );

    assert_eq!(loaded.event_commands.get(Event::DownloadCompleted), &Command::new(true, "").unwrap());
}
