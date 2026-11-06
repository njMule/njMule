// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The proxy outgoing TCP connections go through: SOCKS (4 or 5, with optional user name and password) or an HTTP proxy
//! that allows CONNECT. UDP (server pings, global searches, Kad) goes through the UDP relay of a SOCKS5 proxy, and
//! straight to the network with a proxy that has none.

use std::fmt;

use strum::{EnumString, IntoStaticStr, VariantArray};

use crate::util::white_space::strip;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, EnumString, IntoStaticStr, VariantArray)]
#[strum(serialize_all = "SCREAMING_SNAKE_CASE", ascii_case_insensitive)]
pub enum ProxyType {
    None,
    Socks,
    Http,
}

impl ProxyType {
    pub fn name(self) -> &'static str {
        self.into()
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct ProxySettings {
    pub kind: ProxyType,
    pub host: String,
    pub port: u16,
    pub user: String,
    pub password: String,
}

impl ProxySettings {
    /// These settings with the host stripped, or why they cannot be.
    pub fn validated(mut self) -> Result<Self, String> {
        self.host = strip(&self.host).to_owned();
        if self.port == 0 {
            return Err(format!("proxy port must be in [1, 65535]: {}", self.port));
        }
        if self.kind != ProxyType::None && self.host.is_empty() {
            return Err("a proxy needs a host".to_owned());
        }
        Ok(self)
    }

    pub fn is_enabled(&self) -> bool {
        self.kind != ProxyType::None
    }
}

/// No proxy.
impl Default for ProxySettings {
    fn default() -> Self {
        Self { kind: ProxyType::None, host: String::new(), port: 1080, user: String::new(), password: String::new() }
    }
}

// The password never shows in logs.
impl fmt::Debug for ProxySettings {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mut settings = f.debug_struct("ProxySettings");
        settings
            .field("kind", &self.kind)
            .field("host", &self.host)
            .field("port", &self.port)
            .field("user", &self.user);
        if !self.password.is_empty() {
            settings.field("password", &"***");
        }
        settings.finish()
    }
}
