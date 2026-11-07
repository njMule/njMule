// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ServerSourceRequestsTest, under its names: twenty downloads asking a server for their sources, on a
//! clock the test moves.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::arithmetic_side_effects)]

mod download_lab;

use std::time::{Duration, Instant};

use download_lab::Downloads;
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::download::download_manager::SERVER_REQUEST_CREDIT;

const OUR_ID: u32 = u32::from_le_bytes([198, 51, 100, 7]);
// A few ticks of the downloads.
const SETTLE: Duration = Duration::from_millis(500);

struct Requests {
    downloads: Downloads,
}

impl Requests {
    fn given_a_server_that_takes_source_requests() -> Self {
        let downloads = Downloads::start([0xab; 16], OUR_ID, Downloads::settings());
        downloads.server.lock().unwrap().connected = true;
        Self { downloads }
    }

    async fn given_downloads(&self, count: u64) {
        self.downloads
            .run(move |lab| {
                for i in 0..count {
                    let half = (i + 1).to_be_bytes();
                    let mut hash = [0u8; 16];
                    hash[..8].copy_from_slice(&half);
                    hash[8..].copy_from_slice(&half);
                    lab.add(&FileLink::of(&format!("file{i}.bin"), 5000, hash).unwrap());
                }
            })
            .await;
    }

    async fn given_the_downloads_have_asked_for(&self, files: usize) {
        self.await_requests_for(files).await;
    }

    fn given_time_passed(&self, time: Duration) {
        self.downloads.advance(time);
    }

    async fn given_no_more_is_asked_for_a_while(&self) {
        self.check_no_more_is_asked_for_a_while().await;
    }

    async fn when_the_downloads_have_asked_for(&self, files: usize) {
        self.await_requests_for(files).await;
    }

    fn when_time_passes(&self, time: Duration) {
        self.downloads.advance(time);
    }

    async fn then_no_more_is_asked_for_a_while(&self) {
        self.check_no_more_is_asked_for_a_while().await;
    }

    async fn then_the_downloads_have_asked_for(&self, files: usize) {
        self.await_requests_for(files).await;
        let mut asked = self.asked();
        asked.sort_unstable();
        asked.dedup();
        assert_eq!(asked.len(), files, "no duplicates");
    }

    async fn check_no_more_is_asked_for_a_while(&self) {
        let before = self.asked().len();
        tokio::time::sleep(SETTLE).await;
        assert_eq!(self.asked().len(), before);
    }

    async fn await_requests_for(&self, files: usize) {
        let deadline = Instant::now() + Duration::from_secs(10);
        while self.asked().len() < files && Instant::now() < deadline {
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        assert_eq!(self.asked().len(), files);
    }

    fn asked(&self) -> Vec<[u8; 16]> {
        self.downloads.server.lock().unwrap().asked.clone()
    }
}

#[tokio::test]
async fn should_ask_the_server_for_fifteen_files_at_once_then_no_more() {
    let requests = Requests::given_a_server_that_takes_source_requests();
    requests.given_downloads(20).await;

    requests.when_the_downloads_have_asked_for(15).await;

    requests.then_no_more_is_asked_for_a_while().await;
}

#[tokio::test]
async fn should_ask_for_one_more_file_every_twenty_seconds() {
    let requests = Requests::given_a_server_that_takes_source_requests();
    requests.given_downloads(20).await;
    requests.given_the_downloads_have_asked_for(15).await;
    requests.given_time_passed(SERVER_REQUEST_CREDIT - Duration::from_secs(1));
    requests.given_no_more_is_asked_for_a_while().await;

    requests.when_time_passes(Duration::from_secs(1));

    requests.then_the_downloads_have_asked_for(16).await;
    requests.then_no_more_is_asked_for_a_while().await;
}
