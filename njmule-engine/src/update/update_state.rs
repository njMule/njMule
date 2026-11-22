// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What njMule remembers of its updates between runs, in [`FILE_NAME`]: when a check last succeeded, the highest version
//! each channel offered (an older one offered later is a replay), the version the user skipped, the version that last
//! ran on this configuration and one whose installation failed, which is not tried again on its own.

use std::collections::HashMap;
use std::fmt::Write as _;
use std::io;
use std::path::Path;

use jiff::Timestamp;
use semver::Version;

use crate::config::update_channel::UpdateChannel;
use crate::persist::{atomic_files, property_lines};

pub const FILE_NAME: &str = "update.properties";

const LAST_CHECK_KEY: &str = "lastCheck";
const HIGHEST_KEY: &str = "highest.";
const SKIPPED_KEY: &str = "skipped";
const RAN_KEY: &str = "ran";
const FAILED_KEY: &str = "failedInstall";
const CHANNELS: [UpdateChannel; 2] = [UpdateChannel::Stable, UpdateChannel::Prerelease];

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct UpdateState {
    pub last_check: Option<Timestamp>,
    pub highest: HashMap<UpdateChannel, Version>,
    pub skipped: Option<String>,
    pub ran: Option<String>,
    pub failed_install: Option<String>,
}

impl UpdateState {
    /// A check of `channel` succeeded at `when`, offering `offered`.
    pub fn checked(&self, when: Timestamp, channel: UpdateChannel, offered: &Version) -> Self {
        let mut next = self.clone();
        next.last_check = Some(when);
        let highest = next.highest.entry(channel).or_insert_with(|| offered.clone());
        if offered > highest {
            *highest = offered.clone();
        }
        next
    }

    pub fn skipping(&self, version: &str) -> Self {
        Self { skipped: Some(version.to_owned()), ..self.clone() }
    }

    pub fn running(&self, version: &str) -> Self {
        Self { ran: Some(version.to_owned()), ..self.clone() }
    }

    pub fn failing(&self, version: Option<&str>) -> Self {
        Self { failed_install: version.map(str::to_owned), ..self.clone() }
    }

    /// The state kept in `file`; none when it is missing, and what can be read of it when it is damaged.
    pub fn load(file: &Path) -> Self {
        let properties = match property_lines::read(file) {
            Ok(properties) => properties,
            Err(e) => {
                if e.kind() != io::ErrorKind::NotFound {
                    tracing::warn!("Cannot read {}: {e}", file.display());
                }
                return Self::default();
            }
        };
        let last_check = properties.get(LAST_CHECK_KEY).and_then(|text| {
            text.parse::<Timestamp>()
                .inspect_err(|e| tracing::debug!("Ignoring the last check in {}: {e}", file.display()))
                .ok()
        });
        let highest = CHANNELS
            .into_iter()
            .filter_map(|channel| {
                let version = properties.get(&key(channel)).and_then(|text| Version::parse(text).ok())?;
                Some((channel, version))
            })
            .collect();
        let version = |key: &str| {
            properties.get(key).map(|text| text.trim().to_owned()).filter(|text| Version::parse(text).is_ok())
        };
        Self {
            last_check,
            highest,
            skipped: version(SKIPPED_KEY),
            ran: version(RAN_KEY),
            failed_install: version(FAILED_KEY),
        }
    }

    /// Keeps the state in `file`, replacing it whole.
    pub fn save(&self, file: &Path) -> io::Result<()> {
        let mut text = String::new();
        if let Some(last_check) = self.last_check {
            let _ = writeln!(text, "{LAST_CHECK_KEY}={last_check}");
        }
        for channel in CHANNELS {
            if let Some(version) = self.highest.get(&channel) {
                let _ = writeln!(text, "{}={version}", key(channel));
            }
        }
        for (key, value) in [(SKIPPED_KEY, &self.skipped), (RAN_KEY, &self.ran), (FAILED_KEY, &self.failed_install)] {
            if let Some(value) = value {
                let _ = writeln!(text, "{key}={value}");
            }
        }
        atomic_files::write(file, text.as_bytes())
    }
}

fn key(channel: UpdateChannel) -> String {
    format!("{HIGHEST_KEY}{}", channel.name().to_lowercase())
}
