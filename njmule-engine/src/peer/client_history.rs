// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The clients met, by user hash: the name and program they gave last, when they were first and last met and, when the
//! countries are shown, where from; never their address. A client not met for [`KEPT`] is forgotten, and at most
//! [`MAX_CLIENTS`] are kept, those met longest ago going first. Kept in [`FILE_NAME`], as the Java engine keeps it.

use std::io;
use std::path::{Path, PathBuf};
use std::time::Duration;

use indexmap::IndexMap;
use serde_json::{Value, json};

use crate::persist::atomic_files;

pub const FILE_NAME: &str = "clienthistory.json";
/// A client not met for this long is forgotten.
pub const KEPT: Duration = Duration::from_secs(90 * 24 * 3600);
pub const MAX_CLIENTS: usize = 20_000;

const FORMAT: u64 = 1;

/// One client met, its times in seconds since 1970.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub user_hash: [u8; 16],
    pub nick: String,
    pub software: String,
    pub first_seen: u64,
    pub last_seen: u64,
    pub country: Option<String>,
}

pub struct ClientHistory {
    file: PathBuf,
    // By last meeting, oldest first.
    clients: IndexMap<[u8; 16], Entry>,
    changed: bool,
}

impl ClientHistory {
    /// The history kept in `config_dir`, without the clients not met for [`KEPT`] at `now`; empty when unreadable.
    pub fn load(config_dir: &Path, now: u64) -> Self {
        let mut history = Self { file: config_dir.join(FILE_NAME), clients: IndexMap::new(), changed: false };
        let read = atomic_files::read(&history.file, |data| {
            serde_json::from_slice::<Value>(data).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
        });
        let root = match read {
            Ok(root) => root,
            // Nobody met yet.
            Err(e) if e.kind() == io::ErrorKind::NotFound => return history,
            Err(e) => {
                tracing::warn!("Starting a new client history: cannot read {}: {e}", history.file.display());
                return history;
            }
        };
        if root.get("format").and_then(Value::as_u64) != Some(FORMAT) {
            return history;
        }
        let list = root.get("clients").and_then(Value::as_array).map_or(&[][..], Vec::as_slice);
        // A malformed client starts the history anew, as in the Java engine.
        let Some(mut read) = list.iter().map(entry).collect::<Option<Vec<Entry>>>() else {
            tracing::warn!("Starting a new client history: {} holds a malformed client", history.file.display());
            return history;
        };
        read.sort_by_key(|entry| entry.last_seen);
        for entry in read {
            history.clients.insert(entry.user_hash, entry);
        }
        history.forget_old(now);
        history
    }

    /// Notes that `user_hash` was met at `now`, with what it said of itself.
    pub fn seen(&mut self, user_hash: [u8; 16], nick: &str, software: &str, country: Option<&str>, now: u64) {
        let before = self.clients.shift_remove(&user_hash);
        let country = match (&before, country) {
            (Some(before), None) => before.country.clone(),
            (_, country) => country.map(str::to_owned),
        };
        self.clients.insert(
            user_hash,
            Entry {
                user_hash,
                nick: nick.to_owned(),
                software: software.to_owned(),
                first_seen: before.map_or(now, |before| before.first_seen),
                last_seen: now,
                country,
            },
        );
        while self.clients.len() > MAX_CLIENTS {
            self.clients.shift_remove_index(0);
        }
        self.changed = true;
    }

    /// The clients met within [`KEPT`] of `now`, those met last first.
    pub fn clients(&mut self, now: u64) -> Vec<Entry> {
        self.forget_old(now);
        self.clients.values().rev().cloned().collect()
    }

    /// Writes the history when it changed.
    pub fn save(&mut self) {
        if !self.changed {
            return;
        }
        let clients: Vec<Value> = self
            .clients
            .values()
            .map(|entry| {
                let mut client = json!({
                    "userHash": hex(&entry.user_hash),
                    "nick": entry.nick,
                    "software": entry.software,
                    "firstSeen": entry.first_seen,
                    "lastSeen": entry.last_seen,
                });
                if let (Some(country), Some(fields)) = (&entry.country, client.as_object_mut()) {
                    fields.insert("country".to_owned(), json!(country));
                }
                client
            })
            .collect();
        let root = json!({"format": FORMAT, "clients": clients});
        let written = serde_json::to_vec(&root)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))
            .and_then(|data| atomic_files::write(&self.file, &data));
        match written {
            Ok(()) => self.changed = false,
            Err(e) => tracing::warn!("Cannot save the client history to {}: {e}", self.file.display()),
        }
    }

    fn forget_old(&mut self, now: u64) {
        let oldest = now.saturating_sub(KEPT.as_secs());
        while self.clients.first().is_some_and(|(_, entry)| entry.last_seen < oldest) {
            self.clients.shift_remove_index(0);
            self.changed = true;
        }
    }
}

fn entry(client: &Value) -> Option<Entry> {
    let text = |name: &str| client.get(name).and_then(Value::as_str).unwrap_or_default().to_owned();
    let number = |name: &str| client.get(name).and_then(Value::as_u64).unwrap_or(0);
    let user_hash = client.get("userHash").and_then(Value::as_str).and_then(parse_hash)?;
    Some(Entry {
        user_hash,
        nick: text("nick"),
        software: text("software"),
        first_seen: number("firstSeen"),
        last_seen: number("lastSeen"),
        country: client.get("country").and_then(Value::as_str).map(str::to_owned),
    })
}

fn hex(hash: &[u8; 16]) -> String {
    data_encoding::HEXUPPER.encode(hash)
}

fn parse_hash(text: &str) -> Option<[u8; 16]> {
    data_encoding::HEXLOWER_PERMISSIVE.decode(text.as_bytes()).ok()?.try_into().ok()
}
