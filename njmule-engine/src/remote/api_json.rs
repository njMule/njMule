// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The JSON of the remote-control API, field for field as `openapi.yaml` describes it.

use serde_json::{Map, Value, json};
use time::format_description::well_known::Rfc3339;

use crate::api::category::Category;
use crate::api::download_info::{DownloadInfo, SourceInfo};
use crate::api::ed2k_link::FileLink;
use crate::api::emule_history::EmuleHistory;
use crate::api::engine_event::EngineEvent;
use crate::api::engine_status::EngineStatus;
use crate::api::file_comment::FileComment;
use crate::api::import_report::{Arg, ImportReport};
use crate::api::kad_contact_info::KadContactInfo;
use crate::api::kad_info::{Buddy, KadInfo};
use crate::api::kad_note::KadNote;
use crate::api::kad_search_info::{KadSearchInfo, Kind};
use crate::api::known_client::KnownClient;
use crate::api::log_line::LogLine;
use crate::api::nj_mule_info;
use crate::api::peer_info::PeerInfo;
use crate::api::port_test::PortTest;
use crate::api::saved_search::SavedSearch;
use crate::api::search_result::{Media, SearchResult};
use crate::api::server_info::ServerInfo;
use crate::api::shared_directory::SharedDirectory;
use crate::api::shared_file_info::SharedFileInfo;
use crate::api::spam_marks::SpamMarks;
use crate::api::upload_info::{BanInfo, UploadInfo};
use crate::config::bind_interface::Candidate;
use crate::config::preferences::Preferences;
use crate::config::schedule::{Rule, Schedule, time_text};
use crate::files::file_types;
use crate::files::part_file::PartMap;
use crate::persist::preferences_codec;
use crate::remote::rate_history::Rate;
use crate::remote::recent_log::Entry;

// The passwords the preferences hold, which never leave.
const SECRETS: [&str; 2] = ["notify.smtpPassword", "proxy.password"];

pub fn preferences(preferences: &Preferences) -> Value {
    Value::Object(
        preferences_codec::values(preferences)
            .into_iter()
            .filter(|(key, _)| !SECRETS.contains(&key.as_str()))
            .map(|(key, value)| (key, json!(value)))
            .collect(),
    )
}

/// Whether each password is set, so that a form shows one is without ever holding it.
pub fn preference_passwords(preferences: &Preferences) -> Value {
    let values = preferences_codec::values(preferences);
    Value::Object(
        SECRETS
            .iter()
            .map(|&key| (key.to_owned(), json!(values.get(key).is_some_and(|value| !value.is_empty()))))
            .collect(),
    )
}

/// No update checks yet: nothing known, nothing to install.
pub fn update() -> Value {
    json!({
        "state": "idle",
        "current": nj_mule_info::version(),
        "installation": "unknown",
        "installer": "none",
        "canInstall": false,
        "checking": false,
        "lastCheck": null,
        "failure": null,
        "available": null,
        "skipped": null,
        "download": null,
        "ready": false,
        "readyFile": null,
        "install": "asked",
    })
}

/// A network interface the connections may be bound to.
pub fn network_interface(candidate: &Candidate) -> Value {
    json!({
        "name": candidate.name,
        "displayName": candidate.display_name,
        "addresses": candidate.addresses.iter().map(ToString::to_string).collect::<Vec<_>>(),
    })
}

/// A client we upload to or that waits for its slot.
pub fn upload(info: &UploadInfo) -> Value {
    json!({
        "address": info.address,
        "userHash": hash(&info.user_hash),
        "nick": info.nick,
        "software": info.software,
        "file": info.file,
        "state": info.state.name(),
        "transferred": info.transferred,
        "bytesPerSecond": info.bytes_per_second,
        "waitingSeconds": info.waiting_seconds,
        "country": info.country,
    })
}

pub fn shared_directory(directory: &SharedDirectory) -> Value {
    json!({"path": directory.path.to_string_lossy(), "subdirectories": directory.subdirectories})
}

pub fn ban(info: &BanInfo) -> Value {
    json!({"address": info.address, "secondsLeft": info.seconds_left})
}

pub fn port_test(test: &PortTest) -> Value {
    json!({
        "tcpPort": test.tcp_port,
        "udpPort": test.udp_port,
        "forwarding": test.forwarding.name(),
        "router": test.router,
        "tcp": test.tcp.name(),
        "udp": test.udp.name(),
        "done": test.done,
    })
}

/// The rules of the schedule and the one in force, if any.
pub fn schedule(schedule: &Schedule, active: Option<&Rule>) -> Value {
    json!({
        "rules": schedule.rules.iter().map(rule).collect::<Vec<_>>(),
        "active": active.map(|rule| rule.name.as_str()),
    })
}

fn rule(rule: &Rule) -> Value {
    let mut out = json!({
        "name": rule.name,
        "enabled": rule.enabled,
        "days": rule.days.iter().map(|day| day.name().to_ascii_lowercase()).collect::<Vec<_>>(),
        "start": time_text(rule.start),
        "end": time_text(rule.end),
    });
    let limits = [
        ("uploadKiBps", rule.upload_kibps),
        ("downloadKiBps", rule.download_kibps),
        ("maxConnections", rule.max_connections),
        ("maxSourcesPerFile", rule.max_sources_per_file),
        ("stopCategory", rule.stop_category),
        ("resumeCategory", rule.resume_category),
    ];
    if let Some(fields) = out.as_object_mut() {
        for (name, value) in limits {
            if let Some(value) = value {
                fields.insert(name.to_owned(), json!(value));
            }
        }
    }
    out
}

pub fn peer_info(info: &PeerInfo) -> Value {
    json!({
        "address": info.address,
        "userHash": hash(&info.user_hash),
        "nick": info.nick,
        "software": info.software,
        "obfuscated": info.obfuscated,
        "identification": info.identification.name(),
    })
}

pub fn known_client(client: &KnownClient) -> Value {
    json!({
        "userHash": hash(&client.user_hash),
        "nick": client.nick,
        "software": client.software,
        "firstSeen": client.first_seen,
        "lastSeen": client.last_seen,
        "country": client.country,
        "uploaded": client.uploaded,
        "downloaded": client.downloaded,
    })
}

/// A file a client shares, as its list gave it; none when no link can be made of it.
pub fn shared_by_client(file: &SearchResult) -> Option<Value> {
    let link = file.link().ok()?.to_uri();
    Some(json!({
        "hash": hash(&file.hash),
        "name": file.name,
        "size": file.size,
        "type": file.file_type,
        "link": link,
    }))
}

/// Where a front end reads whether the router is asked to forward our ports, and to let firewalled clients through.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Reach {
    pub upnp: bool,
    pub router_traversal: bool,
}

/// The status, with the free space of the incoming and temporary folders when it could be read.
pub fn status(status: &EngineStatus, incoming_free: Option<u64>, temp_free: Option<u64>, reach: Reach) -> Value {
    let mut out = json!({
        "upnp": reach.upnp,
        "routerTraversal": reach.router_traversal,
        "relatedSearch": status.related_search,
        "server": status.server.as_ref().map_or(Value::Null, server),
        "clientId": status.client_id,
        "lowId": status.low_id,
        "obfuscated": status.obfuscated,
        "kad": status.kad.as_ref().map_or(Value::Null, kad),
        "downloadBytesPerSecond": status.download_bytes_per_second,
        "uploadBytesPerSecond": status.upload_bytes_per_second,
        "downloaded": status.downloaded,
        "uploaded": status.uploaded,
        "downloads": status.downloads,
        "uploads": status.uploads,
        "queued": status.queued,
        "sharedFiles": status.shared_files,
        "totalDownloaded": status.total_downloaded,
        "totalUploaded": status.total_uploaded,
        "sessions": status.sessions,
        "sessionSeconds": status.session_seconds,
        "totalSeconds": status.total_seconds,
        "publicIp": status.public_ip,
        "tcpPort": status.tcp_port,
        "overheadUploaded": status.overhead_uploaded,
        "overheadDownloaded": status.overhead_downloaded,
        "forwarding": status.forwarding,
        "router": status.router,
    });
    if let Some(fields) = out.as_object_mut() {
        if incoming_free.is_some() || temp_free.is_some() {
            let mut free = Map::new();
            if let Some(incoming) = incoming_free {
                free.insert("incoming".to_owned(), json!(incoming));
            }
            if let Some(temp) = temp_free {
                free.insert("temp".to_owned(), json!(temp));
            }
            fields.insert("freeSpace".to_owned(), Value::Object(free));
        }
        if let Some(ipv6) = &status.public_ipv6 {
            fields.insert("publicIpv6".to_owned(), json!(ipv6));
        }
        // Present while the network interface the preferences bind to is gone, which keeps njMule offline.
        if let Some(missing) = &status.missing_interface {
            fields.insert("missingInterface".to_owned(), json!(missing));
        }
        // Present while another program holds Kad's UDP port, which Kad tries again.
        if let Some(port) = status.kad_port_taken {
            fields.insert("kadPortTaken".to_owned(), json!(port));
        }
    }
    out
}

pub fn category(category: &Category) -> Value {
    let mut out = json!({
        "id": category.id,
        "name": category.name,
        "comment": category.comment,
        "priority": category.priority.name(),
        "color": category.color,
        "patterns": category.patterns,
        "regex": category.regex,
    });
    if let (Some(incoming), Some(fields)) = (&category.incoming, out.as_object_mut()) {
        fields.insert("incoming".to_owned(), json!(incoming.to_string_lossy()));
    }
    out
}

/// A search left open, with its results.
pub fn saved_search(search: &SavedSearch) -> Value {
    json!({
        "title": search.title,
        "query": search.request.expression,
        "scope": search.request.scope.name(),
        "results": search.results.iter().map(search_result).collect::<Vec<_>>(),
    })
}

/// A line of the user's log: when, how serious and what.
pub fn log_line(line: &LogLine) -> Value {
    let time = time::OffsetDateTime::from(line.time).format(&Rfc3339).unwrap_or_default();
    json!({"time": time, "level": line.level.name(), "message": line.message})
}

/// A line of the recent log with its number, which the next read starts after.
pub fn log_entry(entry: &Entry) -> Value {
    let mut out = log_line(&entry.line);
    if let Some(fields) = out.as_object_mut() {
        fields.insert("sequence".to_owned(), json!(entry.sequence));
    }
    out
}

/// The speed limits, in KiB/s, 0 for none.
pub fn limits(preferences: &Preferences) -> Value {
    json!({"uploadKiBps": preferences.max_upload_kibps, "downloadKiBps": preferences.max_download_kibps})
}

/// Rates `step` seconds apart, oldest first, each as `[down, up]` in bytes a second.
pub fn rates(step: u32, rates: &[Rate]) -> Value {
    json!({"step": step, "rates": rates.iter().map(|rate| json!([rate.down, rate.up])).collect::<Vec<_>>()})
}

/// The answer of a call that only needs to be done.
pub fn ok() -> Value {
    json!({"ok": true})
}

/// What an import took or would take, each file with its outcome, its detail in English and its phrases, whose keys
/// the interfaces translate.
pub fn import_report(report: &ImportReport) -> Value {
    let items: Vec<Value> = report
        .items
        .iter()
        .map(|item| {
            let phrases: Vec<Value> = item
                .phrases
                .iter()
                .map(|phrase| {
                    let args: Vec<Value> = phrase
                        .args
                        .iter()
                        .map(|arg| match arg {
                            Arg::Text(text) => json!(text),
                            Arg::Number(number) => json!(number),
                        })
                        .collect();
                    json!({"key": phrase.key.translation_key(), "args": args})
                })
                .collect();
            json!({
                "file": item.file,
                "outcome": item.outcome.name().to_lowercase(),
                "detail": item.detail(),
                "phrases": phrases,
            })
        })
        .collect();
    json!({"dryRun": report.dry_run, "startAtLogin": report.start_at_login, "items": items})
}

pub fn emule_history(typed: &EmuleHistory) -> Value {
    json!({"searches": typed.searches, "serverLists": typed.server_lists})
}

pub fn error(message: &str) -> Value {
    json!({"error": message})
}

/// The name and the data of an event, as the event stream sends them.
pub fn event(event: &EngineEvent) -> (&'static str, Value) {
    match event {
        EngineEvent::Started { version } => ("started", json!({"version": version})),
        EngineEvent::Stopping => ("stopping", Value::Object(Map::new())),
        EngineEvent::PreferencesChanged => ("preferences.changed", Value::Object(Map::new())),
        EngineEvent::Log(line) => ("log", log_line(line)),
        EngineEvent::ServerConnecting(info) => ("server.connecting", json!({"server": server(info)})),
        EngineEvent::ServerConnected { server: info, client_id, low_id, obfuscated } => (
            "server.connected",
            json!({"server": server(info), "clientId": client_id, "lowId": low_id, "obfuscated": obfuscated}),
        ),
        EngineEvent::ServerDisconnected { server: info, reason } => {
            let mut data = Map::new();
            if let Some(info) = info {
                data.insert("server".to_owned(), server(info));
            }
            data.insert("reason".to_owned(), json!(reason));
            ("server.disconnected", Value::Object(data))
        }
        EngineEvent::ServerMessage { server: info, text } => {
            ("server.message", json!({"server": server(info), "text": text}))
        }
        EngineEvent::ServerUpdated(info) => ("server.updated", json!({"server": server(info)})),
        EngineEvent::ServersChanged { count } => ("servers.changed", json!({"count": count})),
        EngineEvent::SearchResults { search, results } => (
            "search.results",
            json!({"search": search, "results": results.iter().map(search_result).collect::<Vec<_>>()}),
        ),
        EngineEvent::SearchEnded { search, results } => ("search.ended", json!({"search": search, "results": results})),
        EngineEvent::DownloadAdded(info) => ("download.added", json!({"download": download(info, false)})),
        EngineEvent::DownloadProgress(info) => ("download.progress", json!({"download": download(info, false)})),
        EngineEvent::DownloadCompleted { download: info, file } => {
            ("download.completed", json!({"download": download(info, false), "file": file.display().to_string()}))
        }
        EngineEvent::DownloadFailed { download: info, reason } => {
            ("download.failed", json!({"download": download(info, false), "reason": reason}))
        }
        EngineEvent::DownloadRemoved(removed) => ("download.removed", json!({"hash": hash(removed)})),
    }
}

/// A download, with the map of its parts when asked for.
pub fn download(info: &DownloadInfo, with_parts: bool) -> Value {
    let link = FileLink::of(&info.name, info.size, info.hash).map(|link| link.to_uri()).unwrap_or_default();
    let mut data = json!({
        "hash": hash(&info.hash),
        "name": info.name,
        "size": info.size,
        "completed": info.completed,
        "status": info.status.name(),
        "priority": info.priority.name(),
        "category": info.category,
        "sources": info.sources,
        "transferring": info.transferring,
        "bytesPerSecond": info.bytes_per_second,
        "previewFirst": info.preview_first,
        "autoSwap": info.auto_swap,
        "link": link,
        "kind": kind(&info.name),
    });
    if with_parts && let Value::Object(fields) = &mut data {
        fields.insert("parts".to_owned(), parts(&info.parts));
    }
    data
}

/// The map of a download's parts.
pub fn download_parts(info: &DownloadInfo) -> Value {
    json!({"hash": hash(&info.hash), "parts": parts(&info.parts)})
}

fn parts(map: &PartMap) -> Value {
    let spans = |spans: &[(u64, u64)]| spans.iter().map(|(start, end)| json!([start, end])).collect::<Vec<_>>();
    json!({
        "size": map.size,
        "missing": spans(&map.missing),
        "requested": spans(&map.requested),
        "sources": map.sources,
    })
}

/// A source of a download, as the list of downloading clients shows it.
pub fn source(info: &SourceInfo) -> Value {
    json!({
        "file": hash(&info.file),
        "fileName": info.file_name,
        "address": info.address,
        "nick": info.nick,
        "software": info.software,
        "state": info.state.name(),
        "queueRank": info.queue_rank,
        "received": info.received,
        "parts": info.parts,
        "partCount": info.part_count,
        "reportedName": info.reported_name,
        "askedFor": info.asked_for.as_ref().map(hash),
        "askedForName": info.asked_for_name,
        "country": info.country,
    })
}

// The kind of a file named `name`; other when its extension does not tell.
fn kind(name: &str) -> &'static str {
    match file_types::of(name) {
        Some(file_types::VIDEO) => "video",
        Some(file_types::AUDIO) => "audio",
        Some(file_types::IMAGE) => "image",
        Some(file_types::DOCUMENT) => "document",
        Some(_) if file_types::is_a(file_types::ARCHIVE, name) => "archive",
        Some(_) if file_types::is_a(file_types::DISC_IMAGE, name) => "disc_image",
        Some(_) => "program",
        None => "other",
    }
}

/// A file a search found, with what we know of it: downloading, shared, downloaded or cancelled before.
pub fn found(result: &SearchResult, known: Option<&str>) -> Value {
    let mut out = search_result(result);
    if let (Some(known), Some(fields)) = (known, out.as_object_mut()) {
        fields.insert("known".to_owned(), json!(known));
    }
    out
}

/// The files marked as spam or as not spam, and the names of those marked spam, each sorted.
pub fn spam_marks(marks: &SpamMarks) -> Value {
    let sorted = |hashes: &std::collections::HashSet<[u8; 16]>| {
        let mut texts: Vec<String> = hashes.iter().map(hash).collect();
        texts.sort();
        texts
    };
    let mut names: Vec<&String> = marks.names.iter().collect();
    names.sort();
    json!({"spam": sorted(&marks.spam), "notSpam": sorted(&marks.not_spam), "names": names})
}

/// A file a search found.
pub fn search_result(result: &SearchResult) -> Value {
    let mut out = json!({
        "hash": hash(&result.hash),
        "name": result.name,
        "size": result.size,
        "sources": result.sources,
        "completeSources": result.complete_sources,
        "type": result.file_type,
        // Its eD2K link carries it, so that a download from the link can check and repair the file.
        "aich": result.aich_hash.map(|aich| data_encoding::BASE32_NOPAD.encode(&aich)),
        "spamRating": result.spam_rating,
        "spam": result.is_spam(),
        "names": result
            .names
            .iter()
            .map(|name| json!({"name": name.name, "sources": name.sources, "completeSources": name.complete_sources}))
            .collect::<Vec<_>>(),
    });
    let media = &result.media;
    if *media != Media::default()
        && let Some(fields) = out.as_object_mut()
    {
        fields.insert(
            "media".to_owned(),
            json!({
                "artist": media.artist,
                "album": media.album,
                "title": media.title,
                "length": media.length,
                "bitrate": media.bitrate,
                "codec": media.codec,
            }),
        );
    }
    out
}

/// A shared file: what it is, where it lies and how much it was asked for.
pub fn shared_file(file: &SharedFileInfo) -> Value {
    let statistics = &file.statistics;
    json!({
        "hash": hash(&file.hash),
        "name": file.name,
        "size": file.size,
        "path": file.path.display().to_string(),
        "priority": file.priority.name(),
        "link": file.link().to_uri(),
        "statistics": {
            "requests": statistics.requests,
            "accepted": statistics.accepted,
            "transferred": statistics.transferred,
            "totalRequests": statistics.total_requests,
            "totalAccepted": statistics.total_accepted,
            "totalTransferred": statistics.total_transferred,
        },
    })
}

/// A known server: its address, what it told of itself and how we use it.
pub fn server(info: &ServerInfo) -> Value {
    json!({
        "address": info.address,
        "name": info.name,
        "description": info.description,
        "users": info.users,
        "files": info.files,
        "maxUsers": info.max_users,
        "lowIdUsers": info.low_id_users,
        "softFiles": info.soft_files,
        "hardFiles": info.hard_files,
        "failed": info.failed,
        "priority": info.priority.name(),
        "ping": info.ping,
        "version": info.version,
        "obfuscation": info.obfuscation,
        "static": info.is_static,
        "country": info.country,
    })
}

/// The state of Kad.
pub fn kad(info: &KadInfo) -> Value {
    json!({
        "connected": info.connected,
        "firewalled": info.firewalled,
        "contacts": info.contacts,
        "verifiedContacts": info.verified_contacts,
        "id": info.id,
        "publicIp": info.public_ip,
        "udpPort": info.udp_port,
        "udpFirewalled": info.udp_firewalled,
        "udpVerified": info.udp_verified,
        "buddy": match info.buddy {
            Buddy::None => "none",
            Buddy::Connecting => "connecting",
            Buddy::Connected => "connected",
            Buddy::Serving => "serving",
        },
        "users": info.users,
        "files": info.files,
    })
}

pub fn kad_contact(contact: &KadContactInfo) -> Value {
    json!({
        "id": contact.id,
        "address": contact.address,
        "type": contact.contact_type,
        "distance": contact.distance,
        "version": contact.version,
        "verified": contact.verified,
        "country": contact.country,
    })
}

/// A note Kad gave on a file: who published it, the name it gives the file, and its comment.
pub fn kad_note(note: &KadNote) -> Value {
    comment(&note.publisher, &note.file_name, &note.comment)
}

/// A rating and comment of a file: who gave it, under which name of the file.
pub fn comment(who: &str, file_name: &str, comment: &FileComment) -> Value {
    json!({
        "who": who,
        "fileName": file_name,
        "rating": comment.rating(),
        "text": comment.text(),
    })
}

pub fn kad_search(search: &KadSearchInfo) -> Value {
    json!({
        "target": search.target,
        "kind": match search.kind {
            Kind::Node => "node",
            Kind::Keyword => "keyword",
            Kind::Sources => "sources",
            Kind::Notes => "notes",
            Kind::StoreKeyword => "store_keyword",
            Kind::StoreSource => "store_source",
            Kind::StoreNotes => "store_notes",
        },
        "answers": search.answers,
    })
}

/// A hash as the API writes it: 32 upper-case hex digits.
pub fn hash(hash: &[u8; 16]) -> String {
    data_encoding::HEXUPPER.encode(hash)
}
