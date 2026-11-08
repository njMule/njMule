// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Whether the web interface runs, on which port, whether other computers may reach it, the hashes of its passwords,
//! the host names it answers to and the proxies it trusts; kept in `webui.properties` of the config folder.

use std::fmt;
use std::io::{self, ErrorKind};
use std::net::IpAddr;
use std::path::Path;

use crate::persist::{atomic_files, property_lines};
use crate::remote::trusted_proxies;

pub const FILE_NAME: &str = "webui.properties";
/// The port eMule's web interface uses too.
pub const DEFAULT_PORT: u16 = 4711;

// The longest DNS name and label (RFC 1035).
const MAX_NAME: usize = 253;
const MAX_LABEL: usize = 63;

#[derive(Clone, PartialEq, Eq)]
pub struct WebUiSettings {
    pub enabled: bool,
    pub port: u16,
    /// Whether to listen on every address instead of this computer's only.
    pub lan: bool,
    /// Empty until a password is set; the interface does not start without one.
    pub password_hash: String,
    /// Whether the remote-control API answers too, to holders of its token.
    pub api: bool,
    /// Names besides localhost under which browsers open the interface, as [`host_name`] keeps them.
    pub hosts: Vec<String>,
    /// The reverse proxies trusted to name their client, as [`trusted_proxies::entry`] keeps them; none by default.
    pub proxies: Vec<String>,
    /// The hash of the password of a guest, who may only look; empty for no guest.
    pub guest_password_hash: String,
}

// Without the password hashes, which a log line would spread.
impl fmt::Debug for WebUiSettings {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("WebUiSettings")
            .field("enabled", &self.enabled)
            .field("port", &self.port)
            .field("lan", &self.lan)
            .field("api", &self.api)
            .field("hosts", &self.hosts)
            .field("proxies", &self.proxies)
            .field("guest", &self.has_guest())
            .finish_non_exhaustive()
    }
}

impl Default for WebUiSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            port: DEFAULT_PORT,
            lan: false,
            password_hash: String::new(),
            api: false,
            hosts: Vec::new(),
            proxies: Vec::new(),
            guest_password_hash: String::new(),
        }
    }
}

impl WebUiSettings {
    /// These settings with their host names and proxies as they are kept, each once; why not when one is wrong.
    pub fn validated(mut self) -> Result<Self, String> {
        if self.port == 0 {
            return Err("port must be in [1, 65535]: 0".to_owned());
        }
        self.hosts = once(self.hosts.iter().map(|name| host_name(name)).collect::<Result<_, _>>()?);
        self.proxies = once(self.proxies.iter().map(|proxy| trusted_proxies::entry(proxy)).collect::<Result<_, _>>()?);
        Ok(self)
    }

    /// Whether a guest may log in.
    pub fn has_guest(&self) -> bool {
        !self.guest_password_hash.is_empty()
    }

    /// Whether the interface can start: enabled, with a password.
    pub fn usable(&self) -> bool {
        self.enabled && !self.password_hash.is_empty()
    }

    /// The settings kept in `config`; the defaults, with the interface off, when there are none or they cannot be read.
    pub fn load(config: &Path) -> Self {
        let file = config.join(FILE_NAME);
        match property_lines::read(&file) {
            Ok(p) => {
                let text = |key: &str| p.get(key).map(|value| value.trim().to_owned()).unwrap_or_default();
                let flag = |key: &str| text(key).eq_ignore_ascii_case("true");
                let port = match p.get("port") {
                    None => Ok(DEFAULT_PORT),
                    Some(port) => port.trim().parse::<u16>().map_err(|e| format!("port {port}: {e}")),
                };
                let read = port.and_then(|port| {
                    Self {
                        enabled: flag("enabled"),
                        port,
                        lan: flag("lan"),
                        password_hash: text("password"),
                        api: flag("api"),
                        hosts: separated(&text("hosts")),
                        proxies: separated(&text("proxies")),
                        guest_password_hash: text("guestPassword"),
                    }
                    .validated()
                });
                read.unwrap_or_else(|e| {
                    tracing::warn!("The web interface stays off: cannot read {}: {e}", file.display());
                    Self::default()
                })
            }
            Err(e) if e.kind() == ErrorKind::NotFound => Self::default(),
            Err(e) => {
                tracing::warn!("The web interface stays off: cannot read {}: {e}", file.display());
                Self::default()
            }
        }
    }

    /// Writes the settings in one step, for their owner only where the file system can say so.
    pub fn save(&self, config: &Path) -> io::Result<()> {
        let mut text = "# njMule web interface\n".to_owned();
        property_lines::append(&mut text, "enabled", &self.enabled.to_string());
        property_lines::append(&mut text, "port", &self.port.to_string());
        property_lines::append(&mut text, "lan", &self.lan.to_string());
        property_lines::append(&mut text, "password", &self.password_hash);
        property_lines::append(&mut text, "api", &self.api.to_string());
        property_lines::append(&mut text, "hosts", &self.hosts.join(","));
        property_lines::append(&mut text, "proxies", &self.proxies.join(","));
        if self.has_guest() {
            property_lines::append(&mut text, "guestPassword", &self.guest_password_hash);
        }
        atomic_files::write_secret(&config.join(FILE_NAME), text.as_bytes())
    }
}

/// A host name or IP address as the interface compares it with the Host and Origin headers: in lower case, an IPv6
/// address in brackets; why not when `name` is neither, or comes with a port or a scheme.
pub fn host_name(name: &str) -> Result<String, String> {
    let lower = name.trim().to_ascii_lowercase();
    let bracketed = lower.starts_with('[') && lower.ends_with(']');
    let bare = if bracketed { lower.trim_start_matches('[').trim_end_matches(']') } else { lower.as_str() };
    let refused = || format!("not a host name or an IP address: {name}");
    // Browsers send an international name in its ASCII form (xn--), which is the one to give.
    match bare.parse::<IpAddr>() {
        Ok(IpAddr::V6(address)) => Ok(format!("[{address}]")),
        Ok(IpAddr::V4(address)) if !bracketed => Ok(address.to_string()),
        Ok(IpAddr::V4(_)) => Err(refused()),
        Err(_) if !bracketed && is_domain_name(bare) => Ok(bare.to_owned()),
        Err(_) => Err(refused()),
    }
}

/// The host names of a text that separates them with commas or white space.
pub fn host_names(text: &str) -> Result<Vec<String>, String> {
    Ok(once(separated(text).iter().map(|name| host_name(name)).collect::<Result<_, _>>()?))
}

/// The proxies of a text that separates them with commas or white space.
pub fn proxies(text: &str) -> Result<Vec<String>, String> {
    Ok(once(separated(text).iter().map(|proxy| trusted_proxies::entry(proxy)).collect::<Result<_, _>>()?))
}

// A DNS name (RFC 1123): labels of letters, digits, hyphens and underscores, none starting or ending with a hyphen, the
// last not starting with a digit, which would make an address of it.
fn is_domain_name(name: &str) -> bool {
    let name = name.strip_suffix('.').unwrap_or(name);
    let labels: Vec<&str> = name.split('.').collect();
    let valid = |label: &&str| {
        !label.is_empty()
            && label.len() <= MAX_LABEL
            && !label.starts_with('-')
            && !label.ends_with('-')
            && label.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    };
    !name.is_empty()
        && name.len() <= MAX_NAME
        && labels.iter().all(valid)
        && labels.last().is_some_and(|last| !last.starts_with(|c: char| c.is_ascii_digit()))
}

fn separated(text: &str) -> Vec<String> {
    text.split(|c: char| c == ',' || c.is_whitespace())
        .filter(|part| !part.is_empty())
        .map(str::to_owned)
        .collect()
}

fn once(values: Vec<String>) -> Vec<String> {
    let mut kept: Vec<String> = Vec::new();
    for value in values {
        if !kept.contains(&value) {
            kept.push(value);
        }
    }
    kept
}
