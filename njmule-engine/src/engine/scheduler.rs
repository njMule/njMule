// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The schedule in force: the rule that holds now on the user's clock, whose limits replace those of the saved
//! preferences while it holds. A rule acts once, when it begins.

use std::collections::HashSet;
use std::io;
use std::path::PathBuf;

use time::{OffsetDateTime, PrimitiveDateTime, UtcOffset};

use crate::config::preferences::Preferences;
use crate::config::schedule::{Rule, Schedule};
use crate::persist::schedule_codec;

/// How often, in seconds, the scheduler looks at the clock.
pub const CHECK_SECONDS: u32 = 15;

/// What a look at the clock changed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Change {
    /// A rule began to hold: its actions run now.
    Began(Rule),
    /// No rule holds any more: the preferences apply again.
    Ended,
}

#[derive(Debug)]
pub struct Scheduler {
    file: PathBuf,
    schedule: Schedule,
    active: Option<Rule>,
}

impl Scheduler {
    /// The schedule kept in `file`, with no rule in force yet; one that cannot be read is empty.
    pub fn load(file: PathBuf) -> Self {
        let schedule = schedule_codec::load(&file).unwrap_or_else(|e| {
            tracing::warn!("Cannot read {}: {e}", file.display());
            Schedule::default()
        });
        Self { file, schedule, active: None }
    }

    pub fn schedule(&self) -> &Schedule {
        &self.schedule
    }

    /// The rule in force, if any.
    pub fn active(&self) -> Option<&Rule> {
        self.active.as_ref()
    }

    /// The `saved` preferences with the limits of the rule in force.
    pub fn effective(&self, saved: &Preferences) -> Preferences {
        self.active.as_ref().map_or_else(|| saved.clone(), |rule| rule.apply_to(saved))
    }

    /// Puts the rule that holds at `now` in force; tells what changed, if anything did.
    pub fn refresh(&mut self, now: PrimitiveDateTime) -> Option<Change> {
        let holding = self.schedule.active(now).cloned();
        if holding == self.active {
            return None;
        }
        self.active.clone_from(&holding);
        Some(holding.map_or(Change::Ended, Change::Began))
    }

    /// Saves `changed` and puts it in place of the schedule. A rule edited while it holds goes on without acting again:
    /// the same rule, or the one in its place.
    pub fn update(&mut self, changed: Schedule, now: PrimitiveDateTime) -> io::Result<Option<Change>> {
        schedule_codec::save(&self.file, &changed)?;
        let before = std::mem::replace(&mut self.schedule, changed);
        let holding = self.schedule.active(now).cloned();
        let goes_on = match (&self.active, &holding) {
            (Some(was), Some(rule)) => rule == was || place(&self.schedule, rule) == place(&before, was),
            _ => false,
        };
        if goes_on {
            self.active = holding;
        }
        Ok(self.refresh(now))
    }

    /// Lets the rules go of the `deleted` categories, as a new category may take the same id; saves the schedule when a
    /// rule changed. The rule in force stays in force.
    pub fn forget_categories(&mut self, deleted: &HashSet<u32>) -> io::Result<()> {
        let kept = |id: Option<u32>| id.filter(|id| !deleted.contains(id));
        let mut changed = false;
        let mut active = self.active.clone();
        let mut rules = Vec::with_capacity(self.schedule.rules.len());
        for rule in &self.schedule.rules {
            let (stop, resume) = (kept(rule.stop_category), kept(rule.resume_category));
            if stop == rule.stop_category && resume == rule.resume_category {
                rules.push(rule.clone());
                continue;
            }
            let cleaned = Rule { stop_category: stop, resume_category: resume, ..rule.clone() };
            if self.active.as_ref() == Some(rule) {
                active = Some(cleaned.clone());
            }
            rules.push(cleaned);
            changed = true;
        }
        if changed {
            let cleaned = Schedule { rules };
            schedule_codec::save(&self.file, &cleaned)?;
            self.schedule = cleaned;
            self.active = active;
        }
        Ok(())
    }
}

/// The date and time on the user's clock, where the rules' windows are.
pub fn local_now() -> PrimitiveDateTime {
    // The time crate finds no local offset in a process of many threads on Unix; chrono reads the system's zone itself.
    let offset =
        UtcOffset::from_whole_seconds(chrono::Local::now().offset().local_minus_utc()).unwrap_or(UtcOffset::UTC);
    let now = OffsetDateTime::now_utc().to_offset(offset);
    PrimitiveDateTime::new(now.date(), now.time())
}

fn place(schedule: &Schedule, rule: &Rule) -> Option<usize> {
    schedule.rules.iter().position(|each| each == rule)
}
