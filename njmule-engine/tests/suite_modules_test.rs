// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Checks that every test file in `tests/` is declared as a module in `tests/main.rs`: the integration tests share
//! one binary so the engine is linked once, so an undeclared file would silently never compile or run.

#![allow(clippy::unwrap_used)]

use std::collections::BTreeSet;
use std::fs;

const SUITE: &str = "tests/main.rs";

#[test]
fn should_declare_every_test_file_as_a_module_of_the_suite() {
    let mut suite = Suite::default();
    suite.given_the_test_files();

    suite.when_the_suite_is_read();

    suite.then_it_declares_every_test_file();
}

#[derive(Default)]
struct Suite {
    files: BTreeSet<String>,
    modules: BTreeSet<String>,
}

impl Suite {
    fn given_the_test_files(&mut self) {
        for entry in fs::read_dir("tests").unwrap() {
            let path = entry.unwrap().path();
            if path.extension().is_some_and(|extension| extension == "rs") && !path.ends_with("main.rs") {
                self.files.insert(path.file_stem().unwrap().to_string_lossy().into_owned());
            }
        }
    }

    fn when_the_suite_is_read(&mut self) {
        self.modules = fs::read_to_string(SUITE)
            .unwrap()
            .lines()
            .filter_map(|line| line.trim().strip_prefix("mod ").and_then(|rest| rest.strip_suffix(';')))
            .map(str::to_owned)
            .collect();
    }

    fn then_it_declares_every_test_file(&self) {
        let left_out: Vec<&String> = self.files.difference(&self.modules).collect();
        assert!(left_out.is_empty(), "Test files not declared in {SUITE}, which never run: {left_out:#?}");
    }
}
