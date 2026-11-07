// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's DownloadManagerTest, under its names: what the user does with downloads, on the downloads as the
//! engine runs them. Those about where sources come from wait for their port (`tests/not_ported.txt`).

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::panic
)]

mod download_lab;

use std::path::PathBuf;
use std::time::Duration;

use download_lab::{Downloads, Lab, PORT};
use njmule_engine::api::category::{self, Category};
use njmule_engine::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus};
use njmule_engine::api::ed2k_link::{Ed2kLink, FileLink, Source};
use njmule_engine::config::next_download::NextDownload;
use njmule_engine::download::download_manager::DownloadEvent;

const OUR_ID: u32 = u32::from_le_bytes([198, 51, 100, 7]);
const LINK_TO_STOP: &str = "ed2k://|file|stop.bin|5000|56565656565656565656565656565656|/|sources,192.0.2.10:4662|/";
const LINK_WITH_ODD_SOURCES: &str = "ed2k://|file|odd.bin|5000|4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a|/|sources,0.1.2.3:4662,\
     224.0.0.251:4662,240.1.2.3:4662,255.255.255.255:4662|/";
// A few ticks of the downloads.
const QUIET: Duration = Duration::from_secs(1);

struct Manager {
    downloads: Option<Downloads>,
    categories: Vec<Category>,
    clean_up: Option<&'static str>,
    answers: bool,
    hash: [u8; 16],
    info: Option<DownloadInfo>,
    answer: bool,
    renamed: Option<Result<bool, &'static str>>,
    next: Option<DownloadInfo>,
    preview: Option<PathBuf>,
}

impl Manager {
    fn new() -> Self {
        Self {
            downloads: None,
            categories: Vec::new(),
            clean_up: None,
            answers: false,
            hash: [0; 16],
            info: None,
            answer: false,
            renamed: None,
            next: None,
            preview: None,
        }
    }

    async fn given_the_download_manager(&mut self) {
        let mut settings = Downloads::settings();
        if let Some(words) = self.clean_up {
            settings.clean_up_new_names = true;
            settings.name_cleanups = words.to_owned();
        }
        let downloads = Downloads::start([0xab; 16], OUR_ID, settings);
        downloads.server.lock().unwrap().connected = self.answers;
        let categories = self.categories.clone();
        downloads.run(move |lab| lab.manager.set_categories(categories)).await;
        self.downloads = Some(downloads);
    }

    async fn given_the_download_manager_with_a_server_that_answers(&mut self) {
        self.answers = true;
        self.given_the_download_manager().await;
    }

    async fn given_the_download_manager_restarted(&mut self) {
        self.downloads.as_mut().unwrap().restart().await;
    }

    fn given_the_categories(&mut self, list: Vec<Category>) {
        self.categories = list;
    }

    fn given_only_the_first_category_left(&mut self) {
        self.categories.truncate(1);
    }

    fn given_the_names_of_new_downloads_cleaned_up_of(&mut self, words: &'static str) {
        self.clean_up = Some(words);
    }

    async fn given_a_download(&mut self, name: &str, hash: [u8; 16]) {
        self.hash = hash;
        self.downloads().add(FileLink::of(name, 5000, hash).unwrap()).await;
    }

    async fn given_a_paused_download(&mut self, name: &str, size: u64, hash: [u8; 16]) {
        self.hash = hash;
        let link = FileLink::of(name, size, hash).unwrap();
        self.run(move |lab| {
            lab.add(&link);
            pause(lab, &hash)
        })
        .await;
    }

    async fn given_the_link_added_with_the_priority(&mut self, uri: &str, priority: DownloadPriority) {
        let link = file_link(uri);
        self.hash = link.hash;
        self.run(move |lab| {
            lab.add(&link);
            lab.manager.set_priority(&link.hash, priority)
        })
        .await;
    }

    async fn given_the_download_stopped(&mut self) {
        let hash = self.hash;
        self.run(move |lab| stop(lab, &hash)).await;
    }

    async fn given_the_link_added_again(&mut self, size: u64, aich: Option<[u8; 20]>, source: &str) {
        self.when_the_link_is_added_again(size, aich, source).await;
    }

    async fn given_a_paused_film_a_stopped_urgent_file_and_a_paused_later_file(
        &mut self,
        film: [u8; 16],
        urgent: [u8; 16],
        later: [u8; 16],
    ) {
        self.run(move |lab| {
            lab.add(&FileLink::of("film.mkv", 5000, film).unwrap());
            lab.add(&FileLink::of("urgent.txt", 5000, urgent).unwrap());
            lab.add(&FileLink::of("later.txt", 5000, later).unwrap());
            lab.manager.set_priority(&film, DownloadPriority::Low);
            lab.manager.set_priority(&urgent, DownloadPriority::High);
            lab.manager.set_priority(&later, DownloadPriority::Normal);
            pause(lab, &film);
            stop(lab, &urgent);
            pause(lab, &later);
        })
        .await;
    }

    async fn given_the_next_download_resumed(&mut self, category: u32, mode: NextDownload) {
        assert!(self.resume_next(category, mode).await.is_some());
    }

    async fn given_the_download_moved_to_the_category(&mut self, category: u32) {
        let hash = self.hash;
        assert!(self.run(move |lab| lab.manager.set_category(&hash, category)).await);
    }

    async fn given_a_running_download_the_server_was_asked_for(&mut self, name: &str, hash: [u8; 16]) {
        self.given_a_download(name, hash).await;
        self.downloads().wait_for("the server asked", |lab| !lab.server_asked().is_empty()).await;
        assert_eq!(self.server_asked().len(), 1);
    }

    async fn given_two_paused_downloads_after_a_cancelled_one(
        &mut self,
        gone: [u8; 16],
        first: [u8; 16],
        second: [u8; 16],
    ) {
        self.run(move |lab| {
            lab.add(&FileLink::of("gone.bin", 5000, gone).unwrap());
            lab.add(&FileLink::of("first.bin", 5000, first).unwrap());
            let ours = lab.ours.clone();
            lab.manager.cancel(&gone, &ours);
            lab.add(&FileLink::of("second.bin", 5000, second).unwrap());
            pause(lab, &first);
            pause(lab, &second);
        })
        .await;
    }

    // The lab keeps every event the downloads tell.
    fn given_a_listener_of_added_downloads(&mut self) {}

    async fn given_the_download_renamed(&mut self, name: &'static str) {
        self.when_the_download_is_renamed(name).await;
        assert_eq!(self.renamed, Some(Ok(true)));
    }

    async fn given_the_file_written(&mut self, start: u64, end: u64) {
        let hash = self.hash;
        self.run(move |lab| lab.manager.find_mut(&hash).unwrap().file.written(start, end)).await;
    }

    async fn when_the_link_is_added_paused(&mut self, uri: &str) {
        let link = file_link(uri);
        self.hash = link.hash;
        self.info = Some(
            self.run(move |lab| {
                let added = lab.add(&link);
                pause(lab, &link.hash);
                added
            })
            .await,
        );
    }

    async fn when_a_download_is_added(&mut self, name: &str, hash: [u8; 16]) {
        self.hash = hash;
        self.info = Some(self.downloads().add(FileLink::of(name, 5000, hash).unwrap()).await);
    }

    async fn when_a_download_is_added_paused(&mut self, name: &str, hash: [u8; 16]) {
        self.given_a_paused_download(name, 5000, hash).await;
    }

    async fn when_the_download_is_stopped(&mut self) {
        let hash = self.hash;
        self.info = Some(
            self.run(move |lab| {
                stop(lab, &hash);
                lab.manager.download(&hash).unwrap()
            })
            .await,
        );
    }

    async fn when_the_download_is_resumed(&mut self) {
        let hash = self.hash;
        self.info = Some(
            self.run(move |lab| {
                let ours = lab.ours.clone();
                lab.manager.resume(&hash, &ours);
                let resumed = lab.manager.download(&hash).unwrap();
                // Paused again at once, so that it asks no source.
                pause(lab, &hash);
                resumed
            })
            .await,
        );
    }

    async fn when_a_download_we_do_not_have_is_stopped(&mut self, unknown: [u8; 16]) {
        self.answer = self.run(move |lab| stop(lab, &unknown)).await;
    }

    async fn when_the_next_download_is_resumed(&mut self, category: u32, mode: NextDownload) {
        self.next = self.resume_next(category, mode).await;
    }

    async fn when_the_download_is_moved_to_the_category(&mut self, category: u32) {
        let hash = self.hash;
        self.answer = self.run(move |lab| lab.manager.set_category(&hash, category)).await;
    }

    async fn when_the_download_is_renamed(&mut self, name: &'static str) {
        let hash = self.hash;
        self.renamed = Some(self.run(move |lab| lab.manager.rename(&hash, name)).await);
    }

    async fn when_a_download_we_do_not_have_is_renamed(&mut self, unknown: [u8; 16]) {
        self.renamed = Some(self.run(move |lab| lab.manager.rename(&unknown, "any.iso")).await);
    }

    // Saved as it closes, read back as it opens.
    async fn when_the_download_manager_is_closed_and_opened_again(&mut self) {
        self.given_the_download_manager_restarted().await;
    }

    async fn when_the_categories_change(&mut self) {
        let categories = self.categories.clone();
        self.run(move |lab| lab.manager.set_categories(categories)).await;
    }

    async fn when_a_low_and_a_high_priority_download_are_added_together(&mut self, low: [u8; 16], high: [u8; 16]) {
        self.run(move |lab| {
            lab.add(&FileLink::of("low.bin", 5000, low).unwrap());
            lab.manager.set_priority(&low, DownloadPriority::Low);
            lab.add(&FileLink::of("high.bin", 5000, high).unwrap());
            lab.manager.set_priority(&high, DownloadPriority::High);
        })
        .await;
    }

    async fn when_the_running_download_is_resumed(&mut self) {
        let hash = self.hash;
        self.answer = self
            .run(move |lab| {
                let ours = lab.ours.clone();
                lab.manager.resume(&hash, &ours)
            })
            .await;
    }

    async fn when_the_link_is_added_again(&mut self, size: u64, aich: Option<[u8; 20]>, source: &str) {
        let source = Source { host: source.to_owned(), port: PORT };
        let link = FileLink::new("same.bin".to_owned(), size, self.hash, aich, Vec::new(), vec![source], Vec::new());
        let link = link.unwrap();
        self.info = Some(self.downloads().add(link).await);
    }

    async fn when_a_file_to_preview_is_asked_for(&mut self) {
        let hash = self.hash;
        self.preview = self.run(move |lab| lab.manager.data_file(&hash)).await;
    }

    fn then_the_resulting_download_has_sources(&self, expected: usize) {
        assert_eq!(self.info().sources, expected);
    }

    fn then_the_resulting_download_is(&self, expected: DownloadStatus) {
        assert_eq!(self.info().status, expected);
    }

    fn then_the_resulting_download_has_the_priority(&self, expected: DownloadPriority) {
        assert_eq!(self.info().priority, expected);
    }

    fn then_the_resulting_download_is_in_the_category(&self, expected: u32) {
        assert_eq!(self.info().category, expected);
    }

    async fn then_the_download_is_now_in_the_category(&self, download: [u8; 16], expected: u32) {
        assert_eq!(self.run(move |lab| lab.manager.download(&download).unwrap().category).await, expected);
    }

    fn then_it_is_accepted(&self) {
        assert!(self.answer);
    }

    fn then_it_is_refused(&self, description: &str) {
        assert!(!self.answer, "{description}");
    }

    fn then_the_rename_is_accepted(&self) {
        assert_eq!(self.renamed, Some(Ok(true)));
    }

    fn then_the_rename_is_refused(&self) {
        assert_eq!(self.renamed, Some(Ok(false)));
    }

    fn then_the_rename_fails_for_the_name(&self) {
        assert!(matches!(self.renamed, Some(Err(_))));
    }

    async fn then_the_download_is_named(&self, download: [u8; 16], name: &str) {
        assert_eq!(self.run(move |lab| lab.manager.download(&download).unwrap().name).await, name);
    }

    fn then_nothing_is_resumed(&self, description: &str) {
        assert!(self.next.is_none(), "{description}");
    }

    fn then_the_resumed_download_is(&self, expected: [u8; 16], description: &str) {
        assert_eq!(self.next.as_ref().map(|next| next.hash), Some(expected), "{description}");
    }

    fn then_the_resumed_download_is_now(&self, expected: DownloadStatus) {
        assert_eq!(self.next.as_ref().map(|next| next.status), Some(expected));
    }

    async fn then_the_server_is_asked_first_for(&self, files: &[[u8; 16]]) {
        let wanted = files.len();
        self.downloads().wait_for("the server asked", move |lab| lab.server_asked().len() >= wanted).await;
        assert_eq!(&self.server_asked()[..wanted], files);
    }

    async fn then_the_server_is_not_asked_again_before_its_time(&self) {
        tokio::time::sleep(QUIET).await;
        assert_eq!(self.server_asked().len(), 1, "not asked again before its time");
    }

    async fn then_one_added_event_tells(&self, category: u32, priority: DownloadPriority) {
        let added: Vec<(u32, DownloadPriority)> = self
            .run(|lab| {
                lab.events
                    .iter()
                    .filter_map(|event| match event {
                        DownloadEvent::Added(info) => Some((info.category, info.priority)),
                        _ => None,
                    })
                    .collect()
            })
            .await;
        assert_eq!(added, [(category, priority)]);
    }

    async fn then_the_file_took_the_aich_hash(&self, expected: [u8; 20]) {
        let hash = self.hash;
        let aich = self.run(move |lab| lab.manager.find(&hash).unwrap().file.aich_hash()).await;
        assert_eq!(aich, Some(expected), "the link's AICH hash is taken");
    }

    fn then_there_is_no_file_to_preview(&self, description: &str) {
        assert!(self.preview.is_none(), "{description}");
    }

    fn then_there_is_a_file_to_preview(&self) {
        assert!(self.preview.is_some());
    }

    fn downloads(&self) -> &Downloads {
        self.downloads.as_ref().unwrap()
    }

    fn info(&self) -> &DownloadInfo {
        self.info.as_ref().unwrap()
    }

    fn server_asked(&self) -> Vec<[u8; 16]> {
        self.downloads().server.lock().unwrap().asked.clone()
    }

    async fn run<T: Send + 'static>(&self, work: impl FnOnce(&mut Lab) -> T + Send + 'static) -> T {
        self.downloads().run(work).await
    }

    async fn resume_next(&self, category: u32, mode: NextDownload) -> Option<DownloadInfo> {
        self.run(move |lab| {
            let ours = lab.ours.clone();
            lab.manager.resume_next(category, mode, &ours)
        })
        .await
    }
}

fn pause(lab: &mut Lab, hash: &[u8; 16]) -> bool {
    let ours = lab.ours.clone();
    lab.manager.pause(hash, &ours)
}

fn stop(lab: &mut Lab, hash: &[u8; 16]) -> bool {
    let ours = lab.ours.clone();
    lab.manager.stop(hash, &ours)
}

fn hash_of(octet: u8) -> [u8; 16] {
    [octet; 16]
}

fn aich(letter: char) -> [u8; 20] {
    let text: String = std::iter::repeat_n(letter, 32).collect();
    data_encoding::BASE32_NOPAD.decode(text.as_bytes()).unwrap().try_into().unwrap()
}

fn category(id: u32, name: &str, color: u32, priority: DownloadPriority, patterns: &str) -> Category {
    Category {
        id,
        name: name.to_owned(),
        incoming: None,
        comment: String::new(),
        color,
        priority,
        patterns: patterns.to_owned(),
        regex: false,
    }
}

fn films() -> Vec<Category> {
    vec![category(1, "Films", 1, DownloadPriority::Auto, "*.mkv")]
}

fn films_and_video() -> Vec<Category> {
    vec![
        category(1, "Films", 1, DownloadPriority::High, "*.mkv"),
        category(2, "Video", 0, DownloadPriority::Auto, "mkv|avi"),
    ]
}

fn file_link(uri: &str) -> FileLink {
    match Ed2kLink::parse(uri).unwrap() {
        Ed2kLink::File(link) => link,
        other => panic!("not a file link: {other:?}"),
    }
}

#[tokio::test]
async fn should_forget_the_sources_and_keep_the_priority_when_stopping() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;
    manager.given_the_link_added_with_the_priority(LINK_TO_STOP, DownloadPriority::High).await;

    manager.when_the_download_is_stopped().await;

    manager.then_the_resulting_download_is(DownloadStatus::Stopped);
    manager.then_the_resulting_download_has_sources(0);
    manager.then_the_resulting_download_has_the_priority(DownloadPriority::High);
}

#[tokio::test]
async fn should_resume_a_stopped_download() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;
    manager.given_the_link_added_with_the_priority(LINK_TO_STOP, DownloadPriority::High).await;
    manager.given_the_download_stopped().await;

    manager.when_the_download_is_resumed().await;

    manager.then_the_resulting_download_is(DownloadStatus::Downloading);
}

#[tokio::test]
async fn should_not_stop_a_download_we_do_not_have() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;

    manager.when_a_download_we_do_not_have_is_stopped(hash_of(0x00)).await;

    manager.then_it_is_refused("no such download");
}

#[tokio::test]
async fn should_resume_no_paused_download_when_asked_for_none() {
    let mut manager = Manager::new();
    manager.given_the_categories(films());
    manager.given_the_download_manager().await;
    manager
        .given_a_paused_film_a_stopped_urgent_file_and_a_paused_later_file(hash_of(0xa1), hash_of(0xa2), hash_of(0xa3))
        .await;

    manager.when_the_next_download_is_resumed(1, NextDownload::None).await;

    manager.then_nothing_is_resumed("none asked for");
}

#[tokio::test]
async fn should_resume_the_paused_download_of_the_category_first_though_of_low_priority() {
    let mut manager = Manager::new();
    manager.given_the_categories(films());
    manager.given_the_download_manager().await;
    manager
        .given_a_paused_film_a_stopped_urgent_file_and_a_paused_later_file(hash_of(0xa1), hash_of(0xa2), hash_of(0xa3))
        .await;

    manager.when_the_next_download_is_resumed(1, NextDownload::SameCategoryFirst).await;

    manager.then_the_resumed_download_is(hash_of(0xa1), "the category's, though of low priority");
}

#[tokio::test]
async fn should_resume_nothing_when_none_is_left_in_the_category() {
    let mut manager = Manager::new();
    manager.given_the_categories(films());
    manager.given_the_download_manager().await;
    manager
        .given_a_paused_film_a_stopped_urgent_file_and_a_paused_later_file(hash_of(0xa1), hash_of(0xa2), hash_of(0xa3))
        .await;
    manager.given_the_next_download_resumed(1, NextDownload::SameCategoryFirst).await;

    manager.when_the_next_download_is_resumed(1, NextDownload::SameCategory).await;

    manager.then_nothing_is_resumed("none left in the category");
}

#[tokio::test]
async fn should_resume_the_download_of_highest_priority_then_a_stopped_one_too() {
    let mut manager = Manager::new();
    manager.given_the_categories(films());
    manager.given_the_download_manager().await;
    manager
        .given_a_paused_film_a_stopped_urgent_file_and_a_paused_later_file(hash_of(0xa1), hash_of(0xa2), hash_of(0xa3))
        .await;
    manager.given_the_next_download_resumed(1, NextDownload::SameCategoryFirst).await;

    manager.when_the_next_download_is_resumed(1, NextDownload::SameCategoryFirst).await;

    manager.then_the_resumed_download_is(hash_of(0xa2), "then any, a stopped one too");
}

#[tokio::test]
async fn should_resume_the_last_waiting_download() {
    let mut manager = Manager::new();
    manager.given_the_categories(films());
    manager.given_the_download_manager().await;
    manager
        .given_a_paused_film_a_stopped_urgent_file_and_a_paused_later_file(hash_of(0xa1), hash_of(0xa2), hash_of(0xa3))
        .await;
    manager.given_the_next_download_resumed(1, NextDownload::SameCategoryFirst).await;
    manager.given_the_next_download_resumed(1, NextDownload::SameCategoryFirst).await;

    manager.when_the_next_download_is_resumed(category::NONE, NextDownload::Any).await;

    manager.then_the_resumed_download_is_now(DownloadStatus::Downloading);
}

#[tokio::test]
async fn should_resume_nothing_once_all_downloads_run() {
    let mut manager = Manager::new();
    manager.given_the_categories(films());
    manager.given_the_download_manager().await;
    manager
        .given_a_paused_film_a_stopped_urgent_file_and_a_paused_later_file(hash_of(0xa1), hash_of(0xa2), hash_of(0xa3))
        .await;
    manager.given_the_next_download_resumed(1, NextDownload::SameCategoryFirst).await;
    manager.given_the_next_download_resumed(1, NextDownload::SameCategoryFirst).await;
    manager.given_the_next_download_resumed(category::NONE, NextDownload::Any).await;

    manager.when_the_next_download_is_resumed(category::NONE, NextDownload::Any).await;

    manager.then_nothing_is_resumed("all run");
}

#[tokio::test]
async fn should_put_a_new_download_in_the_first_category_its_name_matches() {
    let mut manager = Manager::new();
    manager.given_the_categories(films_and_video());
    manager.given_the_download_manager().await;

    manager.when_a_download_is_added("Film.MKV", hash_of(0xbc)).await;

    manager.then_the_resulting_download_is_in_the_category(1);
    manager.then_the_resulting_download_has_the_priority(DownloadPriority::High);
}

#[tokio::test]
async fn should_put_a_new_download_that_matches_no_category_in_none() {
    let mut manager = Manager::new();
    manager.given_the_categories(films_and_video());
    manager.given_the_download_manager().await;

    manager.when_a_download_is_added("notes.txt", hash_of(0xcd)).await;

    manager.then_the_resulting_download_is_in_the_category(category::NONE);
}

#[tokio::test]
async fn should_move_a_download_to_another_category() {
    let mut manager = Manager::new();
    manager.given_the_categories(films_and_video());
    manager.given_the_download_manager().await;
    manager.given_a_download("notes.txt", hash_of(0xcd)).await;

    manager.when_the_download_is_moved_to_the_category(2).await;

    manager.then_it_is_accepted();
    manager.then_the_download_is_now_in_the_category(hash_of(0xcd), 2).await;
}

#[tokio::test]
async fn should_rename_a_download_with_a_name_every_file_system_takes() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;
    manager.given_a_download("draft.iso", hash_of(0xf1)).await;

    manager.when_the_download_is_renamed("a/b:c?.iso").await;

    manager.then_the_rename_is_accepted();
    manager.then_the_download_is_named(hash_of(0xf1), "a_b_c_.iso").await;
}

#[tokio::test]
async fn should_keep_the_name_of_a_renamed_download_after_a_restart() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;
    manager.given_a_download("draft.iso", hash_of(0xf2)).await;
    manager.given_the_download_renamed("Debian 13.iso").await;

    manager.when_the_download_manager_is_closed_and_opened_again().await;

    manager.then_the_download_is_named(hash_of(0xf2), "Debian 13.iso").await;
}

#[tokio::test]
async fn should_clean_up_the_name_of_a_new_download_when_asked() {
    let mut manager = Manager::new();
    manager.given_the_names_of_new_downloads_cleaned_up_of("sponsored");
    manager.given_the_download_manager().await;

    manager.when_a_download_is_added("Big_Buck_Bunny.sponsored.[www.site.com].mkv", hash_of(0xf5)).await;

    manager.then_the_download_is_named(hash_of(0xf5), "Big Buck Bunny.mkv").await;
}

#[tokio::test]
async fn should_keep_the_name_of_a_new_download_as_it_comes_by_default() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;

    manager.when_a_download_is_added("Big_Buck_Bunny.mkv", hash_of(0xf6)).await;

    manager.then_the_download_is_named(hash_of(0xf6), "Big_Buck_Bunny.mkv").await;
}

#[tokio::test]
async fn should_refuse_a_blank_name_for_a_download() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;
    manager.given_a_download("draft.iso", hash_of(0xf3)).await;

    manager.when_the_download_is_renamed("  ").await;

    manager.then_the_rename_fails_for_the_name();
    manager.then_the_download_is_named(hash_of(0xf3), "draft.iso").await;
}

#[tokio::test]
async fn should_rename_no_download_we_do_not_have() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;

    manager.when_a_download_we_do_not_have_is_renamed(hash_of(0xf4)).await;

    manager.then_the_rename_is_refused();
}

#[tokio::test]
async fn should_not_move_a_download_to_a_category_that_does_not_exist() {
    let mut manager = Manager::new();
    manager.given_the_categories(films_and_video());
    manager.given_the_download_manager().await;
    manager.given_a_download("notes.txt", hash_of(0xcd)).await;

    manager.when_the_download_is_moved_to_the_category(9).await;

    manager.then_it_is_refused("no such category");
}

#[tokio::test]
async fn should_take_a_download_out_of_a_category_that_is_gone() {
    let mut manager = Manager::new();
    manager.given_the_categories(films_and_video());
    manager.given_the_download_manager().await;
    manager.given_a_paused_download("Film.MKV", 5000, hash_of(0xbc)).await;
    manager.given_a_paused_download("notes.txt", 5000, hash_of(0xcd)).await;
    manager.given_the_download_moved_to_the_category(2).await;
    manager.given_only_the_first_category_left();

    manager.when_the_categories_change().await;

    manager.then_the_download_is_now_in_the_category(hash_of(0xcd), category::NONE).await;
    manager.then_the_download_is_now_in_the_category(hash_of(0xbc), 1).await;
}

#[tokio::test]
async fn should_ask_the_server_for_higher_priorities_first() {
    let mut manager = Manager::new();
    manager.given_the_download_manager_with_a_server_that_answers().await;

    manager.when_a_low_and_a_high_priority_download_are_added_together(hash_of(0x78), hash_of(0x9a)).await;

    manager.then_the_server_is_asked_first_for(&[hash_of(0x9a), hash_of(0x78)]).await;
}

#[tokio::test]
async fn should_change_nothing_when_resuming_a_running_download() {
    let mut manager = Manager::new();
    manager.given_the_download_manager_with_a_server_that_answers().await;
    manager.given_a_running_download_the_server_was_asked_for("running.bin", hash_of(0xbd)).await;

    manager.when_the_running_download_is_resumed().await;

    manager.then_it_is_refused("it runs already");
    manager.then_the_server_is_not_asked_again_before_its_time().await;
}

#[tokio::test]
async fn should_keep_the_first_added_first_after_a_restart() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;
    manager.given_two_paused_downloads_after_a_cancelled_one(hash_of(0xe1), hash_of(0xe2), hash_of(0xe3)).await;
    manager.given_the_download_manager_restarted().await;

    manager.when_the_next_download_is_resumed(category::NONE, NextDownload::Any).await;

    manager.then_the_resumed_download_is(hash_of(0xe2), "the first added");
}

#[tokio::test]
async fn should_refuse_link_sources_no_client_can_have() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;

    manager.when_the_link_is_added_paused(LINK_WITH_ODD_SOURCES).await;

    manager.then_the_resulting_download_has_sources(0);
}

#[tokio::test]
async fn should_tell_the_category_and_priority_in_the_added_event() {
    let mut manager = Manager::new();
    manager.given_the_categories(vec![category(1, "Films", 1, DownloadPriority::High, "*.mkv")]);
    manager.given_a_listener_of_added_downloads();
    manager.given_the_download_manager().await;

    manager.when_a_download_is_added_paused("film.mkv", hash_of(0x4b)).await;

    manager.then_one_added_event_tells(1, DownloadPriority::High).await;
}

#[tokio::test]
async fn should_give_no_sources_when_a_link_of_another_size_is_added_again() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;
    manager.given_a_paused_download("same.bin", 5000, hash_of(0x4c)).await;

    manager.when_the_link_is_added_again(6000, None, "192.0.2.1").await;

    manager.then_the_resulting_download_has_sources(0);
}

#[tokio::test]
async fn should_give_sources_and_the_aich_hash_when_a_link_of_the_same_file_is_added_again() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;
    manager.given_a_paused_download("same.bin", 5000, hash_of(0x4c)).await;

    manager.when_the_link_is_added_again(5000, Some(aich('A')), "192.0.2.2").await;

    manager.then_the_resulting_download_has_sources(1);
    manager.then_the_file_took_the_aich_hash(aich('A')).await;
}

#[tokio::test]
async fn should_give_no_sources_when_a_link_with_another_aich_hash_is_added_again() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;
    manager.given_a_paused_download("same.bin", 5000, hash_of(0x4c)).await;
    manager.given_the_link_added_again(5000, Some(aich('A')), "192.0.2.2").await;

    manager.when_the_link_is_added_again(5000, Some(aich('B')), "192.0.2.3").await;

    manager.then_the_resulting_download_has_sources(1);
}

#[tokio::test]
async fn should_not_preview_without_the_start_of_the_file() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;
    manager.given_a_paused_download("film.avi", 100_000, hash_of(0x4d)).await;
    manager.given_the_file_written(16 * 1024, 100_000).await;

    manager.when_a_file_to_preview_is_asked_for().await;

    manager.then_there_is_no_file_to_preview("all but the start");
}

#[tokio::test]
async fn should_preview_with_the_start_of_the_file() {
    let mut manager = Manager::new();
    manager.given_the_download_manager().await;
    manager.given_a_paused_download("film.avi", 100_000, hash_of(0x4d)).await;
    manager.given_the_file_written(16 * 1024, 100_000).await;
    manager.given_the_file_written(0, 16 * 1024).await;

    manager.when_a_file_to_preview_is_asked_for().await;

    manager.then_there_is_a_file_to_preview();
}
