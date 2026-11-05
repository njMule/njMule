// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The `--hash` examples of the Java launcher's MainTest, under their names, against the executable.

#![allow(clippy::unwrap_used)]

use std::fs;
use std::process::{Command, Output};

fn hashed(name: &str, dir: &std::path::Path) -> Output {
    Command::new(env!("CARGO_BIN_EXE_njmule")).arg("--hash").arg(dir.join(name)).output().unwrap()
}

#[test]
fn should_print_the_link_of_a_file_to_hash() {
    let dir = tempfile::tempdir().unwrap();
    fs::write(dir.path().join("abc.txt"), "abc").unwrap();

    let output = hashed("abc.txt", dir.path());

    assert_eq!(output.status.code(), Some(0));
    let printed = String::from_utf8(output.stdout).unwrap();
    assert!(
        printed
            .contains("ed2k://|file|abc.txt|3|A448017AAF21D8525FC10AE87AA6729D|h=VGMT4NSHA2AWVOR6EVYXQUGCNSONBWE5|/")
    );
    assert!(printed.contains("3 bytes, 0 part hashes"));
}

#[test]
fn should_fail_to_hash_a_missing_file() {
    let dir = tempfile::tempdir().unwrap();

    let output = hashed("missing", dir.path());

    assert_eq!(output.status.code(), Some(1));
    assert!(String::from_utf8(output.stderr).unwrap().contains("Cannot hash"));
}
