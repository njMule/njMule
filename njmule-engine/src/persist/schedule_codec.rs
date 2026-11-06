// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The schedule as `scheduler.properties`: `rule.N.*` keys, rules in the order of N. A broken rule is left out and
//! logged.

use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::Path;

use crate::config::schedule::{self, DayOfWeek, Rule, Schedule};
use crate::persist::{atomic_files, property_lines};
use crate::util::white_space::strip;

pub const FILE_NAME: &str = "scheduler.properties";

const PREFIX: &str = "rule.";

pub fn load(file: &Path) -> io::Result<Schedule> {
    if !file.exists() && !atomic_files::bak_path(file).exists() {
        return Ok(Schedule::default());
    }
    let properties = property_lines::read(file)?;
    let mut rules = Vec::new();
    for n in property_lines::numbers(&properties, PREFIX) {
        match rule(&properties, &format!("{PREFIX}{n}.")) {
            Ok(rule) => rules.push(rule),
            Err(reason) => {
                let name = file.file_name().unwrap_or_default().to_string_lossy();
                tracing::warn!("Ignoring rule {n} of {name}: {reason}");
            }
        }
    }
    Ok(Schedule { rules })
}

pub fn save(file: &Path, schedule: &Schedule) -> io::Result<()> {
    let mut text = "# njMule scheduler\n".to_owned();
    for (n, rule) in (1_usize..).zip(&schedule.rules) {
        let key = |name: &str| format!("{PREFIX}{n}.{name}");
        property_lines::append(&mut text, &key("name"), &rule.name);
        property_lines::append(&mut text, &key("enabled"), &rule.enabled.to_string());
        let days: Vec<&str> = rule.days.iter().map(|day| day.name()).collect();
        property_lines::append(&mut text, &key("days"), &days.join(","));
        property_lines::append(&mut text, &key("start"), &schedule::time_text(rule.start));
        property_lines::append(&mut text, &key("end"), &schedule::time_text(rule.end));
        for (name, value) in [
            ("uploadKiBps", rule.upload_kibps),
            ("downloadKiBps", rule.download_kibps),
            ("maxConnections", rule.max_connections),
            ("maxSourcesPerFile", rule.max_sources_per_file),
            ("stopCategory", rule.stop_category),
            ("resumeCategory", rule.resume_category),
        ] {
            if let Some(value) = value {
                property_lines::append(&mut text, &key(name), &value.to_string());
            }
        }
    }
    atomic_files::write(file, text.as_bytes())
}

fn rule(properties: &BTreeMap<String, String>, key: &str) -> Result<Rule, String> {
    let get = |name: &str| properties.get(&format!("{key}{name}")).map(String::as_str);
    Rule {
        name: get("name").unwrap_or_default().to_owned(),
        // As the Java engine reads it: only "true", in any case, is true.
        enabled: get("enabled").is_none_or(|enabled| enabled.eq_ignore_ascii_case("true")),
        days: days(get("days").unwrap_or_default())?,
        start: schedule::parse_time(strip(get("start").unwrap_or("00:00")))?,
        end: schedule::parse_time(strip(get("end").unwrap_or("00:00")))?,
        upload_kibps: number(get("uploadKiBps"))?,
        download_kibps: number(get("downloadKiBps"))?,
        max_connections: number(get("maxConnections"))?,
        max_sources_per_file: number(get("maxSourcesPerFile"))?,
        stop_category: number(get("stopCategory"))?,
        resume_category: number(get("resumeCategory"))?,
    }
    .validated()
}

fn days(text: &str) -> Result<BTreeSet<DayOfWeek>, String> {
    text.split(',')
        .map(strip)
        .filter(|day| !day.is_empty())
        .map(|day| day.parse().map_err(|_| format!("not a day of the week: {day}")))
        .collect()
}

fn number(text: Option<&str>) -> Result<Option<u32>, String> {
    match text.map(strip) {
        None | Some("") => Ok(None),
        Some(text) => text.parse().map(Some).map_err(|_| format!("not a number in range: {text}")),
    }
}
