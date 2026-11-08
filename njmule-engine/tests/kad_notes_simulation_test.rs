// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNotesSimulationTest, under its names: notes travel through a network of simulated nodes, and
//! one node's comment reaches another's search.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use crate::kad_wire;

use std::sync::{Arc, Mutex};

use kad_wire::Wire;
use njmule_engine::api::file_comment::FileComment;
use njmule_engine::kad::kad_node::KadEvent;
use njmule_engine::kad::kad_notes::Note;
use njmule_engine::kad::uint128::UInt128;
use rand::RngExt;

const NODES: usize = 60;
const SIZE: u64 = 123_456_789;
// A small network may miss the node near the file at one try: like a user, the searcher tries again.
const SEARCHES: usize = 3;
const PUBLISHER: usize = 7;
const SEARCHER: usize = 42;

struct NotesSimulation {
    wire: Wire,
    // The comment the nodes tell on any file they publish notes of.
    comment: Arc<Mutex<Option<FileComment>>>,
    file: [u8; 16],
    found: Vec<Note>,
}

impl NotesSimulation {
    fn new() -> Self {
        Self { wire: Wire::new(20_261_014), comment: Arc::default(), file: [0; 16], found: Vec::new() }
    }

    fn given_a_network_that_found_itself(&mut self) {
        for at in 0..NODES {
            let ip = u32::from_be_bytes([30 + u8::try_from(at / 200).unwrap(), u8::try_from(at % 200).unwrap(), 9, 1]);
            let comment = Arc::clone(&self.comment);
            self.wire.add_node(ip, Arc::new(move |_| comment.lock().unwrap().clone()));
        }
        self.wire.bootstrap_from_each_other();
        // The second lookup of their own ids, after four hours, has the nodes know their nearest neighbours.
        self.wire.run(4 * 60 * 60 + 5 * 60);
    }

    // With few nodes, the file is picked so that a node besides the publisher and the searcher may store its notes.
    fn given_a_file_some_node_is_close_to(&mut self) {
        self.file = loop {
            let hash: [u8; 16] = self.wire.random.random();
            if self.wire.some_node_near(UInt128::from_big_endian(hash), &[PUBLISHER, SEARCHER]) {
                break hash;
            }
        };
    }

    fn given_the_node_at_publishes_its_comment(&mut self, index: usize, comment: FileComment) {
        *self.comment.lock().unwrap() = Some(comment);
        assert!(self.wire.nodes[index].publish_note(self.file, "Night Sky.zip", SIZE));
        // A note publish lives 100 s.
        self.wire.run(110);
    }

    fn when_the_node_at_searches_for_notes(&mut self, index: usize) {
        for _ in 0..SEARCHES {
            if !self.found.is_empty() {
                break;
            }
            assert!(self.wire.nodes[index].find_notes(self.file, SIZE));
            self.wire.run(60);
            for event in self.wire.nodes[index].take_events() {
                if let KadEvent::Notes { notes, .. } = event {
                    self.found.extend(notes);
                }
            }
        }
    }

    fn then_some_nodes_store_the_note(&self) {
        let storing = self.wire.nodes.iter().filter(|node| node.indexed_notes() > 0).count();
        assert!(storing > 0, "nodes that store the note");
    }

    fn then_the_search_found_the_note_of(&self, index: usize, comment: &FileComment) {
        assert!(!self.found.is_empty(), "the note is found");
        let publisher = self.wire.nodes[index].id();
        for note in &self.found {
            assert_eq!(note.publisher, publisher);
            assert_eq!(note.file_name, "Night Sky.zip");
            assert_eq!(note.size, SIZE);
            assert_eq!(&note.comment, comment);
        }
    }
}

#[test]
fn should_find_the_note_another_node_published() {
    let mut simulation = NotesSimulation::new();
    simulation.given_a_network_that_found_itself();
    simulation.given_a_file_some_node_is_close_to();
    simulation.given_the_node_at_publishes_its_comment(PUBLISHER, FileComment::of("the real thing", 5));

    simulation.when_the_node_at_searches_for_notes(SEARCHER);

    simulation.then_some_nodes_store_the_note();
    simulation.then_the_search_found_the_note_of(PUBLISHER, &FileComment::of("the real thing", 5));
}
