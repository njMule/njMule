// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The lists of servers and Kad contacts the engine reads: from the web, from a file of its computer or as sent; and
//! the addresses of the server lists, kept in addresses.dat for the updates at the start.

use std::io;
use std::path::Path;

use reqwest::Url;

use crate::api::list_source::{ListError, ListSource};
use crate::net::routes::Routes;
use crate::net::web_lists::{self, WebLists};
use crate::persist::atomic_files;
use crate::persist::format::text_files;

/// The file the addresses of the server lists are kept in.
pub const ADDRESSES_FILE: &str = "addresses.dat";

/// The list from where it comes, read by `parse`; content sent that is no list is the caller's error.
pub async fn read<T>(
    source: ListSource,
    routes: Routes,
    parse: impl Fn(&[u8]) -> Result<T, String>,
) -> Result<T, ListError> {
    let data = match source {
        ListSource::Data(data) => return parse(&data).map_err(ListError::NotAList),
        ListSource::File(path) => tokio::task::spawn_blocking(move || read_file(&path))
            .await
            .map_err(|e| ListError::Failed(e.to_string()))?
            .map_err(|e| ListError::Failed(e.to_string()))?,
        ListSource::Web(url) => {
            WebLists::create(routes).fetch(&url).await.map_err(|e| ListError::Failed(e.to_string()))?
        }
    };
    parse(&data).map_err(ListError::Failed)
}

/// The web addresses among the entries of `data`, as addresses.dat holds them; the others are left out.
pub fn addresses(data: &[u8]) -> Vec<String> {
    text_files::entries(data)
        .into_iter()
        .map(|entry| entry.trim().to_owned())
        .filter(|entry| Url::parse(entry).is_ok_and(|url| matches!(url.scheme(), "http" | "https")))
        .collect()
}

/// The addresses of the server lists kept under `config`; none without the file.
pub fn load_addresses(config: &Path) -> io::Result<Vec<String>> {
    match std::fs::read(config.join(ADDRESSES_FILE)) {
        Ok(data) => Ok(addresses(&data)),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(e) => Err(e),
    }
}

pub fn save_addresses(config: &Path, addresses: &[String]) -> io::Result<()> {
    atomic_files::write(&config.join(ADDRESSES_FILE), &text_files::write_lines(addresses))
}

/// The host of a list's address, as the log names it.
pub fn host(url: &str) -> String {
    Url::parse(url).ok().and_then(|url| url.host_str().map(str::to_owned)).unwrap_or_else(|| url.to_owned())
}

// A file the user chose, no larger than a list from the web may be.
fn read_file(path: &Path) -> io::Result<Vec<u8>> {
    if std::fs::metadata(path)?.len() > web_lists::MAX_SIZE {
        let name = path.file_name().map_or_else(|| path.display().to_string(), |name| name.to_string_lossy().into());
        return Err(io::Error::other(format!("{name} is larger than a list may be")));
    }
    std::fs::read(path)
}
