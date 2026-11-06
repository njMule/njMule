// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's EmuleImportDryRunTest, under its names: a dry run of the import on a real eMule profile from
//! `NJMULE_EMULE_CONFIG`, which is only read; it prints the outcome of each file, never names, hashes or other contents.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use njmule_engine::api::category::Category;
use njmule_engine::api::import_options::ImportOptions;
use njmule_engine::api::import_report::Outcome;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::persist::emule_importer::{self, EmuleImporter, Target};
use njmule_engine::persist::format::known_met::KnownFile;
use njmule_engine::persist::format::{known2_met, nodes_dat};

/// A target that refuses every change, which a dry run never asks for.
struct Refusing(PathBuf);

impl Target for Refusing {
    fn config_dir(&self) -> PathBuf {
        self.0.clone()
    }

    fn update_preferences(&mut self, _: &dyn Fn(&Preferences) -> Result<Preferences, String>) -> Result<(), String> {
        unreachable!("a dry run changes nothing")
    }

    fn add_servers(&mut self, _: &Path) -> Result<usize, String> {
        unreachable!("a dry run changes nothing")
    }

    fn add_kad_contacts(&mut self, _: &nodes_dat::Contents) -> Result<usize, String> {
        unreachable!("a dry run changes nothing")
    }

    fn add_known_files(&mut self, _: &[KnownFile], _: &[known2_met::Entry]) -> usize {
        unreachable!("a dry run changes nothing")
    }

    fn share_directory(&mut self, _: &Path) -> Result<(), String> {
        unreachable!("a dry run changes nothing")
    }

    fn add_categories(&mut self, _: &[Category]) -> Result<usize, String> {
        unreachable!("a dry run changes nothing")
    }

    fn adopt_download(&mut self, _: &Path, _: &Path, _: u32) -> Result<String, String> {
        unreachable!("a dry run changes nothing")
    }
}

#[test]
fn should_read_every_file_of_the_profile() {
    let Ok(config) = std::env::var("NJMULE_EMULE_CONFIG") else { return };
    let ours = tempfile::tempdir().unwrap();
    let options = ImportOptions { dry_run: true, identity: true, downloads: true };

    let report = EmuleImporter::new(
        Path::new(&config),
        options,
        &mut Refusing(ours.path().to_path_buf()),
        &emule_importer::is_emule_running,
    )
    .run();

    let mut counts = BTreeMap::new();
    for item in &report.items {
        *counts.entry(item.outcome.name()).or_insert(0) += 1;
    }
    println!("[private-fixtures] import dry run: {counts:?}");
    assert!(report.items.iter().all(|item| item.outcome != Outcome::Failed), "{:?}", failed(&report.items));
    assert!(counts.contains_key(Outcome::WouldImport.name()));
    assert_eq!(fs::read_dir(ours.path()).unwrap().count(), 0);
}

// Why items failed, by the keys of their phrases: never the names of the files or their contents.
fn failed(items: &[njmule_engine::api::import_report::Item]) -> Vec<Vec<String>> {
    items
        .iter()
        .filter(|item| item.outcome == Outcome::Failed)
        .map(|item| item.phrases.iter().map(|phrase| phrase.key.translation_key()).collect())
        .collect()
}
