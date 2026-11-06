// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The programs the user runs when something happens: a download completes or fails, a chat message arrives, the temp
//! disk runs short of space. No shell runs them: [`arguments`] splits a command line into the program and its
//! arguments, and the placeholders of each event are filled in inside the arguments.

/// Characters of a command line at most, in UTF-16 units as the Java engine counts them.
pub const MAX_COMMAND_LINE: usize = 4096;

/// What a command can run on.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Event {
    DownloadCompleted,
    DownloadFailed,
    ChatMessage,
    DiskSpaceShort,
}

impl Event {
    pub const ALL: [Self; 4] = [Self::DownloadCompleted, Self::DownloadFailed, Self::ChatMessage, Self::DiskSpaceShort];

    /// A short name in lowerCamelCase, for files and texts.
    pub fn id(self) -> &'static str {
        match self {
            Self::DownloadCompleted => "downloadCompleted",
            Self::DownloadFailed => "downloadFailed",
            Self::ChatMessage => "chatMessage",
            Self::DiskSpaceShort => "diskSpaceShort",
        }
    }
}

/// One event's command: whether it runs, and its command line.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Command {
    enabled: bool,
    command_line: String,
}

impl Command {
    /// A command of one line of printable characters, stripped, of at most [`MAX_COMMAND_LINE`].
    pub fn new(enabled: bool, command_line: &str) -> Result<Self, String> {
        let command_line = command_line.trim();
        if command_line.encode_utf16().count() > MAX_COMMAND_LINE {
            return Err(format!("a command line must be at most {MAX_COMMAND_LINE} characters"));
        }
        if command_line.chars().any(char::is_control) {
            return Err("a command line must be one line of printable characters".to_owned());
        }
        Ok(Self { enabled, command_line: command_line.to_owned() })
    }

    pub fn enabled(&self) -> bool {
        self.enabled
    }

    pub fn command_line(&self) -> &str {
        &self.command_line
    }
}

/// The command of each event; by default none runs.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct EventCommands {
    download_completed: Command,
    download_failed: Command,
    chat_message: Command,
    disk_space_short: Command,
}

impl EventCommands {
    pub fn get(&self, event: Event) -> &Command {
        match event {
            Event::DownloadCompleted => &self.download_completed,
            Event::DownloadFailed => &self.download_failed,
            Event::ChatMessage => &self.chat_message,
            Event::DiskSpaceShort => &self.disk_space_short,
        }
    }

    /// These commands with `command` for `event`.
    pub fn with(&self, event: Event, command: Command) -> Self {
        let mut changed = self.clone();
        *match event {
            Event::DownloadCompleted => &mut changed.download_completed,
            Event::DownloadFailed => &mut changed.download_failed,
            Event::ChatMessage => &mut changed.chat_message,
            Event::DiskSpaceShort => &mut changed.disk_space_short,
        } = command;
        changed
    }
}

/// The program and its arguments in `command_line`: they are apart by spaces; double or single quotes keep spaces in
/// one, and two quotes of the same kind inside them stand for one. Backslashes are plain characters, as in Windows
/// paths, so shell-word crates, which read them as escapes, do not fit. A quote left open is an error.
pub fn arguments(command_line: &str) -> Result<Vec<String>, String> {
    let mut arguments = Vec::new();
    let mut current = String::new();
    let mut in_argument = false;
    let mut quote = None;
    let mut chars = command_line.chars().peekable();
    while let Some(c) = chars.next() {
        match quote {
            Some(open) if c == open => {
                if chars.next_if_eq(&open).is_some() {
                    current.push(c);
                } else {
                    quote = None;
                }
            }
            Some(_) => current.push(c),
            None if c == '"' || c == '\'' => {
                quote = Some(c);
                in_argument = true;
            }
            None if c == ' ' || c == '\t' => {
                if in_argument {
                    arguments.push(std::mem::take(&mut current));
                    in_argument = false;
                }
            }
            None => {
                current.push(c);
                in_argument = true;
            }
        }
    }
    if let Some(open) = quote {
        return Err(format!("a quote is not closed: {open}"));
    }
    if in_argument {
        arguments.push(current);
    }
    Ok(arguments)
}
