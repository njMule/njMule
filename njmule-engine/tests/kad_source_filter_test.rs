// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Which sources found on Kad are worth asking.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::download::download_manager::KadSource;
use njmule_engine::engine::network::kept_kad_sources;
use njmule_engine::kad::kad_node::{Buddy, Source};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::protocol::ed2k::nat_traversal_messages::OPTION_UTP;

const USER: [u8; 16] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF];
// 198.51.100.9 and 203.0.113.7: documentation addresses (RFC 5737).
const BUDDY_IP: u32 = 0xC633_6409;
const SOURCE_IP: u32 = 0xCB00_7107;

#[test]
fn should_keep_a_firewalled_source_met_through_the_routers() {
    let mut test = Filter::new();
    test.given_we_cannot_be_called_back();
    test.given_we_meet_sources_through_the_routers();
    test.given_a_firewalled_source_with_a_buddy(OPTION_UTP);

    test.when_the_source_is_checked();

    test.then_the_source_is_kept(true);
}

#[test]
fn should_drop_a_firewalled_source_without_utp_while_we_cannot_be_called_back() {
    let mut test = Filter::new();
    test.given_we_cannot_be_called_back();
    test.given_we_meet_sources_through_the_routers();
    test.given_a_firewalled_source_with_a_buddy(0);

    test.when_the_source_is_checked();

    test.then_the_source_is_kept(false);
}

// Without a buddy there is no one to pass the rendezvous on.
#[test]
fn should_drop_a_source_taking_direct_callbacks_while_we_cannot_be_called_back() {
    let mut test = Filter::new();
    test.given_we_cannot_be_called_back();
    test.given_we_meet_sources_through_the_routers();
    test.given_a_source_taking_direct_callbacks(OPTION_UTP);

    test.when_the_source_is_checked();

    test.then_the_source_is_kept(false);
}

#[test]
fn should_drop_a_firewalled_source_while_we_neither_are_called_back_nor_traverse() {
    let mut test = Filter::new();
    test.given_we_cannot_be_called_back();
    test.given_a_firewalled_source_with_a_buddy(OPTION_UTP);

    test.when_the_source_is_checked();

    test.then_the_source_is_kept(false);
}

#[test]
fn should_keep_a_firewalled_source_while_we_can_be_called_back() {
    let mut test = Filter::new();
    test.given_a_firewalled_source_with_a_buddy(0);

    test.when_the_source_is_checked();

    test.then_the_source_is_kept(true);
}

#[test]
fn should_keep_an_open_source_while_we_cannot_be_called_back() {
    let mut test = Filter::new();
    test.given_we_cannot_be_called_back();
    test.given_an_open_source();

    test.when_the_source_is_checked();

    test.then_the_source_is_kept(true);
}

struct Filter {
    callable: bool,
    traversing: bool,
    source: Option<Source>,
    kept: Vec<KadSource>,
}

impl Filter {
    fn new() -> Self {
        Self { callable: true, traversing: false, source: None, kept: Vec::new() }
    }

    fn given_we_cannot_be_called_back(&mut self) {
        self.callable = false;
    }

    fn given_we_meet_sources_through_the_routers(&mut self) {
        self.traversing = true;
    }

    fn given_a_firewalled_source_with_a_buddy(&mut self, options: u8) {
        let buddy = Buddy { ip: BUDDY_IP, udp_port: 4672, id: UInt128::from_big_endian([0xFE; 16]) };
        self.source = Some(source(options, Some(buddy), false));
    }

    fn given_a_source_taking_direct_callbacks(&mut self, options: u8) {
        self.source = Some(source(options, None, true));
    }

    fn given_an_open_source(&mut self) {
        self.source = Some(source(0, None, false));
    }

    fn when_the_source_is_checked(&mut self) {
        self.kept = kept_kad_sources(&[self.source.clone().unwrap()], self.callable, self.traversing);
    }

    fn then_the_source_is_kept(&self, expected: bool) {
        assert_eq!(self.kept.len(), usize::from(expected));
    }
}

fn source(options: u8, buddy: Option<Buddy>, direct_callback: bool) -> Source {
    Source {
        user_hash: USER,
        ip: SOURCE_IP,
        tcp_port: 4662,
        connect_options: options,
        udp_port: 4672,
        buddy,
        direct_callback,
        ipv6: None,
    }
}
