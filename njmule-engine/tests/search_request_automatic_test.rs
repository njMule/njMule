// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's SearchRequestAutomaticTest, under its names: the network an automatic search goes to, by what is
//! connected and the server connected to.

use njmule_engine::api::search_request::{self, Scope};
use njmule_engine::api::server_info::ServerInfo;
use njmule_engine::server::server_list::Priority;

fn server(users: u64, files: u64, is_static: bool) -> ServerInfo {
    ServerInfo {
        address: "81.2.3.4:4661".to_owned(),
        name: "Server".to_owned(),
        description: String::new(),
        users,
        files,
        max_users: 0,
        failed: 0,
        priority: Priority::Normal,
        ping: 0,
        soft_files: 0,
        hard_files: 0,
        low_id_users: 0,
        version: String::new(),
        obfuscation: false,
        is_static,
        country: None,
    }
}

#[test]
fn should_search_nowhere_without_a_network() {
    assert_eq!(search_request::automatic(false, false, None, 10), None);
}

#[test]
fn should_search_kad_alone() {
    assert_eq!(search_request::automatic(false, true, None, 10), Some(Scope::Kad));
}

#[test]
fn should_search_the_server_alone_even_unknown() {
    assert_eq!(search_request::automatic(true, false, None, 10), Some(Scope::Server));
}

#[test]
fn should_prefer_kad_to_an_ordinary_server() {
    let server = server(10_000, 1_000_000, false);

    assert_eq!(search_request::automatic(true, true, Some(&server), 10), Some(Scope::Kad));
}

#[test]
fn should_stay_on_a_static_server() {
    let server = server(10, 10, true);

    assert_eq!(search_request::automatic(true, true, Some(&server), 10), Some(Scope::Server));
}

#[test]
fn should_stay_on_a_big_server_only_in_a_short_list() {
    for (users, files, servers, expected) in [
        (40_001, 5_000_001, 39, Scope::Server),
        (40_000, 5_000_001, 39, Scope::Kad),
        (1_999_999, 5_000_001, 39, Scope::Server),
        (2_000_000, 5_000_001, 39, Scope::Kad),
        (40_001, 5_000_000, 39, Scope::Kad),
        (40_001, 5_000_001, 40, Scope::Kad),
    ] {
        let server = server(users, files, false);

        let scope = search_request::automatic(true, true, Some(&server), servers);

        assert_eq!(scope, Some(expected), "{users} users, {files} files, {servers} servers");
    }
}
