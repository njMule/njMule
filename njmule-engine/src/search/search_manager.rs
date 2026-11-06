// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Searches on the eD2K servers: the connected server first over TCP, then, for global searches, the other known
//! servers over UDP, one every [`GLOBAL_INTERVAL`]. Results with the same hash are merged and their sources add up.
//! UDP answers count only from servers the search asked. The engine's task owns the manager; it carries out the
//! [`Effect`]s it hands back.

use std::collections::{HashMap, HashSet, VecDeque};
use std::net::{Ipv4Addr, SocketAddr};
use std::time::Duration;

use indexmap::IndexMap;

use crate::api::search_result::{self, SearchResult};
use crate::files::file_types;
use crate::kad::keywords;
use crate::persist::format::server_met::Server;
use crate::protocol::ed2k::file_entry::FileEntry;
use crate::protocol::ed2k::server_datagrams;
use crate::protocol::ed2k::server_messages::{self, IdChange};
use crate::protocol::packet::Packet;
use crate::protocol::tag::file_tag;
use crate::search::result_names;
use crate::search::search_expr::SearchExpr;
use crate::search::search_parser;
use crate::search::search_syntax_error::SearchSyntaxError;
use crate::server::server_list::{self, Address, ServerList};
use crate::server::server_pinger::PORT_OFFSET;

/// How long to wait for the connected server before giving up on it.
pub const SERVER_WAIT: Duration = Duration::from_secs(50);
/// Pace of the UDP queries of a global search.
pub const GLOBAL_INTERVAL: Duration = Duration::from_millis(750);
/// Time left for UDP answers after the last query.
pub const ANSWER_GRACE: Duration = Duration::from_secs(5);
/// A global search asks no further server once it holds more than this many results.
pub const GLOBAL_RESULTS: usize = 100;
/// The results one search keeps at most, against a flood.
pub const MAX_RESULTS: usize = 2_000;
/// Requests for more results of one search, as servers allow.
pub const MAX_MORE_REQUESTS: u32 = 5;

pub use crate::api::search_request::Scope;

/// Why a search cannot start.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StartError {
    Syntax(SearchSyntaxError),
    /// No server is connected, or Kad does not run.
    Unavailable(String),
}

/// A timer of a search; a timer whose token is no longer the search's was cancelled.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Timer {
    pub search: u64,
    pub kind: TimerKind,
    token: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TimerKind {
    /// The connected server did not answer in time.
    ServerSilent,
    /// The next UDP query of a global search.
    Query,
    /// The answers of a global search had their time.
    End,
}

/// What the manager asks the engine to do.
#[derive(Debug, Clone)]
pub enum Effect {
    Datagram(SocketAddr, Packet),
    Results { search: u64, changed: Vec<SearchResult> },
    Ended { search: u64, results: usize },
    After(Duration, Timer),
}

struct Search {
    expression: SearchExpr,
    scope: Scope,
    // An archive or disc image search, which the networks answer with every program.
    kind: Option<&'static str>,
    results: IndexMap<[u8; 16], SearchResult>,
    // Files given two AICH roots, which keep none.
    aich_disputed: HashSet<[u8; 16]>,
    udp_queue: VecDeque<Server>,
    asked_over_udp: HashSet<u32>,
    server_token: u64,
    udp_token: u64,
    udp_started: bool,
    global_started: bool,
    more_available: bool,
    more_requests: u32,
}

#[derive(Default)]
pub struct SearchManager {
    searches: HashMap<u64, Search>,
    next_id: u64,
    next_token: u64,
    // Searches the connected server has yet to answer, in the order we asked: its answers carry no search id, so a
    // late answer must not land in the search asked after it. Ended ones stay to keep the order.
    server_queue: VecDeque<u64>,
    // The last search sent to the server, the only one whose results it can continue.
    server_search: Option<u64>,
}

impl SearchManager {
    /// Starts a search on the connected server; the server and global searches still open end, as server answers carry
    /// no search id to tell them apart.
    pub fn start(
        &mut self,
        expression: &str,
        scope: Scope,
        id: Option<IdChange>,
        send: impl FnOnce(Packet) -> bool,
        effects: &mut Vec<Effect>,
    ) -> Result<u64, StartError> {
        let expression = search_parser::parse(expression).map_err(StartError::Syntax)?;
        if scope == Scope::Kad {
            return Err(StartError::Unavailable("Kad is not running".to_owned()));
        }
        let id = id.ok_or_else(|| StartError::Unavailable("not connected to a server".to_owned()))?;
        let open: Vec<u64> = self.searches.keys().copied().collect();
        for search in open {
            self.end(search, effects);
        }
        self.next_id = self.next_id.saturating_add(1);
        let number = self.next_id;
        let large_files = id.has(server_messages::TCP_LARGEFILES);
        let request = server_messages::search_request(&expression, large_files)
            .map_err(|e| StartError::Unavailable(format!("cannot write the search: {e}")))?;
        self.searches.insert(
            number,
            Search {
                kind: required_kind(&expression),
                expression,
                scope,
                results: IndexMap::new(),
                aich_disputed: HashSet::new(),
                udp_queue: VecDeque::new(),
                asked_over_udp: HashSet::new(),
                server_token: 0,
                udp_token: 0,
                udp_started: false,
                global_started: false,
                more_available: false,
                more_requests: 0,
            },
        );
        self.server_search = Some(number);
        send(request);
        self.wait_for_server(number, effects);
        Ok(number)
    }

    /// Whether the server said it holds more results of this search and [`Self::more`] may ask for them now: not while
    /// an answer is awaited, past the limit or once the server has taken a newer search.
    pub fn has_more(&self, search: u64) -> bool {
        self.server_search == Some(search)
            && self.searches.get(&search).is_some_and(|s| s.more_available && s.more_requests < MAX_MORE_REQUESTS)
    }

    /// Asks the server for more results; false unless [`Self::has_more`] or when the request cannot be sent.
    pub fn more(&mut self, search: u64, send: impl FnOnce(Packet) -> bool, effects: &mut Vec<Effect>) -> bool {
        if !self.has_more(search) || !send(server_messages::query_more_results()) {
            return false;
        }
        if let Some(open) = self.searches.get_mut(&search) {
            open.more_requests = open.more_requests.saturating_add(1);
            open.more_available = false;
        }
        self.wait_for_server(search, effects);
        true
    }

    pub fn stop(&mut self, search: u64, effects: &mut Vec<Effect>) {
        self.end(search, effects);
    }

    /// Results from the connected server, for the oldest search it has yet to answer.
    pub fn server_results(
        &mut self,
        files: &[FileEntry],
        more: bool,
        servers: &ServerList,
        connected: Option<Address>,
        effects: &mut Vec<Effect>,
    ) {
        let Some(number) = self.server_queue.pop_front() else {
            return;
        };
        let Some(search) = self.searches.get_mut(&number) else {
            tracing::debug!("Dropping the server's answer to an ended search");
            return;
        };
        // Its wait is over.
        search.server_token = search.server_token.wrapping_add(1);
        search.more_available = more;
        self.merge(number, files, true, effects);
        self.after_server(number, servers, connected, effects);
    }

    /// A UDP answer from the server at `ip`; ignored unless an open global search asked it.
    pub fn datagram_results(&mut self, ip: u32, files: &[FileEntry], effects: &mut Vec<Effect>) {
        let asking = self.searches.iter().find(|(_, search)| search.asked_over_udp.contains(&ip)).map(|(id, _)| *id);
        match asking {
            Some(number) => self.merge(number, files, true, effects),
            None => tracing::debug!("Unrequested search results from {}", Ipv4Addr::from(ip)),
        }
    }

    /// Ends the server and global searches when the server connection is lost.
    pub fn server_lost(&mut self, effects: &mut Vec<Effect>) {
        self.server_queue.clear();
        let open: Vec<u64> = self.searches.keys().copied().collect();
        for search in open {
            self.end(search, effects);
        }
    }

    /// A timer of a search went off.
    pub fn timer(&mut self, timer: Timer, servers: &ServerList, connected: Option<Address>, effects: &mut Vec<Effect>) {
        let Some(search) = self.searches.get(&timer.search) else {
            return;
        };
        let current = match timer.kind {
            TimerKind::ServerSilent => search.server_token,
            TimerKind::Query | TimerKind::End => search.udp_token,
        };
        if current != timer.token {
            return;
        }
        match timer.kind {
            // A server that does not answer in time is taken not to answer at all.
            TimerKind::ServerSilent => {
                if let Some(position) = self.server_queue.iter().position(|queued| *queued == timer.search) {
                    self.server_queue.remove(position);
                    self.after_server(timer.search, servers, connected, effects);
                }
            }
            TimerKind::Query => self.query_next(timer.search, effects),
            TimerKind::End => self.end(timer.search, effects),
        }
    }

    fn wait_for_server(&mut self, number: u64, effects: &mut Vec<Effect>) {
        self.server_queue.push_back(number);
        let token = self.token();
        if let Some(search) = self.searches.get_mut(&number) {
            search.server_token = token;
            effects.push(Effect::After(SERVER_WAIT, Timer { search: number, kind: TimerKind::ServerSilent, token }));
        }
    }

    fn after_server(
        &mut self,
        number: u64,
        servers: &ServerList,
        connected: Option<Address>,
        effects: &mut Vec<Effect>,
    ) {
        let Some(search) = self.searches.get(&number) else {
            return;
        };
        if search.scope == Scope::Global && !search.global_started {
            self.start_global(number, servers, connected, effects);
        } else if (search.scope == Scope::Server || search.udp_queue.is_empty())
            && !search.more_available
            && !search.udp_started
        {
            self.end(number, effects);
        }
    }

    fn start_global(
        &mut self,
        number: u64,
        servers: &ServerList,
        connected: Option<Address>,
        effects: &mut Vec<Effect>,
    ) {
        let token = self.token();
        let Some(search) = self.searches.get_mut(&number) else {
            return;
        };
        search.global_started = true;
        for server in servers.candidates() {
            // A server listens for UDP on its TCP port + 4, which does not exist above 65531.
            let has_udp_port = server.port.checked_add(PORT_OFFSET).is_some();
            if has_udp_port && connected.as_ref() != Some(&server_list::address_of(&server)) {
                search.udp_queue.push_back(server);
            }
        }
        if search.udp_queue.is_empty() {
            self.end(number, effects);
            return;
        }
        search.udp_started = true;
        search.udp_token = token;
        self.query_next(number, effects);
    }

    fn query_next(&mut self, number: u64, effects: &mut Vec<Effect>) {
        let Some(search) = self.searches.get_mut(&number) else {
            return;
        };
        if search.udp_queue.is_empty() || search.results.len() > GLOBAL_RESULTS {
            effects.push(Effect::After(
                ANSWER_GRACE,
                Timer { search: number, kind: TimerKind::End, token: search.udp_token },
            ));
            return;
        }
        let next = Timer { search: number, kind: TimerKind::Query, token: search.udp_token };
        effects.push(Effect::After(GLOBAL_INTERVAL, next));
        let Some(server) = search.udp_queue.pop_front() else {
            return;
        };
        let flags = u32::try_from(server.udp_flags().unwrap_or(0)).unwrap_or(0);
        let request = match server_datagrams::search_request(&search.expression, flags) {
            Ok(Some(request)) => request,
            Ok(None) => return,
            Err(e) => {
                tracing::debug!("Cannot write a global search: {e}");
                return;
            }
        };
        let Some(port) = server.port.checked_add(PORT_OFFSET) else {
            return;
        };
        search.asked_over_udp.insert(server.ip);
        effects.push(Effect::Datagram(SocketAddr::from((Ipv4Addr::from(server.ip), port)), request));
    }

    // Answers of the connected server, or of a server over UDP: what they name, merged into the search.
    fn merge(&mut self, number: u64, files: &[FileEntry], add_counts: bool, effects: &mut Vec<Effect>) {
        let Some(search) = self.searches.get_mut(&number) else {
            return;
        };
        let mut changed = Vec::new();
        for file in files {
            let (Some(name), Some(size)) = (file.name(), file.size()) else {
                continue;
            };
            let reported = SearchResult {
                media: file.media(),
                aich_hash: file.aich_hash(),
                ..SearchResult::new(
                    file.hash,
                    name,
                    size,
                    file.sources(),
                    Some(file.complete_sources()),
                    file.file_type(),
                )
            };
            let shown = as_shown(reported);
            if !search_result::is_downloadable(&shown.name, shown.size) {
                continue;
            }
            if search.kind.is_some_and(|kind| !file_types::is_a(kind, &shown.name)) {
                continue;
            }
            let known = search.results.get(&shown.hash).cloned();
            if known.is_none() && search.results.len() >= MAX_RESULTS {
                continue;
            }
            if known.as_ref().is_some_and(|known| known.size != shown.size) {
                continue;
            }
            let merged = match &known {
                Some(known) => {
                    let aich = agreed_aich(&mut search.aich_disputed, known, &shown);
                    SearchResult { aich_hash: aich, ..result_names::found_again(known, &shown, add_counts) }
                }
                None => shown,
            };
            if known.as_ref() != Some(&merged) {
                search.results.insert(merged.hash, merged.clone());
                changed.push(merged);
            }
        }
        if !changed.is_empty() {
            effects.push(Effect::Results { search: number, changed });
        }
    }

    fn end(&mut self, number: u64, effects: &mut Vec<Effect>) {
        let Some(search) = self.searches.remove(&number) else {
            return;
        };
        effects.push(Effect::Ended { search: number, results: search.results.len() });
    }

    fn token(&mut self) -> u64 {
        self.next_token = self.next_token.wrapping_add(1);
        self.next_token
    }
}

// Archives and disc images every match must be: asked for as programs, the others are left out here.
fn required_kind(expression: &SearchExpr) -> Option<&'static str> {
    match expression {
        SearchExpr::And(left, right) => required_kind(left).or_else(|| required_kind(right)),
        SearchExpr::Not(left, _) => required_kind(left),
        SearchExpr::StringAttr { tag, value } if *tag == file_tag::FILETYPE => match value.as_str() {
            file_types::ARCHIVE => Some(file_types::ARCHIVE),
            file_types::DISC_IMAGE => Some(file_types::DISC_IMAGE),
            _ => None,
        },
        _ => None,
    }
}

/// The longest word, as Kad indexes words, that every match of `expression` has: joined by AND or on the left of NOT,
/// preferably outside quotes; an error saying why there is none.
pub fn kad_keyword(expression: &SearchExpr) -> Result<String, String> {
    let mut plain = Vec::new();
    let mut quoted = Vec::new();
    let alternatives = required_words(expression, &mut plain, &mut quoted);
    let words = if plain.is_empty() { quoted } else { plain };
    let mut best: Option<String> = None;
    for word in words {
        if best.as_ref().is_none_or(|best| word.chars().count() > best.chars().count()) {
            best = Some(word);
        }
    }
    best.ok_or_else(|| {
        if alternatives {
            "Kad cannot search for words joined by OR: search for each on its own".to_owned()
        } else {
            "Kad needs a word of at least three letters to search for".to_owned()
        }
    })
}

// Adds the words every match has; true when alternatives (OR) were left out.
fn required_words(expression: &SearchExpr, plain: &mut Vec<String>, quoted: &mut Vec<String>) -> bool {
    match expression {
        SearchExpr::And(left, right) => {
            let left = required_words(left, plain, quoted);
            let right = required_words(right, plain, quoted);
            left || right
        }
        SearchExpr::Not(left, _) => required_words(left, plain, quoted),
        SearchExpr::Or(..) => true,
        SearchExpr::Keyword(text) => {
            let words = keywords::of(text);
            if text.starts_with('"') {
                quoted.extend(words)
            } else {
                plain.extend(words)
            }
            false
        }
        SearchExpr::StringAttr { .. } | SearchExpr::NumericAttr { .. } => false,
    }
}

// Names are shown and printed: no terminal escapes, line breaks or direction overrides that would hide the real
// extension. The publisher's type may lie or be no type at all: the name's extension tells first, and a reported type
// counts only when it is a real one.
fn as_shown(file: SearchResult) -> SearchResult {
    let name: String = file.name.chars().filter(|c| !unshown(*c)).collect();
    let name = crate::util::white_space::strip(&name).to_owned();
    let file_type = file_types::of(&name)
        .map(str::to_owned)
        .or_else(|| file.file_type.clone().filter(|kind| file_types::is_known(kind)));
    SearchResult { name, file_type, ..file }
}

// Control characters, and the embeddings, overrides and isolates that reorder text.
fn unshown(c: char) -> bool {
    c.is_control() || ('\u{202A}'..='\u{202E}').contains(&c) || ('\u{2066}'..='\u{2069}').contains(&c)
}

// Publishers that disagree on a file's AICH root leave it none for the rest of the search: a wrong one would keep its
// download from completing.
fn agreed_aich(disputed: &mut HashSet<[u8; 16]>, known: &SearchResult, file: &SearchResult) -> Option<[u8; 20]> {
    if disputed.contains(&file.hash) {
        return None;
    }
    if let (Some(a), Some(b)) = (known.aich_hash, file.aich_hash)
        && a != b
    {
        disputed.insert(file.hash);
        return None;
    }
    known.aich_hash.or(file.aich_hash)
}
