// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's answers in `golden/search-expressions.jsonl`, given again: every text parsed as the corpus
//! says, with the tree a server that takes 64-bit sizes gets for it, or the reason it is refused.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

mod canonical;

use canonical::Canonical;
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::search::search_parser::parse;
use njmule_engine::search::search_tree_codec;
use serde_json::{Map, Value, json};

const CORPUS: &str =
    concat!(env!("CARGO_MANIFEST_DIR"), "/../njmule-core/src/test/resources/golden/search-expressions.jsonl");

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

// What the Java engine's SearchParserCorpusTest answers for the text of `recorded`.
fn answered(recorded: &Value) -> Value {
    let mut answer = Map::new();
    answer.insert("kind".into(), recorded["kind"].clone());
    answer.insert("text".into(), recorded["text"].clone());
    match parse(recorded["text"].as_str().unwrap()) {
        Ok(search) => {
            let mut wire = PacketWriter::new();
            search_tree_codec::write(&mut wire, &search, true).unwrap();
            answer.insert("value".into(), search.canonical());
            answer.insert("wire".into(), json!(hex::encode(wire.into_byte_array())));
        }
        Err(e) => {
            answer.insert("error".into(), json!(e.reason().name()));
        }
    }
    Value::Object(answer)
}
