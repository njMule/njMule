// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The preferences as `njmule.properties`, the file both engines read: UTF-8, sorted keys, no timestamp, so that it diffs
//! cleanly. Loading is lenient: an invalid value keeps its default and logs a warning, so that a hand-edited typo never
//! prevents the start. Keys of a newer version stay in the file as they were.

use std::collections::BTreeMap;
use std::io;
use std::path::{Path, PathBuf};
use std::str::FromStr;
use std::sync::LazyLock;

use crate::config::event_commands::{Command, Event};
use crate::config::next_download::NextDownload;
use crate::config::night_hours::NightHours;
use crate::config::notifications::{self, SmtpSecurity, WebhookFormat};
use crate::config::obfuscation_mode::ObfuscationMode;
use crate::config::preferences::Preferences;
use crate::config::proxy_settings::ProxyType;
use crate::config::shared_files_visibility::SharedFilesVisibility;
use crate::config::update_channel::UpdateChannel;
use crate::config::update_install_time::UpdateInstallTime;
use crate::config::update_mode::UpdateMode;
use crate::persist::{atomic_files, property_lines};
use crate::util::white_space::strip;

pub const FILE_NAME: &str = "njmule.properties";

type Getter = Box<dyn Fn(&Preferences) -> String + Send + Sync>;
type Setter = Box<dyn Fn(&mut Preferences, &str) -> Result<(), String> + Send + Sync>;

struct Key {
    name: String,
    get: Getter,
    set: Setter,
}

// In the order they are read: a value checked against others comes after them.
static KEYS: LazyLock<Vec<Key>> = LazyLock::new(|| {
    let mut keys = vec![
        text_key("user.nick", |p| p.nick.clone(), |p, v| p.nick = v),
        int_key("net.tcpPort", |p| p.tcp_port, |p, v| p.tcp_port = v),
        int_key("net.udpPort", |p| p.udp_port, |p, v| p.udp_port = v),
        bool_key("net.udpEnabled", |p| p.udp_enabled, |p, v| p.udp_enabled = v),
        bool_key("net.ed2k.enabled", |p| p.ed2k_enabled, |p, v| p.ed2k_enabled = v),
        bool_key("net.kad.enabled", |p| p.kad_enabled, |p, v| p.kad_enabled = v),
        bool_key("net.autoConnect", |p| p.auto_connect, |p, v| p.auto_connect = v),
        bool_key("net.reconnect", |p| p.reconnect, |p, v| p.reconnect = v),
        int_key("net.maxConnections", |p| p.max_connections, |p, v| p.max_connections = v),
        int_key("limits.uploadKiBps", |p| p.max_upload_kibps, |p, v| p.max_upload_kibps = v),
        int_key("limits.downloadKiBps", |p| p.max_download_kibps, |p, v| p.max_download_kibps = v),
        int_key("limits.downloadCapacityKiBps", |p| p.download_capacity_kibps, |p, v| p.download_capacity_kibps = v),
        int_key("limits.uploadCapacityKiBps", |p| p.upload_capacity_kibps, |p, v| p.upload_capacity_kibps = v),
        int_key("download.maxSourcesPerFile", |p| p.max_sources_per_file, |p, v| p.max_sources_per_file = v),
        int_key("download.minFreeSpaceMiB", |p| p.min_free_space_mib, |p, v| p.min_free_space_mib = v),
        int_key("download.autoDeleteDays", |p| p.auto_delete_days, |p, v| p.auto_delete_days = v),
        name_key("security.obfuscation", |p| p.obfuscation.name(), |p, v: ObfuscationMode| p.obfuscation = v),
        bool_key("security.secureIdent", |p| p.secure_ident, |p, v| p.secure_ident = v),
        bool_key("upload.creditSystem", |p| p.credit_system, |p, v| p.credit_system = v),
        path_key("dirs.incoming", |p| p.incoming_dir.clone(), |p, v| p.incoming_dir = v),
        path_key("dirs.temp", |p| p.temp_dir.clone(), |p, v| p.temp_dir = v),
        text_key("log.level", |p| p.log_level.clone(), |p, v| p.log_level = strip(&v).to_uppercase()),
        bool_key("net.upnp", |p| p.upnp, |p, v| p.upnp = v),
        text_key("net.bindInterface", |p| p.bind_interface.clone(), |p, v| p.bind_interface = v),
        int_key("backup.copies", |p| p.backup_copies, |p, v| p.backup_copies = v),
        int_key("security.ipFilterLevel", |p| p.ip_filter_level, |p, v| p.ip_filter_level = v),
        bool_key("security.filterLan", |p| p.filter_lan_addresses, |p, v| p.filter_lan_addresses = v),
        text_key("security.ipFilterWhitelist", |p| p.ip_filter_whitelist.clone(), |p, v| p.ip_filter_whitelist = v),
        text_key("security.ipFilterLists", |p| p.ip_filter_lists.clone(), |p, v| p.ip_filter_lists = v),
        int_key("security.ipFilterUpdateDays", |p| p.ip_filter_update_days, |p, v| p.ip_filter_update_days = v),
        name_key("update.mode", |p| p.update_mode.name(), |p, v: UpdateMode| p.update_mode = v),
        name_key("update.channel", |p| p.update_channel.name(), |p, v: UpdateChannel| p.update_channel = v),
        key(
            "update.installAt",
            |p| p.update_install_time.name().to_owned(),
            |p, v| {
                p.update_install_time = parse_name::<UpdateInstallTime>(&strip(v).replace('-', "_"))?;
                Ok(())
            },
        ),
        key(
            "update.nightHours",
            |p| p.night_hours.to_string(),
            |p, v| {
                p.night_hours = NightHours::parse(v)?;
                Ok(())
            },
        ),
        key(
            "update.url",
            |p| p.update_url.clone().unwrap_or_default(),
            |p, v| {
                p.update_url = Some(strip(v)).filter(|url| !url.is_empty()).map(str::to_owned);
                Ok(())
            },
        ),
        bool_key("chat.friendsOnly", |p| p.messages_from_friends_only, |p, v| p.messages_from_friends_only = v),
        bool_key("search.spamFilter", |p| p.search_spam_filter, |p, v| p.search_spam_filter = v),
        text_key("chat.filter", |p| p.message_filter.clone(), |p, v| p.message_filter = v),
        bool_key("chat.spamFilter", |p| p.advanced_spam_filter, |p, v| p.advanced_spam_filter = v),
        int_key(
            "net.newConnectionsPer5s",
            |p| p.new_connections_per_five_seconds,
            |p, v| p.new_connections_per_five_seconds = v,
        ),
        int_key("upload.queueSize", |p| p.upload_queue_size, |p, v| p.upload_queue_size = v),
        bool_key("download.previewFirst", |p| p.preview_first, |p, v| p.preview_first = v),
        text_key("preview.player", |p| p.video_player.clone(), |p, v| p.video_player = v),
        text_key("preview.args", |p| p.video_player_args.clone(), |p, v| p.video_player_args = v),
        text_key("comments.filter", |p| p.comment_filter.clone(), |p, v| p.comment_filter = v),
        text_key("files.nameCleanups", |p| p.name_cleanups.clone(), |p, v| p.name_cleanups = v),
        bool_key("files.cleanUpNewNames", |p| p.clean_up_new_names, |p, v| p.clean_up_new_names = v),
        bool_key("notify.sound", |p| p.notification_sound, |p, v| p.notification_sound = v),
        bool_key("system.preventSleep", |p| p.prevent_sleep, |p, v| p.prevent_sleep = v),
        bool_key("geo.countries", |p| p.show_countries, |p, v| p.show_countries = v),
        bool_key("notify.email", |p| p.notifications.email, |p, v| p.notifications.email = v),
        text_key("notify.smtpHost", |p| p.notifications.smtp_host.clone(), |p, v| p.notifications.smtp_host = v),
        int_key("notify.smtpPort", |p| p.notifications.smtp_port, |p, v| p.notifications.smtp_port = v),
        name_key(
            "notify.smtpSecurity",
            |p| p.notifications.security.name(),
            |p, v: SmtpSecurity| p.notifications.security = v,
        ),
        text_key("notify.smtpUser", |p| p.notifications.smtp_user.clone(), |p, v| p.notifications.smtp_user = v),
        text_key(
            "notify.smtpPassword",
            |p| p.notifications.smtp_password.clone(),
            |p, v| p.notifications.smtp_password = v,
        ),
        text_key("notify.mailFrom", |p| p.notifications.mail_from.clone(), |p, v| p.notifications.mail_from = v),
        text_key("notify.mailTo", |p| p.notifications.mail_to.clone(), |p, v| p.notifications.mail_to = v),
        bool_key("notify.webhook", |p| p.notifications.webhook, |p, v| p.notifications.webhook = v),
        text_key("notify.webhookUrl", |p| p.notifications.webhook_url.clone(), |p, v| p.notifications.webhook_url = v),
        name_key(
            "notify.webhookBody",
            |p| p.notifications.webhook_body.name(),
            |p, v: WebhookFormat| p.notifications.webhook_body = v,
        ),
        key(
            "notify.events",
            |p| p.notifications.events.iter().map(|event| event.id()).collect::<Vec<_>>().join(","),
            |p, v| {
                p.notifications.events = notifications::events(v)?;
                Ok(())
            },
        ),
        path_key("notify.soundFile", |p| p.notification_sound_file.clone(), |p, v| p.notification_sound_file = v),
        name_key(
            "share.visibleTo",
            |p| p.shared_files_visibility.name(),
            |p, v: SharedFilesVisibility| p.shared_files_visibility = v,
        ),
        bool_key("files.rememberDownloaded", |p| p.remember_downloaded_files, |p, v| p.remember_downloaded_files = v),
        bool_key("files.rememberCancelled", |p| p.remember_cancelled_files, |p, v| p.remember_cancelled_files = v),
        bool_key("search.store", |p| p.store_searches, |p, v| p.store_searches = v),
        bool_key("download.addPaused", |p| p.add_downloads_paused, |p, v| p.add_downloads_paused = v),
        name_key("download.startNext", |p| p.next_download.name(), |p, v: NextDownload| p.next_download = v),
        int_key("server.deadRetries", |p| p.dead_server_retries, |p, v| p.dead_server_retries = v),
        bool_key("server.addFromServer", |p| p.add_servers_from_server, |p, v| p.add_servers_from_server = v),
        bool_key("server.addFromClients", |p| p.add_servers_from_clients, |p, v| p.add_servers_from_clients = v),
        bool_key("server.staticOnly", |p| p.static_servers_only, |p, v| p.static_servers_only = v),
        bool_key("server.updateAtStart", |p| p.update_servers_at_start, |p, v| p.update_servers_at_start = v),
        bool_key(
            "server.manualHighPriority",
            |p| p.manual_servers_high_priority,
            |p, v| p.manual_servers_high_priority = v,
        ),
    ];
    for event in Event::ALL {
        let name = format!("events.{}", event.id());
        keys.push(key(
            &format!("{name}.enabled"),
            move |p| p.event_commands.get(event).enabled().to_string(),
            move |p, v| {
                let line = p.event_commands.get(event).command_line().to_owned();
                p.event_commands = p.event_commands.with(event, Command::new(boolean(v)?, &line)?);
                Ok(())
            },
        ));
        keys.push(key(
            &format!("{name}.command"),
            move |p| p.event_commands.get(event).command_line().to_owned(),
            move |p, v| {
                let enabled = p.event_commands.get(event).enabled();
                p.event_commands = p.event_commands.with(event, Command::new(enabled, v)?);
                Ok(())
            },
        ));
    }
    keys.extend([
        text_key("proxy.host", |p| p.proxy.host.clone(), |p, v| p.proxy.host = v),
        int_key("proxy.port", |p| p.proxy.port, |p, v| p.proxy.port = v),
        text_key("proxy.user", |p| p.proxy.user.clone(), |p, v| p.proxy.user = v),
        text_key("proxy.password", |p| p.proxy.password.clone(), |p, v| p.proxy.password = v),
        // A proxy type needs its host: read after it.
        name_key("proxy.type", |p| p.proxy.kind.name(), |p, v: ProxyType| p.proxy.kind = v),
    ]);
    keys
});

/// The keys of the preferences, in the order they are read.
pub fn keys() -> impl Iterator<Item = &'static str> {
    KEYS.iter().map(|key| key.name.as_str())
}

/// Every preference as the file writes it, by key.
pub fn values(preferences: &Preferences) -> BTreeMap<String, String> {
    KEYS.iter().map(|key| (key.name.clone(), (key.get)(preferences))).collect()
}

/// `preferences` with `changes` applied as the file's values are read, in the order of the keys; an unknown key or a
/// wrong value is refused, naming its key.
pub fn apply(preferences: &Preferences, changes: &BTreeMap<String, String>) -> Result<Preferences, String> {
    if let Some(unknown) = changes.keys().find(|name| !is_key(name)) {
        return Err(format!("Unknown preference: {unknown}"));
    }
    let mut applied = preferences.clone();
    for key in KEYS.iter() {
        if let Some(value) = changes.get(&key.name) {
            applied = set(key, &applied, value).map_err(|reason| format!("{}: {reason}", key.name))?;
        }
    }
    Ok(applied)
}

/// Loads the preferences, or the defaults if the file and its backup are missing. A file another program holds fails
/// rather than yield the older backup, which saving would then make the file.
pub fn load(file: &Path) -> io::Result<Preferences> {
    match atomic_files::read_unless_held(file, |data| Ok(parse(&String::from_utf8_lossy(data)))) {
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(Preferences::default()),
        read => read,
    }
}

/// Writes the preferences, for their owner only as they hold the proxy and mail passwords; keys of the file this
/// version does not know stay, so that an older version loses none.
pub fn save(file: &Path, preferences: &Preferences) -> io::Result<()> {
    let mut unknown = property_lines::read(file).unwrap_or_default();
    unknown.retain(|name, _| !is_key(name));
    let mut text = format(preferences);
    if !unknown.is_empty() {
        text.push_str("# Not used by this version of njMule, kept as they were.\n");
        for (name, value) in &unknown {
            property_lines::append(&mut text, name, value);
        }
    }
    atomic_files::write_secret(file, text.as_bytes())
}

/// The preferences of the text of a file, each invalid value keeping its default.
pub fn parse(text: &str) -> Preferences {
    let properties = property_lines::parse(text);
    for name in properties.keys().filter(|name| !is_key(name)) {
        tracing::debug!("Keeping the unknown preference {name} as it is");
    }
    let mut preferences = Preferences::default();
    for key in KEYS.iter() {
        if let Some(value) = properties.get(&key.name) {
            match set(key, &preferences, value) {
                Ok(set) => preferences = set,
                Err(reason) => {
                    tracing::warn!("Invalid value for preference {}, keeping the default: {reason}", key.name)
                }
            }
        }
    }
    preferences
}

/// The text of the file: a comment, then every preference in the order of the keys.
pub fn format(preferences: &Preferences) -> String {
    let mut text = "# njMule preferences - edited by the application; invalid values revert to defaults.\n".to_owned();
    for (name, value) in values(preferences) {
        property_lines::append(&mut text, &name, &value);
    }
    text
}

fn set(key: &Key, preferences: &Preferences, value: &str) -> Result<Preferences, String> {
    let mut candidate = preferences.clone();
    (key.set)(&mut candidate, value)?;
    candidate.validated()
}

fn is_key(name: &str) -> bool {
    KEYS.iter().any(|key| key.name == name)
}

fn key(
    name: &str,
    get: impl Fn(&Preferences) -> String + Send + Sync + 'static,
    set: impl Fn(&mut Preferences, &str) -> Result<(), String> + Send + Sync + 'static,
) -> Key {
    Key { name: name.to_owned(), get: Box::new(get), set: Box::new(set) }
}

fn text_key(name: &str, get: fn(&Preferences) -> String, set: fn(&mut Preferences, String)) -> Key {
    key(name, get, move |p, v| {
        set(p, v.to_owned());
        Ok(())
    })
}

fn int_key<T: FromStr + ToString + 'static>(
    name: &str,
    get: fn(&Preferences) -> T,
    set: fn(&mut Preferences, T),
) -> Key {
    key(
        name,
        move |p| get(p).to_string(),
        move |p, v| {
            set(p, strip(v).parse().map_err(|_| format!("not a number in range: {v}"))?);
            Ok(())
        },
    )
}

fn bool_key(name: &str, get: fn(&Preferences) -> bool, set: fn(&mut Preferences, bool)) -> Key {
    key(
        name,
        move |p| get(p).to_string(),
        move |p, v| {
            set(p, boolean(v)?);
            Ok(())
        },
    )
}

fn name_key<T: FromStr + 'static>(
    name: &str,
    get: fn(&Preferences) -> &'static str,
    set: fn(&mut Preferences, T),
) -> Key {
    key(
        name,
        move |p| get(p).to_owned(),
        move |p, v| {
            set(p, parse_name(v)?);
            Ok(())
        },
    )
}

fn path_key(name: &str, get: fn(&Preferences) -> Option<PathBuf>, set: fn(&mut Preferences, Option<PathBuf>)) -> Key {
    key(
        name,
        move |p| get(p).map(|path| path.display().to_string()).unwrap_or_default(),
        move |p, v| {
            let path = strip(v);
            set(p, (!path.is_empty()).then(|| PathBuf::from(path)));
            Ok(())
        },
    )
}

fn parse_name<T: FromStr>(value: &str) -> Result<T, String> {
    strip(value).parse().map_err(|_| format!("not a value of this preference: {value}"))
}

fn boolean(value: &str) -> Result<bool, String> {
    match strip(value).to_lowercase().as_str() {
        "true" | "1" | "yes" | "on" => Ok(true),
        "false" | "0" | "no" | "off" => Ok(false),
        _ => Err(format!("not a boolean: {value}")),
    }
}
