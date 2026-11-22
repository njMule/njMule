// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The network work asked on the command line: add servers, connect, search, say hello, download.

use std::collections::HashMap;
use std::io::Write;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::time::{Duration, Instant};

use indexmap::IndexMap;
use tokio::sync::broadcast;

use crate::api::download_info::DownloadInfo;
use crate::api::ed2k_link::FileLink;
use crate::api::engine_event::EngineEvent;
use crate::api::kad_info::{Buddy, KadInfo};
use crate::api::list_source::ListSource;
use crate::api::nj_mule_engine::NjMuleEngine;
use crate::api::peer_info::Identification;
use crate::app::launch_options::{EmuleImport, NetworkTasks};
use crate::engine::network::ConnectError;
use crate::search::search_manager::{Scope, StartError};
use crate::server::server_list::Address;

pub const EXIT_OK: i32 = 0;
pub const EXIT_FAILURE: i32 = 1;

/// Longest wait for a login, several servers included.
pub const CONNECT_WAIT: Duration = Duration::from_secs(3 * 60);

/// Longest wait for a search to end.
pub const SEARCH_WAIT: Duration = Duration::from_secs(90);

/// A search that went quiet this long after its results is over.
pub const SEARCH_QUIET: Duration = Duration::from_secs(3);

/// How long a greeted peer keeps seeing us connected.
pub const HELLO_HOLD: Duration = Duration::from_secs(30);

/// Downloads print their progress this often.
pub const PROGRESS_EVERY: Duration = Duration::from_secs(5);

/// Longest wait for Kad to know enough nodes before a Kad search.
pub const KAD_WAIT: Duration = Duration::from_secs(3 * 60);

/// Contacts Kad should know before a Kad search.
pub const KAD_CONTACTS: usize = 20;

/// Adds servers and starts the connection; with a search, hello, download or import also runs them and waits.
pub async fn run<E, W1, W2>(engine: &E, tasks: &NetworkTasks, out: &mut W1, err: &mut W2) -> i32
where
    E: NjMuleEngine,
    W1: Write,
    W2: Write,
{
    let mut subscription = engine.subscribe();
    let mut runner = Runner { engine, events: &mut subscription.live, out, err };
    match runner.execute(tasks).await {
        Ok(code) => code,
        Err(message) => {
            let _ = writeln!(runner.err, "Failed: {message}");
            EXIT_FAILURE
        }
    }
}

struct Runner<'a, E: NjMuleEngine, W1: Write, W2: Write> {
    engine: &'a E,
    events: &'a mut broadcast::Receiver<EngineEvent>,
    out: &'a mut W1,
    err: &'a mut W2,
}

impl<E: NjMuleEngine, W1: Write, W2: Write> Runner<'_, E, W1, W2> {
    async fn execute(&mut self, tasks: &NetworkTasks) -> Result<i32, String> {
        if let Some(import) = &tasks.emule_import {
            self.import_emule(import).await?;
        }
        if let Some(server_met) = &tasks.server_met {
            let added =
                self.engine.add_servers(ListSource::File(server_met.clone())).await.map_err(|e| e.to_string())?;
            let _ = writeln!(self.out, "Added {added} servers from {}", server_met.display());
        }
        for dir in &tasks.shares {
            self.engine.share_directory(dir.clone(), false).await?;
            let _ = writeln!(self.out, "Sharing {}", dir.display());
        }
        if let Some(address) = &tasks.hello {
            self.hello(address, tasks).await?;
        }
        if let Some(bootstrap) = &tasks.kad_bootstrap {
            let node = resolve_v4(bootstrap).await?;
            self.engine.bootstrap_kad(node).await?;
            let _ = writeln!(self.out, "Joining Kad through {bootstrap}");
        }
        if tasks.kad {
            if !self.await_kad().await {
                return Ok(EXIT_FAILURE);
            }
            if let Some(query) = &tasks.search {
                self.search(query, Scope::Kad).await?;
            }
            return if tasks.downloads.is_empty() { Ok(EXIT_OK) } else { self.download(&tasks.downloads).await };
        }
        if tasks.server.is_none() && tasks.search.is_none() {
            return if tasks.downloads.is_empty() { Ok(EXIT_OK) } else { self.download(&tasks.downloads).await };
        }
        self.engine.connect_server(tasks.server.clone()).await.map_err(connect_error)?;
        if !tasks.is_one_shot() {
            return Ok(EXIT_OK);
        }
        if !self.await_login().await {
            return Ok(EXIT_FAILURE);
        }
        if let Some(query) = &tasks.search {
            let scope = if tasks.global { Scope::Global } else { Scope::Server };
            self.search(query, scope).await?;
        }
        if tasks.downloads.is_empty() { Ok(EXIT_OK) } else { self.download(&tasks.downloads).await }
    }

    async fn import_emule(&mut self, task: &EmuleImport) -> Result<(), String> {
        let heading = if task.options.dry_run { "What would be imported from " } else { "Importing from " };
        let _ = writeln!(self.out, "{heading}{}", task.dir.display());
        let report = self.engine.import_from_emule(task.dir.clone(), task.options).await?;
        for item in &report.items {
            let outcome = item.outcome.name().to_ascii_lowercase().replace('_', " ");
            let _ = writeln!(self.out, "  {outcome:<13} {:<20} {}", item.file, item.detail());
        }
        Ok(())
    }

    async fn hello(&mut self, address: &Address, tasks: &NetworkTasks) -> Result<(), String> {
        let target = resolve_socket(address).await?;
        let peer = tokio::time::timeout(Duration::from_secs(60), self.engine.hello(target))
            .await
            .map_err(|_| "TimeoutException".to_owned())?
            .map_err(|e| e.to_string())?;
        let nick = peer.nick.as_deref().map(|n| format!(", \"{n}\"")).unwrap_or_default();
        let obfuscated = if peer.obfuscated { ", obfuscated" } else { "" };
        let hash = data_encoding::HEXUPPER.encode(&peer.user_hash);
        let _ = writeln!(
            self.out,
            "Hello answered by {}: {}{nick}{obfuscated}, user hash {hash}, identity {}",
            peer.address,
            peer.software,
            identity(peer.identification)
        );
        if !tasks.is_one_shot() || tasks.search.is_none() {
            let _ = writeln!(
                self.out,
                "Keeping the connection open for {} s, so the peer lists us among its clients",
                HELLO_HOLD.as_secs()
            );
            tokio::time::sleep(HELLO_HOLD).await;
        }
        Ok(())
    }

    async fn await_login(&mut self) -> bool {
        let start = Instant::now();
        while start.elapsed() < CONNECT_WAIT {
            let remaining = CONNECT_WAIT.saturating_sub(start.elapsed()).min(Duration::from_secs(1));
            match tokio::time::timeout(remaining, self.events.recv()).await {
                Ok(Ok(EngineEvent::ServerConnecting(server))) => {
                    let _ = writeln!(self.out, "Connecting to {}", describe(&server.name, &server.address));
                }
                Ok(Ok(EngineEvent::ServerMessage { text, .. })) => {
                    let _ = writeln!(self.out, "  > {text}");
                }
                Ok(Ok(EngineEvent::ServerConnected { server, client_id, low_id, obfuscated })) => {
                    let id_kind = if low_id { "LowID " } else { "HighID " };
                    let obf = if obfuscated { ", obfuscated" } else { "" };
                    let _ = writeln!(
                        self.out,
                        "Connected to {} with {id_kind}{client_id}{obf}",
                        describe(&server.name, &server.address)
                    );
                    return true;
                }
                Ok(Ok(EngineEvent::ServerDisconnected { reason, .. })) => {
                    let _ = writeln!(self.err, "Not connected: {reason}");
                    return false;
                }
                Ok(Ok(_) | Err(broadcast::error::RecvError::Lagged(_))) | Err(_) => {}
                Ok(Err(broadcast::error::RecvError::Closed)) => break,
            }
        }
        let _ = writeln!(self.err, "Not connected within {} minutes", CONNECT_WAIT.as_secs() / 60);
        false
    }

    // Waits until Kad knows enough nodes, printing how it grows.
    async fn await_kad(&mut self) -> bool {
        let start = Instant::now();
        let mut next_report: Option<Instant> = None;
        while start.elapsed() < KAD_WAIT {
            let Some(kad) = self.engine.kad().await else {
                let _ = writeln!(self.err, "Kad is not running");
                return false;
            };
            if kad.connected && kad.verified_contacts >= KAD_CONTACTS {
                let _ = writeln!(self.out, "{}", kad_line(&kad));
                return true;
            }
            let now = Instant::now();
            if next_report.is_none_or(|due| now >= due) {
                next_report = now.checked_add(PROGRESS_EVERY);
                let _ = writeln!(self.out, "{}", kad_line(&kad));
            }
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
        let _ = writeln!(self.err, "Kad knows too few nodes after {} minutes", KAD_WAIT.as_secs() / 60);
        false
    }

    async fn search(&mut self, expression: &str, scope: Scope) -> Result<(), String> {
        let global = scope != Scope::Server;
        let id = self.engine.start_search(expression.to_owned(), Some(scope)).await.map_err(start_error)?;
        let where_text = match scope {
            Scope::Server => " on the server",
            Scope::Global => " on every known server",
            Scope::Kad => " on Kad",
        };
        let _ = writeln!(self.out, "Searching \"{expression}\"{where_text}");
        let mut results = IndexMap::new();
        let start = Instant::now();
        let mut quiet_since: Option<Instant> = None;
        while start.elapsed() < SEARCH_WAIT {
            match tokio::time::timeout(Duration::from_millis(200), self.events.recv()).await {
                Ok(Ok(EngineEvent::SearchResults { search, results: batch })) if search == id => {
                    for result in batch {
                        results.insert(result.hash, result);
                    }
                    quiet_since = Some(Instant::now());
                }
                Ok(Ok(EngineEvent::SearchEnded { search, .. })) if search == id => break,
                Ok(Ok(EngineEvent::ServerMessage { text, .. })) => {
                    let _ = writeln!(self.out, "  > {text}");
                }
                Ok(Ok(EngineEvent::ServerDisconnected { reason, .. })) => {
                    let _ = writeln!(self.err, "Disconnected: {reason}");
                    break;
                }
                Ok(Err(broadcast::error::RecvError::Closed)) => break,
                _ => {
                    if !global && quiet_since.is_some_and(|since| since.elapsed() > SEARCH_QUIET) {
                        break;
                    }
                }
            }
        }
        self.engine.stop_search(id).await;
        let mut sorted: Vec<_> = results.into_values().collect();
        sorted.sort_by_key(|a| std::cmp::Reverse(a.sources));
        for result in &sorted {
            let _ = writeln!(self.out, "{:7}  {:>10}  {}", result.sources, size(result.size), result.name);
            if let Ok(link) = result.link() {
                let _ = writeln!(self.out, "                     {}", link.to_uri());
            }
        }
        let _ = writeln!(self.out, "{} results", sorted.len());
        Ok(())
    }

    // Runs until every download is finished or has failed.
    async fn download(&mut self, links: &[FileLink]) -> Result<i32, String> {
        let mut running: IndexMap<[u8; 16], DownloadInfo> = IndexMap::new();
        for link in links {
            let info = self.engine.add_download(link.clone(), None).await?;
            let noun = if info.sources == 1 { " source)" } else { " sources)" };
            let _ = writeln!(self.out, "Downloading {} ({}, {}{noun}", info.name, size(info.size), info.sources);
            running.insert(info.hash, info);
        }
        let mut latest: HashMap<[u8; 16], DownloadInfo> =
            running.iter().map(|(&hash, info)| (hash, info.clone())).collect();
        let mut failed = false;
        let mut next_report = Instant::now().checked_add(PROGRESS_EVERY);
        while !running.is_empty() {
            match tokio::time::timeout(Duration::from_millis(500), self.events.recv()).await {
                Ok(Ok(EngineEvent::DownloadProgress(info))) if running.contains_key(&info.hash) => {
                    latest.insert(info.hash, info);
                }
                Ok(Ok(EngineEvent::DownloadCompleted { download, file })) if running.contains_key(&download.hash) => {
                    running.shift_remove(&download.hash);
                    let _ = writeln!(self.out, "Finished {}: {}", download.name, file.display());
                }
                Ok(Ok(EngineEvent::DownloadFailed { download, reason })) if running.contains_key(&download.hash) => {
                    running.shift_remove(&download.hash);
                    failed = true;
                    let _ = writeln!(self.err, "Failed {}: {reason}", download.name);
                }
                Ok(Ok(EngineEvent::ServerConnected { server, .. })) => {
                    let _ = writeln!(self.out, "Connected to {}", server.address);
                }
                Ok(Ok(EngineEvent::ServerDisconnected { reason, .. })) => {
                    let _ = writeln!(self.err, "Disconnected from the server: {reason}");
                }
                Ok(Err(broadcast::error::RecvError::Closed)) => break,
                _ => {}
            }
            let now = Instant::now();
            if next_report.is_some_and(|due| now >= due) {
                next_report = now.checked_add(PROGRESS_EVERY);
                for hash in running.keys() {
                    if let Some(info) = latest.get(hash) {
                        let _ = writeln!(self.out, "{}", progress(info));
                    }
                }
            }
        }
        Ok(if failed { EXIT_FAILURE } else { EXIT_OK })
    }
}

/// One-line summary of the Kad state.
pub fn kad_line(kad: &KadInfo) -> String {
    let connected = if kad.connected { ", connected" } else { "" };
    let reach = if kad.firewalled { ", firewalled" } else { ", reachable" };
    let udp = if kad.udp_verified || kad.udp_firewalled {
        if kad.udp_firewalled { ", UDP firewalled" } else { ", UDP open" }
    } else {
        ""
    };
    let buddy = match kad.buddy {
        Buddy::None => "",
        Buddy::Connecting => ", connecting to a buddy",
        Buddy::Connected => ", with a buddy",
        Buddy::Serving => ", buddy of a firewalled client",
    };
    format!("Kad: {} contacts, {} verified{connected}{reach}{udp}{buddy}", kad.contacts, kad.verified_contacts)
}

/// One-line progress of a download.
pub fn progress(info: &DownloadInfo) -> String {
    #[expect(clippy::cast_precision_loss, reason = "display formatting of a download percentage")]
    let percent = if info.size == 0 { 100.0 } else { 100.0 * (info.completed as f64) / (info.size as f64) };
    format!(
        "{percent:6.1}%  {:>12}/s  {}/{} sources  {}",
        size(info.bytes_per_second),
        info.transferring,
        info.sources,
        info.name
    )
}

/// Binary-unit byte size (`0 B`, `1.5 KiB`, `3.0 TiB`).
pub fn size(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KiB", "MiB", "GiB", "TiB"];
    #[expect(clippy::cast_precision_loss, reason = "display formatting of a byte size")]
    let mut value = bytes as f64;
    let mut unit: usize = 0;
    while value >= 1024.0 && unit < UNITS.len().saturating_sub(1) {
        value /= 1024.0;
        unit = unit.saturating_add(1);
    }
    if unit == 0 {
        format!("{bytes} B")
    } else {
        let label = UNITS.get(unit).copied().unwrap_or("TiB");
        format!("{value:.1} {label}")
    }
}

fn connect_error(error: ConnectError) -> String {
    match error {
        ConnectError::Refused(reason) => reason,
        ConnectError::NoServer => "No server to connect to".to_owned(),
    }
}

fn start_error(error: StartError) -> String {
    match error {
        StartError::Syntax(e) => e.to_string(),
        StartError::NotForKad(reason) | StartError::Unavailable(reason) => reason,
    }
}

fn describe(name: &str, address: &str) -> String {
    if name == address { address.to_owned() } else { format!("{name} ({address})") }
}

fn identity(outcome: Identification) -> &'static str {
    match outcome {
        Identification::Verified => "verified",
        Identification::Failed => "NOT verified",
        Identification::NotSupported => "not offered",
        Identification::Pending => "not proven in time",
    }
}

async fn resolve_socket(address: &Address) -> Result<SocketAddr, String> {
    if address.ip != 0 {
        return Ok(SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::from(address.ip), address.port)));
    }
    tokio::net::lookup_host(address.to_string())
        .await
        .map_err(|e| format!("Cannot resolve {address}: {e}"))?
        .next()
        .ok_or_else(|| format!("Cannot resolve {address}"))
}

async fn resolve_v4(address: &Address) -> Result<SocketAddrV4, String> {
    if address.ip != 0 {
        return Ok(SocketAddrV4::new(Ipv4Addr::from(address.ip), address.port));
    }
    tokio::net::lookup_host(address.to_string())
        .await
        .map_err(|e| format!("Cannot resolve {address}: {e}"))?
        .find_map(|addr| match addr {
            SocketAddr::V4(v4) => Some(v4),
            SocketAddr::V6(_) => None,
        })
        .ok_or_else(|| format!("Cannot resolve IPv4 address for {address}"))
}
