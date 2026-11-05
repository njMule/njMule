// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's answers in `golden/ed2k-links.jsonl`, given again: every text parsed as the corpus says, the link
//! in the form of the Java engine's `Canonical`, or its refusal.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use njmule_engine::api::ed2k_link::{Ed2kLink, Source};
use serde_json::{Map, Value, json};

const CORPUS: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../njmule-core/src/test/resources/golden/ed2k-links.jsonl");

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

// What the Java engine's Ed2kLinkCorpusTest answers for the text of `recorded`.
fn answered(recorded: &Value) -> Value {
    let mut answer = Map::new();
    answer.insert("kind".into(), recorded["kind"].clone());
    answer.insert("text".into(), recorded["text"].clone());
    match Ed2kLink::try_parse(recorded["text"].as_str().unwrap()) {
        Some(link) => answer.insert("value".into(), canonical(&link)),
        None => answer.insert("error".into(), json!("invalid")),
    };
    Value::Object(answer)
}

fn canonical(link: &Ed2kLink) -> Value {
    match link {
        Ed2kLink::File(file) => json!({
            "@type": "FileLink",
            "name": file.name,
            "size": file.size,
            "hash": hex::encode(file.hash),
            "aichHash": file.aich_hash.map(hex::encode),
            "partHashes": file.part_hashes.iter().map(hex::encode).collect::<Vec<_>>(),
            "sources": file.sources.iter().map(source).collect::<Vec<_>>(),
            "httpSources": file.http_sources,
        }),
        Ed2kLink::Server(server) => json!({"@type": "ServerLink", "host": server.host, "port": server.port}),
        Ed2kLink::ServerList(list) => json!({"@type": "ServerListLink", "url": list.url}),
        Ed2kLink::NodesList(list) => json!({"@type": "NodesListLink", "url": list.url}),
        Ed2kLink::Search(search) => json!({"@type": "SearchLink", "term": search.term}),
    }
}

fn source(source: &Source) -> Value {
    json!({"host": source.host, "port": source.port})
}
