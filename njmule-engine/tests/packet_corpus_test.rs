// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Replays `golden/packets.jsonl`: every packet kind decoded into canonical JSON as recorded.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use crate::canonical;

use canonical::packet_decoders::{Decoder, decoder};
use njmule_engine::protocol::packet::Packet;
use serde_json::{Map, Value, json};

const CORPUS: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/golden/packets.jsonl");

#[test]
fn should_give_the_recorded_answers() {
    let text = std::fs::read_to_string(CORPUS).expect("the golden corpus");
    let mut differences = Vec::new();
    let mut unknown = Vec::new();
    let mut cases = 0;
    for line in text.lines().filter(|line| !line.trim().is_empty()) {
        let recorded: Value = serde_json::from_str(line).unwrap();
        let kind = recorded["kind"].as_str().unwrap();
        let Some(decode) = decoder(kind) else {
            unknown.push(kind.to_owned());
            continue;
        };
        let answer = answered(&recorded, decode);
        cases += 1;
        if answer != recorded {
            differences.push(format!("recorded {recorded}\nanswered {answer}"));
        }
    }
    assert!(unknown.is_empty(), "unknown packet kinds in corpus: {unknown:?}");
    assert!(cases > 0, "no case in {CORPUS}");
    assert!(differences.is_empty(), "{} of {cases} cases differ:\n{}", differences.len(), differences.join("\n"));
}

// Expected answer from the golden corpus for the input of `recorded`.
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
