// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The IP filter from the web: lists fetched by their addresses and joined into one, fetched again once the filter is
//! as old as the preferences say.

use std::time::{Duration, SystemTime};

use reqwest::Url;

use crate::net::web_lists::WebLists;
use crate::persist::format::ip_filter_dat::{self, Range};

/// How often the lists of the preferences are looked at.
pub const CHECK: Duration = Duration::from_secs(60 * 60);
/// How long a failed update of the lists waits before the next.
pub const RETRY: Duration = Duration::from_secs(6 * 60 * 60);
/// When the lists are first looked at after the start.
pub const FIRST_CHECK: Duration = Duration::from_secs(60);

/// The ranges of a list fetched from `url`. The text forms are told apart by the file name: the address's first,
/// then PeerGuardian, then the ipfilter.dat form.
pub fn ip_ranges(data: &[u8], url: &str) -> Vec<Range> {
    let path = Url::parse(url).ok().filter(|url| !url.cannot_be_a_base()).map(|url| url.path().to_owned());
    let path = path.unwrap_or_default();
    let name = path.rsplit('/').next().unwrap_or_default();
    let lower = name.to_ascii_lowercase();
    let name = [".gz", ".zip"]
        .iter()
        .find_map(|packed| lower.strip_suffix(packed).and_then(|kept| name.get(..kept.len())))
        .unwrap_or(name);
    [name, "guarding.p2p", "ipfilter.dat"]
        .into_iter()
        .map(|candidate| ip_filter_dat::read(data, candidate))
        .find(|ranges| !ranges.is_empty())
        .unwrap_or_default()
}

/// Fetches the lists at once and joins their ranges, as [`joined`] does.
pub async fn fetch_joined(web: &WebLists, lists: &[String], warn: impl Fn(String)) -> Result<Vec<Range>, String> {
    let fetched = futures_util::future::join_all(lists.iter().map(|url| fetch(web, url))).await;
    joined(lists, fetched, warn)
}

/// The ranges of the `lists` that could be fetched and read, in their order; each other one is left out and told
/// through `warn`. An error, the first reason, when none could.
pub fn joined(
    lists: &[String],
    fetched: Vec<Result<Vec<Range>, String>>,
    warn: impl Fn(String),
) -> Result<Vec<Range>, String> {
    let mut ranges = Vec::new();
    let mut failure = None;
    let mut read = 0_usize;
    for (url, list) in lists.iter().zip(fetched) {
        match list {
            Ok(list) if !list.is_empty() => {
                ranges.extend(list);
                read = read.saturating_add(1);
            }
            outcome => {
                let reason = outcome.err().unwrap_or_else(|| "no IP filter ranges".to_owned());
                let host = Url::parse(url).ok().and_then(|url| url.host_str().map(str::to_owned)).unwrap_or_default();
                warn(format!("IP filter list {host} left out: {reason}"));
                failure.get_or_insert(reason);
            }
        }
    }
    if read == 0 {
        return Err(failure.unwrap_or_else(|| "no IP filter list".to_owned()));
    }
    Ok(ranges)
}

/// The ranges of the list at `url`, none when it holds none; an error when it cannot be fetched.
pub async fn fetch(web: &WebLists, url: &str) -> Result<Vec<Range>, String> {
    let data = web.fetch_text(url).await.map_err(|e| e.to_string())?;
    Ok(ip_ranges(&data, url))
}

/// Whether the `lists` are due again: the filter was last written at `filter_date` (none for no filter), `days` ago or
/// more; never with no lists or `days` 0.
pub fn is_due(filter_date: Option<SystemTime>, now: SystemTime, days: u32, lists: &[String]) -> bool {
    if lists.is_empty() || days == 0 {
        return false;
    }
    let age = Duration::from_secs(u64::from(days).saturating_mul(24 * 60 * 60));
    filter_date.is_none_or(|date| now.duration_since(date).is_ok_and(|since| since >= age))
}

/// The web addresses among the lines of `text`, blank ones left out.
pub fn addresses(text: &str) -> Vec<String> {
    text.lines().map(str::trim).filter(|line| !line.is_empty()).map(str::to_owned).collect()
}
