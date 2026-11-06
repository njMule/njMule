// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ScheduleCodecTest, under its names.

#![allow(clippy::unwrap_used)]

use std::collections::BTreeSet;
use std::fs;

use njmule_engine::config::schedule::{self, DayOfWeek, Rule, Schedule};
use njmule_engine::persist::schedule_codec;
use njmule_engine::util::white_space::strip;
use proptest::prelude::any;

#[test]
fn should_lose_only_the_line_of_a_malformed_escape() {
    let directory = tempfile::tempdir().unwrap();
    let file = directory.path().join(schedule_codec::FILE_NAME);
    fs::write(&file, "rule.1.name=Night\\u00\nrule.1.start=01:00\nrule.2.name=Day\nrule.2.start=09:00\n").unwrap();

    let loaded = schedule_codec::load(&file).unwrap();

    let names: Vec<&str> = loaded.rules.iter().map(|rule| rule.name.as_str()).collect();
    assert_eq!(names, ["", "Day"]);
}

proptest::proptest! {
    #![proptest_config(proptest::prelude::ProptestConfig::with_cases(200))]

    #[test]
    fn should_keep_the_rule_names_through_the_file(
        names in proptest::collection::vec(proptest::collection::vec(any::<char>(), 0..=30), 0..=4),
    ) {
        let saved = Schedule {
            rules: names
                .into_iter()
                .map(|name| Rule {
                    days: BTreeSet::from([DayOfWeek::Monday]),
                    start: schedule::parse_time("01:00").unwrap(),
                    end: schedule::parse_time("02:00").unwrap(),
                    upload_kibps: Some(10),
                    ..Rule::new(strip(&String::from_iter(name)))
                })
                .collect(),
        };
        let directory = tempfile::tempdir().unwrap();
        let file = directory.path().join(schedule_codec::FILE_NAME);

        schedule_codec::save(&file, &saved).unwrap();
        let loaded = schedule_codec::load(&file).unwrap();

        proptest::prop_assert_eq!(loaded, saved);
    }
}
