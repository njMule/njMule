// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodeKeywordResultsTest, under its names: a node may answer all it holds under the keyword, so
//! the results are checked again against the whole search.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod kad_lab;

use std::time::Duration;

use kad_lab::{KadLab, PORT, ip};
use njmule_engine::kad::kad_node::{KadEvent, KadNode};
use njmule_engine::kad::keywords;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::kad::kad_messages::{Answer, Entry, SearchAnswer};
use njmule_engine::protocol::tag::file_tag;
use njmule_engine::search::search_expr::{Comparison, SearchExpr};

const PEER: u32 = ip(20, 1, 1, 1);
const WORD: &str = "ubuntu";

struct KeywordResults {
    lab: KadLab,
    node: KadNode,
}

impl KeywordResults {
    fn new() -> Self {
        let mut lab = KadLab::new();
        let mut settings = kad_lab::settings();
        settings.udp_port = PORT;
        let mut node = lab.node(settings);
        node.add_contacts(&kad_lab::one_contact(keywords::id(WORD), PEER));
        Self { lab, node }
    }

    fn given_a_search_for(&mut self, expression: SearchExpr) {
        self.search(Some(expression));
    }

    fn given_a_search_without_an_expression(&mut self) {
        self.search(None);
    }

    fn when_the_node_finds(&mut self, files: &[(&str, u64)]) {
        let target = keywords::id(WORD);
        let results: Vec<Entry> = files
            .iter()
            .map(|(name, size)| kad_lab::file(UInt128::random(&mut self.lab.random), name, *size))
            .collect();
        let answer = SearchAnswer { sender: target, target, results };
        self.node.received(PEER, PORT, &answer.encode().unwrap(), 0, 0);
    }

    fn then_the_files_found_are(&mut self, names: &[&str]) {
        let found: Vec<String> = self
            .node
            .take_events()
            .into_iter()
            .flat_map(|event| match event {
                KadEvent::Files { results, .. } => results.into_iter().map(|result| result.name).collect(),
                _ => Vec::new(),
            })
            .collect();
        assert_eq!(found, names);
    }

    fn search(&mut self, expression: Option<SearchExpr>) {
        assert!(self.node.search_keyword(WORD, expression).is_some());
        let answer = Answer { target: keywords::id(WORD), contacts: Vec::new() };
        self.node.received(PEER, PORT, &answer.encode().unwrap(), 0, 0);
        self.lab.advance(Duration::from_secs(4));
        self.node.tick();
    }
}

#[test]
fn should_keep_only_the_files_matching_the_whole_expression() {
    let mut results = KeywordResults::new();
    results.given_a_search_for(SearchExpr::and(
        SearchExpr::not(SearchExpr::keyword(WORD), SearchExpr::keyword("server")),
        SearchExpr::NumericAttr { tag: file_tag::FILESIZE, comparison: Comparison::Greater, value: 500 },
    ));

    results.when_the_node_finds(&[
        ("ubuntu desktop.iso", 1_000),
        ("ubuntu server.iso", 1_000),
        ("ubuntu small.iso", 100),
    ]);

    results.then_the_files_found_are(&["ubuntu desktop.iso"]);
}

#[test]
fn should_keep_only_the_files_with_the_word_looked_up_without_an_expression() {
    let mut results = KeywordResults::new();
    results.given_a_search_without_an_expression();

    results.when_the_node_finds(&[("ubuntu desktop.iso", 1_000), ("kubuntu.iso", 1_000)]);

    results.then_the_files_found_are(&["ubuntu desktop.iso"]);
}
