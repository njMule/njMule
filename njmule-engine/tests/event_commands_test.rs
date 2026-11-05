// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's EventCommandsTest, under their names.

#![allow(clippy::unwrap_used)]

use njmule_engine::config::event_commands::{self, Command, Event, EventCommands, MAX_COMMAND_LINE};

#[test]
fn should_split_a_command_line_at_spaces() {
    let arguments = event_commands::arguments("notify-send  njMule\t%NAME");

    assert_eq!(arguments.unwrap(), ["notify-send", "njMule", "%NAME"]);
}

#[test]
fn should_keep_spaces_and_backslashes_inside_double_quotes() {
    let arguments = event_commands::arguments("\"C:\\Program Files\\Tools\\tell.exe\" --title \"Download done\"");

    assert_eq!(arguments.unwrap(), ["C:\\Program Files\\Tools\\tell.exe", "--title", "Download done"]);
}

#[test]
fn should_read_single_quotes_and_doubled_quotes() {
    let arguments = event_commands::arguments("echo 'it''s' \"say \"\"hi\"\"\" a\"b c\"d \"\"");

    assert_eq!(arguments.unwrap(), ["echo", "it's", "say \"hi\"", "ab cd", ""]);
}

#[test]
fn should_refuse_a_quote_left_open() {
    let arguments = event_commands::arguments("player \"my file");

    assert!(arguments.is_err());
}

#[test]
fn should_give_no_arguments_for_an_empty_command_line() {
    let arguments = event_commands::arguments("   ");

    assert_eq!(arguments.unwrap(), Vec::<String>::new());
}

#[test]
fn should_refuse_a_command_line_of_several_lines() {
    let command = Command::new(true, "first\nsecond");

    assert!(command.is_err());
}

#[test]
fn should_replace_the_command_of_one_event_only() {
    let beep = Command::new(true, "beep").unwrap();

    let commands = EventCommands::default().with(Event::ChatMessage, beep.clone());

    assert_eq!(commands.get(Event::ChatMessage), &beep);
    for event in [Event::DownloadCompleted, Event::DownloadFailed, Event::DiskSpaceShort] {
        assert_eq!(commands.get(event), &Command::default(), "{}", event.id());
    }
}

#[test]
fn should_strip_a_command_line_and_refuse_a_longer_one_than_its_limit() {
    let stripped = Command::new(false, "  beep  ").unwrap();

    assert_eq!((stripped.enabled(), stripped.command_line()), (false, "beep"));
    assert!(Command::new(true, &"x".repeat(MAX_COMMAND_LINE)).is_ok());
    assert!(Command::new(true, &"x".repeat(MAX_COMMAND_LINE + 1)).is_err());
}

#[test]
fn should_name_every_event_as_its_files_and_texts_do() {
    let ids: Vec<&str> = Event::ALL.iter().map(|event| event.id()).collect();

    assert_eq!(ids, ["downloadCompleted", "downloadFailed", "chatMessage", "diskSpaceShort"]);
}
