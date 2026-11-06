// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's answers in `golden/kad-keywords.jsonl`, given again: the keywords Kad publishes a name under with
//! their ids, and every UTF-16 unit the network's lower case changes.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing)]

use njmule_engine::kad::{kad_lower_case, keywords};
use serde_json::{Map, Value, json};

const CORPUS: &str =
    concat!(env!("CARGO_MANIFEST_DIR"), "/../njmule-core/src/test/resources/golden/kad-keywords.jsonl");

#[test]
fn should_give_the_recorded_answers() {
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
    assert!(cases > 1, "no case in {CORPUS}");
    assert!(differences.is_empty(), "{} of {cases} cases differ:\n{}", differences.len(), differences.join("\n"));
}

// What the Java engine's KeywordsCorpusTest answers for the input of `recorded`.
fn answered(recorded: &Value) -> Value {
    let mut answer = Map::new();
    answer.insert("kind".into(), recorded["kind"].clone());
    if recorded["kind"] == "lower-case" {
        let changed: Vec<Value> = (0..=u16::MAX)
            .filter_map(|unit| {
                let lower = kad_lower_case::lower_unit(unit);
                (lower != unit).then(|| json!([unit, lower]))
            })
            .collect();
        answer.insert("value".into(), Value::Array(changed));
    } else {
        let text = recorded["text"].as_str().unwrap();
        answer.insert("text".into(), json!(text));
        let words: Vec<Value> = keywords::of(text)
            .iter()
            .map(|word| json!({"word": word, "id": hex::encode(keywords::id(word).to_big_endian())}))
            .collect();
        answer.insert("value".into(), Value::Array(words));
    }
    Value::Object(answer)
}
