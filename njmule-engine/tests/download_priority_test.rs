// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Automatic priority puts rare files first.

use njmule_engine::api::download_info::DownloadPriority;

#[test]
fn should_put_rare_files_first_when_automatic() {
    let cases = [
        (0, DownloadPriority::High),
        (20, DownloadPriority::High),
        (21, DownloadPriority::Normal),
        (100, DownloadPriority::Normal),
        (101, DownloadPriority::Low),
    ];
    for (sources, expected) in cases {
        let mut priority = Priority::new();
        priority.given_the_priority(DownloadPriority::Auto);

        priority.when_it_acts_for_a_file_with_sources(sources);

        priority.then_it_acts_with(expected);
    }
}

#[test]
fn should_keep_a_chosen_priority_whatever_the_sources() {
    let mut priority = Priority::new();
    priority.given_the_priority(DownloadPriority::Low);

    priority.when_it_acts_for_a_file_with_sources(0);

    priority.then_it_acts_with(DownloadPriority::Low);
}

struct Priority {
    chosen: DownloadPriority,
    effective: DownloadPriority,
}

impl Priority {
    fn new() -> Self {
        Self { chosen: DownloadPriority::Normal, effective: DownloadPriority::Normal }
    }

    fn given_the_priority(&mut self, chosen: DownloadPriority) {
        self.chosen = chosen;
    }

    fn when_it_acts_for_a_file_with_sources(&mut self, sources: usize) {
        self.effective = self.chosen.effective(sources);
    }

    fn then_it_acts_with(&self, expected: DownloadPriority) {
        assert_eq!(self.effective, expected);
    }
}
