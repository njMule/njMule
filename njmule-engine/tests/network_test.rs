// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's NetworkTest, under its names: spam marks, shared folders, IP filter, categories, servers, Kad,
//! cancelled files, port tests, downloads, friends, hello probing and automatic connections.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket};
use std::path::Path;
use std::time::{Duration, Instant};

use njmule_engine::api::category::Category;
use njmule_engine::api::download_info::{DownloadPriority, DownloadStatus};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::friend::Friend;
use njmule_engine::api::log_line::Level as LogLevel;
use njmule_engine::api::peer_info::Identification;
use njmule_engine::api::port_test::{Forwarding, Reach};
use njmule_engine::api::search_request::Scope;
use njmule_engine::api::search_result::SearchResult;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::config::schedule::{DayOfWeek, Rule, Schedule};
use njmule_engine::engine::Engine;
use njmule_engine::engine::switches::Switches;
use njmule_engine::files::cancelled_files;
use njmule_engine::kad::kad_node;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::net::tcp_connection::{read_packet, write_packet};
use njmule_engine::persist::format::ip_filter_dat::{self, Range};
use njmule_engine::persist::format::nodes_dat::{self, Contact, UdpKey};
use njmule_engine::persist::format::server_met::{self, Server};
use njmule_engine::protocol::ed2k::hello::{self, Hello, Ours};
use njmule_engine::protocol::ed2k::server_messages::{self, Endpoint, IdChange, Login, ServerIdent, ServerStatus};
use njmule_engine::protocol::io::{PacketReader, PacketWriter};
use njmule_engine::protocol::packet::opcodes::{client, extended, server};
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::protocol::tag::{Tag, TagName, server_tag};
use njmule_engine::search::{spam_filter, stored_searches};
use njmule_engine::server::server_list::{self, Address, Priority};
use njmule_engine::util::ipv4;
use time::Time;
use tokio::net::TcpListener as AsyncTcpListener;
use tokio::sync::broadcast;

use crate::free_port;
use crate::scripted_server::{HIGH_ID, ScriptedServer};

const WAIT: Duration = Duration::from_secs(10);
const OTHER_HIGH_ID: u32 = 0x0807_0605;
const BLOCKED: u32 = 0xCB00_7107; // 203.0.113.7
const CRYPT: u32 = hello::MISC2_SUPPORTS_CRYPT | hello::MISC2_REQUESTS_CRYPT | hello::MISC2_REQUIRES_CRYPT;
const PAL: [u8; 16] = [0xe1; 16];
const PEER_HASH: [u8; 16] = [0x21; 16];

fn spam_result() -> SearchResult {
    SearchResult::new([0xb1; 16], "free movie installer.exe", 100, 1, Some(0), None)
}

fn films_cat() -> Category {
    Category {
        id: 1,
        name: "Films".to_owned(),
        incoming: None,
        comment: String::new(),
        color: 1,
        priority: DownloadPriority::Auto,
        patterns: String::new(),
        regex: false,
    }
}

fn music_cat() -> Category {
    Category {
        id: 2,
        name: "Music".to_owned(),
        incoming: None,
        comment: String::new(),
        color: 2,
        priority: DownloadPriority::Auto,
        patterns: String::new(),
        regex: false,
    }
}

fn known_server() -> Address {
    Address::new(ipv4::of(198, 51, 100, 7), 4661)
}

fn unknown_server() -> Address {
    Address::new(ipv4::of(198, 51, 100, 8), 4661)
}

fn contact(byte: u8, ip: u32) -> Contact {
    Contact {
        id: UInt128::from_big_endian([byte; 16]),
        ip,
        udp_port: 4672,
        tcp_port: 4662,
        version: 8,
        contact_type: 0,
        udp_key: UdpKey::default(),
        verified: true,
    }
}

fn first_contact() -> Contact {
    contact(0xc1, ipv4::of(198, 51, 100, 11))
}

fn second_contact() -> Contact {
    contact(0xc2, ipv4::of(198, 51, 100, 12))
}

fn lan_contact() -> Contact {
    contact(0xc3, ipv4::of(10, 0, 0, 7))
}

fn film_link(name: &str, size: u64, byte: u8) -> FileLink {
    FileLink::of(name, size, [byte; 16]).unwrap()
}

fn peer_hello() -> Hello {
    let ours = Ours { nick: "peer", udp_port: 0, kad_port: 0, misc_options1: 0, misc_options2: 0 };
    Hello::of(PEER_HASH, 0, 4999, &ours, 0, 0)
}

#[tokio::test]
async fn should_keep_spam_marks_that_cannot_be_saved() {
    let mut lab = Lab::new();
    lab.start("").await;
    let spam = spam_result();
    let marks = lab.engine().mark_spam(vec![spam.clone()], true).await;
    assert_eq!(marks.spam.len(), 1);
    assert!(lab.config().join(spam_filter::FILE_NAME).exists());
    block_writes(&lab.config().join(spam_filter::FILE_NAME));

    let marks = lab.engine().mark_spam(vec![spam.clone()], false).await;

    assert!(marks.spam.is_empty());
    assert!(marks.not_spam.contains(&spam.hash));
    assert_eq!(lab.engine().spam_marks().await, marks);
    lab.stop().await;
}

#[tokio::test]
async fn should_share_and_unshare_folders_once_started() {
    let mut lab = Lab::new();
    lab.start("").await;
    let films = lab.dir.path().join("films");
    let music = lab.dir.path().join("music");
    fs::create_dir_all(&films).unwrap();
    fs::create_dir_all(&music).unwrap();
    lab.engine().share_directory(films.clone(), false).await.unwrap();
    lab.engine().share_directory(music.clone(), true).await.unwrap();
    let mut listed: Vec<String> = lab
        .engine()
        .shared_directories()
        .await
        .into_iter()
        .map(|d| {
            format!("{}{}", d.path.file_name().unwrap().to_string_lossy(), if d.subdirectories { "/**" } else { "" })
        })
        .collect();
    listed.sort();
    assert_eq!(listed, ["films", "music/**"]);

    lab.engine().unshare_directory(films).await.unwrap();

    assert_eq!(lab.engine().shared_directories().await.len(), 1);
    let desc = lab.engine().describe().await;
    assert!(desc.starts_with("downloads:") && desc.contains("uploads: "), "desc: {desc}");
    lab.stop().await;
}

#[tokio::test]
async fn should_never_replace_the_ip_filter_by_nothing() {
    let mut lab = Lab::new();
    lab.start("").await;

    let res = lab.engine().update_ip_filter("http://127.0.0.1:9/empty.dat".to_owned()).await;

    assert!(res.is_err());
    lab.stop().await;
}

#[tokio::test]
async fn should_replace_the_ip_filter_by_a_list() {
    let mut lab = Lab::new();
    let ranges = vec![
        Range { start: BLOCKED, end: BLOCKED, level: 0, description: "test".to_owned() },
        Range { start: 1, end: 2, level: 0, description: "low".to_owned() },
    ];
    fs::create_dir_all(lab.config()).unwrap();
    fs::write(lab.config().join("ipfilter.dat"), ip_filter_dat::write(&ranges)).unwrap();
    lab.start("").await;

    assert!(lab.config().join("ipfilter.dat").exists());
    let err = lab.engine().connect_server(Some(Address::new(BLOCKED, 4661))).await;
    assert!(err.is_err());
    lab.stop().await;
}

#[tokio::test]
async fn should_refuse_to_connect_to_a_server_the_ip_filter_blocks() {
    let mut lab = Lab::new();
    let ranges = vec![Range { start: BLOCKED, end: BLOCKED, level: 0, description: "test".to_owned() }];
    fs::create_dir_all(lab.config()).unwrap();
    fs::write(lab.config().join("ipfilter.dat"), ip_filter_dat::write(&ranges)).unwrap();
    lab.start("").await;

    let err = lab.engine().connect_server(Some(Address::new(BLOCKED, 4661))).await.unwrap_err();

    assert!(format!("{err:?}").contains("IP filter"), "err: {err:?}");
    lab.stop().await;
}

#[tokio::test]
async fn should_refuse_categories_that_share_an_id() {
    let mut lab = Lab::new();
    lab.start("").await;

    let err = lab.engine().set_categories(vec![films_cat(), films_cat()]).await;

    assert!(err.is_err());
    assert!(lab.engine().categories().await.is_empty());
    lab.stop().await;
}

#[tokio::test]
async fn should_take_unreadable_files_as_empty() {
    let mut lab = Lab::new();
    fs::create_dir_all(lab.config()).unwrap();
    fs::write(lab.config().join(stored_searches::FILE_NAME), [9, 9, 9]).unwrap();
    fs::write(lab.config().join(cancelled_files::FILE_NAME), [9, 9, 9]).unwrap();

    lab.start("").await;

    assert!(lab.engine().saved_searches().await.is_empty());
    assert!(!lab.engine().file_history().await.cancelled(&[0x0a; 16]));
    lab.stop().await;
}

#[tokio::test]
async fn should_change_servers_even_when_their_files_cannot_be_saved() {
    let mut lab = Lab::new();
    lab.start("server.manualHighPriority=true\n").await;
    block_writes(&lab.config().join(server_list::FILE_NAME));
    block_writes(&lab.config().join(server_list::STATIC_FILE_NAME));
    let mut events = lab.engine().subscribe().live;

    assert!(lab.engine().add_server(known_server()).await);
    assert!(!lab.engine().add_server(known_server()).await);
    let servers = lab.engine().servers().await;
    assert_eq!(servers.len(), 1);
    assert_eq!(servers[0].priority, Priority::High);
    assert!(lab.engine().set_static_server(known_server(), true).await);
    assert!(lab.engine().set_server_priority(known_server(), Priority::Low).await);
    assert!(!lab.engine().set_static_server(unknown_server(), true).await);
    assert!(!lab.engine().set_server_priority(unknown_server(), Priority::Low).await);
    let updated = next_event(&mut events, |e| matches!(e, EngineEvent::ServerUpdated(s) if s.is_static)).await;
    assert!(updated.is_some());

    assert!(lab.engine().remove_server(known_server()).await);
    assert!(lab.engine().servers().await.is_empty());
    lab.stop().await;
}

#[tokio::test]
async fn should_leave_us_unreachable_when_another_program_holds_our_port() {
    let mut lab = Lab::new();
    let taken = njmule_engine::net::tcp_connector::listen(None, 0).await.unwrap();
    let port = taken.local_addr().unwrap().port();
    lab.tcp_port = port;

    lab.start("").await;

    let logs = lab.backlog_logs();
    assert!(logs.iter().any(|(_, m)| m.starts_with(&format!("Cannot listen on TCP port {port}"))), "logs: {logs:?}");
    assert_eq!(lab.engine().status().await.unwrap().tcp_port, port);
    lab.stop().await;
    drop(taken);
}

#[tokio::test]
async fn should_not_recheck_the_kad_firewall_nor_list_anything_while_kad_does_not_run() {
    let mut lab = Lab::new();
    lab.start("").await;

    assert!(!lab.engine().recheck_kad().await);
    assert!(lab.engine().kad_contacts().await.is_empty());
    assert!(lab.engine().kad_searches().await.is_empty());
    lab.stop().await;
}

#[tokio::test]
async fn should_refuse_a_kad_bootstrap_while_kad_does_not_run() {
    let mut lab = Lab::new();
    lab.start("").await;

    assert!(lab.engine().bootstrap_kad(SocketAddrV4::new(Ipv4Addr::LOCALHOST, 9)).await.is_err());
    lab.stop().await;
}

#[tokio::test]
async fn should_recheck_the_kad_firewall_while_kad_runs() {
    let mut lab = Lab::new();
    lab.start("").await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_kad().await;
    lab.engine().connect_kad().await;
    assert!(lab.engine().kad().await.is_some());

    assert!(lab.engine().recheck_kad().await);
    assert!(await_log(&mut events, LogLevel::Info, "Kad: checking the firewall again").await);
    lab.stop().await;
}

#[tokio::test]
async fn should_take_no_lan_contact_while_kad_runs() {
    let mut lab = Lab::new();
    lab.start("").await;
    lab.engine().connect_kad().await;
    lab.engine().connect_kad().await;
    let port = free_port::free_udp_port();
    lab.engine().bootstrap_kad(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port)).await.unwrap();

    let added = lab
        .engine()
        .keep_kad_contacts(nodes_dat::Contents { file_version: 2, bootstrap: false, contacts: vec![lan_contact()] })
        .await
        .unwrap();

    assert_eq!(added, 0);
    assert!(lab.engine().kad_contacts().await.is_empty());
    assert!(lab.engine().kad_searches().await.is_empty());
    lab.stop().await;
}

#[tokio::test]
async fn should_stop_kad_once_when_disconnected_twice() {
    let mut lab = Lab::new();
    lab.start("").await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_kad().await;
    lab.engine().connect_kad().await;

    lab.engine().disconnect_kad().await;
    lab.engine().disconnect_kad().await;

    assert!(lab.engine().kad().await.is_none());
    let logs = drain_logs(&mut events, Duration::from_millis(300)).await;
    assert_eq!(logs.iter().filter(|(_, m)| m == "Kad stopped").count(), 1, "logs: {logs:?}");
    lab.stop().await;
}

#[tokio::test]
async fn should_refuse_a_kad_search_while_kad_does_not_run() {
    let mut lab = Lab::new();
    lab.start("").await;

    let err = lab.engine().start_search("ubuntu".to_owned(), Some(Scope::Kad)).await.unwrap_err();

    assert!(format!("{err:?}").contains("not running"), "err: {err:?}");
    lab.stop().await;
}

#[tokio::test]
async fn should_refuse_a_kad_search_without_a_contact_to_ask() {
    let mut lab = Lab::new();
    lab.start("").await;
    lab.engine().connect_kad().await;

    let err = lab.engine().start_search("ubuntu".to_owned(), Some(Scope::Kad)).await.unwrap_err();

    assert!(format!("{err:?}").contains("now"), "err: {err:?}");
    assert!(lab.engine().kad_searches().await.is_empty());
    lab.stop().await;
}

#[tokio::test]
async fn should_tell_in_our_hello_how_much_obfuscation_we_want() {
    let cases = [
        (ObfuscationMode::Disabled, 0),
        (ObfuscationMode::Supported, hello::MISC2_SUPPORTS_CRYPT),
        (ObfuscationMode::Requested, hello::MISC2_SUPPORTS_CRYPT | hello::MISC2_REQUESTS_CRYPT),
        (ObfuscationMode::Required, CRYPT),
    ];
    for (mode, wanted) in cases {
        let mut lab = Lab::new();
        lab.start(&format!("security.obfuscation={}\n", mode.name())).await;
        let peer = AsyncTcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let addr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), peer.local_addr().unwrap().port());
        let accept = tokio::spawn(async move {
            let (mut socket, _) = peer.accept().await.unwrap();
            let pkt = read_packet(&mut socket, &|_, _| Some(1 << 20)).await.unwrap().unwrap();
            drop(socket);
            pkt
        });

        let result = lab.engine().hello(addr).await;
        let pkt = accept.await.unwrap();

        assert_eq!(Hello::decode(&pkt).unwrap().misc_options2() & CRYPT, wanted, "mode={mode:?}");
        assert!(result.is_err());
        lab.stop().await;
    }
}

#[tokio::test]
async fn should_report_kad_that_cannot_take_its_port() {
    let mut lab = Lab::new();
    let taken = UdpSocket::bind(("0.0.0.0", 0)).unwrap();
    let udp_port = taken.local_addr().unwrap().port();
    lab.udp_port = udp_port;

    lab.start("net.kad.enabled=true\n").await;

    let logs = lab.backlog_logs();
    assert!(
        logs.iter().any(|(_, m)| m.starts_with(&format!("Cannot start Kad on UDP port {udp_port}"))),
        "logs: {logs:?}"
    );
    lab.stop().await;
    drop(taken);
}

#[tokio::test]
async fn should_not_start_kad_by_hand_without_its_port() {
    let mut lab = Lab::new();
    let taken = UdpSocket::bind(("0.0.0.0", 0)).unwrap();
    lab.udp_port = taken.local_addr().unwrap().port();
    lab.start("net.kad.enabled=true\n").await;

    lab.engine().connect_kad().await;

    assert!(lab.engine().kad().await.is_none());
    lab.stop().await;
    drop(taken);
}

#[tokio::test]
async fn should_turn_off_quietly_kad_that_could_not_take_its_port() {
    let mut lab = Lab::new();
    let taken = UdpSocket::bind(("0.0.0.0", 0)).unwrap();
    lab.udp_port = taken.local_addr().unwrap().port();
    lab.start("net.kad.enabled=true\n").await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_kad().await;

    lab.engine()
        .update_preferences(&BTreeMap::from([("net.kad.enabled".to_owned(), "false".to_owned())]))
        .unwrap();

    assert!(lab.engine().kad().await.is_none());
    let logs = drain_logs(&mut events, Duration::from_millis(200)).await;
    assert!(!logs.iter().any(|(_, m)| m == "Kad stopped"), "logs: {logs:?}");
    lab.stop().await;
    drop(taken);
}

#[tokio::test]
async fn should_take_back_a_cancelled_download_and_forget_the_cancel() {
    let mut lab = Lab::new();
    lab.start("").await;
    let link = film_link("film.mkv", 5000, 0xf4);
    lab.engine().add_download(link.clone(), None).await.unwrap();
    assert!(lab.engine().cancel_download(link.hash).await);
    assert!(lab.engine().file_history().await.cancelled(&link.hash));

    assert!(lab.engine().restore_download(link.hash).await);

    assert!(lab.engine().downloads().await.iter().any(|d| d.hash == link.hash));
    assert!(!lab.engine().file_history().await.cancelled(&link.hash));
    lab.stop().await;
}

#[tokio::test]
async fn should_leave_on_disk_the_cancelled_files_that_cannot_be_forgotten() {
    let mut lab = Lab::new();
    lab.start("").await;
    let link = film_link("film.mkv", 5000, 0xf4);
    lab.engine().add_download(link.clone(), None).await.unwrap();
    assert!(lab.engine().cancel_download(link.hash).await);
    lab.stop_keep_dir().await;

    let cancelled_path = lab.config().join(cancelled_files::FILE_NAME);
    let remembered = fs::read(&cancelled_path).unwrap();
    block_writes(&cancelled_path);

    lab.start("files.rememberCancelled=false\n").await;

    assert!(!lab.engine().file_history().await.cancelled(&link.hash));
    assert_eq!(fs::read(&cancelled_path).unwrap(), remembered);
    lab.stop().await;
}

#[tokio::test]
async fn should_keep_nothing_of_a_bootstrap_list() {
    let mut lab = Lab::new();
    fs::create_dir_all(lab.config()).unwrap();
    fs::write(lab.config().join(kad_node::NODES_FILE), nodes_dat::write(&[first_contact()]).unwrap()).unwrap();
    lab.start("").await;

    let added = lab
        .engine()
        .keep_kad_contacts(nodes_dat::Contents { file_version: 2, bootstrap: true, contacts: vec![second_contact()] })
        .await
        .unwrap();

    assert_eq!(added, 0);
    lab.stop().await;
}

#[tokio::test]
async fn should_add_the_new_kad_contacts_to_nodes_dat_while_kad_is_off() {
    let mut lab = Lab::new();
    fs::create_dir_all(lab.config()).unwrap();
    fs::write(lab.config().join(kad_node::NODES_FILE), nodes_dat::write(&[first_contact()]).unwrap()).unwrap();
    lab.start("").await;

    let added = lab
        .engine()
        .keep_kad_contacts(nodes_dat::Contents {
            file_version: 2,
            bootstrap: false,
            contacts: vec![first_contact(), second_contact()],
        })
        .await
        .unwrap();

    assert_eq!(added, 1);
    let saved = nodes_dat::read(&fs::read(lab.config().join(kad_node::NODES_FILE)).unwrap()).unwrap();
    let ids: Vec<UInt128> = saved.contacts.into_iter().map(|c| c.id).collect();
    assert_eq!(ids, [first_contact().id, second_contact().id]);
    lab.stop().await;
}

#[tokio::test]
async fn should_add_the_kad_contacts_to_nodes_dat_while_kad_runs() {
    let mut lab = Lab::new();
    lab.start("").await;
    lab.engine().connect_kad().await;
    lab.engine().connect_kad().await;

    let added = lab
        .engine()
        .keep_kad_contacts(nodes_dat::Contents { file_version: 2, bootstrap: false, contacts: vec![lan_contact()] })
        .await
        .unwrap();

    assert_eq!(added, 0);
    let saved = nodes_dat::read(&fs::read(lab.config().join(kad_node::NODES_FILE)).unwrap()).unwrap();
    let ids: Vec<UInt128> = saved.contacts.into_iter().map(|c| c.id).collect();
    assert_eq!(ids, [lan_contact().id]);
    lab.stop().await;
}

#[tokio::test]
async fn should_log_in_again_and_move_kad_when_the_ports_change() {
    let mut lab = Lab::new();
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    lab.start("net.kad.enabled=true\n").await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_server(Some(Address::new(server.ip(), server.port()))).await.unwrap();
    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerConnected { .. })).await.is_some());
    assert!(server.next(server::LOGINREQUEST, WAIT).await.is_some());
    let old_kad = lab.engine().kad().await.unwrap().udp_port;

    let new_tcp = free_port::free_port();
    let new_udp = free_port::free_udp_port();
    lab.engine()
        .update_preferences(&BTreeMap::from([
            ("net.tcpPort".to_owned(), new_tcp.to_string()),
            ("net.udpPort".to_owned(), new_udp.to_string()),
        ]))
        .unwrap();

    let login = server.next(server::LOGINREQUEST, WAIT).await.unwrap();
    assert_eq!(Login::decode(&login).unwrap().tcp_port, new_tcp);
    let kad_now = lab.engine().kad().await.unwrap().udp_port;
    assert_eq!(kad_now, new_udp);
    assert_ne!(kad_now, old_kad);
    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerConnected { .. })).await.is_some());
    lab.stop().await;
}

#[tokio::test]
async fn should_find_the_tcp_port_open_when_the_server_takes_us_again_with_a_high_id() {
    let mut lab = Lab::new();
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    lab.start("").await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_server(Some(Address::new(server.ip(), server.port()))).await.unwrap();
    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerConnected { .. })).await.is_some());
    assert!(server.next(server::LOGINREQUEST, WAIT).await.is_some());

    let test = lab.engine().test_ports().await.unwrap();
    assert!(server.next(server::LOGINREQUEST, WAIT).await.is_some());
    let finished = await_port_test(lab.engine(), test).await;

    assert!(finished.done);
    assert_eq!(finished.forwarding, Forwarding::Off);
    assert_eq!(finished.tcp, Reach::Open);
    assert_eq!(finished.udp, Reach::Unknown);
    lab.stop().await;
}

#[tokio::test]
async fn should_find_the_tcp_port_closed_when_the_server_takes_us_again_with_a_low_id() {
    let mut lab = Lab::new();
    let server = ScriptedServer::start(12_345, 0).await;
    lab.start("").await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_server(Some(Address::new(server.ip(), server.port()))).await.unwrap();
    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerConnected { .. })).await.is_some());
    assert!(server.next(server::LOGINREQUEST, WAIT).await.is_some());

    let test = lab.engine().test_ports().await.unwrap();
    let finished = await_port_test(lab.engine(), test).await;

    assert!(finished.done);
    assert_eq!(finished.tcp, Reach::Closed);
    assert_eq!(finished.udp, Reach::Unknown);
    lab.stop().await;
}

#[tokio::test]
async fn should_end_the_test_of_the_ports_left_for_a_new_one_on_the_new_ports() {
    let mut lab = Lab::new();
    lab.start("net.kad.enabled=true\n").await;
    let first = lab.engine().test_ports().await.unwrap();
    assert!(!first.done);

    let new_tcp = free_port::free_port();
    let new_udp = free_port::free_udp_port();
    lab.engine()
        .update_preferences(&BTreeMap::from([
            ("net.tcpPort".to_owned(), new_tcp.to_string()),
            ("net.udpPort".to_owned(), new_udp.to_string()),
        ]))
        .unwrap();
    let deadline = Instant::now() + WAIT;
    while lab.engine().listen_port() != new_tcp || lab.engine().kad().await.map(|k| k.udp_port) != Some(new_udp) {
        assert!(Instant::now() < deadline, "ports did not move to {new_tcp}/{new_udp}");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }

    let next = lab.engine().test_ports().await.unwrap();
    assert_eq!(next.tcp_port, new_tcp);
    assert_eq!(next.udp_port, new_udp);
    assert!(!next.done);
    lab.stop().await;
}

#[tokio::test]
async fn should_join_the_test_of_the_ports_that_runs_on_the_same_ports() {
    let mut lab = Lab::new();
    lab.start("net.kad.enabled=true\n").await;
    let first = lab.engine().test_ports().await.unwrap();
    assert!(!first.done);

    let second = lab.engine().test_ports().await.unwrap();

    assert_eq!(second.tcp_port, first.tcp_port);
    assert_eq!(second.udp_port, first.udp_port);
    assert!(!second.done);
    lab.stop().await;
}

#[tokio::test]
async fn should_tell_the_ports_unknown_at_once_without_a_server_or_kad() {
    let mut lab = Lab::new();
    lab.start("").await;

    let result = lab.engine().test_ports().await.unwrap();

    assert!(result.done);
    assert_eq!(result.tcp, Reach::Unknown);
    assert_eq!(result.udp, Reach::Unknown);
    lab.stop().await;
}

#[tokio::test]
async fn should_not_take_a_server_list_it_did_not_ask_for() {
    let mut lab = Lab::new();
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    lab.start("net.kad.enabled=true\n").await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_server(Some(Address::new(server.ip(), server.port()))).await.unwrap();
    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerConnected { .. })).await.is_some());

    server
        .send(&[
            server_messages::server_list(&[Endpoint { ip: ipv4::of(198, 51, 100, 31), port: 65_534 }]).unwrap(),
            server_messages::message("after the list").unwrap(),
        ])
        .await;
    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerMessage { .. })).await.is_some());

    assert!(lab.engine().servers().await.is_empty());
    lab.stop().await;
}

#[tokio::test]
async fn should_disconnect_when_ed2k_is_turned_off() {
    let mut lab = Lab::new();
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    lab.start("net.kad.enabled=true\n").await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_server(Some(Address::new(server.ip(), server.port()))).await.unwrap();
    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerConnected { .. })).await.is_some());

    lab.engine()
        .update_preferences(&BTreeMap::from([
            ("net.ed2k.enabled".to_owned(), "false".to_owned()),
            ("files.rememberCancelled".to_owned(), "false".to_owned()),
        ]))
        .unwrap();

    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerDisconnected { .. })).await.is_some());
    assert!(lab.engine().status().await.unwrap().server.is_none());
    lab.stop().await;
}

#[tokio::test]
async fn should_log_in_and_ask_for_the_servers_the_server_knows() {
    let mut lab = Lab::new();
    fs::create_dir_all(lab.config()).unwrap();
    fs::write(
        lab.config().join("ipfilter.dat"),
        ip_filter_dat::write(&[Range { start: BLOCKED, end: BLOCKED, level: 0, description: "test".to_owned() }]),
    )
    .unwrap();
    lab.start("server.addFromServer=true\nsecurity.filterLan=true\n").await;
    let dead = vec![
        Server {
            ip: ipv4::of(198, 51, 100, 21),
            port: 65_534,
            tags: vec![Tag::Int { name: TagName::id(server_tag::FAIL), value: 99 }],
        },
        Server {
            ip: ipv4::of(198, 51, 100, 22),
            port: 65_534,
            tags: vec![Tag::Int { name: TagName::id(server_tag::FAIL), value: 99 }],
        },
    ];
    assert_eq!(lab.engine().import_servers(dead).await, 2);

    let server = ScriptedServer::start(HIGH_ID, server_messages::TCP_RELATED_SEARCH).await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_server(Some(Address::new(server.ip(), server.port()))).await.unwrap();
    let connected = next_event(&mut events, |e| matches!(e, EngineEvent::ServerConnected { .. })).await.unwrap();

    match connected {
        EngineEvent::ServerConnected { client_id, .. } => assert_eq!(client_id, HIGH_ID),
        _ => unreachable!(),
    }
    assert!(lab.engine().status().await.unwrap().related_search);
    assert!(server.next(server::GETSERVERLIST, WAIT).await.is_some());
    lab.stop().await;
}

#[tokio::test]
async fn should_log_what_the_server_says_and_learn_the_servers_it_knows() {
    let mut lab = Lab::new();
    fs::create_dir_all(lab.config()).unwrap();
    fs::write(
        lab.config().join("ipfilter.dat"),
        ip_filter_dat::write(&[Range { start: BLOCKED, end: BLOCKED, level: 0, description: "test".to_owned() }]),
    )
    .unwrap();
    lab.start("server.addFromServer=true\nsecurity.filterLan=true\n").await;
    let dead = vec![
        Server {
            ip: ipv4::of(198, 51, 100, 21),
            port: 65_534,
            tags: vec![Tag::Int { name: TagName::id(server_tag::FAIL), value: 99 }],
        },
        Server {
            ip: ipv4::of(198, 51, 100, 22),
            port: 65_534,
            tags: vec![Tag::Int { name: TagName::id(server_tag::FAIL), value: 99 }],
        },
    ];
    assert_eq!(lab.engine().import_servers(dead).await, 2);

    let server = ScriptedServer::start(HIGH_ID, server_messages::TCP_RELATED_SEARCH).await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_server(Some(Address::new(server.ip(), server.port()))).await.unwrap();
    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerConnected { .. })).await.is_some());
    assert!(server.next(server::GETSERVERLIST, WAIT).await.is_some());

    server
        .send(&[
            server_messages::message("ERROR: no more room\nWARNING: low id\nWelcome").unwrap(),
            server_messages::server_list(&[
                Endpoint { ip: ipv4::of(198, 51, 100, 1), port: 65_534 },
                Endpoint { ip: ipv4::of(198, 51, 100, 2), port: 65_533 },
            ])
            .unwrap(),
            callback_packet(ipv4::of(10, 0, 0, 5)),
            callback_packet(BLOCKED),
            callback_packet(ipv4::of(127, 0, 0, 9)),
            Packet::empty(protocol::EDONKEY, server::REJECT),
            Packet::of(protocol::EDONKEY, server::CALLBACKREQUESTED, vec![0u8; 2]),
            Packet::of(protocol::EDONKEY, 0xEE, vec![0u8; 2]),
            ServerStatus { users: 500, files: 600 }.encode(),
        ])
        .await;

    let mut logs = Vec::new();
    let mut saw_users = false;
    let deadline = tokio::time::Instant::now() + WAIT;
    while tokio::time::Instant::now() < deadline && !saw_users {
        if let Ok(Ok(ev)) = tokio::time::timeout(Duration::from_millis(100), events.recv()).await {
            match ev {
                EngineEvent::Log(line) => logs.push((line.level, line.message)),
                EngineEvent::ServerUpdated(s) if s.users == 500 => saw_users = true,
                _ => {}
            }
        }
    }
    logs.extend(drain_logs(&mut events, Duration::from_millis(200)).await);
    assert!(saw_users);
    assert!(logs.iter().any(|(l, m)| *l == LogLevel::Error && m == "ERROR: no more room"), "logs: {logs:?}");
    assert!(logs.iter().any(|(l, m)| *l == LogLevel::Warning && m == "WARNING: low id"), "logs: {logs:?}");
    assert!(logs.iter().any(|(l, m)| *l == LogLevel::Info && m == "2 dead servers removed"), "logs: {logs:?}");
    let expected_from = format!("2 new servers from 127.0.0.1:{}", server.port());
    assert!(logs.iter().any(|(l, m)| *l == LogLevel::Info && m == &expected_from), "logs: {logs:?}");
    assert_eq!(lab.engine().servers().await.len(), 2);
    lab.stop().await;
}

#[tokio::test]
async fn should_take_the_name_the_server_gives_itself() {
    let mut lab = Lab::new();
    let server = ScriptedServer::start(HIGH_ID, server_messages::TCP_RELATED_SEARCH).await;
    lab.start("").await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_server(Some(Address::new(server.ip(), server.port()))).await.unwrap();
    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerConnected { .. })).await.is_some());

    server
        .send(&[ServerIdent {
            hash: [0x33; 16],
            ip: server.ip(),
            port: server.port(),
            name: Some("Scripted".to_owned()),
            description: None,
        }
        .encode()
        .unwrap()])
        .await;

    let updated = next_event(&mut events, |e| matches!(e, EngineEvent::ServerUpdated(s) if s.name == "Scripted")).await;
    assert!(updated.is_some());
    lab.stop().await;
}

#[tokio::test]
async fn should_tell_when_the_server_hangs_up() {
    let mut lab = Lab::new();
    let server = ScriptedServer::start(HIGH_ID, server_messages::TCP_RELATED_SEARCH).await;
    lab.start("").await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_server(Some(Address::new(server.ip(), server.port()))).await.unwrap();
    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerConnected { .. })).await.is_some());
    server
        .send(&[ServerIdent {
            hash: [0x33; 16],
            ip: server.ip(),
            port: server.port(),
            name: Some("Scripted".to_owned()),
            description: None,
        }
        .encode()
        .unwrap()])
        .await;
    assert!(
        next_event(&mut events, |e| matches!(e, EngineEvent::ServerUpdated(s) if s.name == "Scripted"))
            .await
            .is_some()
    );

    server.hang_up();

    let mut disconnected_named = false;
    let mut logged_disconnect = false;
    let prefix = format!("Disconnected from Scripted (127.0.0.1:{})", server.port());
    let deadline = tokio::time::Instant::now() + WAIT;
    while tokio::time::Instant::now() < deadline && !(disconnected_named && logged_disconnect) {
        if let Ok(Ok(ev)) = tokio::time::timeout(Duration::from_millis(100), events.recv()).await {
            match ev {
                EngineEvent::ServerDisconnected { server: Some(_), .. } => disconnected_named = true,
                EngineEvent::Log(line) if line.message.starts_with(&prefix) => logged_disconnect = true,
                _ => {}
            }
        }
    }
    assert!(disconnected_named);
    assert!(logged_disconnect);
    assert_eq!(lab.engine().status().await.unwrap().client_id, 0);
    lab.stop().await;
}

#[tokio::test]
async fn should_leave_a_paused_download_as_it_is_when_added_again() {
    let mut lab = Lab::new();
    lab.start("download.addPaused=true\n").await;
    let large = film_link("large.iso", 5_000_000_000, 0xd1);
    let added = lab.engine().add_download(large.clone(), None).await.unwrap();
    assert_eq!(added.status, DownloadStatus::Paused);

    let again = lab.engine().add_download(large, None).await.unwrap();

    assert_eq!(again.status, DownloadStatus::Paused);
    lab.stop().await;
}

#[tokio::test]
async fn should_ask_for_large_files_only_from_servers_that_take_them() {
    let mut lab = Lab::new();
    lab.start("download.addPaused=true\n").await;
    let large = film_link("large.iso", 5_000_000_000, 0xd1);
    let small = film_link("small.iso", 5000, 0xd2);
    assert_eq!(lab.engine().add_download(large.clone(), None).await.unwrap().status, DownloadStatus::Paused);
    lab.engine().add_download(small.clone(), None).await.unwrap();
    let server = ScriptedServer::start(HIGH_ID, server_messages::TCP_OBFUSCATION).await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_server(Some(Address::new(server.ip(), server.port()))).await.unwrap();
    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerConnected { .. })).await.is_some());

    assert!(lab.engine().resume_download(large.hash).await.is_some());
    assert!(lab.engine().resume_download(small.hash).await.is_some());

    let asked = server.next(server::GETSOURCES_OBFU, WAIT).await.unwrap();
    assert_eq!(PacketReader::of(asked.payload()).hash16().unwrap(), small.hash);
    assert!(server.next(server::GETSOURCES_OBFU, Duration::from_secs(3)).await.is_none());
    lab.stop().await;
}

#[tokio::test]
async fn should_give_the_friend_slot_only_to_a_friend() {
    let mut lab = Lab::new();
    lab.start("").await;
    lab.engine().add_friend(Friend { user_hash: PAL, name: "pal".to_owned(), address: None }).await.unwrap();

    assert!(!lab.engine().set_friend_slot(Some([0x0a; 16])).await);
    lab.stop().await;
}

#[tokio::test]
async fn should_take_the_friend_slot_away_with_the_friend() {
    let mut lab = Lab::new();
    lab.start("").await;
    lab.engine().add_friend(Friend { user_hash: PAL, name: "pal".to_owned(), address: None }).await.unwrap();
    assert!(lab.engine().set_friend_slot(Some(PAL)).await);

    assert!(lab.engine().remove_friend(PAL).await.unwrap());
    assert!(lab.engine().friend_slot().await.is_none());
    assert!(lab.engine().friends().await.is_empty());
    lab.stop().await;
}

#[tokio::test]
async fn should_stop_prioritise_and_preview_a_download() {
    let mut lab = Lab::new();
    lab.start("files.rememberCancelled=false\n").await;
    let film = film_link("film.mkv", 5000, 0xf1);
    lab.engine().add_download(film.clone(), None).await.unwrap();
    assert!(lab.engine().set_download_priority(film.hash, DownloadPriority::High).await);
    assert!(lab.engine().set_download_preview(film.hash, true).await);
    assert!(lab.engine().download_data(film.hash).await.is_none());

    assert!(lab.engine().stop_download(film.hash).await);

    let downloads = lab.engine().downloads().await;
    assert_eq!(downloads.len(), 1);
    assert_eq!(downloads[0].status, DownloadStatus::Stopped);
    assert_eq!(downloads[0].priority, DownloadPriority::High);
    lab.stop().await;
}

#[tokio::test]
async fn should_not_remember_a_cancelled_download_when_the_preferences_say_so() {
    let mut lab = Lab::new();
    lab.start("files.rememberCancelled=false\n").await;
    let film = film_link("film.mkv", 5000, 0xf1);
    lab.engine().add_download(film.clone(), None).await.unwrap();

    assert!(lab.engine().cancel_download(film.hash).await);
    assert!(!lab.engine().file_history().await.cancelled(&film.hash));
    lab.stop().await;
}

#[tokio::test]
async fn should_cancel_a_download_that_cannot_be_remembered() {
    let mut lab = Lab::new();
    lab.start("").await;
    let other = film_link("film.mkv", 5000, 0xf2);
    lab.engine().add_download(other.clone(), None).await.unwrap();
    block_writes(&lab.config().join(cancelled_files::FILE_NAME));

    assert!(lab.engine().cancel_download(other.hash).await);
    assert!(lab.engine().downloads().await.is_empty());
    lab.stop().await;
}

#[tokio::test]
async fn should_add_a_download_paused_in_the_category_chosen() {
    let mut lab = Lab::new();
    lab.start("").await;
    lab.engine().set_categories(vec![films_cat(), music_cat()]).await.unwrap();
    let film = film_link("film.mkv", 5000, 0xa3);

    let info = lab.engine().add_download_in(film, Some(2), Some(true)).await.unwrap();

    assert_eq!(info.category, 2);
    assert_eq!(info.status, DownloadStatus::Paused);
    lab.stop().await;
}

#[tokio::test]
async fn should_add_a_download_running_when_asked_so_although_downloads_are_added_paused() {
    let mut lab = Lab::new();
    lab.start("download.addPaused=true\n").await;
    lab.engine().set_categories(vec![films_cat(), music_cat()]).await.unwrap();
    let film = film_link("film.mkv", 5000, 0xa3);

    let info = lab.engine().add_download_in(film, Some(2), Some(false)).await.unwrap();

    assert_eq!(info.category, 2);
    assert_eq!(info.status, DownloadStatus::Downloading);
    lab.stop().await;
}

#[tokio::test]
async fn should_leave_downloads_of_other_categories_and_running_ones_alone_by_a_scheduler_rule() {
    let mut lab = Lab::new();
    lab.start("").await;
    lab.engine().set_categories(vec![films_cat(), music_cat()]).await.unwrap();
    let film = film_link("film.mkv", 5000, 0xa3);
    let song = film_link("song.mp3", 5000, 0xa4);
    lab.engine().add_download(film.clone(), None).await.unwrap();
    lab.engine().set_download_category(film.hash, 1).await;
    lab.engine().add_download(song.clone(), None).await.unwrap();
    lab.engine().set_download_category(song.hash, 2).await;
    let mut events = lab.engine().subscribe().live;

    let rule = Rule {
        name: "window".to_owned(),
        enabled: true,
        days: all_days(),
        start: Time::MIDNIGHT,
        end: Time::MIDNIGHT,
        upload_kibps: None,
        download_kibps: None,
        max_connections: None,
        max_sources_per_file: None,
        stop_category: Some(3),
        resume_category: Some(2),
    };
    lab.engine().update_schedule(Schedule { rules: vec![rule] }).await.unwrap();

    let downloads = lab.engine().downloads().await;
    assert!(downloads.iter().all(|d| d.status == DownloadStatus::Downloading));
    let logs = drain_logs(&mut events, Duration::from_millis(300)).await;
    assert!(logs.iter().any(|(_, m)| m == "Scheduler: downloads of category 2 resumed"), "logs: {logs:?}");
    assert!(logs.iter().any(|(_, m)| m == "Scheduler: downloads of category 3 stopped"), "logs: {logs:?}");
    lab.stop().await;
}

#[tokio::test]
async fn should_fail_a_hello_to_nobody() {
    let mut lab = Lab::new();
    lab.start("").await;
    let unused = free_port::free_port();

    assert!(lab.engine().hello(SocketAddr::new(Ipv4Addr::LOCALHOST.into(), unused)).await.is_err());
    lab.stop().await;
}

#[tokio::test]
async fn should_fail_a_hello_to_a_client_that_hangs_up() {
    let mut lab = Lab::new();
    lab.start("").await;
    let peer = AsyncTcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let addr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), peer.local_addr().unwrap().port());
    let server = tokio::spawn(async move {
        let (mut socket, _) = peer.accept().await.unwrap();
        read_packet(&mut socket, &|_, _| Some(1 << 20)).await.unwrap().unwrap()
    });

    let res = lab.engine().hello(addr).await;
    let pkt = server.await.unwrap();

    assert_eq!(pkt.opcode(), client::HELLO);
    assert!(res.is_err());
    lab.stop().await;
}

#[tokio::test]
async fn should_fail_a_hello_answered_unreadably() {
    let mut lab = Lab::new();
    lab.start("").await;
    let peer = AsyncTcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let addr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), peer.local_addr().unwrap().port());
    let server = tokio::spawn(async move {
        let (mut socket, _) = peer.accept().await.unwrap();
        let _ = read_packet(&mut socket, &|_, _| Some(1 << 20)).await.unwrap();
        write_packet(&mut socket, &Packet::of(protocol::EDONKEY, client::HELLOANSWER, vec![0u8; 3])).await.unwrap();
    });

    let res = lab.engine().hello(addr).await;
    server.await.unwrap();

    assert!(res.is_err());
    lab.stop().await;
}

#[tokio::test]
async fn should_tell_a_client_without_the_identification() {
    let mut lab = Lab::new();
    lab.start("").await;
    let peer = AsyncTcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let addr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), peer.local_addr().unwrap().port());
    let server = tokio::spawn(async move {
        let (mut socket, _) = peer.accept().await.unwrap();
        let _ = read_packet(&mut socket, &|_, _| Some(1 << 20)).await.unwrap();
        write_packet(&mut socket, &peer_hello().encode(false).unwrap()).await.unwrap();
        write_packet(&mut socket, &peer_hello().encode(true).unwrap()).await.unwrap();
    });

    let info = lab.engine().hello(addr).await.unwrap();
    server.await.unwrap();

    assert_eq!(info.user_hash, PEER_HASH);
    assert_eq!(info.nick.as_deref(), Some("peer"));
    assert_eq!(info.identification, Identification::NotSupported);
    lab.stop().await;
}

#[tokio::test]
async fn should_ignore_other_packets_but_answer_a_hello_of_the_client_afterwards() {
    let mut lab = Lab::new();
    lab.start("").await;
    let peer = AsyncTcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let addr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), peer.local_addr().unwrap().port());
    let server = tokio::spawn(async move {
        let (mut socket, _) = peer.accept().await.unwrap();
        let _ = read_packet(&mut socket, &|_, _| Some(1 << 20)).await.unwrap();
        write_packet(&mut socket, &peer_hello().encode(false).unwrap()).await.unwrap();
        write_packet(&mut socket, &peer_hello().encode(true).unwrap()).await.unwrap();
        // Wait briefly so hello completes, then send other packets and a HELLO.
        write_packet(&mut socket, &Packet::empty(protocol::EMULE, extended::KAD_FWTCPCHECK_ACK)).await.unwrap();
        write_packet(&mut socket, &Packet::empty(protocol::EDONKEY, client::HELLOANSWER)).await.unwrap();
        write_packet(&mut socket, &peer_hello().encode(false).unwrap()).await.unwrap();
        read_packet(&mut socket, &|_, _| Some(1 << 20)).await.unwrap().unwrap()
    });

    let info = lab.engine().hello(addr).await.unwrap();
    let reply = server.await.unwrap();

    assert_eq!(info.identification, Identification::NotSupported);
    assert_eq!(reply.opcode(), client::HELLOANSWER);
    lab.stop().await;
}

#[tokio::test]
async fn should_learn_the_server_of_a_client_when_the_preferences_ask_for_it() {
    let mut lab = Lab::new();
    lab.start("server.addFromClients=true\n").await;
    let mut events = lab.engine().subscribe().live;

    let ours = Ours { nick: "peer", udp_port: 0, kad_port: 0, misc_options1: 0, misc_options2: 0 };
    let hello = Hello::of([0x23; 16], 0, 4999, &ours, ipv4::of(198, 51, 100, 3), 65_534);
    let mut stream = tokio::net::TcpStream::connect((Ipv4Addr::LOCALHOST, lab.engine().listen_port())).await.unwrap();
    write_packet(&mut stream, &hello.encode(false).unwrap()).await.unwrap();

    let mut saw_count = false;
    let mut saw_log = false;
    let deadline = tokio::time::Instant::now() + WAIT;
    while tokio::time::Instant::now() < deadline && !(saw_count && saw_log) {
        if let Ok(Ok(ev)) = tokio::time::timeout(Duration::from_millis(100), events.recv()).await {
            match ev {
                EngineEvent::ServersChanged { count: 1 } => saw_count = true,
                EngineEvent::Log(line)
                    if line.level == LogLevel::Info && line.message == "1 new server from a client" =>
                {
                    saw_log = true;
                }
                _ => {}
            }
        }
    }
    assert!(saw_count);
    assert!(saw_log);
    drop(stream);
    lab.stop().await;
}

#[tokio::test]
async fn should_tell_in_our_hello_the_new_id_from_the_server() {
    let mut lab = Lab::new();
    let server = ScriptedServer::start(HIGH_ID, 0).await;
    lab.start("").await;
    let mut events = lab.engine().subscribe().live;
    lab.engine().connect_server(Some(Address::new(server.ip(), server.port()))).await.unwrap();
    assert!(next_event(&mut events, |e| matches!(e, EngineEvent::ServerConnected { .. })).await.is_some());

    server.send(&[IdChange { client_id: OTHER_HIGH_ID, server_flags: 0, reported_ip: 0 }.encode()]).await;
    let deadline = Instant::now() + WAIT;
    while lab.engine().status().await.unwrap().client_id != OTHER_HIGH_ID {
        assert!(Instant::now() < deadline, "new id not taken");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }

    let peer = AsyncTcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let addr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), peer.local_addr().unwrap().port());
    let server_task = tokio::spawn(async move {
        let (mut socket, _) = peer.accept().await.unwrap();
        let pkt = read_packet(&mut socket, &|_, _| Some(1 << 20)).await.unwrap().unwrap();
        drop(socket);
        pkt
    });
    let _ = lab.engine().hello(addr).await;
    let pkt = server_task.await.unwrap();

    assert_eq!(Hello::decode(&pkt).unwrap().client_id, OTHER_HIGH_ID);
    lab.stop().await;
}

#[tokio::test]
async fn should_give_kad_its_port_even_when_the_server_socket_holds_it() {
    let mut lab = Lab::new();
    lab.start("").await;
    let target_udp = free_port::free_udp_port();

    lab.engine()
        .update_preferences(&BTreeMap::from([
            ("net.udpPort".to_owned(), target_udp.to_string()),
            ("net.kad.enabled".to_owned(), "true".to_owned()),
        ]))
        .unwrap();

    let kad = lab.engine().kad().await.expect("Kad started");
    assert_eq!(kad.udp_port, target_udp);
    lab.stop().await;
}

#[tokio::test]
async fn should_connect_at_the_start_of_a_run_for_the_user_when_the_preferences_say_so() {
    let Some(lan) = ScriptedServer::lan_address() else {
        return;
    };
    let mut lab = Lab::new();
    let server = ScriptedServer::start_at(lan, HIGH_ID, 0).await;
    fs::create_dir_all(lab.config()).unwrap();
    let listed = Server {
        ip: server.ip(),
        port: server.port(),
        tags: vec![Tag::Int { name: TagName::id(server_tag::UDPFLAGS), value: 0 }],
    };
    fs::write(lab.config().join(server_list::FILE_NAME), server_met::write(&[listed]).unwrap()).unwrap();

    let mut switches = Switches::QUIET;
    switches.automatic_connections = true;
    lab.start_with("net.autoConnect=true\nsecurity.filterLan=false\n", switches).await;
    let mut sub = lab.engine().subscribe();

    let already = sub.missed.iter().any(|e| match e {
        EngineEvent::ServerConnected { server: s, .. } => s.address.ends_with(&format!(":{}", server.port())),
        _ => false,
    });
    if !already {
        let port_suffix = format!(":{}", server.port());
        let connected = next_event(
            &mut sub.live,
            |e| matches!(e, EngineEvent::ServerConnected { server: s, .. } if s.address.ends_with(&port_suffix)),
        )
        .await;
        assert!(connected.is_some());
    }
    lab.stop().await;
}

#[tokio::test]
async fn should_stay_disconnected_in_a_run_for_the_user_when_the_preferences_say_so() {
    let Some(lan) = ScriptedServer::lan_address() else {
        return;
    };
    let mut lab = Lab::new();
    let server = ScriptedServer::start_at(lan, HIGH_ID, 0).await;
    fs::create_dir_all(lab.config()).unwrap();
    let listed = Server {
        ip: server.ip(),
        port: server.port(),
        tags: vec![Tag::Int { name: TagName::id(server_tag::UDPFLAGS), value: 0 }],
    };
    fs::write(lab.config().join(server_list::FILE_NAME), server_met::write(&[listed]).unwrap()).unwrap();

    let mut switches = Switches::QUIET;
    switches.automatic_connections = true;
    lab.start_with("net.autoConnect=false\nsecurity.filterLan=false\n", switches).await;

    tokio::time::sleep(Duration::from_secs(2)).await;

    assert_eq!(server.connections(), 0);
    lab.stop().await;
}

struct Lab {
    dir: tempfile::TempDir,
    tcp_port: u16,
    udp_port: u16,
    engine: Option<Engine>,
}

impl Lab {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            tcp_port: free_port::free_port(),
            udp_port: free_port::free_udp_port(),
            engine: None,
        }
    }

    fn config(&self) -> std::path::PathBuf {
        self.dir.path().join("njmule").join("config")
    }

    fn engine(&self) -> &Engine {
        self.engine.as_ref().unwrap()
    }

    async fn start(&mut self, extra: &str) {
        self.start_with(extra, Switches::QUIET).await;
    }

    async fn start_with(&mut self, extra: &str, switches: Switches) {
        let dirs = AppDirectories::under_root(&self.dir.path().join("njmule")).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let mut content = format!("net.tcpPort={}\nnet.udpPort={}\n", self.tcp_port, self.udp_port);
        if !extra.contains("net.kad.enabled=") {
            content.push_str("net.kad.enabled=false\n");
        }
        content.push_str(extra);
        fs::write(dirs.config.join("njmule.properties"), content).unwrap();
        self.engine = Some(Engine::start_with(dirs, switches).await.unwrap());
    }

    fn backlog_logs(&self) -> Vec<(LogLevel, String)> {
        self.engine()
            .subscribe()
            .missed
            .into_iter()
            .filter_map(|e| match e {
                EngineEvent::Log(line) => Some((line.level, line.message)),
                _ => None,
            })
            .collect()
    }

    async fn stop_keep_dir(&mut self) {
        if let Some(engine) = self.engine.take() {
            engine.stop().await;
        }
    }

    async fn stop(mut self) {
        self.stop_keep_dir().await;
    }
}

fn block_writes(file: &Path) {
    let tmp = file.with_file_name(format!("{}.tmp", file.file_name().unwrap().to_string_lossy()));
    fs::create_dir_all(&tmp).unwrap();
    fs::write(tmp.join("keep"), "x").unwrap();
}

fn callback_packet(ip: u32) -> Packet {
    let mut w = PacketWriter::new();
    w.u32(ipv4::to_ed2k(ip)).u16(4662);
    Packet::of(protocol::EDONKEY, server::CALLBACKREQUESTED, w.into_byte_array())
}

fn all_days() -> BTreeSet<DayOfWeek> {
    [
        DayOfWeek::Monday,
        DayOfWeek::Tuesday,
        DayOfWeek::Wednesday,
        DayOfWeek::Thursday,
        DayOfWeek::Friday,
        DayOfWeek::Saturday,
        DayOfWeek::Sunday,
    ]
    .into_iter()
    .collect()
}

async fn next_event(
    events: &mut broadcast::Receiver<EngineEvent>,
    predicate: impl Fn(&EngineEvent) -> bool,
) -> Option<EngineEvent> {
    let deadline = tokio::time::Instant::now() + WAIT;
    while tokio::time::Instant::now() < deadline {
        match tokio::time::timeout(Duration::from_millis(100), events.recv()).await {
            Ok(Ok(event)) if predicate(&event) => return Some(event),
            Ok(Ok(_) | Err(broadcast::error::RecvError::Lagged(_))) | Err(_) => {}
            Ok(Err(broadcast::error::RecvError::Closed)) => return None,
        }
    }
    None
}

async fn await_log(events: &mut broadcast::Receiver<EngineEvent>, level: LogLevel, message: &str) -> bool {
    next_event(events, |e| matches!(e, EngineEvent::Log(l) if l.level == level && l.message == message))
        .await
        .is_some()
}

async fn drain_logs(events: &mut broadcast::Receiver<EngineEvent>, for_time: Duration) -> Vec<(LogLevel, String)> {
    let mut out = Vec::new();
    let deadline = tokio::time::Instant::now() + for_time;
    while tokio::time::Instant::now() < deadline {
        let left = deadline.saturating_duration_since(tokio::time::Instant::now());
        match tokio::time::timeout(left.min(Duration::from_millis(50)), events.recv()).await {
            Ok(Ok(EngineEvent::Log(l))) => out.push((l.level, l.message)),
            Ok(Ok(_)) => {}
            _ => break,
        }
    }
    out
}

async fn await_port_test(
    engine: &Engine,
    mut current: njmule_engine::api::port_test::PortTest,
) -> njmule_engine::api::port_test::PortTest {
    let deadline = Instant::now() + Duration::from_secs(20);
    while !current.done && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(50)).await;
        if let Some(updated) = engine.port_test().await {
            current = updated;
        }
    }
    current
}
