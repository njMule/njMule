// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! HTTP clients that take the route of the other outgoing connections: from the network interface chosen and through
//! the proxy of the preferences, HTTP or SOCKS 5 with its user name and password. They follow no redirect and unpack
//! no body, which are the caller's to check.
//!
//! TLS is rustls on ring, and the certificates are checked against the system's (falling back to Mozilla's bundled root
//! certificates on minimal Linux environments without a system CA bundle): the default provider of reqwest, aws-lc,
//! carries the OpenSSL license and needs cmake and NASM to build on Windows.

use std::io;
use std::net::IpAddr;
use std::sync::{Arc, Once};
use std::time::Duration;

use percent_encoding::{NON_ALPHANUMERIC, utf8_percent_encode};
use reqwest::{Client, ClientBuilder, Proxy, redirect};
use rustls_platform_verifier::BuilderVerifierExt;

use crate::api::nj_mule_info;
use crate::config::proxy_settings::ProxyType;
use crate::net::routes::Routes;

pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);

/// A client on the route chosen now; a client for each use, as the route may have changed since the last one. An error
/// while the interface chosen is gone.
pub fn client(routes: &Routes) -> io::Result<Client> {
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
    build_client(builder)
}

/// A client for a device of this computer's LAN, such as the router's web server: straight, never through the proxy,
/// and done with a request, its body included, within `timeout`.
pub fn lan_client(timeout: Duration) -> io::Result<Client> {
    build_client(
        Client::builder()
            .redirect(redirect::Policy::none())
            .connect_timeout(timeout)
            .timeout(timeout)
            .user_agent(format!("{}/{}", nj_mule_info::NAME, nj_mule_info::version()))
            .no_proxy(),
    )
}

/// A direct client without an overall request timeout, for streaming calls such as server-sent events.
pub fn direct_client(connect_timeout: Duration) -> io::Result<Client> {
    build_client(
        Client::builder()
            .redirect(redirect::Policy::none())
            .connect_timeout(connect_timeout)
            .user_agent(format!("{}/{}", nj_mule_info::NAME, nj_mule_info::version()))
            .no_proxy(),
    )
}

/// TLS client configuration on ring: checks certificates against the system trust store, falling back to Mozilla's
/// bundled root CAs when the system has none (for example, minimal Linux containers without `ca-certificates`).
pub fn tls_client_config() -> io::Result<rustls::ClientConfig> {
    install_tls();
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    match rustls::ClientConfig::builder_with_provider(Arc::clone(&provider))
        .with_safe_default_protocol_versions()
        .and_then(BuilderVerifierExt::with_platform_verifier)
    {
        Ok(builder) => Ok(builder.with_no_client_auth()),
        Err(reason) => {
            tracing::debug!("Platform TLS verifier unavailable ({reason}); falling back to bundled root certificates");
            bundled_tls_client_config(provider)
        }
    }
}

fn bundled_tls_client_config(provider: Arc<rustls::crypto::CryptoProvider>) -> io::Result<rustls::ClientConfig> {
    let mut roots = rustls::RootCertStore::empty();
    let (added, _) = roots.add_parsable_certificates(webpki_root_certs::TLS_SERVER_ROOT_CERTS.iter().cloned());
    if added == 0 {
        return Err(io::Error::other("cannot configure TLS: bundled root certificate store is empty"));
    }
    Ok(rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| io::Error::other(format!("cannot configure TLS: {e}")))?
        .with_root_certificates(roots)
        .with_no_client_auth())
}

fn build_client(builder: ClientBuilder) -> io::Result<Client> {
    let mut tls = tls_client_config()?;
    tls.alpn_protocols = vec![b"http/1.1".to_vec()];
    builder
        .tls_backend_preconfigured(tls)
        .build()
        .map_err(|e| io::Error::other(format!("cannot start the HTTP client: {e}")))
}

fn install_tls() {
    static PROVIDER: Once = Once::new();
    PROVIDER.call_once(|| {
        // Another part of the process may have chosen one already: then that one serves.
        let _ = rustls::crypto::ring::default_provider().install_default();
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bundled_tls_fallback_builds_a_working_http_client() -> io::Result<()> {
        install_tls();
        let mut tls = bundled_tls_client_config(Arc::new(rustls::crypto::ring::default_provider()))?;
        tls.alpn_protocols = vec![b"http/1.1".to_vec()];
        assert!(Client::builder().no_proxy().tls_backend_preconfigured(tls).build().is_ok());
        Ok(())
    }

    #[test]
    fn lan_and_direct_clients_build_with_default_tls() {
        assert!(lan_client(Duration::from_secs(5)).is_ok());
        assert!(direct_client(Duration::from_secs(5)).is_ok());
    }
}
