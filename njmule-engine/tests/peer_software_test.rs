// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's PeerSoftwareTest, under their names.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use njmule_engine::api::nj_mule_info;
use njmule_engine::peer::peer_software;
use njmule_engine::protocol::ed2k::client_version;
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::tag::{Tag, TagName, client_tag};
use rand::Rng;

const HASH: [u8; 16] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF];

fn hello(tags: Vec<Tag>) -> Hello {
    Hello { user_hash: HASH, client_id: 0, tcp_port: 4662, tags, server_ip: 0, server_port: 0 }
}

fn version_tag(version: u32) -> Tag {
    Tag::Int { name: TagName::id(client_tag::EMULE_VERSION), value: u64::from(version) }
}

#[test]
fn should_name_a_peer_without_version_tag_e_donkey_compatible() {
    assert_eq!(peer_software::describe(&hello(Vec::new())), "eDonkey compatible");
}

#[test]
fn should_name_the_software_from_its_version_tag() {
    for (compat, major, minor, update, name) in [
        (0, 0, 50, 1, "eMule v0.50b"),
        (0, 0, 70, 7, "eMule v0.70h"),
        (3, 2, 3, 1, "aMule v2.3.1"),
        (40, 1, 2, 3, "eMule compatible (client 40) v1.2.3"),
    ] {
        let tag = version_tag(client_version::encode(compat, major, minor, update));

        assert_eq!(peer_software::describe(&hello(vec![tag])), name);
    }
}

#[test]
fn should_add_the_mod_a_modified_client_names() {
    let tags = vec![
        version_tag(client_version::encode(0, 0, 50, 0)),
        Tag::String { name: TagName::id(client_tag::MOD_VERSION), value: "MorphXT v12.7".to_owned() },
    ];

    assert_eq!(peer_software::describe(&hello(tags)), "eMule v0.50a [MorphXT v12.7]");
}

#[test]
fn should_keep_control_and_direction_characters_out_of_the_mod() {
    let tags = vec![
        version_tag(client_version::encode(0, 0, 50, 0)),
        Tag::String { name: TagName::id(client_tag::MOD_VERSION), value: " Evil\u{202E}gnp.exe\n".to_owned() },
    ];

    assert_eq!(peer_software::describe(&hello(tags)), "eMule v0.50a [Evilgnp.exe]");
}

#[test]
fn should_name_us_by_the_full_version_our_hello_tells() {
    let ours = Ours { nick: "njMule user", udp_port: 4672, kad_port: 4673, misc_options1: 0, misc_options2: 0 };

    let described = peer_software::describe(&Hello::of(HASH, 0, 4662, &ours, 0, 0));

    assert_eq!(described, format!("njMule {}", nj_mule_info::version()));
}

#[test]
fn should_describe_any_version_tag() {
    let mut random = rand::rng();
    for _ in 0..10_000 {
        let tag = version_tag(random.next_u32());

        assert!(!peer_software::describe(&hello(vec![tag])).trim().is_empty());
    }
}
