// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Line-based files: `staticservers.dat` (`host:port,priority,name`), `shareddir.dat` and `sharedfiles.dat` (one path
//! per line, `-` excluding a file), `addresses.dat` (server list URLs) and the `AC_*.dat` histories. They are UTF-16LE
//! with a BOM when written; ANSI and UTF-8 files are read too.

use crate::protocol::io::ed2k_strings;
use crate::util::white_space::strip;

/// Server priorities as stored: normal 0, high 1, low 2.
pub const PRIORITY_NORMAL: u8 = 0;
pub const PRIORITY_HIGH: u8 = 1;
pub const PRIORITY_LOW: u8 = 2;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StaticServer {
    pub host: String,
    pub port: u16,
    pub priority: u8,
    pub name: String,
}

/// A line of `sharedfiles.dat`: a file shared explicitly, or excluded (`-`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SharedFile {
    pub path: String,
    pub excluded: bool,
}

const UTF16LE_BOM: [u8; 2] = [0xFF, 0xFE];

/// The lines of a text file, without line ends (`\n`, `\r` or `\r\n`), decoding a UTF-16LE or UTF-8 BOM.
pub fn lines(data: &[u8]) -> Vec<String> {
    let text = match data.strip_prefix(&UTF16LE_BOM) {
        Some(utf16) => {
            let (units, odd) = utf16.as_chunks::<2>();
            let mut text =
                String::from_utf16_lossy(&units.iter().map(|unit| u16::from_le_bytes(*unit)).collect::<Vec<_>>());
            if !odd.is_empty() {
                text.push(char::REPLACEMENT_CHARACTER);
            }
            text
        }
        None => ed2k_strings::decode(data),
    };
    let mut lines = Vec::new();
    let mut rest = text.as_str();
    while !rest.is_empty() {
        match rest.find(['\r', '\n']) {
            Some(end) => {
                let (line, terminated) = rest.split_at(end);
                lines.push(line.to_owned());
                rest = terminated.strip_prefix("\r\n").or_else(|| terminated.get(1..)).unwrap_or_default();
            }
            None => {
                lines.push(rest.to_owned());
                rest = "";
            }
        }
    }
    lines
}

/// UTF-16LE with a BOM and CRLF line ends.
pub fn write_lines(lines: &[String]) -> Vec<u8> {
    let mut text = String::from('\u{FEFF}');
    for line in lines {
        text.push_str(line);
        text.push_str("\r\n");
    }
    text.encode_utf16().flat_map(u16::to_le_bytes).collect()
}

/// Parses `host:port,priority,name`; comment lines start with `#` or `/`.
pub fn static_servers(data: &[u8]) -> Vec<StaticServer> {
    lines(data).iter().filter_map(|line| static_server(line)).collect()
}

fn static_server(line: &str) -> Option<StaticServer> {
    if line.encode_utf16().count() < 5 || line.starts_with(['#', '/']) {
        return None;
    }
    let host_end = line.find(':').or_else(|| line.find(','))?;
    let (host, rest) = line.split_at(host_end);
    let host = strip(host);
    let rest = rest.get(1..)?;
    let comma = rest.find(',')?;
    let (port, rest) = rest.split_at(comma);
    let port = leading_int(port);
    let mut name = rest.get(1..)?;
    let mut priority = PRIORITY_HIGH;
    // A priority is one character before a second comma.
    if let Some(comma) = name.find(',')
        && let Some(digit) = name.get(..comma).filter(|digit| digit.encode_utf16().count() == 1)
    {
        priority = u8::try_from(leading_int(digit))
            .ok()
            .filter(|p| (PRIORITY_NORMAL..=PRIORITY_LOW).contains(p))
            .unwrap_or(PRIORITY_HIGH);
        name = name.get(comma.saturating_add(1)..)?;
    }
    let port = u16::try_from(port).ok().filter(|port| *port != 0)?;
    (!host.is_empty()).then(|| StaticServer { host: host.to_owned(), port, priority, name: name.to_owned() })
}

pub fn write_static_servers(servers: &[StaticServer]) -> Vec<u8> {
    let lines: Vec<String> = servers
        .iter()
        .map(|server| format!("{}:{},{},{}", server.host, server.port, server.priority, server.name))
        .collect();
    write_lines(&lines)
}

pub fn shared_files(data: &[u8]) -> Vec<SharedFile> {
    lines(data)
        .iter()
        .map(|line| strip(line))
        .filter(|path| !path.is_empty())
        .map(|path| match path.strip_prefix('-') {
            Some(excluded) => SharedFile { path: excluded.to_owned(), excluded: true },
            None => SharedFile { path: path.to_owned(), excluded: false },
        })
        .collect()
}

/// Non-empty lines, stripped: shared directories, server list URLs, histories.
pub fn entries(data: &[u8]) -> Vec<String> {
    lines(data).iter().map(|line| strip(line)).filter(|entry| !entry.is_empty()).map(str::to_owned).collect()
}

// The number the text starts with, up to nine digits; 0 without one.
fn leading_int(text: &str) -> u32 {
    let digits: String = strip(text).chars().take(9).take_while(char::is_ascii_digit).collect();
    digits.parse().unwrap_or(0)
}
