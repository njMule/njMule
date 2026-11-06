// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's EmuleConfigDirectoryTest, under its names: finding the configuration folder of the eMule of this
//! computer's user.

#![allow(clippy::unwrap_used)]

use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use njmule_engine::persist::emule_importer;

fn env(dir: &Path, names: &[(&str, &str)]) -> BTreeMap<String, String> {
    names
        .iter()
        .map(|(variable, base)| ((*variable).to_owned(), dir.join(base).display().to_string()))
        .collect()
}

fn profile_in(dir: &Path, base: &str) {
    fs::create_dir_all(dir.join(base).join("eMule").join("config")).unwrap();
}

#[test]
fn should_find_the_profile_in_the_local_application_data() {
    let dir = tempfile::tempdir().unwrap();
    profile_in(dir.path(), "local");

    let found =
        emule_importer::find_config_directory(&env(dir.path(), &[("LOCALAPPDATA", "local"), ("APPDATA", "roaming")]));

    assert_eq!(found, Some(dir.path().join("local").join("eMule").join("config")));
}

#[test]
fn should_find_the_profile_in_the_roaming_application_data_when_the_local_has_none() {
    let dir = tempfile::tempdir().unwrap();
    profile_in(dir.path(), "roaming");

    let found =
        emule_importer::find_config_directory(&env(dir.path(), &[("LOCALAPPDATA", "local"), ("APPDATA", "roaming")]));

    assert_eq!(found, Some(dir.path().join("roaming").join("eMule").join("config")));
}

#[test]
fn should_find_no_profile_where_there_is_none() {
    let dir = tempfile::tempdir().unwrap();

    let found = emule_importer::find_config_directory(&env(dir.path(), &[("LOCALAPPDATA", "local")]));

    assert_eq!(found, None);
}

#[test]
fn should_find_no_profile_without_the_application_data() {
    let found = emule_importer::find_config_directory(&BTreeMap::new());

    assert_eq!(found, None);
}
