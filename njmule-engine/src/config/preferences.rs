// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The engine's preferences, with the Java engine's defaults and checks. The fields are open to change; only
//! [`Preferences::validated`] ones are used, as the preferences file and the API give them.

use std::path::{Path, PathBuf};

use crate::api::nj_mule_info;
use crate::config::app_directories::AppDirectories;
use crate::config::event_commands::EventCommands;
use crate::config::next_download::NextDownload;
use crate::config::night_hours::NightHours;
use crate::config::notifications::Notifications;
use crate::config::obfuscation_mode::ObfuscationMode;
use crate::config::proxy_settings::ProxySettings;
use crate::config::shared_files_visibility::SharedFilesVisibility;
use crate::config::update_channel::UpdateChannel;
use crate::config::update_install_time::UpdateInstallTime;
use crate::config::update_mode::UpdateMode;
use crate::security::ip_whitelist;
use crate::util::white_space::strip;

// Lengths are in UTF-16 units, as the Java engine counts them.
pub const MAX_NICK_LENGTH: usize = 50;
pub const MAX_RATE_KIBPS: u32 = 10_000_000;
pub const MAX_CONNECTIONS: u32 = 10_000;
pub const MAX_SOURCES_PER_FILE: u32 = 10_000;
pub const LOG_LEVELS: [&str; 5] = ["ERROR", "WARN", "INFO", "DEBUG", "TRACE"];
pub const DEFAULT_TCP_PORT: u16 = 4662;
pub const DEFAULT_UDP_PORT: u16 = 4672;
// A common home line: about 80 Mbit/s down and 8 up.
pub const DEFAULT_DOWNLOAD_CAPACITY: u32 = 10_240;
pub const DEFAULT_UPLOAD_CAPACITY: u32 = 1_024;
pub const DEFAULT_MIN_FREE_SPACE: u32 = 512;
pub const MAX_MIN_FREE_SPACE: u32 = 10_000_000;
pub const MAX_AUTO_DELETE_DAYS: u32 = 365;
/// A server that failed this many times in a row is dropped from the list.
pub const DEFAULT_DEAD_SERVER_RETRIES: u32 = 1;
pub const MAX_DEAD_SERVER_RETRIES: u32 = 10;
/// Zip copies of the configuration kept, written at each exit and daily; 0 writes none.
pub const DEFAULT_BACKUP_COPIES: u32 = 3;
pub const MAX_BACKUP_COPIES: u32 = 50;
/// IP filter ranges below this level block.
pub const DEFAULT_IP_FILTER_LEVEL: u32 = 127;
pub const MAX_IP_FILTER_LEVEL: u32 = 255;
pub const MAX_IP_FILTER_WHITELIST: usize = 16_384;
pub const MAX_IP_FILTER_LISTS: usize = 16;
pub const MAX_IP_FILTER_UPDATE_DAYS: u32 = 365;
pub const DEFAULT_IP_FILTER_UPDATE_DAYS: u32 = 7;
const MAX_LIST_ADDRESS: usize = 2_048;
/// Chat messages holding one of these words, apart by `|`, do not show.
pub const DEFAULT_MESSAGE_FILTER: &str = "fastest download speed|fastest eMule";
pub const MAX_MESSAGE_FILTER: usize = 4096;
/// Longest name of a network interface to bind to.
pub const MAX_BIND_INTERFACE: usize = 256;
/// File comments holding one of these words, apart by `|`, are left out.
pub const DEFAULT_COMMENT_FILTER: &str = "http://|https://|ftp://|www.|ftp.";
/// Words, apart by `|`, a clean-up takes out of file names.
pub const DEFAULT_NAME_CLEANUPS: &str = "http|www.|.com|.de|.org|.net|shared|powered|sponsored|sharelive|filedonkey";
/// Incoming connections taken in five seconds at most.
pub const DEFAULT_NEW_CONNECTIONS: u32 = 20;
pub const MAX_NEW_CONNECTIONS: u32 = 1_000;
/// Clients waiting in the upload queue at most.
pub const DEFAULT_UPLOAD_QUEUE: u32 = 5_000;
pub const MIN_UPLOAD_QUEUE: u32 = 100;
pub const MAX_UPLOAD_QUEUE: u32 = 100_000;

/// The rates are in KiB/s, 0 for unlimited; the directories none for the profile's own.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Preferences {
    pub nick: String,
    pub tcp_port: u16,
    pub udp_port: u16,
    pub udp_enabled: bool,
    pub ed2k_enabled: bool,
    pub kad_enabled: bool,
    pub auto_connect: bool,
    pub reconnect: bool,
    pub max_upload_kibps: u32,
    pub max_download_kibps: u32,
    pub max_connections: u32,
    pub max_sources_per_file: u32,
    pub obfuscation: ObfuscationMode,
    pub secure_ident: bool,
    pub credit_system: bool,
    pub incoming_dir: Option<PathBuf>,
    pub temp_dir: Option<PathBuf>,
    /// One of [`LOG_LEVELS`].
    pub log_level: String,
    pub proxy: ProxySettings,
    /// Whether the router is asked to forward our ports.
    pub upnp: bool,
    /// The network interface (name, display name or IPv4 address) every connection goes through; blank for any.
    pub bind_interface: String,
    /// What the line can download, the top of the speed controls.
    pub download_capacity_kibps: u32,
    pub upload_capacity_kibps: u32,
    /// Downloads wait while the temp disk has less free space; 0 for never.
    pub min_free_space_mib: u32,
    pub dead_server_retries: u32,
    pub add_servers_from_server: bool,
    pub add_servers_from_clients: bool,
    pub static_servers_only: bool,
    pub manual_servers_high_priority: bool,
    pub update_servers_at_start: bool,
    pub backup_copies: u32,
    pub ip_filter_level: u32,
    pub remember_downloaded_files: bool,
    pub remember_cancelled_files: bool,
    pub store_searches: bool,
    pub add_downloads_paused: bool,
    pub next_download: NextDownload,
    pub filter_lan_addresses: bool,
    pub messages_from_friends_only: bool,
    pub message_filter: String,
    pub advanced_spam_filter: bool,
    pub search_spam_filter: bool,
    pub new_connections_per_five_seconds: u32,
    pub upload_queue_size: u32,
    pub shared_files_visibility: SharedFilesVisibility,
    pub preview_first: bool,
    pub video_player: String,
    pub video_player_args: String,
    pub comment_filter: String,
    pub name_cleanups: String,
    pub clean_up_new_names: bool,
    /// Addresses the IP filter never blocks, one per line (see [`invalid_whitelist_entry`]).
    pub ip_filter_whitelist: String,
    /// The programs run when a download ends, a chat message arrives or the disk runs short.
    pub event_commands: EventCommands,
    /// Whether the desktop app plays `notification_sound_file` with its notifications.
    pub notification_sound: bool,
    /// A WAV or MP3 file; none for none.
    pub notification_sound_file: Option<PathBuf>,
    /// Addresses (http or https) of IP filter lists, one per line, joined into one filter (see
    /// [`invalid_list_entry`]).
    pub ip_filter_lists: String,
    /// The lists are fetched again once the filter is this many days old; 0 for by hand only.
    pub ip_filter_update_days: u32,
    pub update_mode: UpdateMode,
    pub update_channel: UpdateChannel,
    /// When a new version downloaded is installed, in the [`UpdateMode::Install`] mode.
    pub update_install_time: UpdateInstallTime,
    /// The hours when njMule may restart to install it, with [`UpdateInstallTime::ExitOrNight`].
    pub night_hours: NightHours,
    /// Where the releases are looked for instead of GitHub, as for a fork or a test; none for GitHub.
    pub update_url: Option<String>,
    /// Keeps the computer from sleeping while files are transferred.
    pub prevent_sleep: bool,
    /// A running download that receives nothing for this many days is deleted, its link kept; 0 for never.
    pub auto_delete_days: u32,
    /// The e-mails and web hook requests that tell of what happens.
    pub notifications: Notifications,
    /// Shows the country of other clients, from DB-IP's database fetched once a month.
    pub show_countries: bool,
}

/// Defaults of a fresh installation, with njMule's own ports.
impl Default for Preferences {
    fn default() -> Self {
        Self {
            nick: "njMule".to_owned(),
            tcp_port: DEFAULT_TCP_PORT,
            udp_port: DEFAULT_UDP_PORT,
            udp_enabled: true,
            ed2k_enabled: true,
            kad_enabled: true,
            auto_connect: true,
            reconnect: true,
            max_upload_kibps: 0,
            max_download_kibps: 0,
            max_connections: 500,
            max_sources_per_file: 400,
            obfuscation: ObfuscationMode::Requested,
            secure_ident: true,
            credit_system: true,
            incoming_dir: None,
            temp_dir: None,
            log_level: "INFO".to_owned(),
            proxy: ProxySettings::default(),
            upnp: true,
            bind_interface: String::new(),
            download_capacity_kibps: DEFAULT_DOWNLOAD_CAPACITY,
            upload_capacity_kibps: DEFAULT_UPLOAD_CAPACITY,
            min_free_space_mib: DEFAULT_MIN_FREE_SPACE,
            dead_server_retries: DEFAULT_DEAD_SERVER_RETRIES,
            add_servers_from_server: false,
            add_servers_from_clients: false,
            static_servers_only: false,
            manual_servers_high_priority: false,
            update_servers_at_start: false,
            backup_copies: DEFAULT_BACKUP_COPIES,
            ip_filter_level: DEFAULT_IP_FILTER_LEVEL,
            remember_downloaded_files: true,
            remember_cancelled_files: true,
            store_searches: true,
            add_downloads_paused: false,
            next_download: NextDownload::None,
            filter_lan_addresses: true,
            messages_from_friends_only: false,
            message_filter: DEFAULT_MESSAGE_FILTER.to_owned(),
            advanced_spam_filter: true,
            search_spam_filter: true,
            new_connections_per_five_seconds: DEFAULT_NEW_CONNECTIONS,
            upload_queue_size: DEFAULT_UPLOAD_QUEUE,
            shared_files_visibility: SharedFilesVisibility::Nobody,
            preview_first: false,
            video_player: String::new(),
            video_player_args: String::new(),
            comment_filter: DEFAULT_COMMENT_FILTER.to_owned(),
            name_cleanups: DEFAULT_NAME_CLEANUPS.to_owned(),
            clean_up_new_names: false,
            ip_filter_whitelist: String::new(),
            event_commands: EventCommands::default(),
            notification_sound: false,
            notification_sound_file: None,
            ip_filter_lists: String::new(),
            ip_filter_update_days: DEFAULT_IP_FILTER_UPDATE_DAYS,
            update_mode: UpdateMode::Notify,
            update_channel: UpdateChannel::of(nj_mule_info::version()),
            update_install_time: UpdateInstallTime::Exit,
            night_hours: NightHours::default(),
            update_url: None,
            prevent_sleep: false,
            auto_delete_days: 0,
            notifications: Notifications::default(),
            show_countries: true,
        }
    }
}

impl Preferences {
    /// These preferences with their texts stripped, or the first reason, naming its field, why they cannot be.
    pub fn validated(mut self) -> Result<Self, String> {
        self.nick = strip(&self.nick).to_owned();
        if !is_valid_nick(&self.nick) {
            return Err(format!("nick must be 1-{MAX_NICK_LENGTH} printable characters"));
        }
        range("tcpPort", self.tcp_port.into(), 1, u16::MAX.into())?;
        range("udpPort", self.udp_port.into(), 1, u16::MAX.into())?;
        range("maxUploadKiBps", self.max_upload_kibps, 0, MAX_RATE_KIBPS)?;
        range("maxDownloadKiBps", self.max_download_kibps, 0, MAX_RATE_KIBPS)?;
        range("maxConnections", self.max_connections, 1, MAX_CONNECTIONS)?;
        range("maxSourcesPerFile", self.max_sources_per_file, 1, MAX_SOURCES_PER_FILE)?;
        absolute("incomingDir", self.incoming_dir.as_deref())?;
        absolute("tempDir", self.temp_dir.as_deref())?;
        if !LOG_LEVELS.contains(&self.log_level.as_str()) {
            return Err(format!("logLevel must be one of {LOG_LEVELS:?}"));
        }
        self.proxy = self.proxy.validated()?;
        range("downloadCapacityKiBps", self.download_capacity_kibps, 1, MAX_RATE_KIBPS)?;
        range("uploadCapacityKiBps", self.upload_capacity_kibps, 1, MAX_RATE_KIBPS)?;
        range("minFreeSpaceMiB", self.min_free_space_mib, 0, MAX_MIN_FREE_SPACE)?;
        range("autoDeleteDays", self.auto_delete_days, 0, MAX_AUTO_DELETE_DAYS)?;
        self.notifications = self.notifications.validated()?;
        range("deadServerRetries", self.dead_server_retries, 1, MAX_DEAD_SERVER_RETRIES)?;
        range("backupCopies", self.backup_copies, 0, MAX_BACKUP_COPIES)?;
        range("ipFilterLevel", self.ip_filter_level, 0, MAX_IP_FILTER_LEVEL)?;
        range("newConnectionsPerFiveSeconds", self.new_connections_per_five_seconds, 1, MAX_NEW_CONNECTIONS)?;
        range("uploadQueueSize", self.upload_queue_size, MIN_UPLOAD_QUEUE, MAX_UPLOAD_QUEUE)?;
        self.video_player = strip(&self.video_player).to_owned();
        self.video_player_args = strip(&self.video_player_args).to_owned();
        self.bind_interface = strip(&self.bind_interface).to_owned();
        if too_long(&self.bind_interface, MAX_BIND_INTERFACE) || self.bind_interface.chars().any(char::is_control) {
            return Err("bindInterface must be a short name without control characters".to_owned());
        }
        for (name, filter) in [
            ("commentFilter", &self.comment_filter),
            ("nameCleanups", &self.name_cleanups),
            ("messageFilter", &self.message_filter),
        ] {
            if too_long(filter, MAX_MESSAGE_FILTER) {
                return Err(format!("{name} must be at most {MAX_MESSAGE_FILTER} characters"));
            }
        }
        self.ip_filter_whitelist = strip(&self.ip_filter_whitelist).to_owned();
        if too_long(&self.ip_filter_whitelist, MAX_IP_FILTER_WHITELIST) {
            return Err(format!("ipFilterWhitelist must be at most {MAX_IP_FILTER_WHITELIST} characters"));
        }
        if let Some(invalid) = invalid_whitelist_entry(&self.ip_filter_whitelist) {
            return Err(format!("ipFilterWhitelist: not an address, CIDR block or range: {invalid}"));
        }
        absolute("notificationSoundFile", self.notification_sound_file.as_deref())?;
        self.ip_filter_lists = strip(&self.ip_filter_lists).to_owned();
        if let Some(invalid) = invalid_list_entry(&self.ip_filter_lists) {
            return Err(format!("ipFilterLists: not an http or https address: {invalid}"));
        }
        if !fits_list_count(&self.ip_filter_lists) {
            return Err(format!("ipFilterLists must hold at most {MAX_IP_FILTER_LISTS} addresses"));
        }
        range("ipFilterUpdateDays", self.ip_filter_update_days, 0, MAX_IP_FILTER_UPDATE_DAYS)?;
        if let Some(url) = self.update_url.as_deref().filter(|url| list_address(url).is_none()) {
            return Err(format!("updateUrl: not an http or https address: {url}"));
        }
        Ok(self)
    }

    /// Whether firewalled clients are met through the routers, over uTP or QUIC: such connections are plain, so not
    /// where obfuscation is required, and not behind a proxy, whose relay shows another address.
    pub fn router_traversal(&self) -> bool {
        self.obfuscation != ObfuscationMode::Required && !self.proxy.is_enabled()
    }

    /// The addresses of the IP filter lists, in their order.
    pub fn ip_filter_list_addresses(&self) -> Vec<&str> {
        list_addresses(&self.ip_filter_lists)
    }

    pub fn effective_incoming_dir(&self, dirs: &AppDirectories) -> PathBuf {
        self.incoming_dir.clone().unwrap_or_else(|| dirs.downloads.clone())
    }

    pub fn effective_temp_dir(&self, dirs: &AppDirectories) -> PathBuf {
        self.temp_dir.clone().unwrap_or_else(|| dirs.temp.clone())
    }
}

/// The first line of a list of IP filter lists that is not an http or https address with a host, if any.
pub fn invalid_list_entry(text: &str) -> Option<&str> {
    list_lines(text).find(|line| list_address(line).is_none())
}

/// The http and https addresses of `text`, one per line, in their order; the other lines left out.
pub fn list_addresses(text: &str) -> Vec<&str> {
    list_lines(text).filter_map(list_address).collect()
}

/// Whether `text` holds no more lists than a filter takes.
pub fn fits_list_count(text: &str) -> bool {
    list_lines(text).count() <= MAX_IP_FILTER_LISTS
}

/// The first line of an IP filter whitelist that is not an address (`1.2.3.4`), a CIDR block (`1.2.3.0/24`) or an
/// ipfilter.dat range (`1.2.3.0 - 1.2.3.255`), if any.
pub fn invalid_whitelist_entry(text: &str) -> Option<String> {
    ip_whitelist::first_invalid_entry(text)
}

/// Whether `nick`, already stripped, can be ours: 1 to 50 characters, none of them a control.
pub fn is_valid_nick(nick: &str) -> bool {
    !nick.is_empty() && !too_long(nick, MAX_NICK_LENGTH) && !nick.chars().any(|c| c.is_control() || is_bidi_control(c))
}

// Embeddings, overrides and isolates would reverse the text shown after the nick in other users' lists.
fn is_bidi_control(c: char) -> bool {
    matches!(c, '\u{202A}'..='\u{202E}' | '\u{2066}'..='\u{2069}')
}

fn list_lines(text: &str) -> impl Iterator<Item = &str> {
    text.split(['\r', '\n']).map(strip).filter(|line| !line.is_empty())
}

fn list_address(line: &str) -> Option<&str> {
    if too_long(line, MAX_LIST_ADDRESS) {
        return None;
    }
    let uri: http::Uri = line.parse().ok()?;
    let web = uri.scheme_str().is_some_and(|s| s.eq_ignore_ascii_case("http") || s.eq_ignore_ascii_case("https"));
    (web && uri.host().is_some()).then_some(line)
}

fn range(name: &str, value: u32, min: u32, max: u32) -> Result<(), String> {
    if (min..=max).contains(&value) { Ok(()) } else { Err(format!("{name} must be in [{min}, {max}]: {value}")) }
}

fn absolute(name: &str, path: Option<&Path>) -> Result<(), String> {
    match path {
        Some(path) if !path.is_absolute() => Err(format!("{name} must be an absolute path: {}", path.display())),
        _ => Ok(()),
    }
}

fn too_long(text: &str, max: usize) -> bool {
    text.encode_utf16().count() > max
}
