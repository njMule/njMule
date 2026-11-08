// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The web interface: the interface of njmule-ui behind a password, and the remote-control API it calls with the login
//! session. Here the checks of its requests: their host, the origin of their changes and the encodings they take.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

use url::Url;

// Names under which a browser on this computer reaches the interface; other computers use its address or a name of the
// settings. Any other Host header may be a DNS-rebinding attempt.
const LOOPBACK_NAMES: [&str; 3] = ["localhost", "127.0.0.1", "[::1]"];

// Synology's QuickConnect names an address of a NAS a-b-c-d.<id>.direct.quickconnect.to, and its DNS answers with that
// address only: such a name of the address a request came to cannot be rebound elsewhere.
const QUICKCONNECT: &str = ".direct.quickconnect.to";

/// Whether a request with this Host header may be answered: a name of this computer, a name of the settings, or, while
/// other computers may connect, an address or a QuickConnect name of the address `reached`.
pub fn trusted_host(host: Option<&str>, lan: bool, names: &[String], reached: IpAddr) -> bool {
    let Some(host) = host else {
        return false;
    };
    let name = without_port(host);
    LOOPBACK_NAMES.contains(&name.as_str())
        || names.contains(&name)
        || (lan && (is_address(&name) || is_quickconnect_name_of(&name, reached)))
}

/// Whether a change may come from a page of this `origin`: browsers send it with every POST, and a different one is
/// another site's form. A name of the settings stands for the pages on any port and web scheme: a reverse proxy shows
/// them on its own, and may pass its own address as Host.
pub fn trusted_origin(origin: Option<&str>, host: Option<&str>, names: &[String]) -> bool {
    let Some(origin) = origin else {
        return true;
    };
    // An origin is a scheme and an authority, with nothing after them.
    let (Ok(_), Some((scheme, authority))) = (Url::parse(origin), origin.split_once("://")) else {
        return false;
    };
    if authority.is_empty() || authority.contains(['/', '?', '#']) {
        return false;
    }
    host.is_some_and(|host| authority.eq_ignore_ascii_case(host))
        || ((scheme.eq_ignore_ascii_case("http") || scheme.eq_ignore_ascii_case("https"))
            && !authority.contains('@')
            && names.contains(&without_port(authority)))
}

/// Whether a client with this Accept-Encoding takes gzip (RFC 9110): a coding of quality 0 is refused.
pub fn takes_gzip(accept_encoding: Option<&str>) -> bool {
    accept_encoding.is_some_and(|header| {
        header.split(',').any(|item| {
            let mut parts = item.split(';').map(str::trim);
            let coding = parts.next().unwrap_or_default();
            let refused = parts.filter_map(|parameter| parameter.split_once('=')).any(|(name, value)| {
                name.trim().eq_ignore_ascii_case("q") && value.trim().parse::<f32>().is_ok_and(|q| q <= 0.0)
            });
            !refused && (coding.eq_ignore_ascii_case("gzip") || coding == "*")
        })
    })
}

// The name of host[:port], in lower case.
fn without_port(authority: &str) -> String {
    let name = match (authority.rfind(':'), authority.rfind(']')) {
        (Some(colon), Some(bracket)) if colon > bracket => authority.get(..colon),
        (Some(colon), None) => authority.get(..colon),
        _ => Some(authority),
    };
    name.unwrap_or(authority).to_ascii_lowercase()
}

// An IP address literal, which no DNS answer can point elsewhere.
fn is_address(name: &str) -> bool {
    match name.strip_prefix('[').and_then(|inner| inner.strip_suffix(']')) {
        Some(inner) => inner.parse::<Ipv6Addr>().is_ok(),
        None => name.parse::<Ipv4Addr>().is_ok(),
    }
}

fn is_quickconnect_name_of(name: &str, reached: IpAddr) -> bool {
    let IpAddr::V4(reached) = reached.to_canonical() else {
        return false;
    };
    let Some((dashed, id)) = name.strip_suffix(QUICKCONNECT).and_then(|rest| rest.split_once('.')) else {
        return false;
    };
    let named = !id.is_empty() && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-');
    let parts: Vec<&str> = dashed.split('-').collect();
    let digits = parts.len() == 4 && parts.iter().all(|part| (1..=3).contains(&part.len()));
    named && digits && parts.join(".").parse::<Ipv4Addr>().is_ok_and(|address| address == reached)
}
