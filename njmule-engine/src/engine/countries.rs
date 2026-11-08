// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The countries of DB-IP, kept in the cache: read at the first check, fetched again once a month old. Turned off in
//! the preferences, the table is let go.

use std::path::Path;
use std::time::{Duration, Instant, SystemTime};

use time::{Month, OffsetDateTime};

use crate::net::country_table::CountryTable;
use crate::net::web_lists::WebLists;
use crate::persist::atomic_files;

/// The file of the cache the table is kept in.
pub const FILE: &str = "countries.dat";
/// When the table is first looked at after the start.
pub const FIRST_CHECK: Duration = Duration::from_secs(5);
/// How often it is looked at then.
pub const CHECK: Duration = Duration::from_secs(60 * 60);
/// How long a failed fetch waits before the next.
pub const RETRY: Duration = Duration::from_secs(6 * 60 * 60);
/// How old the table may be before it is fetched again.
pub const AGE: Duration = Duration::from_secs(32 * 24 * 60 * 60);
const URL: &str = "https://download.db-ip.com/free/dbip-country-lite-";

/// What a check does with the table.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Step {
    Nothing,
    /// Reads the copy of the cache.
    Read,
    /// Fetches it from DB-IP.
    Fetch,
}

/// What a check does: the table is fetched when it may be, its copy is missing or a month old, and the last failed
/// fetch is old enough; else read when none is held yet and the cache has a copy.
pub fn step(may_fetch: bool, written: Option<SystemTime>, now: SystemTime, tried: Option<Instant>, held: bool) -> Step {
    let old = written.is_none_or(|written| now.duration_since(written).is_ok_and(|age| age >= AGE));
    let waited = tried.is_none_or(|tried| tried.elapsed() >= RETRY);
    if may_fetch && old && waited {
        Step::Fetch
    } else if !held && written.is_some() {
        Step::Read
    } else {
        Step::Nothing
    }
}

/// The copy the cache keeps under `cache`.
pub fn read(cache: &Path) -> Result<CountryTable, String> {
    let data = std::fs::read(cache.join(FILE)).map_err(|e| e.to_string())?;
    CountryTable::read(&data).map_err(|e| e.to_string())
}

/// When the copy of the cache was written; none without one.
pub fn written(cache: &Path) -> Option<SystemTime> {
    std::fs::metadata(cache.join(FILE)).and_then(|metadata| metadata.modified()).ok()
}

/// This month's database, or last month's while this one is not out yet, kept in `cache`.
pub async fn fetch(web: &WebLists, cache: &Path) -> Result<CountryTable, String> {
    let now = OffsetDateTime::now_utc();
    let this_month = (now.year(), now.month());
    let last_month = match now.month() {
        Month::January => (now.year().saturating_sub(1), Month::December),
        month => (now.year(), month.previous()),
    };
    let mut first = None;
    for (year, month) in [this_month, last_month] {
        let url = format!("{URL}{year:04}-{:02}.csv.gz", u8::from(month));
        match web.fetch(&url).await.and_then(|data| CountryTable::parse(&data)) {
            Ok(table) => {
                std::fs::create_dir_all(cache).map_err(|e| e.to_string())?;
                atomic_files::write(&cache.join(FILE), &table.write()).map_err(|e| e.to_string())?;
                return Ok(table);
            }
            Err(e) => {
                first.get_or_insert(e.to_string());
            }
        }
    }
    Err(first.unwrap_or_default())
}
