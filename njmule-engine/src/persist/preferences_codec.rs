// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The preferences as `njmule.properties`, the text file both engines read: one `key=value` per preference, values as
//! text. Keys this engine does not use yet stay in the file as they were, so the Java engine loses none.

use std::collections::BTreeMap;
use std::io;
use std::path::{Path, PathBuf};

use encoding_rs::UTF_8;
use java_properties::{PropertiesIter, PropertiesWriter};

use crate::config::preferences::{ObfuscationMode, Preferences, SharedFilesVisibility};
use crate::persist::atomic_files;

pub const FILE_NAME: &str = "njmule.properties";

/// The keys this engine reads and writes, as the Java engine names them.
pub const KEYS: &[&str] = &[
    "user.nick",
    "net.tcpPort",
    "net.udpPort",
    "net.udpEnabled",
    "security.obfuscation",
    "security.filterLan",
    "dirs.incoming",
    "dirs.temp",
    "share.visibleTo",
    "download.addPaused",
    "server.deadRetries",
    "server.addFromServer",
    "server.addFromClients",
    "server.staticOnly",
];

/// Every key with its value as text.
pub fn to_map(preferences: &Preferences) -> BTreeMap<&'static str, String> {
    let path = |path: &Option<PathBuf>| path.as_ref().map(|p| p.display().to_string()).unwrap_or_default();
    BTreeMap::from([
        ("user.nick", preferences.nick.clone()),
        ("net.tcpPort", preferences.tcp_port.to_string()),
        ("net.udpPort", preferences.udp_port.to_string()),
        ("net.udpEnabled", preferences.udp_enabled.to_string()),
        ("security.obfuscation", preferences.obfuscation.name().to_owned()),
        ("security.filterLan", preferences.filter_lan_addresses.to_string()),
        ("dirs.incoming", path(&preferences.incoming_dir)),
        ("dirs.temp", path(&preferences.temp_dir)),
        ("share.visibleTo", preferences.shared_files_visibility.name().to_owned()),
        ("download.addPaused", preferences.add_downloads_paused.to_string()),
        ("server.deadRetries", preferences.dead_server_retries.to_string()),
        ("server.addFromServer", preferences.add_servers_from_server.to_string()),
        ("server.addFromClients", preferences.add_servers_from_clients.to_string()),
        ("server.staticOnly", preferences.static_servers_only.to_string()),
    ])
}

/// The preferences with `changes` made; an unknown key or a value its preference refuses changes nothing.
pub fn apply(preferences: &Preferences, changes: &BTreeMap<String, String>) -> Result<Preferences, String> {
    if let Some(unknown) = changes.keys().find(|key| !KEYS.contains(&key.as_str())) {
        return Err(format!("Unknown preference: {unknown}"));
    }
    let mut changed = preferences.clone();
    for (key, value) in changes {
        set(&mut changed, key, value).map_err(|reason| format!("{key}: {reason}"))?;
    }
    Ok(changed)
}

/// Loads the preferences, or the defaults if the file is missing; a value that cannot be read keeps its default.
pub fn load(file: &Path) -> io::Result<Preferences> {
    let mut preferences = Preferences::default();
    for (key, value) in read(file)? {
        if KEYS.contains(&key.as_str())
            && let Err(reason) = set(&mut preferences, &key, &value)
        {
            tracing::warn!("Ignoring {key} in {}: {reason}", file.display());
        }
    }
    Ok(preferences)
}

/// Writes the preferences, keeping the keys of the file this engine does not use.
pub fn save(file: &Path, preferences: &Preferences) -> io::Result<()> {
    let mut properties = read(file)?;
    for (key, value) in to_map(preferences) {
        properties.insert(key.to_owned(), value);
    }
    let mut text = Vec::new();
    let mut writer = PropertiesWriter::new_with_encoding(&mut text, UTF_8);
    for (key, value) in &properties {
        writer.write(key, value).map_err(io::Error::other)?;
    }
    writer.finish().map_err(io::Error::other)?;
    drop(writer);
    atomic_files::write(file, &text, true)
}

fn read(file: &Path) -> io::Result<BTreeMap<String, String>> {
    let text = match std::fs::read(file) {
        Ok(text) => text,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(BTreeMap::new()),
        Err(e) => return Err(e),
    };
    let mut properties = BTreeMap::new();
    PropertiesIter::new_with_encoding(text.as_slice(), UTF_8)
        .read_into(|key, value| {
            properties.insert(key, value);
        })
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    Ok(properties)
}

fn set(preferences: &mut Preferences, key: &str, value: &str) -> Result<(), String> {
    match key {
        "user.nick" => preferences.nick = value.to_owned(),
        "net.tcpPort" => preferences.tcp_port = port(value)?,
        "net.udpPort" => preferences.udp_port = port(value)?,
        "net.udpEnabled" => preferences.udp_enabled = boolean(value)?,
        "security.obfuscation" => {
            preferences.obfuscation =
                ObfuscationMode::parse(value).ok_or_else(|| format!("not an obfuscation mode: {value}"))?;
        }
        "security.filterLan" => preferences.filter_lan_addresses = boolean(value)?,
        "dirs.incoming" => preferences.incoming_dir = optional_path(value),
        "dirs.temp" => preferences.temp_dir = optional_path(value),
        "share.visibleTo" => {
            preferences.shared_files_visibility =
                SharedFilesVisibility::parse(value).ok_or_else(|| format!("not a visibility: {value}"))?;
        }
        "download.addPaused" => preferences.add_downloads_paused = boolean(value)?,
        "server.deadRetries" => {
            preferences.dead_server_retries = value.trim().parse().map_err(|_| format!("not a count: {value}"))?;
        }
        "server.addFromServer" => preferences.add_servers_from_server = boolean(value)?,
        "server.addFromClients" => preferences.add_servers_from_clients = boolean(value)?,
        "server.staticOnly" => preferences.static_servers_only = boolean(value)?,
        _ => return Err("unknown".to_owned()),
    }
    Ok(())
}

fn boolean(value: &str) -> Result<bool, String> {
    match value.trim().to_lowercase().as_str() {
        "true" | "1" | "yes" | "on" => Ok(true),
        "false" | "0" | "no" | "off" => Ok(false),
        _ => Err(format!("not a boolean: {value}")),
    }
}

fn port(value: &str) -> Result<u16, String> {
    value.trim().parse().map_err(|_| format!("not a port: {value}"))
}

fn optional_path(value: &str) -> Option<PathBuf> {
    let trimmed = value.trim();
    (!trimmed.is_empty()).then(|| PathBuf::from(trimmed))
}
