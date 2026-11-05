// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's answers in `golden/hashes.jsonl`, given again: how files split into eD2K parts, how AICH trees
//! are built from their block hashes and what AICH recovery data proves.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use njmule_engine::files::hash::aich_recovery;
use njmule_engine::files::hash::aich_tree::AichTree;
use njmule_engine::files::hash::ed2k;
use serde_json::{Map, Value, json};

const CORPUS: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../njmule-core/src/test/resources/golden/hashes.jsonl");

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

fn hash20(value: &Value) -> [u8; 20] {
    hex::decode(value.as_str().unwrap()).unwrap().try_into().unwrap()
}

// What the Java engine's HashCorpusTest answers for the input of `recorded`.
fn answered(recorded: &Value) -> Value {
    let kind = recorded["kind"].as_str().unwrap();
    let fields: &[&str] = match kind {
        "ed2k-layout" => &["kind", "size"],
        "aich-tree" => &["kind", "size", "blocks"],
        "aich-recovery" => &["kind", "size", "part", "master", "in"],
        other => panic!("unknown kind {other}"),
    };
    let mut answer: Map<String, Value> = fields.iter().map(|f| ((*f).to_owned(), recorded[*f].clone())).collect();
    let size = recorded["size"].as_u64().unwrap();
    match kind {
        "ed2k-layout" => match ed2k::check_size(size) {
            Ok(()) => {
                let parts = ed2k::part_count(size);
                let mut layout = json!({
                    "parts": parts,
                    "ed2kParts": ed2k::ed2k_part_count(size),
                    "hashSet": ed2k::hash_set_size(size),
                });
                // An empty file has no last part.
                if parts > 0 {
                    layout["lastPartLength"] = json!(ed2k::part_length(size, parts - 1));
                }
                answer.insert("value".into(), layout);
            }
            Err(_) => {
                answer.insert("error".into(), json!("invalid"));
            }
        },
        "aich-tree" => {
            let blocks = recorded["blocks"].as_array().unwrap().iter().map(hash20).collect();
            let tree = AichTree::from_block_hashes(size, blocks).unwrap();
            let parts: Vec<String> = tree.part_hashes().iter().map(hex::encode).collect();
            answer.insert("value".into(), json!({"master": hex::encode(tree.master_hash()), "parts": parts}));
        }
        _ => {
            let part = u32::try_from(recorded["part"].as_u64().unwrap()).unwrap();
            let data = hex::decode(recorded["in"].as_str().unwrap()).unwrap();
            match aich_recovery::verify(size, part, &hash20(&recorded["master"]), &data) {
                Some(blocks) => {
                    answer.insert("value".into(), json!(blocks.iter().map(hex::encode).collect::<Vec<_>>()));
                }
                None => {
                    answer.insert("error".into(), json!("unproven"));
                }
            }
        }
    }
    Value::Object(answer)
}
