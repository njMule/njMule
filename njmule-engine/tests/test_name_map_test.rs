// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The test-name map of the port: every test of a Java test class that has a Rust twin (`FooTest.java` and
//! `tests/foo_test.rs`) is found among the Rust tests under its name in snake case (`shouldX` as `should_x`, digits
//! split off or not), or is listed in `tests/not_ported.txt` with the reason it waits. A test added to Java, or lost in Rust, fails here; so
//! does a listed one that is ported, or gone from Java, so that the list only shrinks. In the areas ported whole
//! (`tests/ported_areas.txt`), every Java test class has a twin, or is listed as a class with the reason it has none.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Path, PathBuf};

const JAVA_TESTS: &str = "../njmule-core/src/test/java";
const NOT_PORTED: &str = "tests/not_ported.txt";
// The packages of org.njmule.core, with those under them, whose test classes all have Rust twins.
const AREAS: &str = "tests/ported_areas.txt";
// The root of the one executable of the tests, which declares each file as a module.
const SUITE: &str = "tests/main.rs";
const ANNOTATIONS: [&str; 6] =
    ["@Test", "@ParameterizedTest", "@Property", "@RepeatedTest", "@TestFactory", "@Example"];

// `shouldTakeAPort` as `should_take_a_port`, `ShouldUseA768BitPrime` as `should_use_a768_bit_prime`.
fn snake(name: &str) -> String {
    let chars: Vec<char> = name.chars().collect();
    let mut out = String::new();
    for (i, &c) in chars.iter().enumerate() {
        let previous = if i > 0 { chars.get(i - 1).copied() } else { None };
        let next = chars.get(i + 1).copied();
        let starts_word = c.is_ascii_uppercase()
            && previous.is_some_and(|p| {
                p.is_ascii_lowercase()
                    || p.is_ascii_digit()
                    || (p.is_ascii_uppercase() && next.is_some_and(|n| n.is_ascii_lowercase()))
            });
        if starts_word {
            out.push('_');
        }
        out.push(c.to_ascii_lowercase());
    }
    out
}

fn files(dir: &Path, found: &mut Vec<PathBuf>) {
    for entry in fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            files(&path, found);
        } else {
            found.push(path);
        }
    }
}

// The test methods of a Java test class: those under a test annotation.
fn java_tests(source: &str) -> Vec<String> {
    let mut tests = Vec::new();
    let mut annotated = false;
    for line in source.lines().map(str::trim) {
        if ANNOTATIONS.iter().any(|annotation| {
            line.strip_prefix(annotation).is_some_and(|rest| rest.is_empty() || rest.starts_with(['(', ' ']))
        }) {
            annotated = true;
        }
        if annotated && let Some(start) = line.find("void ") {
            let rest = &line[start + 5..];
            if let Some(end) = rest.find('(') {
                tests.push(rest[..end].trim().to_owned());
                annotated = false;
            }
        }
    }
    tests
}

fn rust_tests() -> (BTreeSet<String>, BTreeSet<String>) {
    let mut twins = BTreeSet::new();
    let mut names = BTreeSet::new();
    for entry in fs::read_dir("tests").unwrap() {
        let path = entry.unwrap().path();
        if path.extension().is_some_and(|extension| extension == "rs") {
            twins.insert(path.file_stem().unwrap().to_string_lossy().into_owned());
            for line in fs::read_to_string(&path).unwrap().lines() {
                let line = line.trim();
                if let Some(rest) = line.strip_prefix("async ").unwrap_or(line).strip_prefix("fn should_") {
                    names.insert(key(&format!("should{}", &rest[..rest.find('(').unwrap()])));
                }
            }
        }
    }
    (twins, names)
}

// A name as both languages spell it: `should_use_a_768_bit` and `shouldUseA768Bit` alike.
fn key(name: &str) -> String {
    name.chars().filter(|c| *c != '_').map(|c| c.to_ascii_lowercase()).collect()
}

// The lines of a list, without its blank lines and `#` comments.
fn listed(file: &str) -> Vec<String> {
    fs::read_to_string(file)
        .unwrap()
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .map(str::to_owned)
        .collect()
}

// `Class.method` lines; comments give the reason of the lines that follow.
fn not_ported() -> BTreeSet<String> {
    listed(NOT_PORTED).into_iter().collect()
}

#[test]
fn should_find_every_test_of_a_ported_java_class_in_rust_or_among_those_not_ported() {
    let (twins, rust) = rust_tests();
    let areas = listed(AREAS);
    let mut java = Vec::new();
    files(Path::new(JAVA_TESTS), &mut java);
    let mut missing = BTreeMap::new();
    let mut known = BTreeSet::new();
    let core = Path::new(JAVA_TESTS).join("org/njmule/core");
    for path in java.iter().filter(|path| path.extension().is_some_and(|extension| extension == "java")) {
        let class = path.file_stem().unwrap().to_string_lossy().into_owned();
        if !class.ends_with("Test") {
            continue;
        }
        if !twins.contains(&snake(&class)) {
            let package = path.strip_prefix(&core).map(|p| p.to_string_lossy().replace('\\', "/")).unwrap_or_default();
            if areas.iter().any(|area| package.starts_with(area.as_str())) {
                missing.insert(class.clone(), snake(&class));
            }
            continue;
        }
        for test in java_tests(&fs::read_to_string(path).unwrap()) {
            let qualified = format!("{class}.{test}");
            if !rust.contains(&key(&test)) {
                missing.insert(qualified.clone(), snake(&test));
            }
            known.insert(qualified);
        }
    }
    let listed = not_ported();

    let unported: Vec<String> = missing
        .iter()
        .filter(|(key, _)| !listed.contains(*key))
        .map(|(key, rust)| format!("{key} ({rust})"))
        .collect();
    let stale: Vec<&String> = listed.iter().filter(|key| !missing.contains_key(*key)).collect();

    assert!(
        unported.is_empty(),
        "Java tests with no Rust test of their name, or test classes of a ported area with no twin, nor listed in \
         {NOT_PORTED}: {unported:#?}"
    );
    assert!(
        stale.is_empty(),
        "Listed in {NOT_PORTED} but ported or gone from Java (known: {}): {stale:#?}",
        known.len()
    );
}

#[test]
fn should_declare_every_test_file_as_a_module_of_the_suite() {
    let mut suite = Suite::default();
    suite.given_the_test_files();

    suite.when_the_suite_is_read();

    suite.then_it_declares_every_test_file();
}

// The files of `tests/` and the modules the suite declares: a file left out would compile into nothing and never run.
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

#[test]
fn should_write_java_test_names_in_snake_case() {
    let names = ["shouldTakeAPortWithinItsRange", "shouldUseA768BitPrimeForTheDhGroup", "shouldParseUTF8Names"];

    let snaked: Vec<String> = names.iter().map(|name| snake(name)).collect();

    assert_eq!(
        snaked,
        [
            "should_take_a_port_within_its_range",
            "should_use_a768_bit_prime_for_the_dh_group",
            "should_parse_utf8_names"
        ]
    );
}
