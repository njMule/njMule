// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The commands of `njmule --remote`: they act on a running njMule through its remote-control API and print what it
//! answers.

use std::fmt;
use std::io::Write;
use std::time::{Duration, Instant};

use percent_encoding::{AsciiSet, CONTROLS, utf8_percent_encode};
use serde_json::{Map, Value, json};

use crate::api::install_kind::InstallKind;
use crate::api::nj_mule_info;
use crate::app::network_commands;
use crate::app::update_report::UpdateReport;
use crate::remote::api_client::{ApiClient, ApiError};

pub const COMMANDS: &str = "\
status                          connection, speeds and counts
downloads                       the downloads
add <ed2k link>...              download the files of these links
pause|resume|stop|cancel <hash>...
priority <hash> <low|normal|high|auto>
uploads                         clients being sent to or waiting
servers                         the known servers
connect [<host:port>]           connect to this server, or any
disconnect                      leave the server
kad [connect|disconnect|recheck]
search [server|global|kad] <expression>
                                search, wait a moment and print the results
results <id>                    the results of a search
get <id> <hash>                 download a result of a search
limits [<upload> <download>]    in KiB/s, or with K, M or G (1.5M); 0: unlimited
log                             the latest log lines
events [<prefix>,...]           print events as they come, until interrupted
update [check|skip <version>]   what is known of new versions; look now; stop telling of one
update download                 download the new version and check it
update install [at-exit]        install it now, njMule restarting, or when it exits
shutdown                        save and exit
";

const POLL: Duration = Duration::from_millis(500);
const UPDATE_WAIT: Duration = Duration::from_secs(4 * 60);
const DOWNLOAD_WAIT: Duration = Duration::from_secs(2 * 60 * 60);
const SETTLED_POLLS: usize = 2;
const DOWNLOADS: &str = "/downloads/";

// RFC 3986 path segment escaping.
const PATH_SEGMENT: &AsciiSet = &CONTROLS
    .add(b' ')
    .add(b'"')
    .add(b'#')
    .add(b'%')
    .add(b'/')
    .add(b'<')
    .add(b'>')
    .add(b'?')
    .add(b'`')
    .add(b'{')
    .add(b'}');

#[derive(Debug)]
pub enum RemoteError {
    Usage(String),
    Api(ApiError),
}

impl fmt::Display for RemoteError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Usage(message) => f.write_str(message),
            Self::Api(error) => write!(f, "{error}"),
        }
    }
}

impl std::error::Error for RemoteError {}

impl From<ApiError> for RemoteError {
    fn from(value: ApiError) -> Self {
        Self::Api(value)
    }
}

pub struct RemoteControl<'a, W: Write> {
    api: &'a ApiClient,
    out: &'a mut W,
    search_wait: Duration,
}

impl<'a, W: Write> RemoteControl<'a, W> {
    pub fn new(api: &'a ApiClient, out: &'a mut W, search_wait: Duration) -> Self {
        Self { api, out, search_wait }
    }

    pub async fn run(&mut self, words: &[String]) -> Result<(), RemoteError> {
        let Some((first, args)) = words.split_first() else {
            return Err(RemoteError::Usage("Name a command.".to_owned()));
        };
        let command = first.to_ascii_lowercase();
        match command.as_str() {
            "status" => {
                none(args)?;
                self.status().await
            }
            "downloads" => {
                none(args)?;
                self.downloads().await
            }
            "add" => self.add(args).await,
            "pause" | "resume" | "stop" => self.each_post(args, &command).await,
            "cancel" => self.each_delete(args).await,
            "priority" => {
                let (hash, priority) = exactly_two(args)?;
                let _ = self
                    .api
                    .put(
                        &format!("{DOWNLOADS}{}/priority", encode(hash)),
                        json!({ "priority": priority.to_ascii_lowercase() }),
                    )
                    .await?;
                let _ = writeln!(self.out, "Done.");
                Ok(())
            }
            "uploads" => {
                none(args)?;
                self.uploads().await
            }
            "servers" => {
                none(args)?;
                self.servers().await
            }
            "connect" => {
                let mut body = Map::new();
                if args.len() > 1 {
                    return Err(RemoteError::Usage("connect takes one server at most.".to_owned()));
                }
                if let Some(address) = args.first() {
                    body.insert("address".to_owned(), Value::String(address.clone()));
                }
                let _ = self.api.post("/servers/connect", Value::Object(body)).await?;
                let _ = writeln!(self.out, "Connecting.");
                Ok(())
            }
            "disconnect" => {
                none(args)?;
                let _ = self.api.post("/servers/disconnect", json!({})).await?;
                let _ = writeln!(self.out, "Disconnected.");
                Ok(())
            }
            "kad" => self.kad(args).await,
            "search" => self.search(args).await,
            "results" => {
                let id = exactly_one(args)?;
                let search = self.api.get(&format!("/searches/{}", encode(id))).await?;
                self.results(&search);
                Ok(())
            }
            "get" => {
                let (id, hash) = exactly_two(args)?;
                let download =
                    self.api.post(&format!("/searches/{}/download", encode(id)), json!({ "hash": hash })).await?;
                let _ = writeln!(self.out, "Downloading {}", text(&download, "name"));
                Ok(())
            }
            "limits" => self.limits(args).await,
            "log" => {
                none(args)?;
                let lines = self.api.get("/log").await?;
                for line in list(&lines) {
                    let _ = writeln!(
                        self.out,
                        "{}  {}  {}",
                        text(line, "time"),
                        text(line, "level"),
                        text(line, "message")
                    );
                }
                Ok(())
            }
            "events" => {
                if args.len() > 1 {
                    return Err(RemoteError::Usage(
                        "events takes one list of prefixes, as download,search.".to_owned(),
                    ));
                }
                let types = args.first().map(String::as_str).unwrap_or("");
                self.api
                    .events(types, |name, data| {
                        if writeln!(self.out, "{name} {data}").is_err() || self.out.flush().is_err() {
                            return Ok(false);
                        }
                        Ok(true)
                    })
                    .await?;
                Ok(())
            }
            "update" => self.update(args).await,
            "shutdown" => {
                none(args)?;
                let _ = self.api.post("/shutdown", json!({})).await?;
                let _ = writeln!(self.out, "njMule is saving and exiting.");
                Ok(())
            }
            _ => Err(RemoteError::Usage(format!("Unknown command: {first}"))),
        }
    }

    async fn update(&mut self, args: &[String]) -> Result<(), RemoteError> {
        let mut status = if args.is_empty() {
            self.api.get("/update").await?
        } else if args.len() == 1 && args.first().is_some_and(|a| a.eq_ignore_ascii_case("check")) {
            let mut now = self.api.post("/update/check", json!({})).await?;
            // A check that outlasts the call goes on: its outcome comes a little later.
            let start = Instant::now();
            while flag(&now, "checking") && start.elapsed() < UPDATE_WAIT {
                tokio::time::sleep(POLL).await;
                now = self.api.get("/update").await?;
            }
            now
        } else if let [verb, version] = args
            && verb.eq_ignore_ascii_case("skip")
        {
            let _ = self.api.post("/update/skip", json!({ "version": version })).await?;
            let _ = writeln!(self.out, "No more word of {version}, unless it fixes a security problem.");
            return Ok(());
        } else if args.len() == 1 && args.first().is_some_and(|a| a.eq_ignore_ascii_case("download")) {
            let mut now = self.api.post("/update/download", json!({})).await?;
            let mut told: i64 = -1;
            let start = Instant::now();
            while let Some(progress) = now.get("download").filter(|v| !v.is_null())
                && start.elapsed() < DOWNLOAD_WAIT
            {
                let size = u64_field(progress, "size");
                let received = u64_field(progress, "received");
                let tenth = received
                    .saturating_mul(10)
                    .checked_div(size)
                    .map_or(10, |value| i64::try_from(value).unwrap_or(10));
                if tenth > told {
                    told = tenth;
                    let _ = writeln!(self.out, "Downloading {}%", tenth.saturating_mul(10));
                }
                tokio::time::sleep(POLL).await;
                now = self.api.get("/update").await?;
            }
            if flag(&now, "ready") {
                let version = now.get("available").map(|v| text(v, "version")).unwrap_or_default();
                let suffix =
                    if flag(&now, "canInstall") { ". Install it with: njmule --remote update install" } else { "." };
                let _ = writeln!(self.out, "Downloaded and checked: {version}{suffix}");
            } else {
                let failure = now.get("failure").and_then(Value::as_str).unwrap_or("it goes on");
                let _ = writeln!(self.out, "The download did not end: {failure}");
            }
            return Ok(());
        } else if args.first().is_some_and(|a| a.eq_ignore_ascii_case("install")) && args.len() <= 2 {
            let at_exit = args.len() == 2;
            if at_exit && !args.get(1).is_some_and(|a| a.eq_ignore_ascii_case("at-exit")) {
                return Err(RemoteError::Usage("update install takes nothing, or at-exit.".to_owned()));
            }
            let now = self.api.post("/update/install", json!({ "restart": !at_exit })).await?;
            let version = now.get("available").map(|v| text(v, "version")).unwrap_or_default();
            if at_exit {
                let _ = writeln!(self.out, "{} {version} is installed when njMule exits.", nj_mule_info::NAME);
            } else {
                let _ =
                    writeln!(self.out, "{} is saving, installing {version} and starting again.", nj_mule_info::NAME);
            }
            return Ok(());
        } else {
            return Err(RemoteError::Usage(
                "update takes check, download, install [at-exit], or skip and a version.".to_owned(),
            ));
        };
        let available = status.get("available").filter(|v| !v.is_null());
        let download = available.and_then(|v| v.get("download")).filter(|v| !v.is_null());
        let report = UpdateReport {
            current: text(&status, "current"),
            installation: parse_installation(&text(&status, "installation")),
            checked: status.get("lastCheck").is_some_and(|v| !v.is_null()),
            failure: status.get("failure").and_then(Value::as_str).map(str::to_owned),
            available: available.map(|v| text(v, "version")),
            page: available.and_then(|v| v.get("page").and_then(Value::as_str)).map(str::to_owned),
            download: download.and_then(|d| d.get("url").and_then(Value::as_str)).map(str::to_owned),
        };
        let mut out_sink = Vec::new();
        let mut err_sink = Vec::new();
        report.print(&mut out_sink, &mut err_sink);
        let _ = self.out.write_all(&out_sink);
        let _ = self.out.write_all(&err_sink);
        let _ = status.take();
        Ok(())
    }

    async fn status(&mut self) -> Result<(), RemoteError> {
        let s = self.api.get("/status").await?;
        let ed2k = match s.get("server").filter(|v| !v.is_null()) {
            None => "not connected".to_owned(),
            Some(server) => format!(
                "{} ({}, {})",
                text(server, "name"),
                text(server, "address"),
                if flag(&s, "lowId") { "LowID" } else { "HighID" }
            ),
        };
        let _ = writeln!(self.out, "eD2K:      {ed2k}");
        let kad = match s.get("kad").filter(|v| !v.is_null()) {
            None => "off",
            Some(k) if !flag(k, "connected") => "not connected",
            Some(k) if flag(k, "firewalled") => "firewalled",
            Some(_) => "open",
        };
        let _ = writeln!(self.out, "Kad:       {kad}");
        let _ = writeln!(
            self.out,
            "Download:  {}/s, {} this session",
            network_commands::size(u64_field(&s, "downloadBytesPerSecond")),
            network_commands::size(u64_field(&s, "downloaded"))
        );
        let _ = writeln!(
            self.out,
            "Upload:    {}/s, {} this session",
            network_commands::size(u64_field(&s, "uploadBytesPerSecond")),
            network_commands::size(u64_field(&s, "uploaded"))
        );
        let _ = writeln!(
            self.out,
            "Downloads: {}, uploads {}, queued {}, shared files {}",
            u64_field(&s, "downloads"),
            u64_field(&s, "uploads"),
            u64_field(&s, "queued"),
            u64_field(&s, "sharedFiles")
        );
        Ok(())
    }

    async fn downloads(&mut self) -> Result<(), RemoteError> {
        let all = self.api.get("/downloads").await?;
        let items = list(&all);
        if items.is_empty() {
            let _ = writeln!(self.out, "No downloads.");
            return Ok(());
        }
        for d in items {
            let size = u64_field(d, "size");
            let completed = u64_field(d, "completed");
            let percent = completed.saturating_mul(100).checked_div(size).unwrap_or(0);
            let _ = writeln!(
                self.out,
                "{}  {:3}%  {:>10}  {:>10}/s  {:3}/{:<3}  {:<11}  {}",
                text(d, "hash"),
                percent,
                network_commands::size(size),
                network_commands::size(u64_field(d, "bytesPerSecond")),
                u64_field(d, "transferring"),
                u64_field(d, "sources"),
                text(d, "status"),
                text(d, "name"),
            );
        }
        Ok(())
    }

    async fn add(&mut self, links: &[String]) -> Result<(), RemoteError> {
        if links.is_empty() {
            return Err(RemoteError::Usage("add takes one link or more.".to_owned()));
        }
        let added = self.api.post("/downloads", json!({ "links": links })).await?;
        for d in list(&added) {
            let _ = writeln!(self.out, "Added {}", text(d, "name"));
        }
        Ok(())
    }

    async fn each_post(&mut self, hashes: &[String], action: &str) -> Result<(), RemoteError> {
        if hashes.is_empty() {
            return Err(RemoteError::Usage("Name the hash of one download or more.".to_owned()));
        }
        for hash in hashes {
            let d = self.api.post(&format!("{DOWNLOADS}{}/{action}", encode(hash)), json!({})).await?;
            print_download_action(self.out, &d);
        }
        Ok(())
    }

    async fn each_delete(&mut self, hashes: &[String]) -> Result<(), RemoteError> {
        if hashes.is_empty() {
            return Err(RemoteError::Usage("Name the hash of one download or more.".to_owned()));
        }
        for hash in hashes {
            let d = self.api.delete(&format!("{DOWNLOADS}{}", encode(hash))).await?;
            print_download_action(self.out, &d);
        }
        Ok(())
    }

    async fn uploads(&mut self) -> Result<(), RemoteError> {
        let all = self.api.get("/uploads").await?;
        let items = list(&all);
        if items.is_empty() {
            let _ = writeln!(self.out, "No uploads and nobody waiting.");
            return Ok(());
        }
        for u in items {
            let _ = writeln!(
                self.out,
                "{:<9}  {:<21}  {:>10}/s  {:>10}  {}",
                text(u, "state"),
                text(u, "address"),
                network_commands::size(u64_field(u, "bytesPerSecond")),
                network_commands::size(u64_field(u, "transferred")),
                text(u, "file"),
            );
        }
        Ok(())
    }

    async fn servers(&mut self) -> Result<(), RemoteError> {
        let all = self.api.get("/servers").await?;
        let items = list(&all);
        if items.is_empty() {
            let _ = writeln!(self.out, "No servers.");
            return Ok(());
        }
        for s in items {
            let _ = writeln!(
                self.out,
                "{:<21}  {:8} users  {:10} files  {}",
                text(s, "address"),
                u64_field(s, "users"),
                u64_field(s, "files"),
                text(s, "name"),
            );
        }
        Ok(())
    }

    async fn kad(&mut self, args: &[String]) -> Result<(), RemoteError> {
        if args.is_empty() {
            let kad = self.api.get("/kad").await?;
            if kad.is_null() {
                let _ = writeln!(self.out, "Kad is off.");
                return Ok(());
            }
            let connected = if flag(&kad, "connected") { "connected" } else { "not connected" };
            let firewalled = if flag(&kad, "firewalled") { ", firewalled" } else { "" };
            let _ = writeln!(self.out, "{connected}{firewalled}, {} contacts", u64_field(&kad, "contacts"));
            return Ok(());
        }
        let action = exactly_one(args)?.to_ascii_lowercase();
        if !matches!(action.as_str(), "connect" | "disconnect" | "recheck") {
            return Err(RemoteError::Usage("kad takes connect, disconnect or recheck.".to_owned()));
        }
        let _ = self.api.post(&format!("/kad/{action}"), json!({})).await?;
        let _ = writeln!(self.out, "Done.");
        Ok(())
    }

    async fn search(&mut self, args: &[String]) -> Result<(), RemoteError> {
        let (scope, words) = match args.split_first() {
            Some((first, rest)) if matches!(first.to_ascii_lowercase().as_str(), "server" | "global" | "kad") => {
                (first.to_ascii_lowercase(), rest)
            }
            _ => ("server".to_owned(), args),
        };
        if words.is_empty() {
            return Err(RemoteError::Usage("search takes what to look for.".to_owned()));
        }
        let started = self.api.post("/searches", json!({ "query": words.join(" "), "scope": scope })).await?;
        let id = u64_field(&started, "id");
        let _ = writeln!(self.out, "Search {id}: waiting for results...");
        let search = self.settled(&format!("/searches/{id}")).await?;
        self.results(&search);
        let _ = writeln!(self.out, "More: njmule --remote results {id}; download: njmule --remote get {id} <hash>");
        Ok(())
    }

    // The results once they stop growing for a moment, or at the end of the wait.
    async fn settled(&self, search: &str) -> Result<Value, ApiError> {
        let start = Instant::now();
        let mut now = self.api.get(search).await?;
        let mut seen: Option<usize> = None;
        let mut still: usize = 0;
        while start.elapsed() < self.search_wait && still < SETTLED_POLLS {
            tokio::time::sleep(POLL).await;
            now = self.api.get(search).await?;
            let count = now.get("results").map_or(0, |r| list(r).len());
            still = if count > 0 && seen == Some(count) { still.saturating_add(1) } else { 0 };
            seen = Some(count);
        }
        Ok(now)
    }

    fn results(&mut self, search: &Value) {
        let found = search.get("results").map(list).unwrap_or(&[]);
        let _ = writeln!(self.out, "{} results", found.len());
        for r in found {
            let _ = writeln!(
                self.out,
                "{}  {:7}  {:>10}  {}",
                text(r, "hash"),
                u64_field(r, "sources"),
                network_commands::size(u64_field(r, "size")),
                text(r, "name"),
            );
        }
    }

    async fn limits(&mut self, args: &[String]) -> Result<(), RemoteError> {
        let now = if args.is_empty() {
            self.api.get("/limits").await?
        } else {
            let (upload, download) = exactly_two(args)?;
            let up = rate(upload)?;
            let down = rate(download)?;
            self.api.put("/limits", json!({ "uploadKiBps": up, "downloadKiBps": down })).await?
        };
        let _ = writeln!(self.out, "Upload:   {}", limit(i64_field(&now, "uploadKiBps")));
        let _ = writeln!(self.out, "Download: {}", limit(i64_field(&now, "downloadKiBps")));
        Ok(())
    }
}

/// A limit as it reads best: `unlimited`, `50 KiB/s`, `1.50 MiB/s` or `1.10 GiB/s`.
pub fn limit(kibps: i64) -> String {
    if kibps == 0 {
        return "unlimited".to_owned();
    }
    if kibps < 1024 {
        return format!("{kibps} KiB/s");
    }
    #[expect(clippy::cast_precision_loss, reason = "display formatting of a rate")]
    let value = kibps as f64;
    if kibps < 1024 * 1024 {
        format!("{:.2} MiB/s", value / 1024.0)
    } else {
        format!("{:.2} GiB/s", value / (1024.0 * 1024.0))
    }
}

/// A rate in KiB/s, or with a unit: `500K`, `1.5M`, `1G` (binary units).
pub fn rate(text: &str) -> Result<i64, RemoteError> {
    let invalid = || RemoteError::Usage(format!("Not a rate in KiB/s, or with K, M or G: {text}"));
    let typed = text.trim();
    if typed.is_empty() {
        return Err(invalid());
    }
    let last = typed.chars().next_back().unwrap_or('\0').to_ascii_uppercase();
    let (unit, number) = match last {
        'K' => (Some(1u128), &typed[..typed.len().saturating_sub(1)]),
        'M' => (Some(1024u128), &typed[..typed.len().saturating_sub(1)]),
        'G' => (Some(1024u128 * 1024), &typed[..typed.len().saturating_sub(1)]),
        _ => (None, typed),
    };
    if number.is_empty() || number.starts_with('-') || number.starts_with('+') {
        return Err(invalid());
    }
    let (whole_str, frac_str) = match number.split_once('.') {
        Some((w, f)) => {
            if unit.is_none() || (w.is_empty() && f.is_empty()) || f.contains('.') {
                return Err(invalid());
            }
            (w, f)
        }
        None => (number, ""),
    };
    if !whole_str.bytes().all(|b| b.is_ascii_digit()) || !frac_str.bytes().all(|b| b.is_ascii_digit()) {
        return Err(invalid());
    }
    let scale = 10u128.checked_pow(u32::try_from(frac_str.len()).map_err(|_| invalid())?).ok_or_else(invalid)?;
    let whole: u128 = if whole_str.is_empty() { 0 } else { whole_str.parse().map_err(|_| invalid())? };
    let frac: u128 = if frac_str.is_empty() { 0 } else { frac_str.parse().map_err(|_| invalid())? };
    let combined = whole.checked_mul(scale).and_then(|v| v.checked_add(frac)).ok_or_else(invalid)?;
    let multiplier = unit.unwrap_or(1);
    let numerator = combined.checked_mul(multiplier).ok_or_else(invalid)?;
    // HALF_UP rounding: add half the denominator before dividing.
    let rounded = numerator.checked_add(scale / 2).and_then(|v| v.checked_div(scale)).ok_or_else(invalid)?;
    i64::try_from(rounded).map_err(|_| invalid())
}

fn parse_installation(name: &str) -> InstallKind {
    match name.to_ascii_lowercase().as_str() {
        "msi" => InstallKind::Msi,
        "windows-zip" | "windows_zip" => InstallKind::WindowsZip,
        "dmg" => InstallKind::Dmg,
        "deb" => InstallKind::Deb,
        "rpm" => InstallKind::Rpm,
        "tar" => InstallKind::Tar,
        "static-tar" | "static_tar" => InstallKind::StaticTar,
        "synology" => InstallKind::Synology,
        "docker" => InstallKind::Docker,
        _ => InstallKind::Unknown,
    }
}

fn print_download_action<W: Write>(out: &mut W, d: &Value) {
    if d.get("name").is_none_or(Value::is_null) {
        let _ = writeln!(out, "Done.");
    } else {
        let _ = writeln!(out, "{}: {}", text(d, "name"), text(d, "status"));
    }
}

fn encode(segment: &str) -> String {
    utf8_percent_encode(segment, PATH_SEGMENT).to_string()
}

fn none(args: &[String]) -> Result<(), RemoteError> {
    if args.is_empty() { Ok(()) } else { Err(RemoteError::Usage(format!("Unexpected: {}", args.join(" ")))) }
}

fn exactly_one(args: &[String]) -> Result<&String, RemoteError> {
    match args {
        [one] => Ok(one),
        _ => Err(RemoteError::Usage("Expected 1 argument.".to_owned())),
    }
}

fn exactly_two(args: &[String]) -> Result<(&String, &String), RemoteError> {
    match args {
        [a, b] => Ok((a, b)),
        _ => Err(RemoteError::Usage("Expected 2 arguments.".to_owned())),
    }
}

fn flag(value: &Value, name: &str) -> bool {
    value.get(name).and_then(Value::as_bool).unwrap_or(false)
}

fn text(value: &Value, name: &str) -> String {
    match value.get(name) {
        None | Some(Value::Null) => String::new(),
        Some(Value::String(s)) => s.clone(),
        Some(other) => other.to_string(),
    }
}

fn u64_field(value: &Value, name: &str) -> u64 {
    value.get(name).and_then(Value::as_u64).unwrap_or(0)
}

fn i64_field(value: &Value, name: &str) -> i64 {
    value.get(name).and_then(Value::as_i64).unwrap_or(0)
}

fn list(value: &Value) -> &[Value] {
    value.as_array().map(Vec::as_slice).unwrap_or(&[])
}
