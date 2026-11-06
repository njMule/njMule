// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's SearchManagerTest, under their names: server and global searches, and Kad searches
//! on a Kad the test plays.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::panic, clippy::arithmetic_side_effects)]

use std::collections::HashMap;
use std::net::Ipv4Addr;

use njmule_engine::api::search_result::{Media, Origin, SearchResult};
use njmule_engine::persist::format::server_met::Server;
use njmule_engine::protocol::ed2k::file_entry::FileEntry;
use njmule_engine::protocol::ed2k::server_datagrams;
use njmule_engine::protocol::ed2k::server_messages::{self, IdChange};
use njmule_engine::protocol::packet::Packet;
use njmule_engine::protocol::packet::opcodes::{server, server_udp};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag, server_tag};
use njmule_engine::search::search_expr::SearchExpr;
use njmule_engine::search::search_manager::{
    self, Effect, GLOBAL_RESULTS, KadSearches, MAX_MORE_REQUESTS, MAX_RESULTS, Scope, SearchManager, SpamRater,
    StartError, TimerKind,
};
use njmule_engine::search::search_parser;
use njmule_engine::server::server_list::{Address, Priority, ServerList};
use njmule_engine::util::ipv4;

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

/// A Kad the test plays: it takes every keyword but `refused`, and remembers what it was asked.
#[derive(Default)]
struct FakeKad {
    refused: Option<String>,
    keywords: Vec<String>,
    asked: Option<SearchExpr>,
    searches: HashMap<u64, String>,
}

impl KadSearches for FakeKad {
    fn search(&mut self, search: u64, keyword: &str, expression: &SearchExpr) -> bool {
        self.keywords.push(keyword.to_owned());
        self.asked = Some(expression.clone());
        if self.refused.as_deref() == Some(keyword) {
            return false;
        }
        self.searches.insert(search, keyword.to_owned());
        true
    }
}

/// Takes for spam the names whose bare name has `word`, and writes down the servers counted.
#[derive(Default)]
struct FakeRater {
    word: Option<String>,
    answered: Vec<String>,
}

impl SpamRater for FakeRater {
    fn rate(&self, result: &SearchResult) -> u32 {
        match &self.word {
            Some(word) if result.origin.bare_name.contains(word.as_str()) => 70,
            _ => 0,
        }
    }

    fn answered(&mut self, server_ip: u32, results: u32, spam: u32) {
        self.answered.push(format!("answered {} {results} {spam}", Ipv4Addr::from(server_ip)));
    }
}

struct Searching {
    manager: SearchManager,
    servers: ServerList,
    connected: Option<Address>,
    sent: Vec<Packet>,
    effects: Vec<Effect>,
    kad: Option<FakeKad>,
    rater: FakeRater,
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
        Self {
            manager: SearchManager::default(),
            servers,
            connected,
            sent: Vec::new(),
            effects: Vec::new(),
            kad: None,
            rater: FakeRater::default(),
        }
    }

    /// No server, and a running Kad that cannot look `refused` up.
    fn on_kad(refused: &str) -> Self {
        let mut searching = Self::new(&[]);
        searching.kad = Some(FakeKad { refused: Some(refused.to_owned()), ..FakeKad::default() });
        searching
    }

    fn start(&mut self, scope: Scope) -> u64 {
        self.try_start(scope, connected_id()).unwrap()
    }

    fn try_start(&mut self, scope: Scope, id: Option<IdChange>) -> Result<u64, StartError> {
        self.try_start_for("ubuntu", scope, id)
    }

    fn start_for(&mut self, expression: &str, scope: Scope) -> u64 {
        self.try_start_for(expression, scope, connected_id()).unwrap()
    }

    fn try_start_for(&mut self, expression: &str, scope: Scope, id: Option<IdChange>) -> Result<u64, StartError> {
        let sent = &mut self.sent;
        let kad = self.kad.as_mut().map(|kad| kad as &mut dyn KadSearches);
        self.manager.start(
            expression,
            scope,
            id,
            kad,
            |packet| {
                sent.push(packet);
                true
            },
            &mut self.effects,
        )
    }

    fn answer(&mut self, files: &[FileEntry], more: bool) {
        self.manager.server_results(
            files,
            more,
            &self.servers,
            self.connected.clone(),
            &mut self.rater,
            &mut self.effects,
        );
    }

    fn udp_answer(&mut self, from: u32, files: &[FileEntry]) {
        self.manager.datagram_results(from, files, &mut self.rater, &mut self.effects);
    }

    fn kad_answer(&mut self, search: u64, results: Vec<SearchResult>) {
        self.manager.kad_results(search, results, &mut self.rater, &mut self.effects);
    }

    // The first result of each report, as `name sources/complete`.
    fn reports(&self) -> Vec<String> {
        self.effects
            .iter()
            .filter_map(|effect| match effect {
                Effect::Results { changed, .. } => changed.first().map(|first| {
                    let complete = first.complete_sources.map_or_else(|| "-1".to_owned(), |n| n.to_string());
                    format!("{} {}/{complete}", first.name, first.sources)
                }),
                _ => None,
            })
            .collect()
    }

    // Every result reported, in order.
    fn reported(&self) -> Vec<SearchResult> {
        self.effects
            .iter()
            .filter_map(|effect| match effect {
                Effect::Results { changed, .. } => Some(changed.clone()),
                _ => None,
            })
            .flatten()
            .collect()
    }

    fn last_reported(&self, hash: [u8; 16]) -> SearchResult {
        self.reported().into_iter().rev().find(|result| result.hash == hash).unwrap()
    }

    fn kad(&self) -> &FakeKad {
        self.kad.as_ref().unwrap()
    }

    // The keywords Kad stopped looking up, for the searches that asked for them.
    fn stopped(&self) -> Vec<String> {
        self.effects
            .iter()
            .filter_map(|effect| match effect {
                Effect::StopKad(search) => self.kad().searches.get(search).cloned(),
                _ => None,
            })
            .collect()
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
    searching.udp_answer(ip(10, 0, 0, 2), &[file(1, "ubuntu.iso", 1000, 7)]);
    // Not asked: its answer counts for nothing.
    searching.udp_answer(ip(10, 0, 0, 9), &[file(1, "ubuntu.iso", 1000, 100)]);
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

fn kad_hash(seed: u32) -> [u8; 16] {
    let mut hash = [0; 16];
    hash[12..].copy_from_slice(&seed.to_be_bytes());
    hash
}

fn result(seed: u32, name: &str, size: u64, sources: u32, complete: Option<u32>) -> SearchResult {
    SearchResult::new(kad_hash(seed), name, size, sources, complete, None)
}

fn first_kad_answer() -> Vec<SearchResult> {
    vec![
        result(1, "distro.iso", 1000, 5, Some(1)),
        result(2, " ", 1000, 1, Some(1)),
        result(3, "empty.iso", 0, 1, Some(1)),
    ]
}

fn second_kad_answer() -> Vec<SearchResult> {
    vec![
        SearchResult { file_type: Some("Iso".to_owned()), ..result(1, "distro.iso", 1000, 3, Some(4)) },
        result(1, "distro.iso", 2000, 9, Some(9)),
    ]
}

fn more_files_than_the_limit() -> Vec<SearchResult> {
    (0..=MAX_RESULTS)
        .map(|i| result(1000 + u32::try_from(i).unwrap(), &format!("file{i}.bin"), 10, 1, Some(0)))
        .collect()
}

fn distro_with_aich(root: u8) -> SearchResult {
    SearchResult { aich_hash: Some([root; 20]), ..result(1, "distro.iso", 1000, 5, None) }
}

fn song(media: Media) -> SearchResult {
    SearchResult { media, ..SearchResult::new(kad_hash(9), "opening song.mp3", 4_000_000, 1, Some(0), Some("Audio")) }
}

// An entry naming a HighID source at `source`.
fn file_from(hash: u8, name: &str, source: u32) -> FileEntry {
    FileEntry {
        hash: [hash; 16],
        client_id: ipv4::to_ed2k(source),
        port: 4662,
        tags: vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: 1000 },
            Tag::Int { name: TagName::id(file_tag::SOURCES), value: 3 },
        ],
    }
}

// One server with 64-bit sizes, asked first, one without them, and one whose UDP port would be beyond 65535.
fn servers_with_large_files_without_them_and_without_a_udp_port() -> Searching {
    let udp_flags = u64::from(server_datagrams::UDP_LARGEFILES | server_datagrams::UDP_EXT_GETFILES);
    let wide = Server {
        ip: ip(192, 0, 2, 1),
        port: 4661,
        tags: vec![Tag::Int { name: TagName::id(server_tag::UDPFLAGS), value: udp_flags }],
    };
    let narrow = Server { ip: ip(192, 0, 2, 2), port: 4661, tags: Vec::new() };
    let no_udp_port = Server { ip: ip(192, 0, 2, 3), port: 65_533, tags: Vec::new() };
    let mut searching = Searching::new(&[wide.clone(), narrow, no_udp_port]);
    searching.servers.set_priority(&Address::new(wide.ip, wide.port), Priority::High);
    searching
}

#[test]
fn should_refuse_a_kad_search_for_a_keyword_kad_cannot_look_up() {
    let mut searching = Searching::on_kad("never");

    let started = searching.try_start_for("never", Scope::Kad, None);

    assert!(matches!(started, Err(StartError::Unavailable(_))), "{started:?}");
}

#[test]
fn should_look_up_the_longest_word_on_kad() {
    let mut searching = Searching::on_kad("never");

    let started = searching.try_start_for("rare linux distro", Scope::Kad, None);

    assert!(started.is_ok());
    assert_eq!(searching.kad().keywords.last().map(String::as_str), Some("distro"), "the longest word");
}

#[test]
fn should_report_the_files_of_a_kad_answer() {
    let mut searching = Searching::on_kad("never");
    let search = searching.start_for("rare linux distro", Scope::Kad);

    searching.kad_answer(search, first_kad_answer());

    assert_eq!(searching.reports(), ["distro.iso 5/1"]);
}

#[test]
fn should_rate_a_kad_result_by_its_name_without_the_words_searched_for() {
    let mut searching = Searching::on_kad("never");
    searching.rater.word = Some("pirate".to_owned());
    let search = searching.start_for("rare linux distro", Scope::Kad);

    searching.kad_answer(search, vec![result(1, "Rare-Linux_Distro (pirate).iso", 1000, 5, Some(0))]);

    let reported = searching.last_reported(kad_hash(1));
    assert_eq!(reported.origin, Origin::new([], true, [], "pirate iso"));
    assert_eq!(reported.spam_rating, 70);
}

#[test]
fn should_rate_the_udp_answers_of_a_server_and_count_its_spam() {
    let asked = ip(192, 0, 2, 1);
    let mut searching = Searching::new(&[Server { ip: asked, port: 4661, tags: Vec::new() }]);
    searching.rater.word = Some("pirate".to_owned());
    searching.start_for("linux", Scope::Global);
    searching.answer(&[], false);
    assert_eq!(searching.datagrams().len(), 1, "the query went over UDP");

    searching
        .udp_answer(asked, &[file_from(5, "Linux (pirate).iso", ip(203, 0, 113, 5)), file(6, "linux.iso", 1000, 2)]);

    assert_eq!(searching.rater.answered, ["answered 192.0.2.1 2 1"]);
    let reported = searching.last_reported([5; 16]);
    assert_eq!(reported.origin, Origin::new([asked], false, [ip(203, 0, 113, 5)], "pirate iso"));
    assert_eq!(reported.spam_rating, 70);
}

#[test]
fn should_give_the_link_of_a_result_the_aich_root_kad_told() {
    let mut searching = Searching::on_kad("never");
    let search = searching.start_for("rare linux distro", Scope::Kad);

    searching.kad_answer(search, vec![distro_with_aich(0x0A)]);

    assert_eq!(searching.last_reported(kad_hash(1)).link().unwrap().aich_hash, Some([0x0A; 20]));
}

#[test]
fn should_keep_the_largest_counts_kad_nodes_report() {
    let mut searching = Searching::on_kad("never");
    let search = searching.start_for("rare linux distro", Scope::Kad);
    searching.kad_answer(search, first_kad_answer());

    searching.kad_answer(search, second_kad_answer());

    assert_eq!(
        searching.reports().last().map(String::as_str),
        Some("distro.iso 5/4"),
        "Kad nodes report the same sources: the largest count is kept"
    );
}

#[test]
fn should_take_kad_results_up_to_the_limit() {
    let mut searching = Searching::on_kad("never");
    let search = searching.start_for("rare linux distro", Scope::Kad);
    searching.kad_answer(search, first_kad_answer());
    searching.kad_answer(search, second_kad_answer());

    searching.kad_answer(search, more_files_than_the_limit());

    assert!(searching.reports().last().unwrap().starts_with("file0.bin"));
    let held = searching.reported().iter().map(|result| result.hash).collect::<std::collections::HashSet<_>>().len();
    assert_eq!(held, MAX_RESULTS);
}

#[test]
fn should_let_a_kad_search_outlive_the_server_and_take_nothing_more_once_stopped() {
    let mut searching = Searching::on_kad("never");
    let search = searching.start_for("rare linux distro", Scope::Kad);
    searching.kad_answer(search, first_kad_answer());
    searching.kad_answer(search, second_kad_answer());
    searching.kad_answer(search, more_files_than_the_limit());
    searching.manager.server_lost(&mut searching.effects);
    assert_eq!(searching.ended(search), None, "a Kad search outlives the server");
    searching.manager.stop(search, &mut searching.effects);
    searching.manager.stop(search, &mut searching.effects);
    let told = searching.effects.len();

    searching.kad_answer(search, vec![result(5, "late.iso", 10, 1, Some(0))]);

    assert_eq!(searching.ended(search), Some(MAX_RESULTS), "once stopped it takes nothing more");
    assert_eq!(searching.stopped(), ["distro"]);
    assert_eq!(searching.effects.len(), told);
}

#[test]
fn should_fill_the_media_details_a_first_answer_lacked_from_later_ones() {
    let mut searching = Searching::on_kad("never");
    let search = searching.start_for("opening song", Scope::Kad);
    searching.kad_answer(search, vec![song(Media::new("Some Band", "", "", 0, 192, ""))]);

    searching.kad_answer(search, vec![song(Media::new("Other Band", "First Album", "", 205, 128, "mp3"))]);

    assert_eq!(searching.reported().last().unwrap().media, Media::new("Some Band", "First Album", "", 205, 192, "mp3"));
}

#[test]
fn should_ask_kad_for_archives_as_programs_and_keep_only_the_archives() {
    let mut searching = Searching::on_kad("never");
    let search = searching.start_for("linux @type=archive", Scope::Kad);

    searching.kad_answer(
        search,
        vec![
            SearchResult::new(kad_hash(14), "linux setup.exe", 1000, 1, Some(0), Some("Pro")),
            SearchResult::new(kad_hash(15), "linux sources.tar.gz", 1000, 1, Some(0), Some("Pro")),
        ],
    );

    assert_eq!(
        searching.kad().asked,
        Some(SearchExpr::and(
            SearchExpr::keyword("linux"),
            SearchExpr::StringAttr { tag: file_tag::FILETYPE, value: "Pro".to_owned() }
        ))
    );
    let names: Vec<String> = searching.reported().into_iter().map(|result| result.name).collect();
    assert_eq!(names, ["linux sources.tar.gz"]);
}

fn kad_keyword(expression: &str) -> Result<String, String> {
    search_manager::kad_keyword(&search_parser::parse(expression).unwrap())
}

#[test]
fn should_look_up_on_kad_the_longest_word_every_match_has() {
    for (expression, keyword) in [("@type=video linux", "linux"), ("ubuntu -windows", "ubuntu")] {
        assert_eq!(kad_keyword(expression), Ok(keyword.to_owned()), "{expression}");
    }
}

#[test]
fn should_look_up_on_kad_a_word_in_quotes_when_there_are_no_others() {
    assert_eq!(
        kad_keyword("\"free software\" go"),
        Ok("software".to_owned()),
        "words in quotes when there are no others"
    );
}

#[test]
fn should_not_look_up_any_word_on_kad_for_alternatives() {
    let refusal = kad_keyword("debian OR ubuntu").unwrap_err();

    assert!(refusal.contains("OR"), "{refusal}");
}

#[test]
fn should_not_look_up_on_kad_a_word_shorter_than_three_letters() {
    let refusal = kad_keyword("go to").unwrap_err();

    assert!(refusal.contains("three letters"), "{refusal}");
}

#[test]
fn should_hold_no_more_results_of_a_kad_search() {
    let mut searching = Searching::on_kad("never");
    let search = searching.start_for("rare linux distro", Scope::Kad);

    assert!(!searching.manager.has_more(search));
}

#[test]
fn should_end_a_global_search_with_the_server_answer_without_the_udp_socket() {
    let mut searching = servers_with_large_files_without_them_and_without_a_udp_port();
    searching.manager = SearchManager::default().without_udp();
    let search = searching.start_for("alone", Scope::Global);

    searching.answer(&[], false);

    assert!(searching.datagrams().is_empty());
    assert_eq!(searching.ended(search), Some(0));
}

#[test]
fn should_end_a_global_search_no_server_can_be_asked_over_udp_once_the_time_for_answers_is_over() {
    // A query that cannot be made for a server without 64-bit sizes: the search still ends, once the time for answers
    // is over. The one sent, which the Java engine's closed socket fails, is never answered.
    let mut searching = servers_with_large_files_without_them_and_without_a_udp_port();
    let search = searching.start_for("@size>5g linux", Scope::Global);
    searching.answer(&[], false);
    searching.fire(TimerKind::Query);
    searching.fire(TimerKind::Query);
    assert_eq!(searching.ended(search), None);

    searching.fire(TimerKind::End);

    assert_eq!(searching.datagrams().len(), 1, "only the server with 64-bit sizes can be asked");
    assert_eq!(searching.ended(search), Some(0));
}

// Beyond the Java engine's examples: the branches its other tests reach.

#[test]
fn should_end_a_server_search_the_server_never_answers() {
    let mut searching = Searching::new(&[]);
    let search = searching.start(Scope::Server);

    searching.fire(TimerKind::ServerSilent);

    assert_eq!(searching.ended(search), Some(0));
}

#[test]
fn should_ignore_the_timers_of_an_ended_search() {
    let mut searching = Searching::new(&[udp_server(2)]);
    let search = searching.start(Scope::Global);
    searching.answer(&[], false);
    searching.manager.stop(search, &mut searching.effects);
    let told = searching.effects.len();

    searching.fire(TimerKind::Query);

    assert_eq!(searching.effects.len(), told);
}

#[test]
fn should_rate_nothing_without_a_spam_filter() {
    let mut searching = Searching::new(&[]);
    searching.start(Scope::Server);

    searching.manager.server_results(
        &[file(1, "ubuntu (pirate).iso", 1000, 1)],
        false,
        &searching.servers,
        searching.connected.clone(),
        &mut search_manager::NoSpam,
        &mut searching.effects,
    );

    assert_eq!(searching.results()[0].spam_rating, 0);
}

#[test]
fn should_keep_an_excluded_word_in_the_bare_name() {
    let mut searching = Searching::on_kad("never");
    let search = searching.start_for("rare linux -pirate @type=cd", Scope::Kad);

    searching.kad_answer(search, vec![result(1, "Rare Linux pirate.iso", 1000, 5, Some(0))]);

    assert_eq!(searching.last_reported(kad_hash(1)).origin.bare_name, "pirate iso");
}
