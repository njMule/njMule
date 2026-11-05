// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! IP filter lists: `ipfilter.dat` lines `a.b.c.d - e.f.g.h , level , description`, PeerGuardian text lines
//! `description:a.b.c.d-e.f.g.h` and the PeerGuardian binary P2B format (`FF FF FF FF 'P2B' <version 1|2>` then `<name
//! NUL><start BE><end BE>`). An address is blocked when its range level is below the configured filter level.
//!
//! Text lines are read as UTF-16 units, as their positions and lengths are counted in those.

use std::collections::{BTreeMap, HashMap};
use std::net::Ipv4Addr;

use crate::persist::format::text_files;
use crate::protocol::io::ed2k_strings;
use crate::util::white_space::strip;

/// Level of ranges that do not give one.
pub const DEFAULT_LEVEL: u32 = 100;
const P2B_MAGIC: [u8; 7] = [0xFF, 0xFF, 0xFF, 0xFF, b'P', b'2', b'B'];
const MIN_LINE: usize = 15;
const MAX_DESCRIPTION: usize = 255;

/// `start` and `end` are inclusive, in [`crate::util::ipv4`] form.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Range {
    pub start: u32,
    pub end: u32,
    pub level: u32,
    pub description: String,
}

impl Range {
    pub fn contains(&self, ip: u32) -> bool {
        (self.start..=self.end).contains(&ip)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    Unknown,
    FilterDat,
    PeerGuardian,
}

/// Parses any of the formats, then sorts by start and merges the overlapping and adjacent ranges of one level;
/// identical ranges keep the lowest level, and ranges of other levels stay apart even where they overlap.
///
/// `file_name` is used only to recognise `.p2p` and `.prefix` files.
pub fn read(data: &[u8], file_name: &str) -> Vec<Range> {
    let ranges = if data.starts_with(&P2B_MAGIC) { p2b(data) } else { text(data, kind_of(file_name)) };
    merge(ranges)
}

/// The ipfilter.dat text form.
pub fn write(ranges: &[Range]) -> Vec<u8> {
    let mut out = String::new();
    for range in ranges {
        let start = Ipv4Addr::from(range.start).to_string();
        let end = Ipv4Addr::from(range.end).to_string();
        out.push_str(&format!("{start:<15} - {end:<15} , {:3} , {}\n", range.level, range.description));
    }
    out.into_bytes()
}

fn kind_of(file_name: &str) -> Kind {
    let name = file_name.to_lowercase();
    if name.ends_with(".p2p") || name == "guarding.p2p.txt" {
        Kind::PeerGuardian
    } else if name.ends_with(".prefix") {
        Kind::FilterDat
    } else {
        Kind::Unknown
    }
}

const fn unit(c: u8) -> u16 {
    c as u16
}

fn text(data: &[u8], hint: Kind) -> Vec<Range> {
    let mut kind = hint;
    let mut ranges = Vec::new();
    for raw in text_files::lines(data) {
        let mut line: Vec<u16> = strip(&raw).encode_utf16().collect();
        if line.len() < MIN_LINE || line.first().is_some_and(|c| *c == unit(b'#') || *c == unit(b'/')) {
            continue;
        }
        if kind == Kind::Unknown {
            if line.contains(&unit(b'<'))
                && let Some(tag_end) = line.iter().rposition(|c| *c == unit(b'>'))
            {
                line = line.split_off(tag_end.saturating_add(1));
            }
            if range_at(&line, 0).is_some() {
                kind = Kind::FilterDat;
            } else if let Some(colon) = line.iter().position(|c| *c == unit(b':'))
                && range_at(&line, colon.saturating_add(1)).is_some()
            {
                kind = Kind::PeerGuardian;
            }
        }
        let range = match kind {
            Kind::FilterDat => filter_dat_line(&line),
            Kind::PeerGuardian => peer_guardian_line(&line),
            Kind::Unknown => None,
        };
        ranges.extend(range);
    }
    ranges
}

fn filter_dat_line(line: &[u16]) -> Option<Range> {
    let (start, end, after) = range_at(line, 0)?;
    let mut level = DEFAULT_LEVEL;
    let mut description = String::new();
    let rest = line
        .get(after..)
        .and_then(|tail| tail.iter().position(|c| *c == unit(b',')))
        .and_then(|comma| line.get(after.saturating_add(comma).saturating_add(1)..));
    if let Some(rest) = rest {
        let second = rest.iter().position(|c| *c == unit(b','));
        let level_text = string(second.and_then(|second| rest.get(..second)).unwrap_or(rest));
        let level_text = strip(&level_text);
        if !level_text.is_empty() && level_text.chars().all(|c| c.is_ascii_digit()) {
            // A level has three digits; lists that write more are read by their first three.
            level = level_text.get(..3).unwrap_or(level_text).parse().unwrap_or(DEFAULT_LEVEL);
            if let Some(after_level) = second.and_then(|second| rest.get(second.saturating_add(1)..)) {
                description = strip(&string(after_level)).to_owned();
            }
        }
    }
    range(start, end, level, &description)
}

fn peer_guardian_line(line: &[u16]) -> Option<Range> {
    let colon = line.iter().rposition(|c| *c == unit(b':'))?;
    let (start, end, _) = range_at(line, colon.saturating_add(1))?;
    let description = string(line.get(..colon)?).replace("PGIPDB", "");
    range(start, end, DEFAULT_LEVEL, strip(&description))
}

fn string(units: &[u16]) -> String {
    String::from_utf16_lossy(units)
}

fn range(start: u32, end: u32, level: u32, description: &str) -> Option<Range> {
    if start > end {
        return None;
    }
    let mut length = 0;
    let description: String = description
        .chars()
        .take_while(|c| {
            length = c.len_utf16().saturating_add(length);
            length <= MAX_DESCRIPTION
        })
        .collect();
    Some(Range { start, end, level, description })
}

/// `a.b.c.d - e.f.g.h` at `from` (spaces allowed): start, end and the position after it.
fn range_at(line: &[u16], from: usize) -> Option<(u32, u32, usize)> {
    let mut at = skip_spaces(line, from);
    let start = ip(line, &mut at)?;
    at = skip_spaces(line, at);
    if line.get(at) != Some(&unit(b'-')) {
        return None;
    }
    at = skip_spaces(line, at.saturating_add(1));
    let end = ip(line, &mut at)?;
    Some((start, end, at))
}

fn ip(line: &[u16], at: &mut usize) -> Option<u32> {
    let mut octets = [0u8; 4];
    let mut i = *at;
    for (part, octet) in octets.iter_mut().enumerate() {
        if part > 0 {
            if line.get(i) != Some(&unit(b'.')) {
                return None;
            }
            i = i.saturating_add(1);
        }
        let digits: Vec<u16> =
            line.get(i..)?.iter().take(3).take_while(|c| (unit(b'0')..=unit(b'9')).contains(*c)).copied().collect();
        *octet = string(&digits).parse().ok()?;
        i = i.saturating_add(digits.len());
    }
    *at = i;
    Some(u32::from_be_bytes(octets))
}

fn skip_spaces(line: &[u16], from: usize) -> usize {
    let spaces = line
        .get(from..)
        .map_or(0, |tail| tail.iter().take_while(|c| **c == unit(b' ') || **c == unit(b'\t')).count());
    from.saturating_add(spaces)
}

fn p2b(data: &[u8]) -> Vec<Range> {
    let mut ranges = Vec::new();
    let utf8 = match data.get(P2B_MAGIC.len()) {
        Some(1) => false,
        Some(2) => true,
        _ => return ranges,
    };
    let mut rest = data.get(P2B_MAGIC.len().saturating_add(1)..).unwrap_or_default();
    while !rest.is_empty() {
        let nul = rest.iter().position(|b| *b == 0).unwrap_or(rest.len());
        let Some((name, addresses)) = rest.get(..nul).zip(rest.get(nul.saturating_add(1)..)) else {
            break;
        };
        let Some((start, after_start)) = addresses.split_first_chunk::<4>() else {
            break;
        };
        let Some((end, after)) = after_start.split_first_chunk::<4>() else {
            break;
        };
        let name = name.get(..MAX_DESCRIPTION).unwrap_or(name);
        let name =
            if utf8 { ed2k_strings::decode(name) } else { name.iter().copied().map(char::from).collect::<String>() };
        let (start, end) = (u32::from_be_bytes(*start), u32::from_be_bytes(*end));
        if start <= end {
            ranges.push(Range { start, end, level: DEFAULT_LEVEL, description: name });
        }
        rest = after;
    }
    ranges
}

// Only ranges of one level merge: a range overlapping one of another level keeps blocking at its own level.
fn merge(input: Vec<Range>) -> Vec<Range> {
    let mut distinct: Vec<Range> = Vec::new();
    let mut seen: HashMap<(u32, u32), usize> = HashMap::new();
    for range in input {
        match seen.get(&(range.start, range.end)).and_then(|i| distinct.get_mut(*i)) {
            Some(kept) => kept.level = kept.level.min(range.level),
            None => {
                seen.insert((range.start, range.end), distinct.len());
                distinct.push(range);
            }
        }
    }
    let mut by_level: BTreeMap<u32, Vec<Range>> = BTreeMap::new();
    for range in distinct {
        by_level.entry(range.level).or_default().push(range);
    }
    let mut out: Vec<Range> = by_level.into_values().flat_map(merge_one_level).collect();
    out.sort_by_key(|range| (range.start, range.end));
    out
}

fn merge_one_level(mut ranges: Vec<Range>) -> Vec<Range> {
    ranges.sort_by_key(|range| range.start);
    let mut out: Vec<Range> = Vec::with_capacity(ranges.len());
    for current in ranges {
        if let Some(previous) = out.last_mut()
            && (current.start <= previous.end || previous.end.checked_add(1) == Some(current.start))
        {
            previous.end = previous.end.max(current.end);
        } else {
            out.push(current);
        }
    }
    out
}
