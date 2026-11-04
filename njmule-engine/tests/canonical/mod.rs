// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Decoded values as the JSON the corpora keep (the Java engine's `Canonical`): structs as objects of their fields in
//! lowerCamelCase, with an `@type` for the cases of a sealed type, integers as the unsigned values of their width,
//! floats as the hex of their bits, bytes and hashes in lower-case hex, an absent value as null.

use njmule_engine::api::file_comment::FileComment;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::ed2k::client_messages::{
    CompressedPart, FileName, FileStatus, HashSet, PartStatus, Range, RequestParts, SendingPart,
};
use njmule_engine::protocol::ed2k::client_reasks::{Ack, Ping, ThroughBuddy};
use njmule_engine::protocol::ed2k::file_entry::FileEntry;
use njmule_engine::protocol::ed2k::hello::Hello;
use njmule_engine::protocol::ed2k::low_id_messages::{Callback, DirectCallbackRequest, UdpCheckRequest};
use njmule_engine::protocol::ed2k::server_datagrams::{Description, Status};
use njmule_engine::protocol::ed2k::server_messages::{
    Endpoint, FoundSources, IdChange, Login, SearchResults, ServerIdent, ServerStatus, Source,
};
use njmule_engine::protocol::ed2k::source_exchange::{self, Answer, Request};
use njmule_engine::protocol::kad::kad_messages::{
    self, BootstrapAnswer, BuddyOffer, CallbackRequest, Contact, Entry, FindBuddy, FirewallCheck, FirewallUdp,
    KeywordPublish, NotesPublish, NotesSearch, PublishAnswer, SearchAnswer, Sender, SourcePublish, SourceSearch,
};
use njmule_engine::protocol::packet::Packet;
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

impl Canonical for Hello {
    fn canonical(&self) -> Value {
        json!({
            "userHash": self.user_hash.canonical(),
            "clientId": self.client_id,
            "tcpPort": self.tcp_port,
            "tags": self.tags.canonical(),
            "serverIp": self.server_ip,
            "serverPort": self.server_port,
        })
    }
}

impl Canonical for FileName {
    fn canonical(&self) -> Value {
        json!({"file": self.file.canonical(), "name": self.name})
    }
}

// A set of parts as the indexes of those present, as a bit set's form is.
fn indexes(parts: &[bool]) -> Value {
    json!(parts.iter().enumerate().filter(|(_, has)| **has).map(|(part, _)| part).collect::<Vec<_>>())
}

impl Canonical for FileStatus {
    fn canonical(&self) -> Value {
        json!({"file": self.file.canonical(), "partCount": self.part_count, "parts": indexes(&self.parts)})
    }
}

impl Canonical for HashSet {
    fn canonical(&self) -> Value {
        json!({"file": self.file.canonical(), "parts": self.parts.canonical()})
    }
}

impl Canonical for Range {
    fn canonical(&self) -> Value {
        json!({"start": self.start, "end": self.end})
    }
}

impl Canonical for RequestParts {
    fn canonical(&self) -> Value {
        json!({"file": self.file.canonical(), "ranges": self.ranges.to_vec().canonical()})
    }
}

impl Canonical for SendingPart {
    fn canonical(&self) -> Value {
        json!({"file": self.file.canonical(), "start": self.start, "end": self.end, "data": hex::encode(&self.data)})
    }
}

impl Canonical for CompressedPart {
    fn canonical(&self) -> Value {
        json!({
            "file": self.file.canonical(),
            "blockStart": self.block_start,
            "compressedSize": self.compressed_size,
            "chunk": hex::encode(&self.chunk),
        })
    }
}

impl Canonical for UInt128 {
    fn canonical(&self) -> Value {
        json!(hex::encode(self.to_big_endian()))
    }
}

impl Canonical for Packet {
    fn canonical(&self) -> Value {
        json!({"protocol": self.protocol(), "opcode": self.opcode(), "payload": hex::encode(self.payload())})
    }
}

impl Canonical for PartStatus {
    fn canonical(&self) -> Value {
        json!({"partCount": self.part_count, "parts": indexes(&self.parts)})
    }
}

impl Canonical for source_exchange::Source {
    fn canonical(&self) -> Value {
        json!({
            "ip": self.ip,
            "lowId": self.low_id,
            "port": self.port,
            "serverIp": self.server_ip,
            "serverPort": self.server_port,
            "userHash": self.user_hash.canonical(),
            "cryptOptions": self.crypt_options,
            "ipv6": self.ipv6.map(|address| address.to_string()),
        })
    }
}

impl Canonical for Answer {
    fn canonical(&self) -> Value {
        json!({"version": self.version, "file": self.file.canonical(), "sources": self.sources.canonical()})
    }
}

impl Canonical for Request {
    fn canonical(&self) -> Value {
        json!({"version": self.version, "file": self.file.canonical()})
    }
}

impl Canonical for Ping {
    fn canonical(&self) -> Value {
        json!({"file": self.file.canonical(), "parts": self.parts.canonical(), "completeSources": self.complete_sources})
    }
}

impl Canonical for Ack {
    fn canonical(&self) -> Value {
        json!({"parts": self.parts.canonical(), "rank": self.rank})
    }
}

impl Canonical for ThroughBuddy {
    fn canonical(&self) -> Value {
        json!({"buddyId": self.buddy_id.canonical(), "ping": self.ping.canonical()})
    }
}

impl Canonical for Callback {
    fn canonical(&self) -> Value {
        json!({
            "buddyId": self.buddy_id.canonical(),
            "file": self.file.canonical(),
            "requesterIp": self.requester_ip,
            "requesterPort": self.requester_port,
        })
    }
}

impl Canonical for DirectCallbackRequest {
    fn canonical(&self) -> Value {
        json!({"tcpPort": self.tcp_port, "userHash": self.user_hash.canonical(), "connectOptions": self.connect_options})
    }
}

impl Canonical for UdpCheckRequest {
    fn canonical(&self) -> Value {
        json!({"internalPort": self.internal_port, "externalPort": self.external_port, "senderKey": self.sender_key})
    }
}

impl Canonical for FileComment {
    fn canonical(&self) -> Value {
        json!({"text": self.text(), "rating": self.rating()})
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
        })
    }
}

impl Canonical for Sender {
    fn canonical(&self) -> Value {
        json!({"id": self.id.canonical(), "tcpPort": self.tcp_port, "version": self.version})
    }
}

impl Canonical for BootstrapAnswer {
    fn canonical(&self) -> Value {
        json!({"sender": self.sender.canonical(), "contacts": self.contacts.canonical()})
    }
}

impl Canonical for kad_messages::Hello {
    fn canonical(&self) -> Value {
        json!({"sender": self.sender.canonical(), "tags": self.tags.canonical()})
    }
}

impl Canonical for kad_messages::Request {
    fn canonical(&self) -> Value {
        json!({"count": self.count, "target": self.target.canonical(), "receiver": self.receiver.canonical()})
    }
}

impl Canonical for kad_messages::Answer {
    fn canonical(&self) -> Value {
        json!({"target": self.target.canonical(), "contacts": self.contacts.canonical()})
    }
}

impl Canonical for SourceSearch {
    fn canonical(&self) -> Value {
        json!({"target": self.target.canonical(), "start": self.start, "size": self.size})
    }
}

impl Canonical for NotesSearch {
    fn canonical(&self) -> Value {
        json!({"target": self.target.canonical(), "size": self.size})
    }
}

impl Canonical for Entry {
    fn canonical(&self) -> Value {
        json!({"id": self.id.canonical(), "tags": self.tags.canonical()})
    }
}

impl Canonical for SearchAnswer {
    fn canonical(&self) -> Value {
        json!({"sender": self.sender.canonical(), "target": self.target.canonical(), "results": self.results.canonical()})
    }
}

impl Canonical for KeywordPublish {
    fn canonical(&self) -> Value {
        json!({"keyword": self.keyword.canonical(), "files": self.files.canonical()})
    }
}

impl Canonical for SourcePublish {
    fn canonical(&self) -> Value {
        json!({"file": self.file.canonical(), "source": self.source.canonical(), "tags": self.tags.canonical()})
    }
}

impl Canonical for NotesPublish {
    fn canonical(&self) -> Value {
        json!({"file": self.file.canonical(), "publisher": self.publisher.canonical(), "tags": self.tags.canonical()})
    }
}

impl Canonical for PublishAnswer {
    fn canonical(&self) -> Value {
        json!({"target": self.target.canonical(), "load": self.load})
    }
}

impl Canonical for FirewallCheck {
    fn canonical(&self) -> Value {
        json!({"tcpPort": self.tcp_port, "userHash": self.user_hash.canonical(), "connectOptions": self.connect_options})
    }
}

impl Canonical for FindBuddy {
    fn canonical(&self) -> Value {
        json!({
            "buddyId": self.buddy_id.canonical(),
            "userHash": self.user_hash.canonical(),
            "tcpPort": self.tcp_port,
            "connectOptions": self.connect_options,
        })
    }
}

impl Canonical for BuddyOffer {
    fn canonical(&self) -> Value {
        json!({
            "buddyId": self.buddy_id.canonical(),
            "userHash": self.user_hash.canonical(),
            "tcpPort": self.tcp_port,
            "connectOptions": self.connect_options,
        })
    }
}

impl Canonical for CallbackRequest {
    fn canonical(&self) -> Value {
        json!({"buddyId": self.buddy_id.canonical(), "file": self.file.canonical(), "tcpPort": self.tcp_port})
    }
}

impl Canonical for FirewallUdp {
    fn canonical(&self) -> Value {
        json!({"error": self.error, "port": self.port})
    }
}

impl Canonical for u32 {
    fn canonical(&self) -> Value {
        json!(self)
    }
}

impl Canonical for u16 {
    fn canonical(&self) -> Value {
        json!(self)
    }
}
