// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Takes njMule's version from `gradle.properties`, its single source, so that peers and the API see the version of
//! the release this engine belongs to.

use std::fs;

fn main() {
    let file = "../gradle.properties";
    println!("cargo::rerun-if-changed={file}");
    let text = fs::read_to_string(file).unwrap_or_default();
    let version = text
        .lines()
        .find_map(|line| line.strip_prefix("version=").map(str::trim))
        .filter(|version| !version.is_empty())
        .unwrap_or(env!("CARGO_PKG_VERSION"));
    println!("cargo::rustc-env=NJMULE_VERSION={version}");
}
