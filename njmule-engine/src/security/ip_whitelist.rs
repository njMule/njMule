// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The addresses the user always allows, whatever the IP filter says: one entry per line, an address (`1.2.3.4`), a CIDR
//! block (`1.2.3.0/24`) or a range as ipfilter.dat writes it (`1.2.3.0 - 1.2.3.255 , 100 , name`, the level left
//! aside). Empty lines and lines starting with `#` or `//` are comments.

use crate::persist::format::ip_filter_dat::{self, Range};
use crate::util::white_space::strip;

const MAX_PREFIX_DIGITS: usize = 2;

/// The ranges of `text`, or the first entry that is none of the forms.
pub fn ranges(text: &str) -> Result<Vec<Range>, String> {
    entries(text)
        .map(|entry| range(entry).ok_or_else(|| format!("not an address, CIDR block or range: {entry}")))
        .collect()
}

/// The first entry of `text` that is not an address, a CIDR block or a range, if any.
pub fn first_invalid_entry(text: &str) -> Option<String> {
    entries(text).find(|entry| range(entry).is_none()).map(str::to_owned)
}

fn entries(text: &str) -> impl Iterator<Item = &str> {
    text.split(['\r', '\n'])
        .map(strip)
        .filter(|line| !line.is_empty() && !line.starts_with('#') && !line.starts_with("//"))
}

fn range(entry: &str) -> Option<Range> {
    // A range line has a dash, a level or a PeerGuardian name; its name may hold a slash.
    if entry.contains(['-', ',', ':']) {
        return filter_line(entry);
    }
    let Some((address, prefix)) = entry.split_once('/') else {
        return filter_line(&format!("{entry} - {entry}"));
    };
    let prefix = strip(prefix);
    if prefix.is_empty() || prefix.len() > MAX_PREFIX_DIGITS || !prefix.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let bits: u32 = prefix.parse().ok()?;
    let address = strip(address);
    if bits > 32 || address.is_empty() {
        return None;
    }
    // The address is read as ipfilter.dat reads them; the standard library has no CIDR parser.
    let read = filter_line(&format!("{address} - {address}"))?;
    let mask = u32::MAX.checked_shl(32_u32.saturating_sub(bits)).unwrap_or(0);
    let start = read.start & mask;
    Some(Range { start, end: start | !mask, level: ip_filter_dat::DEFAULT_LEVEL, description: String::new() })
}

// One ipfilter.dat or PeerGuardian line, all of it a range: what follows the addresses is only level and name.
fn filter_line(line: &str) -> Option<Range> {
    let mut read = ip_filter_dat::read(line.as_bytes(), "");
    if read.len() == 1 { read.pop() } else { None }
}
