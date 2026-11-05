// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's SearchManagerTest for server and global searches, under their names; those of
//! Kad, of the spam filter and of media details wait for their ports.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::panic, clippy::arithmetic_side_effects)]

use njmule_engine::api::search_result::SearchResult;
use njmule_engine::persist::format::server_met::Server;
use njmule_engine::protocol::ed2k::file_entry::FileEntry;
use njmule_engine::protocol::ed2k::server_messages::{self, IdChange};
use njmule_engine::protocol::packet::Packet;
use njmule_engine::protocol::packet::opcodes::{server, server_udp};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};
use njmule_engine::search::search_manager::{
    Effect, GLOBAL_RESULTS, MAX_MORE_REQUESTS, Scope, SearchManager, StartError, TimerKind,
};
use njmule_engine::server::server_list::{Address, ServerList};

fn ip(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from_be_bytes([a, b, c, d])
}

fn connected_id() -> Option<IdChange> {
    Some(IdChange { client_id: ip(1, 2, 3, 4), server_flags: server_messages::TCP_LARGEFILES, reported_ip: 0 })
}

fn file(hash: u8, name: &str, size: u64, sources: u64) -> FileEntry {
    FileEntry {
        hash: [hash; 16],
        client_id: 0,
        port: 0,
        tags: vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: size },
            Tag::Int { name: TagName::id(file_tag::SOURCES), value: sources },
            Tag::Int { name: TagName::id(file_tag::COMPLETE_SOURCES), value: sources },
        ],
    }
}

fn with(mut entry: FileEntry, tag: Tag) -> FileEntry {
    entry.tags.push(tag);
    entry
}

struct Searching {
    manager: SearchManager,
    servers: ServerList,
    connected: Option<Address>,
    sent: Vec<Packet>,
    effects: Vec<Effect>,
}

impl Searching {
    /// The connected server, and `others` known besides.
    fn new(others: &[Server]) -> Self {
        let mut servers = ServerList::default();
        let connected = Server { ip: ip(10, 0, 0, 1), port: 4661, tags: Vec::new() };
        servers.add(connected.clone());
        for other in others {
            servers.add(other.clone());
        }
        let connected = Some(Address::new(connected.ip, connected.port));
        Self { manager: SearchManager::default(), servers, connected, sent: Vec::new(), effects: Vec::new() }
    }

    fn start(&mut self, scope: Scope) -> u64 {
        self.try_start(scope, connected_id()).unwrap()
    }

    fn try_start(&mut self, scope: Scope, id: Option<IdChange>) -> Result<u64, StartError> {
        let sent = &mut self.sent;
        self.manager.start(
            "ubuntu",
            scope,
            id,
            |packet| {
                sent.push(packet);
                true
            },
            &mut self.effects,
        )
    }

    fn answer(&mut self, files: &[FileEntry], more: bool) {
        self.manager.server_results(files, more, &self.servers, self.connected.clone(), &mut self.effects);
    }

    fn more(&mut self, search: u64) -> bool {
        let sent = &mut self.sent;
        self.manager.more(
            search,
            |packet| {
                sent.push(packet);
                true
            },
            &mut self.effects,
        )
    }

    // Fires the last timer of this kind the manager asked for.
    fn fire(&mut self, kind: TimerKind) {
        let timer = self
            .effects
            .iter()
            .rev()
            .find_map(|effect| match effect {
                Effect::After(_, timer) if timer.kind == kind => Some(*timer),
                _ => None,
            })
            .unwrap_or_else(|| panic!("no {kind:?} timer"));
        self.manager.timer(timer, &self.servers, self.connected.clone(), &mut self.effects);
    }

    fn ended(&self, search: u64) -> Option<usize> {
        self.effects.iter().find_map(|effect| match effect {
            Effect::Ended { search: ended, results } if *ended == search => Some(*results),
            _ => None,
        })
    }

    // The latest version of each result told.
    fn results(&self) -> Vec<SearchResult> {
        let mut latest: Vec<SearchResult> = Vec::new();
        for effect in &self.effects {
            if let Effect::Results { changed, .. } = effect {
                for result in changed {
                    match latest.iter_mut().find(|known| known.hash == result.hash) {
                        Some(known) => *known = result.clone(),
                        None => latest.push(result.clone()),
                    }
                }
            }
        }
        latest
    }

    fn datagrams(&self) -> Vec<&Packet> {
        self.effects
            .iter()
            .filter_map(|effect| match effect {
                Effect::Datagram(_, packet) => Some(packet),
                _ => None,
            })
            .collect()
    }
}

fn udp_server(last: u8) -> Server {
    Server { ip: ip(10, 0, 0, last), port: 4661, tags: Vec::new() }
}

#[test]
fn should_refuse_a_server_search_without_a_server() {
    let mut searching = Searching::new(&[]);

    assert!(matches!(searching.try_start(Scope::Server, None), Err(StartError::Unavailable(_))));
}

#[test]
fn should_refuse_a_kad_search_while_kad_is_not_running() {
    let mut searching = Searching::new(&[]);

    assert!(matches!(searching.try_start(Scope::Kad, connected_id()), Err(StartError::Unavailable(_))));
}

#[test]
fn should_send_the_search_to_the_connected_server() {
    let mut searching = Searching::new(&[]);

    searching.start(Scope::Server);

    assert_eq!(searching.sent[0].opcode(), server::SEARCHREQUEST);
}

#[test]
fn should_ignore_answers_no_search_asked_for() {
    let mut searching = Searching::new(&[]);

    searching.answer(&[file(1, "ubuntu.iso", 1000, 1)], false);

    assert!(searching.effects.is_empty());
}

#[test]
fn should_take_more_results_from_the_server_one_request_at_a_time_until_its_last_answer() {
    let mut searching = Searching::new(&[]);
    let search = searching.start(Scope::Server);
    searching.answer(&[file(1, "ubuntu.iso", 1000, 1)], true);
    assert!(searching.more(search));

    searching.answer(&[file(2, "ubuntu 2.iso", 2000, 1)], false);

    assert_eq!(searching.sent.last().unwrap().opcode(), server::QUERY_MORE_RESULT);
    assert_eq!(searching.ended(search), Some(2));
}

#[test]
fn should_hold_more_results_once_the_server_says_it_has_some() {
    let mut searching = Searching::new(&[]);
    let search = searching.start(Scope::Server);

    searching.answer(&[file(1, "ubuntu.iso", 1000, 1)], true);

    assert!(searching.manager.has_more(search));
    assert_eq!(searching.ended(search), None);
}

#[test]
fn should_hold_no_more_results_while_their_answer_is_awaited() {
    let mut searching = Searching::new(&[]);
    let search = searching.start(Scope::Server);
    searching.answer(&[file(1, "ubuntu.iso", 1000, 1)], true);

    searching.more(search);

    assert!(!searching.manager.has_more(search));
}

#[test]
fn should_hold_no_more_results_of_an_unknown_search() {
    let searching = Searching::new(&[]);

    assert!(!searching.manager.has_more(42));
}

#[test]
fn should_hold_no_more_results_once_asked_for_the_most_times() {
    let mut searching = Searching::new(&[]);
    let search = searching.start(Scope::Server);
    searching.answer(&[file(1, "ubuntu.iso", 1000, 1)], true);
    for _ in 0..MAX_MORE_REQUESTS {
        assert!(searching.more(search));
        searching.answer(&[], true);
    }

    let asked = searching.more(search);

    assert!(!asked);
    assert!(!searching.manager.has_more(search));
}

#[test]
fn should_hold_no_more_results_when_the_server_says_it_has_none() {
    let mut searching = Searching::new(&[]);
    let search = searching.start(Scope::Server);

    searching.answer(&[file(1, "ubuntu.iso", 1000, 1)], false);

    assert!(!searching.manager.has_more(search));
    assert_eq!(searching.ended(search), Some(1));
}

#[test]
fn should_end_a_server_search_when_the_server_is_lost() {
    let mut searching = Searching::new(&[]);
    let search = searching.start(Scope::Server);

    searching.manager.server_lost(&mut searching.effects);

    assert_eq!(searching.ended(search), Some(0));
}

#[test]
fn should_ignore_the_late_answer_of_a_lost_server() {
    let mut searching = Searching::new(&[]);
    searching.start(Scope::Server);
    searching.manager.server_lost(&mut searching.effects);

    searching.answer(&[file(1, "ubuntu.iso", 1000, 1)], false);

    assert!(searching.results().is_empty());
}

#[test]
fn should_end_a_new_search_s_predecessor_and_keep_answers_in_their_order() {
    let mut searching = Searching::new(&[]);
    let first = searching.start(Scope::Server);
    let second = searching.start(Scope::Server);

    // The answer to the first comes late: it lands nowhere, and the next answer is the second's.
    searching.answer(&[file(1, "first.iso", 1000, 1)], false);
    searching.answer(&[file(2, "second.iso", 1000, 1)], false);

    assert_eq!(searching.ended(first), Some(0));
    assert_eq!(searching.results().iter().map(|r| r.name.as_str()).collect::<Vec<_>>(), ["second.iso"]);
    assert_eq!(searching.ended(second), Some(1));
}

#[test]
fn should_end_a_global_search_no_server_can_be_asked_over_udp_once_the_server_answered() {
    let mut searching = Searching::new(&[Server { ip: ip(10, 0, 0, 2), port: 65_533, tags: Vec::new() }]);
    let search = searching.start(Scope::Global);

    searching.answer(&[file(1, "ubuntu.iso", 1000, 1)], false);

    assert!(searching.datagrams().is_empty());
    assert_eq!(searching.ended(search), Some(1));
}

#[test]
fn should_ask_the_other_servers_over_udp_and_end_after_their_time_for_answers() {
    let mut searching = Searching::new(&[udp_server(2), udp_server(3)]);
    let search = searching.start(Scope::Global);
    searching.answer(&[file(1, "ubuntu.iso", 1000, 50)], false);
    assert_eq!(searching.datagrams().len(), 1);
    searching.fire(TimerKind::Query);
    searching
        .manager
        .datagram_results(ip(10, 0, 0, 2), &[file(1, "ubuntu.iso", 1000, 7)], &mut searching.effects);
    // Not asked: its answer counts for nothing.
    searching
        .manager
        .datagram_results(ip(10, 0, 0, 9), &[file(1, "ubuntu.iso", 1000, 100)], &mut searching.effects);
    searching.fire(TimerKind::Query);

    searching.fire(TimerKind::End);

    assert!(searching.datagrams().iter().all(|packet| packet.opcode() == server_udp::GLOBSEARCHREQ));
    assert_eq!(searching.datagrams().len(), 2);
    assert_eq!(searching.results()[0].sources, 57);
    assert_eq!(searching.ended(search), Some(1));
}

#[test]
fn should_ask_no_other_server_once_the_connected_one_gave_more_than_a_hundred_results() {
    let mut searching = Searching::new(&[udp_server(2)]);
    searching.start(Scope::Global);
    let many: Vec<FileEntry> =
        (0..=GLOBAL_RESULTS).map(|i| file(i as u8, &format!("ubuntu {i}.iso"), 1000, 1)).collect();

    searching.answer(&many, false);

    assert!(searching.datagrams().is_empty());
}

#[test]
fn should_ask_the_other_servers_while_the_connected_one_gave_a_hundred_results() {
    let mut searching = Searching::new(&[udp_server(2)]);
    searching.start(Scope::Global);
    let hundred: Vec<FileEntry> =
        (0..GLOBAL_RESULTS).map(|i| file(i as u8, &format!("ubuntu {i}.iso"), 1000, 1)).collect();

    searching.answer(&hundred, false);

    assert_eq!(searching.datagrams().len(), 1);
}

fn aich_tag(root: u8) -> Tag {
    Tag::String { name: TagName::id(file_tag::AICH_HASH), value: data_encoding::BASE32_NOPAD.encode(&[root; 20]) }
}

#[test]
fn should_keep_no_aich_root_of_a_file_given_two_even_when_the_first_comes_again() {
    let mut searching = Searching::new(&[]);
    searching.start(Scope::Server);
    let a = with(file(1, "ubuntu.iso", 1000, 1), aich_tag(1));
    let b = with(file(1, "ubuntu.iso", 1000, 1), aich_tag(2));

    searching.answer(&[a.clone(), b, a], false);

    assert_eq!(searching.results()[0].aich_hash, None);
}

#[test]
fn should_show_the_name_most_sources_give_and_keep_the_others() {
    let mut searching = Searching::new(&[]);
    searching.start(Scope::Server);

    searching.answer(&[file(1, "ubuntu.iso", 1000, 1), file(1, "Ubuntu 24.04.iso", 1000, 5)], false);

    let result = &searching.results()[0];
    assert_eq!(result.name, "Ubuntu 24.04.iso");
    assert_eq!(result.names.len(), 2);
}

#[test]
fn should_take_the_type_from_the_file_name_rather_than_from_the_publisher() {
    let mut searching = Searching::new(&[]);
    searching.start(Scope::Server);
    let lying = with(
        file(1, "setup.exe", 1000, 1),
        Tag::String { name: TagName::id(file_tag::FILETYPE), value: "Video".to_owned() },
    );

    searching.answer(&[lying], false);

    assert_eq!(searching.results()[0].file_type.as_deref(), Some("Pro"));
}

#[test]
fn should_keep_the_reported_type_of_a_name_that_does_not_tell_it() {
    let mut searching = Searching::new(&[]);
    searching.start(Scope::Server);
    let typed = with(
        file(1, "readme", 1000, 1),
        Tag::String { name: TagName::id(file_tag::FILETYPE), value: "Doc".to_owned() },
    );

    searching.answer(&[typed], false);

    assert_eq!(searching.results()[0].file_type.as_deref(), Some("Doc"));
}

#[test]
fn should_drop_a_reported_type_that_is_no_file_type() {
    let mut searching = Searching::new(&[]);
    searching.start(Scope::Server);
    let typed = with(
        file(1, "readme", 1000, 1),
        Tag::String { name: TagName::id(file_tag::FILETYPE), value: "Nonsense".to_owned() },
    );

    searching.answer(&[typed], false);

    assert_eq!(searching.results()[0].file_type, None);
}

#[test]
fn should_keep_control_and_direction_characters_out_of_result_names() {
    let mut searching = Searching::new(&[]);
    searching.start(Scope::Server);

    searching.answer(&[file(1, " evil\u{202E}gpj.exe\n", 1000, 1)], false);

    assert_eq!(searching.results()[0].name, "evilgpj.exe");
}

#[test]
fn should_leave_out_results_without_a_usable_name_or_size() {
    let mut searching = Searching::new(&[]);
    searching.start(Scope::Server);

    searching.answer(&[file(1, "empty.bin", 0, 1), file(2, " ", 100, 1), file(3, "ubuntu.iso", 100, 1)], false);

    assert_eq!(searching.results().iter().map(|r| r.name.as_str()).collect::<Vec<_>>(), ["ubuntu.iso"]);
}
