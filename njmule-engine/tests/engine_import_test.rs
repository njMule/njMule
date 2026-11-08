// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's EngineImportTest: an eMule profile imported into a running engine, its downloads
//! going on, its identity taken at the next start, its searches and categories, and a dry run that changes nothing.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use crate::free_port;
use crate::synthetic_emule_profile::{DOWNLOAD_SIZE, NICK, SyntheticEmuleProfile};

use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use njmule_engine::api::category::{self, Category};
use njmule_engine::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus};
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::import_options::ImportOptions;
use njmule_engine::api::import_report::{ImportReport, Outcome};
use njmule_engine::api::nj_mule_engine::Subscription;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::engine::switches::Switches;
use njmule_engine::kad::kad_node;
use njmule_engine::persist::emule_importer;
use njmule_engine::persist::format::search_files::{self, StoredSearch};
use njmule_engine::persist::format::{nodes_dat, preferences_dat};
use njmule_engine::persist::user_hash_store;
use njmule_engine::search::stored_searches;

const WAIT: Duration = Duration::from_secs(5);
// Reading a profile and copying its downloads take a while.
const IMPORT_WAIT: Duration = Duration::from_secs(30);
const SETTLE_WAIT: Duration = Duration::from_secs(20);

#[tokio::test]
async fn should_go_on_with_the_imported_downloads_and_take_the_identity_at_the_next_start() {
    let mut test = EngineImport::new();
    test.given_an_emule_profile();
    test.given_a_profile_of_ours();
    test.given_a_running_engine().await;
    test.given_the_emule_profile_was_imported(options(false, true, true)).await;
    test.given_nothing_failed_in_the_import();
    test.given_the_nick_is_taken_but_not_the_port();
    test.given_the_kad_contacts_saved(3);
    test.given_the_complete_download_is_finished().await;
    test.given_the_half_download_goes_on().await;
    test.given_the_file_is_shared("shared.bin").await;
    test.given_the_identity_waits_for_the_next_start();

    test.when_the_engine_starts_again().await;

    test.then_the_engine_runs();
    test.then_no_identity_waits_any_more();
    test.then_the_user_hash_is_the_one_of_the_emule_profile();
    test.then_the_emule_profile_is_untouched();
}

#[tokio::test]
async fn should_start_after_two_identity_imports() {
    let mut test = EngineImport::new();
    test.given_an_emule_profile();
    test.given_a_profile_of_ours();
    test.given_a_running_engine().await;
    test.given_the_emule_profile_was_imported(options(false, true, false)).await;
    test.given_the_emule_profile_was_imported(options(false, true, false)).await;

    test.when_the_engine_starts_again().await;

    test.then_the_engine_runs();
    test.then_no_identity_waits_any_more();
    test.then_our_file_is_the_one_of_the_emule_profile("cryptkey.dat");
}

#[tokio::test]
async fn should_keep_the_searches_of_emule_beyond_the_session_of_the_import() {
    let mut test = EngineImport::new();
    test.given_an_emule_profile();
    test.given_the_emule_profile_has_the_search("linux");
    test.given_a_profile_of_ours();
    test.given_a_running_engine().await;
    test.given_the_emule_profile_was_imported(options(false, false, false)).await;
    test.given_the_report_says_the_searches_come_from_the_next_start();
    test.given_the_interface_saves_its_searches().await;

    test.when_the_engine_starts_again().await;

    test.then_the_saved_searches_are(&["linux"]).await;
}

#[tokio::test]
async fn should_not_put_a_download_in_an_unrelated_category_of_ours() {
    let mut test = EngineImport::new();
    test.given_an_emule_profile();
    test.given_the_emule_profile_has_the_category("Films");
    test.given_the_half_download_of_emule_is_in_category(1);
    test.given_a_profile_of_ours();
    test.given_a_running_engine().await;
    test.given_our_category(1, "Mine").await;

    test.when_the_emule_profile_is_imported(options(false, false, true)).await;

    test.then_the_half_download_is_in_category(category::NONE).await;
}

#[tokio::test]
async fn should_announce_an_imported_download_in_the_category_it_ends_in() {
    let mut test = EngineImport::new();
    test.given_an_emule_profile();
    test.given_the_emule_profile_has_the_category("Films");
    test.given_the_half_download_of_emule_is_in_category(1);
    test.given_a_profile_of_ours();
    test.given_a_running_engine().await;
    test.given_our_category(1, "Mine").await;
    test.given_the_events_are_watched();

    test.when_the_emule_profile_is_imported(options(false, false, true)).await;

    test.then_the_half_download_was_announced_in_category(category::NONE).await;
}

#[tokio::test]
async fn should_leave_the_engine_as_it_was_after_a_dry_run() {
    let mut test = EngineImport::new();
    test.given_an_emule_profile();
    test.given_a_profile_of_ours();
    test.given_a_running_engine().await;

    test.when_the_emule_profile_is_imported(options(true, true, true)).await;

    test.then_the_report_is_a_dry_run();
    test.then_the_nick_is_not_taken();
    test.then_there_is_no_download().await;
    test.then_no_kad_contact_is_saved();
}

/// An eMule profile and one of ours in a test folder, the engine running on ours, and what the import told.
struct EngineImport {
    dir: tempfile::TempDir,
    emule: Option<SyntheticEmuleProfile>,
    before: BTreeMap<String, String>,
    port: u16,
    dirs: Option<AppDirectories>,
    engine: Option<Engine>,
    subscription: Option<Subscription>,
    report: Option<ImportReport>,
}

impl EngineImport {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            emule: None,
            before: BTreeMap::new(),
            port: 0,
            dirs: None,
            engine: None,
            subscription: None,
            report: None,
        }
    }

    fn given_an_emule_profile(&mut self) {
        let emule = SyntheticEmuleProfile::create(&self.dir.path().join("emule"));
        self.before = emule.fingerprint();
        self.emule = Some(emule);
    }

    fn given_the_emule_profile_has_the_search(&self, title: &str) {
        let search = StoredSearch {
            id: 1,
            search_type: 1,
            client_shared_files: false,
            title: title.to_owned(),
            expression: title.to_owned(),
            file_type: String::new(),
            results: Vec::new(),
        };
        self.emule().write(stored_searches::FILE_NAME, &search_files::write_stored(&[search]).unwrap());
    }

    fn given_the_emule_profile_has_the_category(&self, title: &str) {
        self.emule().write("Category.ini", format!("[Cat#1]\r\nTitle={title}\r\n").as_bytes());
    }

    fn given_the_half_download_of_emule_is_in_category(&self, category: u64) {
        self.emule().put_in_category(2, category);
    }

    // No Kad: the imported contacts must not be greeted from a test.
    fn given_a_profile_of_ours(&mut self) {
        self.port = free_port::free_port();
        let dirs = AppDirectories::under_root(&self.dir.path().join("njmule")).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let preferences = format!("net.tcpPort={}\nnet.kad.enabled=false\n", self.port);
        fs::write(dirs.config.join("njmule.properties"), preferences).unwrap();
        self.dirs = Some(dirs);
    }

    async fn given_a_running_engine(&mut self) {
        self.start().await;
    }

    async fn given_our_category(&self, id: u32, name: &str) {
        let ours = Category {
            id,
            name: name.to_owned(),
            incoming: None,
            comment: String::new(),
            color: 0,
            priority: DownloadPriority::Auto,
            patterns: String::new(),
            regex: false,
        };
        within(self.engine().set_categories(vec![ours])).await.unwrap();
    }

    async fn given_the_emule_profile_was_imported(&mut self, options: ImportOptions) {
        self.when_the_emule_profile_is_imported(options).await;
    }

    fn given_nothing_failed_in_the_import(&self) {
        let failed: Vec<_> = self.report().items.iter().filter(|item| item.outcome == Outcome::Failed).collect();
        assert!(failed.is_empty(), "{failed:?}");
    }

    fn given_the_nick_is_taken_but_not_the_port(&self) {
        let preferences = self.engine().preferences();
        assert_eq!(preferences.nick, NICK);
        assert_eq!(preferences.tcp_port, self.port);
    }

    fn given_the_kad_contacts_saved(&self, count: usize) {
        let data = fs::read(self.config().join(kad_node::NODES_FILE)).unwrap();
        assert_eq!(nodes_dat::read(&data).unwrap().contacts.len(), count);
    }

    // Both copies are one file: the complete one finishes, the other is then known. The name is reserved by an empty
    // file before the data replaces it.
    async fn given_the_complete_download_is_finished(&self) {
        let finished = self.engine().preferences().effective_incoming_dir(self.dirs()).join("complete.bin");
        let deadline = Instant::now() + SETTLE_WAIT;
        while fs::metadata(&finished).map_or(true, |metadata| metadata.len() != DOWNLOAD_SIZE as u64) {
            assert!(Instant::now() < deadline, "complete.bin finished");
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        assert_eq!(fs::read(&finished).unwrap(), self.emule().download_data);
        // The file is in place a moment before the engine lists the download as complete.
        while !within(self.engine().downloads())
            .await
            .iter()
            .any(|download| download.name == "complete.bin" && download.status == DownloadStatus::Complete)
        {
            assert!(Instant::now() < deadline, "the download listed as complete");
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }

    // The complete one stays listed as complete.
    async fn given_the_half_download_goes_on(&self) {
        let downloads: Vec<DownloadInfo> = within(self.engine().downloads())
            .await
            .into_iter()
            .filter(|download| download.status != DownloadStatus::Complete)
            .collect();
        assert_eq!(downloads.len(), 1);
        assert_eq!(downloads[0].hash, self.emule().half_hash);
        assert_eq!(downloads[0].completed, (DOWNLOAD_SIZE / 2) as u64);
    }

    // The files are shared by the scan that sharing their folder starts, which reads the folder off the engine's task.
    async fn given_the_file_is_shared(&self, name: &str) {
        let deadline = Instant::now() + SETTLE_WAIT;
        while !within(self.engine().shared_files()).await.iter().any(|file| file.name == name) {
            assert!(Instant::now() < deadline, "{name} is not shared");
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }

    fn given_the_identity_waits_for_the_next_start(&self) {
        assert!(self.config().join(emule_importer::PENDING_IDENTITY).is_dir());
    }

    fn given_the_report_says_the_searches_come_from_the_next_start(&self) {
        let items: Vec<_> = self.report().items.iter().filter(|item| item.file == stored_searches::FILE_NAME).collect();
        assert_eq!(items.len(), 1);
        assert!(items[0].detail().ends_with("from the next start"), "{}", items[0].detail());
    }

    // What closing the interface after a look at the searches does.
    async fn given_the_interface_saves_its_searches(&self) {
        within(self.engine().save_searches(Vec::new())).await.unwrap();
    }

    fn given_the_events_are_watched(&mut self) {
        self.subscription = Some(self.engine().subscribe());
    }

    async fn when_the_emule_profile_is_imported(&mut self, options: ImportOptions) {
        let emule = self.emule().config.clone();
        let report = tokio::time::timeout(IMPORT_WAIT, self.engine().import_from_emule(emule, options))
            .await
            .expect("imported in time")
            .unwrap();
        self.report = Some(report);
    }

    async fn when_the_engine_starts_again(&mut self) {
        within(self.engine().stop()).await;
        self.start().await;
    }

    fn then_the_engine_runs(&self) {
        assert!(self.engine().is_running());
    }

    fn then_no_identity_waits_any_more(&self) {
        assert!(!self.config().join(emule_importer::PENDING_IDENTITY).exists());
    }

    fn then_the_user_hash_is_the_one_of_the_emule_profile(&self) {
        let user_hash = |config: &PathBuf| {
            preferences_dat::read(&fs::read(config.join(user_hash_store::FILE_NAME)).unwrap()).unwrap().user_hash
        };
        assert_eq!(user_hash(&self.config()), user_hash(&self.emule().config));
    }

    fn then_our_file_is_the_one_of_the_emule_profile(&self, name: &str) {
        assert_eq!(fs::read(self.config().join(name)).unwrap(), fs::read(self.emule().config.join(name)).unwrap());
    }

    fn then_the_emule_profile_is_untouched(&self) {
        assert_eq!(self.emule().fingerprint(), self.before);
    }

    async fn then_the_saved_searches_are(&self, titles: &[&str]) {
        let searches = within(self.engine().saved_searches()).await;
        assert_eq!(searches.iter().map(|search| search.title.as_str()).collect::<Vec<_>>(), titles);
    }

    // Events reach their watchers through a channel: the announcement may come just after the import.
    async fn then_the_half_download_was_announced_in_category(&mut self, category: u32) {
        let half = self.emule().half_hash;
        let subscription = self.subscription.as_mut().expect("events watched");
        let announced = tokio::time::timeout(SETTLE_WAIT, async {
            loop {
                if let EngineEvent::DownloadAdded(download) = subscription.live.recv().await.unwrap()
                    && download.hash == half
                {
                    return download;
                }
            }
        })
        .await
        .expect("the half download announced");
        assert_eq!(announced.category, category);
    }

    async fn then_the_half_download_is_in_category(&self, category: u32) {
        let downloads = within(self.engine().downloads()).await;
        let halves: Vec<_> = downloads.iter().filter(|download| download.hash == self.emule().half_hash).collect();
        assert_eq!(halves.len(), 1);
        assert_eq!(halves[0].category, category);
    }

    fn then_the_report_is_a_dry_run(&self) {
        assert!(self.report().dry_run);
    }

    fn then_the_nick_is_not_taken(&self) {
        assert_ne!(self.engine().preferences().nick, NICK);
    }

    async fn then_there_is_no_download(&self) {
        assert!(within(self.engine().downloads()).await.is_empty());
    }

    fn then_no_kad_contact_is_saved(&self) {
        assert!(!self.config().join(kad_node::NODES_FILE).exists());
    }

    // No eMule runs as far as the engine knows: the user's own may run where the tests do.
    async fn start(&mut self) {
        let switches = Switches { emule_running: || false, ..Switches::QUIET };
        self.engine = Some(within(Engine::start_with(self.dirs().clone(), switches)).await.unwrap());
    }

    fn emule(&self) -> &SyntheticEmuleProfile {
        self.emule.as_ref().expect("an eMule profile")
    }

    fn dirs(&self) -> &AppDirectories {
        self.dirs.as_ref().expect("a profile of ours")
    }

    fn config(&self) -> PathBuf {
        self.dirs().config.clone()
    }

    fn report(&self) -> &ImportReport {
        self.report.as_ref().expect("an import")
    }

    fn engine(&self) -> Engine {
        self.engine.clone().expect("a running engine")
    }
}

impl Drop for EngineImport {
    fn drop(&mut self) {
        if let Some(engine) = self.engine.take() {
            tokio::spawn(async move { engine.stop().await });
        }
    }
}

fn options(dry_run: bool, identity: bool, downloads: bool) -> ImportOptions {
    ImportOptions { dry_run, identity, downloads }
}

async fn within<T>(work: impl Future<Output = T>) -> T {
    tokio::time::timeout(WAIT, work).await.expect("answered in time")
}
