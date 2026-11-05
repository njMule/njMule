// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The executable logs to its profile's `logs` folder, where the desktop window, its smoke test and the user look.

#![allow(clippy::unwrap_used)]

use std::fs;
use std::io::{BufRead, BufReader};
use std::process::{Command, Stdio};

#[test]
fn should_log_its_control_endpoint_in_the_logs_folder_of_its_profile() {
    let profile = tempfile::tempdir().unwrap();
    let mut engine = Command::new(env!("CARGO_BIN_EXE_njmule"))
        .arg("--config-dir")
        .arg(profile.path())
        .env("NJMULE_CONTROL_ENDPOINT", "1")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let mut control = String::new();
    BufReader::new(engine.stdout.take().unwrap()).read_line(&mut control).unwrap();

    drop(engine.stdin.take());
    engine.wait().unwrap();

    assert!(control.starts_with("njmule-control "));
    let logs: Vec<String> = fs::read_dir(profile.path().join("logs"))
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .filter(|path| path.extension().is_some_and(|extension| extension == "log"))
        .map(|path| fs::read_to_string(path).unwrap())
        .collect();
    assert!(logs.iter().any(|log| log.contains("Control endpoint for the desktop app on port")), "{logs:?}");
}
