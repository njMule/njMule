// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The country of IPv4 addresses, from DB-IP's "IP to Country Lite" (CC BY 4.0, IP Geolocation by DB-IP): ranges sorted
//! by their first address, those next to each other with the same country joined. Immutable; a lookup is a binary
//! search.

use std::io;
use std::net::Ipv4Addr;

use crate::util::white_space::strip;

const FORMAT: u32 = 1;
// Far more than the database holds: a file claiming more is not one of ours.
const MAX_RANGES: usize = 4_000_000;
const RECORD: usize = 10;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CountryTable {
    // First address, last address and two lower-case ASCII letters.
    ranges: Vec<(u32, u32, [u8; 2])>,
}

impl CountryTable {
    /// No country for any address.
    pub fn empty() -> Self {
        Self::default()
    }

    /// The table of the database's CSV: `first,last,country` lines; IPv6 lines and lines without a two-letter country,
    /// such as `ZZ` for the unknown, are left out. An error when no line holds an IPv4 range.
    pub fn parse(csv: &[u8]) -> io::Result<Self> {
        let mut ranges = Vec::new();
        for line in csv.split(|&b| b == b'\n') {
            let line = strip(std::str::from_utf8(line).unwrap_or_default());
            let fields: Vec<&str> = line.split(',').collect();
            let (Some(first), Some(last), Some(code)) = (fields.first(), fields.get(1), fields.get(2)) else {
                continue;
            };
            if line.contains(':') {
                continue;
            }
            let code = strip(code).replace('"', "").to_ascii_lowercase();
            let Ok([a, b]) = <[u8; 2]>::try_from(code.as_bytes()) else { continue };
            if !a.is_ascii_lowercase() || !b.is_ascii_lowercase() || code == "zz" {
                continue;
            }
            if let (Ok(first), Ok(last)) = (strip(first).parse::<Ipv4Addr>(), strip(last).parse::<Ipv4Addr>()) {
                ranges.push((u32::from(first), u32::from(last), [a, b]));
            }
        }
        if ranges.is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "no IPv4 range in the country database"));
        }
        Ok(Self::built(ranges))
    }

    /// The table as [`CountryTable::read`] takes it back.
    pub fn write(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.ranges.len().saturating_mul(RECORD).saturating_add(8));
        out.extend(FORMAT.to_be_bytes());
        out.extend(u32::try_from(self.ranges.len()).unwrap_or(u32::MAX).to_be_bytes());
        for (start, end, code) in &self.ranges {
            out.extend(start.to_be_bytes());
            out.extend(end.to_be_bytes());
            out.extend(code);
        }
        out
    }

    /// The table [`CountryTable::write`] wrote.
    pub fn read(data: &[u8]) -> io::Result<Self> {
        let refused = |reason: String| io::Error::new(io::ErrorKind::InvalidData, reason);
        let (Some(format), Some(count)) = (data.get(..4), data.get(4..8)) else {
            return Err(refused("a country table cut short".to_owned()));
        };
        if u32::from_be_bytes(format.try_into().unwrap_or_default()) != FORMAT {
            return Err(refused("not a country table of this version".to_owned()));
        }
        let count = usize::try_from(u32::from_be_bytes(count.try_into().unwrap_or_default())).unwrap_or(usize::MAX);
        let records = data.get(8..).unwrap_or_default();
        if count > MAX_RANGES || count > records.len() / RECORD {
            return Err(refused(format!("a country table cut short: {count} ranges")));
        }
        let (records, _) = records.as_chunks::<RECORD>();
        let ranges = records
            .iter()
            .take(count)
            .map(|&[a, b, c, d, e, f, g, h, first, second]| {
                (u32::from_be_bytes([a, b, c, d]), u32::from_be_bytes([e, f, g, h]), [first, second])
            })
            .collect();
        Ok(Self::built(ranges))
    }

    /// The ranges the table holds.
    pub fn size(&self) -> usize {
        self.ranges.len()
    }

    /// The country of `ip` ([`crate::util::ipv4`] form) as its ISO 3166-1 code in lower case; none when unknown.
    pub fn country(&self, ip: u32) -> Option<String> {
        let index = self.ranges.partition_point(|(start, _, _)| *start <= ip).checked_sub(1)?;
        let (_, end, [a, b]) = self.ranges.get(index)?;
        (ip <= *end).then(|| String::from_utf8_lossy(&[*a, *b]).into_owned())
    }

    /// The country of a client at `address`, `ip:port` as the API writes it; none for IPv6.
    pub fn country_of(&self, address: &str) -> Option<String> {
        if address.starts_with('[') || self.ranges.is_empty() {
            return None;
        }
        let (ip, _) = address.rsplit_once(':')?;
        self.country(u32::from(ip.parse::<Ipv4Addr>().ok()?))
    }

    // Ranges in any order, sorted and joined; one that overlaps those before it is left out.
    fn built(mut ranges: Vec<(u32, u32, [u8; 2])>) -> Self {
        ranges.retain(|(start, end, _)| start <= end);
        ranges.truncate(MAX_RANGES);
        ranges.sort_by_key(|(start, _, _)| *start);
        let mut joined: Vec<(u32, u32, [u8; 2])> = Vec::with_capacity(ranges.len());
        for (start, end, code) in ranges {
            match joined.last_mut() {
                Some(last) if last.2 == code && last.1.checked_add(1) == Some(start) => last.1 = end,
                Some(last) if start <= last.1 => {}
                _ => joined.push((start, end, code)),
            }
        }
        Self { ranges: joined }
    }
}
