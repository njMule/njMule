// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The user's preferences, the part of them this engine uses so far, with the Java engine's defaults.

use std::path::PathBuf;

/// How the protocol obfuscation is used.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ObfuscationMode {
    Disabled,
    Supported,
    Requested,
    Required,
}

impl ObfuscationMode {
    pub fn name(self) -> &'static str {
        match self {
            Self::Disabled => "DISABLED",
            Self::Supported => "SUPPORTED",
            Self::Requested => "REQUESTED",
            Self::Required => "REQUIRED",
        }
    }

    /// A mode by its name, in any case.
    pub fn parse(text: &str) -> Option<Self> {
        [Self::Disabled, Self::Supported, Self::Requested, Self::Required]
            .into_iter()
            .find(|mode| mode.name().eq_ignore_ascii_case(text.trim()))
    }
}

/// Who may see the list of our shared files.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SharedFilesVisibility {
    Nobody,
    Friends,
    Everybody,
}

impl SharedFilesVisibility {
    pub fn name(self) -> &'static str {
        match self {
            Self::Nobody => "NOBODY",
            Self::Friends => "FRIENDS",
            Self::Everybody => "EVERYBODY",
        }
    }

    pub fn parse(text: &str) -> Option<Self> {
        [Self::Nobody, Self::Friends, Self::Everybody]
            .into_iter()
            .find(|visibility| visibility.name().eq_ignore_ascii_case(text.trim()))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Preferences {
    pub nick: String,
    pub tcp_port: u16,
    pub udp_port: u16,
    pub udp_enabled: bool,
    pub obfuscation: ObfuscationMode,
    pub filter_lan_addresses: bool,
    /// Where completed downloads go; none is the profile's `incoming`.
    pub incoming_dir: Option<PathBuf>,
    /// Where part files go; none is the profile's `temp`.
    pub temp_dir: Option<PathBuf>,
    pub shared_files_visibility: SharedFilesVisibility,
    pub add_downloads_paused: bool,
    pub dead_server_retries: u32,
    pub add_servers_from_server: bool,
    pub add_servers_from_clients: bool,
    pub static_servers_only: bool,
}

impl Default for Preferences {
    fn default() -> Self {
        Self {
            nick: "njMule".to_owned(),
            tcp_port: 4662,
            udp_port: 4672,
            udp_enabled: true,
            obfuscation: ObfuscationMode::Requested,
            filter_lan_addresses: true,
            incoming_dir: None,
            temp_dir: None,
            shared_files_visibility: SharedFilesVisibility::Nobody,
            add_downloads_paused: false,
            dead_server_retries: 1,
            add_servers_from_server: false,
            add_servers_from_clients: false,
            static_servers_only: false,
        }
    }
}
