// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's answers in `golden/profile-files.jsonl`, given again: the files of a profile as the Java engine
//! writes them and changed three times, each read as the Java engine reads it.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use crate::canonical;

use canonical::Canonical;
use njmule_engine::persist::format::cancelled_met;
use njmule_engine::persist::format::clients_met::{self, Credit};
use njmule_engine::persist::format::friends_met::{self, Friend};
use njmule_engine::persist::format::ip_filter_dat::{self, Range};
use njmule_engine::persist::format::kad_index_files::{
    self, Entry, IndexKey, Keyword, KeywordEntry, KeywordIndex, KeywordSource, Load, LoadIndex, Source, SourceIndex,
};
use njmule_engine::persist::format::known_met::{self, KnownFile};
use njmule_engine::persist::format::known2_met;
use njmule_engine::persist::format::nodes_dat::{self, Contact};
use njmule_engine::persist::format::part_met;
use njmule_engine::persist::format::preferences_dat;
use njmule_engine::persist::format::preferences_kad_dat;
use njmule_engine::persist::format::search_files::{self, SearchResult, StoredSearch};
use njmule_engine::persist::format::server_met::{self, Server};
use njmule_engine::persist::format::text_files::{self, SharedFile, StaticServer};
use njmule_engine::protocol::io::DecodeError;
use serde_json::{Value, json};

const CORPUS: &str =
    concat!(env!("CARGO_MANIFEST_DIR"), "/../njmule-core/src/test/resources/golden/profile-files.jsonl");

#[test]
fn should_give_the_recorded_answers() {
    let text = std::fs::read_to_string(CORPUS).expect("the corpus of the Java engine");
    let mut differences = Vec::new();
    let mut cases = 0;
    for line in text.lines().filter(|line| !line.trim().is_empty()) {
        let recorded: Value = serde_json::from_str(line).unwrap();
        let answer = answered(&recorded);
        cases += 1;
        if answer != recorded {
            differences.push(format!("recorded {recorded}\nanswered {answer}"));
        }
    }
    assert!(cases > 0, "no case in {CORPUS}");
    assert!(differences.is_empty(), "{} of {cases} cases differ:\n{}", differences.len(), differences.join("\n"));
}

// What the Java engine's ProfileFilesCorpusTest answers for the input of `recorded`.
fn answered(recorded: &Value) -> Value {
    let kind = recorded["kind"].as_str().unwrap();
    let data = hex::decode(recorded["in"].as_str().unwrap()).unwrap();
    let read: Result<Value, DecodeError> = match kind {
        "server.met" => server_met::read(&data).map(|v| v.canonical()),
        "known.met" => known_met::read(&data).map(|v| v.canonical()),
        "known.met salvaged" => Ok(known_met::salvage(&data).canonical()),
        "part.met" => part_met::read(&data).map(|v| v.canonical()),
        "clients.met" => clients_met::read(&data).map(|v| v.canonical()),
        "emfriends.met" => friends_met::read(&data).map(|v| v.canonical()),
        "cancelled.met" => cancelled_met::read(&data).map(|v| v.canonical()),
        "StoredSearches.met" => search_files::read_stored(&data).map(|v| v.canonical()),
        "SearchSpam.met" => search_files::read_spam(&data).map(|v| v.canonical()),
        "known2_64.met" => known2_met::read(&data).map(|v| v.canonical()),
        "known2_64.met salvaged" => Ok(known2_met::salvage(&data).canonical()),
        "preferencesKad.dat" => preferences_kad_dat::read(&data).map(|v| v.canonical()),
        "nodes.dat" => nodes_dat::read(&data).map(|v| v.canonical()),
        "preferences.dat" => preferences_dat::read(&data).map(|v| v.canonical()),
        "load_index.dat" => kad_index_files::read_load(&data).map(|v| v.canonical()),
        "src_index.dat" => kad_index_files::read_sources(&data).map(|v| v.canonical()),
        "key_index.dat" => kad_index_files::read_keywords(&data).map(|v| v.canonical()),
        "ipfilter.dat" => Ok(ip_filter_dat::read(&data, "ipfilter.dat").canonical()),
        "staticservers.dat" => Ok(text_files::static_servers(&data).canonical()),
        "shareddir.dat" => Ok(text_files::shared_files(&data).canonical()),
        other => panic!("unknown kind {other}"),
    };
    let mut answer = json!({"kind": kind, "in": recorded["in"]});
    match read {
        Ok(value) => answer["value"] = value,
        Err(_) => answer["error"] = json!("decode"),
    }
    answer
}

impl Canonical for [u8; 20] {
    fn canonical(&self) -> Value {
        json!(hex::encode(self))
    }
}

impl Canonical for Server {
    fn canonical(&self) -> Value {
        json!({"ip": self.ip, "port": self.port, "tags": self.tags.canonical()})
    }
}

impl Canonical for KnownFile {
    fn canonical(&self) -> Value {
        json!({
            "mtime": self.mtime,
            "fileHash": self.file_hash.canonical(),
            "partHashes": self.part_hashes.canonical(),
            "tags": self.tags.canonical(),
        })
    }
}

impl Canonical for part_met::Contents {
    fn canonical(&self) -> Value {
        json!({
            "version": self.version,
            "mtime": self.mtime,
            "fileHash": self.file_hash.canonical(),
            "partHashes": self.part_hashes.canonical(),
            "tags": self.tags.canonical(),
        })
    }
}

impl Canonical for Credit {
    fn canonical(&self) -> Value {
        json!({
            "userHash": self.user_hash.canonical(),
            "uploaded": self.uploaded,
            "downloaded": self.downloaded,
            "lastSeen": self.last_seen,
            "publicKey": hex::encode(&self.public_key),
        })
    }
}

impl Canonical for Friend {
    fn canonical(&self) -> Value {
        json!({
            "userHash": self.user_hash.canonical(),
            "lastIp": self.last_ip,
            "lastPort": self.last_port,
            "lastSeen": self.last_seen,
            "lastChatted": self.last_chatted,
            "tags": self.tags.canonical(),
        })
    }
}

impl Canonical for cancelled_met::Contents {
    fn canonical(&self) -> Value {
        json!({"seed": self.seed, "legacy": self.legacy, "keys": self.keys.canonical()})
    }
}

impl Canonical for SearchResult {
    fn canonical(&self) -> Value {
        json!({
            "fileHash": self.file_hash.canonical(),
            "clientId": self.client_id,
            "port": self.port,
            "tags": self.tags.canonical(),
        })
    }
}

impl Canonical for StoredSearch {
    fn canonical(&self) -> Value {
        json!({
            "id": self.id,
            "type": self.search_type,
            "clientSharedFiles": self.client_shared_files,
            "title": self.title,
            "expression": self.expression,
            "fileType": self.file_type,
            "results": self.results.canonical(),
        })
    }
}

impl Canonical for known2_met::Entry {
    fn canonical(&self) -> Value {
        json!({"masterHash": self.master_hash.canonical(), "blockHashes": self.block_hashes.canonical()})
    }
}

impl Canonical for preferences_kad_dat::Contents {
    fn canonical(&self) -> Value {
        json!({"lastIp": self.last_ip, "kadId": self.kad_id.canonical()})
    }
}

impl Canonical for Contact {
    fn canonical(&self) -> Value {
        json!({
            "id": self.id.canonical(),
            "ip": self.ip,
            "udpPort": self.udp_port,
            "tcpPort": self.tcp_port,
            "version": self.version,
            "type": self.contact_type,
            "udpKey": {"key": self.udp_key.key, "ip": self.udp_key.ip},
            "verified": self.verified,
        })
    }
}

impl Canonical for nodes_dat::Contents {
    fn canonical(&self) -> Value {
        json!({"fileVersion": self.file_version, "bootstrap": self.bootstrap, "contacts": self.contacts.canonical()})
    }
}

impl Canonical for preferences_dat::Contents {
    fn canonical(&self) -> Value {
        json!({
            "version": self.version,
            "userHash": self.user_hash.canonical(),
            "windowPlacement": hex::encode(&self.window_placement),
        })
    }
}

impl Canonical for Load {
    fn canonical(&self) -> Value {
        json!({"key": self.key.canonical(), "time": self.time})
    }
}

impl Canonical for LoadIndex {
    fn canonical(&self) -> Value {
        json!({"saveTime": self.save_time, "loads": self.loads.canonical()})
    }
}

impl Canonical for Entry {
    fn canonical(&self) -> Value {
        json!({"lifetime": self.lifetime, "tags": self.tags.canonical()})
    }
}

impl Canonical for Source {
    fn canonical(&self) -> Value {
        json!({"id": self.id.canonical(), "entries": self.entries.canonical()})
    }
}

impl Canonical for IndexKey {
    fn canonical(&self) -> Value {
        json!({"key": self.key.canonical(), "sources": self.sources.canonical()})
    }
}

impl Canonical for SourceIndex {
    fn canonical(&self) -> Value {
        json!({"version": self.version, "expiry": self.expiry, "keys": self.keys.canonical()})
    }
}

impl Canonical for KeywordEntry {
    fn canonical(&self) -> Value {
        let tracking = &self.tracking;
        json!({
            "lifetime": self.lifetime,
            "tracking": {
                "aichHashes": tracking.aich_hashes.canonical(),
                "names": tracking.names.iter().map(|n| json!({"name": n.name, "popularity": n.popularity})).collect::<Vec<_>>(),
                "publishers": tracking
                    .publishers
                    .iter()
                    .map(|p| json!({"ip": p.ip, "time": p.time, "aichIndex": p.aich_index}))
                    .collect::<Vec<_>>(),
            },
            "tags": self.tags.canonical(),
        })
    }
}

impl Canonical for KeywordSource {
    fn canonical(&self) -> Value {
        json!({"id": self.id.canonical(), "entries": self.entries.canonical()})
    }
}

impl Canonical for Keyword {
    fn canonical(&self) -> Value {
        json!({"key": self.key.canonical(), "sources": self.sources.canonical()})
    }
}

impl Canonical for KeywordIndex {
    fn canonical(&self) -> Value {
        json!({
            "version": self.version,
            "expiry": self.expiry,
            "ownId": self.own_id.canonical(),
            "keywords": self.keywords.canonical(),
        })
    }
}

impl Canonical for Range {
    fn canonical(&self) -> Value {
        json!({"start": self.start, "end": self.end, "level": self.level, "description": self.description})
    }
}

impl Canonical for StaticServer {
    fn canonical(&self) -> Value {
        json!({"host": self.host, "port": self.port, "priority": self.priority, "name": self.name})
    }
}

impl Canonical for SharedFile {
    fn canonical(&self) -> Value {
        json!({"path": self.path, "excluded": self.excluded})
    }
}
