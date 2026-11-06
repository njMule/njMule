// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! HTTP clients that take the route of the other outgoing connections: from the network interface chosen and through
//! the proxy of the preferences, HTTP or SOCKS 5 with its user name and password. They follow no redirect and unpack
//! no body, which are the caller's to check.
//!
//! TLS is rustls on ring, and the certificates are checked against the system's: the default provider of reqwest,
//! aws-lc, carries the OpenSSL license and needs cmake and NASM to build on Windows.

use std::io;
use std::net::IpAddr;
use std::sync::Once;
use std::time::Duration;

use percent_encoding::{NON_ALPHANUMERIC, utf8_percent_encode};
use reqwest::{Client, Proxy, redirect};

use crate::api::nj_mule_info;
use crate::config::proxy_settings::ProxyType;
use crate::net::routes::Routes;

pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);

/// A client on the route chosen now; a client for each use, as the route may have changed since the last one. An error
/// while the interface chosen is gone.
pub fn client(routes: &Routes) -> io::Result<Client> {
    static PROVIDER: Once = Once::new();
    PROVIDER.call_once(|| {
        // Another part of the process may have chosen one already: then that one serves.
        let _ = rustls::crypto::ring::default_provider().install_default();
    });
    let local = routes.local_address()?;
    let mut builder = Client::builder()
        .redirect(redirect::Policy::none())
        .connect_timeout(CONNECT_TIMEOUT)
        .user_agent(format!("{}/{}", nj_mule_info::NAME, nj_mule_info::version()))
        .no_proxy();
    if let Some(local) = local {
        builder = builder.local_address(IpAddr::V4(local));
    }
    let proxy = routes.proxy();
    let host = if proxy.host.contains(':') { format!("[{}]", proxy.host) } else { proxy.host.clone() };
    let at = match proxy.kind {
        ProxyType::None => None,
        ProxyType::Http => Some(format!("http://{host}:{}", proxy.port)),
        // The proxy looks the name up, as for the other connections.
        ProxyType::Socks if proxy.user.is_empty() => Some(format!("socks5h://{host}:{}", proxy.port)),
        ProxyType::Socks => Some(format!(
            "socks5h://{}:{}@{host}:{}",
            utf8_percent_encode(&proxy.user, NON_ALPHANUMERIC),
            utf8_percent_encode(&proxy.password, NON_ALPHANUMERIC),
            proxy.port
        )),
    };
    if let Some(at) = at {
        builder = builder.proxy(Proxy::all(at).map_err(io::Error::other)?);
    }
    builder.build().map_err(|e| io::Error::other(format!("cannot start the HTTP client: {e}")))
}
