// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's EngineImportManifestTest: every import of an eMule profile leaves a manifest of
//! the files it read, their SHA-256 and what became of them.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing)]

use crate::free_port;

use std::fs;
use std::path::PathBuf;
use std::time::Duration;

use njmule_engine::api::import_options::ImportOptions;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::engine::switches::Switches;
use njmule_engine::persist::emule_importer;
use sha2::{Digest, Sha256};

const PREFERENCES: &[u8] = b"[eMule]\r\nNick=Tester\r\n";
const WAIT: Duration = Duration::from_secs(30);

#[tokio::test]
async fn should_list_every_file_read_with_its_sha256_and_outcome() {
    let mut test = Manifest::new();
    test.given_an_emule_profile_with_its_preferences();
    test.given_nj_mule_running().await;

    test.when_the_profile_is_imported(false).await;

    test.then_the_manifest_says(&format!(
        "IMPORTED\t{}\t{}\tpreferences.ini\t",
        PREFERENCES.len(),
        sha256(PREFERENCES)
    ));
    test.then_the_manifest_says("MISSING\t\t\tserver.met\t");
}

#[tokio::test]
async fn should_leave_no_manifest_after_a_dry_run() {
    let mut test = Manifest::new();
    test.given_an_emule_profile_with_its_preferences();
    test.given_nj_mule_running().await;

    test.when_the_profile_is_imported(true).await;

    test.then_there_is_no_manifest();
}

struct Manifest {
    dir: tempfile::TempDir,
    dirs: Option<AppDirectories>,
    emule: PathBuf,
    engine: Option<Engine>,
}

impl Manifest {
    fn new() -> Self {
        Self { dir: tempfile::tempdir().unwrap(), dirs: None, emule: PathBuf::new(), engine: None }
    }

    fn given_an_emule_profile_with_its_preferences(&mut self) {
        self.emule = self.dir.path().join("emule");
        fs::create_dir_all(&self.emule).unwrap();
        fs::write(self.emule.join("preferences.ini"), PREFERENCES).unwrap();
    }

    async fn given_nj_mule_running(&mut self) {
        let dirs = AppDirectories::under_root(&self.dir.path().join("njmule")).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let preferences = format!("net.tcpPort={}\nnet.kad.enabled=false\n", free_port::free_port());
        fs::write(dirs.config.join("njmule.properties"), preferences).unwrap();
        let switches = Switches { emule_running: || false, ..Switches::QUIET };
        self.engine = Some(Engine::start_with(dirs.clone(), switches).await.unwrap());
        self.dirs = Some(dirs);
    }

    async fn when_the_profile_is_imported(&self, dry_run: bool) {
        let options = ImportOptions { dry_run, identity: false, downloads: false };
        let engine = self.engine.clone().expect("a running engine");
        tokio::time::timeout(WAIT, engine.import_from_emule(self.emule.clone(), options))
            .await
            .expect("imported in time")
            .unwrap();
    }

    fn then_the_manifest_says(&self, line: &str) {
        let manifests = self.manifests();
        assert_eq!(manifests.len(), 1, "{manifests:?}");
        let text = fs::read_to_string(&manifests[0]).unwrap();
        assert!(text.lines().any(|written| written.starts_with(line)), "{line:?} in {text}");
    }

    fn then_there_is_no_manifest(&self) {
        assert_eq!(self.manifests(), Vec::<PathBuf>::new());
    }

    fn manifests(&self) -> Vec<PathBuf> {
        let folder = self.dirs.as_ref().expect("our profile").config.join(emule_importer::MANIFESTS);
        let Ok(entries) = fs::read_dir(folder) else {
            return Vec::new();
        };
        entries
            .map(|entry| entry.unwrap().path())
            .filter(|path| path.extension().is_some_and(|extension| extension == "txt"))
            .collect()
    }
}

impl Drop for Manifest {
    fn drop(&mut self) {
        if let Some(engine) = self.engine.take() {
            tokio::spawn(async move { engine.stop().await });
        }
    }
}

fn sha256(data: &[u8]) -> String {
    data_encoding::HEXLOWER.encode(&Sha256::digest(data))
}
