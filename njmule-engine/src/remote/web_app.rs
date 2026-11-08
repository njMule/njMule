// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The interface built by njmule-ui, which the web interface serves: its page, whose session token is filled in for
//! each session, and its files. A release build carries the interface Gradle ships (`njmule-ui/build/shipped`); a
//! build without it serves an empty page.

use std::borrow::Cow;
use std::io::{self, Read};
use std::path::PathBuf;

use flate2::read::GzDecoder;
use rust_embed::RustEmbed;

use crate::remote::html;

const TOKEN: &str = "__NJMULE_CSRF__";
const ROLE: &str = "__NJMULE_ROLE__";
// The build ships the text files compressed only.
const GZIP: &str = ".gz";

// Only the names Vite and the translations give files: nothing else of the interface can be asked for.
const ASSETS: &str = "/assets/";
const EXTENSIONS: [&str; 5] = ["js", "css", "svg", "woff2", "ttf"];

#[derive(RustEmbed)]
#[folder = "../njmule-ui/build/shipped"]
#[allow_missing = true]
struct Shipped;

enum Source {
    Shipped,
    Folder(PathBuf),
}

/// A file of the interface; the bundled files have their content's hash in their names and never change.
pub struct AppFile {
    bytes: Cow<'static, [u8]>,
    content_type: &'static str,
    immutable: bool,
    gzipped: bool,
}

impl AppFile {
    /// The content as it is kept: compressed with gzip when [`AppFile::gzipped`].
    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub fn gzipped(&self) -> bool {
        self.gzipped
    }

    /// The content itself, for a client that does not take gzip.
    pub fn unpacked(&self) -> io::Result<Vec<u8>> {
        if !self.gzipped {
            return Ok(self.bytes.to_vec());
        }
        let mut unpacked = Vec::new();
        GzDecoder::new(self.bytes.as_ref()).read_to_end(&mut unpacked)?;
        Ok(unpacked)
    }

    pub fn content_type(&self) -> &'static str {
        self.content_type
    }

    pub fn immutable(&self) -> bool {
        self.immutable
    }
}

pub struct WebApp {
    source: Source,
    page: String,
}

impl WebApp {
    /// The interface this build carries.
    pub fn shipped() -> Self {
        Self::of(Source::Shipped)
    }

    /// The interface in `folder`, laid out as Gradle ships it.
    pub fn from_folder(folder: PathBuf) -> Self {
        Self::of(Source::Folder(folder))
    }

    fn of(source: Source) -> Self {
        let page = read(&source, "index.html").map(|bytes| String::from_utf8_lossy(&bytes).into_owned());
        Self { page: page.unwrap_or_default(), source }
    }

    /// The page for a session with `csrf`, a token of URL-safe characters, and for a guest or not.
    pub fn page(&self, csrf: &str, guest: bool) -> String {
        self.page.replace(TOKEN, &html::text(csrf)).replace(ROLE, if guest { "guest" } else { "admin" })
    }

    pub fn file(&self, path: &str) -> Option<AppFile> {
        if !servable(path) {
            return None;
        }
        let name = path.strip_prefix('/')?;
        let content_type = content_type(path);
        let immutable = path.starts_with(ASSETS);
        if let Some(bytes) = read(&self.source, &format!("{name}{GZIP}")) {
            return Some(AppFile { bytes, content_type, immutable, gzipped: true });
        }
        read(&self.source, name).map(|bytes| AppFile { bytes, content_type, immutable, gzipped: false })
    }
}

/// Whether `path` names a file of the interface: a bundled asset, a translation, a flag or the third-party notices.
pub fn servable(path: &str) -> bool {
    if let Some(name) = path.strip_prefix(ASSETS) {
        let named = name.bytes().all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b));
        return named
            && !name.contains("..")
            && name
                .rsplit_once('.')
                .is_some_and(|(stem, extension)| !stem.is_empty() && EXTENSIONS.contains(&extension));
    }
    let translation = path
        .strip_prefix("/i18n/")
        .and_then(|name| name.strip_suffix(".json"))
        .is_some_and(|tag| (2..=10).contains(&tag.len()) && tag.bytes().all(|b| b.is_ascii_alphabetic() || b == b'-'));
    // The flags of the countries, by their codes.
    let flag = path
        .strip_prefix("/flags/")
        .and_then(|name| name.strip_suffix(".png"))
        .is_some_and(|code| code.len() == 2 && code.bytes().all(|b| b.is_ascii_lowercase()));
    path == "/THIRD-PARTY.md" || translation || flag
}

fn content_type(path: &str) -> &'static str {
    match path.rsplit_once('.').map_or("", |(_, extension)| extension) {
        "js" => "text/javascript; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "svg" => "image/svg+xml",
        "woff2" => "font/woff2",
        "ttf" => "font/ttf",
        "png" => "image/png",
        "json" => "application/json; charset=utf-8",
        _ => "text/markdown; charset=utf-8",
    }
}

fn read(source: &Source, name: &str) -> Option<Cow<'static, [u8]>> {
    match source {
        Source::Shipped => Shipped::get(name).map(|file| file.data),
        Source::Folder(folder) => std::fs::read(folder.join(name)).ok().map(Cow::Owned),
    }
}
