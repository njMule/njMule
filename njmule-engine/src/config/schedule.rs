// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Times of the week when other limits apply: during a rule's window its values replace those of the preferences, which
//! stay as they are; the first enabled rule whose window holds wins.

use std::collections::BTreeSet;

use strum::{EnumString, IntoStaticStr, VariantArray};
use time::{PrimitiveDateTime, Time, Weekday};

use crate::config::preferences::{MAX_CONNECTIONS, MAX_RATE_KIBPS, MAX_SOURCES_PER_FILE, Preferences};
use crate::util::white_space::strip;

/// A day of the week, named as the files and the API name it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, EnumString, IntoStaticStr, VariantArray)]
#[strum(serialize_all = "SCREAMING_SNAKE_CASE", ascii_case_insensitive)]
pub enum DayOfWeek {
    Monday,
    Tuesday,
    Wednesday,
    Thursday,
    Friday,
    Saturday,
    Sunday,
}

impl DayOfWeek {
    pub fn name(self) -> &'static str {
        self.into()
    }

    pub fn of(weekday: Weekday) -> Self {
        match weekday {
            Weekday::Monday => Self::Monday,
            Weekday::Tuesday => Self::Tuesday,
            Weekday::Wednesday => Self::Wednesday,
            Weekday::Thursday => Self::Thursday,
            Weekday::Friday => Self::Friday,
            Weekday::Saturday => Self::Saturday,
            Weekday::Sunday => Self::Sunday,
        }
    }
}

/// One window of the week and the limits it sets; a window that ends before it starts runs past midnight, one that
/// ends when it starts lasts the whole day. When it begins, it can stop the downloads of one category and resume those
/// of another (0 for the downloads in none).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rule {
    pub name: String,
    pub enabled: bool,
    pub days: BTreeSet<DayOfWeek>,
    pub start: Time,
    pub end: Time,
    pub upload_kibps: Option<u32>,
    pub download_kibps: Option<u32>,
    pub max_connections: Option<u32>,
    pub max_sources_per_file: Option<u32>,
    pub stop_category: Option<u32>,
    pub resume_category: Option<u32>,
}

impl Rule {
    /// A rule that sets limits only, enabled, with no days and a window of the whole day.
    pub fn new(name: &str) -> Self {
        Self {
            name: name.to_owned(),
            enabled: true,
            days: BTreeSet::new(),
            start: Time::MIDNIGHT,
            end: Time::MIDNIGHT,
            upload_kibps: None,
            download_kibps: None,
            max_connections: None,
            max_sources_per_file: None,
            stop_category: None,
            resume_category: None,
        }
    }

    /// This rule with its name stripped, or the first reason, naming its field, why it cannot be.
    pub fn validated(mut self) -> Result<Self, String> {
        self.name = strip(&self.name).to_owned();
        check("uploadKiBps", self.upload_kibps, 0, MAX_RATE_KIBPS)?;
        check("downloadKiBps", self.download_kibps, 0, MAX_RATE_KIBPS)?;
        check("maxConnections", self.max_connections, 1, MAX_CONNECTIONS)?;
        check("maxSourcesPerFile", self.max_sources_per_file, 1, MAX_SOURCES_PER_FILE)?;
        check("stopCategory", self.stop_category, 0, i32::MAX.unsigned_abs())?;
        check("resumeCategory", self.resume_category, 0, i32::MAX.unsigned_abs())?;
        Ok(self)
    }

    /// Whether `now` falls in this rule's window.
    pub fn holds(&self, now: PrimitiveDateTime) -> bool {
        if !self.enabled {
            return false;
        }
        let today = DayOfWeek::of(now.weekday());
        let time = now.time();
        if self.start == self.end {
            self.days.contains(&today)
        } else if self.start < self.end {
            self.days.contains(&today) && self.start <= time && time < self.end
        } else {
            (self.days.contains(&today) && self.start <= time)
                || (self.days.contains(&DayOfWeek::of(now.weekday().previous())) && time < self.end)
        }
    }

    /// `preferences` with this rule's values.
    pub fn apply_to(&self, preferences: &Preferences) -> Preferences {
        Preferences {
            max_upload_kibps: self.upload_kibps.unwrap_or(preferences.max_upload_kibps),
            max_download_kibps: self.download_kibps.unwrap_or(preferences.max_download_kibps),
            max_connections: self.max_connections.unwrap_or(preferences.max_connections),
            max_sources_per_file: self.max_sources_per_file.unwrap_or(preferences.max_sources_per_file),
            ..preferences.clone()
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Schedule {
    pub rules: Vec<Rule>,
}

impl Schedule {
    /// The rule that holds at `now`, if any.
    pub fn active(&self, now: PrimitiveDateTime) -> Option<&Rule> {
        self.rules.iter().find(|rule| rule.holds(now))
    }
}

/// A time as the files and the API write it: `HH:mm`, with `:ss` when it has seconds, and a fraction of 3, 6 or 9
/// digits when it has one.
pub fn time_text(time: Time) -> String {
    let mut text = format!("{:02}:{:02}", time.hour(), time.minute());
    let nanos = time.nanosecond();
    if time.second() != 0 || nanos != 0 {
        text.push_str(&format!(":{:02}", time.second()));
    }
    if nanos != 0 {
        if nanos.is_multiple_of(1_000_000) {
            text.push_str(&format!(".{:03}", nanos / 1_000_000));
        } else if nanos.is_multiple_of(1_000) {
            text.push_str(&format!(".{:06}", nanos / 1_000));
        } else {
            text.push_str(&format!(".{nanos:09}"));
        }
    }
    text
}

/// The time of `HH:mm`, `HH:mm:ss` or `HH:mm:ss.f` with up to nine digits of fraction, each field of two digits.
// The time crate's ISO 8601 parsing also takes the basic forms (`0830`), which the files and the API never hold.
pub fn parse_time(text: &str) -> Result<Time, String> {
    let refused = || format!("not a time of the form hh:mm: {text}");
    let two_digits = |part: &str| -> Option<u8> {
        (part.len() == 2 && part.bytes().all(|b| b.is_ascii_digit())).then(|| part.parse().ok()).flatten()
    };
    let mut parts = text.splitn(3, ':');
    let hour = parts.next().and_then(two_digits).ok_or_else(refused)?;
    let minute = parts.next().and_then(two_digits).ok_or_else(refused)?;
    let (second, nanos) = match parts.next() {
        None => (0, 0),
        Some(rest) => {
            let (second, fraction) = rest.split_once('.').map_or((rest, None), |(s, f)| (s, Some(f)));
            let nanos = match fraction {
                None => 0,
                Some(f) if (1..=9).contains(&f.len()) && f.bytes().all(|b| b.is_ascii_digit()) => {
                    format!("{f:0<9}").parse().map_err(|_| refused())?
                }
                Some(_) => return Err(refused()),
            };
            (two_digits(second).ok_or_else(refused)?, nanos)
        }
    };
    Time::from_hms_nano(hour, minute, second, nanos).map_err(|_| refused())
}

fn check(name: &str, value: Option<u32>, min: u32, max: u32) -> Result<(), String> {
    match value {
        Some(value) if !(min..=max).contains(&value) => Err(format!("{name} must be in [{min}, {max}]: {value}")),
        _ => Ok(()),
    }
}
