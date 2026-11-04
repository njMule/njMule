// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Decoded values as the JSON the corpora keep (the Java engine's `Canonical`): structs as objects of their fields in
//! lowerCamelCase, with an `@type` for the cases of a sealed type, integers as the unsigned values of their width,
//! floats as the hex of their bits, bytes and hashes in lower-case hex, an absent value as null.

use njmule_engine::protocol::ed2k::file_entry::FileEntry;
use njmule_engine::protocol::ed2k::server_datagrams::{Description, Status};
use njmule_engine::protocol::ed2k::server_messages::{
    Endpoint, FoundSources, IdChange, Login, SearchResults, ServerIdent, ServerStatus, Source,
};
use njmule_engine::protocol::tag::{Tag, TagName};
use serde_json::{Value, json};

pub trait Canonical {
    fn canonical(&self) -> Value;
}

impl<T: Canonical> Canonical for Vec<T> {
    fn canonical(&self) -> Value {
        Value::Array(self.iter().map(Canonical::canonical).collect())
    }
}

impl<T: Canonical> Canonical for Option<T> {
    fn canonical(&self) -> Value {
        self.as_ref().map_or(Value::Null, Canonical::canonical)
    }
}

impl Canonical for String {
    fn canonical(&self) -> Value {
        json!(self)
    }
}

impl Canonical for [u8; 16] {
    fn canonical(&self) -> Value {
        json!(hex::encode(self))
    }
}

impl Canonical for TagName {
    fn canonical(&self) -> Value {
        self.as_id().map_or_else(|| json!(hex::encode(self.to_bytes())), |id| json!(id))
    }
}

impl Canonical for Tag {
    fn canonical(&self) -> Value {
        let (kind, value) = match self {
            Tag::Int { value, .. } => ("IntTag", json!(value)),
            Tag::String { value, .. } => ("StringTag", json!(value)),
            Tag::Hash { value, .. } => ("HashTag", value.canonical()),
            Tag::Float { value, .. } => ("FloatTag", json!(format!("{:08x}", value.to_bits()))),
            Tag::Bool { value, .. } => ("BoolTag", json!(value)),
            Tag::BoolArray { bit_count, value, .. } => {
                return json!({
                    "@type": "BoolArrayTag",
                    "name": self.name().canonical(),
                    "bitCount": bit_count,
                    "value": hex::encode(value),
                });
            }
            Tag::Blob { value, .. } => ("BlobTag", json!(hex::encode(value))),
            Tag::Bsob { value, .. } => ("BsobTag", json!(hex::encode(value))),
        };
        json!({"@type": kind, "name": self.name().canonical(), "value": value})
    }
}

impl Canonical for FileEntry {
    fn canonical(&self) -> Value {
        json!({"hash": self.hash.canonical(), "clientId": self.client_id, "port": self.port, "tags": self.tags.canonical()})
    }
}

impl Canonical for Login {
    fn canonical(&self) -> Value {
        json!({
            "userHash": self.user_hash.canonical(),
            "clientId": self.client_id,
            "tcpPort": self.tcp_port,
            "nick": self.nick,
            "capabilities": self.capabilities,
        })
    }
}

impl Canonical for IdChange {
    fn canonical(&self) -> Value {
        json!({"clientId": self.client_id, "serverFlags": self.server_flags, "reportedIp": self.reported_ip})
    }
}

impl Canonical for ServerStatus {
    fn canonical(&self) -> Value {
        json!({"users": self.users, "files": self.files})
    }
}

impl Canonical for ServerIdent {
    fn canonical(&self) -> Value {
        json!({
            "hash": self.hash.canonical(),
            "ip": self.ip,
            "port": self.port,
            "name": self.name.canonical(),
            "description": self.description.canonical(),
        })
    }
}

impl Canonical for Endpoint {
    fn canonical(&self) -> Value {
        json!({"ip": self.ip, "port": self.port})
    }
}

impl Canonical for SearchResults {
    fn canonical(&self) -> Value {
        json!({"files": self.files.canonical(), "more": self.more})
    }
}

impl Canonical for Source {
    fn canonical(&self) -> Value {
        json!({
            "clientId": self.client_id,
            "port": self.port,
            "cryptOptions": self.crypt_options,
            "userHash": self.user_hash.canonical(),
        })
    }
}

impl Canonical for FoundSources {
    fn canonical(&self) -> Value {
        json!({"file": self.file.canonical(), "sources": self.sources.canonical()})
    }
}

impl Canonical for Status {
    fn canonical(&self) -> Value {
        json!({
            "challenge": self.challenge,
            "users": self.users,
            "files": self.files,
            "maxUsers": self.max_users,
            "softFiles": self.soft_files,
            "hardFiles": self.hard_files,
            "udpFlags": self.udp_flags,
            "lowIdUsers": self.low_id_users,
            "obfuscatedUdpPort": self.obfuscated_udp_port,
            "obfuscatedTcpPort": self.obfuscated_tcp_port,
            "udpKey": self.udp_key,
        })
    }
}

impl Canonical for Description {
    fn canonical(&self) -> Value {
        json!({"challenge": self.challenge, "tags": self.tags.canonical()})
    }
}
