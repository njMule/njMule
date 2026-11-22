// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Twin of Java's `PeerTransferTest`: two Rust engines exchanging files over IPv4 and IPv6 with SecureIdent credits,
//! shared-file browsing visibility, and file comments.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::panic
)]

use std::fs;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use njmule_engine::api::download_info::DownloadInfo;
use njmule_engine::api::ed2k_link::Ed2kLink;
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::file_comment::FileComment;
use njmule_engine::api::peer_comment::PeerComment;
use njmule_engine::api::search_result::SearchResult;
use njmule_engine::api::shared_file_info::SharedFileInfo;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::config::shared_files_visibility::SharedFilesVisibility;
use njmule_engine::engine::Engine;
use njmule_engine::persist::format::clients_met::{self, Credit};
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};
use tempfile::TempDir;

use crate::free_port::free_port;

const WAIT: Duration = Duration::from_secs(60);

struct Lab {
    dir: TempDir,
    uploader_settings: String,
    uploader_root: Option<PathBuf>,
    uploader: Option<Engine>,
    shared: Option<SharedFileInfo>,
    data: Vec<u8>,
    comment: Option<FileComment>,
    downloader_root: Option<PathBuf>,
    downloader: Option<Engine>,
    browsed: Vec<SearchResult>,
    heard: Vec<PeerComment>,
}

impl Lab {
    fn new() -> Self {
        Self {
            dir: TempDir::new().unwrap(),
            uploader_settings: String::new(),
            uploader_root: None,
            uploader: None,
            shared: None,
            data: Vec::new(),
            comment: None,
            downloader_root: None,
            downloader: None,
            browsed: Vec::new(),
            heard: Vec::new(),
        }
    }

    fn has_ipv6_loopback() -> bool {
        TcpListener::bind(SocketAddr::from((Ipv6Addr::LOCALHOST, 0))).is_ok()
    }

    fn given_the_uploader_sends_at_most_kibps(&mut self, kibps: u32) {
        self.uploader_settings = format!("limits.uploadKiBps={kibps}\n");
    }

    async fn given_an_uploader_sharing_random_bytes(&mut self, name: &str, size: usize) {
        let mut bytes = vec![0u8; size];
        let mut rng = StdRng::seed_from_u64(9);
        rng.fill(&mut bytes[..]);
        if size > 4_000_000 {
            bytes[1_000_000..4_000_000].fill(7);
        }
        self.given_an_uploader_sharing(name, bytes).await;
    }

    async fn given_an_uploader_sharing_zeros(&mut self, name: &str, size: usize) {
        self.given_an_uploader_sharing(name, vec![0u8; size]).await;
    }

    async fn given_an_uploader_sharing(&mut self, name: &str, bytes: Vec<u8>) {
        self.data = bytes;
        let root = self.dir.path().join("uploader");
        let incoming = root.join("incoming");
        fs::create_dir_all(&incoming).unwrap();
        fs::write(incoming.join(name), &self.data).unwrap();
        let engine = start_engine(&root, &self.uploader_settings).await;
        let shared = await_shared(&engine).await;
        assert_eq!(shared.size, u64::try_from(self.data.len()).unwrap());
        self.uploader_root = Some(root);
        self.uploader = Some(engine);
        self.shared = Some(shared);
    }

    async fn given_the_uploader_comments_the_file(&mut self, text: &str, rating: u8) {
        let comment = FileComment::of(text, rating);
        let hash = self.shared.as_ref().unwrap().hash;
        self.uploader.as_ref().unwrap().set_file_comment(hash, comment.clone()).await.unwrap();
        assert_eq!(self.uploader.as_ref().unwrap().file_comment(hash).await, Some(comment.clone()));
        self.comment = Some(comment);
    }

    fn given_the_uploader_shows_its_files_to(&self, visibility: SharedFilesVisibility) {
        self.uploader
            .as_ref()
            .unwrap()
            .change_preferences(&|p: &Preferences| {
                let mut next = p.clone();
                next.shared_files_visibility = visibility;
                Ok(next)
            })
            .unwrap();
    }

    async fn given_a_downloader(&mut self) {
        let root = self.dir.path().join("downloader");
        let engine = start_engine(&root, "").await;
        self.downloader_root = Some(root);
        self.downloader = Some(engine);
    }

    async fn given_the_downloader_adds_the_file_with_the_uploader_as_source(&self) {
        self.add_the_file_with_the_uploader_as_source("127.0.0.1").await;
    }

    async fn given_the_downloader_downloaded_the_whole_file_from_the_uploader(&self) {
        self.given_the_downloader_downloaded_the_whole_file_from_the_uploader_at("127.0.0.1").await;
    }

    async fn given_the_downloader_downloaded_the_whole_file_from_the_uploader_at(&self, host: &str) {
        let downloader = self.downloader.as_ref().unwrap();
        let mut events = downloader.subscribe();
        let added = self.add_the_file_with_the_uploader_as_source(host).await;
        let deadline = Instant::now() + WAIT;
        let file = loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            assert!(!remaining.is_zero(), "download did not complete within {WAIT:?}");
            let event = tokio::time::timeout(remaining, events.live.recv()).await.unwrap().unwrap();
            match event {
                EngineEvent::DownloadCompleted { download, file } if download.hash == added.hash => break file,
                EngineEvent::DownloadFailed { reason, .. } => panic!("download failed: {reason}"),
                _ => {}
            }
        };
        assert_eq!(fs::read(&file).unwrap(), self.data);
    }

    async fn given_the_downloader_sees_none_of_the_uploaders_files(&self) {
        assert!(self.browse_the_uploader().await.is_empty(), "nobody sees them by default");
    }

    async fn when_both_close(&mut self) {
        if let Some(uploader) = self.uploader.take() {
            uploader.stop().await;
        }
        if let Some(downloader) = self.downloader.take() {
            downloader.stop().await;
        }
    }

    async fn when_the_downloader_browses_the_uploader(&mut self) {
        self.browsed = self.browse_the_uploader().await;
    }

    async fn when_the_downloader_waits_to_hear_what_peers_say_of_the_file(&mut self) {
        let deadline = Instant::now() + WAIT;
        let hash = self.shared.as_ref().unwrap().hash;
        let downloader = self.downloader.as_ref().unwrap();
        while Instant::now() < deadline {
            let heard = downloader.peer_comments(hash).await;
            if !heard.is_empty() {
                self.heard = heard;
                return;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    }

    fn then_the_uploader_credits_the_downloader_with_more_uploaded_than(&self, bytes: u64) {
        let credit = only_credit(self.uploader_root.as_ref().unwrap());
        assert!(credit.uploaded > bytes, "expected uploaded > {bytes}, got {}", credit.uploaded);
        assert!(!credit.public_key.is_empty(), "expected proven public key");
    }

    fn then_the_downloader_credits_the_uploader_with_more_downloaded_than(&self, bytes: u64) {
        let credit = only_credit(self.downloader_root.as_ref().unwrap());
        assert!(credit.downloaded > bytes, "expected downloaded > {bytes}, got {}", credit.downloaded);
        assert!(!credit.public_key.is_empty(), "expected proven public key");
    }

    fn then_the_downloader_sees_only(&self, name: &str, size: u64) {
        assert_eq!(self.browsed.len(), 1);
        let found = &self.browsed[0];
        assert_eq!(found.hash, self.shared.as_ref().unwrap().hash);
        assert_eq!(found.name, name);
        assert_eq!(found.size, size);
    }

    fn then_the_downloader_heard_only_the_uploaders_comment(&self) {
        assert_eq!(self.heard.len(), 1);
        assert_eq!(self.heard[0].comment, *self.comment.as_ref().unwrap());
    }

    async fn add_the_file_with_the_uploader_as_source(&self, host: &str) -> DownloadInfo {
        let port = self.uploader.as_ref().unwrap().preferences().tcp_port;
        let base = self.shared.as_ref().unwrap().link().to_uri();
        let uri = format!("{base}|sources,{host}:{port}|/");
        let Ed2kLink::File(link) = Ed2kLink::parse(&uri).unwrap() else {
            panic!("not a file link: {uri}");
        };
        self.downloader.as_ref().unwrap().add_download(link, None).await.unwrap()
    }

    async fn browse_the_uploader(&self) -> Vec<SearchResult> {
        let port = self.uploader.as_ref().unwrap().preferences().tcp_port;
        let address = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port);
        tokio::time::timeout(Duration::from_secs(30), self.downloader.as_ref().unwrap().browse(address))
            .await
            .unwrap()
            .unwrap()
    }

    async fn stop(mut self) {
        self.when_both_close().await;
    }
}

async fn start_engine(root: &Path, extra: &str) -> Engine {
    let dirs = AppDirectories::under_root(root).unwrap();
    fs::create_dir_all(&dirs.config).unwrap();
    let port = free_port();
    let props = format!("net.tcpPort={port}\nsecurity.filterLan=false\n{extra}");
    fs::write(dirs.config.join("njmule.properties"), props).unwrap();
    Engine::start(dirs).await.unwrap()
}

async fn await_shared(engine: &Engine) -> SharedFileInfo {
    let deadline = Instant::now() + WAIT;
    while Instant::now() < deadline {
        let files = engine.shared_files().await;
        if let Some(first) = files.into_iter().next() {
            return first;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    panic!("nothing shared within {WAIT:?}");
}

fn only_credit(root: &Path) -> Credit {
    let raw = fs::read(root.join("config").join("clients.met")).unwrap();
    let credits = clients_met::read(&raw).unwrap();
    assert_eq!(credits.len(), 1, "expected 1 credit entry in clients.met, got {}", credits.len());
    credits.into_iter().next().unwrap()
}

#[tokio::test]
async fn should_download_what_another_nj_mule_shares_and_count_it_for_credits() {
    let mut t = Lab::new();
    t.given_an_uploader_sharing_random_bytes("shared.bin", 12_000_000).await;
    t.given_a_downloader().await;
    t.given_the_downloader_downloaded_the_whole_file_from_the_uploader().await;

    t.when_both_close().await;

    t.then_the_uploader_credits_the_downloader_with_more_uploaded_than(5_000_000);
    t.then_the_downloader_credits_the_uploader_with_more_downloaded_than(5_000_000);
}

#[tokio::test]
async fn should_download_over_ipv6_with_both_identities_proven() {
    if !Lab::has_ipv6_loopback() {
        return;
    }
    let mut t = Lab::new();
    t.given_an_uploader_sharing_random_bytes("six.bin", 6_000_000).await;
    t.given_a_downloader().await;
    t.given_the_downloader_downloaded_the_whole_file_from_the_uploader_at("[::1]").await;

    t.when_both_close().await;

    t.then_the_uploader_credits_the_downloader_with_more_uploaded_than(1_000_000);
    t.then_the_downloader_credits_the_uploader_with_more_downloaded_than(1_000_000);
}

#[tokio::test]
async fn should_let_another_nj_mule_see_our_files_only_when_the_preferences_say_so() {
    let mut t = Lab::new();
    t.given_an_uploader_sharing_zeros("film.mkv", 300_000).await;
    t.given_a_downloader().await;
    t.given_the_downloader_sees_none_of_the_uploaders_files().await;
    t.given_the_uploader_shows_its_files_to(SharedFilesVisibility::Everybody);

    t.when_the_downloader_browses_the_uploader().await;

    t.then_the_downloader_sees_only("film.mkv", 300_000);
    t.stop().await;
}

#[tokio::test]
async fn should_let_a_downloader_hear_what_the_uploader_says_of_the_file() {
    let mut t = Lab::new();
    t.given_the_uploader_sends_at_most_kibps(1);
    t.given_an_uploader_sharing_zeros("song.mp3", 3_000_000).await;
    t.given_the_uploader_comments_the_file("A fine song", 4).await;
    t.given_a_downloader().await;
    t.given_the_downloader_adds_the_file_with_the_uploader_as_source().await;

    t.when_the_downloader_waits_to_hear_what_peers_say_of_the_file().await;

    t.then_the_downloader_heard_only_the_uploaders_comment();
    t.stop().await;
}
