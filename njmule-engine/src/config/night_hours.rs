// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The hours of the day when njMule may restart to install a new version, written `03:00-05:00`; a window that ends
//! before it starts runs past midnight.

use std::fmt;

use time::Time;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NightHours {
    start: Time,
    end: Time,
}

impl Default for NightHours {
    /// From three to five in the morning.
    fn default() -> Self {
        Self { start: at(3, 0), end: at(5, 0) }
    }
}

impl NightHours {
    /// The window from `start` to the minute before `end`.
    pub fn new(start: Time, end: Time) -> Result<Self, String> {
        if start == end {
            return Err(format!("the night hours must not start when they end: {}", text(start)));
        }
        Ok(Self { start, end })
    }

    /// The hours written `HH:mm-HH:mm`.
    pub fn parse(text: &str) -> Result<Self, String> {
        let refused = || format!("not hours from-to, as 03:00-05:00: {text}");
        let mut parts = text.trim().split('-');
        let (Some(start), Some(end), None) = (parts.next(), parts.next(), parts.next()) else {
            return Err(refused());
        };
        Self::new(time_of(start.trim()).ok_or_else(refused)?, time_of(end.trim()).ok_or_else(refused)?)
    }

    pub fn start(&self) -> Time {
        self.start
    }

    pub fn end(&self) -> Time {
        self.end
    }

    /// Whether `time` falls in the window.
    pub fn holds(&self, time: Time) -> bool {
        if self.start < self.end {
            self.start <= time && time < self.end
        } else {
            self.start <= time || time < self.end
        }
    }
}

impl fmt::Display for NightHours {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}-{}", text(self.start), text(self.end))
    }
}

// `HH:mm`, two digits each.
fn time_of(text: &str) -> Option<Time> {
    let (hour, minute) = text.split_once(':')?;
    let two_digits = |part: &str| part.len() == 2 && part.bytes().all(|b| b.is_ascii_digit());
    if !two_digits(hour) || !two_digits(minute) {
        return None;
    }
    Time::from_hms(hour.parse().ok()?, minute.parse().ok()?, 0).ok()
}

fn text(time: Time) -> String {
    format!("{:02}:{:02}", time.hour(), time.minute())
}

fn at(hour: u8, minute: u8) -> Time {
    Time::from_hms(hour, minute, 0).unwrap_or(Time::MIDNIGHT)
}
