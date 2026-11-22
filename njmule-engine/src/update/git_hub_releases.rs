// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The releases of njMule on GitHub. The newest release is a web address that redirects to its files, which GitHub
//! serves without the limits of its API; test versions are found through the API, at most once a day per client, and the
//! newest release stands in when the API does not answer.

use std::future::Future;
use std::io;

use semver::Version;
use serde_json::Value;

use super::update_manifest::FILE_NAME;
use crate::config::update_channel::UpdateChannel;

pub const SIGNATURE_SUFFIX: &str = ".minisig";

// The newest releases are enough to find the newest test version.
const LISTED: u32 = 10;
const MAX_LISTING: usize = 8 * 1024 * 1024;

/// Fetches the body at an address.
pub trait Fetcher: Send + Sync {
    fn fetch(&self, url: &str) -> impl Future<Output = io::Result<Vec<u8>>> + Send;
}

/// A manifest as fetched and its signature file, both unchecked.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Signed {
    pub manifest: Vec<u8>,
    pub signature: String,
}

pub struct GitHubReleases<F> {
    fetcher: F,
    repository: String,
    api: String,
}

impl<F: Fetcher> GitHubReleases<F> {
    /// The releases of the repository at `repository` (https://github.com/owner/name), listed by `api`
    /// (https://api.github.com/repos/owner/name).
    pub fn new(fetcher: F, repository: &str, api: &str) -> Self {
        Self {
            fetcher,
            repository: repository.trim_end_matches('/').to_owned(),
            api: api.trim_end_matches('/').to_owned(),
        }
    }

    /// The manifest of the newest release of `channel`.
    pub async fn latest(&self, channel: UpdateChannel) -> io::Result<Signed> {
        if channel == UpdateChannel::Prerelease {
            match self.newest_tag().await {
                Ok(Some(tag)) => return self.fetch(&format!("{}/releases/download/{tag}/", self.repository)).await,
                Ok(None) => {}
                Err(e) => tracing::debug!("Cannot list the releases, taking the newest one: {e}"),
            }
        }
        self.fetch(&format!("{}/releases/latest/download/", self.repository)).await
    }

    /// Where a file of the release of `version` is downloaded from.
    pub fn download(&self, version: &Version, name: &str) -> String {
        format!("{}/releases/download/v{version}/{name}", self.repository)
    }

    /// The page of the release of `version`.
    pub fn page(&self, version: &Version) -> String {
        format!("{}/releases/tag/v{version}", self.repository)
    }

    async fn fetch(&self, base: &str) -> io::Result<Signed> {
        let manifest = self.fetcher.fetch(&format!("{base}{FILE_NAME}")).await?;
        let signature = self.fetcher.fetch(&format!("{base}{FILE_NAME}{SIGNATURE_SUFFIX}")).await?;
        Ok(Signed { manifest, signature: String::from_utf8_lossy(&signature).into_owned() })
    }

    // The tag of the newest release, test versions included, that has a signed manifest.
    async fn newest_tag(&self) -> io::Result<Option<String>> {
        let listing = self.fetcher.fetch(&format!("{}/releases?per_page={LISTED}", self.api)).await?;
        if listing.len() > MAX_LISTING {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "the list of releases is too large"));
        }
        let releases: Value = serde_json::from_slice(&listing)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("malformed list of releases: {e}")))?;
        let Value::Array(releases) = releases else {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "malformed list of releases"));
        };
        Ok(releases
            .iter()
            .filter(|release| !release.get("draft").and_then(Value::as_bool).unwrap_or(false))
            .filter(|release| is_signed(release))
            .filter_map(|release| release.get("tag_name").and_then(Value::as_str))
            .filter_map(|tag| version(tag).map(|version| (version, tag)))
            .max_by(|(one, _), (other, _)| one.cmp(other))
            .map(|(_, tag)| tag.to_owned()))
    }
}

fn is_signed(release: &Value) -> bool {
    let names: Vec<&str> = release
        .get("assets")
        .and_then(Value::as_array)
        .map(|assets| assets.iter().filter_map(|asset| asset.get("name").and_then(Value::as_str)).collect())
        .unwrap_or_default();
    let signature = format!("{FILE_NAME}{SIGNATURE_SUFFIX}");
    names.contains(&FILE_NAME) && names.contains(&signature.as_str())
}

// Tags are "v" and the version.
fn version(tag: &str) -> Option<Version> {
    tag.strip_prefix('v').and_then(|version| Version::parse(version).ok())
}
