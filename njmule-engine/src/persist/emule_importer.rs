// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Takes what njMule can use from an eMule profile. The eMule directories are only read: every file is checked before
//! anything is taken from it, and the report says what happened to each one.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs;
use std::io;
use std::path::{Component, Path, PathBuf};

use data_encoding::{BASE64, HEXLOWER};
use sha2::{Digest, Sha256};
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;

use crate::api::category::{self, Category};
use crate::api::download_info::DownloadPriority;
use crate::api::emule_history::EmuleHistory;
use crate::api::import_options::ImportOptions;
use crate::api::import_report::{Arg, ImportReport, Item, Key, Outcome, Phrase};
use crate::config::bind_interface;
use crate::config::next_download::NextDownload;
use crate::config::obfuscation_mode::ObfuscationMode;
use crate::config::preferences::{self, Preferences};
use crate::config::proxy_settings::{ProxySettings, ProxyType};
use crate::config::shared_files_visibility::SharedFilesVisibility;
use crate::crypto::rsa_keys;
use crate::persist::atomic_files;
use crate::persist::format::ini_file::IniFile;
use crate::persist::format::known_met::KnownFile;
use crate::persist::format::{
    cancelled_met, clients_met, friends_met, ip_filter_dat, known_met, known2_met, nodes_dat, part_met,
    preferences_dat, preferences_kad_dat, search_files, server_met, text_files,
};
use crate::persist::user_hash_store;
use crate::util::white_space::strip;

/// Where the import goes; the engine applies the changes on its own task.
pub trait Target {
    fn config_dir(&self) -> PathBuf;

    fn update_preferences(
        &mut self,
        change: &dyn Fn(&Preferences) -> Result<Preferences, String>,
    ) -> Result<(), String>;

    /// Adds the servers of a `server.met`; returns how many were new.
    fn add_servers(&mut self, server_met: &Path) -> Result<usize, String>;

    /// Adds Kad contacts; returns how many were new.
    fn add_kad_contacts(&mut self, contacts: &nodes_dat::Contents) -> Result<usize, String>;

    /// Remembers hashes of files, so shared files need not be hashed again.
    fn add_known_files(&mut self, files: &[KnownFile], trees: &[known2_met::Entry]) -> usize;

    fn share_directory(&mut self, directory: &Path) -> Result<(), String>;

    /// Takes these categories when we have none of our own; returns how many were taken.
    fn add_categories(&mut self, categories: &[Category]) -> Result<usize, String>;

    /// Downloads on from a copy of another client's part file, in `category` (0 for none); returns the file name.
    fn adopt_download(&mut self, met: &Path, data: &Path, category: u32) -> Result<String, String>;
}

/// Under the config directory: the identity files waiting for the next start.
pub const PENDING_IDENTITY: &str = "import-identity";
/// Under the config directory: imported files that the running engine would overwrite, waiting for its start.
pub const PENDING_FILES: &str = "import-files";
/// Under the config directory: a manifest for each import, naming every file read from eMule with its size and
/// SHA-256 and what became of it.
pub const MANIFESTS: &str = "import";

const SECTION: &str = "eMule";
const PROXY: &str = "Proxy";
// Limits at or above this mean "no limit".
const UNLIMITED: i64 = 65_535;
// Far beyond the files of real profiles, and still well within the memory of the engine.
const MAX_FILE_SIZE: u64 = 256 * 1024 * 1024;
const MIB: u64 = 1024 * 1024;
const SERVER_LIST_HISTORY: &str = "AC_ServerMetURLs.dat";
const SEARCH_HISTORY: &str = "AC_SearchStrings.dat";
// A box offers a few entries: more are of no use, from a damaged file too.
const MAX_TYPED: usize = 50;
const KAD_KEY_FILE: &str = "kadudpkey.dat";
const KEY_FILE: &str = "cryptkey.dat";
const IDENTITY: [&str; 5] = [user_hash_store::FILE_NAME, KEY_FILE, "clients.met", "preferencesKad.dat", KAD_KEY_FILE];
// Kept up to date by the engine while it runs, which would save its own over them.
const LIVE: [&str; 6] =
    ["cancelled.met", "emfriends.met", "StoredSearches.met", "SearchSpam.met", "fileinfo.ini", "staticservers.dat"];

pub struct EmuleImporter<'a> {
    emule: PathBuf,
    options: ImportOptions,
    target: &'a mut dyn Target,
    emule_running: &'a dyn Fn() -> bool,
    items: Vec<Item>,
    // The size and SHA-256 of each file read, by name, for the manifest.
    fingerprints: HashMap<String, String>,
    // The ids of eMule's categories taken over: only downloads in these keep theirs.
    categories_taken: HashSet<u32>,
}

/// eMule's configuration folder of this computer's user, in the local then the roaming application data that `env`
/// names, if one is there.
pub fn find_config_directory(env: &BTreeMap<String, String>) -> Option<PathBuf> {
    ["LOCALAPPDATA", "APPDATA"].into_iter().find_map(|variable| {
        let base = env.get(variable).filter(|base| !strip(base).is_empty())?;
        let config = Path::new(base).join("eMule").join("config");
        (config.is_absolute() && config.is_dir()).then_some(config)
    })
}

/// What was typed in eMule's boxes, as an import kept it in njMule's configuration directory `config`.
pub fn history(config: &Path) -> EmuleHistory {
    EmuleHistory {
        searches: typed(&config.join(SEARCH_HISTORY)),
        server_lists: typed(&config.join(SERVER_LIST_HISTORY)),
    }
}

// A box's entries, the newest first and each once; none when the file is not there or cannot be read.
fn typed(file: &Path) -> Vec<String> {
    let read = fs::metadata(file).and_then(|metadata| {
        if metadata.len() > MAX_FILE_SIZE {
            return Err(io::Error::other("too large to be eMule's"));
        }
        fs::read(file)
    });
    match read {
        Ok(data) => {
            let mut seen = HashSet::new();
            text_files::entries(&data).into_iter().filter(|entry| seen.insert(entry.clone())).take(MAX_TYPED).collect()
        }
        Err(e) if e.kind() == io::ErrorKind::NotFound => Vec::new(),
        Err(e) => {
            tracing::warn!("Cannot read {}: {e}", file.display());
            Vec::new()
        }
    }
}

/// Whether an eMule runs on this computer.
pub fn is_emule_running() -> bool {
    let Ok(mut system) = sysinfo::System::new() else {
        tracing::warn!("Cannot list the processes to see whether eMule runs");
        return false;
    };
    system.refresh_processes(sysinfo::ProcessesToUpdate::All, true);
    system
        .processes()
        .values()
        .any(|process| process.name().to_string_lossy().to_lowercase().ends_with("emule.exe"))
}

/// Before the engine reads its identity: moves an imported identity into `config_dir`, unless an eMule runs, since two
/// clients with one identity spoil each other's credits. Returns whether an identity was taken over.
pub fn apply_pending_identity(config_dir: &Path, emule_running: &dyn Fn() -> bool) -> io::Result<bool> {
    let pending = config_dir.join(PENDING_IDENTITY);
    if !pending.is_dir() {
        return Ok(false);
    }
    if emule_running() {
        tracing::warn!("eMule runs: its identity is taken over once it is closed");
        return Ok(false);
    }
    // Our key with another user hash would fail every identification, and the reverse.
    let complete = pending.join(user_hash_store::FILE_NAME).is_file() && pending.join(KEY_FILE).is_file();
    if complete {
        // Every file is in place before the folder goes: a start that fails half way takes all of them again.
        for name in IDENTITY {
            let file = pending.join(name);
            if file.is_file() {
                atomic_files::write_secret(&config_dir.join(name), &fs::read(&file)?)?;
            }
        }
        tracing::info!("Using the identity imported from eMule");
    } else {
        tracing::warn!("Leaving out the identity imported from eMule: its user hash or key is missing");
    }
    remove_folder(&pending);
    Ok(complete)
}

/// Before the engine reads its files: puts the imported ones it keeps up to date in place, over what it saved since the
/// import. Returns how many were taken.
pub fn apply_pending_files(config_dir: &Path) -> usize {
    let pending = config_dir.join(PENDING_FILES);
    if !pending.is_dir() {
        return 0;
    }
    let mut taken = 0_usize;
    for name in LIVE {
        let file = pending.join(name);
        if file.is_file() {
            if let Err(e) = fs::read(&file).and_then(|data| atomic_files::write(&config_dir.join(name), &data)) {
                tracing::warn!("Cannot take the files imported from eMule, trying again at the next start: {e}");
                return taken;
            }
            taken = taken.saturating_add(1);
        }
    }
    tracing::info!("Using {taken} files imported from eMule");
    remove_folder(&pending);
    taken
}

// With what an interrupted or repeated import left in it, folders too; a folder that stays is taken again at the next
// start.
fn remove_folder(folder: &Path) {
    match fs::remove_dir_all(folder) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => tracing::warn!("Cannot remove {}: {e}", folder.display()),
        _ => {}
    }
}

/// Our preferences with eMule's values where it has usable ones.
pub fn map_preferences(ini: &IniFile, current: &Preferences) -> Result<Preferences, String> {
    let mut p = current.clone();
    if let Some(nick) = ini.get(SECTION, "Nick").map(strip).filter(|nick| preferences::is_valid_nick(nick)) {
        nick.clone_into(&mut p.nick);
    }
    if let Some(v) = limit(ini, "MaxUpload") {
        p.max_upload_kibps = v;
    }
    if let Some(v) = limit(ini, "MaxDownload") {
        p.max_download_kibps = v;
    }
    // An unknown capacity is stored as "unlimited", which says nothing about the line.
    if let Some(v) = capacity(ini, "DownloadCapacity") {
        p.download_capacity_kibps = v;
    }
    if let Some(v) = capacity(ini, "UploadCapacityNew") {
        p.upload_capacity_kibps = v;
    }
    // eMule keeps a minimum in bytes, used only while its check is on; ours is always on. The unsigned 32-bit count is
    // written signed, so 2 GiB and more come out negative.
    if ini.get_boolean(SECTION, "CheckDiskspace").unwrap_or(false)
        && let Some(v) = ini.get_long(SECTION, "MinFreeDiskSpace")
    {
        let bytes = if v < 0 { v & 0xFFFF_FFFF } else { v };
        p.min_free_space_mib = clamp(bytes / 1024 / 1024, 0, preferences::MAX_MIN_FREE_SPACE);
    }
    if let Some(v) = ini.get_long(SECTION, "MaxConnections") {
        p.max_connections = clamp(v, 1, 10_000);
    }
    if let Some(v) = ini.get_long(SECTION, "MaxSourcesPerFile") {
        p.max_sources_per_file = clamp(v, 1, 10_000);
    }
    // The address eMule's sockets are bound to; 0.0.0.0 is eMule's own "any".
    if let Some(address) =
        ini.get(SECTION, "BindAddr").map(strip).filter(|a| bind_interface::is_address(a) && *a != "0.0.0.0")
    {
        address.clone_into(&mut p.bind_interface);
    }
    let flag = |key: &str, field: &mut bool| {
        if let Some(value) = ini.get_boolean(SECTION, key) {
            *field = value;
        }
    };
    flag("Reconnect", &mut p.reconnect);
    flag("Autoconnect", &mut p.auto_connect);
    if let Some(v) = ini.get_long(SECTION, "DeadServerRetry") {
        p.dead_server_retries = clamp(v, 1, preferences::MAX_DEAD_SERVER_RETRIES);
    }
    flag("AddServersFromServer", &mut p.add_servers_from_server);
    flag("AddServersFromClient", &mut p.add_servers_from_clients);
    flag("AutoConnectStaticOnly", &mut p.static_servers_only);
    flag("ManualHighPrio", &mut p.manual_servers_high_priority);
    flag("Serverlist", &mut p.update_servers_at_start);
    flag("RememberDownloadedFiles", &mut p.remember_downloaded_files);
    flag("RememberCancelledFiles", &mut p.remember_cancelled_files);
    flag("StoreSearches", &mut p.store_searches);
    flag("AddNewFilesPaused", &mut p.add_downloads_paused);
    if let Some(v) = ini.get_long(SECTION, "StartNextFile") {
        p.next_download = next_download(v);
    }
    flag("FilterBadIPs", &mut p.filter_lan_addresses);
    flag("MessagesFromFriendsOnly", &mut p.messages_from_friends_only);
    flag("EnableSearchResultSpamFilter", &mut p.search_spam_filter);
    if let Some(filter) = ini.get(SECTION, "MessageFilter") {
        p.message_filter = shortened(filter, preferences::MAX_MESSAGE_FILTER).to_owned();
    }
    flag("AdvancedSpamFilter", &mut p.advanced_spam_filter);
    if let Some(filter) = ini.get(SECTION, "CommentFilter").filter(|v| fits(v, preferences::MAX_MESSAGE_FILTER)) {
        filter.clone_into(&mut p.comment_filter);
    }
    if let Some(cleanups) = ini.get(SECTION, "FilenameCleanups").filter(|v| fits(v, preferences::MAX_MESSAGE_FILTER)) {
        cleanups.clone_into(&mut p.name_cleanups);
    }
    flag("AutoFilenameCleanup", &mut p.clean_up_new_names);
    if let Some(v) = ini.get_long(SECTION, "MaxConnectionsPerFiveSeconds") {
        p.new_connections_per_five_seconds = clamp(v, 1, preferences::MAX_NEW_CONNECTIONS);
    }
    flag("PreviewPrio", &mut p.preview_first);
    if let Some(player) = ini.get(SECTION, "VideoPlayer") {
        player.clone_into(&mut p.video_player);
    }
    if let Some(args) = ini.get(SECTION, "VideoPlayerArgs") {
        args.clone_into(&mut p.video_player_args);
    }
    // NotifierUseSound: 0 none, 1 the sound file, 2 speech, which njMule has not.
    if let Some(v) = ini.get_long(SECTION, "NotifierUseSound") {
        p.notification_sound = v == 1;
    }
    if let Some(sound) = ini.get(SECTION, "NotifierSoundPath").and_then(path) {
        p.notification_sound_file = Some(sound);
    }
    // SeeShare: 0 everybody, 1 friends, 2 nobody.
    if let Some(v) = ini.get_long(SECTION, "SeeShare") {
        p.shared_files_visibility = visibility(v);
    }
    if let Some(v) = ini.get_long(SECTION, "QueueSize") {
        p.upload_queue_size = clamp(v, preferences::MIN_UPLOAD_QUEUE, preferences::MAX_UPLOAD_QUEUE);
    }
    if let Some(v) = ini.get_long(SECTION, "FilterLevel") {
        p.ip_filter_level = clamp(v, 0, preferences::MAX_IP_FILTER_LEVEL);
    }
    flag("NetworkED2K", &mut p.ed2k_enabled);
    flag("NetworkKademlia", &mut p.kad_enabled);
    flag("SecureIdent", &mut p.secure_ident);
    flag("UseCreditSystem", &mut p.credit_system);
    p.obfuscation = obfuscation(ini, current.obfuscation);
    if let Some(proxy) = proxy(ini) {
        p.proxy = proxy;
    }
    p.validated()
}

// SeeShare: 0 everybody, 1 friends, 2 nobody.
fn visibility(value: i64) -> SharedFilesVisibility {
    match value {
        0 => SharedFilesVisibility::Everybody,
        1 => SharedFilesVisibility::Friends,
        _ => SharedFilesVisibility::Nobody,
    }
}

// StartNextFile: 0 none, 3 the same category only; any other value starts one, 2 and up of the same category first.
fn next_download(value: i64) -> NextDownload {
    match value {
        0 => NextDownload::None,
        3 => NextDownload::SameCategory,
        v if v >= 2 => NextDownload::SameCategoryFirst,
        _ => NextDownload::Any,
    }
}

// eMule's proxy types: 1-3 SOCKS 4, 4a and 5, 4-5 HTTP 1.0 and 1.1.
fn proxy(ini: &IniFile) -> Option<ProxySettings> {
    if !ini.get_boolean(PROXY, "ProxyEnableProxy").unwrap_or(false) {
        return None;
    }
    let kind = ini.get_long(PROXY, "ProxyType").unwrap_or(0);
    if !(1..=5).contains(&kind) {
        return None;
    }
    let password = ini.get_boolean(PROXY, "ProxyEnablePassword").unwrap_or(false);
    let credential =
        |key: &str| if password { ini.get(PROXY, key).unwrap_or_default().to_owned() } else { String::new() };
    ProxySettings {
        kind: if kind <= 3 { ProxyType::Socks } else { ProxyType::Http },
        host: ini.get(PROXY, "ProxyName").unwrap_or_default().to_owned(),
        port: u16::try_from(ini.get_long(PROXY, "ProxyPort").unwrap_or(1080)).ok()?,
        user: credential("ProxyUser"),
        password: credential("ProxyPassword"),
    }
    .validated()
    .ok()
}

fn capacity(ini: &IniFile, key: &str) -> Option<u32> {
    limit(ini, key).filter(|&v| v > 0).map(|v| v.min(preferences::MAX_RATE_KIBPS))
}

fn limit(ini: &IniFile, key: &str) -> Option<u32> {
    ini.get_long(SECTION, key).map(|v| if v <= 0 || v >= UNLIMITED { 0 } else { clamp(v, 0, u32::MAX) })
}

fn clamp(value: i64, min: u32, max: u32) -> u32 {
    u32::try_from(value.clamp(min.into(), max.into())).unwrap_or(min)
}

fn obfuscation(ini: &IniFile, current: ObfuscationMode) -> ObfuscationMode {
    let supported = ini.get_boolean(SECTION, "CryptLayerSupported");
    let requested = ini.get_boolean(SECTION, "CryptLayerRequested");
    let required = ini.get_boolean(SECTION, "CryptLayerRequired");
    if supported.is_none() && requested.is_none() && required.is_none() {
        return current;
    }
    ObfuscationMode::from_flags(supported.unwrap_or(true), requested.unwrap_or(false), required.unwrap_or(false))
}

/// eMule keeps any colour (0x00BBGGRR); ours are a few tints of the theme: the one of the nearest hue, none for greys.
pub fn color(value: Option<i64>) -> u32 {
    let Some(c) = value.filter(|&c| c > 0 && c <= 0xFF_FFFF) else {
        return 0;
    };
    let channel = |shift: u32| f64::from(u8::try_from((c >> shift) & 0xFF).unwrap_or(0));
    let (r, g, b) = (channel(0), channel(8), channel(16));
    let max = r.max(g).max(b);
    let min = r.min(g).min(b);
    let delta = max - min;
    if delta < 24.0 {
        return 0;
    }
    let hue = if max == r {
        60.0 * (((g - b) / delta) % 6.0)
    } else if max == g {
        60.0 * ((b - r) / delta + 2.0)
    } else {
        60.0 * ((r - g) / delta + 4.0)
    };
    let hue = (hue + 360.0) % 360.0;
    // Red, orange, yellow, green, teal, blue, purple, pink: 45 degrees each, red around 0.
    let sector = ((hue + 22.5) % 360.0 / 45.0) as u32;
    sector.saturating_add(1)
}

impl<'a> EmuleImporter<'a> {
    /// `emule` is eMule's configuration directory; `emule_running` tells whether an eMule runs now.
    pub fn new(
        emule: &Path,
        options: ImportOptions,
        target: &'a mut dyn Target,
        emule_running: &'a dyn Fn() -> bool,
    ) -> Self {
        Self {
            emule: emule.to_path_buf(),
            options,
            target,
            emule_running,
            items: Vec::new(),
            fingerprints: HashMap::new(),
            categories_taken: HashSet::new(),
        }
    }

    pub fn run(mut self) -> ImportReport {
        let emule = self.emule.clone();
        // njMule writes into its configuration directory, which must never be eMule's.
        if canonical(&self.target.config_dir()).starts_with(canonical(&emule)) {
            self.report(&emule, Outcome::Failed, vec![phrase(Key::InsideEmule, vec![])]);
            return ImportReport { dry_run: self.options.dry_run, items: self.items, start_at_login: false };
        }
        let ini = self.preferences();
        self.with(&emule.join("server.met"), Self::servers);
        self.with(&emule.join("nodes.dat"), Self::nodes);
        self.with(&emule.join("known.met"), Self::known);
        self.shared_directories(ini.as_ref());
        self.with(&emule.join("sharedfiles.dat"), |_, file, data| Ok(shared_files(file, data)));
        self.with(&emule.join("ipfilter.dat"), Self::ip_filter);
        self.kept("cancelled.met", |data| {
            Ok(phrase(Key::Files, vec![cancelled_met::read(data).map_err(|e| e.to_string())?.keys.len().into()]))
        });
        self.kept("emfriends.met", |data| {
            Ok(phrase(Key::Friends, vec![friends_met::read(data).map_err(|e| e.to_string())?.len().into()]))
        });
        self.kept("StoredSearches.met", |data| {
            Ok(phrase(Key::Searches, vec![search_files::read_stored(data).map_err(|e| e.to_string())?.len().into()]))
        });
        self.kept("SearchSpam.met", |data| {
            Ok(phrase(Key::SpamMarkers, vec![search_files::read_spam(data).map_err(|e| e.to_string())?.len().into()]))
        });
        self.kept("fileinfo.ini", |data| Ok(phrase(Key::Comments, vec![IniFile::parse(data).sections().len().into()])));
        self.kept("staticservers.dat", |data| {
            Ok(phrase(Key::Servers, vec![text_files::static_servers(data).len().into()]))
        });
        self.kept("addresses.dat", |data| Ok(phrase(Key::Addresses, vec![text_files::lines(data).len().into()])));
        // The addresses and searches typed in eMule's boxes, which `history` gives the interfaces to offer again.
        for history in [SERVER_LIST_HISTORY, SEARCH_HISTORY] {
            self.kept(history, |data| Ok(phrase(Key::Entries, vec![text_files::entries(data).len().into()])));
        }
        self.categories(ini.as_ref());
        for index in ["key_index.dat", "src_index.dat", "load_index.dat"] {
            let file = emule.join(index);
            if file.exists() {
                self.report(&file, Outcome::Skipped, vec![phrase(Key::ShortLived, vec![])]);
            }
        }
        self.identity(ini.as_ref());
        self.downloads(ini.as_ref());
        // Nothing to list when no file of the profile was there to read.
        if !self.options.dry_run && !self.fingerprints.is_empty() {
            self.write_manifest();
        }
        ImportReport {
            dry_run: self.options.dry_run,
            items: self.items,
            start_at_login: ini.as_ref().is_some_and(starts_at_login),
        }
    }

    // Nick, limits, networks, obfuscation and credits; ports and directories stay njMule's own.
    fn preferences(&mut self) -> Option<IniFile> {
        let file = self.emule.join("preferences.ini");
        if !file.exists() {
            self.report_text(&file, Outcome::Missing, "");
            return None;
        }
        let ini = match self.read(&file) {
            Ok(data) => IniFile::parse(&data),
            Err(e) => {
                self.report_text(&file, Outcome::Failed, &e);
                return None;
            }
        };
        if !ini.has_section(SECTION) {
            self.report(&file, Outcome::Failed, vec![phrase(Key::NoSection, vec![SECTION.into()])]);
            return None;
        }
        let mut detail = vec![phrase(Key::Preferences, vec![])];
        if starts_at_login(&ini) {
            detail.push(phrase(Key::StartAtLogin, vec![]));
        }
        detail.push(phrase(Key::PortsOurs, vec![]));
        if self.options.dry_run {
            self.report(&file, Outcome::WouldImport, detail);
            return Some(ini);
        }
        match self.target.update_preferences(&|current| map_preferences(&ini, current)) {
            Ok(()) => self.report(&file, Outcome::Imported, detail),
            Err(e) => self.report_text(&file, Outcome::Failed, &e),
        }
        Some(ini)
    }

    fn servers(&mut self, file: &Path, data: &[u8]) -> Result<Item, String> {
        let count = server_met::read(data).map_err(|e| e.to_string())?.len();
        if self.options.dry_run {
            return Ok(item(file, Outcome::WouldImport, vec![phrase(Key::Servers, vec![count.into()])]));
        }
        let added = self.target.add_servers(file)?;
        Ok(item(
            file,
            Outcome::Imported,
            vec![phrase(Key::Servers, vec![count.into()]), phrase(Key::Added, vec![added.into()])],
        ))
    }

    fn nodes(&mut self, file: &Path, data: &[u8]) -> Result<Item, String> {
        let contents = nodes_dat::read(data).map_err(|e| e.to_string())?;
        let key = if contents.bootstrap { Key::BootstrapNodes } else { Key::KadContacts };
        let what = phrase(key, vec![contents.contacts.len().into()]);
        if self.options.dry_run {
            return Ok(item(file, Outcome::WouldImport, vec![what]));
        }
        let added = self.target.add_kad_contacts(&contents)?;
        Ok(item(file, Outcome::Imported, vec![what, phrase(Key::Added, vec![added.into()])]))
    }

    fn known(&mut self, file: &Path, data: &[u8]) -> Result<Item, String> {
        let files = known_met::read(data).map_err(|e| e.to_string())?;
        let mut trees = Vec::new();
        let mut left_out = None;
        let aich = self.emule.join("known2_64.met");
        if aich.exists() {
            match self.read(&aich).and_then(|data| known2_met::read(&data).map_err(|e| e.to_string())) {
                Ok(read) => trees = read,
                // The trees only spare hashing the shared files again.
                Err(e) => left_out = Some(phrase(Key::LeftOut, vec![name(&aich).into(), e.into()])),
            }
        }
        let mut what =
            vec![phrase(Key::KnownFiles, vec![files.len().into()]), phrase(Key::AichTrees, vec![trees.len().into()])];
        what.extend(left_out);
        if self.options.dry_run {
            return Ok(item(file, Outcome::WouldImport, what));
        }
        what.push(phrase(Key::Added, vec![self.target.add_known_files(&files, &trees).into()]));
        Ok(item(file, Outcome::Imported, what))
    }

    // eMule shares its incoming directory and those of shareddir.dat, each without subdirectories.
    fn shared_directories(&mut self, ini: Option<&IniFile>) {
        let file = self.emule.join("shareddir.dat");
        let mut dirs: Vec<PathBuf> = Vec::new();
        let mut add = |dir: PathBuf| {
            if dir.is_dir() && !dirs.contains(&dir) {
                dirs.push(dir);
            }
        };
        if let Some(incoming) = ini.and_then(|ini| ini.get(SECTION, "IncomingDir")).and_then(path) {
            add(incoming);
        }
        if file.exists() {
            match self.read(&file) {
                Ok(data) => text_files::entries(&data).iter().filter_map(|entry| path(entry)).for_each(&mut add),
                Err(e) => {
                    self.report_text(&file, Outcome::Failed, &e);
                    return;
                }
            }
        }
        if dirs.is_empty() {
            self.report(&file, Outcome::Missing, vec![phrase(Key::NoSharedFolder, vec![])]);
            return;
        }
        if self.options.dry_run {
            self.report(&file, Outcome::WouldImport, vec![phrase(Key::Folders, vec![dirs.len().into()])]);
            return;
        }
        // A directory refused (a private one) or failing does not stop the others.
        let mut shared = 0_usize;
        let mut failure = String::new();
        for dir in &dirs {
            match self.target.share_directory(dir) {
                Ok(()) => shared = shared.saturating_add(1),
                Err(e) => failure = e,
            }
        }
        if shared == 0 {
            self.report_text(&file, Outcome::Failed, &failure);
            return;
        }
        let mut what = vec![phrase(Key::Folders, vec![shared.into()])];
        let skipped = dirs.len().saturating_sub(shared);
        if skipped > 0 {
            what.push(phrase(Key::NotShared, vec![skipped.into(), failure.into()]));
        }
        self.report(&file, Outcome::Imported, what);
    }

    fn ip_filter(&mut self, file: &Path, data: &[u8]) -> Result<Item, String> {
        let ranges = phrase(Key::Ranges, vec![ip_filter_dat::read(data, &name(file)).len().into()]);
        let ours = self.target.config_dir().join("ipfilter.dat");
        if ours.exists() {
            return Ok(item(file, Outcome::Skipped, vec![ranges, phrase(Key::OurOwn, vec![])]));
        }
        if self.options.dry_run {
            return Ok(item(file, Outcome::WouldImport, vec![ranges]));
        }
        atomic_files::write(&ours, data).map_err(|e| e.to_string())?;
        Ok(item(file, Outcome::Imported, vec![ranges, phrase(Key::NextStart, vec![])]))
    }

    // eMule's categories keep their numbers, so downloads taken from its temp folder stay in theirs.
    fn categories(&mut self, preferences: Option<&IniFile>) {
        // eMule's own incoming folder is never ours: those categories finish into njMule's.
        let emule_incoming = preferences.and_then(|p| p.get(SECTION, "IncomingDir")).and_then(path);
        let file = self.emule.join("Category.ini");
        self.with(&file, |this, file, data| {
            let ini = IniFile::parse(data);
            let mut found = Vec::new();
            let mut notes = Vec::new();
            for section in ini.sections() {
                // Cat#0 is the list of all downloads, not a category.
                let Some(id) = category_id(section).filter(|&id| id >= 1) else { continue };
                // eMule allows longer texts than ours: they are cut rather than the category lost.
                let title = shortened(strip(ini.get(section, "Title").unwrap_or_default()), category::MAX_NAME);
                let incoming =
                    ini.get(section, "Incoming").and_then(path).filter(|dir| emule_incoming.as_ref() != Some(dir));
                let made = |patterns: &str, regex: bool| {
                    Category {
                        id,
                        name: title.to_owned(),
                        incoming: incoming.clone(),
                        comment: shortened(
                            strip(ini.get(section, "Comment").unwrap_or_default()),
                            category::MAX_COMMENT,
                        )
                        .to_owned(),
                        color: color(ini.get_long(section, "Color")),
                        priority: DownloadPriority::Auto,
                        patterns: patterns.to_owned(),
                        regex,
                    }
                    .validated()
                };
                let patterns =
                    shortened(strip(ini.get(section, "AutoCat").unwrap_or_default()), category::MAX_PATTERNS);
                let regex = ini.get_boolean(section, "AutoCatAsRegularExpression").unwrap_or(false);
                match made(patterns, regex) {
                    Ok(category) => found.push(category),
                    // Patterns we cannot compile: the category comes without them.
                    Err(e) => match made("", false) {
                        Ok(category) => {
                            found.push(category);
                            notes.push(phrase(Key::WithoutPatterns, vec![title.into()]));
                        }
                        Err(_) => tracing::info!("Leaving out the category {section} of {}: {e}", file.display()),
                    },
                }
            }
            if found.is_empty() {
                return Ok(item(file, Outcome::Skipped, vec![phrase(Key::NoCategory, vec![])]));
            }
            if this.options.dry_run {
                return Ok(with_notes(
                    file,
                    Outcome::WouldImport,
                    phrase(Key::Categories, vec![found.len().into()]),
                    notes,
                ));
            }
            let added = this.target.add_categories(&found)?;
            if added == found.len() {
                this.categories_taken.extend(found.iter().map(|category| category.id));
            }
            Ok(if added == 0 {
                item(
                    file,
                    Outcome::Skipped,
                    vec![phrase(Key::Categories, vec![found.len().into()]), phrase(Key::OurOwn, vec![])],
                )
            } else {
                with_notes(file, Outcome::Imported, phrase(Key::Categories, vec![added.into()]), notes)
            })
        });
    }

    /// Files checked, then kept unless njMule has its own; those the engine keeps up to date wait for its start.
    fn kept(&mut self, file_name: &str, reader: impl FnOnce(&[u8]) -> Result<Phrase, String>) {
        let file = self.emule.join(file_name);
        self.with(&file, |this, file, data| {
            let what = reader(data)?;
            let config = this.target.config_dir();
            let ours = config.join(file_name);
            let pending = config.join(PENDING_FILES);
            let staged = pending.join(file_name);
            let live = LIVE.contains(&file_name);
            // With a copy waiting from an earlier import, ours is only what the engine saved since.
            if ours.exists() && !(live && staged.exists()) {
                return Ok(item(file, Outcome::Skipped, vec![what, phrase(Key::OurOwn, vec![])]));
            }
            if this.options.dry_run {
                return Ok(item(file, Outcome::WouldImport, vec![what]));
            }
            if live {
                fs::create_dir_all(&pending).map_err(|e| e.to_string())?;
                atomic_files::write_with(&staged, false, false, |out| out.write_all(data))
                    .map_err(|e| e.to_string())?;
                return Ok(item(file, Outcome::Imported, vec![what, phrase(Key::NextStart, vec![])]));
            }
            atomic_files::write(&ours, data).map_err(|e| e.to_string())?;
            Ok(item(file, Outcome::Imported, vec![what]))
        });
    }

    // The user hash, key, credits and Kad identity belong together; they wait for the next start.
    fn identity(&mut self, ini: Option<&IniFile>) {
        let refusal = if !self.options.identity {
            Some(Key::OwnIdentity)
        } else if (self.emule_running)() {
            Some(Key::CloseEmule)
        } else {
            None
        };
        if let Some(refusal) = refusal {
            for file_name in IDENTITY.iter().take(IDENTITY.len().saturating_sub(1)) {
                let file = self.emule.join(file_name);
                if file.exists() {
                    self.report(&file, Outcome::Skipped, vec![phrase(refusal, vec![])]);
                }
            }
            return;
        }
        if !self.options.dry_run {
            // An identity waiting from an earlier import goes as a whole: its files must not mix with these.
            self.drop_pending_identity();
        }
        let hash = self.pending(user_hash_store::FILE_NAME, |data| {
            let contents = preferences_dat::read(data).map_err(|e| e.to_string())?;
            if !preferences_dat::is_marked_user_hash(&contents.user_hash) {
                return Err("not a usable user hash".to_owned());
            }
            Ok(phrase(Key::UserHash, vec![]))
        });
        let key = self.pending(KEY_FILE, |data| {
            let text: Vec<u8> =
                data.iter().copied().filter(|b| !matches!(b, b' ' | b'\t' | b'\n' | 0x0B | 0x0C | b'\r')).collect();
            let der = BASE64.decode(&text).map_err(|e| e.to_string())?;
            rsa_keys::decode_private(&der).map_err(|e| e.to_string())?;
            Ok(phrase(Key::IdentityKey, vec![]))
        });
        if !hash || !key {
            // Our key with eMule's user hash would fail every identification, and the reverse.
            if !self.options.dry_run {
                self.drop_pending_identity();
            }
            let emule = self.emule.clone();
            self.report(&emule, Outcome::Skipped, vec![phrase(Key::Together, vec![])]);
            return;
        }
        self.pending("clients.met", |data| {
            Ok(phrase(Key::Credits, vec![clients_met::read(data).map_err(|e| e.to_string())?.len().into()]))
        });
        self.pending("preferencesKad.dat", |data| {
            preferences_kad_dat::read(data).map_err(|e| e.to_string())?;
            Ok(phrase(Key::KadId, vec![]))
        });
        let udp_key = ini.and_then(|ini| ini.get_long(SECTION, "KadUDPKey")).unwrap_or(0) & 0xFFFF_FFFF;
        if !self.options.dry_run && udp_key != 0 {
            let pending = self.target.config_dir().join(PENDING_IDENTITY);
            let key = u32::try_from(udp_key).unwrap_or_default().to_le_bytes();
            if let Err(e) = fs::create_dir_all(&pending).and_then(|()| write_pending(&pending.join(KAD_KEY_FILE), &key))
            {
                self.report_text(&pending, Outcome::Failed, &e.to_string());
            }
        }
    }

    fn drop_pending_identity(&self) {
        remove_folder(&self.target.config_dir().join(PENDING_IDENTITY));
    }

    /// Checks an identity file and puts it aside for the next start; returns whether it could.
    fn pending(&mut self, file_name: &str, check: impl FnOnce(&[u8]) -> Result<Phrase, String>) -> bool {
        let file = self.emule.join(file_name);
        let outcome = self.with(&file, |this, file, data| {
            let what = check(data)?;
            if this.options.dry_run {
                return Ok(item(file, Outcome::WouldImport, vec![what]));
            }
            let pending = this.target.config_dir().join(PENDING_IDENTITY);
            fs::create_dir_all(&pending)
                .and_then(|()| write_pending(&pending.join(file_name), data))
                .map_err(|e| e.to_string())?;
            Ok(item(file, Outcome::Imported, vec![what, phrase(Key::NextStart, vec![])]))
        });
        matches!(outcome, Outcome::Imported | Outcome::WouldImport)
    }

    // Downloads are copied, never moved, and only while eMule does not write them.
    fn downloads(&mut self, ini: Option<&IniFile>) {
        let temps = self.temp_directories(ini);
        let Some(first) = temps.first().cloned() else {
            let temp = self.emule.join("Temp");
            self.report_text(&temp, Outcome::Missing, "");
            return;
        };
        if !self.options.downloads {
            self.report(&first, Outcome::Skipped, vec![phrase(Key::NotAsked, vec![])]);
            return;
        }
        let running = (self.emule_running)();
        let mut found = 0_usize;
        for temp in &temps {
            let mets = fs::read_dir(temp).and_then(|entries| {
                let mut mets = Vec::new();
                for entry in entries {
                    let path = entry?.path();
                    if name(&path).ends_with(".part.met") {
                        mets.push(path);
                    }
                }
                Ok(mets)
            });
            match mets {
                Ok(mut mets) => {
                    mets.sort();
                    for met in mets {
                        found = found.saturating_add(1);
                        self.with(&met, |this, file, data| this.download(file, data, running));
                    }
                }
                Err(e) => self.report_text(temp, Outcome::Failed, &e.to_string()),
            }
        }
        if found == 0 {
            self.report(&first, Outcome::Missing, vec![phrase(Key::NoDownloads, vec![])]);
        }
    }

    // TempDir, then the others of TempDirs separated by '|'; next to the config by default.
    fn temp_directories(&self, ini: Option<&IniFile>) -> Vec<PathBuf> {
        let get = |key: &str| ini.and_then(|ini| ini.get(SECTION, key)).unwrap_or_default().to_owned();
        let listed = format!("{}|{}", get("TempDir"), get("TempDirs"));
        let mut dirs: Vec<PathBuf> = Vec::new();
        for dir in listed.split('|').filter_map(path) {
            if dir.is_dir() && !dirs.contains(&dir) {
                dirs.push(dir);
            }
        }
        if dirs.is_empty()
            && let Some(temp) = self.emule.parent().map(|parent| parent.join("Temp")).filter(|temp| temp.is_dir())
        {
            dirs.push(temp);
        }
        dirs
    }

    fn download(&mut self, met: &Path, data: &[u8], running: bool) -> Result<Item, String> {
        let contents = part_met::read(data).map_err(|e| e.to_string())?;
        let met_name = name(met);
        let download_name = contents.name().map_or_else(|| met_name.clone(), str::to_owned);
        let part = met.with_file_name(met_name.strip_suffix(".met").unwrap_or(&met_name));
        if contents.size().is_none() || !part.is_file() {
            return Ok(item(met, Outcome::Failed, vec![phrase(Key::NoDataFile, vec![download_name.into()])]));
        }
        if running && !contents.paused() {
            return Ok(item(met, Outcome::Skipped, vec![phrase(Key::PauseFirst, vec![download_name.into()])]));
        }
        let mib = fs::metadata(&part).map_err(|e| e.to_string())?.len() / MIB;
        if self.options.dry_run {
            return Ok(item(
                met,
                Outcome::WouldImport,
                vec![phrase(Key::Download, vec![download_name.into(), mib.into()])],
            ));
        }
        // eMule's number names one of ours only when that category came with this import.
        let category = contents
            .category()
            .and_then(|category| u32::try_from(category).ok())
            .filter(|category| *category <= i32::MAX.unsigned_abs() && self.categories_taken.contains(category))
            .unwrap_or(category::NONE);
        let adopted = self.target.adopt_download(met, &part, category)?;
        Ok(item(met, Outcome::Imported, vec![phrase(Key::Copied, vec![adopted.into(), mib.into()])]))
    }

    // A file far larger than any of a real profile is refused rather than read into memory.
    fn read(&mut self, file: &Path) -> Result<Vec<u8>, String> {
        let size = fs::metadata(file).map_err(|e| e.to_string())?.len();
        if size > MAX_FILE_SIZE {
            return Err(format!("too large to be eMule's: {size} bytes"));
        }
        let data = fs::read(file).map_err(|e| e.to_string())?;
        self.fingerprints.insert(name(file), format!("{}\t{}", data.len(), sha256(&data)));
        Ok(data)
    }

    // What was read and what became of it, for whoever wants to check an import later.
    fn write_manifest(&self) {
        let now = OffsetDateTime::now_utc();
        let emule = std::path::absolute(&self.emule).unwrap_or_else(|_| self.emule.clone());
        let mut text = format!(
            "# njMule import from {} at {}\n# outcome\tbytes\tSHA-256\tfile\tdetail\n",
            emule.display(),
            now.format(&Rfc3339).unwrap_or_default()
        );
        for item in &self.items {
            let fingerprint = self.fingerprints.get(&item.file).map_or("\t", String::as_str);
            let detail: String = item.detail().chars().map(|c| if c.is_control() { ' ' } else { c }).collect();
            text.push_str(&format!("{}\t{fingerprint}\t{}\t{detail}\n", item.outcome.name(), item.file));
        }
        let folder = self.target.config_dir().join(MANIFESTS);
        // Named in UTC, as the time the manifest gives.
        let file_name = format!(
            "{:04}{:02}{:02}-{:02}{:02}{:02}.txt",
            now.year(),
            u8::from(now.month()),
            now.day(),
            now.hour(),
            now.minute(),
            now.second()
        );
        if let Err(e) =
            fs::create_dir_all(&folder).and_then(|()| atomic_files::write(&folder.join(file_name), text.as_bytes()))
        {
            tracing::warn!("Cannot write the import manifest in {}: {e}", folder.display());
        }
    }

    fn with(&mut self, file: &Path, step: impl FnOnce(&mut Self, &Path, &[u8]) -> Result<Item, String>) -> Outcome {
        let item = if file.exists() {
            match self.read(file).and_then(|data| step(self, file, &data)) {
                Ok(item) => item,
                Err(e) => Item::text(&name(file), Outcome::Failed, &e),
            }
        } else {
            Item::text(&name(file), Outcome::Missing, "")
        };
        let outcome = item.outcome;
        self.items.push(item);
        outcome
    }

    fn report(&mut self, file: &Path, outcome: Outcome, phrases: Vec<Phrase>) {
        self.items.push(item(file, outcome, phrases));
    }

    fn report_text(&mut self, file: &Path, outcome: Outcome, text: &str) {
        self.items.push(Item::text(&name(file), outcome, text));
    }
}

fn shared_files(file: &Path, data: &[u8]) -> Item {
    let single = text_files::shared_files(data).iter().filter(|shared| !shared.excluded).count();
    item(file, Outcome::Skipped, vec![phrase(Key::SingleFiles, vec![single.into()])])
}

// Only the name eMule writes: Cat#01 or Cat# 1 next to Cat#1 would give two categories one id.
fn category_id(section: &str) -> Option<u32> {
    let number = section.strip_prefix("Cat#")?;
    let id: i32 = number.parse().ok()?;
    (id.to_string() == number).then_some(id).and_then(|id| u32::try_from(id).ok())
}

// Without a backup: the folder holds only what the next start takes; for its owner only, as an identity.
fn write_pending(file: &Path, data: &[u8]) -> io::Result<()> {
    atomic_files::write_with(file, false, true, |out| out.write_all(data))
}

// Starting with the system is not a preference of ours but a setting of the system, which the desktop app makes.
fn starts_at_login(ini: &IniFile) -> bool {
    ini.get_boolean(SECTION, "AutoStart").unwrap_or(false)
}

// The phrases of the report, which the interfaces translate.
fn phrase(key: Key, args: Vec<Arg>) -> Phrase {
    Phrase::of(key, args)
}

fn item(file: &Path, outcome: Outcome, phrases: Vec<Phrase>) -> Item {
    Item::new(&name(file), outcome, phrases)
}

fn with_notes(file: &Path, outcome: Outcome, what: Phrase, notes: Vec<Phrase>) -> Item {
    let mut phrases = vec![what];
    phrases.extend(notes);
    item(file, outcome, phrases)
}

// Absolute only, as a relative one would name a folder below njMule's working directory; normalized, so that one folder
// written two ways is taken once.
fn path(text: &str) -> Option<PathBuf> {
    let text = strip(text);
    let path = Path::new(text);
    (!text.is_empty() && path.is_absolute()).then(|| normalized(path))
}

fn normalized(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    // The components hold no `.` past the start, which an absolute path has not.
    for component in path.components() {
        match component {
            Component::ParentDir => {
                if !matches!(out.components().next_back(), Some(Component::Normal(_))) || !out.pop() {
                    out.push(component);
                }
            }
            _ => out.push(component),
        }
    }
    out
}

// The nearest existing folder resolved, so that links and other spellings of one folder compare equal.
fn canonical(path: &Path) -> PathBuf {
    let absolute = std::path::absolute(path).map_or_else(|_| normalized(path), |p| normalized(&p));
    absolute
        .ancestors()
        .find(|ancestor| ancestor.exists())
        .and_then(|existing| Some(fs::canonicalize(existing).ok()?.join(absolute.strip_prefix(existing).ok()?)))
        .unwrap_or(absolute)
}

// Cut to at most `max` UTF-16 units, as the Java engine counts them, never inside a character.
fn shortened(text: &str, max: usize) -> &str {
    let mut units = 0_usize;
    let end = text
        .char_indices()
        .find(|(_, c)| {
            units = units.saturating_add(c.len_utf16());
            units > max
        })
        .map_or(text.len(), |(at, _)| at);
    text.get(..end).unwrap_or(text)
}

fn fits(text: &str, max: usize) -> bool {
    text.encode_utf16().count() <= max
}

fn name(file: &Path) -> String {
    file.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default()
}

fn sha256(data: &[u8]) -> String {
    HEXLOWER.encode(&Sha256::digest(data))
}
