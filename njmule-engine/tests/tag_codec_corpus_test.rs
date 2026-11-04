// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's answers in `golden/tag-codec.jsonl`, given again: every case read and written as the corpus
//! says (`njmule-core/src/test/resources/golden/README.md`).

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use njmule_engine::protocol::io::{PacketReader, PacketWriter, StringEncoding};
use njmule_engine::protocol::tag::tag_codec::{
    read_ed2k, read_ed2k_list, read_kad_list, write_ed2k_list, write_kad_list,
};
use njmule_engine::protocol::tag::{Ed2kTagFormat, Tag};
use serde_json::{Map, Value, json};

const CORPUS: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../njmule-core/src/test/resources/golden/tag-codec.jsonl");
const MAX_COUNT: usize = 64;

#[test]
fn should_give_the_answers_of_the_java_engine() {
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

// What the Java engine's TagCodecCorpusTest answers for the input of `recorded`.
fn answered(recorded: &Value) -> Value {
    let kind = recorded["kind"].as_str().unwrap();
    let data = hex::decode(recorded["in"].as_str().unwrap()).unwrap();
    let mut answer = Map::new();
    answer.insert("kind".into(), json!(kind));
    answer.insert("in".into(), recorded["in"].clone());
    if let Some(count) = recorded.get("count") {
        answer.insert("count".into(), count.clone());
    }
    let mut input = PacketReader::of(&data);
    let read = match kind {
        "ed2k-tag" => read_ed2k(&mut input).map(|tag| vec![tag]),
        "ed2k-tags" => read_ed2k_list(&mut input, recorded["count"].as_u64().unwrap(), MAX_COUNT),
        "kad-tags" => read_kad_list(&mut input),
        other => panic!("unknown kind {other}"),
    };
    match read {
        Ok(tags) => {
            answer.insert("used".into(), json!(input.position()));
            match kind {
                "ed2k-tag" => {
                    answer.insert("new".into(), ed2k(&tags, Ed2kTagFormat::New, StringEncoding::Utf8));
                    answer.insert("old".into(), ed2k(&tags, Ed2kTagFormat::Old, StringEncoding::Utf8BomIfNeeded));
                }
                "ed2k-tags" => {
                    answer.insert("new".into(), ed2k(&tags, Ed2kTagFormat::New, StringEncoding::Utf8));
                }
                _ => {
                    let mut out = PacketWriter::new();
                    answer.insert("kad".into(), written(write_kad_list(&mut out, &tags).map(|()| out)));
                }
            }
        }
        Err(_) => {
            answer.insert("error".into(), json!("decode"));
        }
    }
    Value::Object(answer)
}

fn ed2k(tags: &[Tag], format: Ed2kTagFormat, encoding: StringEncoding) -> Value {
    let mut out = PacketWriter::new();
    written(write_ed2k_list(&mut out, tags, format, encoding).map(|()| out))
}

// The bytes written in hex, or null where writing them is refused.
fn written<E>(result: Result<PacketWriter, E>) -> Value {
    match result {
        Ok(out) => json!(hex::encode(out.to_byte_array())),
        Err(_) => Value::Null,
    }
}
