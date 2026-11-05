// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The comment a client sends with a file name; the Java engine tests it through its download session.

#![allow(clippy::unwrap_used)]

use njmule_engine::api::file_comment::FileComment;
use njmule_engine::protocol::ed2k::file_description;
use njmule_engine::protocol::ed2k::hello::Hello;
use njmule_engine::protocol::tag::{Tag, TagName, client_tag};

fn hello_with_misc_options(misc_options1: u64) -> Hello {
    let tags = vec![Tag::Int { name: TagName::id(client_tag::EMULE_MISCOPTIONS1), value: misc_options1 }];
    Hello { user_hash: [0; 16], client_id: 0, tcp_port: 4662, tags, server_ip: 0, server_port: 0 }
}

#[test]
fn should_round_trip_a_comment_and_its_rating() {
    let comment = FileComment::new("good quality, complete", 4).unwrap();

    let decoded = file_description::decode(&file_description::encode(&comment).unwrap());

    assert_eq!(decoded, Ok(comment));
}

#[test]
fn should_send_comments_only_to_clients_that_take_them() {
    assert!(file_description::accepted(&hello_with_misc_options(1 << 4)));
    assert!(!file_description::accepted(&hello_with_misc_options(0)));
}
