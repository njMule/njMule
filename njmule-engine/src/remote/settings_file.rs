// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The web interface's settings as the API sees and changes them in `webui.properties`: never the password hashes,
//! and a change kept only whole.

use std::io;
use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::path::PathBuf;
use std::sync::{Mutex, PoisonError};

use crate::remote::passwords;
use crate::remote::web_ui_settings::WebUiSettings;

const LOOPBACK: &str = "127.0.0.1";

/// The settings as the API gives them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WebView {
    pub enabled: bool,
    pub port: u16,
    pub lan: bool,
    pub hosts: Vec<String>,
    pub proxies: Vec<String>,
    pub api: bool,
    pub has_password: bool,
    pub has_guest: bool,
    /// Where a browser opens the interface, while it is on with a password.
    pub url: Option<String>,
}

/// What to change; what is none stays.
#[derive(Debug, Clone, Default)]
pub struct WebChange {
    pub enabled: Option<bool>,
    pub port: Option<u16>,
    pub lan: Option<bool>,
    pub hosts: Option<Vec<String>>,
    pub proxies: Option<Vec<String>>,
    pub api: Option<bool>,
    /// The new password of the admin.
    pub password: Option<String>,
    /// The new password of the guest, or an empty one to let no guest in.
    pub guest_password: Option<String>,
}

/// Why a change was not kept.
#[derive(Debug)]
pub enum ChangeError {
    /// A value is wrong, or the interface would be on without a password.
    Invalid(String),
    Io(io::Error),
}

pub struct SettingsFile {
    config: PathBuf,
    saved: Box<dyn Fn(&WebUiSettings) + Send + Sync>,
    changing: Mutex<()>,
}

impl SettingsFile {
    /// The settings of `config`; `saved` hears the settings each change leaves, to follow them at once.
    pub fn new(config: PathBuf, saved: impl Fn(&WebUiSettings) + Send + Sync + 'static) -> Self {
        Self { config, saved: Box::new(saved), changing: Mutex::new(()) }
    }

    /// The settings as they are kept.
    pub fn settings(&self) -> WebView {
        view(&WebUiSettings::load(&self.config))
    }

    /// Keeps the settings with `change` applied, and gives them as they are then.
    pub fn change(&self, change: WebChange) -> Result<WebView, ChangeError> {
        let _changing = self.changing.lock().unwrap_or_else(PoisonError::into_inner);
        let now = WebUiSettings::load(&self.config);
        let admin = match &change.password {
            Some(password) => hashed(password)?,
            None => now.password_hash.clone(),
        };
        let guest = match &change.guest_password {
            Some(password) if password.is_empty() => String::new(),
            Some(password) => guest_hash(password, &admin)?,
            None => now.guest_password_hash.clone(),
        };
        let next = WebUiSettings {
            enabled: change.enabled.unwrap_or(now.enabled),
            port: change.port.unwrap_or(now.port),
            lan: change.lan.unwrap_or(now.lan),
            password_hash: admin,
            api: change.api.unwrap_or(now.api),
            hosts: change.hosts.unwrap_or(now.hosts),
            proxies: change.proxies.unwrap_or(now.proxies),
            guest_password_hash: guest,
        }
        .validated()
        .map_err(ChangeError::Invalid)?;
        if next.enabled && next.password_hash.is_empty() {
            return Err(ChangeError::Invalid("the web interface needs a password".to_owned()));
        }
        next.save(&self.config).map_err(ChangeError::Io)?;
        (self.saved)(&next);
        Ok(view(&next))
    }
}

fn hashed(password: &str) -> Result<String, ChangeError> {
    if password.chars().count() < passwords::MIN_LENGTH {
        return Err(ChangeError::Invalid(format!("the password needs at least {} characters", passwords::MIN_LENGTH)));
    }
    Ok(passwords::hash(password))
}

// A guest's password may not be the admin's, who would log in as a guest otherwise.
fn guest_hash(password: &str, admin: &str) -> Result<String, ChangeError> {
    if !admin.is_empty() && passwords::matches(password, admin) {
        return Err(ChangeError::Invalid("the guest password must differ from yours".to_owned()));
    }
    hashed(password)
}

fn view(settings: &WebUiSettings) -> WebView {
    let url = settings.usable().then(|| {
        let host =
            if settings.lan { lan_address().unwrap_or_else(|| LOOPBACK.to_owned()) } else { LOOPBACK.to_owned() };
        format!("http://{host}:{}/", settings.port)
    });
    WebView {
        enabled: settings.enabled,
        port: settings.port,
        lan: settings.lan,
        hosts: settings.hosts.clone(),
        proxies: settings.proxies.clone(),
        api: settings.api,
        has_password: !settings.password_hash.is_empty(),
        has_guest: settings.has_guest(),
        url,
    }
}

// The address of this computer on its LAN: the one a datagram to a public address would leave from (none is sent).
fn lan_address() -> Option<String> {
    let probe = UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).ok()?;
    probe.connect(SocketAddr::from((Ipv4Addr::new(192, 0, 2, 1), 9))).ok()?;
    let local = probe.local_addr().ok()?.ip();
    (!local.is_unspecified()).then(|| local.to_string())
}
