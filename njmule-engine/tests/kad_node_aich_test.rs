// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KadNodeAichTest, under its names: the AICH root a node tells for a file is taken only when it is
//! the one root, given by a third of the publishers at least.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::kad::kad_node;
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};

const ROOT: [u8; 20] = [0x0A; 20];
const OTHER: [u8; 20] = [0x0B; 20];

#[derive(Default)]
struct Aich {
    tags: Vec<Tag>,
    taken: Option<[u8; 20]>,
}

impl Aich {
    // The publish info holds the number of publishers in its third byte.
    fn given_the_publishers(&mut self, publishers: u64) {
        self.tags.push(Tag::Int { name: TagName::id(file_tag::PUBLISHINFO), value: publishers << 16 });
    }

    fn given_the_roots_told(&mut self, told: &[(u8, [u8; 20])]) {
        let mut out = PacketWriter::new();
        out.u8(u8::try_from(told.len()).unwrap());
        for (popularity, root) in told {
            out.u8(*popularity).bytes(root);
        }
        self.tags.push(Tag::Bsob { name: TagName::id(file_tag::KADAICHHASHRESULT), value: out.into_byte_array() });
    }

    fn given_a_cut_root_tag(&mut self) {
        self.tags.push(Tag::Bsob { name: TagName::id(file_tag::KADAICHHASHRESULT), value: vec![1, 1, 7] });
    }

    fn when_the_root_is_read(&mut self) {
        self.taken = kad_node::agreed_aich(&self.tags);
    }

    fn then_the_root_taken_is(&self, expected: Option<[u8; 20]>) {
        assert_eq!(self.taken, expected);
    }
}

#[test]
fn should_take_the_one_root_a_third_of_the_publishers_gave() {
    let mut aich = Aich::default();
    aich.given_the_publishers(9);
    aich.given_the_roots_told(&[(3, ROOT)]);

    aich.when_the_root_is_read();

    aich.then_the_root_taken_is(Some(ROOT));
}

#[test]
fn should_take_no_root_fewer_than_a_third_of_the_publishers_gave() {
    let mut aich = Aich::default();
    aich.given_the_publishers(12);
    aich.given_the_roots_told(&[(3, ROOT)]);

    aich.when_the_root_is_read();

    aich.then_the_root_taken_is(None);
}

#[test]
fn should_take_no_root_when_the_publishers_gave_two() {
    let mut aich = Aich::default();
    aich.given_the_publishers(4);
    aich.given_the_roots_told(&[(2, ROOT), (2, OTHER)]);

    aich.when_the_root_is_read();

    aich.then_the_root_taken_is(None);
}

#[test]
fn should_take_no_root_without_the_number_of_publishers() {
    let mut aich = Aich::default();
    aich.given_the_roots_told(&[(1, ROOT)]);

    aich.when_the_root_is_read();

    aich.then_the_root_taken_is(None);
}

#[test]
fn should_take_no_root_from_a_cut_answer() {
    let mut aich = Aich::default();
    aich.given_the_publishers(1);
    aich.given_a_cut_root_tag();

    aich.when_the_root_is_read();

    aich.then_the_root_taken_is(None);
}
