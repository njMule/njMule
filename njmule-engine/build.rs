// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Takes njMule's version from `VERSION`, its single source, so that peers and the API see the version of
//! the release this engine belongs to.

use std::fs;

fn main() {
    let file = "../VERSION";
    println!("cargo::rerun-if-changed={file}");
    println!("cargo::rerun-if-changed=../njmule-ui/build/shipped");
    println!("cargo::rerun-if-env-changed=NJMULE_VERSION");
    let override_version = std::env::var("NJMULE_VERSION").ok().map(|v| v.trim().to_owned()).filter(|v| !v.is_empty());
    let version = override_version.unwrap_or_else(|| {
        fs::read_to_string(file)
            .ok()
            .map(|text| text.trim().to_owned())
            .filter(|version| !version.is_empty())
            .unwrap_or_else(|| env!("CARGO_PKG_VERSION").to_owned())
    });
    println!("cargo::rustc-env=NJMULE_VERSION={version}");
}
