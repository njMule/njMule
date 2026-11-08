// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ApiSamples: records of the engine with every field set, as the API's answers and events carry
//! them.

#![allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]

use std::collections::{BTreeSet, HashSet};
use std::path::PathBuf;
use std::time::{Duration, SystemTime};

use njmule_engine::api::category::Category;
use njmule_engine::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus, SourceInfo, SourceState};
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::engine_status::EngineStatus;
use njmule_engine::api::file_comment::FileComment;
use njmule_engine::api::friend::Friend;
use njmule_engine::api::import_report::{Arg, ImportReport, Item, Key, Outcome, Phrase};
use njmule_engine::api::kad_contact_info::KadContactInfo;
use njmule_engine::api::kad_info::{Buddy, KadInfo};
use njmule_engine::api::kad_note::KadNote;
use njmule_engine::api::kad_search_info::{KadSearchInfo, Kind};
use njmule_engine::api::known_client::KnownClient;
use njmule_engine::api::log_line::{Level, LogLine};
use njmule_engine::api::peer_comment::PeerComment;
use njmule_engine::api::peer_info::{Identification, PeerInfo};
use njmule_engine::api::port_test::{Forwarding, PortTest, Reach};
use njmule_engine::api::saved_search::SavedSearch;
use njmule_engine::api::search_request::{Scope, SearchRequest};
use njmule_engine::api::search_result::{Media, Name, SearchResult};
use njmule_engine::api::server_info::ServerInfo;
use njmule_engine::api::shared_directory::SharedDirectory;
use njmule_engine::api::shared_file_info::{SharedFileInfo, Statistics};
use njmule_engine::api::spam_marks::SpamMarks;
use njmule_engine::api::upload_info::{BanInfo, UploadInfo, UploadState};
use njmule_engine::api::upload_priority::UploadPriority;
use njmule_engine::config::schedule::{DayOfWeek, Rule, Schedule};
use njmule_engine::files::part_file::PartMap;
use njmule_engine::server::server_list::{Address, Priority};
use time::Time;

pub const HASH: [u8; 16] = [0xab; 16];
pub const USER: [u8; 16] = [0xcd; 16];
/// The client the samples chat with.
pub const PEER: &str = "198.51.100.9:4662";

pub fn server() -> ServerInfo {
    ServerInfo {
        address: "203.0.113.10:4661".to_owned(),
        name: "Lighthouse".to_owned(),
        description: "Stable".to_owned(),
        users: 412_345,
        files: 25_153_045,
        max_users: 454_300,
        failed: 0,
        priority: Priority::High,
        ping: 24,
        soft_files: 28_864_150,
        hard_files: 37_111_050,
        low_id_users: 82_469,
        version: "17.15".to_owned(),
        obfuscation: true,
        is_static: false,
        country: Some("nl".to_owned()),
    }
}

pub fn kad() -> KadInfo {
    KadInfo {
        connected: true,
        contacts: 1243,
        verified_contacts: 873,
        firewalled: false,
        id: "8F3C2A1D90B44E6E9A0C7E51D2B3F4A5".to_owned(),
        public_ip: "198.51.100.23".to_owned(),
        udp_port: 4672,
        udp_firewalled: false,
        udp_verified: true,
        buddy: Buddy::None,
        users: 2_100_000,
        files: 310_000_000,
    }
}

/// A status connected to a server and to Kad, with every field the API may leave out, or connected to neither.
pub fn status(connected: bool) -> EngineStatus {
    EngineStatus {
        server: connected.then(server),
        client_id: 0x1764_3C6A,
        low_id: false,
        obfuscated: true,
        kad: connected.then(kad),
        download_bytes_per_second: 1_322_500,
        upload_bytes_per_second: 98_100,
        downloaded: 2_576_980_378,
        uploaded: 640_942_080,
        downloads: 11,
        uploads: 3,
        queued: 7,
        shared_files: 26,
        total_downloaded: 442_381_959_168,
        total_uploaded: 308_163_100_672,
        sessions: 214,
        session_seconds: 3 * 3600 + 42 * 60,
        total_seconds: 1_911 * 3600,
        public_ip: "198.51.100.23".to_owned(),
        tcp_port: 4662,
        related_search: true,
        missing_interface: connected.then(|| "eth9".to_owned()),
        public_ipv6: connected.then(|| "2001:db8::23".to_owned()),
        overhead_uploaded: 0,
        overhead_downloaded: 0,
        forwarding: if connected { "forwarded" } else { "off" }.to_owned(),
        router: if connected { "192.0.2.1" } else { "" }.to_owned(),
        kad_port_taken: connected.then_some(4672),
    }
}

pub fn download() -> DownloadInfo {
    DownloadInfo {
        hash: HASH,
        name: "talk.bin".to_owned(),
        size: 5000,
        completed: 1200,
        status: DownloadStatus::Downloading,
        priority: DownloadPriority::High,
        category: 1,
        sources: 4,
        transferring: 2,
        bytes_per_second: 30_000,
        parts: PartMap { size: 0, missing: Vec::new(), requested: Vec::new(), sources: Vec::new() },
        preview_first: false,
        auto_swap: false,
    }
}

pub fn source() -> SourceInfo {
    SourceInfo {
        file: HASH,
        file_name: "talk.bin".to_owned(),
        address: "198.51.100.7:4662".to_owned(),
        nick: Some("someone".to_owned()),
        software: "eMule v0.70b".to_owned(),
        state: SourceState::Queued,
        queue_rank: 12,
        received: 0,
        parts: 3,
        part_count: 1,
        reported_name: Some("talk (1).bin".to_owned()),
        asked_for: None,
        asked_for_name: String::new(),
        country: Some("it".to_owned()),
    }
}

pub fn upload() -> UploadInfo {
    UploadInfo {
        address: "198.51.100.8:4662".to_owned(),
        user_hash: USER,
        nick: None,
        software: "aMule 2.3.3".to_owned(),
        file: "talk.bin".to_owned(),
        state: UploadState::Uploading,
        transferred: 1_048_576,
        bytes_per_second: 20_000,
        waiting_seconds: 95,
        country: Some("fr".to_owned()),
    }
}

pub fn shared() -> SharedFileInfo {
    SharedFileInfo {
        hash: HASH,
        name: "talk.bin".to_owned(),
        size: 5000,
        path: PathBuf::from("shared").join("talk.bin"),
        priority: UploadPriority::VeryLow,
        statistics: Statistics {
            requests: 4,
            accepted: 2,
            transferred: 1800,
            total_requests: 40,
            total_accepted: 20,
            total_transferred: 18_000,
        },
    }
}

pub fn directory() -> SharedDirectory {
    SharedDirectory { path: PathBuf::from("shared"), subdirectories: true }
}

pub fn result() -> SearchResult {
    let mut result = SearchResult::new(HASH, "talk.bin", 5000, 3, None, Some("Pro"));
    result.media = Media::new("Ann", "Talks", "Opening", 3725, 192, "mp3");
    result.spam_rating = 70;
    result.aich_hash = Some([0x5a; 20]);
    result.names = vec![
        Name { name: "talk.bin".to_owned(), sources: 2, complete_sources: Some(1) },
        Name { name: "talk (2).bin".to_owned(), sources: 1, complete_sources: None },
    ];
    result
}

pub fn category() -> Category {
    Category {
        id: 1,
        name: "Talks".to_owned(),
        incoming: Some(std::path::absolute("talks").unwrap()),
        comment: String::new(),
        color: 0,
        priority: DownloadPriority::Auto,
        patterns: String::new(),
        regex: false,
    }
}

pub fn friend() -> Friend {
    Friend { user_hash: USER, name: "Ann".to_owned(), address: Address::parse(PEER) }
}

pub fn schedule() -> Schedule {
    let rule = Rule {
        name: "Night".to_owned(),
        enabled: true,
        days: BTreeSet::from([DayOfWeek::Monday, DayOfWeek::Sunday]),
        start: Time::from_hms(23, 0, 0).unwrap(),
        end: Time::from_hms(6, 0, 0).unwrap(),
        upload_kibps: None,
        download_kibps: Some(0),
        max_connections: Some(300),
        max_sources_per_file: None,
        stop_category: None,
        resume_category: Some(1),
    };
    Schedule { rules: vec![rule] }
}

pub fn port_test() -> PortTest {
    PortTest {
        tcp_port: 4662,
        udp_port: 4672,
        forwarding: Forwarding::Forwarded,
        router: "192.0.2.1".to_owned(),
        tcp: Reach::Open,
        udp: Reach::Testing,
        done: false,
    }
}

pub fn import_report() -> ImportReport {
    let servers = Item::new(
        "server.met",
        Outcome::WouldImport,
        vec![Phrase::of(Key::Servers, vec![Arg::Number(12)]), Phrase::of(Key::Added, vec![Arg::Number(3)])],
    );
    let known = Item::text("known.met", Outcome::Failed, "unreadable");
    let filter = Item::text("ipfilter.dat", Outcome::Missing, "");
    ImportReport { dry_run: true, items: vec![servers, known, filter], start_at_login: true }
}

pub fn saved_search() -> SavedSearch {
    SavedSearch {
        title: "talks".to_owned(),
        request: SearchRequest::new("talk", Scope::Global),
        results: vec![result()],
    }
}

pub fn spam_marks() -> SpamMarks {
    SpamMarks {
        spam: HashSet::from([HASH]),
        not_spam: HashSet::from([[0xff; 16]]),
        names: HashSet::from(["free money now.exe".to_owned()]),
    }
}

pub fn peer() -> PeerInfo {
    PeerInfo {
        address: PEER.to_owned(),
        user_hash: HASH,
        nick: Some("Ann".to_owned()),
        software: "eMule v0.70".to_owned(),
        obfuscated: true,
        identification: Identification::Verified,
    }
}

pub fn peer_comment() -> PeerComment {
    PeerComment { user: "Ann".to_owned(), file_name: "talk.bin".to_owned(), comment: FileComment::of("clean copy", 4) }
}

pub fn kad_note() -> KadNote {
    KadNote { publisher: "Bob".to_owned(), file_name: "talk (2).bin".to_owned(), comment: FileComment::of("fake", 1) }
}

pub fn ban() -> BanInfo {
    BanInfo { address: "192.0.2.200".to_owned(), seconds_left: 5400 }
}

pub fn kad_contact() -> KadContactInfo {
    KadContactInfo {
        id: "8F3C2A1D90B44E6E9A0C7E51D2B3F4A5".to_owned(),
        address: "198.51.100.40:4672".to_owned(),
        contact_type: 2,
        distance: "8F3C2A1D…".to_owned(),
        version: 9,
        verified: true,
        country: Some("jp".to_owned()),
    }
}

pub fn kad_search() -> KadSearchInfo {
    KadSearchInfo { target: "debian".to_owned(), kind: Kind::StoreKeyword, answers: 37 }
}

pub fn known_client() -> KnownClient {
    KnownClient {
        user_hash: HASH,
        nick: "ann".to_owned(),
        software: "eMule v0.70b".to_owned(),
        first_seen: 1_760_000_000,
        last_seen: 1_761_000_000,
        country: Some("it".to_owned()),
        uploaded: 5_000_000,
        downloaded: 12_000_000,
    }
}

pub fn log_line() -> LogLine {
    // 2026-10-18T08:00:00Z
    let time = SystemTime::UNIX_EPOCH + Duration::from_secs(1_792_310_400);
    LogLine { time, level: Level::Success, message: "Connected".to_owned() }
}

/// One event of every kind the engine announces.
pub fn events() -> Vec<EngineEvent> {
    vec![
        EngineEvent::Started { version: "0.1.0".to_owned() },
        EngineEvent::Stopping,
        EngineEvent::PreferencesChanged,
        EngineEvent::Log(log_line()),
        EngineEvent::ServerConnecting(server()),
        EngineEvent::ServerConnected { server: server(), client_id: 0x1764_3C6A, low_id: false, obfuscated: true },
        EngineEvent::ServerDisconnected { server: Some(server()), reason: "closed".to_owned() },
        EngineEvent::ServerMessage { server: server(), text: "Welcome".to_owned() },
        EngineEvent::ServerUpdated(server()),
        EngineEvent::ServersChanged { count: 14 },
        EngineEvent::SearchResults { search: 7, results: vec![result()] },
        EngineEvent::SearchEnded { search: 7, results: 1 },
        EngineEvent::DownloadAdded(download()),
        EngineEvent::DownloadProgress(download()),
        EngineEvent::DownloadCompleted { download: download(), file: PathBuf::from("incoming").join("talk.bin") },
        EngineEvent::DownloadFailed { download: download(), reason: "disk full".to_owned() },
        EngineEvent::DownloadRemoved(HASH),
        EngineEvent::DiskSpace { directory: PathBuf::from("temp"), enough: false },
        EngineEvent::MessageReceived { address: PEER.to_owned(), user_hash: USER, nick: None, text: "hi".to_owned() },
        EngineEvent::CaptchaRequested { address: PEER.to_owned(), user_hash: USER, image: vec![1, 2] },
        EngineEvent::CaptchaAnswered { address: PEER.to_owned(), solved: true },
        EngineEvent::MessageFailed { address: PEER.to_owned(), reason: "no connection".to_owned() },
    ]
}
