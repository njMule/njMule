// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's EmuleAutostartImportTest, under its names.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use std::fs;
use std::path::{Path, PathBuf};

use njmule_engine::api::category::Category;
use njmule_engine::api::import_options::ImportOptions;
use njmule_engine::api::import_report::ImportReport;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::persist::emule_importer::{EmuleImporter, Target};
use njmule_engine::persist::format::known_met::KnownFile;
use njmule_engine::persist::format::{known2_met, nodes_dat};

/// A target a check never changes.
struct Untouched(PathBuf);

const REFUSED: &str = "a check changes nothing";

impl Target for Untouched {
    fn config_dir(&self) -> PathBuf {
        self.0.clone()
    }

    fn update_preferences(&mut self, _: &dyn Fn(&Preferences) -> Result<Preferences, String>) -> Result<(), String> {
        Err(REFUSED.to_owned())
    }

    fn add_servers(&mut self, _: &Path) -> Result<usize, String> {
        Err(REFUSED.to_owned())
    }

    fn add_kad_contacts(&mut self, _: &nodes_dat::Contents) -> Result<usize, String> {
        Err(REFUSED.to_owned())
    }

    fn add_known_files(&mut self, _: &[KnownFile], _: &[known2_met::Entry]) -> usize {
        unreachable!("{REFUSED}")
    }

    fn share_directory(&mut self, _: &Path) -> Result<(), String> {
        Err(REFUSED.to_owned())
    }

    fn add_categories(&mut self, _: &[Category]) -> Result<usize, String> {
        Err(REFUSED.to_owned())
    }

    fn adopt_download(&mut self, _: &Path, _: &Path, _: u32) -> Result<String, String> {
        Err(REFUSED.to_owned())
    }
}

fn checked(preferences: Option<&str>) -> ImportReport {
    let dir = tempfile::tempdir().unwrap();
    let emule = dir.path().join("emule");
    fs::create_dir_all(&emule).unwrap();
    if let Some(text) = preferences {
        fs::write(emule.join("preferences.ini"), text).unwrap();
    }
    let ours = dir.path().join("njmule");
    fs::create_dir_all(&ours).unwrap();
    let options = ImportOptions { dry_run: true, identity: false, downloads: false };
    EmuleImporter::new(&emule, options, &mut Untouched(ours), &|| false).run()
}

#[test]
fn should_tell_that_emule_starts_at_login() {
    let report = checked(Some("[eMule]\nNick=Tester\nAutoStart=1\n"));

    assert!(report.start_at_login);
    let preferences: Vec<_> = report.items.iter().filter(|item| item.file.ends_with("preferences.ini")).collect();
    assert_eq!(preferences.len(), 1);
    assert!(preferences[0].detail().contains("start at login"));
}

#[test]
fn should_not_tell_it_when_emule_does_not_start_at_login() {
    let report = checked(Some("[eMule]\nNick=Tester\nAutoStart=0\n"));

    assert!(!report.start_at_login);
}

#[test]
fn should_not_tell_it_without_emule_preferences() {
    let report = checked(None);

    assert!(!report.start_at_login);
}
