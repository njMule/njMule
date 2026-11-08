// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's answers in `golden/packets.jsonl`, given again by the messages ported so far: every case of their
//! kinds decoded as the corpus says. The kinds still to port are named, so a kind new to the corpus fails until it is
//! ported or named.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use crate::canonical;

use canonical::packet_decoders::{Decoder, decoder};
use njmule_engine::protocol::packet::Packet;
use serde_json::{Map, Value, json};

const CORPUS: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../njmule-core/src/test/resources/golden/packets.jsonl");

const NOT_PORTED: &[&str] = &[];

#[test]
fn should_give_the_recorded_answers() {
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
