// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The JSON of the remote-control API, field for field as `openapi.yaml` describes it.

use serde_json::{Map, Value, json};

use crate::api::engine_event::EngineEvent;
use crate::api::nj_mule_info;
use crate::api::peer_info::PeerInfo;
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

pub fn error(message: &str) -> Value {
    json!({"error": message})
}

/// The name and the data of an event, as the event stream sends them.
pub fn event(event: &EngineEvent) -> (&'static str, Value) {
    match event {
        EngineEvent::Started { version } => ("started", json!({"version": version})),
        EngineEvent::Stopping => ("stopping", Value::Object(Map::new())),
        EngineEvent::PreferencesChanged => ("preferences.changed", Value::Object(Map::new())),
    }
}

/// A hash as the API writes it: 32 upper-case hex digits.
pub fn hash(hash: &[u8; 16]) -> String {
    data_encoding::HEXUPPER.encode(hash)
}
