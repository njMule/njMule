// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The JSON of the remote-control API, field for field as `openapi.yaml` describes it.

use serde_json::{Map, Value, json};

use crate::api::download_info::{DownloadInfo, SourceInfo};
use crate::api::ed2k_link::FileLink;
use crate::api::engine_event::EngineEvent;
use crate::api::nj_mule_info;
use crate::api::peer_info::PeerInfo;
use crate::api::search_result::SearchResult;
use crate::api::server_info::ServerInfo;
use crate::config::preferences::Preferences;
use crate::files::file_types;
use crate::files::shared_files::SharedFile;
use crate::persist::preferences_codec;

pub fn preferences(preferences: &Preferences) -> Value {
    Value::Object(
        preferences_codec::to_map(preferences)
            .into_iter()
            .map(|(key, value)| (key.to_owned(), json!(value)))
            .collect(),
    )
}

/// No update checks yet: nothing known, nothing to install.
pub fn update() -> Value {
    json!({
        "state": "idle",
        "current": nj_mule_info::version(),
        "installation": "unknown",
        "installer": "none",
        "canInstall": false,
        "checking": false,
        "lastCheck": null,
        "failure": null,
        "available": null,
        "skipped": null,
        "download": null,
        "ready": false,
        "readyFile": null,
        "install": "asked",
    })
}

/// No schedule yet: no rules, none active.
pub fn schedule() -> Value {
    json!({"rules": [], "active": null})
}

pub fn peer_info(info: &PeerInfo) -> Value {
    json!({
        "address": info.address,
        "userHash": hash(&info.user_hash),
        "nick": info.nick,
        "software": info.software,
        "obfuscated": info.obfuscated,
        "identification": info.identification.name(),
    })
}

/// The answer of a call that only needs to be done.
pub fn ok() -> Value {
    json!({"ok": true})
}

pub fn error(message: &str) -> Value {
    json!({"error": message})
}

/// The name and the data of an event, as the event stream sends them.
pub fn event(event: &EngineEvent) -> (&'static str, Value) {
    match event {
        EngineEvent::Started { version } => ("started", json!({"version": version})),
        EngineEvent::Stopping => ("stopping", Value::Object(Map::new())),
        EngineEvent::PreferencesChanged => ("preferences.changed", Value::Object(Map::new())),
        EngineEvent::ServerConnecting(info) => ("server.connecting", json!({"server": server(info)})),
        EngineEvent::ServerConnected { server: info, client_id, low_id, obfuscated } => (
            "server.connected",
            json!({"server": server(info), "clientId": client_id, "lowId": low_id, "obfuscated": obfuscated}),
        ),
        EngineEvent::ServerDisconnected { server: info, reason } => {
            let mut data = Map::new();
            if let Some(info) = info {
                data.insert("server".to_owned(), server(info));
            }
            data.insert("reason".to_owned(), json!(reason));
            ("server.disconnected", Value::Object(data))
        }
        EngineEvent::ServerMessage { server: info, text } => {
            ("server.message", json!({"server": server(info), "text": text}))
        }
        EngineEvent::ServerUpdated(info) => ("server.updated", json!({"server": server(info)})),
        EngineEvent::ServersChanged { count } => ("servers.changed", json!({"count": count})),
        EngineEvent::SearchResults { search, results } => (
            "search.results",
            json!({"search": search, "results": results.iter().map(search_result).collect::<Vec<_>>()}),
        ),
        EngineEvent::SearchEnded { search, results } => ("search.ended", json!({"search": search, "results": results})),
        EngineEvent::DownloadAdded(info) => ("download.added", json!({"download": download(info, false)})),
        EngineEvent::DownloadProgress(info) => ("download.progress", json!({"download": download(info, false)})),
        EngineEvent::DownloadCompleted { download: info, file } => {
            ("download.completed", json!({"download": download(info, false), "file": file.display().to_string()}))
        }
        EngineEvent::DownloadFailed { download: info, reason } => {
            ("download.failed", json!({"download": download(info, false), "reason": reason}))
        }
        EngineEvent::DownloadRemoved(removed) => ("download.removed", json!({"hash": hash(removed)})),
    }
}

/// A download, with the map of its parts when asked for.
pub fn download(info: &DownloadInfo, with_parts: bool) -> Value {
    let link = FileLink::of(&info.name, info.size, info.hash).map(|link| link.to_uri()).unwrap_or_default();
    let mut data = json!({
        "hash": hash(&info.hash),
        "name": info.name,
        "size": info.size,
        "completed": info.completed,
        "status": info.status.name(),
        "priority": info.priority.name(),
        "category": info.category,
        "sources": info.sources,
        "transferring": info.transferring,
        "bytesPerSecond": info.bytes_per_second,
        "previewFirst": info.preview_first,
        "autoSwap": info.auto_swap,
        "link": link,
        "kind": kind(&info.name),
    });
    if with_parts && let Value::Object(fields) = &mut data {
        let spans = |spans: &[(u64, u64)]| spans.iter().map(|(start, end)| json!([start, end])).collect::<Vec<_>>();
        fields.insert(
            "parts".to_owned(),
            json!({
                "size": info.parts.size,
                "missing": spans(&info.parts.missing),
                "requested": spans(&info.parts.requested),
                "sources": info.parts.sources,
            }),
        );
    }
    data
}

/// A source of a download, as the list of downloading clients shows it.
pub fn source(info: &SourceInfo) -> Value {
    json!({
        "file": hash(&info.file),
        "fileName": info.file_name,
        "address": info.address,
        "nick": info.nick,
        "software": info.software,
        "state": info.state.name(),
        "queueRank": info.queue_rank,
        "received": info.received,
        "parts": info.parts,
        "partCount": info.part_count,
        "reportedName": info.reported_name,
        "askedFor": null,
        "askedForName": "",
        "country": null,
    })
}

// The kind of a file named `name`; other when its extension does not tell.
fn kind(name: &str) -> &'static str {
    match file_types::of(name) {
        Some(file_types::VIDEO) => "video",
        Some(file_types::AUDIO) => "audio",
        Some(file_types::IMAGE) => "image",
        Some(file_types::DOCUMENT) => "document",
        Some(_) if file_types::is_a(file_types::ARCHIVE, name) => "archive",
        Some(_) if file_types::is_a(file_types::DISC_IMAGE, name) => "disc_image",
        Some(_) => "program",
        None => "other",
    }
}

/// A file a search found.
pub fn search_result(result: &SearchResult) -> Value {
    json!({
        "hash": hash(&result.hash),
        "name": result.name,
        "size": result.size,
        "sources": result.sources,
        "completeSources": result.complete_sources,
        "type": result.file_type,
        // Its eD2K link carries it, so that a download from the link can check and repair the file.
        "aich": result.aich_hash.map(|aich| data_encoding::BASE32_NOPAD.encode(&aich)),
        "spamRating": result.spam_rating,
        "spam": result.is_spam(),
        "names": result
            .names
            .iter()
            .map(|name| json!({"name": name.name, "sources": name.sources, "completeSources": name.complete_sources}))
            .collect::<Vec<_>>(),
    })
}

/// A shared file: what it is, where it lies and how much it was asked for.
pub fn shared_file(file: &SharedFile) -> Value {
    json!({
        "hash": hash(&file.hash),
        "name": file.name,
        "size": file.size,
        "path": file.path.display().to_string(),
        "priority": "normal",
        "link": file.link().to_uri(),
        "statistics": {
            "requests": 0,
            "accepted": 0,
            "transferred": 0,
            "totalRequests": 0,
            "totalAccepted": 0,
            "totalTransferred": 0,
        },
    })
}

/// A known server: its address, what it told of itself and how we use it.
pub fn server(info: &ServerInfo) -> Value {
    json!({
        "address": info.address,
        "name": info.name,
        "description": info.description,
        "users": info.users,
        "files": info.files,
        "maxUsers": info.max_users,
        "lowIdUsers": info.low_id_users,
        "softFiles": info.soft_files,
        "hardFiles": info.hard_files,
        "failed": info.failed,
        "priority": info.priority.name(),
        "ping": info.ping,
        "version": info.version,
        "obfuscation": info.obfuscation,
        "static": info.is_static,
        "country": null,
    })
}

/// A hash as the API writes it: 32 upper-case hex digits.
pub fn hash(hash: &[u8; 16]) -> String {
    data_encoding::HEXUPPER.encode(hash)
}
