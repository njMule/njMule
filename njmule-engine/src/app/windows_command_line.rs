// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A Windows command line, for the system to start a program with these parts.

/// The program and its arguments, each quoted as programs on Windows read their arguments.
pub fn of<S: AsRef<str>>(parts: &[S]) -> String {
    parts
        .iter()
        .map(|part| {
            let part = part.as_ref();
            // A backslash before the closing quote would escape it.
            let trailing = part.bytes().rev().take_while(|&b| b == b'\\').count();
            let escaped = part.replace('"', "\\\"");
            let pad = "\\".repeat(trailing);
            format!("\"{escaped}{pad}\"")
        })
        .collect::<Vec<_>>()
        .join(" ")
}
