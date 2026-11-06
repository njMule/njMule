// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's PublicIpv6Test, under its names, on routes of their own rather than the process's.

#![allow(clippy::unwrap_used)]

use njmule_engine::config::bind_interface::Binding;
use njmule_engine::config::proxy_settings::{ProxySettings, ProxyType};
use njmule_engine::net::public_ipv6;
use njmule_engine::net::routes::Routes;

#[test]
fn should_tell_the_address_of_a_direct_connection() {
    let told = Routes::direct().may_tell_public_ipv6();

    assert!(told);
}

#[test]
fn should_hide_the_address_while_a_proxy_carries_the_connections() {
    let socks = ProxySettings { kind: ProxyType::Socks, host: "proxy.lan".to_owned(), ..ProxySettings::default() };
    let routes = Routes::new(move || socks.clone(), || Binding::Any);

    let told = routes.may_tell_public_ipv6();

    assert!(!told);
    assert_eq!(public_ipv6::find(&routes), None);
}

#[test]
fn should_hide_the_address_while_njmule_keeps_to_an_interface_of_its_own() {
    let routes = Routes::new(ProxySettings::default, || Binding::Missing("wg0".to_owned()));

    let told = routes.may_tell_public_ipv6();

    assert!(!told);
}
