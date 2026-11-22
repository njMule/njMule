// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The web as a test sets it: bodies by address, HTTP 404 for any other, and what was asked in order.

#![allow(dead_code, clippy::unwrap_used, clippy::expect_used)]

use std::collections::HashMap;
use std::io;
use std::sync::{Arc, Mutex};

use njmule_engine::update::git_hub_releases::{Fetcher, GitHubReleases, SIGNATURE_SUFFIX};
use njmule_engine::update::update_manifest::FILE_NAME;

use crate::test_signing::TestSigning;

pub const REPOSITORY: &str = "https://github.com/njMule/njMule";
pub const API: &str = "https://api.github.com/repos/njMule/njMule";

pub fn latest() -> String {
    format!("{REPOSITORY}/releases/latest/download/")
}

pub fn listing() -> String {
    format!("{API}/releases?per_page=10")
}

#[derive(Clone, Default)]
pub struct FakeWeb {
    bodies: Arc<Mutex<HashMap<String, Vec<u8>>>>,
    asked: Arc<Mutex<Vec<String>>>,
}

impl FakeWeb {
    pub fn serve(&self, address: &str, body: &[u8]) {
        self.bodies.lock().unwrap().insert(address.to_owned(), body.to_vec());
    }

    /// Serves the manifest `json` and its signature by `signer` under `base`.
    pub fn serve_release(&self, base: &str, json: &str, signer: &TestSigning) {
        self.serve(&format!("{base}{FILE_NAME}"), json.as_bytes());
        self.serve(&format!("{base}{FILE_NAME}{SIGNATURE_SUFFIX}"), signer.sign(json.as_bytes()).as_bytes());
    }

    pub fn asked(&self) -> Vec<String> {
        self.asked.lock().unwrap().clone()
    }

    pub fn releases(&self) -> GitHubReleases<FakeWeb> {
        GitHubReleases::new(self.clone(), REPOSITORY, API)
    }
}

impl Fetcher for FakeWeb {
    async fn fetch(&self, url: &str) -> io::Result<Vec<u8>> {
        self.asked.lock().unwrap().push(url.to_owned());
        let body = self.bodies.lock().unwrap().get(url).cloned();
        body.ok_or_else(|| io::Error::other(format!("HTTP 404 from {url}")))
    }
}
