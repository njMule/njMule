// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Each message kind of `golden/packets.jsonl` and the decoder that reads it, as its canonical JSON.

#![allow(dead_code)]

use super::Canonical;
use njmule_engine::protocol::ed2k::client_messages::{
    CompressedPart, FileName, FileStatus, HashSet, RequestParts, SendingPart,
};
use njmule_engine::protocol::ed2k::client_reasks::{Ack, Ping, ThroughBuddy};
use njmule_engine::protocol::ed2k::hello::Hello;
use njmule_engine::protocol::ed2k::low_id_messages::{Callback, DirectCallbackRequest, UdpCheckRequest};
use njmule_engine::protocol::ed2k::server_datagrams::{self, Description, Status};
use njmule_engine::protocol::ed2k::server_messages::{
    self, FoundSources, IdChange, Login, SearchResults, ServerIdent, ServerStatus,
};
use njmule_engine::protocol::ed2k::source_exchange::{Answer, Request};
use njmule_engine::protocol::ed2k::{chat_messages, file_description};
use njmule_engine::protocol::io::DecodeError;
use njmule_engine::protocol::io::PacketReader;
use njmule_engine::protocol::kad::kad_messages::{
    self, BootstrapAnswer, BuddyOffer, CallbackRequest, FindBuddy, FirewallCheck, FirewallUdp, KeywordPublish,
    KeywordSearch, NotesPublish, NotesSearch, PublishAnswer, SearchAnswer, SourcePublish, SourceSearch,
};
use njmule_engine::protocol::packet::{Packet, packing};
use njmule_engine::search::search_tree_codec;
use serde_json::Value;

pub type Decoder = fn(Packet) -> Result<Value, DecodeError>;

pub fn decoder(kind: &str) -> Option<Decoder> {
    Some(match kind {
        "login" => |p| Login::decode(&p).map(|v| v.canonical()),
        "id change" => |p| IdChange::decode(&p).map(|v| v.canonical()),
        "server status" => |p| ServerStatus::decode(&p).map(|v| v.canonical()),
        "server ident" => |p| ServerIdent::decode(&p).map(|v| v.canonical()),
        "search results" => |p| SearchResults::decode(&p).map(|v| v.canonical()),
        "found sources" => |p| FoundSources::decode(&p).map(|v| v.canonical()),
        "server message" => |p| server_messages::decode_message(&p).map(|v| v.canonical()),
        "server list" => |p| server_messages::decode_server_list(&p).map(|v| v.canonical()),
        "server status datagram" => |p| Status::decode(&p).map(|v| v.canonical()),
        "server description" => |p| Description::decode(&p).map(|v| v.canonical()),
        "global search results" => |p| server_datagrams::decode_search_results(&p).map(|v| v.canonical()),
        "global found sources" => |p| server_datagrams::decode_found_sources(&p).map(|v| v.canonical()),
        "hello" | "hello answer" => |p| Hello::decode(&p).map(|v| v.canonical()),
        "file name" => |p| FileName::decode(&p).map(|v| v.canonical()),
        "file status" => |p| FileStatus::decode(&p).map(|v| v.canonical()),
        "hash set" => |p| HashSet::decode(&p).map(|v| v.canonical()),
        "request parts" | "request parts, 64 bit" => |p| RequestParts::decode(&p).map(|v| v.canonical()),
        "sending part" => |p| SendingPart::decode(&p).map(|v| v.canonical()),
        "compressed part" => |p| CompressedPart::decode(&p).map(|v| v.canonical()),
        "source exchange answer" => |p| Answer::decode(&p).map(|v| v.canonical()),
        "packed source exchange answer" => {
            |p| Answer::decode(&packing::unpack(p, packing::MAX_CLIENT_UNPACKED)?).map(|v| v.canonical())
        }
        "source exchange request" => |p| Request::decode(&p).map(|v| v.canonical()),
        "reask ping" => |p| Ping::decode(&p, 4).map(|v| v.canonical()),
        "reask ack" => |p| Ack::decode(&p, 4).map(|v| v.canonical()),
        "reask through a buddy" => |p| ThroughBuddy::decode(&p).map(|v| v.canonical()),
        "callback" => |p| Callback::decode(&p).map(|v| v.canonical()),
        "direct callback request" => |p| DirectCallbackRequest::decode(&p).map(|v| v.canonical()),
        "udp check request" => |p| UdpCheckRequest::decode(&p).map(|v| v.canonical()),
        "file comment" => |p| file_description::decode(&p).map(|v| v.canonical()),
        "chat message" => |p| chat_messages::text(&p).map(|v| v.canonical()),
        "bootstrap answer" => |p| BootstrapAnswer::decode(&p).map(|v| v.canonical()),
        "kad hello" => |p| kad_messages::Hello::decode(&p).map(|v| v.canonical()),
        "kad request" => |p| kad_messages::Request::decode(&p).map(|v| v.canonical()),
        "kad answer" => |p| kad_messages::Answer::decode(&p).map(|v| v.canonical()),
        "source search" => |p| SourceSearch::decode(&p).map(|v| v.canonical()),
        "notes search" => |p| NotesSearch::decode(&p).map(|v| v.canonical()),
        "search answer" => |p| SearchAnswer::decode(&p).map(|v| v.canonical()),
        "packed kad search answer" => {
            |p| SearchAnswer::decode(&packing::unpack(p, packing::MAX_CLIENT_UNPACKED)?).map(|v| v.canonical())
        }
        "keyword publish" => |p| KeywordPublish::decode(&p).map(|v| v.canonical()),
        "source publish" => |p| SourcePublish::decode(&p).map(|v| v.canonical()),
        "notes publish" => |p| NotesPublish::decode(&p).map(|v| v.canonical()),
        "publish answer" => |p| PublishAnswer::decode(&p).map(|v| v.canonical()),
        "firewall check" => |p| FirewallCheck::decode(&p).map(|v| v.canonical()),
        "firewall answer" => |p| kad_messages::decode_firewall_answer(&p).map(|v| v.canonical()),
        "find buddy" => |p| FindBuddy::decode(&p).map(|v| v.canonical()),
        "buddy offer" => |p| BuddyOffer::decode(&p).map(|v| v.canonical()),
        "kad callback request" => |p| CallbackRequest::decode(&p).map(|v| v.canonical()),
        "udp firewall answer" => |p| FirewallUdp::decode(&p).map(|v| v.canonical()),
        "pong" => |p| kad_messages::decode_pong(&p).map(|v| v.canonical()),
        // The search, or the tree of its expression when it carries one.
        "keyword search and its expression" => |p| {
            let search = KeywordSearch::decode(&p)?;
            if search.expression.is_empty() {
                Ok(search.canonical())
            } else {
                search_tree_codec::read(&mut PacketReader::of(&search.expression)).map(|v| v.canonical())
            }
        },
        "packed search results" => |p| SearchResults::decode(&packing::unpack_from_server(p)?).map(|v| v.canonical()),
        _ => return None,
    })
}
