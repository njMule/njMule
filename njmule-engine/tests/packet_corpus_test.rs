// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's answers in `golden/packets.jsonl`, given again by the messages ported so far: every case of their
//! kinds decoded as the corpus says. The kinds still to port are named, so a kind new to the corpus fails until it is
//! ported or named.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

mod canonical;

use canonical::Canonical;
use njmule_engine::protocol::ed2k::server_datagrams::{self, Description, Status};
use njmule_engine::protocol::ed2k::server_messages::{
    self, FoundSources, IdChange, Login, SearchResults, ServerIdent, ServerStatus,
};
use njmule_engine::protocol::io::DecodeError;
use njmule_engine::protocol::packet::{Packet, packing};
use serde_json::{Map, Value, json};

const CORPUS: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../njmule-core/src/test/resources/golden/packets.jsonl");

const NOT_PORTED: &[&str] = &[
    "hello",
    "hello answer",
    "file name",
    "file status",
    "hash set",
    "request parts",
    "request parts, 64 bit",
    "sending part",
    "compressed part",
    "source exchange answer",
    "source exchange request",
    "packed source exchange answer",
    "reask ping",
    "reask ack",
    "reask through a buddy",
    "callback",
    "direct callback request",
    "udp check request",
    "file comment",
    "chat message",
    "bootstrap answer",
    "kad hello",
    "kad request",
    "kad answer",
    "keyword search and its expression",
    "source search",
    "notes search",
    "search answer",
    "packed kad search answer",
    "keyword publish",
    "source publish",
    "notes publish",
    "publish answer",
    "firewall check",
    "firewall answer",
    "find buddy",
    "buddy offer",
    "kad callback request",
    "udp firewall answer",
    "pong",
];

type Decoder = fn(Packet) -> Result<Value, DecodeError>;

fn decoder(kind: &str) -> Option<Decoder> {
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
        "packed search results" => |p| SearchResults::decode(&packing::unpack_from_server(p)?).map(|v| v.canonical()),
        _ => return None,
    })
}

#[test]
fn should_give_the_answers_of_the_java_engine() {
    let text = std::fs::read_to_string(CORPUS).expect("the corpus of the Java engine");
    let mut differences = Vec::new();
    let mut unknown = Vec::new();
    let mut cases = 0;
    for line in text.lines().filter(|line| !line.trim().is_empty()) {
        let recorded: Value = serde_json::from_str(line).unwrap();
        let kind = recorded["kind"].as_str().unwrap();
        let Some(decode) = decoder(kind) else {
            if !NOT_PORTED.contains(&kind) {
                unknown.push(kind.to_owned());
            }
            continue;
        };
        let answer = answered(&recorded, decode);
        cases += 1;
        if answer != recorded {
            differences.push(format!("recorded {recorded}\nanswered {answer}"));
        }
    }
    assert!(unknown.is_empty(), "kinds neither ported nor named: {unknown:?}");
    assert!(cases > 0, "no case in {CORPUS}");
    assert!(differences.is_empty(), "{} of {cases} cases differ:\n{}", differences.len(), differences.join("\n"));
}

// What the Java engine's PacketCorpusTest answers for the input of `recorded`.
fn answered(recorded: &Value, decode: Decoder) -> Value {
    let byte = |field: &str| u8::try_from(recorded[field].as_u64().unwrap()).unwrap();
    let packet = Packet::of(byte("protocol"), byte("opcode"), hex::decode(recorded["in"].as_str().unwrap()).unwrap());
    let mut answer = Map::new();
    for field in ["kind", "in", "protocol", "opcode"] {
        answer.insert(field.into(), recorded[field].clone());
    }
    match decode(packet) {
        Ok(value) => answer.insert("value".into(), value),
        Err(_) => answer.insert("error".into(), json!("decode")),
    };
    Value::Object(answer)
}
