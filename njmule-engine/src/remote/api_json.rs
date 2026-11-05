// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The JSON of the remote-control API, field for field as `openapi.yaml` describes it.

use serde_json::{Map, Value, json};

use crate::api::engine_event::EngineEvent;
use crate::api::nj_mule_info;
use crate::api::peer_info::PeerInfo;
use crate::api::server_info::ServerInfo;
use crate::config::preferences::Preferences;
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
    }
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
