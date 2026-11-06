// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Where outgoing connections go: from the network interface chosen, and through the proxy of the preferences. Both
//! are read at each connection, so new settings apply at once. The network passes its routes to whatever connects,
//! where the Java engine keeps them for the whole process.

use std::fmt;
use std::io;
use std::net::Ipv4Addr;
use std::sync::Arc;

use crate::config::bind_interface::Binding;
use crate::config::proxy_settings::ProxySettings;

type Read<T> = Arc<dyn Fn() -> T + Send + Sync>;

#[derive(Clone)]
pub struct Routes {
    proxy: Read<ProxySettings>,
    binding: Read<Binding>,
}

impl Routes {
    /// Straight to the network from any interface.
    pub fn direct() -> Self {
        Self::new(ProxySettings::default, || Binding::Any)
    }

    pub fn new(
        proxy: impl Fn() -> ProxySettings + Send + Sync + 'static,
        binding: impl Fn() -> Binding + Send + Sync + 'static,
    ) -> Self {
        Self { proxy: Arc::new(proxy), binding: Arc::new(binding) }
    }

    /// The proxy of the preferences now.
    pub fn proxy(&self) -> ProxySettings {
        (self.proxy)()
    }

    /// The interface outgoing connections leave from now.
    pub fn binding(&self) -> Binding {
        (self.binding)()
    }

    /// The local address to bind a socket to for the interface chosen now: none for any; an error while it is gone.
    pub fn local_address(&self) -> io::Result<Option<Ipv4Addr>> {
        match self.binding() {
            Binding::Any => Ok(None),
            Binding::Bound(address) => Ok(Some(address)),
            Binding::Missing(choice) => Err(io::Error::new(
                io::ErrorKind::AddrNotAvailable,
                format!("the network interface {choice} is not available"),
            )),
        }
    }

    /// Whether our IPv6 address may be told to peers: not while njMule keeps to an interface of its own, such as a
    /// VPN's, nor through a proxy, as peers reaching us straight at our address would bypass what hides it.
    pub fn may_tell_public_ipv6(&self) -> bool {
        self.binding() == Binding::Any && !self.proxy().is_enabled()
    }
}

impl fmt::Debug for Routes {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Routes").field("proxy", &self.proxy()).field("binding", &self.binding()).finish()
    }
}
