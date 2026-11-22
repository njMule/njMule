// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The description of a release that njMule offers as an update, [`FILE_NAME`], signed beside it: its version, its
//! notes and the packages it has. Packages of a kind this version does not know are left out, so that later releases may
//! add some.

use std::io;

use jiff::Timestamp;
use semver::Version;
use serde_json::{Map, Value};

pub const FILE_NAME: &str = "njmule-update.json";
pub const SCHEMA: u64 = 1;
pub const MAX_SIZE: usize = 256 * 1024;
pub const MAX_NOTES: usize = 64 * 1024;

// Limits of the document, beyond the size: what a manifest never needs.
const MAX_DEPTH: usize = 4;
const MAX_NAME: usize = 32;

/// A release: its version, when it was published, whether it fixes a security problem, its notes in Markdown and its
/// packages.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateManifest {
    pub version: Version,
    pub published: Timestamp,
    pub critical: bool,
    pub notes: String,
    pub assets: Vec<Asset>,
}

/// A package of the release: the system and processor it runs on ("windows-x64"), its kind as the installation kinds
/// name them, its file's name among the release's assets, its length, and its SHA-256 hash in lower-case hexadecimal.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Asset {
    pub platform: String,
    pub kind: String,
    pub name: String,
    pub size: u64,
    pub sha256: String,
}

impl UpdateManifest {
    /// The package for `platform` of `kind`, if the release has one.
    pub fn asset(&self, platform: &str, kind: &str) -> Option<&Asset> {
        self.assets.iter().find(|asset| asset.platform == platform && asset.kind == kind)
    }

    /// Reads a manifest whose signature has been checked; fails when it is malformed or of another schema.
    pub fn parse(json: &[u8]) -> io::Result<Self> {
        if json.len() > MAX_SIZE {
            return Err(invalid("the update information is too large"));
        }
        let root: Value =
            serde_json::from_slice(json).map_err(|e| invalid(format!("malformed update information: {e}")))?;
        if !within_limits(&root, 1) {
            return Err(invalid("malformed update information: beyond its limits"));
        }
        let Value::Object(root) = root else {
            return Err(invalid("malformed update information"));
        };
        if root.get("schema").and_then(Value::as_u64) != Some(SCHEMA) {
            return Err(invalid("update information of an unknown schema"));
        }
        let version = Version::parse(text(&root, "version")?)
            .map_err(|_| invalid("update information without a valid version"))?;
        let published = text(&root, "published")?
            .parse::<Timestamp>()
            .map_err(|_| invalid("update information without a valid date"))?;
        let critical = match root.get("critical") {
            None => false,
            Some(Value::Bool(critical)) => *critical,
            Some(_) => return Err(invalid("malformed update information: critical")),
        };
        let notes = match root.get("notes") {
            None => String::new(),
            Some(Value::String(notes)) => notes.clone(),
            Some(_) => return Err(invalid("malformed update information: notes")),
        };
        let Some(Value::Array(listed)) = root.get("assets") else {
            return Err(invalid("update information without packages"));
        };
        let mut assets = Vec::new();
        for listed in listed {
            if let Some(asset) = asset(listed)? {
                assets.push(asset);
            }
        }
        Ok(Self { version, published, critical, notes, assets })
    }
}

// An asset of a kind or platform this version does not know of is left out; one that is malformed is not.
fn asset(node: &Value) -> io::Result<Option<Asset>> {
    let Value::Object(node) = node else {
        return Err(invalid("malformed update information: an asset"));
    };
    let platform = text(node, "platform")?;
    let kind = text(node, "kind")?;
    let name = text(node, "name")?;
    let sha256 = text(node, "sha256")?;
    if !is_platform(platform) || !is_kind(kind) {
        return Ok(None);
    }
    let size = node.get("size").and_then(Value::as_u64).filter(|&size| size > 0);
    match size {
        Some(size) if is_file_name(name) && is_sha256(sha256) => Ok(Some(Asset {
            platform: platform.to_owned(),
            kind: kind.to_owned(),
            name: name.to_owned(),
            size,
            sha256: sha256.to_owned(),
        })),
        _ => Err(invalid(format!("malformed update information: asset {name}"))),
    }
}

fn text<'a>(node: &'a Map<String, Value>, name: &str) -> io::Result<&'a str> {
    node.get(name)
        .and_then(Value::as_str)
        .ok_or_else(|| invalid(format!("malformed update information: {name}")))
}

// Nested at most four deep, names short and strings no longer than the notes may be.
fn within_limits(value: &Value, depth: usize) -> bool {
    match value {
        _ if depth > MAX_DEPTH => false,
        Value::String(text) => text.chars().count() <= MAX_NOTES,
        Value::Array(values) => values.iter().all(|value| within_limits(value, depth.saturating_add(1))),
        Value::Object(fields) => fields
            .iter()
            .all(|(name, value)| name.chars().count() <= MAX_NAME && within_limits(value, depth.saturating_add(1))),
        _ => true,
    }
}

// "system-processor": lower-case letters, digits and underscores, 1 to 16 on each side.
fn is_platform(text: &str) -> bool {
    let part = |part: &str| {
        (1..=16).contains(&part.len())
            && part.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
    };
    text.split_once('-').is_some_and(|(system, processor)| part(system) && part(processor))
}

fn is_kind(text: &str) -> bool {
    (1..=16).contains(&text.len()) && text.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

// A file name of its own, with nothing a URL would have to escape: no path, never "." or "..", nor hidden.
fn is_file_name(text: &str) -> bool {
    let allowed = |b: u8| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'~' | b'+' | b'-');
    let mut bytes = text.bytes();
    (1..=128).contains(&text.len()) && bytes.next().is_some_and(allowed) && bytes.all(|b| allowed(b) || b == b'.')
}

fn is_sha256(text: &str) -> bool {
    text.len() == 64 && text.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn invalid(message: impl Into<String>) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message.into())
}
