// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's EmuleImporterTest, under its names, on a synthetic eMule profile.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod synthetic_emule_profile;

use std::collections::HashMap;
use std::fs;
use std::path::{MAIN_SEPARATOR, Path, PathBuf};

use njmule_engine::api::category::{self, Category};
use njmule_engine::api::import_options::ImportOptions;
use njmule_engine::api::import_report::{ImportReport, Outcome};
use njmule_engine::config::next_download::NextDownload;
use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::config::preferences::{self, Preferences};
use njmule_engine::config::proxy_settings::{ProxySettings, ProxyType};
use njmule_engine::persist::emule_importer::{self, EmuleImporter, Target};
use njmule_engine::persist::format::ini_file::IniFile;
use njmule_engine::persist::format::known_met::KnownFile;
use njmule_engine::persist::format::{known2_met, nodes_dat, preferences_dat, text_files};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};
use synthetic_emule_profile::{SyntheticEmuleProfile, fingerprint};

/// Records what the importer asks for.
#[derive(Default)]
struct Recorder {
    config_dir: PathBuf,
    preferences: Preferences,
    servers: Vec<PathBuf>,
    contacts: usize,
    known: usize,
    shared: Vec<PathBuf>,
    refused: Vec<PathBuf>,
    adopted: Vec<String>,
    adopted_from: Vec<PathBuf>,
    adopted_categories: HashMap<String, u32>,
    categories: Vec<Category>,
    own_categories: bool,
    refuse_preferences: bool,
}

impl Target for Recorder {
    fn config_dir(&self) -> PathBuf {
        self.config_dir.clone()
    }

    fn update_preferences(
        &mut self,
        change: &dyn Fn(&Preferences) -> Result<Preferences, String>,
    ) -> Result<(), String> {
        if self.refuse_preferences {
            return Err("refusing the preferences".to_owned());
        }
        self.preferences = change(&self.preferences)?;
        Ok(())
    }

    fn add_servers(&mut self, server_met: &Path) -> Result<usize, String> {
        self.servers.push(server_met.to_path_buf());
        Ok(2)
    }

    fn add_kad_contacts(&mut self, contacts: &nodes_dat::Contents) -> Result<usize, String> {
        self.contacts += contacts.contacts.len();
        Ok(contacts.contacts.len())
    }

    fn add_known_files(&mut self, files: &[KnownFile], _trees: &[known2_met::Entry]) -> usize {
        self.known += files.len();
        files.len()
    }

    fn share_directory(&mut self, directory: &Path) -> Result<(), String> {
        if self.refused.iter().any(|refused| refused == directory) {
            return Err(format!("refusing {}", directory.display()));
        }
        self.shared.push(directory.to_path_buf());
        Ok(())
    }

    fn add_categories(&mut self, categories: &[Category]) -> Result<usize, String> {
        if self.own_categories {
            return Ok(0);
        }
        self.categories.extend_from_slice(categories);
        Ok(categories.len())
    }

    fn adopt_download(&mut self, met: &Path, data: &Path, category: u32) -> Result<String, String> {
        assert!(data.exists());
        let name = met.file_name().unwrap().to_string_lossy().into_owned();
        self.adopted.push(name.clone());
        self.adopted_from.extend([met.to_path_buf(), data.to_path_buf()]);
        self.adopted_categories.insert(name.clone(), category);
        Ok(name)
    }
}

impl Recorder {
    fn untouched(&self) -> bool {
        self.servers.is_empty()
            && self.contacts == 0
            && self.known == 0
            && self.shared.is_empty()
            && self.adopted.is_empty()
            && self.preferences == Preferences::default()
    }
}

struct Fixture {
    dir: tempfile::TempDir,
    emule: SyntheticEmuleProfile,
    ours: PathBuf,
    target: Recorder,
    emule_running: bool,
    missing_profile: Option<PathBuf>,
    report: Option<ImportReport>,
}

fn profile() -> Fixture {
    let dir = tempfile::tempdir().unwrap();
    let emule = SyntheticEmuleProfile::create(&dir.path().join("emule"));
    let ours = dir.path().join("njmule");
    fs::create_dir_all(&ours).unwrap();
    let target = Recorder { config_dir: ours.clone(), ..Recorder::default() };
    Fixture { dir, emule, ours, target, emule_running: false, missing_profile: None, report: None }
}

const DRY_RUN_OF_ALL: ImportOptions = ImportOptions { dry_run: true, identity: true, downloads: true };
const ALL: ImportOptions = ImportOptions { dry_run: false, identity: true, downloads: true };
const NONE: ImportOptions = ImportOptions { dry_run: false, identity: false, downloads: false };
const IDENTITY: ImportOptions = ImportOptions { dry_run: false, identity: true, downloads: false };
const DOWNLOADS: ImportOptions = ImportOptions { dry_run: false, identity: false, downloads: true };

impl Fixture {
    fn import(&mut self, options: ImportOptions) -> &ImportReport {
        let running = self.emule_running;
        let config = self.missing_profile.clone().unwrap_or_else(|| self.emule.config.clone());
        self.target.config_dir.clone_from(&self.ours);
        let report = EmuleImporter::new(&config, options, &mut self.target, &move || running).run();
        self.report.insert(report)
    }

    fn report(&self) -> &ImportReport {
        self.report.as_ref().unwrap()
    }

    fn outcome(&self, file: &str) -> Option<Outcome> {
        self.report().items.iter().find(|item| item.file == file).map(|item| item.outcome)
    }

    fn detail(&self, file: &str) -> String {
        self.report().items.iter().find(|item| item.file == file).unwrap().detail()
    }

    fn assert_outcomes(&self, expected: &[(&str, Outcome)]) {
        for (file, outcome) in expected {
            assert_eq!(self.outcome(file), Some(*outcome), "{file}: {:?}", self.report().items);
        }
    }

    fn emule_file(&self, name: &str, data: &[u8]) {
        self.emule.write(name, data);
    }

    fn delete_emule_file(&self, name: &str) {
        fs::remove_file(self.emule.config.join(name)).unwrap();
    }

    fn replace_in_emule_preferences(&self, text: &str, replacement: &str) {
        let ini = self.emule.config.join("preferences.ini");
        let before = String::from_utf8(fs::read(&ini).unwrap()).unwrap();
        fs::write(&ini, before.replace(text, replacement)).unwrap();
    }

    fn emule_temp_directories(&self, directories: &[String]) {
        let ini = self.emule.config.join("preferences.ini");
        let before = String::from_utf8(fs::read(&ini).unwrap()).unwrap();
        fs::write(&ini, format!("{before}TempDirs={}\r\n", directories.join("|"))).unwrap();
    }

    fn our_file(&self, name: &str, content: &str) {
        fs::write(self.ours.join(name), content).unwrap();
    }

    fn pending_identity(&self) -> PathBuf {
        self.ours.join(emule_importer::PENDING_IDENTITY)
    }

    fn pending_files(&self) -> PathBuf {
        self.ours.join(emule_importer::PENDING_FILES)
    }

    fn apply_pending_identity(&self) -> bool {
        let running = self.emule_running;
        emule_importer::apply_pending_identity(&self.ours, &move || running).unwrap()
    }

    fn assert_ours_is_the_emule_one(&self, name: &str) {
        assert_eq!(fs::read(self.ours.join(name)).unwrap(), fs::read(self.emule.config.join(name)).unwrap(), "{name}");
    }

    fn assert_pending_identity_holds(&self, names: &[&str]) {
        for name in names {
            assert!(self.pending_identity().join(name).exists(), "{name}");
        }
    }

    fn assert_pending_identity_lacks(&self, names: &[&str]) {
        for name in names {
            assert!(!self.pending_identity().join(name).exists(), "{name}");
        }
    }

    fn assert_nothing_in_ours(&self) {
        assert_eq!(fs::read_dir(&self.ours).unwrap().count(), 0);
    }

    fn adopted(&self) -> Vec<String> {
        let mut adopted = self.target.adopted.clone();
        adopted.sort();
        adopted
    }
}

fn mapped(ini: &str) -> Preferences {
    emule_importer::map_preferences(&IniFile::parse(ini.as_bytes()), &Preferences::default()).unwrap()
}

#[test]
fn should_report_everything_and_change_nothing_on_a_dry_run() {
    let mut f = profile();
    let before = f.emule.fingerprint();

    f.import(DRY_RUN_OF_ALL);

    assert!(f.report().dry_run);
    f.assert_outcomes(&[
        ("preferences.ini", Outcome::WouldImport),
        ("server.met", Outcome::WouldImport),
        ("nodes.dat", Outcome::WouldImport),
        ("known.met", Outcome::WouldImport),
        ("shareddir.dat", Outcome::WouldImport),
        ("sharedfiles.dat", Outcome::Skipped),
        ("ipfilter.dat", Outcome::WouldImport),
        ("cancelled.met", Outcome::WouldImport),
        ("emfriends.met", Outcome::Missing),
        ("key_index.dat", Outcome::Skipped),
        ("preferences.dat", Outcome::WouldImport),
        ("cryptkey.dat", Outcome::WouldImport),
        ("clients.met", Outcome::WouldImport),
        ("preferencesKad.dat", Outcome::WouldImport),
        ("001.part.met", Outcome::WouldImport),
        ("002.part.met", Outcome::WouldImport),
    ]);
    assert_eq!(f.detail("server.met"), "2 servers");
    assert_eq!(f.detail("nodes.dat"), "3 Kad contacts");
    assert!(f.target.untouched());
    f.assert_nothing_in_ours();
    assert_eq!(f.emule.fingerprint(), before);
}

#[test]
fn should_take_everything_and_leave_emule_alone() {
    let mut f = profile();
    let before = f.emule.fingerprint();

    f.import(ALL);

    f.assert_outcomes(&[
        ("preferences.ini", Outcome::Imported),
        ("server.met", Outcome::Imported),
        ("preferences.dat", Outcome::Imported),
        ("001.part.met", Outcome::Imported),
    ]);
    let p = &f.target.preferences;
    assert_eq!(p.nick, synthetic_emule_profile::NICK);
    assert_eq!((p.max_upload_kibps, p.max_download_kibps), (50, 0));
    assert!(!p.kad_enabled);
    assert!(!p.remember_cancelled_files && p.remember_downloaded_files);
    assert!(!p.store_searches);
    assert!(p.add_downloads_paused);
    assert_eq!(p.next_download, NextDownload::SameCategoryFirst);
    assert!(!p.filter_lan_addresses);
    assert!(p.messages_from_friends_only);
    assert_eq!(p.message_filter, "buy now|cheap");
    assert!(p.clean_up_new_names);
    assert_eq!(p.name_cleanups, "dvdrip|sponsored");
    assert!(!p.search_spam_filter);
    assert!(p.advanced_spam_filter, "not in the profile");
    assert_eq!((p.new_connections_per_five_seconds, p.upload_queue_size), (35, 9000));
    assert_eq!(p.tcp_port, preferences::DEFAULT_TCP_PORT);
    assert_eq!(f.target.servers, [f.emule.config.join("server.met")]);
    assert_eq!(f.target.contacts, 3);
    assert_eq!(f.target.known, 1);
    assert_eq!(f.target.shared, [f.emule.incoming.clone(), f.emule.shared.clone()]);
    assert_eq!(f.adopted(), ["001.part.met", "002.part.met"]);
    assert!(f.pending_files().join("cancelled.met").exists());
    assert!(f.ours.join("ipfilter.dat").exists());
    assert_eq!(
        fs::read(f.pending_identity().join("preferences.dat")).unwrap(),
        fs::read(f.emule.config.join("preferences.dat")).unwrap()
    );
    f.assert_pending_identity_holds(&["cryptkey.dat", "clients.met", "preferencesKad.dat"]);
    let key = fs::read(f.pending_identity().join("kadudpkey.dat")).unwrap();
    assert_eq!(u32::from_le_bytes(key.try_into().unwrap()), synthetic_emule_profile::KAD_UDP_KEY);
    assert_eq!(f.emule.fingerprint(), before);
}

#[test]
fn should_keep_the_numbers_and_patterns_of_the_categories_and_give_them_a_colour_of_ours() {
    let mut f = profile();
    let ini = [
        "[General]".to_owned(),
        "Count=3".to_owned(),
        "[Cat#0]".to_owned(),
        "Title=all".to_owned(),
        "[Cat#1]".to_owned(),
        "Title=Films".to_owned(),
        format!("Incoming={}", f.emule.incoming.display()),
        "Comment=evening".to_owned(),
        "Color=255".to_owned(),
        "AutoCat=*.mkv|*.avi".to_owned(),
        "[Cat#2]".to_owned(),
        "Title=".to_owned(),
        "[Cat#3]".to_owned(),
        "Title=Linux".to_owned(),
        format!("Incoming={}", f.emule.shared.display()),
        "Color=16711680".to_owned(),
        "AutoCat=ubuntu|debian".to_owned(),
    ]
    .join("\r\n");
    f.emule_file("Category.ini", ini.as_bytes());

    f.import(NONE);

    f.assert_outcomes(&[("Category.ini", Outcome::Imported)]);
    let categories = &f.target.categories;
    assert_eq!(categories.iter().map(|c| c.id).collect::<Vec<_>>(), [1, 3]);
    assert_eq!(categories[0].name, "Films");
    assert_eq!(categories[0].incoming, None, "eMule's incoming folder is not ours: our own takes its place");
    assert_eq!(categories[0].color, 1, "red");
    assert!(categories[0].matches("Big Buck Bunny.MKV"));
    assert_eq!(categories[1].incoming.as_ref(), Some(&f.emule.shared));
    assert_eq!(categories[1].color, 6, "blue");
}

#[test]
fn should_give_an_emule_colour_one_of_ours() {
    for (rgb, expected, name) in [(0x80_8080, 0, "grey"), (0x00_A5FF, 2, "orange")] {
        assert_eq!(emule_importer::color(Some(rgb)), expected, "{name}");
    }
}

#[test]
fn should_shorten_a_category_we_cannot_fully_represent_instead_of_leaving_it_out() {
    let mut f = profile();
    let ini = [
        "[Cat#1]".to_owned(),
        format!("Title={}", "t".repeat(category::MAX_NAME + 10)),
        format!("Comment={}", "c".repeat(category::MAX_COMMENT + 10)),
        format!("AutoCat={}", "p".repeat(category::MAX_PATTERNS + 10)),
        "[Cat#2]".to_owned(),
        "Title=Broken".to_owned(),
        "AutoCat=(unclosed".to_owned(),
        "AutoCatAsRegularExpression=1".to_owned(),
        String::new(),
    ]
    .join("\r\n");
    f.emule_file("Category.ini", ini.as_bytes());

    f.import(NONE);

    let categories = &f.target.categories;
    assert_eq!(categories.iter().map(|c| c.id).collect::<Vec<_>>(), [1, 2]);
    assert_eq!(
        (categories[0].name.len(), categories[0].comment.len(), categories[0].patterns.len()),
        (category::MAX_NAME, category::MAX_COMMENT, category::MAX_PATTERNS)
    );
    assert_eq!(categories[1].patterns, "");
    let detail = f.detail("Category.ini");
    assert!(detail.contains("2 categories") && detail.contains("Broken"), "{detail}");
}

#[test]
fn should_keep_only_the_categories_that_came_with_the_downloads() {
    let mut f = profile();
    f.emule.put_in_category(1, 1);
    f.emule.put_in_category(2, 2);
    f.emule_file("Category.ini", b"[Cat#1]\r\nTitle=Films\r\n");

    f.import(DOWNLOADS);

    assert_eq!(f.target.adopted_categories["001.part.met"], 1);
    assert_eq!(f.target.adopted_categories["002.part.met"], category::NONE, "eMule's category 2 was not taken");
}

#[test]
fn should_adopt_the_downloads_without_category_when_the_target_keeps_its_own_categories() {
    let mut f = profile();
    f.emule.put_in_category(1, 1);
    f.emule.put_in_category(2, 2);
    f.emule_file("Category.ini", b"[Cat#1]\r\nTitle=Films\r\n");
    f.import(DOWNLOADS);
    f.target.own_categories = true;

    f.import(DOWNLOADS);

    assert_eq!(f.target.adopted_categories["001.part.met"], category::NONE);
}

#[test]
fn should_count_only_the_canonical_section_of_a_category() {
    let mut f = profile();
    f.emule_file("Category.ini", b"[Cat#1]\r\nTitle=Films\r\n[Cat#01]\r\nTitle=Copy\r\n[Cat# 2]\r\nTitle=Spaced\r\n");

    f.import(NONE);

    f.assert_outcomes(&[("Category.ini", Outcome::Imported)]);
    assert_eq!(f.target.categories.iter().map(|c| c.name.as_str()).collect::<Vec<_>>(), ["Films"]);
}

#[test]
fn should_keep_our_own_files() {
    let mut f = profile();
    f.our_file("ipfilter.dat", "");
    f.our_file("cancelled.met", "");

    f.import(NONE);

    f.assert_outcomes(&[("ipfilter.dat", Outcome::Skipped), ("cancelled.met", Outcome::Skipped)]);
    assert!(fs::read(f.ours.join("ipfilter.dat")).unwrap().is_empty());
}

#[test]
fn should_put_the_files_the_engine_rewrites_in_their_place_at_the_next_start() {
    let mut f = profile();
    f.emule_file("staticservers.dat", b"10.1.2.3:4661,1,Static\r\n");
    f.import(NONE);
    assert!(f.detail("cancelled.met").ends_with("from the next start"));
    assert!(!f.ours.join("cancelled.met").exists());
    // What the engine writes before it stops.
    f.our_file("staticservers.dat", "");

    let applied = emule_importer::apply_pending_files(&f.ours);

    assert_eq!(applied, 2);
    f.assert_ours_is_the_emule_one("cancelled.met");
    f.assert_ours_is_the_emule_one("staticservers.dat");
    assert!(!f.pending_files().exists());
}

#[test]
fn should_apply_the_pending_files_only_once() {
    let mut f = profile();
    f.emule_file("staticservers.dat", b"10.1.2.3:4661,1,Static\r\n");
    f.import(NONE);
    f.our_file("staticservers.dat", "");
    emule_importer::apply_pending_files(&f.ours);

    let applied = emule_importer::apply_pending_files(&f.ours);

    assert_eq!(applied, 0);
}

#[test]
fn should_replace_the_files_waiting_and_keep_our_own_on_a_second_import() {
    let mut f = profile();
    f.emule_file("staticservers.dat", b"10.1.2.3:4661,1,Static\r\n");
    f.our_file("staticservers.dat", "");
    f.import(NONE);
    // Written by the engine after the first import, so not of its own before it.
    f.our_file("cancelled.met", "");

    f.import(NONE);

    f.assert_outcomes(&[("cancelled.met", Outcome::Imported), ("staticservers.dat", Outcome::Skipped)]);
    assert!(!f.pending_files().join("staticservers.dat").exists());
}

#[test]
fn should_leave_the_emule_files_njmule_does_not_read() {
    let mut f = profile();
    let names = ["statistics.ini", "AC_BootstrapIPs.dat", "AC_IPFilterUpdateURLs.dat"];
    for name in names {
        f.emule_file(name, b"[General]\nx=1\n");
    }

    f.import(NONE);

    for name in names {
        assert_eq!(f.outcome(name), None, "{name}");
        assert!(!f.ours.join(name).exists(), "{name}");
        assert!(!f.pending_files().join(name).exists(), "{name}");
    }
}

#[test]
fn should_offer_again_what_was_typed_in_the_boxes_of_the_emule_imported() {
    let mut f = profile();
    let searches = ["linux iso", "talk", "linux iso"].map(str::to_owned);
    f.emule_file("AC_SearchStrings.dat", &text_files::write_lines(&searches));
    f.emule_file("AC_ServerMetURLs.dat", &text_files::write_lines(&["http://example.org/server.met".to_owned()]));
    f.import(NONE);

    let typed = emule_importer::history(&f.ours);

    assert_eq!(typed.searches, ["linux iso", "talk"]);
    assert_eq!(typed.server_lists, ["http://example.org/server.met"]);
}

#[test]
fn should_offer_nothing_typed_before_an_import() {
    let f = profile();

    let typed = emule_importer::history(&f.ours);

    assert_eq!(typed, Default::default());
}

#[test]
fn should_take_the_identity_and_the_downloads_only_when_asked() {
    let mut f = profile();

    f.import(NONE);

    f.assert_outcomes(&[
        ("preferences.dat", Outcome::Skipped),
        ("cryptkey.dat", Outcome::Skipped),
        ("Temp", Outcome::Skipped),
    ]);
    assert_eq!(f.outcome("001.part.met"), None);
    assert!(!f.pending_identity().exists());
    assert!(f.target.adopted.is_empty());
}

#[test]
fn should_leave_a_running_emule_its_identity_and_active_downloads() {
    let mut f = profile();
    f.emule_running = true;

    f.import(ALL);

    f.assert_outcomes(&[
        ("preferences.dat", Outcome::Skipped),
        ("001.part.met", Outcome::Imported),
        ("002.part.met", Outcome::Skipped),
    ]);
    assert!(f.detail("002.part.met").contains("pause it in eMule"));
    assert!(!f.pending_identity().exists());
    assert_eq!(f.adopted(), ["001.part.met"]);
}

#[test]
fn should_take_the_user_hash_only_with_its_key() {
    let mut f = profile();
    f.emule_file("cryptkey.dat", b"not base64 at all!");

    f.import(IDENTITY);

    f.assert_outcomes(&[("cryptkey.dat", Outcome::Failed)]);
    assert!(f.report().items.iter().any(|item| item.detail().contains("the user hash and the key are taken only")));
    assert!(!f.pending_identity().exists());
}

#[test]
fn should_report_broken_files_and_go_on_with_the_rest() {
    let mut f = profile();
    f.emule_file("server.met", &[0xE0, 1]);
    f.delete_emule_file("preferences.ini");

    f.import(NONE);

    f.assert_outcomes(&[
        ("server.met", Outcome::Failed),
        ("preferences.ini", Outcome::Missing),
        ("nodes.dat", Outcome::Imported),
    ]);
    assert!(f.target.servers.is_empty());
}

#[test]
fn should_keep_the_pending_identity_waiting_while_emule_runs() {
    let mut f = profile();
    f.import(IDENTITY);
    f.emule_running = true;

    let applied = f.apply_pending_identity();

    assert!(!applied);
    assert!(f.pending_identity().is_dir());
}

#[test]
fn should_apply_the_pending_identity_once_emule_is_closed() {
    let mut f = profile();
    f.import(IDENTITY);
    f.emule_running = true;
    f.apply_pending_identity();
    f.emule_running = false;

    let applied = f.apply_pending_identity();

    assert!(applied);
    assert!(!f.pending_identity().exists());
    f.assert_ours_is_the_emule_one("preferences.dat");
    assert!(f.ours.join("kadudpkey.dat").exists());
}

#[test]
fn should_apply_the_pending_identity_only_once() {
    let mut f = profile();
    f.import(IDENTITY);
    f.apply_pending_identity();

    let applied = f.apply_pending_identity();

    assert!(!applied);
}

#[test]
fn should_still_take_an_identity_imported_twice_before_a_restart() {
    let mut f = profile();
    f.import(IDENTITY);
    f.import(IDENTITY);

    let applied = f.apply_pending_identity();

    assert!(applied);
    assert!(!f.pending_identity().exists());
    f.assert_ours_is_the_emule_one("preferences.dat");
}

#[test]
fn should_not_let_leftovers_in_the_pending_identity_stop_the_start() {
    let mut f = profile();
    f.import(IDENTITY);
    fs::write(f.pending_identity().join("preferences.dat.bak"), "older").unwrap();
    fs::write(f.pending_identity().join("cryptkey.dat.tmp"), "interrupted").unwrap();
    // A folder among the leftovers must not stop the removal of the files listed after it.
    fs::create_dir_all(f.pending_identity().join("a folder")).unwrap();
    fs::write(f.pending_identity().join("a folder").join("x"), "x").unwrap();

    let applied = f.apply_pending_identity();

    assert!(applied);
    assert!(!f.pending_identity().exists());
    f.assert_ours_is_the_emule_one("cryptkey.dat");
}

#[test]
fn should_replace_the_whole_pending_identity_with_that_of_another_profile() {
    let mut f = profile();
    f.import(IDENTITY);
    f.emule = SyntheticEmuleProfile::create(&f.dir.path().join("other"));
    f.delete_emule_file("clients.met");
    f.delete_emule_file("preferencesKad.dat");
    f.replace_in_emule_preferences("KadUDPKey=", "Unused=");

    f.import(IDENTITY);

    assert_eq!(
        fs::read(f.pending_identity().join("preferences.dat")).unwrap(),
        fs::read(f.emule.config.join("preferences.dat")).unwrap()
    );
    f.assert_pending_identity_lacks(&["clients.met", "preferencesKad.dat", "kadudpkey.dat"]);
}

#[test]
fn should_not_apply_a_user_hash_without_its_key() {
    let mut f = profile();
    f.import(IDENTITY);
    fs::remove_file(f.pending_identity().join("cryptkey.dat")).unwrap();

    let applied = f.apply_pending_identity();

    assert!(!applied);
    assert!(!f.ours.join("preferences.dat").exists());
    assert!(!f.pending_identity().exists());
}

#[test]
fn should_keep_the_pending_identity_on_a_dry_run() {
    let mut f = profile();
    f.import(IDENTITY);
    f.delete_emule_file("cryptkey.dat");

    f.import(ImportOptions { dry_run: true, identity: true, downloads: false });

    f.assert_pending_identity_holds(&["preferences.dat", "cryptkey.dat"]);
}

#[test]
fn should_take_only_usable_values_of_the_preferences() {
    let ini = [
        "[eMule]".to_owned(),
        format!("Nick={}", "x".repeat(preferences::MAX_NICK_LENGTH + 1)),
        "MaxUpload=-1".to_owned(),
        "MaxDownload=0".to_owned(),
        "MaxConnections=999999".to_owned(),
        "DownloadCapacity=2048".to_owned(),
        "UploadCapacityNew=-1".to_owned(),
        "CheckDiskspace=1".to_owned(),
        "MinFreeDiskSpace=1073741824".to_owned(),
        "CryptLayerRequired=1".to_owned(),
    ]
    .join("\n");

    let p = mapped(&ini);

    assert_eq!(p.nick, Preferences::default().nick);
    assert_eq!((p.max_upload_kibps, p.max_download_kibps), (0, 0));
    assert_eq!(p.max_connections, 10_000);
    assert_eq!(p.download_capacity_kibps, 2048);
    assert_eq!(p.upload_capacity_kibps, preferences::DEFAULT_UPLOAD_CAPACITY, "an unknown capacity keeps ours");
    assert_eq!(p.min_free_space_mib, 1024, "eMule keeps bytes");
    assert_eq!(p.obfuscation, ObfuscationMode::Required);
}

#[test]
fn should_use_the_free_space_minimum_of_emule_only_while_its_check_is_on() {
    let p = mapped("[eMule]\nCheckDiskspace=0\nMinFreeDiskSpace=1");

    assert_eq!(
        p.min_free_space_mib,
        preferences::DEFAULT_MIN_FREE_SPACE,
        "eMule's minimum is used only while its check is on"
    );
}

#[test]
fn should_turn_obfuscation_off_when_emule_does_not_support_it() {
    assert_eq!(mapped("[eMule]\nCryptLayerSupported=0").obfuscation, ObfuscationMode::Disabled);
}

#[test]
fn should_keep_our_obfuscation_without_preferences() {
    assert_eq!(mapped("").obfuscation, Preferences::default().obfuscation);
}

#[test]
fn should_keep_the_size_of_a_free_space_minimum_of_two_gigabytes_or_more() {
    // eMule writes the 32-bit byte count as a signed number: 3 GiB comes out negative.
    let p = mapped("[eMule]\nCheckDiskspace=1\nMinFreeDiskSpace=-1073741824\n");

    assert_eq!(p.min_free_space_mib, 3072);
}

#[test]
fn should_give_every_start_next_file_value_its_meaning() {
    for (value, next) in [
        (-1, NextDownload::Any),
        (0, NextDownload::None),
        (1, NextDownload::Any),
        (2, NextDownload::SameCategoryFirst),
        (3, NextDownload::SameCategory),
        (4, NextDownload::SameCategoryFirst),
        (9, NextDownload::SameCategoryFirst),
    ] {
        assert_eq!(mapped(&format!("[eMule]\nStartNextFile={value}")).next_download, next, "{value}");
    }
}

#[test]
fn should_take_the_socks_proxy_emule_uses() {
    let p = mapped(
        "[eMule]\n[Proxy]\nProxyEnableProxy=1\nProxyType=3\nProxyName=proxy.lan\nProxyPort=9050\n\
         ProxyEnablePassword=1\nProxyUser=me\nProxyPassword=pw\n",
    );

    let socks = ProxySettings {
        kind: ProxyType::Socks,
        host: "proxy.lan".to_owned(),
        port: 9050,
        user: "me".to_owned(),
        password: "pw".to_owned(),
    };
    assert_eq!(p.proxy, socks);
}

#[test]
fn should_take_the_http_proxy_emule_uses_with_the_default_port_and_no_credentials() {
    let p = mapped("[eMule]\n[Proxy]\nProxyEnableProxy=1\nProxyType=5\nProxyName=h\nProxyUser=x\n");

    assert_eq!(p.proxy, ProxySettings { kind: ProxyType::Http, host: "h".to_owned(), ..ProxySettings::default() });
}

#[test]
fn should_take_no_proxy_when_emule_uses_none_or_an_unusable_one() {
    for proxy in [
        "[Proxy]\nProxyEnableProxy=0\nProxyType=3\nProxyName=h\n",
        "[Proxy]\nProxyEnableProxy=1\nProxyType=9\nProxyName=h\n",
        "[Proxy]\nProxyEnableProxy=1\nProxyType=3\nProxyName=\n",
    ] {
        assert_eq!(mapped(&format!("[eMule]\n{proxy}")).proxy, ProxySettings::default(), "{proxy}");
    }
}

#[test]
fn should_fail_a_profile_without_preferences_section() {
    let mut f = profile();
    f.emule_file("preferences.ini", b"[Other]\nA=1\n");

    f.import(ALL);

    f.assert_outcomes(&[("preferences.ini", Outcome::Failed)]);
    assert_eq!(f.target.preferences, Preferences::default());
    // Without preferences.ini the temp directory is looked for next to the config.
    assert_eq!(f.adopted(), ["001.part.met", "002.part.met"]);
}

#[test]
fn should_take_the_downloads_from_every_temp_directory() {
    let mut f = profile();
    let other = f.dir.path().join("emule").join("Temp2");
    fs::create_dir_all(&other).unwrap();
    for name in ["002.part.met", "002.part"] {
        fs::rename(f.emule.temp.join(name), other.join(name)).unwrap();
    }
    let gone = f.dir.path().join("gone").display().to_string();
    f.emule_temp_directories(&[gone, other.display().to_string()]);

    f.import(DOWNLOADS);

    assert_eq!(f.adopted(), ["001.part.met", "002.part.met"]);
}

#[test]
fn should_report_an_empty_temp_directory() {
    let mut f = profile();
    for entry in fs::read_dir(&f.emule.temp).unwrap() {
        fs::remove_file(entry.unwrap().path()).unwrap();
    }

    f.import(DOWNLOADS);

    f.assert_outcomes(&[("Temp", Outcome::Missing)]);
    assert_eq!(f.detail("Temp"), "no downloads in progress");
}

#[test]
fn should_ignore_the_relative_directories_of_the_profile() {
    let mut f = profile();
    f.replace_in_emule_preferences(&format!("IncomingDir={}", f.emule.incoming.display()), "IncomingDir=.");
    f.replace_in_emule_preferences(&format!("TempDir={}", f.emule.temp.display()), "TempDir=.");
    f.emule_file("shareddir.dat", &text_files::write_lines(&[".".to_owned(), "..".to_owned()]));

    f.import(DOWNLOADS);

    assert!(f.target.shared.is_empty());
    f.assert_outcomes(&[("shareddir.dat", Outcome::Missing)]);
    // Without a usable temp directory, the one next to the config.
    assert_eq!(f.adopted(), ["001.part.met", "002.part.met"]);
}

#[test]
fn should_fail_a_huge_file_alone_instead_of_exhausting_memory() {
    let mut f = profile();
    let known = f.emule.config.join("known.met");
    fs::File::create(&known).unwrap().set_len(3 << 30).unwrap();

    f.import(NONE);

    f.assert_outcomes(&[("known.met", Outcome::Failed), ("server.met", Outcome::Imported)]);
}

#[test]
fn should_import_nothing_into_the_own_directory_of_emule() {
    for inside in ["", "njmule"] {
        let mut f = profile();
        f.ours = f.emule.config.join(inside);
        let before = f.emule.fingerprint();

        f.import(ALL);

        assert_eq!(f.report().items.len(), 1, "{inside}");
        assert_eq!(f.report().items[0].outcome, Outcome::Failed);
        assert!(f.target.untouched());
        assert_eq!(f.emule.fingerprint(), before);
    }
}

#[test]
fn should_leave_only_the_aich_trees_out_when_their_file_is_broken() {
    let mut f = profile();
    f.emule_file("known2_64.met", &[2, 1]);

    f.import(NONE);

    f.assert_outcomes(&[("known.met", Outcome::Imported)]);
    assert!(f.detail("known.met").contains("known2_64.met"));
    assert_eq!(f.target.known, 1);
}

#[test]
fn should_change_neither_emule_nor_what_it_took_already_on_a_second_import() {
    let mut f = profile();
    let before = f.emule.fingerprint();
    f.import(IDENTITY);

    f.import(IDENTITY);

    f.assert_outcomes(&[("ipfilter.dat", Outcome::Skipped), ("preferences.dat", Outcome::Imported)]);
    assert_eq!(f.emule.fingerprint(), before);
}

#[test]
fn should_leave_our_files_as_they_were_on_a_dry_run() {
    let mut f = profile();
    f.import(IDENTITY);
    let before = fingerprint(&f.ours);

    f.import(DRY_RUN_OF_ALL);

    assert_eq!(fingerprint(&f.ours), before);
}

#[test]
fn should_report_a_missing_profile_and_write_nothing() {
    let mut f = profile();
    f.missing_profile = Some(f.dir.path().join("no such profile"));

    f.import(ALL);

    assert!(!f.report().items.is_empty());
    for item in &f.report().items {
        assert!(matches!(item.outcome, Outcome::Missing | Outcome::Skipped), "{item:?}");
    }
    assert!(f.target.untouched());
    f.assert_nothing_in_ours();
}

#[test]
fn should_fail_empty_or_misplaced_files_alone_and_go_on_with_the_rest() {
    let mut f = profile();
    for name in ["server.met", "known.met", "nodes.dat"] {
        f.emule_file(name, &[]);
    }
    f.delete_emule_file("preferences.ini");
    fs::create_dir(f.emule.config.join("preferences.ini")).unwrap();

    f.import(DOWNLOADS);

    f.assert_outcomes(&[
        ("server.met", Outcome::Failed),
        ("known.met", Outcome::Failed),
        ("nodes.dat", Outcome::Failed),
        ("preferences.ini", Outcome::Failed),
        ("cancelled.met", Outcome::Imported),
    ]);
    assert_eq!(f.adopted(), ["001.part.met", "002.part.met"]);
}

#[test]
fn should_leave_a_download_named_to_climb_out_where_it_is() {
    let mut f = profile();
    f.emule.retag(2, Tag::String { name: TagName::id(file_tag::FILENAME), value: "../../evil.bin".to_owned() });

    f.import(DOWNLOADS);

    for adopted in &f.target.adopted_from {
        assert_eq!(adopted.parent(), Some(f.emule.temp.as_path()));
    }
    assert!(!f.dir.path().join("evil.bin").exists());
}

#[test]
fn should_adopt_each_download_once_from_a_temp_directory_named_twice() {
    let mut f = profile();
    let temp = f.emule.temp.display().to_string();
    f.emule_temp_directories(&[format!("{temp}{MAIN_SEPARATOR}."), format!("{temp}{MAIN_SEPARATOR}")]);

    f.import(DOWNLOADS);

    assert_eq!(f.target.adopted.len(), 2);
    assert_eq!(f.adopted(), ["001.part.met", "002.part.met"]);
}

#[test]
fn should_leave_no_identity_waiting_for_an_unmarked_user_hash() {
    let mut f = profile();
    let unmarked = preferences_dat::Contents {
        version: preferences_dat::VERSION,
        user_hash: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16],
        window_placement: Vec::new(),
    };
    f.emule_file("preferences.dat", &preferences_dat::write(&unmarked));

    f.import(IDENTITY);

    f.assert_outcomes(&[("preferences.dat", Outcome::Failed)]);
    assert!(!f.pending_identity().exists());
}

#[test]
fn should_keep_the_rest_of_the_identity_when_the_credits_are_corrupt() {
    let mut f = profile();
    f.emule_file("clients.met", &[0x12, 1, 0]);

    f.import(IDENTITY);

    f.assert_outcomes(&[("clients.met", Outcome::Failed)]);
    f.assert_pending_identity_holds(&["preferences.dat", "cryptkey.dat"]);
    f.assert_pending_identity_lacks(&["clients.met"]);
}

#[test]
fn should_share_the_other_directories_when_one_is_refused() {
    let mut f = profile();
    f.target.refused.push(f.emule.incoming.clone());

    f.import(NONE);

    f.assert_outcomes(&[("shareddir.dat", Outcome::Imported)]);
    let detail = f.detail("shareddir.dat");
    assert!(detail.contains("1 folders") && detail.contains("1 not shared") && detail.contains("refusing"), "{detail}");
    assert_eq!(f.target.shared, [f.emule.shared.clone()]);
}

// Beyond the Java engine's examples: what its other tests and the interfaces check.

#[test]
fn should_take_every_value_of_the_emule_preferences() {
    let p = mapped(
        "[eMule]\nSeeShare=1\nFilterLevel=300\nDeadServerRetry=0\nCommentFilter=spam|ads\nVideoPlayer=vlc\n\
         VideoPlayerArgs=--fullscreen\nUploadCapacityNew=512\nManualHighPrio=1\nAddServersFromServer=1\n",
    );

    assert_eq!(p.shared_files_visibility.name(), "FRIENDS");
    assert_eq!(p.ip_filter_level, preferences::MAX_IP_FILTER_LEVEL);
    assert_eq!(p.dead_server_retries, 1);
    assert_eq!(p.comment_filter, "spam|ads");
    assert_eq!((p.video_player.as_str(), p.video_player_args.as_str()), ("vlc", "--fullscreen"));
    assert_eq!(p.upload_capacity_kibps, 512);
    assert!(p.manual_servers_high_priority && p.add_servers_from_server);
    for (see_share, visibility) in [(0, "EVERYBODY"), (2, "NOBODY"), (7, "NOBODY")] {
        assert_eq!(mapped(&format!("[eMule]\nSeeShare={see_share}")).shared_files_visibility.name(), visibility);
    }
}

#[test]
fn should_give_each_hue_its_colour() {
    for (bgr, expected) in
        [(0x00_FF00, 4), (0xFF_0080, 7), (0xFF_00FF, 8), (0x80_00FF, 8), (0x00_FFFF, 2), (0x00_FFC0, 3), (0xFF_FF00, 5)]
    {
        assert_eq!(emule_importer::color(Some(bgr)), expected, "{bgr:06X}");
    }
    assert_eq!(emule_importer::color(None), 0);
    assert_eq!(emule_importer::color(Some(0x100_0000)), 0);
}

#[test]
fn should_keep_the_lists_and_texts_the_engine_keeps() {
    use njmule_engine::persist::format::{friends_met, search_files};
    let mut f = profile();
    f.emule_file("emfriends.met", &friends_met::write(&[]).unwrap());
    f.emule_file("StoredSearches.met", &search_files::write_stored(&[]).unwrap());
    f.emule_file("SearchSpam.met", &search_files::write_spam(&[]).unwrap());
    f.emule_file("fileinfo.ini", b"[0123456789ABCDEF0123456789ABCDEF]\r\nComment=good\r\n");
    f.emule_file("addresses.dat", b"http://example.org/server.met\r\n");

    f.import(NONE);

    for live in ["emfriends.met", "StoredSearches.met", "SearchSpam.met", "fileinfo.ini"] {
        assert_eq!(f.outcome(live), Some(Outcome::Imported), "{live}");
        assert!(f.pending_files().join(live).exists(), "{live}");
    }
    assert_eq!(f.detail("fileinfo.ini"), "1 file comments; from the next start");
    assert_eq!(f.detail("addresses.dat"), "1 addresses");
    f.assert_ours_is_the_emule_one("addresses.dat");
}

#[test]
fn should_take_the_aich_trees_with_the_known_files() {
    let mut f = profile();
    f.emule_file("known2_64.met", &known2_met::write(&[]).unwrap());

    f.import(NONE);

    assert_eq!(f.detail("known.met"), "1 known files; 0 AICH trees; 1 new");
}

#[test]
fn should_report_preferences_the_engine_refuses() {
    let mut f = profile();
    f.target.refuse_preferences = true;

    f.import(NONE);

    f.assert_outcomes(&[("preferences.ini", Outcome::Failed)]);
    assert_eq!(f.detail("preferences.ini"), "refusing the preferences");
}

#[test]
fn should_fail_the_shared_folders_when_every_one_is_refused() {
    let mut f = profile();
    f.target.refused.extend([f.emule.incoming.clone(), f.emule.shared.clone()]);

    f.import(NONE);

    f.assert_outcomes(&[("shareddir.dat", Outcome::Failed)]);
    assert!(f.detail("shareddir.dat").contains("refusing"));
}

#[test]
fn should_fail_an_unreadable_list_of_shared_folders() {
    let mut f = profile();
    f.delete_emule_file("shareddir.dat");
    fs::create_dir(f.emule.config.join("shareddir.dat")).unwrap();

    f.import(NONE);

    f.assert_outcomes(&[("shareddir.dat", Outcome::Failed)]);
    assert!(f.target.shared.is_empty());
}

#[test]
fn should_take_no_category_from_a_file_of_all_downloads_only() {
    let mut f = profile();
    f.emule_file("Category.ini", b"[Cat#0]\r\nTitle=all\r\n");

    f.import(NONE);

    f.assert_outcomes(&[("Category.ini", Outcome::Skipped)]);
    assert_eq!(f.detail("Category.ini"), "no category besides all downloads");
}

#[test]
fn should_tell_the_categories_a_dry_run_would_take() {
    let mut f = profile();
    f.emule_file("Category.ini", b"[Cat#1]\r\nTitle=Films\r\n");

    f.import(DRY_RUN_OF_ALL);

    f.assert_outcomes(&[("Category.ini", Outcome::WouldImport)]);
    assert_eq!(f.detail("Category.ini"), "1 categories");
    assert!(f.target.categories.is_empty());
}

#[test]
fn should_fail_a_download_without_its_data() {
    let mut f = profile();
    fs::remove_file(f.emule.temp.join("002.part")).unwrap();

    f.import(DOWNLOADS);

    f.assert_outcomes(&[("002.part.met", Outcome::Failed)]);
    assert_eq!(f.detail("002.part.met"), "half.bin: no data file");
    assert_eq!(f.adopted(), ["001.part.met"]);
}

#[test]
fn should_import_even_when_the_manifest_cannot_be_written() {
    let mut f = profile();
    // A file where the folder of the manifests goes.
    f.our_file(emule_importer::MANIFESTS, "");

    f.import(NONE);

    f.assert_outcomes(&[("server.met", Outcome::Imported)]);
    assert!(f.ours.join(emule_importer::MANIFESTS).is_file());
}

#[test]
fn should_list_what_an_import_read_in_its_manifest() {
    let mut f = profile();

    f.import(NONE);

    let manifests: Vec<_> = fs::read_dir(f.ours.join(emule_importer::MANIFESTS)).unwrap().collect();
    assert_eq!(manifests.len(), 1);
    let manifest = fs::read_to_string(manifests[0].as_ref().unwrap().path()).unwrap();
    assert!(manifest.lines().any(|line| line.starts_with("IMPORTED\t") && line.contains("\tserver.met\t2 servers")));
    assert!(manifest.lines().any(|line| line.starts_with("MISSING\t\t\temfriends.met\t")));
}

#[test]
fn should_offer_nothing_typed_of_an_unreadable_or_huge_history() {
    let f = profile();
    fs::create_dir(f.ours.join("AC_SearchStrings.dat")).unwrap();
    fs::File::create(f.ours.join("AC_ServerMetURLs.dat")).unwrap().set_len(300 << 20).unwrap();

    let typed = emule_importer::history(&f.ours);

    assert_eq!(typed, Default::default());
}

#[test]
fn should_try_the_pending_files_again_when_one_cannot_be_put_in_place() {
    let mut f = profile();
    f.import(NONE);
    // A folder where the file goes.
    fs::create_dir(f.ours.join("cancelled.met")).unwrap();

    let applied = emule_importer::apply_pending_files(&f.ours);

    assert_eq!(applied, 0);
    assert!(f.pending_files().join("cancelled.met").exists());
}

#[test]
fn should_tell_whether_an_emule_runs_from_the_processes() {
    let running = emule_importer::is_emule_running();

    // Whether one runs here or not, a second look sees the same.
    assert_eq!(running, emule_importer::is_emule_running());
}
